// Settings model (global + per-game overrides) and the settings dialog.
import { db, getSetting, setSetting } from './storage.js';
import { el, showDialog, toast, download, pickFile, confirmDialog } from './ui.js';
import { BUTTONS } from './core.js';

export const DEFAULT_KEYS = {
  A: 'KeyX', B: 'KeyZ', L: 'KeyA', R: 'KeyS', Start: 'Enter', Select: 'Backspace',
  Up: 'ArrowUp', Down: 'ArrowDown', Left: 'ArrowLeft', Right: 'ArrowRight',
  TurboA: 'KeyW', TurboB: 'KeyQ',
};

// Standard Gamepad API layout indices.
export const DEFAULT_PAD = {
  A: 1, B: 0, L: 4, R: 5, Start: 9, Select: 8, Up: 12, Down: 13, Left: 14, Right: 15, TurboA: 3, TurboB: 2,
};

export const HOTKEYS = {
  menu: { label: 'Open menu', key: 'Escape' },
  pause: { label: 'Pause / resume', key: 'KeyP' },
  fastForward: { label: 'Fast forward (hold)', key: 'Tab' },
  fastForwardToggle: { label: 'Fast forward (toggle)', key: 'Backquote' },
  slowMotion: { label: 'Slow motion (toggle)', key: 'KeyO' },
  rewind: { label: 'Rewind (hold)', key: 'KeyR' },
  quickSave: { label: 'Quick save', key: 'F2' },
  quickLoad: { label: 'Quick load', key: 'F4' },
  slotNext: { label: 'Next save slot', key: 'F3' },
  slotPrev: { label: 'Previous save slot', key: 'F1' },
  screenshot: { label: 'Screenshot', key: 'F12' },
  record: { label: 'Start/stop recording', key: 'F9' },
  fullscreen: { label: 'Fullscreen', key: 'F11' },
  mute: { label: 'Mute', key: 'KeyM' },
  reset: { label: 'Reset', key: 'F10' },
  stats: { label: 'Performance overlay', key: 'F8' },
  frameAdvance: { label: 'Frame advance (paused)', key: 'Period' },
};

export const DEFAULTS = {
  theme: 'indigo',
  bootAnimation: true,
  handheldFrame: 'auto',
  uiSounds: true,
  accent: '',
  // Video
  shader: 'pixel',
  colorCorrection: false,
  scaling: 'fit',
  aspect: 'keep',
  brightness: 1.0,
  saturation: 1.0,
  frameSkip: 'auto',
  showStats: false,
  // Audio
  volume: 0.8,
  latency: 64,
  mute: false,
  ffAudio: true,
  // Emulation
  ffSpeed: 4,
  slowSpeed: 0.5,
  syncMode: 'video',
  rewind: true,
  rewindSeconds: 20,
  rewindInterval: 2,
  autoSaveState: true,
  autoRestore: true,
  pauseHidden: true,
  useBios: true,
  skipBios: false,
  saveType: 'auto',
  rtcOffsetMinutes: 0,
  // Input
  keyMap: DEFAULT_KEYS,
  padMap: DEFAULT_PAD,
  padDeadzone: 0.4,
  turboRate: 10,
  haptics: true,
  touchControls: 'auto',
  touchOpacity: 0.55,
  touchLayout: null,
  hotkeys: Object.fromEntries(Object.entries(HOTKEYS).map(([k, v]) => [k, v.key])),
  profile: 'Default',
  profiles: {},
  // Sensors
  tiltSource: 'device',
  solarLevel: 120,
  // Library
  scrapeCovers: true,
  // Cloud / link
  cloudUrl: '',
  cloudUser: '',
  cloudPass: '',
  cloudAuto: false,
  linkServer: '',
  linkPeer: '',
};

// Keys that may be overridden per game.
export const PER_GAME = ['shader', 'colorCorrection', 'scaling', 'brightness', 'saturation', 'frameSkip', 'volume', 'ffSpeed', 'slowSpeed',
  'rewind', 'useBios', 'skipBios', 'saveType', 'rtcOffsetMinutes', 'profile', 'tiltSource', 'solarLevel', 'syncMode'];

class Settings extends EventTarget {
  constructor() {
    super();
    this.global = structuredClone(DEFAULTS);
    this.game = {};
    this.gameId = null;
  }

  async load() {
    const saved = (await getSetting('all', {})) || {};
    this.global = { ...structuredClone(DEFAULTS), ...saved };
    this.global.keyMap = { ...DEFAULT_KEYS, ...(saved.keyMap || {}) };
    this.global.padMap = { ...DEFAULT_PAD, ...(saved.padMap || {}) };
    this.global.hotkeys = { ...DEFAULTS.hotkeys, ...(saved.hotkeys || {}) };
    // Themes were renamed with the handheld redesign.
    const renamed = { dark: 'indigo', gba: 'indigo', light: 'glacier', oled: 'black' };
    if (renamed[this.global.theme]) this.global.theme = renamed[this.global.theme];
    this.applyTheme();
  }

  get(key) {
    if (this.gameId && key in this.game) return this.game[key];
    return this.global[key];
  }

  async set(key, value) {
    this.global[key] = value;
    await setSetting('all', this.global);
    if (key === 'theme' || key === 'accent') this.applyTheme();
    this.dispatchEvent(new CustomEvent('change', { detail: { key, value } }));
  }

  async setForGame(key, value) {
    if (!this.gameId) return this.set(key, value);
    const g = await db.update('games', this.gameId, (cur) => {
      if (!cur) return undefined;
      const s = { ...(cur.settings || {}) };
      if (value === undefined) delete s[key];
      else s[key] = value;
      return { ...cur, settings: s };
    });
    if (!g) return;
    this.game = g.settings;
    this.dispatchEvent(new CustomEvent('change', { detail: { key, value } }));
  }

  async bindGame(id) {
    this.gameId = id;
    const g = id ? await db.get('games', id) : null;
    this.game = g?.settings || {};
    // A per-game controller profile overrides key/pad maps.
    this.dispatchEvent(new CustomEvent('change', { detail: { key: '*' } }));
  }

  keyMap() {
    const p = this.global.profiles[this.get('profile')];
    return p?.keyMap || this.global.keyMap;
  }

  padMap() {
    const p = this.global.profiles[this.get('profile')];
    return p?.padMap || this.global.padMap;
  }

  applyTheme() {
    document.documentElement.dataset.theme = this.global.theme;
    if (this.global.accent) document.documentElement.style.setProperty('--accent', this.global.accent);
    else document.documentElement.style.removeProperty('--accent');
  }

  exportJson() {
    return JSON.stringify({ oleanderSettings: 1, settings: this.global }, null, 2);
  }

  async importJson(text) {
    const obj = JSON.parse(text);
    if (!obj.oleanderSettings) throw new Error('Not an Oleander settings file');
    this.global = { ...structuredClone(DEFAULTS), ...obj.settings };
    await setSetting('all', this.global);
    this.applyTheme();
    this.dispatchEvent(new CustomEvent('change', { detail: { key: '*' } }));
  }
}

export const settings = new Settings();

// ---------------------------------------------------------------------
// Settings dialog
// ---------------------------------------------------------------------

const SCHEMA = {
  Video: [
    ['shader', 'Filter / shader', 'select', [['pixel', 'Pixel perfect (nearest)'], ['sharp', 'Sharp bilinear'], ['smooth', 'Smooth (bilinear)'], ['lcd', 'LCD grid'], ['scanlines', 'Scanlines'], ['crt', 'CRT']]],
    ['colorCorrection', 'GBA LCD colour correction', 'check'],
    ['scaling', 'Scaling', 'select', [['fit', 'Fit to screen'], ['integer', 'Integer scaling'], ['stretch', 'Stretch']]],
    ['aspect', 'Aspect ratio', 'select', [['keep', 'Preserve 3:2'], ['free', 'Free (with stretch)']]],
    ['brightness', 'Brightness', 'range', [0.5, 1.5, 0.05]],
    ['saturation', 'Saturation', 'range', [0, 1.5, 0.05]],
    ['frameSkip', 'Frame skipping', 'select', [['auto', 'Automatic'], ['0', 'Off'], ['1', '1'], ['2', '2'], ['3', '3'], ['4', '4']]],
    ['showStats', 'Show performance statistics', 'check'],
  ],
  Audio: [
    ['volume', 'Volume', 'range', [0, 1, 0.01]],
    ['mute', 'Mute', 'check'],
    ['latency', 'Latency (ms)', 'range', [20, 250, 4]],
    ['ffAudio', 'Play audio while fast-forwarding', 'check'],
    ['syncMode', 'Synchronisation', 'select', [['video', 'Display (smooth video)'], ['audio', 'Audio (no crackles)']]],
  ],
  Emulation: [
    ['ffSpeed', 'Fast-forward speed', 'select', [['2', '2×'], ['3', '3×'], ['4', '4×'], ['6', '6×'], ['8', '8×'], ['0', 'Unlimited']]],
    ['slowSpeed', 'Slow-motion speed', 'select', [['0.25', '0.25×'], ['0.5', '0.5×'], ['0.75', '0.75×']]],
    ['rewind', 'Enable rewind', 'check'],
    ['rewindSeconds', 'Rewind length (seconds)', 'range', [5, 120, 5]],
    ['autoSaveState', 'Save state automatically when leaving a game', 'check'],
    ['autoRestore', 'Restore that state when starting the game', 'check'],
    ['pauseHidden', 'Pause when the app is in the background', 'check'],
    ['useBios', 'Use BIOS image if installed', 'check'],
    ['skipBios', 'Skip BIOS intro', 'check'],
    ['saveType', 'Save type', 'select', [['auto', 'Detect'], ['none', 'None'], ['sram', 'SRAM'], ['flash64', 'Flash 64K'], ['flash128', 'Flash 128K'], ['eeprom512', 'EEPROM 512B'], ['eeprom8k', 'EEPROM 8K']]],
    ['rtcOffsetMinutes', 'Real-time clock offset (minutes)', 'number'],
    ['tiltSource', 'Tilt / gyro input', 'select', [['device', 'Device motion sensors'], ['keys', 'Keyboard (I/J/K/L, U/O)'], ['mouse', 'Mouse position']]],
    ['solarLevel', 'Solar sensor light level', 'range', [0, 255, 1]],
  ],
  Interface: [
    ['scrapeCovers', 'Download cover art and game names (libretro-thumbnails)', 'check'],
    ['theme', 'Shell colour', 'select', [['indigo', 'Indigo'], ['glacier', 'Glacier'], ['black', 'Black'], ['spice', 'Spice orange'], ['retro', 'Retro green']]],
    ['handheldFrame', 'Handheld frame around the screen', 'select', [['auto', 'With touch controls'], ['always', 'Always'], ['never', 'Never']]],
    ['bootAnimation', 'Boot animation', 'check'],
    ['uiSounds', 'Menu sounds', 'check'],
    ['accent', 'Accent colour (blank = theme)', 'color'],
    ['touchControls', 'Touch controls', 'select', [['auto', 'On touch devices'], ['always', 'Always'], ['never', 'Never']]],
    ['touchOpacity', 'Touch control opacity', 'range', [0.05, 1, 0.05]],
    ['haptics', 'Vibration feedback', 'check'],
    ['turboRate', 'Turbo rate (presses / second)', 'range', [2, 30, 1]],
  ],
};

function field(key, label, type, opts, perGame) {
  const value = perGame ? settings.get(key) : settings.global[key];
  const save = (v) => (perGame ? settings.setForGame(key, v) : settings.set(key, v));
  let input;
  if (type === 'select') {
    input = el('select', {}, opts.map(([v, l]) => el('option', { value: v, selected: String(value) === v }, l)));
    input.onchange = () => {
      const v = input.value;
      save(typeof DEFAULTS[key] === 'number' ? Number(v) : v);
    };
  } else if (type === 'check') {
    input = el('input', { type: 'checkbox', checked: !!value });
    input.onchange = () => save(input.checked);
  } else if (type === 'range') {
    const out = el('span', { class: 'mono' }, String(value));
    const r = el('input', { type: 'range', min: opts[0], max: opts[1], step: opts[2], value });
    r.oninput = () => { out.textContent = r.value; };
    r.onchange = () => save(Number(r.value));
    input = el('span', {}, r, ' ', out);
  } else if (type === 'number') {
    input = el('input', { type: 'number', value, style: { width: '110px' } });
    input.onchange = () => save(Number(input.value));
  } else if (type === 'color') {
    input = el('input', { type: 'text', value: value || '', placeholder: '#f06b9a', style: { width: '120px' } });
    input.onchange = () => save(input.value.trim());
  }
  const row = el('div', { class: 'row' }, el('label', {}, label), input);
  if (perGame) {
    const overridden = key in settings.game;
    row.append(el('button', { type: 'button', title: 'Use the global value', disabled: !overridden, onclick: async () => { await settings.setForGame(key, undefined); toast('Reverted to global setting'); } }, 'Reset'));
  }
  return row;
}

export async function openSettings(initialTab = 'Video', hooks = {}) {
  const tabs = [...Object.keys(SCHEMA), 'Controls', 'Hotkeys', 'Data', 'About'];
  const bar = el('div', { class: 'settings-tabs' });
  const pane = el('div');
  const show = (name) => {
    [...bar.children].forEach((b) => b.classList.toggle('active', b.textContent === name));
    pane.replaceChildren();
    if (SCHEMA[name]) SCHEMA[name].forEach(([k, l, t, o]) => pane.append(field(k, l, t, o, false)));
    if (name === 'Controls') hooks.controls?.(pane);
    if (name === 'Hotkeys') renderHotkeys(pane, hooks.captureKey);
    if (name === 'Data') renderData(pane, hooks);
    if (name === 'About') renderAbout(pane, hooks);
    if (name === 'Emulation') renderBios(pane, hooks);
  };
  tabs.forEach((t) => bar.append(el('button', { type: 'button', onclick: () => show(t) }, t)));
  const p = showDialog('Settings', el('div', {}, bar, pane), { wide: true });
  show(initialTab);
  return p;
}

export function openGameSettings(title) {
  const pane = el('div', {}, el('p', { class: 'sub' }, 'These values override the global settings for this game only.'));
  for (const name of Object.keys(SCHEMA)) {
    for (const [k, l, t, o] of SCHEMA[name]) {
      if (PER_GAME.includes(k)) pane.append(field(k, l, t, o, true));
    }
  }
  const profiles = ['Default', ...Object.keys(settings.global.profiles)];
  pane.append(field('profile', 'Controller profile', 'select', profiles.map((p) => [p, p]), true));
  return showDialog(`Game settings – ${title}`, pane);
}

function renderHotkeys(pane, captureKey) {
  pane.append(el('p', {}, 'Click a binding, then press the new key (Esc clears).'));
  for (const [id, def] of Object.entries(HOTKEYS)) {
    const b = el('button', { type: 'button', class: 'kbd' }, settings.global.hotkeys[id] || '—');
    b.onclick = async () => {
      b.classList.add('waiting');
      b.textContent = 'press a key…';
      const code = await captureKey();
      const hk = { ...settings.global.hotkeys, [id]: code === 'Escape' ? '' : code };
      await settings.set('hotkeys', hk);
      b.classList.remove('waiting');
      b.textContent = hk[id] || '—';
    };
    pane.append(el('div', { class: 'row' }, el('label', {}, def.label), b));
  }
  pane.append(el('div', { class: 'row' }, el('button', { type: 'button', onclick: async () => { await settings.set('hotkeys', structuredClone(DEFAULTS.hotkeys)); renderHotkeysRefresh(pane, captureKey); } }, 'Restore defaults')));
}

function renderHotkeysRefresh(pane, captureKey) {
  pane.replaceChildren();
  renderHotkeys(pane, captureKey);
}

function renderBios(pane, hooks) {
  const status = el('span', { class: 'sub' }, '…');
  db.get('kv', 'bios').then((b) => { status.textContent = b ? `Installed (${b.name}, ${b.data.length} bytes)` : 'Not installed – using the built-in BIOS replacement'; });
  pane.append(el('div', { class: 'section' }, el('h3', {}, 'BIOS'),
    el('p', { class: 'sub' }, 'Oleander runs games without a BIOS. Optionally install a dump of your own console\'s BIOS (16 KB) for the boot animation and maximum accuracy.'),
    el('div', { class: 'row' }, status),
    el('div', { class: 'row' },
      el('button', { type: 'button', onclick: async () => {
        const f = await pickFile('.bin,.rom,.gba');
        if (!f) return;
        const data = new Uint8Array(await f.arrayBuffer());
        if (data.length !== 16384) return toast('A GBA BIOS must be exactly 16384 bytes', 'error');
        await db.put('kv', { name: f.name, data }, 'bios');
        status.textContent = `Installed (${f.name})`;
        toast('BIOS installed');
      } }, 'Install BIOS…'),
      el('button', { type: 'button', class: 'danger', onclick: async () => { await db.del('kv', 'bios'); status.textContent = 'Not installed'; } }, 'Remove BIOS'))));
  hooks.sensors?.(pane);
}

function renderData(pane, hooks) {
  pane.append(
    el('div', { class: 'section' }, el('h3', {}, 'Settings'),
      el('div', { class: 'row' },
        el('button', { type: 'button', onclick: () => download('oleander-settings.json', settings.exportJson(), 'application/json') }, 'Export settings'),
        el('button', { type: 'button', onclick: async () => {
          const f = await pickFile('.json');
          if (!f) return;
          try { await settings.importJson(await f.text()); toast('Settings imported'); } catch (e) { toast(e.message, 'error'); }
        } }, 'Import settings'),
        el('button', { type: 'button', class: 'danger', onclick: async () => {
          if (await confirmDialog('Reset all settings to defaults?', 'Reset', true)) {
            await settings.importJson(JSON.stringify({ oleanderSettings: 1, settings: DEFAULTS }));
          }
        } }, 'Reset settings'))),
    el('div', { class: 'section' }, el('h3', {}, 'Game metadata'),
      el('p', { class: 'sub' }, 'Game names come from the No-Intro database (libretro-database) matched by ROM checksum; cover art from libretro-thumbnails. Only the game name is sent, to raw.githubusercontent.com.'),
      el('div', { class: 'row' }, el('button', { type: 'button', onclick: () => hooks.scrape?.() }, 'Refresh cover art for all games'))),
    el('div', { class: 'section' }, el('h3', {}, 'Backup & restore'),
      el('p', { class: 'sub' }, 'A backup contains your library, ROMs, battery saves, save states, cheats, screenshots and settings.'),
      el('div', { class: 'row' },
        el('button', { type: 'button', onclick: () => hooks.backup?.(false) }, 'Back up everything'),
        el('button', { type: 'button', onclick: () => hooks.backup?.(true) }, 'Back up saves only'),
        el('button', { type: 'button', onclick: () => hooks.restore?.() }, 'Restore backup…'))),
  );
  hooks.cloud?.(pane);
  hooks.storageInfo?.(pane);
}

function renderAbout(pane, hooks) {
  pane.append(
    el('p', {}, el('b', {}, 'Oleander'), ` ${hooks.version || ''} – Game Boy Advance emulator.`),
    el('p', {}, 'Licensed under MIT OR Apache-2.0. The emulator core has no third-party dependencies. Test ROMs from jsmolka/gba-tests (MIT).'),
    el('p', { class: 'sub' }, 'Game Boy Advance is a trademark of Nintendo. Oleander is not affiliated with Nintendo. Only play games you own.'),
  );
}

export { BUTTONS };
