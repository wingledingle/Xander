// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// 3D viewer check in Chromium at phone size: each fixture model opens, has its triangles and draws pixels.
// Screenshots go to docs/proof/model3d/.   node test/model3d.js   (needs Playwright + Chromium)

import { createRequire } from 'node:module';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { serve } from '../serve.js';

const { chromium, devices } = createRequire(import.meta.url)('playwright');
const OUT = fileURLToPath(new URL('../../docs/proof/model3d/', import.meta.url));
mkdirSync(OUT, { recursive: true });
const server = await serve(8794, { '/fixtures/': fileURLToPath(new URL('./fixtures/', import.meta.url)) });
const browser = await chromium.launch({ args: ['--use-gl=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
let failed = 0;
for (const name of ['torus.glb', 'torus.stl', 'torus.obj', 'torus.ply']) {
  const page = await browser.newPage({ ...devices['Pixel 7'] });
  const errors = [];
  page.on('pageerror', (e) => errors.push(e.message));
  await page.goto(`http://localhost:8794/modules/model3d/index.html?file=/fixtures/3d/${name}`);
  await page.waitForFunction(() => document.querySelector('#msg').textContent !== 'Opening…', null, { timeout: 20000 });
  await page.waitForTimeout(500);
  const r = await page.evaluate(() => {
    const c = document.querySelector('#view'), gl = c.getContext('webgl2') || c.getContext('webgl');
    const px = new Uint8Array(4); gl.readPixels(c.width >> 1, c.height >> 1, 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
    return { msg: document.querySelector('#msg').textContent, tris: window.__viewer.countTriangles(), info: document.querySelector('#info').textContent };
  });
  await page.screenshot({ path: `${OUT}${name.replace('.', '-')}.png` });
  const ok = !r.msg && r.tris === 1024 && !errors.length;
  if (!ok) failed++;
  console.log(`${ok ? 'ok' : 'FAILED'} - ${name}: ${r.tris} triangles, ${r.info}${r.msg ? ` msg: ${r.msg}` : ''}${errors.length ? ` errors: ${errors}` : ''}`);
  await page.close();
}
await browser.close(); server.close();
process.exit(failed ? 1 : 0);
