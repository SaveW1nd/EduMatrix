#!/usr/bin/env node
/**
 * ⑤ 前端计时的自检 —— 跑法： node selftest-timing.js
 *
 * 【它测的是 index.html 里那段【真代码】，不是一份抄写】
 * 直接把 index.html 里「⑤ 前端计时」到「vitals」之间那一段原文抠出来放进沙箱跑。
 * 抄一份来测的话，测的是抄件；改了 index.html 而忘了改抄件，这里照样全绿。
 *
 * 【为什么需要它】真机上「锁屏 2 分钟」这种场景，实现方在电脑上复现不了；
 * 而需方拿到手之前，至少要能确认【计时的算术本身是对的】——
 * 否则真机上读到一个奇怪的数字，分不清是手机的问题还是代码写错了。
 *
 * 沙箱里把时间捏在手里：setInterval 不真的跑，由本脚本一拍一拍手动喂，
 * Date.now() 返回受控的墙钟。于是「锁屏 2 分钟」= 【跳过 120 拍，然后喂一拍 dt=120000ms】,
 * 这正是手机冻结页面时浏览器的行为。
 */
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');

const HTML = fs.readFileSync(path.join(__dirname, 'index.html'), 'utf8');
const BEGIN = '// ⑤ 前端计时（F-113 验证）';
const END = '// ---------- vitals ----------';
const b = HTML.indexOf(BEGIN), e = HTML.indexOf(END);
if (b < 0 || e < 0 || e < b) {
  console.error('✗ 在 index.html 里找不到计时那一段（标记被改过？）'); process.exit(1);
}
const SRC = HTML.slice(b, e);

// ---------------------------------------------------------------- 沙箱
let NOW = 1_700_000_000_000;
const timers = [];                       // {fn, ms, next}
const heartbeats = [];

const video = { currentTime: 0, paused: true, ended: false };
const ctx = {
  activeVideo: video,
  document: { hidden: false, addEventListener() {} },
  Date: { now: () => NOW },
  Math,
  Number,
  setInterval: (fn, ms) => { timers.push({ fn, ms, next: NOW + ms }); return timers.length; },
  L: { hi: (_tag, _msg, extra) => { if (extra) heartbeats.push(extra); } },
};
vm.createContext(ctx);
vm.runInContext(SRC, ctx);

/** 把墙钟推进 ms 毫秒，期间【正常触发】到期的定时器。 */
function advance(ms) {
  const target = NOW + ms;
  for (;;) {
    const due = timers.filter(t => t.next <= target).sort((a, b) => a.next - b.next)[0];
    if (!due) break;
    NOW = due.next;
    due.next = NOW + due.ms;
    if (!video.paused && !video.ended && !ctx.document.hidden) video.currentTime += due.ms / 1000;
    due.fn();
  }
  NOW = target;
}

/** 页面被系统【冻结】ms 毫秒：定时器一拍都不触发，醒来后各补一拍。 */
function freeze(ms) {
  NOW += ms;
  for (const t of timers) { t.next = NOW; }
  for (const t of timers.slice().sort((a, b) => a.ms - b.ms)) { t.next = NOW + t.ms; t.fn(); }
}

const S = (ms) => Number((ms / 1000).toFixed(1));
let failed = 0;
function check(name, actual, expected, tol) {
  const ok = Math.abs(actual - expected) <= tol;
  if (!ok) failed++;
  console.log(`  ${ok ? '✅' : '❌'} ${name}：实测 ${actual}s，期望 ${expected}±${tol}s`);
}

// ---------------------------------------------------------------- 场景
console.log('\n【场景一】连续播 5 分钟不碰它');
ctx.resetTiming();
video.paused = false;
advance(300_000);
check('wallRaw', S(ctx.timing.wallRaw), 300, 2);
check('wallCapped', S(ctx.timing.wallCapped), 300, 2);

console.log('\n【场景二】播 1 分钟 → 切后台 30 秒 → 切回来再播 1 分钟（那 30 秒不该算）');
ctx.resetTiming();
video.paused = false; ctx.document.hidden = false;
advance(60_000);
ctx.document.hidden = true;  advance(30_000);      // 切后台：定时器照跑，但 hidden 停表
ctx.document.hidden = false; advance(60_000);
check('wallRaw', S(ctx.timing.wallRaw), 120, 2);
check('wallCapped', S(ctx.timing.wallCapped), 120, 2);

console.log('\n【场景三】播 1 分钟 → 锁屏 2 分钟（页面被冻结）→ 解锁');
console.log('  这一条是本轮的重点：裸计时会虚增，封顶计时应当不受影响');
ctx.resetTiming();
video.paused = false; ctx.document.hidden = false;
advance(60_000);
freeze(120_000);                                    // 冻结：一拍不触发，醒来补一拍 dt=120s
check('wallRaw（预期【虚增】到 180）', S(ctx.timing.wallRaw), 180, 2);
check('wallCapped（预期仍是 60 上下）', S(ctx.timing.wallCapped), 62, 2);

console.log('\n【场景四】暂停与缓冲期间停表');
ctx.resetTiming();
video.paused = false; advance(30_000);
video.paused = true;  advance(30_000);              // 暂停 30 秒
video.paused = false; ctx.timing.buffering = true;
advance(30_000);                                    // 缓冲 30 秒
ctx.timing.buffering = false; advance(30_000);
check('wallRaw', S(ctx.timing.wallRaw), 60, 2);
check('wallCapped', S(ctx.timing.wallCapped), 60, 2);

console.log('\n【场景五】倍速不该让计时加速（累加的是墙钟，不是 currentTime 的增量）');
ctx.resetTiming();
video.paused = false;
const before = video.currentTime;
for (let i = 0; i < 60; i++) { advance(1000); video.currentTime += 1; }  // 额外 +1s/秒 = 2 倍速
check('wallRaw（2 倍速看 60 秒仍记 60）', S(ctx.timing.wallRaw), 60, 2);
console.log(`     （同期 currentTime 走了 ${(video.currentTime - before).toFixed(0)}s —— 按它计时就会记成两倍）`);

// ---------------------------------------------------------------- 心跳字段
console.log('\n【心跳字段】需方要的四个字段必须条条都在');
const last = heartbeats[heartbeats.length - 1] || {};
for (const k of ['currentTime', 'paused', 'visible', 'tickCount']) {
  const ok = Object.prototype.hasOwnProperty.call(last, k);
  if (!ok) failed++;
  console.log(`  ${ok ? '✅' : '❌'} ${k} = ${JSON.stringify(last[k])}`);
}
const cum = heartbeats.length >= 2 &&
  heartbeats[heartbeats.length - 1].elapsedSinceReset > heartbeats[0].elapsedSinceReset;
console.log(`  ${cum ? '✅' : '❌'} 心跳报的是【累计值不是增量】（F-113 口径）`);
if (!cum) failed++;

console.log(failed === 0 ? '\n全部通过\n' : `\n${failed} 条不通过\n`);
process.exit(failed === 0 ? 0 : 1);
