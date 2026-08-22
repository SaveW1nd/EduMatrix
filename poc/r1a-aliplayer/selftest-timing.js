#!/usr/bin/env node
/**
 * ⑤ 前端计时的自检 —— 跑法： node selftest-timing.js
 *
 * 【它测的是 index.html 里那段【真代码】，不是一份抄写】
 * 直接把「⑤ 前端计时」到「vitals」之间那一段原文抠出来放沙箱跑。
 * 抄一份来测的话测的是抄件，改了 index.html 而忘了改抄件，这里照样全绿。
 *
 * 沙箱把时间捏在手里：定时器不真跑、由本脚本一拍一拍喂，Date.now() 受控。
 * 于是「锁屏 5 分钟」= 【停掉定时器、把墙钟推进 300 秒、再启动】，
 * 这正是手机冻结/暂停页面时的行为。
 *
 * 场景表里【每一条都对应一次真机实测或一次被否掉的方案】，不是凑数：
 *   场景三  = 安卓实测那 34.8 秒（第一版栽在这里）
 *   场景六  = 需方指出的拖动漏洞（第二版栽在这里）
 *   场景七  = 往回拖会让时长变负（第二版栽在这里）
 *   场景八  = 重看（第二版会漏算）
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HTML = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const BEGIN = '// ⑤ 前端计时（F-113 验证）';
const END = '// ---------- vitals ----------';
const b = HTML.indexOf(BEGIN), e = HTML.indexOf(END);
if (b < 0 || e < 0 || e < b) { console.error('✗ 在 index.html 里找不到计时那一段'); process.exit(1); }
const SRC = HTML.slice(b, e);

// ---------------------------------------------------------------- 沙箱
let NOW = 1_700_000_000_000;
let ticker = null;                       // 累加定时器（TICK_MS）
let hbTimer = null;                      // 心跳定时器（HB_MS）
const beats = [];

const video = { currentTime: 0, paused: true, playbackRate: 1 };
const ctx = {
  activeVideo: video,
  player: null,
  document: { hidden: false, addEventListener() {} },
  window: { addEventListener() {} },
  Date: { now: () => NOW },
  Math, Number,
  Q: { get: () => null },                // ?hb=all 关闭
  flush() {},
  setInterval: (fn, ms) => {
    const t = { fn, ms };
    if (ms >= 5000) hbTimer = t; else ticker = t;
    return t;
  },
  clearInterval: (t) => { if (t === ticker) ticker = null; },
  L: { hi: (_t, _m, extra) => { if (extra) beats.push(extra); } },
};
vm.createContext(ctx);
vm.runInContext(SRC, ctx);

/** 播放 ms 毫秒：拍子正常触发，视频按 playbackRate 前进。 */
function play(ms) {
  const end = NOW + ms;
  while (ticker && NOW + ticker.ms <= end) {
    NOW += ticker.ms;
    video.currentTime += (ticker.ms / 1000) * video.playbackRate;
    ticker.fn();
  }
  NOW = end;
}
/** 页面被冻结/暂停 ms 毫秒：一拍都不触发，视频也不前进。 */
function frozen(ms) { NOW += ms; }

const S = (ms) => Number((ms / 1000).toFixed(1));
let failed = 0;
function check(name, actual, expected, tol) {
  const ok = Math.abs(actual - expected) <= tol;
  if (!ok) failed++;
  console.log(`  ${ok ? '✅' : '❌'} ${name}：实测 ${actual}s，期望 ${expected}±${tol}s`);
}
function scenario(t) { console.log('\n【' + t + '】'); ctx.resetTiming(); beats.length = 0; }

// ================================================================ 场景
scenario('场景一 · 连续播 5 分钟');
video.paused = false; ctx.timingStart('t'); play(300_000);
check('观看时长', S(ctx.timing.counted), 300, 2);

scenario('场景二 · 播 1 分钟 → 切后台暂停 30 秒 → 再播 1 分钟');
video.paused = false; ctx.timingStart('t'); play(60_000);
video.paused = true;  ctx.timingStop('pause'); frozen(30_000);
video.paused = false; ctx.timingStart('play'); play(60_000);
check('观看时长（那 30 秒不该算）', S(ctx.timing.counted), 120, 2);

scenario('场景三 · 播 1 分钟 → 锁屏 5 分钟 → 解锁自动恢复（★ 安卓实测那一幕）');
video.paused = false; ctx.timingStart('t'); play(60_000);
video.paused = true;  ctx.timingStop('pause');       // 真机日志：pause 是锁屏当场发的
frozen(300_000);                                     // 页面被冻结
video.paused = false; ctx.timingStart('play');       // 解锁自动恢复
play(1_000);
check('观看时长（第一版在这里多算 34.8 秒）', S(ctx.timing.counted), 61, 2);
check('对照：纯墙钟口径（说明冻结确实发生了）', S(ctx.timing.wallRaw), 61, 2);

scenario('场景四 · 2 倍速看 1 分钟');
video.paused = false; video.playbackRate = 2; ctx.timingStart('t'); play(60_000);
check('观看时长（按真实物理时间，不是内容时长）', S(ctx.timing.counted), 60, 2);
console.log(`     （同期视频位置走了 ${video.currentTime.toFixed(0)}s —— 按它算就会记成两倍）`);
video.playbackRate = 1;

scenario('场景五 · 卡顿：墙钟走 10 秒，视频只走 2 秒');
video.paused = false; ctx.timingStart('t');
for (let k = 0; k < 10; k++) { NOW += 1000; video.currentTime += 0.2; ticker.fn(); }
check('观看时长（借阿里云：只加内容前进量）', S(ctx.timing.counted), 2, 1);

scenario('场景六 · 播放中向前拖 190 秒（★ 需方指出的洞，第二版栽在这里）');
video.paused = false; ctx.timingStart('t'); play(10_000);
const c6 = ctx.timing.counted;
ctx.timingStop('seeking'); video.currentTime += 190; ctx.timingStart('seeked'); play(10_000);
check('拖动不该凭空加时长', S(ctx.timing.counted - c6), 10, 2);

scenario('场景七 · 冻结 5 分钟 + 往回拖（★ 第二版在这里算出负数）');
video.paused = false; ctx.timingStart('t'); play(60_000);
ctx.timingStop('pause'); frozen(300_000);
video.currentTime -= 60;                              // 往回拖
video.paused = false; ctx.timingStart('play'); play(1_000);
check('观看时长（不能变负、不能虚增）', S(ctx.timing.counted), 61, 2);
console.log(`     ${ctx.timing.counted >= 0 ? '✅' : '❌'} 没有变成负数（实际 ${S(ctx.timing.counted)}s）`);
if (ctx.timing.counted < 0) failed++;

scenario('场景八 · 重看已经看过的一段（★ 第二版会漏算）');
video.paused = false; ctx.timingStart('t'); play(30_000);
const c8 = ctx.timing.counted;
ctx.timingStop('seeking'); video.currentTime -= 30; ctx.timingStart('seeked'); play(30_000);
check('重看要照常算', S(ctx.timing.counted - c8), 30, 2);

scenario('场景九 · 暂停期间【不发心跳】（需方要求）');
video.paused = false; ctx.timingStart('t');
play(30_000); if (hbTimer) for (let k = 0; k < 3; k++) hbTimer.fn();
const beforeStop = beats.length;
video.paused = true; ctx.timingStop('pause');
const afterStop = beats.length;
frozen(120_000); if (hbTimer) for (let k = 0; k < 12; k++) hbTimer.fn();   // 停表期间轮询了 12 次
const afterSilent = beats.length;
console.log(`  ${afterStop - beforeStop === 1 ? '✅' : '❌'} 停下来【只发 1 条】（实际 ${afterStop - beforeStop} 条）`);
if (afterStop - beforeStop !== 1) failed++;
console.log(`  ${afterSilent === afterStop ? '✅' : '❌'} 之后静默、一条不发（实际又发了 ${afterSilent - afterStop} 条）`);
if (afterSilent !== afterStop) failed++;

scenario('场景十 · 走表期间位置【倒退】，但没有 seeking 事件（M66 守的就是这条）');
// 【为什么要单独造】正常拖动会先发 seeking → timingStop → 检查点清空，
// 于是「倒退」永远走不到 n < 0 那个分支。那条守卫防的是【事件没来】的情况：
// 播放器内部回绕、HLS 不连续、或某个 WebView 干脆不发 seeking。
// 不造这个输入的话，那行守卫就是零覆盖的摆设 —— 删掉它变异全绿（实测过）。
video.paused = false; ctx.timingStart('t'); play(10_000);
const c10 = ctx.timing.counted;
NOW += 1000;                             // 【墙钟必须先推进】否则 i<=0 会先 return，
video.currentTime -= 60;                 // 那条倒退守卫根本走不到（我第一次就写错在这里）
ticker.fn();                             // 这一拍读到 n < 0
NOW += 1000; ticker.fn();
check('倒退那一拍不能算，也不能变负', S(ctx.timing.counted - c10), 1, 1.5);
console.log(`     被跳过的倒退拍数 = ${ctx.timing.skippedBackward}（应 ≥ 1）`);
if (ctx.timing.skippedBackward < 1) failed++;

scenario('场景十一 · 页面被【节流】但视频仍在播，且没有 pause 事件（兜底 clamp 守的就是这条）');
// 【这是我们比 Mux 和阿里云多的那一层】：它们都靠事件，而这条路上事件不会来。
// 真机没观测到，但一旦发生就是无上界的虚增，所以留一行截断。
// 表现：一拍跨了 30 秒，视频也真走了 30 秒（不算卡顿），墙钟会加满 30 秒。
video.paused = false; ctx.timingStart('t'); play(5_000);
const c11 = ctx.timing.counted;
NOW += 30_000; video.currentTime += 30;  // 一拍跨 30 秒，视频同步走了 30 秒
ticker.fn();
check('单拍增量被截断（不是加满 30 秒）', S(ctx.timing.counted - c11), 2, 0.5);
console.log(`     兜底触发次数 clampTicks = ${ctx.timing.clampTicks}（应 ≥ 1）`);
if (ctx.timing.clampTicks < 1) failed++;

// ---------------------------------------------------------------- 字段
console.log('\n【心跳字段】需方要的字段必须条条都在');
ctx.emitHeartbeat('tick');   // 契约枚举值        // 逼一条出来再检查，否则 beats 是空的
const last = beats[beats.length - 1] || {};
for (const k of ['counted', 'wallRaw', 'drift', 'currentTime', 'paused', 'visible',
                 'tickCount', 'posDelta', 'skippedBackward', 'stallTicks', 'clampTicks']) {
  const ok = Object.prototype.hasOwnProperty.call(last, k);
  if (!ok) failed++;
  console.log(`  ${ok ? '✅' : '❌'} ${k} = ${JSON.stringify(last[k])}`);
}

console.log(failed === 0 ? '\n全部通过\n' : `\n${failed} 条不通过\n`);
process.exit(failed === 0 ? 0 : 1);
