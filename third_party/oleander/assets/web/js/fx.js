// Console "feel": boot animation with chime, menu sounds, D-pad/gamepad
// navigation of menus, and the power LED.
import { settings } from './settings.js';
import { input } from './input.js';

let ctx = null;
function audio() {
  if (!ctx) {
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return null;
    ctx = new AC();
  }
  if (ctx.state === 'suspended') ctx.resume().catch(() => {});
  return ctx;
}

/// Short square-wave blip, like a pocket console menu.
export function blip(kind = 'move') {
  if (!settings.global.uiSounds) return;
  const a = audio();
  if (!a || a.state !== 'running') return;
  const o = a.createOscillator();
  const g = a.createGain();
  o.type = 'square';
  const f = { move: 880, select: 1320, back: 520 }[kind] || 880;
  o.frequency.setValueAtTime(f, a.currentTime);
  if (kind === 'select') o.frequency.setValueAtTime(f * 1.5, a.currentTime + 0.04);
  g.gain.setValueAtTime(0.05, a.currentTime);
  g.gain.exponentialRampToValueAtTime(0.0001, a.currentTime + 0.09);
  o.connect(g).connect(a.destination);
  o.start();
  o.stop(a.currentTime + 0.1);
}

/// An original two-note chime played with the boot logo.
function chime() {
  const a = audio();
  if (!a || a.state !== 'running') return;
  const t = a.currentTime + 0.05;
  [[1046.5, 0], [1568, 0.12], [2093, 0.24]].forEach(([f, d]) => {
    const o = a.createOscillator();
    const g = a.createGain();
    o.type = 'triangle';
    o.frequency.value = f;
    g.gain.setValueAtTime(0, t + d);
    g.gain.linearRampToValueAtTime(0.09, t + d + 0.01);
    g.gain.exponentialRampToValueAtTime(0.0001, t + d + 0.7);
    o.connect(g).connect(a.destination);
    o.start(t + d);
    o.stop(t + d + 0.75);
  });
}

/// Boot animation. Resolves when it has finished (or was skipped).
export function bootSplash() {
  const el = document.getElementById('splash');
  if (!settings.global.bootAnimation || !el || matchMedia('(prefers-reduced-motion: reduce)').matches) return Promise.resolve();
  el.classList.remove('hidden');
  return new Promise((resolve) => {
    let done = false;
    const finish = () => {
      if (done) return;
      done = true;
      el.classList.add('fade');
      setTimeout(() => { el.classList.add('hidden'); el.classList.remove('fade'); resolve(); }, 350);
    };
    setTimeout(chime, 550);
    setTimeout(finish, 1700);
    el.addEventListener('pointerdown', finish, { once: true });
    window.addEventListener('keydown', finish, { once: true });
  });
}

/// Power LED: on while a game runs, amber when the device battery is low.
export async function powerLed(on) {
  const led = document.getElementById('power-led');
  if (!led) return;
  led.classList.toggle('off', !on);
  try {
    const b = await navigator.getBattery?.();
    if (b) {
      const upd = () => led.classList.toggle('low', !b.charging && b.level < 0.15);
      upd();
      b.onlevelchange = upd;
      b.onchargingchange = upd;
    }
  } catch { /* not available */ }
}

/// Lets a container of buttons be driven with the D-pad / arrow keys / a
/// gamepad: up/down/left/right move the cursor, A (or Enter) selects and
/// B (or Backspace) calls `onBack`. Returns a function that stops it.
export function navigate(container, onBack) {
  const items = () => [...container.querySelectorAll('button:not([disabled]):not(.close-menu), input[type=range]')].filter((b) => b.offsetParent !== null);
  let index = 0;
  const focus = (i) => {
    const list = items();
    if (!list.length) return;
    index = (i + list.length) % list.length;
    list.forEach((b) => b.classList.remove('cursor'));
    list[index].classList.add('cursor');
    list[index].focus({ preventScroll: false });
  };
  const move = (dx, dy) => {
    const list = items();
    if (!list.length) return;
    const cur = list[index]?.getBoundingClientRect();
    if (!cur) return focus(0);
    // Pick the nearest item in the requested direction.
    let best = -1, bestScore = Infinity;
    list.forEach((b, i) => {
      if (i === index) return;
      const r = b.getBoundingClientRect();
      const ddx = (r.left + r.width / 2) - (cur.left + cur.width / 2);
      const ddy = (r.top + r.height / 2) - (cur.top + cur.height / 2);
      const along = dx ? ddx * dx : ddy * dy;
      if (along <= 4) return;
      const across = dx ? Math.abs(ddy) : Math.abs(ddx);
      const score = along + across * 2.5;
      if (score < bestScore) { bestScore = score; best = i; }
    });
    if (best >= 0) { focus(best); blip('move'); }
  };
  const activate = () => {
    const b = items()[index];
    if (!b) return;
    blip('select');
    b.click();
  };
  const onKey = (e) => {
    if (document.getElementById('dialog').open) return;
    const map = { ArrowUp: [0, -1], ArrowDown: [0, 1], ArrowLeft: [-1, 0], ArrowRight: [1, 0] };
    const keys = settings.keyMap();
    if (map[e.code] || [keys.Up, keys.Down, keys.Left, keys.Right].includes(e.code)) {
      const d = map[e.code] || { [keys.Up]: [0, -1], [keys.Down]: [0, 1], [keys.Left]: [-1, 0], [keys.Right]: [1, 0] }[e.code];
      if (document.activeElement?.type === 'range' && d[0]) return;
      e.preventDefault();
      move(d[0], d[1]);
    } else if (e.code === keys.A || e.code === 'Enter' || e.code === 'Space') {
      if (e.code === 'Enter' || e.code === 'Space') return; // native button activation
      e.preventDefault();
      activate();
    } else if (e.code === keys.B || e.code === 'Backspace') {
      e.preventDefault();
      blip('back');
      onBack();
    }
  };
  window.addEventListener('keydown', onKey, true);
  // Gamepad polling (the emulation loop is paused while menus are open).
  let raf = 0;
  let prev = {};
  const pads = settings.padMap();
  const poll = () => {
    raf = requestAnimationFrame(poll);
    if (document.getElementById('dialog').open) return;
    const p = input.pads()[0];
    if (!p) return;
    const pressed = (i) => !!p.buttons[i]?.pressed;
    const ax = p.axes[0] || 0, ay = p.axes[1] || 0;
    const state = {
      up: pressed(12) || ay < -0.6, down: pressed(13) || ay > 0.6, left: pressed(14) || ax < -0.6, right: pressed(15) || ax > 0.6,
      a: pressed(pads.A), b: pressed(pads.B), start: pressed(pads.Start),
    };
    const edge = (k) => state[k] && !prev[k];
    if (edge('up')) move(0, -1);
    if (edge('down')) move(0, 1);
    if (edge('left')) move(-1, 0);
    if (edge('right')) move(1, 0);
    if (edge('a')) activate();
    if (edge('b') || edge('start')) { blip('back'); onBack(); }
    prev = state;
  };
  // Ignore buttons already held when the menu opened.
  const p0 = input.pads()[0];
  if (p0) prev = { a: !!p0.buttons[pads.A]?.pressed, b: !!p0.buttons[pads.B]?.pressed, start: !!p0.buttons[pads.Start]?.pressed };
  raf = requestAnimationFrame(poll);
  focus(0);
  return () => {
    window.removeEventListener('keydown', onKey, true);
    cancelAnimationFrame(raf);
    items().forEach((b) => b.classList.remove('cursor'));
  };
}
