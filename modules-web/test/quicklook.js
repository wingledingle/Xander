// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Quick Look check in Chromium at phone size: SVG, Markdown, HTML (scripts blocked), font and hex/info views.
// Screenshots go to docs/proof/quicklook/.   node test/quicklook.js   (needs Playwright + Chromium)

import { createRequire } from 'node:module';
import { mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { serve } from '../serve.js';

const { chromium, devices } = createRequire(import.meta.url)('playwright');
const OUT = fileURLToPath(new URL('../../docs/proof/quicklook/', import.meta.url));
mkdirSync(OUT, { recursive: true });
const FONT = '/usr/share/fonts/truetype/dejavu/';
const server = await serve(8795, { '/fixtures/': fileURLToPath(new URL('./fixtures/', import.meta.url)), ...(existsSync(FONT) ? { '/font/': FONT } : {}) });
const browser = await chromium.launch();
let failed = 0;
const check = (ok, msg) => { if (!ok) failed++; console.log(`${ok ? 'ok' : 'FAILED'} - ${msg}`); };
async function view(url, shot, fn) {
  const page = await browser.newPage({ ...devices['Pixel 7'] });
  const errors = []; page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(`http://localhost:8795/modules/quicklook/index.html?file=${url}`);
  await page.waitForTimeout(800);
  await fn(page);
  await page.screenshot({ path: `${OUT}${shot}.png` });
  check(!errors.length, `${shot}: no page errors ${errors.join(' ')}`);
  await page.close();
}
await view('/fixtures/ql/logo.svg', 'svg', async (p) => {
  check(await p.locator('#main img').evaluate((i) => i.complete && i.naturalWidth > 0), 'SVG renders as an image');
  check(await p.title() === 'Quick Look', 'SVG scripts do not run');
});
await view('/fixtures/ql/readme.md', 'markdown', async (p) => {
  const f = p.frameLocator('iframe.doc');
  check(await f.locator('strong').textContent() === 'bold' && await f.locator('table td').count() === 2, 'Markdown renders bold text and a table');
});
await view('/fixtures/ql/page.html', 'html', async (p) => {
  check(await p.frameLocator('iframe.doc').locator('h1').textContent() === 'Hello page', 'HTML renders with its scripts blocked');
});
if (existsSync(FONT)) await view('/font/DejaVuSans.ttf', 'font', async (p) => {
  check(await p.evaluate(() => [...document.fonts].some((f) => f.family === 'QL' && f.status === 'loaded')), 'font file loads and shows a specimen');
});
await view('/fixtures/3d/torus.stl', 'hex', async (p) => {
  const first = await p.locator('.hex div').first().textContent();
  check(/^00000000\s+78 61 6e 64 65 72/.test(first), 'binary file opens on the Hex tab with the right bytes');
  await p.click('.hexbar button:last-child'); await p.waitForTimeout(300);
  check((await p.locator('.hex div').first().textContent()).startsWith('00001000'), 'next page reads the next 4 KB');
  await p.click('[data-tab=info]'); await p.waitForFunction(() => /^[0-9a-f]{64}$/.test(document.querySelector('dl.info dd:last-child').textContent), null, { timeout: 5000 });
  check(true, 'Info tab shows a SHA-256');
});
await browser.close(); server.close();
process.exit(failed ? 1 : 0);
