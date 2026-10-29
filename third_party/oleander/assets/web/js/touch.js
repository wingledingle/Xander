// On-screen touch controls: multitouch, sliding between buttons, 8-way
// D-pad, separate portrait/landscape layouts, and a layout editor
// (move, resize, hide, opacity).
import { BUTTON_BITS } from './core.js';
import { settings } from './settings.js';
import { input } from './input.js';
import { el } from './ui.js';

// Positions are percentages of the screen; sizes are % of the shorter side.
// The landscape layout puts the controls on the grips either side of the
// screen, like the real handheld; portrait puts them below the screen.
const DEFAULT_LAYOUT = {
  landscape: [
    { id: 'dpad', kind: 'dpad', x: 10.5, y: 48, size: 30 },
    { id: 'A', kind: 'button', buttons: ['A'], label: 'A', x: 93, y: 41, size: 15 },
    { id: 'B', kind: 'button', buttons: ['B'], label: 'B', x: 84, y: 53, size: 15 },
    { id: 'L', kind: 'button', buttons: ['L'], label: 'L', x: 9, y: 9, size: 13, shape: 'shoulder' },
    { id: 'R', kind: 'button', buttons: ['R'], label: 'R', x: 91, y: 9, size: 13, shape: 'shoulder' },
    { id: 'Select', kind: 'button', buttons: ['Select'], label: '', sub: 'SELECT', x: 6.5, y: 83, size: 6, shape: 'pill' },
    { id: 'Start', kind: 'button', buttons: ['Start'], label: '', sub: 'START', x: 15, y: 83, size: 6, shape: 'pill' },
    { id: 'Menu', kind: 'button', hotkey: 'menu', label: 'MENU', x: 84, y: 85, size: 9, shape: 'menu' },
    { id: 'FF', kind: 'button', hotkey: 'fastForward', label: '»', x: 84, y: 22, size: 8, shape: 'menu' },
    { id: 'AB', kind: 'button', buttons: ['A', 'B'], label: 'A+B', x: 93, y: 62, size: 9, hidden: true },
    { id: 'TurboA', kind: 'button', turbo: ['A'], label: 'TA', x: 93, y: 24, size: 10, hidden: true },
    { id: 'TurboB', kind: 'button', turbo: ['B'], label: 'TB', x: 84, y: 34, size: 10, hidden: true },
    { id: 'QS', kind: 'button', hotkey: 'quickSave', label: 'SAVE', x: 6.5, y: 22, size: 8, shape: 'menu', hidden: true },
    { id: 'QL', kind: 'button', hotkey: 'quickLoad', label: 'LOAD', x: 15, y: 22, size: 8, shape: 'menu', hidden: true },
    { id: 'RW', kind: 'button', hotkey: 'rewind', label: '«', x: 93, y: 22, size: 8, shape: 'menu', hidden: true },
  ],
  // Portrait positions are relative to the area below the screen.
  portrait: [
    { id: 'dpad', kind: 'dpad', x: 24, y: 50, size: 40 },
    { id: 'A', kind: 'button', buttons: ['A'], label: 'A', x: 87, y: 42, size: 18 },
    { id: 'B', kind: 'button', buttons: ['B'], label: 'B', x: 70, y: 58, size: 18 },
    { id: 'L', kind: 'button', buttons: ['L'], label: 'L', x: 11, y: 9, size: 17, shape: 'shoulder' },
    { id: 'R', kind: 'button', buttons: ['R'], label: 'R', x: 89, y: 9, size: 17, shape: 'shoulder' },
    { id: 'Select', kind: 'button', buttons: ['Select'], label: '', sub: 'SELECT', x: 40, y: 86, size: 8, shape: 'pill' },
    { id: 'Start', kind: 'button', buttons: ['Start'], label: '', sub: 'START', x: 60, y: 86, size: 8, shape: 'pill' },
    { id: 'Menu', kind: 'button', hotkey: 'menu', label: 'MENU', x: 50, y: 9, size: 13, shape: 'menu' },
    { id: 'FF', kind: 'button', hotkey: 'fastForward', label: '»', x: 50, y: 24, size: 10, shape: 'menu' },
    { id: 'AB', kind: 'button', buttons: ['A', 'B'], label: 'A+B', x: 87, y: 70, size: 11, hidden: true },
    { id: 'TurboA', kind: 'button', turbo: ['A'], label: 'TA', x: 87, y: 24, size: 12, hidden: true },
    { id: 'TurboB', kind: 'button', turbo: ['B'], label: 'TB', x: 70, y: 30, size: 12, hidden: true },
    { id: 'QS', kind: 'button', hotkey: 'quickSave', label: 'SAVE', x: 30, y: 9, size: 10, shape: 'menu' },
    { id: 'QL', kind: 'button', hotkey: 'quickLoad', label: 'LOAD', x: 70, y: 9, size: 10, shape: 'menu' },
    { id: 'RW', kind: 'button', hotkey: 'rewind', label: '«', x: 37, y: 24, size: 10, shape: 'menu', hidden: true },
  ],
};

export class TouchControls {
  constructor(layer) {
    this.layer = layer;
    this.pointers = new Map(); // pointerId -> control id(s) / dpad
    this.editing = false;
    this.selected = null;
    this.nodes = new Map();
    layer.addEventListener('pointerdown', (e) => this.down(e));
    layer.addEventListener('pointermove', (e) => this.move(e));
    layer.addEventListener('pointerup', (e) => this.up(e));
    layer.addEventListener('pointercancel', (e) => this.up(e));
    layer.addEventListener('contextmenu', (e) => e.preventDefault());
    window.addEventListener('resize', () => this.render());
    settings.addEventListener('change', (e) => {
      if (['touchControls', 'touchOpacity', 'touchLayout', 'touchScale', 'handheldFrame', '*'].includes(e.detail.key)) this.render();
    });
  }

  get orientation() {
    return innerHeight > innerWidth ? 'portrait' : 'landscape';
  }

  layout() {
    const saved = settings.global.touchLayout?.[this.orientation];
    const base = DEFAULT_LAYOUT[this.orientation];
    if (!saved) return structuredClone(base);
    // Merge so new default controls appear after upgrades.
    return base.map((d) => ({ ...d, ...(saved.find((s) => s.id === d.id) || {}) }));
  }

  visible() {
    const mode = settings.global.touchControls;
    if (mode === 'never') return false;
    if (mode === 'always') return true;
    return matchMedia('(pointer: coarse)').matches || 'ontouchstart' in window;
  }

  render() {
    const visible = this.visible();
    const frame = settings.global.handheldFrame;
    document.body.classList.toggle('portrait', this.orientation === 'portrait');
    document.body.classList.toggle('touch-on', visible);
    document.body.classList.toggle('handheld', frame === 'always' || (frame === 'auto' && (visible || this.editing)));
    document.body.classList.toggle('edge', frame === 'edge');
    this.layer.replaceChildren();
    this.nodes.clear();
    // In portrait the controls live in the body below the screen.
    const shell = document.getElementById('shell');
    const below = this.orientation === 'portrait' && (document.body.classList.contains('handheld') || document.body.classList.contains('edge')) && shell?.offsetParent;
    this.layer.style.top = below ? Math.round(shell.getBoundingClientRect().bottom) + 'px' : '';
    window.dispatchEvent(new Event('oleander-layout'));
    if (!visible && !this.editing) return;
    if (!this.editing || !this.controls) this.controls = this.layout();
    const vmin = (Math.min(innerWidth, innerHeight) / 100) * (settings.global.touchScale || 1);
    this.layer.style.opacity = this.editing ? 1 : settings.global.touchOpacity;
    for (const c of this.controls) {
      if (c.hidden && !this.editing) continue;
      const size = c.size * vmin;
      const shape = c.shape || '';
      const h = shape === 'shoulder' ? size * 0.5 : shape === 'pill' ? size * 0.45 : shape === 'menu' || shape === 'rect' ? size * 0.5 : size;
      const cls = ['tbtn', c.kind === 'dpad' ? 'dpad' : '', shape === 'shoulder' ? 'shoulder-btn' : '', shape === 'pill' || shape === 'rect' ? 'rect' : '',
        shape === 'menu' ? 'menu-btn' : '', this.selected === c.id ? 'selected' : ''].filter(Boolean).join(' ');
      const node = el('div', {
        class: cls,
        style: {
          left: c.x + '%', top: c.y + '%', width: size + 'px', height: h + 'px',
          fontSize: Math.max(10, Math.min(size, h) * 0.42) + 'px', opacity: c.hidden ? 0.35 : 1,
        },
      }, c.kind === 'dpad' ? el('span', { class: 'dot' }) : c.label, c.sub ? el('span', { class: 'lbl' }, c.sub) : null);
      node.dataset.id = c.id;
      this.layer.append(node);
      this.nodes.set(c.id, node);
    }
    if (this.editing) this.renderToolbar();
  }

  hit(x, y) {
    // Largest-first so the D-pad does not shadow small buttons on top of it.
    let best = null;
    for (const c of this.controls || []) {
      if (c.hidden) continue;
      const n = this.nodes.get(c.id);
      if (!n) continue;
      const r = n.getBoundingClientRect();
      const pad = c.kind === 'dpad' ? 0 : r.width * 0.15;
      if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) {
        if (!best || c.kind !== 'dpad') best = { c, r };
      }
    }
    return best;
  }

  dpadMask(r, x, y) {
    const cx = r.left + r.width / 2, cy = r.top + r.height / 2;
    const dx = x - cx, dy = y - cy;
    if (Math.hypot(dx, dy) < r.width * 0.12) return 0;
    const a = Math.atan2(dy, dx) * 180 / Math.PI; // -180..180, 0 = right
    let m = 0;
    if (a > -67.5 && a < 67.5) m |= BUTTON_BITS.Right;
    if (a > 112.5 || a < -112.5) m |= BUTTON_BITS.Left;
    if (a > 22.5 && a < 157.5) m |= BUTTON_BITS.Down;
    if (a < -22.5 && a > -157.5) m |= BUTTON_BITS.Up;
    return m;
  }

  evaluate() {
    let mask = 0, turbo = 0, ff = false, rw = false;
    const taps = new Set(); // one-shot hotkeys (menu, quick save/load) fire once per press
    for (const p of this.pointers.values()) {
      if (!p.hit) continue;
      const { c, r } = p.hit;
      if (c.kind === 'dpad') mask |= this.dpadMask(r, p.x, p.y);
      for (const b of c.buttons || []) mask |= BUTTON_BITS[b];
      for (const b of c.turbo || []) turbo |= BUTTON_BITS[b];
      if (c.hotkey === 'fastForward') ff = true;
      else if (c.hotkey === 'rewind') rw = true;
      else if (c.hotkey) taps.add(c.hotkey);
    }
    if (mask & ~input.touchMask) input.haptic(10);
    input.touchMask = mask;
    input.touchTurbo = turbo;
    input.padHold('touch', 'fastForward', ff);
    input.padHold('touch', 'rewind', rw);
    // One-shot buttons fire when the finger lifts (like a normal button), not when it lands: the menu used to
    // open on touch and the release's click then hit the menu's own buttons and closed it again.
    for (const id of this.tapsDown || []) {
      if (taps.has(id)) continue;
      input.haptic(15);
      input.dispatchEvent(new CustomEvent('hotkey', { detail: { id } }));
    }
    this.tapsDown = taps;
    for (const [id, n] of this.nodes) {
      const active = [...this.pointers.values()].some((p) => p.hit?.c.id === id);
      n.classList.toggle('pressed', active);
    }
  }

  down(e) {
    if (this.editing) return this.editDown(e);
    if (!e.target.classList.contains('tbtn')) return;
    e.preventDefault();
    this.layer.setPointerCapture?.(e.pointerId);
    this.pointers.set(e.pointerId, { x: e.clientX, y: e.clientY, hit: this.hit(e.clientX, e.clientY) });
    this.evaluate();
  }

  move(e) {
    if (this.editing) return this.editMove(e);
    const p = this.pointers.get(e.pointerId);
    if (!p) return;
    p.x = e.clientX; p.y = e.clientY;
    // Slide between buttons, but keep D-pad tracking once started there.
    if (p.hit?.c.kind !== 'dpad') p.hit = this.hit(e.clientX, e.clientY);
    this.evaluate();
  }

  up(e) {
    if (this.editing) { this.drag = null; return; }
    this.pointers.delete(e.pointerId);
    this.evaluate();
  }

  // ---- layout editor ----

  startEditing(onDone) {
    this.editing = true;
    this.controls = this.layout();
    this.onDone = onDone;
    this.layer.classList.add('editing');
    this.render();
  }

  stopEditing() {
    this.editing = false;
    this.selected = null;
    this.layer.classList.remove('editing');
    this.render();
    this.onDone?.();
  }

  async saveLayout() {
    const all = { ...(settings.global.touchLayout || {}) };
    all[this.orientation] = this.controls;
    await settings.set('touchLayout', all);
  }

  editDown(e) {
    const t = e.target.closest?.('.tbtn');
    if (!t) return;
    e.preventDefault();
    this.selected = t.dataset.id;
    const c = this.controls.find((x) => x.id === this.selected);
    this.drag = { id: c.id, sx: e.clientX, sy: e.clientY, ox: c.x, oy: c.y };
    this.layer.setPointerCapture?.(e.pointerId);
    this.render();
  }

  editMove(e) {
    if (!this.drag) return;
    const c = this.controls.find((x) => x.id === this.drag.id);
    const box = this.layer.getBoundingClientRect();
    c.x = Math.max(0, Math.min(100, this.drag.ox + ((e.clientX - this.drag.sx) / box.width) * 100));
    c.y = Math.max(0, Math.min(100, this.drag.oy + ((e.clientY - this.drag.sy) / box.height) * 100));
    const n = this.nodes.get(c.id);
    n.style.left = c.x + '%';
    n.style.top = c.y + '%';
  }

  renderToolbar() {
    const c = this.controls.find((x) => x.id === this.selected);
    const size = el('input', { type: 'range', min: 5, max: 60, step: 1, value: c?.size || 10, disabled: !c });
    size.oninput = () => { if (c) { c.size = Number(size.value); this.render(); } };
    const opacity = el('input', { type: 'range', min: 0.05, max: 1, step: 0.05, value: settings.global.touchOpacity });
    opacity.onchange = () => settings.set('touchOpacity', Number(opacity.value));
    const bar = el('div', { class: 'layout-toolbar' },
      el('span', {}, c ? `Selected: ${c.label || c.id}` : `Drag controls (${this.orientation})`),
      el('label', {}, 'Size ', size),
      el('button', { disabled: !c, onclick: () => { c.hidden = !c.hidden; this.render(); } }, c?.hidden ? 'Show' : 'Hide'),
      el('label', {}, 'Opacity ', opacity),
      el('button', { onclick: async () => {
        const all = { ...(settings.global.touchLayout || {}) };
        delete all[this.orientation];
        await settings.set('touchLayout', all);
        this.controls = this.layout();
        this.render();
      } }, 'Reset'),
      el('button', { class: 'primary', onclick: async () => { await this.saveLayout(); this.stopEditing(); } }, 'Done'));
    bar.addEventListener('pointerdown', (e) => e.stopPropagation());
    this.layer.append(bar);
  }
}
