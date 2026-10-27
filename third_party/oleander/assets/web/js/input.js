// Input: keyboard, gamepads (USB/Bluetooth via the Gamepad API), touch
// controls, turbo buttons, hotkeys and rumble.
import { BUTTON_BITS, BUTTONS, FRAME_RATE } from './core.js';
import { settings } from './settings.js';

const HOLD_HOTKEYS = new Set(['fastForward', 'rewind']);

export class Input extends EventTarget {
  constructor() {
    super();
    this.keys = new Set();
    this.touchMask = 0;       // GBA buttons from the touch overlay
    this.touchTurbo = 0;      // turbo buttons held on the overlay
    this.held = new Set();    // held hold-hotkeys
    this.capture = null;
    this.enabled = true;
    this.tiltKeys = { x: 0, y: 0, z: 0 };
    this.mouse = { x: 0, y: 0 };
    this.lastPadButtons = [];
  }

  attach() {
    window.addEventListener('keydown', (e) => this.onKey(e, true));
    window.addEventListener('keyup', (e) => this.onKey(e, false));
    window.addEventListener('blur', () => { this.keys.clear(); this.held.clear(); });
    window.addEventListener('mousemove', (e) => {
      this.mouse.x = (e.clientX / innerWidth) * 2 - 1;
      this.mouse.y = (e.clientY / innerHeight) * 2 - 1;
    });
    window.addEventListener('gamepadconnected', (e) => this.dispatchEvent(new CustomEvent('pad', { detail: { connected: true, id: e.gamepad.id } })));
    window.addEventListener('gamepaddisconnected', (e) => this.dispatchEvent(new CustomEvent('pad', { detail: { connected: false, id: e.gamepad.id } })));
    // Android app: controllers are read natively (WebView has no Gamepad
    // API) and reported as standard-layout pads.
    this.nativePads = [];
    window.oleanderNativePad = (i, bits, x, y, name) => {
      let p = this.nativePads[i];
      if (!p) {
        p = this.nativePads[i] = { id: name, index: i, mapping: 'standard', connected: true, axes: [0, 0, 0, 0], buttons: Array.from({ length: 17 }, () => ({ pressed: false, value: 0 })) };
        this.dispatchEvent(new CustomEvent('pad', { detail: { connected: true, id: name } }));
      }
      p.buttons.forEach((b, n) => { b.pressed = !!(bits & (1 << n)); b.value = b.pressed ? 1 : 0; });
      p.axes[0] = x;
      p.axes[1] = y;
      p.timestamp = performance.now();
    };
  }

  onKey(e, down) {
    const t = e.target;
    const typing = t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT') && t.type !== 'range' && t.type !== 'checkbox';
    if (this.capture && down) {
      e.preventDefault();
      const c = this.capture;
      this.capture = null;
      c(e.code);
      return;
    }
    if (typing || !this.enabled) return;
    const hk = settings.global.hotkeys;
    for (const [id, code] of Object.entries(hk)) {
      if (code && code === e.code) {
        e.preventDefault();
        if (HOLD_HOTKEYS.has(id)) {
          if (down) this.held.add(id); else this.held.delete(id);
          this.dispatchEvent(new CustomEvent('hold', { detail: { id, down } }));
        } else if (down && !e.repeat) {
          this.dispatchEvent(new CustomEvent('hotkey', { detail: { id } }));
        }
        return;
      }
    }
    const map = settings.keyMap();
    if (Object.values(map).includes(e.code)) e.preventDefault();
    if (down) this.keys.add(e.code); else this.keys.delete(e.code);
    // Keyboard tilt (I/J/K/L, U/O for rotation)
    const tk = { KeyJ: ['x', -1], KeyL: ['x', 1], KeyI: ['y', -1], KeyK: ['y', 1], KeyU: ['z', -1], KeyO: ['z', 1] }[e.code];
    if (tk && settings.get('tiltSource') === 'keys') this.tiltKeys[tk[0]] = down ? tk[1] : 0;
  }

  captureKey() {
    return new Promise((resolve) => { this.capture = resolve; });
  }

  /// Waits for any gamepad button press; resolves with its index.
  capturePad(timeoutMs = 8000) {
    return new Promise((resolve) => {
      const start = performance.now();
      const base = this.padButtons(0);
      const tick = () => {
        const now = this.padButtons(0);
        const idx = now.findIndex((p, i) => p && !base[i]);
        if (idx >= 0) return resolve(idx);
        if (performance.now() - start > timeoutMs) return resolve(null);
        requestAnimationFrame(tick);
      };
      tick();
    });
  }

  pads() {
    const pads = navigator.getGamepads ? [...navigator.getGamepads()].filter(Boolean) : [];
    return this.nativePads.length ? pads.concat(this.nativePads.filter(Boolean)) : pads;
  }

  padButtons(n) {
    const p = this.pads()[n];
    return p ? p.buttons.map((b) => b.pressed || b.value > 0.5) : [];
  }

  padState(n, frame) {
    const p = this.pads()[n];
    if (!p) return 0;
    const map = settings.padMap();
    const dz = settings.global.padDeadzone;
    let mask = 0;
    let turbo = 0;
    for (const [btn, idx] of Object.entries(map)) {
      const b = p.buttons[idx];
      if (b && (b.pressed || b.value > 0.5)) {
        if (btn === 'TurboA') turbo |= BUTTON_BITS.A;
        else if (btn === 'TurboB') turbo |= BUTTON_BITS.B;
        else if (BUTTON_BITS[btn]) mask |= BUTTON_BITS[btn];
      }
    }
    // Left stick as D-pad.
    const [x = 0, y = 0] = p.axes;
    if (x < -dz) mask |= BUTTON_BITS.Left;
    if (x > dz) mask |= BUTTON_BITS.Right;
    if (y < -dz) mask |= BUTTON_BITS.Up;
    if (y > dz) mask |= BUTTON_BITS.Down;
    // Hotkey combos on the pad: Select+R = fast forward, Select+L = rewind,
    // Select+Start = menu (only with an otherwise idle Select).
    const hold = mask & BUTTON_BITS.Select;
    this.padHold(n, 'fastForward', !!(hold && mask & BUTTON_BITS.R));
    this.padHold(n, 'rewind', !!(hold && mask & BUTTON_BITS.L));
    const menuCombo = !!(hold && mask & BUTTON_BITS.Start);
    const key = `menu${n}`;
    if (menuCombo && !this.lastPadButtons[key]) this.dispatchEvent(new CustomEvent('hotkey', { detail: { id: 'menu' } }));
    this.lastPadButtons[key] = menuCombo;
    if (hold && mask & (BUTTON_BITS.R | BUTTON_BITS.L | BUTTON_BITS.Start)) mask = 0;
    return mask | this.turbo(turbo, frame);
  }

  padHold(n, id, on) {
    const k = `pad${n}:${id}`;
    if (on && !this.held.has(k)) {
      this.held.add(k);
      this.dispatchEvent(new CustomEvent('hold', { detail: { id, down: true } }));
    } else if (!on && this.held.has(k)) {
      this.held.delete(k);
      this.dispatchEvent(new CustomEvent('hold', { detail: { id, down: false } }));
    }
  }

  isHeld(id) {
    for (const h of this.held) if (h === id || h.endsWith(':' + id)) return true;
    return false;
  }

  turbo(mask, frame) {
    if (!mask) return 0;
    const period = Math.max(2, Math.round(FRAME_RATE / settings.get('turboRate')));
    return frame % period < period / 2 ? mask : 0;
  }

  /// Button mask for player `n` (0 = keyboard + touch + first pad).
  poll(n, frame) {
    if (!this.enabled) return 0;
    let mask = 0;
    if (n === 0) {
      const map = settings.keyMap();
      let turbo = this.touchTurbo;
      for (const b of BUTTONS) if (this.keys.has(map[b])) mask |= BUTTON_BITS[b];
      if (this.keys.has(map.TurboA)) turbo |= BUTTON_BITS.A;
      if (this.keys.has(map.TurboB)) turbo |= BUTTON_BITS.B;
      mask |= this.touchMask | this.turbo(turbo, frame);
      mask |= this.padState(0, frame);
    } else {
      mask |= this.padState(n, frame);
    }
    // Opposite directions cannot be pressed together on a real D-pad.
    if ((mask & 0x30) === 0x30) mask &= ~0x30;
    if ((mask & 0xC0) === 0xC0) mask &= ~0xC0;
    return mask;
  }

  /// Rumble feedback on phones and supported controllers.
  rumble(on) {
    if (on === this.rumbling) return;
    this.rumbling = on;
    if (!settings.global.haptics) return;
    if (navigator.vibrate) navigator.vibrate(on ? 5000 : 0);
    for (const p of this.pads()) {
      const a = p.vibrationActuator;
      if (!a) continue;
      if (on) a.playEffect?.('dual-rumble', { duration: 5000, strongMagnitude: 0.8, weakMagnitude: 0.5 }).catch(() => {});
      else a.reset?.().catch(() => {});
    }
  }

  haptic(ms = 12) {
    if (settings.global.haptics && navigator.vibrate) navigator.vibrate(ms);
  }
}

export const input = new Input();
