// Game library: import (via the background worker), folder scanning,
// search/sort/filter, favourites, cartridge-style cards with scraped cover
// art, per-game actions, save import/export and home-screen shortcuts.
import { db } from './storage.js';
import { el, toast, showDialog, closeDialog, confirmDialog, promptDialog, download, pickFile, formatDuration, formatDate } from './ui.js';
import { importer } from './importer.js';
import { settings } from './settings.js';
import { ROM_EXT, parseHeader, prettyName } from './romutil.js';

export { parseHeader, prettyName };

export class Library extends EventTarget {
  constructor(core) {
    super();
    this.core = core;
    this.games = [];
    this.filter = 'all';
    this.query = '';
    this.sort = 'recent';
    this.grid = document.getElementById('game-grid');
    this.empty = document.getElementById('empty-library');
    this.coverUrls = new Map();
    this.reloadTimer = null;
    importer.addEventListener('changed', () => this.scheduleReload());
  }

  async load() {
    this.games = await db.all('games');
    this.render();
  }

  /// Coalesces bursts of worker updates into one re-render.
  scheduleReload() {
    if (this.reloadTimer) return;
    this.reloadTimer = setTimeout(async () => {
      this.reloadTimer = null;
      if (document.getElementById('library-view').classList.contains('active')) await this.load();
      else this.games = await db.all('games');
    }, 400);
  }

  /// Adds ROM files (File objects or {name, data}). Returns the new game records.
  async importFiles(files, { quiet = false } = {}) {
    const items = files.filter((f) => ROM_EXT.test(f.name)).map((f) => (f instanceof File ? { name: f.name, file: f } : f));
    if (!items.length) {
      if (!quiet) toast('No GBA ROMs (.gba, .zip) found', 'error');
      return [];
    }
    const res = await importer.import(items);
    await this.load();
    if (res.added.length) toast(res.added.length === 1 ? '1 game added' : `${res.added.length} games added`);
    else if (!quiet && res.skipped) toast('Those games are already in your library');
    this.scrapeLater(res.added);
    return res.added.map((id) => this.games.find((g) => g.id === id)).filter(Boolean);
  }

  /// Scans a directory (File System Access API) recursively for ROMs. Only
  /// file metadata is read here; contents are read by the worker, which
  /// skips files it has seen before (same name, size and date).
  async scanDirectory(handle, { remember = true, quiet = false } = {}) {
    const files = [];
    const walk = async (dir, depth, prefix) => {
      for await (const entry of dir.values()) {
        if (entry.kind === 'file' && ROM_EXT.test(entry.name)) {
          const file = await entry.getFile();
          files.push({ name: entry.name, file, size: file.size, lastModified: file.lastModified, source: `dir:${handle.name}/${prefix}` });
        } else if (entry.kind === 'directory' && depth < 8 && !entry.name.startsWith('.')) {
          await walk(entry, depth + 1, prefix + entry.name + '/');
        }
      }
    };
    await walk(handle, 0, '');
    if (remember) await db.put('kv', handle, 'scanDir');
    if (!files.length) {
      if (!quiet) toast('No GBA ROMs found in that folder', 'error');
      return 0;
    }
    const res = await importer.import(files);
    await this.load();
    if (res.added.length || !quiet) toast(`Folder scanned: ${res.added.length} new, ${res.skipped} already in library`);
    this.scrapeLater(res.added);
    return files.length;
  }

  /// Files listed by the Android app's native folder picker.
  async scanNative(list, { quiet = false } = {}) {
    const items = list.filter((f) => ROM_EXT.test(f.name)).map((f) => ({
      name: f.name,
      url: f.url,
      size: f.size,
      lastModified: f.lastModified,
      source: 'android:' + (f.tree || ''),
    }));
    if (!items.length) {
      if (!quiet) toast('No GBA ROMs found in that folder', 'error');
      return 0;
    }
    const res = await importer.import(items);
    await this.load();
    if (res.added.length || !quiet) toast(`Folder scanned: ${res.added.length} new, ${res.skipped} already in library`);
    this.scrapeLater(res.added);
    return items.length;
  }

  /// Rescans the remembered folder on startup (if permission persists).
  async autoScan() {
    if (window.OleanderAndroid?.rescanFolders) {
      window.OleanderAndroid.rescanFolders();
      return;
    }
    const handle = await db.get('kv', 'scanDir');
    if (!handle?.queryPermission) return;
    if ((await handle.queryPermission({ mode: 'read' })) === 'granted') {
      await this.scanDirectory(handle, { remember: false, quiet: true });
    }
  }

  /// Fetches cover art for the given (or all) games without a cover.
  async scrapeLater(ids = null, force = false) {
    if (!settings.global.scrapeCovers || !navigator.onLine) return;
    if (ids && !ids.length) return;
    const res = await importer.scrape(ids, force);
    if (res.errors?.length && force) toast(res.errors[0], 'error');
    if (force) toast(`Cover art: ${res.scraped || 0} found`);
    await this.load();
  }

  async updateGame(id, patch) {
    const rec = await db.update('games', id, (cur) => (cur ? { ...cur, ...(typeof patch === 'function' ? patch(cur) : patch) } : undefined));
    const i = this.games.findIndex((g) => g.id === id);
    if (rec && i >= 0) this.games[i] = rec;
    return rec;
  }

  visible() {
    const q = this.query.trim().toLowerCase();
    let list = this.games.filter((g) => !q || g.title.toLowerCase().includes(q) || (g.code || '').toLowerCase().includes(q) || (g.headerTitle || '').toLowerCase().includes(q) || (g.meta?.name || '').toLowerCase().includes(q));
    if (this.filter === 'favorites') list = list.filter((g) => g.favorite);
    if (this.filter === 'recent') list = list.filter((g) => g.lastPlayed).sort((a, b) => b.lastPlayed - a.lastPlayed).slice(0, 20);
    const by = {
      recent: (a, b) => (b.lastPlayed || 0) - (a.lastPlayed || 0) || a.title.localeCompare(b.title),
      title: (a, b) => a.title.localeCompare(b.title),
      added: (a, b) => (b.added || 0) - (a.added || 0),
      playtime: (a, b) => (b.playtime || 0) - (a.playtime || 0),
    }[this.sort];
    if (this.filter !== 'recent') list.sort(by);
    return list;
  }

  coverUrl(g) {
    if (!g.cover) return null;
    const key = g.id + ':' + (g.cover.size || 0) + ':' + (g.coverSource || '');
    if (!this.coverUrls.has(key)) this.coverUrls.set(key, URL.createObjectURL(g.cover));
    return this.coverUrls.get(key);
  }

  /// A GBA Game Pak, front view: landscape grey shell, ridged grip lip
  /// along the top, recessed label with a header band, the cover art and the
  /// product code printed along the bottom (AGB-XXXX-REG).
  cartridge(g) {
    const url = this.coverUrl(g);
    const hue = [...g.id].reduce((h, c) => (h * 31 + c.charCodeAt(0)) >>> 0, 7) % 360;
    const art = url
      ? el('img', { class: `cart-art ${g.coverSource === 'libretro:Named_Boxarts' ? 'boxart' : ''}`, src: url, alt: '', loading: 'lazy', decoding: 'async' })
      : el('div', { class: 'cart-art generated', style: { '--hue': hue } }, el('span', {}, g.title));
    // Wrapper carries the drop shadow: the shell itself is masked to the Game Pak outline (narrower bottom).
    return el('div', { class: 'cart' }, el('div', { class: 'cart-shell' },
      el('div', { class: 'cart-top' }, el('b', {}, '▼'), el('i', {}, 'OLEANDER'), el('b', {}, '▼')),
      el('div', { class: 'cart-label' },
        el('div', { class: 'cart-band' }, el('i', {}, 'OLEANDER'), el('span', {}, 'ADVANCE')),
        el('div', { class: 'cart-artbox' }, art, el('div', { class: 'cart-code' }, productCode(g)))),
      el('div', { class: 'cart-foot' })));
  }

  render() {
    const list = this.visible();
    const frag = document.createDocumentFragment();
    this.empty.classList.toggle('hidden', this.games.length > 0);
    for (const g of list) {
      const card = el('div', { class: 'card', tabindex: 0, role: 'button', 'aria-label': `Play ${g.title}`, 'data-id': g.id },
        this.cartridge(g),
        el('div', { class: 'meta' },
          el('div', { class: 'title' }, g.title),
          el('div', { class: 'sub' }, [g.meta?.region || g.code, g.playtime ? formatDuration(g.playtime) : null].filter(Boolean).join(' · '))),
        el('button', { class: 'fav', title: 'Favorite', 'aria-label': 'Favorite', onclick: (e) => { e.stopPropagation(); this.toggleFavorite(g); } }, g.favorite ? '★' : '☆'),
        el('button', { class: 'more', title: 'More', 'aria-label': 'Game options', onclick: (e) => { e.stopPropagation(); this.details(g); } }, '⋯'));
      card.onclick = () => this.dispatchEvent(new CustomEvent('play', { detail: { game: g } }));
      card.onkeydown = (e) => { if (e.key === 'Enter') card.click(); };
      card.oncontextmenu = (e) => { e.preventDefault(); this.details(g); };
      frag.append(card);
    }
    this.grid.replaceChildren(frag);
  }

  async toggleFavorite(g) {
    await this.updateGame(g.id, (cur) => ({ favorite: !cur.favorite }));
    this.render();
  }

  async details(g) {
    g = (await db.get('games', g.id)) || g;
    const save = await db.get('saves', g.id);
    const shots = await db.byIndex('screenshots', 'gameId', g.id);
    const refresh = () => { closeDialog(); this.load(); };
    const body = el('div', {},
      el('div', { class: 'row', style: { alignItems: 'flex-start' } },
        el('div', { class: 'card detail-cart' }, this.cartridge(g)),
        el('div', {},
          el('div', {}, el('b', {}, g.title)),
          g.meta?.name ? el('div', { class: 'sub' }, g.meta.name) : null,
          el('div', { class: 'sub' }, `Header: ${g.headerTitle || '?'} (${g.code || '????'})`),
          el('div', { class: 'sub' }, `Size: ${(g.size / 1048576).toFixed(2)} MiB · CRC ${g.crc || g.id.split('-')[0].toUpperCase()}`),
          el('div', { class: 'sub' }, `Play time: ${formatDuration(g.playtime)} · Last played: ${formatDate(g.lastPlayed)}`),
          el('div', { class: 'sub' }, `Battery save: ${save ? `${save.data.length} bytes, ${formatDate(save.updated)}` : 'none'}`),
          el('div', { class: 'sub' }, `Cover: ${g.coverSource ? g.coverSource.replace('libretro:', 'libretro-thumbnails ') : g.cover ? 'custom' : 'none'} · Screenshots: ${shots.length}`))),
      el('div', { class: 'dlg-actions', style: { justifyContent: 'flex-start' } },
        el('button', { type: 'button', class: 'primary', onclick: () => { closeDialog(); this.dispatchEvent(new CustomEvent('play', { detail: { game: g } })); } }, 'Play'),
        el('button', { type: 'button', onclick: async () => {
          const t = await promptDialog('Title', g.title);
          if (t) { await this.updateGame(g.id, { title: t, titleAuto: false }); this.load(); }
        } }, 'Rename'),
        el('button', { type: 'button', onclick: async () => {
          const f = await pickFile('image/*');
          if (f) { await this.updateGame(g.id, { cover: f, coverSource: 'custom' }); refresh(); }
        } }, 'Set cover'),
        el('button', { type: 'button', onclick: async () => {
          if (!navigator.onLine) return toast('You are offline', 'error');
          closeDialog();
          toast('Looking up cover art…');
          await importer.scrape([g.id], true);
          await this.load();
          const now = this.games.find((x) => x.id === g.id);
          toast(now?.scrape?.found ? 'Cover art found' : 'No cover art found for this ROM', now?.scrape?.found ? '' : 'error');
        } }, 'Find cover art'),
        g.cover ? el('button', { type: 'button', onclick: async () => { await this.updateGame(g.id, { cover: null, coverSource: null }); refresh(); } }, 'Remove cover') : null,
        el('button', { type: 'button', disabled: !save, onclick: () => download(`${safeName(g.title)}.sav`, save.data) }, 'Export save'),
        el('button', { type: 'button', onclick: async () => {
          const f = await pickFile('.sav,.srm,.bin,.fla,.eep');
          if (!f) return;
          const data = new Uint8Array(await f.arrayBuffer());
          if (save && !(await confirmDialog('Replace the existing save?', 'Replace', true))) return;
          await db.put('saves', { data, updated: Date.now() }, g.id);
          toast('Save imported');
        } }, 'Import save'),
        el('button', { type: 'button', onclick: () => this.shortcut(g) }, 'Home-screen shortcut'),
        shots.length ? el('button', { type: 'button', onclick: () => this.screenshots(g, shots) }, 'Screenshots') : null,
        el('button', { type: 'button', onclick: async () => download(`${safeName(g.title)}.gba`, await db.get('roms', g.id)) }, 'Export ROM'),
        el('button', { type: 'button', class: 'danger', onclick: async () => {
          if (!(await confirmDialog(`Remove "${g.title}" and all its saves, states and screenshots from this device?`, 'Remove', true))) return;
          await this.remove(g);
        } }, 'Remove')));
    return showDialog('Game', body);
  }

  async remove(g) {
    await db.del('games', g.id);
    await db.del('roms', g.id);
    await db.del('saves', g.id);
    await db.del('cheats', g.id);
    for (const k of await db.keys('states')) if (String(k).startsWith(g.id + ':')) await db.del('states', k);
    for (const [k, v] of await db.entries('screenshots')) if (v.gameId === g.id) await db.del('screenshots', k);
    // Forget the file so a rescan can add it again.
    await db.update('kv', 'fileIndex', (idx) => {
      if (!idx) return undefined;
      for (const [k, v] of Object.entries(idx)) if (v === g.id) delete idx[k];
      return idx;
    });
    await this.load();
  }

  screenshots(g, shots) {
    const wrap = el('div', { class: 'slots' });
    for (const s of shots) {
      wrap.append(el('div', { class: 'slot' }, el('img', { src: URL.createObjectURL(s.blob) }), el('div', { class: 'sub' }, formatDate(s.created)),
        el('div', { class: 'slot-actions' },
          el('button', { type: 'button', onclick: () => download(`${safeName(g.title)}-${s.created}.png`, s.blob) }, 'Save'),
          el('button', { type: 'button', onclick: async () => { await this.updateGame(g.id, { cover: s.blob, coverSource: 'custom' }); this.render(); toast('Cover updated'); } }, 'Use as cover'))));
    }
    return showDialog(`Screenshots – ${g.title}`, wrap, { wide: true });
  }

  /// Home-screen shortcut: a launch URL plus a per-game web app manifest so
  /// "Add to Home Screen"/"Install" creates an icon that starts the game.
  shortcut(g) {
    const url = new URL(location.href);
    url.search = `?play=${encodeURIComponent(g.id)}`;
    url.hash = '';
    const android = !!window.OleanderAndroid?.createShortcut;
    const body = el('div', {},
      android
        ? el('p', {}, 'Adds an icon for this game to your home screen.')
        : el('p', {}, 'Open this link and use your browser\'s "Add to Home screen" / "Install app" to create a shortcut that starts this game directly.'),
      android ? null : el('input', { type: 'text', value: url.href, readonly: true, style: { width: '100%' }, onclick: (e) => e.target.select() }),
      el('div', { class: 'dlg-actions' },
        android ? el('button', { type: 'button', class: 'primary', onclick: () => { window.OleanderAndroid.createShortcut(g.id, g.title); closeDialog(); } }, 'Add to home screen') : null,
        android ? null : el('button', { type: 'button', onclick: () => navigator.clipboard?.writeText(url.href).then(() => toast('Link copied')) }, 'Copy link'),
        !android && navigator.share ? el('button', { type: 'button', onclick: () => navigator.share({ title: g.title, url: url.href }).catch(() => {}) }, 'Share…') : null,
        android ? null : el('button', { type: 'button', class: 'primary', onclick: () => { location.href = url.href; } }, 'Open shortcut page')));
    return showDialog('Home-screen shortcut', body);
  }
}

const REGIONS = { E: 'USA', P: 'EUR', J: 'JPN', D: 'NOE', F: 'FRA', S: 'ESP', I: 'ITA', U: 'AUS', K: 'KOR', C: 'CHN', X: 'EUR', Y: 'EUR' };

/// Product code as printed on retail Game Paks, e.g. "AGB-BPEE-USA".
export function productCode(g) {
  const code = (g.code || '').toUpperCase();
  if (!/^[A-Z0-9]{4}$/.test(code)) return 'AGB-????';
  return `AGB-${code}-${REGIONS[code[3]] || 'INT'}`;
}

export function safeName(s) {
  return String(s || 'game').replace(/[^\w\- ]+/g, '_').trim() || 'game';
}
