// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// End-to-end walkthrough of Groovesmith Studio in Chromium at phone size (Pixel 7): opens a video, auto-chops
// it, applies video FX, saves the chops to the library, sequences one, and bounces a WAV. Fails on any page
// error or failed check. Screenshots go to docs/proof/studio/.   node proof.js   (needs Playwright + Chromium)

import { createRequire } from 'node:module';
import { mkdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { serve } from './serve.js';

const { chromium, devices } = createRequire(import.meta.url)('playwright'); // global install, no package dependency

const OUT = fileURLToPath(new URL('../docs/proof/studio/', import.meta.url));
const CLIP = fileURLToPath(new URL('./test/fixtures/clip.webm', import.meta.url));
mkdirSync(OUT, { recursive: true });

const server = await serve(8792);
const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ ...devices['Pixel 7'], acceptDownloads: true });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
const shot = (name) => page.screenshot({ path: `${OUT}${name}.png` });
const check = (ok, msg) => { if (!ok) throw new Error(`FAILED: ${msg}`); console.log(`ok - ${msg}`); };
const nav = async (v) => { await page.click(`.nav button[data-view=${v}]`); await page.waitForTimeout(250); };

try {
  await page.goto('http://localhost:8792/modules/studio/index.html');
  await page.evaluate(() => { localStorage.clear(); indexedDB.deleteDatabase('groovesmith-studio'); });
  await page.reload();
  await page.waitForSelector('.step');
  await nav('beat');

  // Beat: draw a step, it lights up and stays after a reload (autosave).
  const cell = page.locator('.step[data-step="2"]').first();
  await cell.click();
  check(await cell.evaluate((el) => el.classList.contains('on')), 'tapping a step turns it on');
  await page.click('.play-btn');
  await page.waitForTimeout(700);
  check(await page.locator('.led.now').count() === 1, 'playhead moves while playing');
  await shot('01-beat-playing');
  await page.click('.play-btn');

  // Video: open, extract audio, auto-chop by transients.
  await nav('video');
  const [chooser] = await Promise.all([page.waitForEvent('filechooser'), page.click('text=Open video')]);
  await chooser.setFiles(CLIP);
  await page.waitForFunction(() => /Audio 0:00/.test(document.querySelector('#video-audio-status')?.textContent || ''), null, { timeout: 15000 });
  check(true, 'audio extracted from the video');
  await page.click('button:has-text("Auto-chop")');
  await page.waitForTimeout(300);
  const chops = await page.locator('.chop').count();
  check(chops === 16, `auto-chop found ${chops} chops in 16 tone bursts`);
  await page.waitForTimeout(1500); // thumbnails
  await page.locator('.chop-main').nth(3).click();
  await page.waitForTimeout(400);
  await shot('02-video-chops');

  // Label + tag a chop.
  await page.locator('.chop .icon-btn').first().click();
  await page.fill('input[aria-label="Label"]', 'Bell hit');
  await page.fill('input[aria-label="Tags"]', 'bell, Lead Tone');
  await page.click('.sheet-foot button:has-text("Done")');
  check(await page.locator('.chop strong', { hasText: 'Bell hit' }).count() === 1, 'chop relabelled');

  await page.click('.tabs-inline button:has-text("Video FX")');
  await page.click('button.chip:has-text("VHS")');
  await page.click('.tabs-inline button:has-text("Pads")');
  await page.locator('.thumb-pad').nth(5).dispatchEvent('pointerdown');
  await page.locator('.thumb-pad').nth(5).dispatchEvent('pointerup');
  await page.waitForTimeout(500);
  await shot('03-video-pads-vhs');
  await page.click('.tabs-inline button:has-text("Audio FX")');
  await shot('04-video-audio-fx');

  // Save chops to the library.
  await page.click('.tabs-inline button:has-text("Chops")');
  await page.click('button:has-text("Save all")');
  await page.waitForFunction(() => /saved to the library/.test(document.querySelector('#toast').textContent), null, { timeout: 20000 });
  await nav('library');
  const cards = await page.locator('.sample-card').count();
  check(cards === chops, `library holds all ${chops} chops`);
  await page.fill('input[type=search]', '#lead-tone');
  check(await page.locator('.sample-card').count() === 1, 'search by #tag recalls the labelled chop');
  await shot('05-library-search');
  await page.fill('input[type=search]', '');
  await page.locator('.sample-card button:has-text("Add to Beat")').first().click();

  // Sequence the chop and bounce.
  await nav('beat');
  const tracks = await page.locator('.track-row:not(.ruler):not(.add)').count();
  check(tracks === 9, 'chop became a 9th track');
  const row = page.locator('.track-row:not(.ruler):not(.add)').last();
  for (const s of [0, 4, 8, 12]) await row.locator(`.step[data-step="${s}"]`).click();
  await shot('06-beat-with-chop');
  await page.click('.seg button:has-text("Pads")');
  await shot('07-pads');

  await page.click('#menu');
  const [dl] = await Promise.all([page.waitForEvent('download', { timeout: 30000 }), page.click('text=Export pattern as WAV')]);
  const wav = readFileSync(await dl.path());
  let peak = 0;
  for (let i = 44; i < wav.length; i += 2) peak = Math.max(peak, Math.abs(wav.readInt16LE(i)));
  check(wav.toString('ascii', 0, 4) === 'RIFF' && peak > 3000, `exported WAV is audible (peak ${peak})`);

  // Sampler on a chop, then mixer while playing.
  await nav('library');
  await page.locator('.sample-card .icon-btn').first().click();
  await page.click('button:has-text("Edit in Sampler")');
  await page.click('.seg button:has-text("Equal")');
  await page.click('button:has-text("Chop")');
  check(await page.locator('.pads.small .pad:not(.empty)').count() >= 2, 'sampler chops a sample into slice pads');
  await shot('08-sampler');
  await nav('synth');
  await shot('09-synth');
  await nav('mixer');
  await page.click('.play-btn');
  await page.waitForTimeout(900);
  await shot('10-mixer-playing');
  await page.click('.play-btn');

  await page.setViewportSize({ width: 1280, height: 800 });
  await nav('beat');
  await page.click('.seg button:has-text("Steps")');
  await shot('11-tablet-beat');

  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  console.log(`screenshots in ${OUT}`);
} catch (e) {
  console.error(e.message, errors);
  await shot('failure').catch(() => {});
  process.exitCode = 1;
} finally {
  await browser.close();
  server.close();
}
