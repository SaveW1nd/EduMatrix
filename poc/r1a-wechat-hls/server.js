#!/usr/bin/env node
/**
 * R1a POC —— 静态托管 + mock 密钥端点 + 客户端日志回收
 *
 * 零依赖，只用 node 标准库。跑法： node server.js  （PORT=8080）
 *
 * 它做四件事：
 *   1. 托管 index.html / media/*.m3u8 / media/*.ts / vendor/*
 *      —— .m3u8 与 .ts 的 Content-Type 必须对，否则某些内核直接不认
 *   2. GET  /key   返回 16 字节密钥，并把【完整请求】打进日志
 *      —— 验证项 ② 的观测点：URL、query、全部请求头、UA、时间戳
 *   3. POST /log   收测试页面回传的诊断信息
 *      —— 微信里打不开开发者工具，这是唯一的观察窗口
 *   4. GET  /admin/logs  在电脑浏览器上实时看手机打回来的日志
 *      —— 手机测、电脑看。没有它，你在手机上只能看到"播了/没播"
 */
'use strict';

const http = require('http');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

const PORT = Number(process.env.PORT || 8080);
const HOST = process.env.HOST || '0.0.0.0';
const ROOT = __dirname;
const MEDIA_DIR = path.join(ROOT, process.env.MEDIA_DIR || 'media');
const KEY_FILE = path.join(MEDIA_DIR, 'enc.key');
const LOG_DIR = path.join(ROOT, 'logs');

fs.mkdirSync(LOG_DIR, { recursive: true });

const KEY_LOG = path.join(LOG_DIR, 'key-requests.jsonl');
const CLIENT_LOG = path.join(LOG_DIR, 'client.jsonl');

// 内存环形缓冲，供 /admin/logs 读取（重启即清空，这是个一次性验证物）
const RING_MAX = 2000;
const ring = [];
function remember(entry) {
  ring.push(entry);
  if (ring.length > RING_MAX) ring.splice(0, ring.length - RING_MAX);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/mp2t',
  '.mp4': 'video/mp4',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
  '.map': 'application/json; charset=utf-8',
};

function ts() {
  return new Date().toISOString();
}

function append(file, obj) {
  fs.appendFile(file, JSON.stringify(obj) + '\n', () => {});
}

function clientIp(req) {
  return (
    req.headers['x-forwarded-for'] ||
    req.headers['x-real-ip'] ||
    (req.socket && req.socket.remoteAddress) ||
    ''
  );
}

/** UA 粗判内核。只用来给日志加个标签，判定还得靠人看截图确认。 */
function sniffKernel(ua) {
  if (!ua) return 'unknown';
  const u = String(ua);
  const wechat = /MicroMessenger/i.test(u);
  let kernel = 'unknown';
  if (/XWEB\/(\d+)/i.test(u)) kernel = 'XWEB/' + RegExp.$1;
  else if (/MQQBrowser|TBS\/(\d+)/i.test(u)) kernel = 'X5(TBS)';
  else if (/iPhone|iPad|iPod/i.test(u)) kernel = 'iOS-WKWebView';
  else if (/Android/i.test(u)) kernel = 'Android-system';
  return (wechat ? 'WeChat ' : '') + kernel;
}

// ---------------------------------------------------------------------------
// GET /key —— 验证项 ② 的观测点
// ---------------------------------------------------------------------------
function handleKey(req, res, u) {
  const query = {};
  for (const [k, v] of u.searchParams) query[k] = v;

  const entry = {
    t: ts(),
    kind: 'key',
    method: req.method,
    url: req.url, // 原始 URL，query 有没有被削掉一眼可见
    query,
    hasToken: Object.prototype.hasOwnProperty.call(query, 'token'),
    ip: clientIp(req),
    ua: req.headers['user-agent'] || '',
    kernel: sniffKernel(req.headers['user-agent']),
    range: req.headers['range'] || null, // 原生播放器取 key 有时会带 Range
    referer: req.headers['referer'] || null,
    origin: req.headers['origin'] || null,
    headers: req.headers, // 全量请求头：谁改写了什么，只有这里看得见
  };
  remember(entry);
  append(KEY_LOG, entry);

  console.log(
    `\n[KEY ] ${entry.t}  ${entry.kernel}\n` +
      `       url    = ${entry.url}\n` +
      `       query  = ${JSON.stringify(entry.query)}   token在? ${entry.hasToken ? 'YES' : '*** NO ***'}\n` +
      `       ua     = ${entry.ua}\n` +
      `       referer= ${entry.referer}\n` +
      `       headers= ${JSON.stringify(entry.headers)}`
  );

  let key;
  try {
    key = fs.readFileSync(KEY_FILE);
  } catch (e) {
    res.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('密钥文件不存在：' + KEY_FILE + '\n先跑 ./make-hls.sh <BASE_URL>');
    console.error('[KEY ] 密钥文件缺失', KEY_FILE);
    return;
  }

  // 注意：这里【故意】不做任何鉴权。POC 只验"参数能不能活着到达"，
  // 不验鉴权逻辑 —— 真实的校验链见契约 §1 与 03-课程与视频 §8.2。
  res.writeHead(200, {
    'Content-Type': 'application/octet-stream',
    'Content-Length': key.length,
    'Cache-Control': 'no-store, no-cache, must-revalidate',
    'Access-Control-Allow-Origin': '*',
  });
  res.end(key);
}

// ---------------------------------------------------------------------------
// POST /log —— 页面回传的诊断信息
// ---------------------------------------------------------------------------
function handleLog(req, res) {
  let body = '';
  let tooBig = false;
  req.on('data', (c) => {
    body += c;
    if (body.length > 512 * 1024) {
      tooBig = true;
      req.destroy();
    }
  });
  req.on('end', () => {
    if (tooBig) return;
    let payload;
    try {
      payload = JSON.parse(body);
    } catch (e) {
      payload = { parseError: String(e), raw: body.slice(0, 2000) };
    }
    const entry = {
      t: ts(),
      kind: 'client',
      ip: clientIp(req),
      ua: req.headers['user-agent'] || '',
      kernel: sniffKernel(req.headers['user-agent']),
      payload,
    };
    remember(entry);
    append(CLIENT_LOG, entry);

    const events = Array.isArray(payload && payload.events) ? payload.events : [payload];
    console.log(`\n[CLNT] ${entry.t}  ${entry.kernel}  session=${(payload && payload.session) || '-'}`);
    for (const ev of events) {
      console.log('       ' + JSON.stringify(ev));
    }

    res.writeHead(204, { 'Access-Control-Allow-Origin': '*' });
    res.end();
  });
}

// ---------------------------------------------------------------------------
// GET /admin/logs —— 电脑上看手机的日志
// ---------------------------------------------------------------------------
const ADMIN_HTML = `<!doctype html><meta charset="utf-8">
<title>R1a POC 日志</title>
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
 body{font:13px/1.5 ui-monospace,Menlo,Consolas,monospace;margin:0;background:#111;color:#ddd}
 header{position:sticky;top:0;background:#1c1c1c;padding:8px 12px;border-bottom:1px solid #333;display:flex;gap:12px;align-items:center;flex-wrap:wrap}
 h1{font-size:14px;margin:0;color:#fff}
 .e{padding:6px 12px;border-bottom:1px solid #222;white-space:pre-wrap;word-break:break-all}
 .key{background:#0d2818}.client{background:#111}
 .tag{display:inline-block;padding:0 6px;border-radius:3px;font-weight:bold;margin-right:6px}
 .tag.key{background:#2e7d32;color:#fff}.tag.client{background:#37474f;color:#fff}
 .lv-error{color:#ff8a80}.lv-warn{color:#ffd180}.lv-ok{color:#a5d6a7}
 .t{color:#888}
 button,label{font:inherit;color:#ddd;background:#333;border:1px solid #555;border-radius:4px;padding:3px 8px;cursor:pointer}
</style>
<header>
 <h1>R1a POC 日志</h1>
 <label><input type="checkbox" id="auto" checked> 自动刷新 2s</label>
 <label><input type="checkbox" id="onlyKey"> 只看 /key</label>
 <button id="clear">清屏</button>
 <span id="count" class="t"></span>
</header>
<div id="out"></div>
<script>
let since = 0, paused = false;
const out = document.getElementById('out');
async function tick(){
  if (!document.getElementById('auto').checked) return;
  const r = await fetch('/admin/logs.json?since=' + since);
  const d = await r.json();
  since = d.next;
  const onlyKey = document.getElementById('onlyKey').checked;
  for (const e of d.items) {
    if (onlyKey && e.kind !== 'key') continue;
    const div = document.createElement('div');
    div.className = 'e ' + e.kind;
    let text;
    if (e.kind === 'key') {
      text = 'url=' + e.url + '\\n  query=' + JSON.stringify(e.query)
           + '   token在? ' + (e.hasToken ? 'YES' : '*** NO ***')
           + '\\n  ua=' + e.ua
           + '\\n  headers=' + JSON.stringify(e.headers, null, 1);
    } else {
      text = JSON.stringify(e.payload, null, 1);
    }
    div.innerHTML = '<span class="tag ' + e.kind + '">' + e.kind.toUpperCase() + '</span>'
      + '<span class="t">' + e.t + '  ' + (e.kernel||'') + '</span>\\n' ;
    div.appendChild(document.createTextNode(text));
    out.appendChild(div);
  }
  document.getElementById('count').textContent = '共 ' + since + ' 条';
  window.scrollTo(0, document.body.scrollHeight);
}
document.getElementById('clear').onclick = () => { out.innerHTML = ''; };
setInterval(tick, 2000); tick();
</script>`;

// ---------------------------------------------------------------------------
// 静态文件
// ---------------------------------------------------------------------------
function serveStatic(req, res, pathname) {
  let rel = decodeURIComponent(pathname);
  if (rel === '/' || rel === '') rel = '/index.html';

  // 只允许 index.html、media/、vendor/ 三处，且防目录穿越
  const abs = path.normalize(path.join(ROOT, rel));
  const allowed =
    abs === path.join(ROOT, 'index.html') ||
    abs.startsWith(MEDIA_DIR + path.sep) ||
    abs.startsWith(path.join(ROOT, 'vendor') + path.sep);
  if (!allowed) return notFound(res, rel);

  // enc.key / enc.keyinfo 不走静态 —— 它们只能经 /key 出去
  if (/enc\.(key|keyinfo)$/.test(abs)) return notFound(res, rel);

  fs.stat(abs, (err, st) => {
    if (err || !st.isFile()) return notFound(res, rel);
    const ext = path.extname(abs).toLowerCase();
    const headers = {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Content-Length': st.size,
      'Access-Control-Allow-Origin': '*',
    };
    // m3u8 与页面不缓存：改一版就要立刻在手机上生效，否则你会对着旧版本调半天
    if (ext === '.m3u8' || ext === '.html') {
      headers['Cache-Control'] = 'no-store, no-cache, must-revalidate';
    }
    res.writeHead(200, headers);
    fs.createReadStream(abs).pipe(res);
  });
}

function notFound(res, what) {
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('404 ' + what);
}

// ---------------------------------------------------------------------------
const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://' + (req.headers.host || 'localhost'));
  const p = u.pathname;

  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,POST,OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    });
    return res.end();
  }

  if (p === '/key') return handleKey(req, res, u);
  if (p === '/log' && req.method === 'POST') return handleLog(req, res);

  if (p === '/admin/logs') {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(ADMIN_HTML);
  }
  if (p === '/admin/logs.json') {
    const since = Number(u.searchParams.get('since') || 0);
    // ring 会截断，since 落在被截掉的区间时从头给（一次性工具，够用）
    const items = ring.slice(Math.max(0, since - (RING_MAX - ring.length)));
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ items, next: since + items.length }));
  }
  if (p === '/health') {
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    return res.end('ok');
  }

  serveStatic(req, res, p);
});

server.on('error', (e) => {
  if (e.code === 'EADDRINUSE') {
    console.error(
      `\n端口 ${PORT} 被占用。换一个： PORT=8099 node server.js\n` +
        `注意：换端口后 key URI 里的端口也变了，要用新地址重跑 ./make-hls.sh\n`
    );
    process.exit(1);
  }
  throw e;
});

server.listen(PORT, HOST, () => {
  console.log('R1a POC server');
  console.log('  监听      http://' + HOST + ':' + PORT);
  console.log('  静态根    ' + ROOT);
  console.log('  媒体目录  ' + MEDIA_DIR + (fs.existsSync(MEDIA_DIR) ? '' : '   *** 不存在，先跑 ./make-hls.sh ***'));
  console.log('  密钥文件  ' + KEY_FILE + (fs.existsSync(KEY_FILE) ? '' : '   *** 不存在，先跑 ./make-hls.sh ***'));
  console.log('  日志      ' + KEY_LOG);
  console.log('            ' + CLIENT_LOG);
  console.log('  日志页面  /admin/logs   （手机测、电脑看）');
  console.log('');
});
