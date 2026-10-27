// Background worker: ROM import (read, unzip, checksum, store) and metadata
// scraping (No-Intro database + libretro-thumbnails cover art). Running
// here keeps the page – and a game that is being played – smooth.
import { Core } from './core.js';
import { db, crc32 } from './storage.js';
import { parseHeader, prettyName, ROM_EXT, fileKey, parseDat, thumbName, cleanTitle } from './romutil.js';

let core = null;
let playing = false;
const queue = [];
let busy = false;

async function getCore() {
  if (!core) core = await Core.load(new URL('../oleander.wasm', import.meta.url).href);
  return core;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Be gentle while a game is running: the import is I/O heavy and competes
// with the page for IndexedDB and memory bandwidth.
const breathe = () => (playing ? sleep(40) : sleep(0));

self.onmessage = (e) => {
  const m = e.data;
  if (m.type === 'playing') { playing = m.value; return; }
  queue.push(m);
  pump();
};

async function pump() {
  if (busy) return;
  busy = true;
  while (queue.length) {
    const job = queue.shift();
    try {
      if (job.type === 'import') await runImport(job);
      else if (job.type === 'scrape') await runScrape(job);
    } catch (err) {
      self.postMessage({ type: 'error', jobId: job.jobId, message: String(err?.message || err) });
      self.postMessage({ type: 'done', jobId: job.jobId, added: [], skipped: 0, failed: 1 });
    }
  }
  busy = false;
}

// ---------------------------------------------------------------------
// Import
// ---------------------------------------------------------------------

async function runImport({ jobId, items }) {
  const index = (await db.get('kv', 'fileIndex')) || {};
  // New index entries are merged into the stored index regularly, so an
  // interrupted scan (app closed, Android killing the process) resumes
  // where it stopped instead of reading every file again.
  let pending = {};
  let lastFlush = Date.now();
  const flush = async () => {
    if (!Object.keys(pending).length) return;
    const add = pending;
    pending = {};
    lastFlush = Date.now();
    await db.update('kv', 'fileIndex', (cur) => ({ ...(cur || {}), ...add }));
  };
  const added = [];
  const ids = []; // every ROM processed, new or already known (in order)
  let skipped = 0, failed = 0;
  for (let i = 0; i < items.length; i++) {
    const it = items[i];
    self.postMessage({ type: 'progress', jobId, done: i, total: items.length, name: it.name });
    try {
      const key = fileKey(it);
      if (key && index[key] && (await db.get('games', index[key]))) { skipped++; ids.push(index[key]); continue; }
      if (!ROM_EXT.test(it.name)) { skipped++; continue; }
      let raw;
      if (it.file) raw = new Uint8Array(await it.file.arrayBuffer());
      else if (it.data) raw = new Uint8Array(it.data);
      else if (it.url) {
        const r = await fetch(it.url);
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        raw = new Uint8Array(await r.arrayBuffer());
      } else throw new Error('no data');
      let rom = raw;
      if (/\.(zip|gz)$/i.test(it.name) || (raw[0] === 0x50 && raw[1] === 0x4B) || (raw[0] === 0x1F && raw[1] === 0x8B)) {
        rom = (await getCore()).extractRom(raw);
      }
      if (rom.length < 0xC0) throw new Error('not a GBA ROM (too small)');
      const crc = crc32(rom);
      const id = `${crc.toString(16).padStart(8, '0')}-${rom.length.toString(16)}`;
      const header = parseHeader(rom);
      let isNew = false;
      const existing = await db.get('games', id);
      if (!existing) {
        await db.put('roms', rom, id);
        await db.put('games', {
          id,
          crc: crc.toString(16).toUpperCase().padStart(8, '0'),
          title: prettyName(it.name) || header.title,
          titleAuto: true,
          headerTitle: header.title,
          code: header.code,
          file: it.name,
          source: it.source || null,
          size: rom.length,
          added: Date.now(),
          lastPlayed: 0,
          playtime: 0,
          favorite: false,
          settings: {},
        });
        isNew = true;
      }
      if (key) { index[key] = id; pending[key] = id; }
      ids.push(id);
      if (isNew) {
        added.push(id);
        self.postMessage({ type: 'added', jobId, id });
      } else skipped++;
    } catch (err) {
      failed++;
      self.postMessage({ type: 'error', jobId, name: it.name, message: String(err?.message || err) });
    }
    if (Date.now() - lastFlush > 2000) await flush();
    await breathe();
  }
  await flush();
  self.postMessage({ type: 'progress', jobId, done: items.length, total: items.length });
  self.postMessage({ type: 'done', jobId, added, ids, skipped, failed });
}

// ---------------------------------------------------------------------
// Metadata scraping
// ---------------------------------------------------------------------

const DAT_URL = 'https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/no-intro/Nintendo%20-%20Game%20Boy%20Advance.dat';
const THUMB_BASE = 'https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/';
const DAT_MAX_AGE = 30 * 24 * 3600 * 1000;
const RETRY_AFTER = 7 * 24 * 3600 * 1000;
// Covers that downloaded art may replace (user-chosen covers are kept).
const REPLACEABLE = new Set(['generated', 'screenshot', undefined, null]);

async function loadIndex(force) {
  const cached = await db.get('kv', 'nointro');
  if (cached && !force && Date.now() - cached.fetched < DAT_MAX_AGE) return cached;
  try {
    const r = await fetch(DAT_URL, { cache: 'no-cache' });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const parsed = parseDat(await r.text());
    const rec = { ...parsed, fetched: Date.now() };
    await db.put('kv', rec, 'nointro');
    return rec;
  } catch (err) {
    if (cached) return cached; // offline: use what we have
    throw err;
  }
}

async function fetchImage(name) {
  for (const kind of ['Named_Boxarts', 'Named_Titles', 'Named_Snaps']) {
    const url = THUMB_BASE + kind + '/' + encodeURIComponent(thumbName(name)) + '.png';
    try {
      const r = await fetch(url);
      if (r.ok) return { blob: await r.blob(), kind };
    } catch { /* offline or blocked: try next / give up */ }
  }
  return null;
}

/// Shrinks cover art to keep the library light (boxarts are ~0.5 MB PNGs).
async function shrink(blob, maxW = 360) {
  if (typeof OffscreenCanvas === 'undefined' || typeof createImageBitmap === 'undefined') return blob;
  try {
    const bmp = await createImageBitmap(blob);
    const scale = Math.min(1, maxW / bmp.width);
    const w = Math.round(bmp.width * scale), h = Math.round(bmp.height * scale);
    const c = new OffscreenCanvas(w, h);
    const g = c.getContext('2d');
    g.imageSmoothingQuality = 'high';
    g.drawImage(bmp, 0, 0, w, h);
    bmp.close?.();
    return await c.convertToBlob({ type: 'image/webp', quality: 0.85 }).catch(() => c.convertToBlob({ type: 'image/png' }));
  } catch {
    return blob;
  }
}

async function runScrape({ jobId, ids, force }) {
  let index;
  try {
    index = await loadIndex(force === 'refresh');
  } catch (err) {
    self.postMessage({ type: 'error', jobId, message: `Metadata database unavailable (${err.message})` });
    self.postMessage({ type: 'done', jobId, added: [], skipped: 0, failed: 0, scraped: 0 });
    return;
  }
  const games = ids ? (await Promise.all(ids.map((id) => db.get('games', id)))).filter(Boolean) : await db.all('games');
  let scraped = 0;
  for (let i = 0; i < games.length; i++) {
    const g = games[i];
    self.postMessage({ type: 'progress', jobId, done: i, total: games.length, name: g.title, scrape: true });
    if (!force && g.cover && !REPLACEABLE.has(g.coverSource)) continue;
    if (!force && g.scrape?.tried && !g.scrape.found && Date.now() - g.scrape.tried < RETRY_AFTER) continue;
    const crc = g.crc || g.id.split('-')[0].toUpperCase();
    const hit = index.byCrc[crc.toUpperCase()];
    const name = hit?.[0] || (g.code && index.bySerial[g.code]) || null;
    let image = null;
    if (name) image = await fetchImage(name);
    const cover = image ? await shrink(image.blob) : null;
    await db.update('games', g.id, (cur) => {
      if (!cur) return undefined;
      const next = { ...cur, scrape: { tried: Date.now(), found: !!cover, name } };
      if (name) {
        next.meta = { name, region: (/\(([^)]*)\)/.exec(name) || [])[1] || '', matched: hit ? 'crc' : 'serial' };
        if (cur.titleAuto !== false) next.title = cleanTitle(name);
      }
      const overwrite = force === true || (force === 'refresh' && cur.coverSource !== 'custom');
      if (cover && (overwrite || !cur.cover || REPLACEABLE.has(cur.coverSource))) {
        next.cover = cover;
        next.coverSource = 'libretro:' + image.kind;
      }
      return next;
    });
    if (cover) scraped++;
    self.postMessage({ type: 'updated', jobId, id: g.id });
    await breathe();
  }
  self.postMessage({ type: 'progress', jobId, done: games.length, total: games.length, scrape: true });
  self.postMessage({ type: 'done', jobId, added: [], skipped: 0, failed: 0, scraped });
}
