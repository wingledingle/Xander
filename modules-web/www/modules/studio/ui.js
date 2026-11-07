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

// Slider control (was a rotary knob). Touch rules it follows: a full-width track with a 48 px hit area (small
// rotary knobs are hard to turn on glass), the value shown beside the control instead of under the finger,
// − and + buttons for fine steps, tap the value to type an exact number, double-tap the track to reset.
// Vertical swipes still scroll the page: a drag changes the value only once it moves sideways.
export function knob({ label, min, max, value, def = value, exp = false, fmt = (v) => v.toFixed(2), onchange, color }) {
  const toPos = (v) => (exp ? Math.log(v / min) / Math.log(max / min) : (v - min) / (max - min));
  const fromPos = (p) => (exp ? min * (max / min) ** p : min + p * (max - min));
  const track = h('div', { class: 'ctl-track', role: 'slider', tabindex: 0, 'aria-label': label, 'aria-valuemin': 0, 'aria-valuemax': 100 },
    h('div', { class: 'ctl-fill' }), h('div', { class: 'ctl-thumb' }));
  const val = h('button', { type: 'button', class: 'ctl-val', title: 'Type a value' });
  const el = h('div', { class: 'ctl' }, h('div', { class: 'ctl-head' }, h('span', { class: 'ctl-label' }, label), val),
    h('div', { class: 'ctl-row' }, h('button', { type: 'button', class: 'ctl-step', 'aria-label': `${label} down`, onclick: () => nudge(-1) }, '−'), track,
      h('button', { type: 'button', class: 'ctl-step', 'aria-label': `${label} up`, onclick: () => nudge(1) }, '+')));
  if (color) el.style.setProperty('--kc', color);
  if (min < 0 && max > 0 && !exp) el.classList.add('bipolar');
  let pos = 0;
  const set = (p, emit) => {
    pos = clamp(p, 0, 1);
    el.style.setProperty('--v', pos);
    const v = fromPos(pos);
    val.textContent = fmt(v);
    track.setAttribute('aria-valuenow', Math.round(pos * 100));
    track.setAttribute('aria-valuetext', fmt(v));
    if (emit) onchange(v);
  };
  const nudge = (d) => set(pos + d / 50, true);
  set(toPos(value), false);
  val.addEventListener('click', async () => {
    const t = await prompt(label, String(Math.round(fromPos(pos) * 1000) / 1000), { ok: 'Set' });
    const n = parseFloat(String(t ?? '').replace(',', '.'));
    if (Number.isFinite(n)) set(toPos(clamp(n, Math.min(min, max), Math.max(min, max))), true);
  });
  let lastTap = 0;
  track.addEventListener('pointerdown', (e) => {
    const now = performance.now();
    if (now - lastTap < 300) { set(toPos(def), true); lastTap = 0; return; }
    lastTap = now;
    const r = track.getBoundingClientRect(), x0 = e.clientX, y0 = e.clientY;
    const at = (x) => (x - r.left - 14) / Math.max(1, r.width - 28);
    let dragging = false;
    const move = (m) => {
      if (!dragging && Math.abs(m.clientX - x0) > 6 && Math.abs(m.clientX - x0) > Math.abs(m.clientY - y0)) { dragging = true; track.setPointerCapture(e.pointerId); el.classList.add('active'); }
      if (dragging) set(at(m.clientX), true);
    };
    const up = (u) => {
      if (!dragging && u.type === 'pointerup' && Math.abs(u.clientX - x0) < 6 && Math.abs(u.clientY - y0) < 6) set(at(u.clientX), true); // a tap jumps there
      el.classList.remove('active');
      track.removeEventListener('pointermove', move); track.removeEventListener('pointerup', up); track.removeEventListener('pointercancel', up);
    };
    track.addEventListener('pointermove', move); track.addEventListener('pointerup', up); track.addEventListener('pointercancel', up);
  });
  track.addEventListener('keydown', (e) => {
    const d = { ArrowRight: 1, ArrowUp: 1, ArrowLeft: -1, ArrowDown: -1, PageUp: 5, PageDown: -5 }[e.key];
    if (d) { e.preventDefault(); nudge(d); }
  });
  return { el, set: (v) => set(toPos(v), false) };
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
