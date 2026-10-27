// Thin wrapper around the Oleander WebAssembly core (C ABI exports).

const enc = new TextEncoder();
const dec = new TextDecoder();

export const SAVE_TYPES = ['auto', 'none', 'sram', 'flash64', 'flash128', 'eeprom512', 'eeprom8k'];
export const CHEAT_FORMATS = ['auto', 'raw', 'gameshark', 'actionreplay', 'codebreaker', 'gameshark-raw', 'actionreplay-raw'];
export const BUTTONS = ['A', 'B', 'Select', 'Start', 'Right', 'Left', 'Up', 'Down', 'R', 'L'];
export const BUTTON_BITS = Object.fromEntries(BUTTONS.map((b, i) => [b, 1 << i]));
export const FRAME_RATE = 16777216 / 280896;
export const AUDIO_RATE = 32768;

export class Core {
  static async load(url = 'oleander.wasm') {
    let instance;
    try {
      ({ instance } = await WebAssembly.instantiateStreaming(fetch(url), {}));
    } catch {
      const bytes = await (await fetch(url)).arrayBuffer();
      ({ instance } = await WebAssembly.instantiate(bytes, {}));
    }
    return new Core(instance.exports);
  }

  constructor(x) {
    this.x = x;
    const n = x.ol_version();
    this.version = this.text(n);
  }

  get mem() { return this.x.memory.buffer; }

  // Copies bytes into wasm memory; returns [ptr, len]. Caller frees.
  put(bytes) {
    const u8 = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const p = this.x.ol_alloc(u8.length);
    new Uint8Array(this.mem, p, u8.length).set(u8);
    return [p, u8.length];
  }

  free(p, len) { this.x.ol_free(p, len); }

  result(len) { return new Uint8Array(this.mem, this.x.ol_result_ptr(), len).slice(); }

  text(len) { return dec.decode(new Uint8Array(this.mem, this.x.ol_result_ptr(), len)); }

  withBytes(bytes, fn) {
    const [p, n] = this.put(bytes);
    try { return fn(p, n); } finally { this.free(p, n); }
  }

  withText(str, fn) { return this.withBytes(enc.encode(str), fn); }

  extractRom(bytes) {
    return this.withBytes(bytes, (p, n) => {
      const len = this.x.ol_extract_rom(p, n);
      if (!len) throw new Error('Could not read archive');
      return this.result(len);
    });
  }

  stateThumbnail(state) {
    return this.withBytes(state, (p, n) => {
      const len = this.x.ol_state_thumbnail(p, n);
      return len ? new Uint8ClampedArray(this.result(len).buffer) : null;
    });
  }

  create(rom, { bios = null, saveType = 'auto', skipBios = false } = {}) {
    const [rp, rn] = this.put(rom);
    const [bp, bn] = bios ? this.put(bios) : [0, 0];
    try {
      const h = this.x.ol_create(rp, rn, bp, bn, Math.max(0, SAVE_TYPES.indexOf(saveType)), skipBios ? 1 : 0);
      if (!h) throw new Error(this.text(this.x.ol_result_len()));
      return new Console(this, h);
    } finally {
      this.free(rp, rn);
      if (bn) this.free(bp, bn);
    }
  }
}

export class Console {
  constructor(core, h) {
    this.core = core;
    this.x = core.x;
    this.h = h;
    this.info = JSON.parse(core.text(this.x.ol_info(h)));
  }

  destroy() { this.x.ol_destroy(this.h); this.h = 0; }
  reset() { this.x.ol_reset(this.h); }

  runFrame() { return this.x.ol_run_frame(this.h); }
  step() { this.x.ol_step(this.h); }
  setRender(on) { this.x.ol_set_render(this.h, on ? 1 : 0); }
  setKeys(mask) { this.x.ol_set_keys(this.h, mask); }
  setSensors(tx, ty, gz, solar) { this.x.ol_set_sensors(this.h, tx, ty, gz, solar); }
  setTime(unix) { this.x.ol_set_time(this.h, unix); }
  rumble() { return this.x.ol_rumble(this.h) !== 0; }
  frameCount() { return this.x.ol_frame_count(this.h); }
  cycles() { return this.x.ol_cycles(this.h); }
  setLayerMask(m) { this.x.ol_layer_mask(this.h, m); }
  setChannelMask(m) { this.x.ol_channel_mask(this.h, m); }

  // RGBA view of the frame (valid until wasm memory grows).
  pixels() { return new Uint8Array(this.core.mem, this.x.ol_framebuffer(this.h), 240 * 160 * 4); }

  // Takes audio produced since the last call: Int16 interleaved stereo @ 32768 Hz (copy).
  takeAudio() {
    const n = this.x.ol_audio_len(this.h);
    const out = n ? new Int16Array(this.core.mem, this.x.ol_audio_ptr(this.h), n).slice() : new Int16Array(0);
    this.x.ol_audio_clear(this.h);
    return out;
  }

  saveData() { return this.core.result(this.x.ol_save_data(this.h)); }
  loadSave(bytes) { this.core.withBytes(bytes, (p, n) => this.x.ol_load_save(this.h, p, n)); }
  saveDirty() { return this.x.ol_save_dirty(this.h) !== 0; }
  clearSaveDirty() { this.x.ol_clear_save_dirty(this.h); }
  framesSinceSaveWrite() { return this.x.ol_frames_since_save_write(this.h); }

  saveState() { return this.core.result(this.x.ol_save_state(this.h, Date.now() / 1000)); }
  loadState(bytes) {
    this.core.withBytes(bytes, (p, n) => {
      const r = this.x.ol_load_state(this.h, p, n);
      if (r) throw new Error(this.core.text(r));
    });
  }
  screenshotPng(scale = 1) { return this.core.result(this.x.ol_screenshot_png(this.h, scale)); }

  rewindConfig(interval, budget) { this.x.ol_rewind_config(this.h, interval, budget); }
  rewindStep() { return this.x.ol_rewind_step(this.h) !== 0; }
  rewindSeconds() { return this.x.ol_rewind_seconds(this.h); }

  addCheat(name, code, format = 'auto') {
    const fmt = Math.max(0, ['auto', 'raw', 'gameshark', 'actionreplay', 'codebreaker', 'gameshark-raw', 'actionreplay-raw'].indexOf(format));
    const nb = enc.encode(name), cb = enc.encode(code);
    const [np, nn] = this.core.put(nb), [cp, cn] = this.core.put(cb);
    try {
      const r = this.x.ol_cheat_add(this.h, np, nn, cp, cn, fmt);
      if (!r) throw new Error(this.core.text(this.x.ol_result_len()));
      return r - 1;
    } finally { this.core.free(np, nn); this.core.free(cp, cn); }
  }
  cheatFormat(i) { return this.core.text(this.x.ol_cheat_format(this.h, i)); }
  enableCheat(i, on) { this.x.ol_cheat_enable(this.h, i, on ? 1 : 0); }
  clearCheats() { this.x.ol_cheat_clear(this.h); }

  regs() { return Array.from(new Uint32Array(this.core.mem, this.x.ol_regs(this.h), 18)); }
  thumb() { return this.x.ol_thumb(this.h) !== 0; }
  readMem(addr, len) { return this.core.result(this.x.ol_read_mem(this.h, addr >>> 0, len)); }
  poke(addr, value, size) { this.x.ol_poke(this.h, addr >>> 0, value >>> 0, size); }
  disasm(addr, count, mode = 2) {
    return this.core.text(this.x.ol_disasm(this.h, addr >>> 0, count, mode)).trim().split('\n').filter(Boolean).map((l) => {
      const [a, op, text] = l.split('|');
      return { addr: parseInt(a, 16), op, text };
    });
  }
  breakpoint(addr, add) { this.x.ol_breakpoint(this.h, addr >>> 0, add ? 1 : 0); }
  clearBreakpoints() { this.x.ol_breakpoints_clear(this.h); }
  watchpoint(start, end, r, w) { this.x.ol_watchpoint(this.h, start >>> 0, end >>> 0, r ? 1 : 0, w ? 1 : 0); }
  clearWatchpoints() { this.x.ol_watchpoints_clear(this.h); }

  linkEnable(player, connected) { this.x.ol_link_enable(this.h, player, connected); }
  linkDisable() { this.x.ol_link_disable(this.h); }
  // Returns an array of Uint8Array messages to send to peers.
  linkTake() {
    const data = this.core.result(this.x.ol_link_take(this.h));
    const out = [];
    for (let i = 0; i < data.length;) { const n = data[i]; out.push(data.slice(i + 1, i + 1 + n)); i += 1 + n; }
    return out;
  }
  linkDeliver(msg) { this.core.withBytes(msg, (p, n) => this.x.ol_link_deliver(this.h, p, n)); }
  linkTransfers() { return this.x.ol_link_transfers(this.h); }
}
