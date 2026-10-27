// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Mixer deck: a channel strip per track plus the synth and master — fader, pan, mute/solo, meter, insert FX.

import { S, changed } from '../state.js';
import { MASTER_PARAMS } from '../core.js';
import { h, btn, knob, fader, sheet, fmt } from '../ui.js';
import { editTrack } from './beat.js';

const meters = new Map(); // channel id → canvas

function synthFx() {
  const sy = S.song.synth;
  const k = (key, label, min, max, def, f) => knob({ label, min, max, def, value: sy[key], fmt: f, onchange: (v) => { sy[key] = v; changed('param'); } }).el;
  sheet('Synth channel', h('div', { class: 'knobs' }, k('cutoff', 'Filter', 0, 1, 1, fmt.pct), k('drive', 'Drive', 0, 1, 0, fmt.pct), k('crush', 'Crush', 0, 1, 0, fmt.pct),
    k('low', 'Low', -15, 15, 0, fmt.db), k('mid', 'Mid', -15, 15, 0, fmt.db), k('high', 'High', -15, 15, 0, fmt.db), k('delay', 'Delay', 0, 1, 0.28, fmt.pct), k('reverb', 'Reverb', 0, 1, 0.18, fmt.pct)));
}

function masterFx() {
  const m = S.song.master, P = MASTER_PARAMS;
  const k = (key, label, f) => knob({ label, min: P[key][0], max: P[key][1], def: P[key][2], value: m[key], fmt: f, onchange: (v) => { m[key] = v; changed('param'); } }).el;
  sheet('Master bus', h('div', {},
    h('p', { class: 'eyebrow' }, 'EQ'), h('div', { class: 'knobs' }, k('low', 'Low', fmt.db), k('mid', 'Mid', fmt.db), k('high', 'High', fmt.db)),
    h('p', { class: 'eyebrow' }, 'Bus compressor'), h('div', { class: 'knobs' }, k('threshold', 'Threshold', (v) => `${Math.round(v)}dB`), k('ratio', 'Ratio', (v) => `${v.toFixed(1)}:1`)),
    h('p', { class: 'eyebrow' }, 'Delay and reverb returns'), h('div', { class: 'knobs' },
      k('delayTime', 'Delay', (v) => `${Math.round(v)}/16`), k('delayFb', 'Feedback', fmt.pct), k('reverbSize', 'Room', (v) => `${v.toFixed(1)}s`)),
    h('p', { class: 'hint' }, 'A brick-wall limiter after the master fader keeps the output from clipping.')));
}

function strip({ id, name, color, ch, onFx }) {
  const meter = h('canvas', { class: 'meter', width: 12, height: 240 });
  meters.set(id, meter);
  const f = fader({ value: ch.vol, label: `${name} level`, def: id === 'master' ? 0.85 : 0.8, onchange: (v) => { ch.vol = v; changed('param'); } });
  return h('div', { class: `strip${id === 'master' ? ' master' : ''}`, style: { '--tc': color } },
    h('button', { type: 'button', class: 'strip-name', onclick: onFx, title: 'Effects' }, h('span', { class: 'swatch' }), h('span', { class: 'ellipsis' }, name)),
    id === 'master' ? h('div', { class: 'pan-ph' }) : knob({ label: 'Pan', min: -1, max: 1, def: 0, value: ch.pan, fmt: fmt.pan, onchange: (v) => { ch.pan = v; changed('param'); }, size: 'mini' }).el,
    h('div', { class: 'strip-fader' }, meter, f.el),
    h('span', { class: 'mono small' }, ch.vol > 0 ? `${(20 * Math.log10(ch.vol)).toFixed(1)}` : '-∞'),
    id === 'master' ? h('div', { class: 'ms-row' }) : h('div', { class: 'ms-row' },
      h('button', { type: 'button', class: `ms m${ch.mute ? ' on' : ''}`, 'aria-pressed': String(!!ch.mute), onclick: () => { ch.mute = !ch.mute; changed('song'); } }, 'M'),
      h('button', { type: 'button', class: `ms s${ch.solo ? ' on' : ''}`, 'aria-pressed': String(!!ch.solo), onclick: () => { ch.solo = !ch.solo; changed('song'); } }, 'S')),
    btn('FX', onFx, { cls: 'ghost xsmall' }));
}

export function renderMixer(root) {
  meters.clear();
  const strips = [
    ...S.song.tracks.map((t) => strip({ id: t.id, name: t.name, color: t.color, ch: t, onFx: () => editTrack(t.id) })),
    strip({ id: 'synth', name: 'Synth', color: '#9d7bff', ch: S.song.synth, onFx: synthFx }),
    strip({ id: 'master', name: 'Master', color: '#ffb02e', ch: S.song.master, onFx: masterFx }),
  ];
  root.replaceChildren(h('div', { class: 'deck mixer' }, h('div', { class: 'strips' }, strips)));
}

// Peak meters with a short hold, drawn every frame while the mixer is open.
const buf = new Float32Array(256), held = new Map();
export function drawMeters() {
  if (!S.engine || !meters.size) return;
  const src = S.engine.meters();
  src.set('master', S.engine.analyser);
  for (const [id, cv] of meters) {
    const an = src.get(id);
    if (!an || !cv.isConnected) continue;
    an.getFloatTimeDomainData(buf.subarray(0, an.fftSize > 256 ? 256 : an.fftSize));
    let p = 0;
    for (let i = 0; i < 256; i++) p = Math.max(p, Math.abs(buf[i]));
    const db = p > 0 ? 20 * Math.log10(p) : -60, lvl = Math.max(0, Math.min(1, (db + 60) / 60));
    const hold = Math.max(lvl, (held.get(id) || 0) - 0.01);
    held.set(id, hold);
    const g = cv.getContext('2d'), H = cv.height;
    g.clearRect(0, 0, 12, H);
    g.fillStyle = 'rgba(255,255,255,.08)'; g.fillRect(0, 0, 12, H);
    const grad = g.createLinearGradient(0, H, 0, 0);
    grad.addColorStop(0, '#3ddc84'); grad.addColorStop(0.75, '#ffd166'); grad.addColorStop(1, '#ff4d4d');
    g.fillStyle = grad; g.fillRect(0, H - lvl * H, 12, lvl * H);
    g.fillStyle = hold > 0.95 ? '#ff4d4d' : '#fff'; g.fillRect(0, H - hold * H, 12, 2);
  }
}
