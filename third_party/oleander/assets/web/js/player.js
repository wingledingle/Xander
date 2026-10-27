// Gameplay: emulation loop, timing, audio/video output, save states,
// rewind, fast-forward/slow motion, screenshots, recording, playtime.
import { FRAME_RATE, AUDIO_RATE } from './core.js';
import { Renderer } from './renderer.js';
import { Audio } from './audio.js';
import { input } from './input.js';
import { sensors } from './sensors.js';
import { settings } from './settings.js';
import { db } from './storage.js';
import { el, toast, download, showDialog, closeDialog, pixelsToBlob, formatDate, pickFile } from './ui.js';

export const SLOTS = 10;

export class Player extends EventTarget {
  constructor(core) {
    super();
    this.core = core;
    this.audio = new Audio();
    this.screens = document.getElementById('screens');
    this.osdEl = document.getElementById('osd');
    this.statsEl = document.getElementById('stats');
    this.units = [];      // [{ con, renderer, canvas, game }]
    this.running = false;
    this.paused = false;
    this.ffHold = false;
    this.ffToggle = false;
    this.slow = false;
    this.rewinding = false;
    this.speed = 1;
    this.slot = 1;
    this.frame = 0;
    this.skipCounter = 0;
    this.acc = 0;
    this.last = 0;
    this.stat = { frames: 0, emuMs: 0, t0: performance.now(), fps: 0, speed: 0, emu: 0 };
    this.playStart = 0;
    this.recorder = null;
    this.linkHold = () => false;   // set by the link module to keep instances in sync
    this.linkPump = () => {};
    this.loop = this.loop.bind(this);
    window.addEventListener('resize', () => this.layout());
    window.addEventListener('oleander-layout', () => requestAnimationFrame(() => this.layout()));
    document.addEventListener('visibilitychange', () => this.onVisibility());
    window.addEventListener('pagehide', () => this.persist(true));
    settings.addEventListener('change', () => this.applySettings());
    input.addEventListener('hotkey', (e) => this.hotkey(e.detail.id));
    input.addEventListener('hold', (e) => {
      if (e.detail.id === 'fastForward') this.ffHold = input.isHeld('fastForward');
      if (e.detail.id === 'rewind') this.rewinding = input.isHeld('rewind');
    });
  }

  get main() { return this.units[0]; }
  get con() { return this.units[0]?.con; }

  // ------------------------------------------------------------------
  // Lifecycle
  // ------------------------------------------------------------------

  async open(game, rom, extra = []) {
    await this.close();
    await settings.bindGame(game.id);
    const biosRec = settings.get('useBios') ? await db.get('kv', 'bios') : null;
    const opts = { bios: biosRec?.data || null, saveType: settings.get('saveType'), skipBios: settings.get('skipBios') };
    const all = [{ game, rom }, ...extra];
    for (const { game: g, rom: r } of all) {
      const con = this.core.create(r, opts);
      const canvas = el('canvas', { width: 240, height: 160, 'aria-label': g.title });
      const renderer = new Renderer(canvas);
      this.screens.append(canvas);
      const save = await db.get('saves', g.id);
      if (save?.data) con.loadSave(save.data);
      this.units.push({ con, renderer, canvas, game: g });
    }
    this.screens.classList.toggle('multi', this.units.length > 1);
    this.applySettings();
    // Cheats for the main game.
    this.dispatchEvent(new CustomEvent('opened', { detail: { game } }));
    if (settings.get('autoRestore')) {
      const auto = await db.get('states', `${game.id}:auto`);
      if (auto) {
        try { this.con.loadState(auto.data); this.osd('Resumed where you left off'); } catch (e) { console.warn(e); }
      }
    }
    const opened = await db.update('games', game.id, (cur) => (cur ? { ...cur, lastPlayed: Date.now(), plays: (cur.plays || 0) + 1 } : undefined));
    if (opened) Object.assign(game, opened);
    // Let the link cable attach before the first frame runs, so the game
    // sees its player number from the start.
    this.beforeStart?.();
    this.running = true;
    this.paused = false;
    this.playStart = performance.now();
    this.lastPersist = performance.now();
    await this.audio.start();
    this.audio.clear();
    this.layout();
    this.last = performance.now();
    requestAnimationFrame(this.loop);
  }

  async close() {
    if (!this.units.length) return;
    this.running = false;
    await this.persist(true);
    if (this.recorder) this.stopRecording();
    for (const u of this.units) u.con.destroy();
    this.units = [];
    this.screens.replaceChildren();
    input.rumble(false);
    await settings.bindGame(null);
    this.dispatchEvent(new Event('closed'));
  }

  /// Flushes battery saves, play time and (optionally) the auto state.
  async persist(leaving = false) {
    for (const u of this.units) {
      if (u.con.saveDirty()) {
        u.con.clearSaveDirty();
        await db.put('saves', { data: u.con.saveData(), updated: Date.now() }, u.game.id);
        this.dispatchEvent(new CustomEvent('saved', { detail: { game: u.game } }));
      }
    }
    const g = this.main?.game;
    if (!g) return;
    const now = performance.now();
    if (!this.paused && this.playStart) {
      const played = (now - this.playStart) / 1000;
      this.playStart = now;
      const rec = await db.update('games', g.id, (cur) => (cur ? { ...cur, playtime: (cur.playtime || 0) + played, lastPlayed: Date.now() } : undefined));
      if (rec) Object.assign(g, rec);
    }
    if (leaving && settings.get('autoSaveState')) {
      const data = this.con.saveState();
      const thumb = await pixelsToBlob(this.con.pixels().slice());
      await db.put('states', { data, thumb, created: Date.now() }, `${g.id}:auto`);
    }
  }

  onVisibility() {
    if (!this.running) return;
    if (document.hidden) {
      this.persist(true);
      if (settings.get('pauseHidden')) { this.wasPausedByHide = !this.paused; this.setPaused(true); }
    } else if (this.wasPausedByHide) {
      this.wasPausedByHide = false;
      this.setPaused(false);
    }
  }

  setPaused(p, silent = false) {
    if (p === this.paused) return;
    if (p) this.persist(false);
    this.paused = p;
    if (!p) { this.playStart = performance.now(); this.last = performance.now(); this.audio.start(); }
    if (!silent) this.osd(p ? 'Paused' : 'Resumed');
    this.dispatchEvent(new Event('state'));
  }

  applySettings() {
    for (const u of this.units) {
      u.renderer.setShader(settings.get('shader'));
      u.renderer.params = { brightness: settings.get('brightness'), saturation: settings.get('saturation'), correct: settings.get('colorCorrection') };
      const sec = settings.get('rewindSeconds');
      const interval = settings.get('rewindInterval') || 2;
      // About 40 KB per snapshot delta on average; budget generously.
      if (settings.get('rewind')) u.con.rewindConfig(interval, Math.min(64 << 20, Math.ceil(sec * FRAME_RATE / interval) * 60 * 1024));
      else u.con.rewindConfig(0, 0);
    }
    this.audio.setVolume(settings.get('volume'));
    this.audio.setMuted(settings.get('mute'));
    this.audio.latencyMs = settings.get('latency');
    this.statsEl.classList.toggle('hidden', !settings.get('showStats'));
    this.layout();
  }

  layout() {
    if (!this.units.length) return;
    const r = this.screens.getBoundingClientRect();
    let w = r.width || innerWidth, h = r.height || innerHeight;
    const n = this.units.length;
    if (n > 1) w = w / n - 8;
    for (const u of this.units) u.renderer.layout(w, h, settings.get('scaling'), settings.get('aspect'));
  }

  // ------------------------------------------------------------------
  // Main loop
  // ------------------------------------------------------------------

  targetSpeed() {
    if (this.ffHold || this.ffToggle) {
      const s = Number(settings.get('ffSpeed'));
      return s === 0 ? 16 : s;
    }
    if (this.slow) return Number(settings.get('slowSpeed'));
    return this.speed;
  }

  loop(now) {
    if (!this.running) return;
    requestAnimationFrame(this.loop);
    const dt = Math.min(250, now - this.last);
    this.last = now;
    if (this.paused) { this.updateStats(now); return; }

    this.linkPump();
    if (this.linkHold()) return; // waiting for linked peers

    if (this.rewinding) {
      this.acc = 0;
      let ok = false;
      for (const u of this.units) ok = u.con.rewindStep() || ok;
      if (!ok) this.osd('No more rewind history');
      this.audio.clear();
      this.present();
      this.updateStats(now);
      return;
    }

    const speed = this.targetSpeed();
    const audioSync = settings.get('syncMode') === 'audio' && speed === 1 && this.audio.ready;
    let frames;
    if (audioSync) {
      // Emulate whenever the audio buffer runs low.
      const want = this.audio.latencyMs;
      frames = this.audio.bufferedMs() < want ? Math.ceil((want - this.audio.bufferedMs()) / (1000 / FRAME_RATE)) : 0;
      frames = Math.min(frames, 4);
    } else {
      this.acc += (dt / 1000) * FRAME_RATE * speed;
      frames = Math.floor(this.acc);
      this.acc -= frames;
      if (speed <= 1) frames = Math.min(frames, 4);
    }

    const budget = 14; // ms per animation frame we allow emulation to use
    const t0 = performance.now();
    const fixedSkip = settings.get('frameSkip');
    for (let i = 0; i < frames; i++) {
      const lastOne = i === frames - 1;
      let render = lastOne;
      if (fixedSkip !== 'auto' && Number(fixedSkip) > 0) {
        render = lastOne && (this.skipCounter++ % (Number(fixedSkip) + 1) === 0);
      }
      if (this.recorder) render = true;
      this.step(render);
      if (speed > 1 && performance.now() - t0 > budget) { this.acc = 0; break; }
    }
    const emuMs = performance.now() - t0;
    this.stat.emuMs += emuMs;
    if (frames > 0) {
      // Automatic frame skip: if emulation alone exceeds the frame budget, the
      // next presentation is dropped (emulation keeps full speed).
      if (fixedSkip === 'auto' && emuMs > 16 && this.autoSkip < 3) { this.autoSkip = (this.autoSkip || 0) + 1; }
      else { this.autoSkip = 0; this.present(); }
    }
    if (now - this.lastPersist > 5000) {
      this.lastPersist = now;
      this.maybeFlushSave();
    }
    this.updateStats(now);
  }

  /// Emulates one frame on every console.
  step(render) {
    this.frame++;
    const [tx, ty, gz, solar] = sensors.read();
    const local = new Date();
    const unix = Date.now() / 1000 - local.getTimezoneOffset() * 60 + settings.get('rtcOffsetMinutes') * 60;
    for (let i = 0; i < this.units.length; i++) {
      const u = this.units[i];
      const con = u.con;
      con.setKeys(input.poll(i, this.frame));
      if (con.info.tilt || con.info.gyro || con.info.solar) con.setSensors(tx, ty, gz, solar);
      if (con.info.rtc && (this.frame & 31) === 0) con.setTime(unix);
      con.setRender(render);
      const r = con.runFrame();
      if (r !== 0) {
        this.setPaused(true);
        this.dispatchEvent(new CustomEvent('debugstop', { detail: { reason: r === 1 ? 'breakpoint' : 'watchpoint' } }));
      }
    }
    this.stat.frames++;
    // Audio from the first console.
    const samples = this.con.takeAudio();
    for (let i = 1; i < this.units.length; i++) this.units[i].con.takeAudio();
    input.rumble(this.con.info.rumble || this.con.info.gyro ? this.con.rumble() : false);
    const speed = this.targetSpeed();
    if (speed > 1 && !settings.get('ffAudio')) return;
    this.audio.push(samples, AUDIO_RATE, Math.max(1, speed));
  }

  present() {
    for (const u of this.units) u.renderer.draw(u.con.pixels());
  }

  async maybeFlushSave() {
    // Debounced: write battery saves once the game stopped writing for ~0.5 s.
    for (const u of this.units) {
      if (u.con.saveDirty() && u.con.framesSinceSaveWrite() > 30) {
        u.con.clearSaveDirty();
        await db.put('saves', { data: u.con.saveData(), updated: Date.now() }, u.game.id);
        this.dispatchEvent(new CustomEvent('saved', { detail: { game: u.game } }));
      }
    }
    await this.persist(false);
  }

  updateStats(now) {
    const s = this.stat;
    if (now - s.t0 < 500) return;
    const secs = (now - s.t0) / 1000;
    s.fps = s.frames / secs;
    s.speed = (s.fps / FRAME_RATE) * 100;
    s.emu = s.frames ? s.emuMs / s.frames : 0;
    s.frames = 0; s.emuMs = 0; s.t0 = now;
    if (!this.statsEl.classList.contains('hidden') && this.con) {
      const mem = performance.memory ? `\nJS heap ${(performance.memory.usedJSHeapSize / 1048576).toFixed(0)} MB` : '';
      this.statsEl.textContent =
        `${s.fps.toFixed(1)} fps  ${s.speed.toFixed(0)}%\n` +
        `emu ${s.emu.toFixed(2)} ms/frame\n` +
        `audio ${this.audio.bufferedMs().toFixed(0)} ms\n` +
        `rewind ${this.con.rewindSeconds().toFixed(0)} s\n` +
        `slot ${this.slot}${this.paused ? '  PAUSED' : ''}${mem}`;
    }
    this.dispatchEvent(new CustomEvent('stats', { detail: s }));
  }

  osd(msg) {
    this.osdEl.textContent = msg;
    this.osdEl.classList.add('show');
    clearTimeout(this.osdTimer);
    this.osdTimer = setTimeout(() => this.osdEl.classList.remove('show'), 1600);
  }

  // ------------------------------------------------------------------
  // Actions
  // ------------------------------------------------------------------

  hotkey(id) {
    if (!this.running) return;
    switch (id) {
      case 'pause': this.setPaused(!this.paused); break;
      case 'fastForwardToggle': this.ffToggle = !this.ffToggle; this.osd(this.ffToggle ? 'Fast forward' : 'Normal speed'); break;
      case 'slowMotion': this.slow = !this.slow; this.osd(this.slow ? 'Slow motion' : 'Normal speed'); break;
      case 'quickSave': this.saveSlot(this.slot); break;
      case 'quickLoad': this.loadSlot(this.slot); break;
      case 'slotNext': this.slot = (this.slot % SLOTS) + 1; this.osd(`Slot ${this.slot}`); break;
      case 'slotPrev': this.slot = ((this.slot + SLOTS - 2) % SLOTS) + 1; this.osd(`Slot ${this.slot}`); break;
      case 'screenshot': this.screenshot(); break;
      case 'record': this.recorder ? this.stopRecording() : this.startRecording(); break;
      case 'fullscreen': this.toggleFullscreen(); break;
      case 'mute': settings.set('mute', !settings.global.mute); this.osd(settings.global.mute ? 'Muted' : 'Sound on'); break;
      case 'reset': this.reset(); break;
      case 'stats': settings.set('showStats', !settings.global.showStats); break;
      case 'frameAdvance': if (this.paused) { this.step(true); this.present(); } break;
      default: this.dispatchEvent(new CustomEvent('hotkey', { detail: { id } }));
    }
  }

  reset() {
    for (const u of this.units) u.con.reset();
    this.osd('Reset');
  }

  setSpeed(s) {
    this.speed = s;
    this.osd(`Speed ${s}×`);
  }

  async saveSlot(slot) {
    const g = this.main.game;
    const data = this.con.saveState();
    const thumb = await pixelsToBlob(this.con.pixels().slice());
    await db.put('states', { data, thumb, created: Date.now() }, `${g.id}:${slot}`);
    this.osd(`Saved state ${slot}`);
  }

  async loadSlot(slot) {
    const g = this.main.game;
    const rec = await db.get('states', `${g.id}:${slot}`);
    if (!rec) return this.osd(`Slot ${slot} is empty`);
    try {
      this.con.loadState(rec.data);
      this.audio.clear();
      this.present();
      this.osd(`Loaded state ${slot}`);
    } catch (e) {
      toast(`Could not load state: ${e.message}`, 'error');
    }
  }

  async statesDialog() {
    const g = this.main.game;
    const wrap = el('div', { class: 'slots' });
    const render = async () => {
      wrap.replaceChildren();
      for (const slot of ['auto', ...Array.from({ length: SLOTS }, (_, i) => i + 1)]) {
        const rec = await db.get('states', `${g.id}:${slot}`);
        const img = rec?.thumb ? el('img', { src: URL.createObjectURL(rec.thumb), alt: '' }) : el('canvas', { width: 240, height: 160 });
        const actions = el('div', { class: 'slot-actions' });
        if (slot !== 'auto') actions.append(el('button', { type: 'button', onclick: async () => { await this.saveSlot(slot); render(); } }, 'Save'));
        if (rec) {
          actions.append(
            el('button', { type: 'button', class: 'primary', onclick: async () => { await this.loadSlot(slot); closeDialog(); this.setPaused(false); } }, 'Load'),
            el('button', { type: 'button', title: 'Export', onclick: () => download(`${g.title || g.id}-${slot}.ost`, rec.data) }, '⤓'),
            el('button', { type: 'button', class: 'danger', title: 'Delete', onclick: async () => { await db.del('states', `${g.id}:${slot}`); render(); } }, '✕'));
        }
        wrap.append(el('div', { class: 'slot' }, img, el('div', {}, slot === 'auto' ? 'Auto' : `Slot ${slot}`),
          el('div', { class: 'sub' }, rec ? formatDate(rec.created) : 'empty'), actions));
      }
    };
    await render();
    const importBtn = el('button', { type: 'button', onclick: async () => {
      const f = await pickFile('.ost,.state');
      if (!f) return;
      try { this.con.loadState(new Uint8Array(await f.arrayBuffer())); toast('State loaded'); closeDialog(); } catch (e) { toast(e.message, 'error'); }
    } }, 'Load state from file…');
    return showDialog('Save states', el('div', {}, wrap, el('div', { class: 'dlg-actions' }, importBtn)), { wide: true });
  }

  async screenshot() {
    const png = this.con.screenshotPng(1);
    const blob = new Blob([png], { type: 'image/png' });
    await db.put('screenshots', { gameId: this.main.game.id, blob, created: Date.now() });
    const g = this.main.game;
    // Use the first screenshot as cover art when none is set.
    await db.update('games', g.id, (cur) => (cur && !cur.cover ? { ...cur, cover: blob, coverSource: 'screenshot' } : undefined));
    download(`${(g.title || 'oleander').replace(/[^\w-]+/g, '_')}-${Date.now()}.png`, blob);
    this.osd('Screenshot saved');
  }

  startRecording() {
    const canvas = this.main.canvas;
    if (!canvas.captureStream || typeof MediaRecorder === 'undefined') return toast('Recording is not supported by this browser', 'error');
    const stream = canvas.captureStream(60);
    const a = this.audio.stream();
    if (a) a.getAudioTracks().forEach((t) => stream.addTrack(t));
    const type = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm', 'video/mp4'].find((t) => MediaRecorder.isTypeSupported(t));
    this.chunks = [];
    this.recorder = new MediaRecorder(stream, { mimeType: type, videoBitsPerSecond: 6_000_000 });
    this.recorder.ondataavailable = (e) => e.data.size && this.chunks.push(e.data);
    this.recorder.onstop = () => {
      const blob = new Blob(this.chunks, { type: type || 'video/webm' });
      download(`${(this.main?.game.title || 'oleander').replace(/[^\w-]+/g, '_')}-${Date.now()}.${(type || '').includes('mp4') ? 'mp4' : 'webm'}`, blob);
    };
    this.recorder.start(1000);
    this.osd('● Recording');
    this.dispatchEvent(new Event('state'));
  }

  stopRecording() {
    this.recorder?.stop();
    this.recorder = null;
    this.osd('Recording saved');
    this.dispatchEvent(new Event('state'));
  }

  toggleFullscreen() {
    const v = document.getElementById('player-view');
    if (document.fullscreenElement) document.exitFullscreen();
    else v.requestFullscreen?.({ navigationUI: 'hide' }).then(() => screen.orientation?.lock?.('landscape').catch(() => {})).catch(() => {});
  }
}
