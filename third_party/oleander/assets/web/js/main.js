// Application bootstrap: wires the library, player, settings and tools.
import { Core, BUTTONS } from './core.js';
import { db, estimate } from './storage.js';
import { settings, openSettings, openGameSettings, DEFAULT_KEYS, DEFAULT_PAD } from './settings.js';
import { input } from './input.js';
import { sensors } from './sensors.js';
import { TouchControls } from './touch.js';
import { Library, safeName } from './library.js';
import { Player } from './player.js';
import { Link } from './link.js';
import { cheatsDialog, loadCheats, applyCheats } from './cheats.js';
import { debuggerDialog } from './debugger.js';
import { cloudSettings, scheduleAutoSync } from './cloud.js';
import { zipStore, zipReadStored } from './zip.js';
import { importer } from './importer.js';
import { bootSplash, navigate, blip, powerLed } from './fx.js';
import { el, toast, showDialog, closeDialog, confirmDialog, download, pickFile, clearToasts } from './ui.js';

const $ = (id) => document.getElementById(id);

async function boot() {
  if ('serviceWorker' in navigator && location.protocol !== 'file:') {
    navigator.serviceWorker.register('sw.js').catch((e) => console.warn('Service worker', e));
  }
  await settings.load();
  bootSplash();
  input.attach();
  let core;
  try {
    core = await Core.load('oleander.wasm');
  } catch (e) {
    document.body.innerHTML = `<div class="empty"><h2>Could not start the emulator</h2><p>${e.message}</p><p>Build the core with <code>scripts/build-web.sh</code>.</p></div>`;
    return;
  }
  const library = new Library(core);
  const player = new Player(core);
  const link = new Link(player);
  const touch = new TouchControls($('touch-layer'));
  window.oleander = { core, library, player, link, settings };
  // Hardware Back button (Android app): close dialogs, toggle the in-game
  // menu; returns false when the app should go to the background.
  window.oleanderBack = () => {
    if ($('dialog').open) { closeDialog(); return true; }
    if (touch.editing) { touch.stopEditing(); return true; }
    if (player.running) {
      if (menu.classList.contains('hidden')) showMenu(); else hideMenu();
      return true;
    }
    return false;
  };

  // ---------------- views ----------------
  const showView = (name) => {
    $('library-view').classList.toggle('active', name === 'library');
    $('player-view').classList.toggle('active', name === 'player');
    document.body.classList.toggle('in-game', name === 'player');
    if (name === 'player') { touch.render(); player.layout(); }
  };

  async function play(game, extra = []) {
    const rom = await db.get('roms', game.id);
    if (!rom) return toast('ROM data missing – re-add the game', 'error');
    showView('player');
    $('game-title').textContent = game.title;
    powerLed(true);
    try {
      await player.open(game, rom, extra);
    } catch (e) {
      showView('library');
      return toast(`Could not start: ${e.message}`, 'error');
    }
    const errs = applyCheats(player.con, await loadCheats(game.id));
    if (errs.length) toast(`${errs.length} cheat(s) failed to load`, 'error');
    if (player.con.info.tilt || player.con.info.gyro) sensors.enable();
    if (player.units.length > 1) link.enableLocal();
    else if (link.mode === 'local') link.disconnect();
    else if (link.active) { link.sendHello(); link.updateRoster(); }
    history.replaceState(null, '', `?play=${encodeURIComponent(game.id)}`);
    setGameManifest(game);
  }

  async function exitToLibrary() {
    hideMenu();
    await player.close();
    if (link.mode === 'local') link.disconnect();
    else if (link.active) link.sendHello();
    history.replaceState(null, '', location.pathname);
    powerLed(false);
    $('manifest-link').href = 'manifest.webmanifest';
    showView('library');
    await library.load();
  }

  // ---------------- library wiring ----------------
  library.addEventListener('play', (e) => play(e.detail.game));
  $('search').addEventListener('input', (e) => { library.query = e.target.value; library.render(); });
  $('sort').addEventListener('change', (e) => { library.sort = e.target.value; library.render(); });
  $('library-tabs').addEventListener('click', (e) => {
    const f = e.target.dataset.filter;
    if (!f) return;
    library.filter = f;
    [...$('library-tabs').children].forEach((b) => b.classList.toggle('active', b === e.target));
    library.render();
  });
  $('btn-import').onclick = () => $('file-input').click();
  $('file-input').onchange = async (e) => { await library.importFiles([...e.target.files]); e.target.value = ''; };
  // Results of the Android app's native folder scan.
  window.oleanderNativeScan = (list, quiet) => library.scanNative(list, { quiet: !!quiet });
  // A ROM opened from another Android app: import (or find) it and play.
  window.oleanderOpenNative = async (item) => {
    const res = await importer.import([{ ...item, source: 'android:open' }]);
    await library.load();
    const g = res.ids?.[0] && library.games.find((x) => x.id === res.ids[0]);
    if (g) { library.scrapeLater(res.added); play(g); }
  };
  $('btn-scan').onclick = async () => {
    if (window.OleanderAndroid?.pickFolder) {
      // Android: system folder picker; access is remembered and the folder
      // is rescanned on every start.
      window.OleanderAndroid.pickFolder();
    } else if (window.showDirectoryPicker) {
      try {
        const handle = await window.showDirectoryPicker({ mode: 'read' });
        toast('Scanning folder…');
        await library.scanDirectory(handle);
      } catch (e) { if (e.name !== 'AbortError') toast(e.message, 'error'); }
    } else {
      $('dir-input').click();
    }
  };
  $('dir-input').onchange = async (e) => { await library.importFiles([...e.target.files]); e.target.value = ''; };
  player.addEventListener('opened', () => { importer.setPlaying(true); clearToasts(); });
  player.addEventListener('closed', () => importer.setPlaying(false));
  $('btn-settings').onclick = () => openAppSettings();

  // Drag & drop: ROMs into the library, saves/states into the player.
  document.addEventListener('dragover', (e) => { e.preventDefault(); document.body.classList.add('dragging'); });
  document.addEventListener('dragleave', (e) => { if (!e.relatedTarget) document.body.classList.remove('dragging'); });
  document.addEventListener('drop', async (e) => {
    e.preventDefault();
    document.body.classList.remove('dragging');
    const files = [...(e.dataTransfer?.files || [])];
    if (!files.length) return;
    if (player.running && /\.(ost|state)$/i.test(files[0].name)) {
      try { player.con.loadState(new Uint8Array(await files[0].arrayBuffer())); toast('State loaded'); } catch (err) { toast(err.message, 'error'); }
      return;
    }
    const added = await library.importFiles(files);
    if (added.length === 1 && !player.running) play(added[0]);
  });

  // Files opened through the OS ("Open with Oleander" for installed app).
  if ('launchQueue' in window) {
    window.launchQueue.setConsumer(async (params) => {
      const files = await Promise.all((params.files || []).map((h) => h.getFile()));
      const added = await library.importFiles(files);
      if (added[0]) play(added[0]);
    });
  }

  // ---------------- player menu ----------------
  const menu = $('player-menu');
  let stopNav = null;
  const showMenu = () => {
    if (!menu.classList.contains('hidden')) return;
    menu.classList.remove('hidden');
    input.enabled = false;
    player.wasRunningBeforeMenu = !player.paused;
    if (!player.paused) player.setPaused(true, true);
    updateMenu();
    blip('select');
    stopNav = navigate(menu, () => hideMenu());
  };
  const hideMenu = () => {
    stopNav?.();
    stopNav = null;
    if (menu.classList.contains('hidden')) return;
    menu.classList.add('hidden');
    input.enabled = true;
    if (player.wasRunningBeforeMenu) player.setPaused(false, true);
  };
  const updateMenu = () => {
    const btn = (act) => menu.querySelector(`[data-act="${act}"]`);
    btn('ff').classList.toggle('on', player.ffToggle);
    btn('slow').classList.toggle('on', player.slow);
    btn('record').textContent = player.recorder ? 'Stop recording' : 'Record video';
    btn('link').textContent = link.active ? 'Link cable: on…' : 'Link cable…';
    $('speed').value = player.speed;
    $('speed-label').textContent = `${player.speed}×`;
  };
  $('menu-button').onclick = showMenu;
  menu.querySelector('.close-menu').onclick = hideMenu;
  $('speed').oninput = (e) => { player.setSpeed(Number(e.target.value)); $('speed-label').textContent = `${e.target.value}×`; };
  menu.addEventListener('click', async (e) => {
    const act = e.target.closest('button')?.dataset.act;
    if (!act) return;
    const g = player.main?.game;
    switch (act) {
      case 'resume': hideMenu(); break;
      case 'states': await player.statesDialog(); break;
      case 'quicksave': await player.saveSlot(player.slot); hideMenu(); break;
      case 'quickload': await player.loadSlot(player.slot); hideMenu(); break;
      case 'ff': player.ffToggle = !player.ffToggle; player.slow = false; hideMenu(); break;
      case 'slow': player.slow = !player.slow; player.ffToggle = false; hideMenu(); break;
      case 'rewind': {
        hideMenu();
        player.rewinding = true;
        setTimeout(() => { player.rewinding = input.isHeld('rewind'); }, 1500);
        break;
      }
      case 'screenshot': await player.screenshot(); break;
      case 'record': hideMenu(); player.recorder ? player.stopRecording() : player.startRecording(); break;
      case 'cheats': await cheatsDialog(player.con, g); break;
      case 'link': await link.dialog(); updateMenu(); break;
      case 'debug': await debuggerDialog(player); break;
      case 'gamesettings': await openGameSettings(g.title); break;
      case 'settings': await openAppSettings(); break;
      case 'layout': menu.classList.add('hidden'); touch.startEditing(() => showMenu()); break;
      case 'fullscreen': player.toggleFullscreen(); break;
      case 'reset': if (await confirmDialog('Reset the game? Unsaved progress is lost.', 'Reset', true)) { player.reset(); hideMenu(); } break;
      case 'exit': await exitToLibrary(); break;
    }
    updateMenu();
  });
  player.addEventListener('hotkey', (e) => {
    if (e.detail.id === 'menu') menu.classList.contains('hidden') ? showMenu() : hideMenu();
  });
  // Escape closes the menu even though input is disabled while it is open.
  // Runs before the input handler (capture phase) so the same key press
  // does not close and immediately reopen the menu.
  window.addEventListener('keydown', (e) => {
    if (!menu.classList.contains('hidden') && e.code === settings.global.hotkeys.menu && !$('dialog').open) {
      e.preventDefault();
      e.stopImmediatePropagation();
      hideMenu();
    }
  }, true);
  player.addEventListener('saved', () => scheduleAutoSync());
  player.addEventListener('debugstop', () => { if ($('dialog').open) return; debuggerDialog(player); });
  player.addEventListener('splitscreen', () => pickSecondGame(library, async (second) => {
    const g = player.main.game;
    const secondRom = await db.get('roms', second.id);
    await play(g, [{ game: second, rom: secondRom }]);
    toast('Player 2: second gamepad');
  }));
  input.addEventListener('pad', (e) => toast(`${e.detail.connected ? 'Controller connected' : 'Controller disconnected'}: ${e.detail.id.slice(0, 40)}`));

  // ---------------- settings dialog ----------------
  function openAppSettings(tab) {
    return openSettings(tab, {
      version: core.version,
      captureKey: () => input.captureKey(),
      controls: (pane) => renderControls(pane),
      sensors: (pane) => pane.append(el('div', { class: 'row' }, el('button', { type: 'button', onclick: async () => toast((await sensors.enable()) ? 'Motion sensors enabled' : 'Permission denied', ) }, 'Enable motion sensors'))),
      backup: (savesOnly) => backup(savesOnly),
      scrape: () => { closeDialog(); toast('Fetching cover art…'); library.scrapeLater(null, 'refresh'); },
      restore: () => restore().then(() => library.load()),
      cloud: (pane) => cloudSettings(pane),
      storageInfo: async (pane) => {
        const est = await estimate();
        if (est) pane.append(el('p', { class: 'sub' }, `Storage used: ${(est.usage / 1048576).toFixed(1)} MB of ${(est.quota / 1048576).toFixed(0)} MB available.`));
      },
    });
  }

  function renderControls(pane) {
    const profiles = settings.global.profiles;
    const profileSel = el('select', {}, ['Default', ...Object.keys(profiles)].map((p) => el('option', { value: p, selected: p === settings.global.profile }, p)));
    profileSel.onchange = async () => { await settings.set('profile', profileSel.value); refresh(); };
    const refresh = () => { pane.replaceChildren(); renderControls(pane); };
    const editing = settings.global.profile;
    const current = () => (editing === 'Default' ? settings.global : profiles[editing]);
    const saveMaps = async (keyMap, padMap) => {
      if (editing === 'Default') {
        if (keyMap) await settings.set('keyMap', keyMap);
        if (padMap) await settings.set('padMap', padMap);
      } else {
        const p = { ...profiles };
        p[editing] = { keyMap: keyMap || p[editing].keyMap, padMap: padMap || p[editing].padMap };
        await settings.set('profiles', p);
      }
    };
    pane.append(el('div', { class: 'row' }, el('label', {}, 'Controller profile'), profileSel,
      el('button', { type: 'button', onclick: async () => {
        const name = prompt('Profile name');
        if (!name) return;
        await settings.set('profiles', { ...profiles, [name]: { keyMap: { ...settings.keyMap() }, padMap: { ...settings.padMap() } } });
        await settings.set('profile', name);
        refresh();
      } }, 'New profile'),
      editing !== 'Default' ? el('button', { type: 'button', class: 'danger', onclick: async () => {
        const p = { ...profiles };
        delete p[editing];
        await settings.set('profiles', p);
        await settings.set('profile', 'Default');
        refresh();
      } }, 'Delete profile') : null));
    pane.append(el('p', { class: 'sub' }, 'Profiles can be assigned per game in Game settings. Pad shortcuts: Select+R fast-forward, Select+L rewind, Select+Start menu.'));
    const names = [...BUTTONS, 'TurboA', 'TurboB'];
    const pads = input.pads();
    pane.append(el('p', {}, pads.length ? `Controllers: ${pads.map((p) => p.id).join(', ')}` : 'No controller detected – press a button on a connected controller.'));
    const table = el('div');
    for (const b of names) {
      const kb = el('button', { type: 'button', class: 'kbd' }, current().keyMap?.[b] || '—');
      kb.onclick = async () => {
        kb.classList.add('waiting'); kb.textContent = 'press a key…';
        const code = await input.captureKey();
        const map = { ...(current().keyMap || DEFAULT_KEYS), [b]: code === 'Escape' ? '' : code };
        await saveMaps(map, null);
        kb.classList.remove('waiting'); kb.textContent = map[b] || '—';
      };
      const pb = el('button', { type: 'button', class: 'kbd' }, current().padMap?.[b] !== undefined ? `Button ${current().padMap[b]}` : '—');
      pb.onclick = async () => {
        pb.classList.add('waiting'); pb.textContent = 'press a button…';
        const idx = await input.capturePad();
        pb.classList.remove('waiting');
        if (idx === null) { pb.textContent = 'timed out'; return; }
        const map = { ...(current().padMap || DEFAULT_PAD), [b]: idx };
        await saveMaps(null, map);
        pb.textContent = `Button ${idx}`;
      };
      table.append(el('div', { class: 'row' }, el('label', {}, b.replace('Turbo', 'Turbo ')), kb, pb));
    }
    pane.append(table, el('div', { class: 'row' },
      el('button', { type: 'button', onclick: async () => { await saveMaps({ ...DEFAULT_KEYS }, { ...DEFAULT_PAD }); refresh(); } }, 'Restore defaults'),
      el('button', { type: 'button', onclick: () => { closeDialog(); if (player.running) { showView('player'); touch.startEditing(); } else toast('Start a game to edit the touch layout'); } }, 'Edit touch layout')));
  }

  // ---------------- backup / restore ----------------
  async function backup(savesOnly) {
    const files = [];
    const add = (name, data) => files.push({ name, data: typeof data === 'string' ? new TextEncoder().encode(data) : data });
    const games = await db.all('games');
    const meta = [];
    for (const g of games) {
      const { cover, ...rest } = g;
      meta.push(rest);
      if (cover && !savesOnly) add(`covers/${g.id}`, new Uint8Array(await cover.arrayBuffer()));
      if (!savesOnly) add(`roms/${g.id}.gba`, await db.get('roms', g.id));
      const save = await db.get('saves', g.id);
      if (save) add(`saves/${g.id}.sav`, save.data);
      const cheats = await db.get('cheats', g.id);
      if (cheats) add(`cheats/${g.id}.json`, JSON.stringify(cheats));
    }
    for (const [k, v] of await db.entries('states')) {
      add(`states/${k.replace(':', '__')}.ost`, v.data);
      if (v.thumb) add(`states/${k.replace(':', '__')}.png`, new Uint8Array(await v.thumb.arrayBuffer()));
    }
    if (!savesOnly) {
      for (const [k, v] of await db.entries('screenshots')) add(`screenshots/${v.gameId}__${v.created}.png`, new Uint8Array(await v.blob.arrayBuffer()));
      const bios = await db.get('kv', 'bios');
      if (bios) add('bios.bin', bios.data);
    }
    add('games.json', JSON.stringify(meta, null, 1));
    add('settings.json', settings.exportJson());
    add('oleander-backup.json', JSON.stringify({ version: 1, created: Date.now(), savesOnly }));
    download(`oleander-${savesOnly ? 'saves' : 'backup'}-${new Date().toISOString().slice(0, 10)}.zip`, zipStore(files));
  }

  async function restore() {
    const f = await pickFile('.zip');
    if (!f) return;
    let entries;
    try { entries = zipReadStored(await f.arrayBuffer()); } catch (e) { return toast(e.message, 'error'); }
    const get = (n) => entries.find((e) => e.name === n)?.data;
    if (!get('oleander-backup.json')) return toast('Not an Oleander backup', 'error');
    if (!(await confirmDialog('Restore this backup? Existing entries with the same names are overwritten.', 'Restore'))) return;
    const txt = (d) => new TextDecoder().decode(d);
    const meta = JSON.parse(txt(get('games.json') || new TextEncoder().encode('[]')));
    for (const g of meta) {
      const cover = get(`covers/${g.id}`);
      const existing = await db.get('games', g.id);
      await db.put('games', { ...existing, ...g, cover: cover ? new Blob([cover]) : existing?.cover || null });
    }
    for (const e of entries) {
      let m;
      if ((m = e.name.match(/^roms\/(.+)\.gba$/))) await db.put('roms', e.data, m[1]);
      else if ((m = e.name.match(/^saves\/(.+)\.sav$/))) await db.put('saves', { data: e.data, updated: Date.now() }, m[1]);
      else if ((m = e.name.match(/^cheats\/(.+)\.json$/))) await db.put('cheats', JSON.parse(txt(e.data)), m[1]);
      else if ((m = e.name.match(/^states\/(.+)\.ost$/))) {
        const png = get(`states/${m[1]}.png`);
        await db.put('states', { data: e.data, thumb: png ? new Blob([png], { type: 'image/png' }) : null, created: Date.now() }, m[1].replace('__', ':'));
      } else if ((m = e.name.match(/^screenshots\/(.+)__(\d+)\.png$/))) await db.put('screenshots', { gameId: m[1], blob: new Blob([e.data], { type: 'image/png' }), created: Number(m[2]) });
      else if (e.name === 'bios.bin') await db.put('kv', { name: 'bios.bin', data: e.data }, 'bios');
    }
    const s = get('settings.json');
    if (s) await settings.importJson(txt(s));
    toast('Backup restored');
  }

  // ---------------- startup ----------------
  await library.load();
  library.autoScan().catch(() => {});
  // Cover art for games added before scraping existed (or while offline).
  setTimeout(() => library.scrapeLater().catch(() => {}), 2500);
  window.addEventListener('online', () => library.scrapeLater().catch(() => {}));
  const params = new URLSearchParams(location.search);
  const autoplay = params.get('play');
  if (autoplay) {
    const g = await db.get('games', autoplay);
    if (g) {
      setGameManifest(g);
      // Browsers require a user gesture before audio can start.
      const start = el('div', { class: 'empty', style: { position: 'fixed', inset: '0', background: 'var(--bg)', zIndex: 20, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', cursor: 'pointer' } },
        el('h2', {}, g.title), el('p', {}, 'Tap to start'));
      start.onclick = () => { start.remove(); play(g); };
      document.body.append(start);
    }
  }
  touch.render();
}

/// Swaps the web app manifest so installing from a game page creates a
/// home-screen shortcut that launches that game.
function setGameManifest(game) {
  const url = new URL(location.href);
  url.search = `?play=${encodeURIComponent(game.id)}`;
  const manifest = {
    name: `${game.title} – Oleander`,
    short_name: game.title.slice(0, 12),
    start_url: url.href,
    scope: new URL('./', location.href).href,
    display: 'standalone',
    background_color: '#000000',
    theme_color: '#1c1830',
    icons: [{ src: new URL('icons/icon-512.png', location.href).href, sizes: '512x512', type: 'image/png' }],
  };
  $('manifest-link').href = 'data:application/manifest+json,' + encodeURIComponent(JSON.stringify(manifest));
}

function pickSecondGame(library, onPick) {
  const list = el('div', { class: 'list' });
  for (const g of library.games) {
    list.append(el('div', { class: 'item' }, el('div', { class: 'grow' }, g.title, el('div', { class: 'small' }, g.code)),
      el('button', { type: 'button', class: 'primary', onclick: () => { closeDialog(); onPick(g); } }, 'Link')));
  }
  return showDialog('Choose the game for player 2', list);
}

export { safeName };
// Camera cutout insets from the Android host (CSS px), used by body.avoid-cutout in themes.css.
window.oleanderCutout = (c) => {
  const r = document.documentElement.style;
  for (const k of ['top', 'right', 'bottom', 'left']) r.setProperty(`--cut-${k}`, `${Math.max(0, +(c?.[k]) || 0)}px`);
};
try { window.oleanderCutout(JSON.parse(window.OleanderAndroid?.cutoutInsets?.() || '{}')); } catch {}
boot();
