// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Small DOM toolkit: element builder, icons, knob, fader, segmented control, bottom sheet, toast.

import { clamp } from './core.js';

export const $ = (s, root = document) => root.querySelector(s);
export function h(tag, attrs = {}, ...kids) {
  const el = document.createElementNS(tag === 'svg' || tag === 'use' ? 'http://www.w3.org/2000/svg' : 'http://www.w3.org/1999/xhtml', tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.setAttribute('class', v);
    else if (k === 'style' && typeof v === 'object') for (const [p, x] of Object.entries(v)) { if (x == null) continue; if (p.startsWith('--')) el.style.setProperty(p, x); else el.style[p] = x; }
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const kid of kids.flat()) if (kid != null && kid !== false) el.append(kid);
  return el;
}
export const icon = (name, cls = '') => h('svg', { class: `ic ${cls}`, 'aria-hidden': 'true' }, h('use', { href: `#i-${name}` }));
export const btn = (label, onclick, { icon: ic, cls = '', title, pressed } = {}) =>
  h('button', { type: 'button', class: `btn ${cls}`, onclick, title, 'aria-label': title || null, 'aria-pressed': pressed == null ? null : String(pressed) }, ic ? icon(ic) : null, label ? h('span', {}, label) : null);
export const iconBtn = (ic, title, onclick, cls = '') => h('button', { type: 'button', class: `icon-btn ${cls}`, title, 'aria-label': title, onclick }, icon(ic));

// Knob: a range input (keyboard + screen reader) under a drawn dial. Drag up/down; double-tap resets.
export function knob({ label, min, max, value, def = value, exp = false, fmt = (v) => v.toFixed(2), onchange, color, size = '' }) {
  const toPos = (v) => (exp ? Math.log(v / min) / Math.log(max / min) : (v - min) / (max - min));
  const fromPos = (p) => (exp ? min * (max / min) ** p : min + p * (max - min));
  const input = h('input', { type: 'range', min: 0, max: 1000, 'aria-label': label });
  const face = h('div', { class: 'knob' }, input);
  const val = h('span', { class: 'knob-val' });
  if (color) face.style.setProperty('--kc', color);
  if (min < 0 && max > 0) face.classList.add('bipolar');
  const set = (pos, emit) => {
    pos = clamp(pos, 0, 1);
    input.value = Math.round(pos * 1000);
    face.style.setProperty('--v', pos);
    const v = fromPos(pos);
    val.textContent = fmt(v);
    input.setAttribute('aria-valuetext', fmt(v));
    if (emit) onchange(v);
  };
  set(toPos(value), false);
  input.addEventListener('input', () => set(input.value / 1000, true));
  let lastTap = 0;
  input.addEventListener('pointerdown', (e) => {
    e.preventDefault();
    const now = performance.now();
    if (now - lastTap < 300) { set(toPos(def), true); lastTap = 0; return; }
    lastTap = now;
    input.setPointerCapture(e.pointerId);
    const y0 = e.clientY, x0 = e.clientX, p0 = input.value / 1000;
    face.classList.add('active');
    const move = (m) => set(p0 + (y0 - m.clientY + (m.clientX - x0) * 0.5) / (e.shiftKey ? 600 : 180), true);
    const up = () => { face.classList.remove('active'); input.removeEventListener('pointermove', move); input.removeEventListener('pointerup', up); input.removeEventListener('pointercancel', up); };
    input.addEventListener('pointermove', move);
    input.addEventListener('pointerup', up);
    input.addEventListener('pointercancel', up);
  });
  const el = h('div', { class: `knob-wrap ${size}` }, face, val, h('span', { class: 'knob-label' }, label));
  return { el, set: (v) => set(toPos(v), false) };
}

// Vertical fader for the mixer. Drag anywhere on the track; double-tap resets.
export function fader({ value, min = 0, max = 1.2, def = 0.8, onchange, label }) {
  const thumb = h('div', { class: 'fader-thumb' });
  const fill = h('div', { class: 'fader-fill' });
  const el = h('div', { class: 'fader', role: 'slider', tabindex: 0, 'aria-label': label, 'aria-valuemin': min, 'aria-valuemax': max }, h('div', { class: 'fader-track' }, fill), thumb);
  const set = (v, emit) => {
    v = clamp(v, min, max);
    const p = (v - min) / (max - min);
    el.style.setProperty('--p', p);
    el.setAttribute('aria-valuenow', v.toFixed(2));
    el.setAttribute('aria-valuetext', v > 0 ? `${(20 * Math.log10(v)).toFixed(1)} dB` : '-inf dB');
    if (emit) onchange(v);
    value = v;
  };
  set(value, false);
  let lastTap = 0;
  el.addEventListener('pointerdown', (e) => {
    const now = performance.now();
    if (now - lastTap < 300) { set(def, true); return; }
    lastTap = now;
    el.setPointerCapture(e.pointerId);
    const r = el.getBoundingClientRect(), y0 = e.clientY, v0 = value;
    const move = (m) => set(v0 + ((y0 - m.clientY) / (r.height - 24)) * (max - min), true);
    const up = () => { el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up); };
    el.addEventListener('pointermove', move);
    el.addEventListener('pointerup', up);
  });
  el.addEventListener('keydown', (e) => {
    const d = { ArrowUp: 0.02, ArrowDown: -0.02, PageUp: 0.1, PageDown: -0.1 }[e.key];
    if (d) { e.preventDefault(); set(value + d, true); }
  });
  return { el, set: (v) => set(v, false) };
}

export function segmented(options, value, onchange, cls = '') {
  const el = h('div', { class: `seg ${cls}`, role: 'radiogroup' });
  const render = (v) => {
    el.replaceChildren(...options.map(([val, label]) => h('button', {
      type: 'button', role: 'radio', 'aria-checked': String(val === v), onclick: () => { render(val); onchange(val); },
    }, label)));
  };
  render(value);
  return el;
}

export function toggle(label, on, onchange, ic) {
  const b = h('button', { type: 'button', class: 'chip-toggle', 'aria-pressed': String(on), onclick: () => { on = !on; b.setAttribute('aria-pressed', String(on)); onchange(on); } }, ic ? icon(ic) : null, label);
  return b;
}

// Bottom sheet on phones, centered panel on tablets. Returns { el, close }.
export function sheet(title, body, { actions = [], onclose } = {}) {
  const close = () => { root.classList.add('closing'); setTimeout(() => root.remove(), 160); onclose?.(); document.removeEventListener('keydown', esc); };
  const esc = (e) => { if (e.key === 'Escape') close(); };
  const root = h('div', { class: 'sheet-root', onclick: (e) => { if (e.target === root) close(); } },
    h('div', { class: 'sheet', role: 'dialog', 'aria-modal': 'true', 'aria-label': title },
      h('div', { class: 'sheet-grip' }),
      h('header', { class: 'sheet-head' }, h('h2', {}, title), iconBtn('close', 'Close', () => close())),
      h('div', { class: 'sheet-body' }, body),
      actions.length ? h('footer', { class: 'sheet-foot' }, actions) : null));
  document.body.append(root);
  document.addEventListener('keydown', esc);
  requestAnimationFrame(() => root.querySelector('input, button:not(.icon-btn)')?.focus({ preventScroll: true }));
  return { el: root, close };
}

export function prompt(title, value, { placeholder = '', ok = 'Save' } = {}) {
  return new Promise((res) => {
    const input = h('input', { class: 'field', value, placeholder, 'aria-label': title });
    let done = false;
    const s = sheet(title, h('form', { onsubmit: (e) => { e.preventDefault(); done = true; s.close(); res(input.value.trim()); } }, input), {
      actions: [btn('Cancel', () => s.close(), { cls: 'ghost' }), btn(ok, () => { done = true; s.close(); res(input.value.trim()); }, { cls: 'primary' })],
      onclose: () => { if (!done) res(null); },
    });
    setTimeout(() => { input.focus(); input.select(); }, 50);
  });
}

export function confirmSheet(title, text, ok = 'Delete') {
  return new Promise((res) => {
    let done = false;
    const s = sheet(title, h('p', { class: 'muted' }, text), {
      actions: [btn('Cancel', () => s.close(), { cls: 'ghost' }), btn(ok, () => { done = true; s.close(); res(true); }, { cls: 'danger' })],
      onclose: () => { if (!done) res(false); },
    });
  });
}

let toastTimer = 0;
export function toast(msg, ms = 2600) {
  const el = $('#toast');
  el.textContent = msg;
  el.hidden = false;
  el.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.classList.remove('show'); setTimeout(() => { el.hidden = true; }, 200); }, ms);
}

// Long-press (500 ms) helper for touch; also fires on contextmenu (right-click).
export function onLongPress(el, fn) {
  let t = 0, fired = false;
  el.addEventListener('pointerdown', (e) => { fired = false; t = setTimeout(() => { fired = true; fn(e); }, 500); });
  for (const ev of ['pointerup', 'pointerleave', 'pointercancel', 'pointermove']) el.addEventListener(ev, (e) => { if (ev !== 'pointermove' || Math.abs(e.movementX) + Math.abs(e.movementY) > 6) clearTimeout(t); });
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); clearTimeout(t); if (!fired) fn(e); });
  el.addEventListener('click', (e) => { if (fired) { e.stopImmediatePropagation(); e.preventDefault(); fired = false; } }, true);
}

export const fmt = {
  pct: (v) => `${Math.round(v * 100)}%`,
  db: (v) => `${v > 0 ? '+' : ''}${v.toFixed(1)}dB`,
  pan: (v) => (Math.abs(v) < 0.02 ? 'C' : `${v < 0 ? 'L' : 'R'}${Math.round(Math.abs(v) * 100)}`),
  st: (v) => `${v > 0 ? '+' : ''}${Math.round(v)}st`,
  secs: (v) => (v < 1 ? `${Math.round(v * 1000)}ms` : `${v.toFixed(2)}s`),
  hz: (v) => (v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${Math.round(v)}`),
};
