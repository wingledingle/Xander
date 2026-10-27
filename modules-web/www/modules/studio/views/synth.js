// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Synth deck: piano roll (scale degrees × steps), two-oscillator synth knobs and a playable keyboard.

import { S, changed, audio } from '../state.js';
import { ROWS, SCALES, WAVES, NOTE_NAMES, SYNTH_PARAMS, degreeToMidi, noteFreq, noteName } from '../core.js';
import { h, knob, segmented, iconBtn, fmt } from '../ui.js';

let octave = 4;
const held = new Map();

function noteOn(key, midi) {
  if (held.has(key)) return;
  const e = audio();
  held.set(key, e.note(noteFreq(midi), e.ctx.currentTime + 0.005, 0.9));
  document.querySelector(`.key[data-midi="${midi}"]`)?.classList.add('down');
}
function noteOff(key, midi) {
  held.get(key)?.release(S.ctx.currentTime);
  held.delete(key);
  document.querySelector(`.key[data-midi="${midi}"]`)?.classList.remove('down');
}

function roll() {
  const n = S.song.steps, pat = S.song.patterns[S.song.current], sy = S.song.synth;
  const rows = [];
  for (let r = ROWS - 1; r >= 0; r--) {
    const midi = degreeToMidi(r, sy.root, sy.scale), tonic = (midi - sy.root) % 12 === 0;
    rows.push(h('div', { class: `roll-row${tonic ? ' tonic' : ''}` }, h('span', { class: 'roll-label' }, noteName(midi)),
      h('div', { class: 'steps', style: { '--n': n } }, Array.from({ length: n }, (_, i) => h('div', {
        class: `note b${Math.floor(i / 4) % 4}${pat.notes[i] === r ? ' on' : ''}`, 'data-step': i, 'data-row': r,
      })))));
  }
  const el = h('div', { class: 'roll' }, rows);
  let paint = null;
  el.addEventListener('pointerdown', (e) => {
    const c = e.target.closest('.note');
    if (!c) return;
    e.preventDefault();
    const i = +c.dataset.step, r = +c.dataset.row, erase = pat.notes[i] === r;
    paint = { erase };
    set(c);
    el.setPointerCapture(e.pointerId);
  });
  function set(c) {
    const i = +c.dataset.step, r = +c.dataset.row;
    if (paint.erase) { if (pat.notes[i] === r) pat.notes[i] = -1; }
    else if (pat.notes[i] !== r) {
      pat.notes[i] = r;
      if (!S.seq?.playing) { const e = audio(); e.note(noteFreq(degreeToMidi(r, sy.root, sy.scale)), e.ctx.currentTime + 0.005, 0.8).release(e.ctx.currentTime + 0.18); }
    }
    el.querySelectorAll(`.note[data-step="${i}"]`).forEach((x) => x.classList.toggle('on', pat.notes[i] === +x.dataset.row));
  }
  el.addEventListener('pointermove', (e) => { if (paint) { const c = document.elementFromPoint(e.clientX, e.clientY)?.closest('.note'); if (c) set(c); } });
  const end = () => { if (paint) { paint = null; changed('song'); } };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
  return h('div', { class: 'roll-scroll' }, el);
}

function keyboard() {
  const base = 12 * (octave + 1), keys = [];
  for (let i = 0; i < 25; i++) {
    const midi = base + i, black = [1, 3, 6, 8, 10].includes(i % 12);
    const k = h('div', { class: `key ${black ? 'black' : 'white'}`, 'data-midi': midi, role: 'button', 'aria-label': noteName(midi) }, !black && i % 12 === 0 ? h('span', {}, noteName(midi)) : null);
    k.addEventListener('pointerdown', (e) => { e.preventDefault(); k.releasePointerCapture?.(e.pointerId); noteOn(`p${e.pointerId}`, midi); });
    k.addEventListener('pointerenter', (e) => { if (e.buttons) noteOn(`p${e.pointerId}`, midi); });
    for (const ev of ['pointerup', 'pointerleave', 'pointercancel']) k.addEventListener(ev, (e) => noteOff(`p${e.pointerId}`, midi));
    keys.push(k);
  }
  return h('section', { class: 'keybed' },
    h('div', { class: 'row-head' }, h('span', { class: 'eyebrow' }, 'Keyboard'), h('div', { class: 'spacer' }),
      iconBtn('minus', 'Octave down', () => { octave = Math.max(1, octave - 1); rerender(); }),
      h('span', { class: 'lcd small' }, `C${octave}`),
      iconBtn('plus', 'Octave up', () => { octave = Math.min(7, octave + 1); rerender(); })),
    h('div', { class: 'keys' }, keys));
}

let rootEl = null;
const rerender = () => rootEl && renderSynth(rootEl);

export function renderSynth(root) {
  rootEl = root;
  const sy = S.song.synth, P = SYNTH_PARAMS;
  const k = (key, label, f, exp) => knob({ label, min: P[key][0], max: P[key][1], def: P[key][2], value: sy[key], fmt: f, exp, onchange: (v) => { sy[key] = v; changed('param'); } }).el;
  const sel = (label, value, options, onchange) => h('label', { class: 'pick' }, label, h('select', { onchange: (e) => onchange(e.target.value) },
    options.map(([v, l]) => h('option', { value: v, selected: String(v) === String(value) || null }, l))));
  const roots = Array.from({ length: 24 }, (_, i) => [24 + i, `${NOTE_NAMES[i % 12]}${Math.floor((24 + i) / 12) - 1}`]);
  root.replaceChildren(h('div', { class: 'deck synth' },
    h('div', { class: 'toolbar' },
      sel('Key', sy.root, roots, (v) => { sy.root = +v; changed('song'); rerender(); }),
      sel('Scale', sy.scale, Object.keys(SCALES).map((s) => [s, s[0].toUpperCase() + s.slice(1)]), (v) => { sy.scale = v; changed('song'); rerender(); }),
      h('div', { class: 'spacer' }),
      iconBtn('trash', 'Clear notes', () => { S.song.patterns[S.song.current].notes.fill(-1); changed('song'); rerender(); })),
    roll(),
    h('section', { class: 'panel' },
      h('div', { class: 'osc-row' },
        h('span', { class: 'eyebrow' }, 'Osc 1'), segmented(WAVES.map((w) => [w, w.slice(0, 3)]), sy.wave1, (v) => { sy.wave1 = v; changed('param'); }, 'compact'),
        h('span', { class: 'eyebrow' }, 'Osc 2'), segmented(WAVES.map((w) => [w, w.slice(0, 3)]), sy.wave2, (v) => { sy.wave2 = v; changed('param'); }, 'compact')),
      h('div', { class: 'knobs' },
        k('detune', 'Detune', (v) => `${Math.round(v)}c`), k('glide', 'Glide', fmt.secs), k('cutoff', 'Cutoff', fmt.pct), k('reso', 'Reso', (v) => v.toFixed(1)),
        k('envAmt', 'Env amt', fmt.pct), k('attack', 'Attack', fmt.secs, true), k('decay', 'Decay', fmt.secs, true), k('sustain', 'Sustain', fmt.pct),
        k('release', 'Release', fmt.secs, true), k('gate', 'Gate', fmt.pct))),
    keyboard()));
}

export function synthPlayhead(p) {
  document.querySelectorAll('.note.now').forEach((el) => el.classList.remove('now'));
  if (p && (S.song.mode !== 'song' || p.pattern === S.song.current)) document.querySelectorAll(`.note[data-step="${p.step}"]`).forEach((el) => el.classList.add('now'));
}

// Computer keyboard: A W S E D F T G Y H U J K play one octave.
const KEYMAP = 'awsedftgyhujk';
document.addEventListener('keydown', (e) => {
  if (S.view !== 'synth' || e.repeat || e.target.matches('input, select, textarea')) return;
  const i = KEYMAP.indexOf(e.key.toLowerCase());
  if (i >= 0) noteOn(`k${e.key}`, 12 * (octave + 1) + i);
});
document.addEventListener('keyup', (e) => {
  const i = KEYMAP.indexOf(e.key.toLowerCase());
  if (i >= 0) noteOff(`k${e.key}`, 12 * (octave + 1) + i);
});
