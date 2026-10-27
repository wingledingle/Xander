// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Video deck responsiveness at phone speed: Chromium with the CPU slowed 6x, a long video opened through a stub
// of the app's XanderHost (60 s mono audio window, like AudioExtractor). Fails if, after the audio arrives, the
// page has long main-thread tasks or the video stops advancing.   node test/video-perf.js <video> <window.wav>
import { createRequire } from 'node:module';
import { dirname, basename, resolve } from 'node:path';
import { serve } from '../serve.js';

const { chromium, devices } = createRequire(import.meta.url)('playwright');
const [video, wav] = process.argv.slice(2).map((p) => resolve(p));
const server = await serve(8794, { '/media/': dirname(video) + '/' });
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ ...devices['Pixel 7'] });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.addInitScript(([v, w]) => {
  window.XanderHost = {
    launchFile: () => JSON.stringify({ url: `/media/${v}`, name: v, mime: 'video/webm', size: 1, token: 't', path: null }),
    decodeAudio: (id) => setTimeout(() => window.__xanderHost.done(id, { url: `/media/${w}`, startSec: 0, durationSec: 60 }), 300),
    thumbnail: (id) => setTimeout(() => window.__xanderHost.fail(id, 'none'), 10),
    padAvailable: () => false, padLatencyMs: () => 0, keepScreenOn() {}, toast() {}, log() {}, openPath: () => 'null',
  };
}, [basename(video), basename(wav)]);
const cdp = await page.context().newCDPSession(page);
await cdp.send('Emulation.setCPUThrottlingRate', { rate: 6 });
await page.goto('http://localhost:8794/modules/studio/index.html');
await page.waitForFunction(() => /Audio 0:00/.test(document.querySelector('#video-audio-status')?.textContent || ''), null, { timeout: 60000 });
await page.evaluate(() => { window.__long = []; new PerformanceObserver((l) => l.getEntries().forEach((e) => window.__long.push(Math.round(e.duration)))).observe({ type: 'longtask' }); });
await page.click('button[aria-label="Play or pause"]');
const t0 = await page.evaluate(() => document.querySelector('.video-el').currentTime);
const f0 = await page.evaluate(() => new Promise((r) => { let n = 0; const end = performance.now() + 5000; const f = () => { n++; performance.now() < end ? requestAnimationFrame(f) : r(n); }; requestAnimationFrame(f); }));
const r = await page.evaluate(() => ({ t: document.querySelector('.video-el').currentTime, long: window.__long }));
const worst = Math.max(0, ...r.long);
console.log(`video advanced ${(r.t - t0).toFixed(2)} s in ~5 s; ${f0 / 5} page frames/s; ${r.long.length} long tasks, worst ${worst} ms; errors: ${errors.length}`);
await browser.close(); server.close();
process.exitCode = r.t - t0 > 4 && f0 / 5 >= 30 && worst < 200 && !errors.length ? 0 : 1;
