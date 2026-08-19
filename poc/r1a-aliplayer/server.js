#!/usr/bin/env node
/**
 * R1a-Ali POC —— 静态托管 + PlayAuth 签发 + 客户端日志回收
 *
 * 零依赖，只用 node 标准库。跑法： PORT=8081 node server.js
 *
 * 四件事：
 *   1. 托管 index.html / vendor/*
 *   2. GET  /playauth?videoId=xxx  调阿里云 VOD 的 GetVideoPlayAuth 签发播放凭证
 *      —— 私有加密在 Web 端【只能】走 VidAuth（阿里云文档：UrlSource ❌），
 *         所以这个端点是 mode=vid 那条路的必经之地
 *   3. POST /log        收测试页面回传的诊断（微信里打不开开发者工具）
 *   4. GET  /admin/logs 手机测、电脑看
 *
 * 【AccessKey 只从环境变量读，不进代码、不进 git】：
 *     ALIYUN_ACCESS_KEY_ID
 *     ALIYUN_ACCESS_KEY_SECRET
 *     ALIYUN_VOD_REGION       默认 cn-shanghai
 * 建议用只授权点播（VOD）的 RAM 子账号，测完即删。
 */
'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8081);
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = __dirname;
const LOG_DIR = path.join(ROOT, 'logs');

const AK_ID = process.env.ALIYUN_ACCESS_KEY_ID || '';
const AK_SECRET = process.env.ALIYUN_ACCESS_KEY_SECRET || '';
const VOD_REGION = process.env.ALIYUN_VOD_REGION || 'cn-shanghai';

fs.mkdirSync(LOG_DIR, { recursive: true });
const CLIENT_LOG = path.join(LOG_DIR, 'client.jsonl');
const AUTH_LOG = path.join(LOG_DIR, 'playauth.jsonl');

const RING_MAX = 2000;
const ring = [];
function remember(e) { ring.push(e); if (ring.length > RING_MAX) ring.splice(0, ring.length - RING_MAX); }

// 只列真会被请求到的类型：静态白名单只放行 index.html 与 vendor/，
// 而 vendor 下就是 aliplayer 的 js / css / img（16 张 png，皮肤 CSS 里以 ./img/ 引用，
// 不本地托管的话微信里控件图标会全部 404）。这里不留用不到的条目。
const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
};

const ts = () => new Date().toISOString();
const append = (f, o) => fs.appendFile(f, JSON.stringify(o) + '\n', () => {});
const clientIp = (req) =>
  req.headers['x-forwarded-for'] || req.headers['x-real-ip'] || (req.socket && req.socket.remoteAddress) || '';

function sniffKernel(ua) {
  if (!ua) return 'unknown';
  const u = String(ua);
  const wechat = /MicroMessenger/i.test(u);
  let k = 'unknown';
  if (/XWEB\/(\d+)/i.test(u)) k = 'XWEB/' + RegExp.$1;
  else if (/MQQBrowser|TBS\/(\d+)/i.test(u)) k = 'X5(TBS)';
  else if (/iPhone|iPad|iPod/i.test(u)) k = 'iOS-WKWebView';
  else if (/Android/i.test(u)) k = 'Android-system';
  return (wechat ? 'WeChat ' : '') + k;
}

// ---------------------------------------------------------------------------
// 阿里云 RPC 签名（v1.0，HMAC-SHA1）—— 不引 SDK，避免这个一次性验证物拖进依赖
// ---------------------------------------------------------------------------
function percentEncode(s) {
  return encodeURIComponent(s)
    .replace(/\+/g, '%20')
    .replace(/\*/g, '%2A')
    .replace(/%7E/g, '~');
}

function signedVodUrl(action, extraParams) {
  const params = Object.assign(
    {
      Action: action,
      Format: 'JSON',
      Version: '2017-03-21',
      AccessKeyId: AK_ID,
      SignatureMethod: 'HMAC-SHA1',
      SignatureVersion: '1.0',
      SignatureNonce: crypto.randomBytes(16).toString('hex'),
      Timestamp: new Date().toISOString().replace(/\.\d{3}/, ''),
      RegionId: VOD_REGION,
    },
    extraParams
  );

  const canonical = Object.keys(params)
    .sort()
    .map((k) => percentEncode(k) + '=' + percentEncode(params[k]))
    .join('&');

  const stringToSign = 'GET&' + percentEncode('/') + '&' + percentEncode(canonical);
  const signature = crypto.createHmac('sha1', AK_SECRET + '&').update(stringToSign).digest('base64');

  return `https://vod.${VOD_REGION}.aliyuncs.com/?Signature=${percentEncode(signature)}&${canonical}`;
}

function httpsGetJson(url) {
  return new Promise((resolve, reject) => {
    https
      .get(url, { timeout: 10000 }, (res) => {
        let body = '';
        res.on('data', (c) => (body += c));
        res.on('end', () => {
          try { resolve({ status: res.statusCode, json: JSON.parse(body) }); }
          catch (e) { resolve({ status: res.statusCode, json: { raw: body.slice(0, 500) } }); }
        });
      })
      .on('timeout', function () { this.destroy(new Error('timeout')); })
      .on('error', reject);
  });
}

// ---------------------------------------------------------------------------
// GET /playauth?videoId=xxx
// ---------------------------------------------------------------------------
async function handlePlayAuth(req, res, u) {
  const videoId = u.searchParams.get('videoId') || '';
  const entry = { t: ts(), kind: 'playauth', videoId, ip: clientIp(req), ua: req.headers['user-agent'] || '' };

  if (!AK_ID || !AK_SECRET) {
    entry.error = 'no-credentials';
    remember(entry); append(AUTH_LOG, entry);
    console.error('[AUTH] 缺少 AccessKey 环境变量');
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({
      error: '服务端没有配置阿里云 AccessKey',
      how: '在 systemd 的 Environment 或 .env 里设 ALIYUN_ACCESS_KEY_ID / ALIYUN_ACCESS_KEY_SECRET 后重启服务',
    }));
  }
  if (!videoId) {
    res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ error: '缺少 videoId 参数' }));
  }

  try {
    const url = signedVodUrl('GetVideoPlayAuth', { VideoId: videoId });
    const r = await httpsGetJson(url);
    const j = r.json || {};

    if (r.status !== 200 || !j.PlayAuth) {
      entry.error = j.Code || ('HTTP ' + r.status);
      entry.message = j.Message || '';
      remember(entry); append(AUTH_LOG, entry);
      console.error('[AUTH] 失败', r.status, JSON.stringify(j).slice(0, 300));
      res.writeHead(502, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify({ error: '阿里云返回失败', code: j.Code, message: j.Message, requestId: j.RequestId }));
    }

    entry.ok = true;
    entry.requestId = j.RequestId;
    entry.title = j.VideoMeta && j.VideoMeta.Title;
    // 不记 PlayAuth 本身：它是凭证
    remember(entry); append(AUTH_LOG, entry);
    console.log(`[AUTH] ${entry.t} videoId=${videoId} ok requestId=${j.RequestId}`);

    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({
      playAuth: j.PlayAuth,
      videoMeta: j.VideoMeta || null,
      requestId: j.RequestId,
    }));
  } catch (e) {
    entry.error = String(e);
    remember(entry); append(AUTH_LOG, entry);
    console.error('[AUTH] 异常', e);
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: String(e) }));
  }
}

// ---------------------------------------------------------------------------
// GET /videoinfo?videoId=xxx —— 诊断用：这条视频到底是不是加密的 HLS
//
// 契约 §1 的挑选规则是 Format=="m3u8" && Encrypt==true。转码模板组若没开加密，
// 转出来就是明文流，拿它测 ① 等于什么都没验 —— 上真机前先在这里确认一次，
// 比在手机上对着"能播"傻乐一轮划算。
// ---------------------------------------------------------------------------
async function handleVideoInfo(req, res, u) {
  const videoId = u.searchParams.get('videoId') || '';
  if (!AK_ID || !AK_SECRET) {
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ error: '没有配置 AccessKey' }));
  }
  if (!videoId) {
    res.writeHead(400, { 'Content-Type': 'application/json; charset=utf-8' });
    return res.end(JSON.stringify({ error: '缺少 videoId' }));
  }
  try {
    // ResultType=Multiple：不加这个，私有加密视频会被直接拒掉
    // （Forbidden.AliyunVoDEncryption：only the AliyunVoDEncryption stream exists）
    const r = await httpsGetJson(signedVodUrl('GetPlayInfo', { VideoId: videoId, ResultType: 'Multiple' }));
    const j = r.json || {};
    const list = (j.PlayInfoList && j.PlayInfoList.PlayInfo) || [];
    const summary = list.map((p) => ({
      Format: p.Format,
      Encrypt: p.Encrypt,
      EncryptType: p.EncryptType,
      Definition: p.Definition,
      Width: p.Width, Height: p.Height,
      Duration: p.Duration,
      Size: p.Size,
      JobId: p.JobId,
      hasPlayURL: !!p.PlayURL,   // 不回传 URL 本身
    }));
    res.writeHead(r.status === 200 ? 200 : 502, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({
      videoBase: j.VideoBase ? { Title: j.VideoBase.Title, Status: j.VideoBase.Status, Duration: j.VideoBase.Duration } : null,
      streams: summary,
      code: j.Code, message: j.Message, requestId: j.RequestId,
    }, null, 1));
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: String(e) }));
  }
}

// ---------------------------------------------------------------------------
function handleLog(req, res) {
  let body = '', tooBig = false;
  req.on('data', (c) => { body += c; if (body.length > 512 * 1024) { tooBig = true; req.destroy(); } });
  req.on('end', () => {
    if (tooBig) return;
    let payload;
    try { payload = JSON.parse(body); } catch (e) { payload = { parseError: String(e), raw: body.slice(0, 2000) }; }
    const entry = { t: ts(), kind: 'client', ip: clientIp(req), ua: req.headers['user-agent'] || '',
                    kernel: sniffKernel(req.headers['user-agent']), payload };
    remember(entry); append(CLIENT_LOG, entry);
    const events = Array.isArray(payload && payload.events) ? payload.events : [payload];
    console.log(`\n[CLNT] ${entry.t}  ${entry.kernel}  session=${(payload && payload.session) || '-'}`);
    for (const ev of events) console.log('       ' + JSON.stringify(ev));
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*' });
    res.end();
  });
}

const ADMIN_HTML = `<!doctype html><meta charset="utf-8"><title>R1a-Ali 日志</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;margin:0;background:#111;color:#ddd}
 header{position:sticky;top:0;background:#1c1c1c;padding:8px 12px;border-bottom:1px solid #333;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
 h1{font-size:14px;margin:0;color:#fff}
 .e{padding:6px 12px;border-bottom:1px solid #222;white-space:pre-wrap;word-break:break-all}
 .playauth{background:#0d2818}.client{background:#111}
 .tag{display:inline-block;padding:0 6px;border-radius:3px;font-weight:bold;margin-right:6px}
 .tag.playauth{background:#2e7d32;color:#fff}.tag.client{background:#37474f;color:#fff}
 .t{color:#888}
 button,label{font:inherit;color:#ddd;background:#333;border:1px solid #555;border-radius:4px;padding:3px 8px;cursor:pointer}
</style>
<header><h1>R1a-Ali 日志</h1>
 <label><input type="checkbox" id="auto" checked> 自动刷新 2s</label>
 <button id="clear">清屏</button><span id="count" class="t"></span></header>
<div id="out"></div>
<script>
let since=0; const out=document.getElementById('out');
async function tick(){
  if(!document.getElementById('auto').checked) return;
  // 必须用【绝对路径】：本页地址是 /admin/logs，相对路径 "admin/logs.json" 会按
  // 目录 /admin/ 解析成 /admin/admin/logs.json → 404，页面永远空着（实测踩过）
  const r=await fetch('/admin/logs.json?since='+since); const d=await r.json(); since=d.next;
  for(const e of d.items){
    const div=document.createElement('div'); div.className='e '+e.kind;
    div.innerHTML='<span class="tag '+e.kind+'">'+e.kind.toUpperCase()+'</span><span class="t">'+e.t+'  '+(e.kernel||'')+'</span>\\n';
    div.appendChild(document.createTextNode(e.kind==='client'?JSON.stringify(e.payload,null,1):JSON.stringify(e,null,1)));
    out.appendChild(div);
  }
  document.getElementById('count').textContent='共 '+since+' 条';
  window.scrollTo(0,document.body.scrollHeight);
}
document.getElementById('clear').onclick=()=>{out.innerHTML='';};
setInterval(tick,2000); tick();
</script>`;

function notFound(res, what) {
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('404 ' + what);
}

function serveStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  if (rel === '/guide') rel = '/guide.html';   // 测试指引，手机上边测边看
  const abs = path.normalize(path.join(ROOT, rel));
  const allowed = abs === path.join(ROOT, 'index.html') ||
                  abs === path.join(ROOT, 'guide.html') ||
                  abs.startsWith(path.join(ROOT, 'vendor') + path.sep);
  if (!allowed) return notFound(res, rel);
  fs.stat(abs, (err, st) => {
    if (err || !st.isFile()) return notFound(res, rel);
    const ext = path.extname(abs).toLowerCase();
    const headers = { 'Content-Type': MIME[ext] || 'application/octet-stream', 'Content-Length': st.size };
    if (ext === '.html') headers['Cache-Control'] = 'no-store, no-cache, must-revalidate';
    res.writeHead(200, headers);
    fs.createReadStream(abs).pipe(res);
  });
}

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
  const p = u.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
                         'Access-Control-Allow-Headers': 'Content-Type' });
    return res.end();
  }
  // 连通性诊断：iOS 上 https 连不上时，用它把「请求到没到这台机器」变成服务端事实，
  // 而不是靠用户描述"打不开 / 空白"。每次访问都记日志（含 IP、UA、走的是域名还是裸 IP）。
  if (p === '/plain') {
    const entry = {
      t: ts(), kind: 'plain', host: req.headers.host || '', proto: req.headers['x-forwarded-proto'] || 'http',
      ip: clientIp(req), ua: req.headers['user-agent'] || '', kernel: sniffKernel(req.headers['user-agent']),
    };
    remember(entry); append(CLIENT_LOG, entry);
    console.log(`[PLAIN] ${entry.t} host=${entry.host} ip=${entry.ip} ${entry.kernel}`);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(
      '<!doctype html><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width,initial-scale=1">' +
      '<title>连通性诊断</title>' +
      '<body style="margin:0;background:#0b3d1e;color:#fff;font:16px/1.7 -apple-system,sans-serif">' +
      '<div style="padding:40px 20px;text-align:center">' +
      '<div style="font-size:64px;line-height:1">✅</div>' +
      '<h1 style="font-size:26px;margin:14px 0">HTTP 通了</h1>' +
      '<p style="opacity:.85">这台手机能访问到服务器。<br>说明网络路径没问题，问题只在 HTTPS/TLS。</p>' +
      '<div style="margin-top:24px;padding:14px;background:rgba(0,0,0,.35);border-radius:8px;' +
      'text-align:left;font:13px/1.7 ui-monospace,Menlo,monospace;word-break:break-all">' +
      'Host: ' + String(entry.host).replace(/[<>&]/g, '') + '<br>' +
      'IP: ' + String(entry.ip).replace(/[<>&]/g, '') + '<br>' +
      'UA: ' + String(entry.ua).replace(/[<>&]/g, '').slice(0, 200) +
      '</div>' +
      '<p style="margin-top:22px;opacity:.7;font-size:14px">这次访问已记进服务端日志。</p>' +
      '</div></body>'
    );
  }

  if (p === '/playauth') return handlePlayAuth(req, res, u);
  if (p === '/videoinfo') return handleVideoInfo(req, res, u);
  if (p === '/log' && req.method === 'POST') return handleLog(req, res);
  if (p === '/admin/logs') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(ADMIN_HTML);
  }
  if (p === '/admin/logs.json') {
    const since = Number(u.searchParams.get('since') || 0);
    const items = ring.slice(Math.max(0, since - (RING_MAX - ring.length)));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ items, next: since + items.length }));
  }
  if (p === '/health') { res.writeHead(200, { 'Content-Type': 'text/plain' }); return res.end('ok'); }

  serveStatic(req, res, p);
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') { console.error(`\n端口 ${PORT} 被占用，换一个： PORT=8082 node server.js\n`); process.exit(1); }
  throw e;
});

server.listen(PORT, HOST, () => {
  console.log('R1a-Ali POC server');
  console.log('  监听        http://' + HOST + ':' + PORT);
  console.log('  静态根      ' + ROOT);
  console.log('  AccessKey   ' + (AK_ID ? ' 已配置（' + AK_ID.slice(0, 4) + '****）' : ' *** 未配置：mode=vid 这条路不可用 ***'));
  console.log('  VOD 区域    ' + VOD_REGION);
  console.log('  日志        ' + CLIENT_LOG);
  console.log('              ' + AUTH_LOG);
  console.log('  日志页面    /admin/logs');
  console.log('');
});
