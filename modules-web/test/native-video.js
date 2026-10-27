// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Video deck with the app's native player (a stub of XanderHost.videoCmd): the page opens the file natively,
// reports where to draw it, drives play/seek/FX, and its clock follows the native state.   node test/native-video.js
import { createRequire } from 'node:module';
import { serve } from '../serve.js';

const { chromium, devices } = createRequire(import.meta.url)('playwright');
const server = await serve(8795);
const browser = await chromium.launch();
const page = await browser.newPage({ ...devices['Pixel 7'] });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
const check = (ok, msg) => { if (!ok) throw new Error(`FAILED: ${msg}`); console.log(`ok - ${msg}`); };
await page.addInitScript(() => {
  const cmds = (window.__cmds = []);
  let t = 0, playing = false;
  const push = (s) => setTimeout(() => window.__xanderVideo.state({ t, playing, dur: 120, w: 1080, h: 1920, rate: 1, ready: true, ...s }), 30);
  window.XanderHost = {
    launchFile: () => JSON.stringify({ url: 'https://appassets.androidplatform.net/host/file/tok123/v.mp4', name: 'v.mp4', mime: 'video/mp4', size: 1, token: 'tok123', path: null }),
    decodeAudio: (id) => setTimeout(() => window.__xanderHost.fail(id, 'no audio in test'), 10),
    thumbnail: (id) => setTimeout(() => window.__xanderHost.fail(id, 'none'), 10),
    videoAvailable: () => true,
    videoCmd: (json) => {
      const c = JSON.parse(json); cmds.push(c);
      if (c.op === 'open') push({ event: 'loadedmetadata' });
      if (c.op === 'play') { playing = true; push({ event: 'play' }); }
      if (c.op === 'pause') { playing = false; push({ event: 'pause' }); }
      if (c.op === 'seek') { t = c.t; push({ event: 'seeked' }); }
    },
    videoExport: (id, name, a, b) => setTimeout(() => window.__xanderHost.done(id, { path: `/Movies/Groovesmith/Clips/${name}.mp4` }), 10),
    mediaIndex: (k) => JSON.stringify(k === 'video' ? [{ path: '/storage/emulated/0/DCIM/Camera/clip2.mp4', name: 'clip2.mp4', size: 5e6, modified: 1 }] : []),
    openPath: (p) => JSON.stringify({ url: 'https://appassets.androidplatform.net/host/file/tok456/clip2.mp4', name: 'clip2.mp4', mime: 'video/mp4', size: 5e6, token: 'tok456', path: p }),
    padAvailable: () => false, padLatencyMs: () => 0, keepScreenOn() {}, toast() {}, log() {},
  };
});
try {
  await page.goto('http://localhost:8795/modules/studio/index.html');
  await page.waitForFunction(() => window.__cmds.some((c) => c.op === 'open'), null, { timeout: 15000 });
  check(await page.evaluate(() => window.__cmds.find((c) => c.op === 'open').token === 'tok123'), 'opens the launched file in the native player by token');
  await page.waitForSelector('.native-video', { timeout: 5000 });
  check(await page.evaluate(() => !document.querySelector('video.video-el')), 'no WebView <video> is used');
  await page.waitForFunction(() => window.__cmds.some((c) => c.op === 'rect' && c.show), null, { timeout: 5000 });
  const r = await page.evaluate(() => [...window.__cmds].reverse().find((c) => c.op === 'rect'));
  const box = await page.evaluate(() => { const b = document.querySelector('.native-video').getBoundingClientRect(); return { w: b.width, h: b.height }; });
  check(r.show && Math.abs(r.w - box.w) <= 1 && Math.abs(r.h / r.w - 1920 / 1080) < 0.02, `native view placed on the video box with the video's shape (${r.w}x${r.h})`);
  await page.click('button[aria-label="Play or pause"]');
  await page.waitForTimeout(1000);
  const t1 = await page.evaluate(() => document.querySelector('.native-video').currentTime);
  check(t1 > 0.8 && t1 < 1.3, `clock follows native playback (${t1.toFixed(2)} s after 1 s)`);
  await page.click('.tabs-inline button:has-text("Video FX")');
  await page.click('button.chip:has-text("VHS")');
  const fx = await page.evaluate(() => [...window.__cmds].reverse().find((c) => c.op === 'fx')?.fx);
  check(fx && fx.scanlines > 0.5 && fx.rgbShift > 0.3, 'VHS preset sent to the native shader');
  check(await page.evaluate(() => document.querySelector('.video-canvas').hidden), 'page WebGL canvas stays off');
  await page.click('.nav button[data-view=beat]');
  await page.waitForTimeout(200);
  check(await page.evaluate(() => [...window.__cmds].reverse().find((c) => c.op === 'rect').show === false), 'native view hidden when leaving the Video deck');
  await page.click('.nav button[data-view=library]');
  await page.click('.seg button:has-text("On phone")');
  await page.waitForSelector('.pack-row');
  check(await page.locator('.pack-row').count() === 1, 'Library > On phone lists videos from the shared index');
  await page.click('.pack-row button:has-text("Chop")');
  await page.waitForFunction(() => window.__cmds.some((c) => c.op === 'open' && c.token === 'tok456'), null, { timeout: 5000 });
  check(true, 'Chop opens that video in the native player');
  check(errors.length === 0, `no page errors${errors.length ? ': ' + errors.join(' | ') : ''}`);
} catch (e) { console.error(e.message, errors); process.exitCode = 1; } finally { await browser.close(); server.close(); }
