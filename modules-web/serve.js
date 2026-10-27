// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Static server for developing web modules in a desktop browser: http://localhost:8790/modules/studio/
// Supports Range requests so <video> can seek, like the app's own file handler.

import { createServer } from 'node:http';
import { createReadStream, statSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('./www', import.meta.url));
const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.svg': 'image/svg+xml', '.png': 'image/png', '.webp': 'image/webp',
  '.json': 'application/json', '.wav': 'audio/wav', '.mp4': 'video/mp4', '.webm': 'video/webm', '.mp3': 'audio/mpeg' };

export function serve(port = 8790, extraRoots = {}) {
  const server = createServer((req, res) => {
    const url = decodeURIComponent(new URL(req.url, 'http://x').pathname);
    const mount = Object.keys(extraRoots).find((m) => url.startsWith(m));
    const file = mount ? join(extraRoots[mount], normalize(url.slice(mount.length))) : join(ROOT, normalize(url));
    if (!file.startsWith(mount ? extraRoots[mount] : ROOT)) { res.writeHead(403).end(); return; }
    let st;
    try { st = statSync(file); } catch { res.writeHead(404).end('not found'); return; }
    if (st.isDirectory()) { res.writeHead(302, { location: `${url.replace(/\/?$/, '/')}index.html` }).end(); return; }
    const type = TYPES[extname(file)] || 'application/octet-stream';
    const m = /bytes=(\d*)-(\d*)/.exec(req.headers.range || '');
    if (m) {
      const start = m[1] ? +m[1] : st.size - +m[2], end = m[1] && m[2] ? Math.min(+m[2], st.size - 1) : st.size - 1;
      res.writeHead(206, { 'content-type': type, 'content-length': end - start + 1, 'content-range': `bytes ${start}-${end}/${st.size}`, 'accept-ranges': 'bytes' });
      createReadStream(file, { start, end }).pipe(res);
    } else {
      res.writeHead(200, { 'content-type': type, 'content-length': st.size, 'accept-ranges': 'bytes', 'cache-control': 'no-store' });
      createReadStream(file).pipe(res);
    }
  });
  return new Promise((r) => server.listen(port, () => r(server)));
}

if (process.argv[1] === fileURLToPath(import.meta.url)) serve().then(() => console.log('http://localhost:8790/modules/studio/'));
