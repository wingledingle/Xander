// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Beat deck: step grid with per-step velocity, a 4×4 pad view for finger drumming, track editor, arrangement.

import { S, changed, getSample } from '../state.js';
import { DRUMS, PATTERNS, PATTERN_NAMES, COLORS, STEP_OPTIONS, CHANNEL_PARAMS, MAX_TRACKS, addTrack, removeTrack, setStepCount } from '../core.js';
import { h, icon, btn, iconBtn, knob, segmented, sheet, prompt, confirmSheet, onLongPress, toast, fmt } from '../ui.js';
import { hitTrack } from '../actions.js';

let mode = 'steps', edit = 'toggle', clip = null;
const cells = new Map(); // `${trackId}:${step}` → element

const track = (id) => S.song.tracks.find((t) => t.id === id);
const pattern = () => S.song.patterns[S.song.current];

function paintCell(el, v) {
  el.style.setProperty('--vel', v);
  el.classList.toggle('on', v > 0);
  el.classList.toggle('accent', v >= 0.95);
}

// Drag-to-paint: the first cell decides whether the drag turns steps on or off. In velocity mode a vertical drag
// sets the velocity of the touched step.
function grid(root) {
  let painting = null;
  const cellAt = (e) => document.elementFromPoint(e.clientX, e.clientY)?.closest('.step');
  root.addEventListener('pointerdown', (e) => {
    const c = e.target.closest('.step');
    if (!c) return;
    e.preventDefault();
    const { track: id, step } = c.dataset, arr = pattern().steps[id], i = +step;
    if (edit === 'velocity') {
      const r = c.getBoundingClientRect();
      const setV = (y) => { arr[i] = Math.max(0.05, Math.min(1, 1 - (y - r.top) / r.height)); paintCell(c, arr[i]); };
      setV(e.clientY);
      root.setPointerCapture(e.pointerId);
      painting = { velocity: true, setV };
      return;
    }
    const on = !(arr[i] > 0);
    painting = { on, seen: new Set() };
    apply(c);
    function apply(el) {
      const k = `${el.dataset.track}:${el.dataset.step}`;
      if (painting.seen.has(k)) return;
      painting.seen.add(k);
      const a = pattern().steps[el.dataset.track];
      a[+el.dataset.step] = painting.on ? (a[+el.dataset.step] || 0.78) : 0;
      paintCell(el, a[+el.dataset.step]);
      if (painting.on && !S.seq?.playing) hitTrack(el.dataset.track, 0.78);
    }
    painting.apply = apply;
    root.setPointerCapture(e.pointerId);
  });
  root.addEventListener('pointermove', (e) => {
    if (!painting) return;
    if (painting.velocity) return painting.setV(e.clientY);
    const c = cellAt(e);
    if (c) painting.apply(c);
  });
  const end = () => { if (painting) { painting = null; changed('song'); } };
  root.addEventListener('pointerup', end);
  root.addEventListener('pointercancel', end);
}

function trackHead(t) {
  const sel = S.selectedTrack === t.id;
  const smp = t.kind === 'sample' ? S.samples.get(t.ref) : null;
  const head = h('div', { class: `track-head${sel ? ' selected' : ''}`, style: { '--tc': t.color } },
    h('button', { type: 'button', class: 'track-name', title: 'Tap to play · hold to edit', onclick: () => { S.selectedTrack = t.id; hitTrack(t.id, 0.9); markSelected(); } },
      h('span', { class: 'swatch' }), h('span', { class: 'tn' }, t.name), t.kind === 'sample' && !smp ? h('span', { class: 'warn', title: 'Sample missing' }, '!') : null),
    h('button', { type: 'button', class: `ms m${t.mute ? ' on' : ''}`, 'aria-pressed': String(t.mute), title: 'Mute', onclick: () => { t.mute = !t.mute; changed('song'); } }, 'M'),
    h('button', { type: 'button', class: `ms s${t.solo ? ' on' : ''}`, 'aria-pressed': String(t.solo), title: 'Solo', onclick: () => { t.solo = !t.solo; changed('song'); } }, 'S'));
  onLongPress(head.querySelector('.track-name'), () => editTrack(t.id));
  return head;
}
function markSelected() {
  document.querySelectorAll('.track-head').forEach((el) => el.classList.toggle('selected', el.dataset.id === S.selectedTrack));
}

function stepsView() {
  cells.clear();
  const n = S.song.steps, pat = pattern();
  const rows = S.song.tracks.map((t) => {
    const head = trackHead(t);
    head.dataset.id = t.id;
    const steps = Array.from({ length: n }, (_, i) => {
      const c = h('div', { class: `step b${Math.floor(i / 4) % 4}`, 'data-track': t.id, 'data-step': i, style: { '--tc': t.color } }, h('i'));
      paintCell(c, pat.steps[t.id][i]);
      cells.set(`${t.id}:${i}`, c);
      return c;
    });
    return h('div', { class: 'track-row' }, head, h('div', { class: 'steps', style: { '--n': n } }, steps));
  });
  const ruler = h('div', { class: 'track-row ruler' }, h('div', { class: 'track-head ghost' }),
    h('div', { class: 'steps', style: { '--n': n } }, Array.from({ length: n }, (_, i) => h('span', { class: `led b${Math.floor(i / 4) % 4}`, 'data-step': i }, i % 4 === 0 ? String(i / 4 + 1) : ''))));
  const g = h('div', { class: 'grid' }, ruler, rows,
    S.song.tracks.length < MAX_TRACKS ? h('div', { class: 'track-row add' }, btn('Add track', addTrackSheet, { icon: 'plus', cls: 'ghost small' })) : null);
  grid(g);
  return h('div', { class: 'grid-scroll' }, g);
}

function padsView() {
  const tracks = S.song.tracks.slice(0, 16);
  const pads = tracks.map((t, i) => {
    const pad = h('button', { type: 'button', class: 'pad', style: { '--tc': t.color }, 'aria-label': `Pad ${i + 1}: ${t.name}` },
      h('span', { class: 'pad-n' }, String(i + 1)), h('span', { class: 'pad-l' }, t.name));
    pad.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      // Pressure (or the vertical touch position on screens without it) sets velocity.
      const r = pad.getBoundingClientRect();
      const vel = e.pressure && e.pressure !== 0.5 ? 0.3 + e.pressure * 0.7 : 0.55 + 0.45 * (1 - (e.clientY - r.top) / r.height);
      hitTrack(t.id, vel);
      S.selectedTrack = t.id;
      pad.classList.remove('hit'); void pad.offsetWidth; pad.classList.add('hit');
    });
    onLongPress(pad, () => editTrack(t.id));
    return pad;
  });
  while (pads.length < 16) pads.push(h('button', { type: 'button', class: 'pad empty', onclick: addTrackSheet, 'aria-label': 'Add track' }, icon('plus')));
  return h('div', { class: 'pads-wrap' }, h('div', { class: 'pads' }, pads),
    h('p', { class: 'hint' }, S.prefs.lowLatencyPads ? 'Low-latency pads on. Hit lower on a pad for a harder hit. Hold a pad to edit its track.' : 'Hit lower on a pad for a harder hit. Hold a pad to edit its track.'));
}

function arrangement() {
  const a = S.song.arrangement, playingBar = S.song.mode === 'song' && S.playhead ? S.playhead.bar : -1;
  const chips = a.map((p, i) => {
    const c = h('button', { type: 'button', class: `arr-chip p${p}${i === playingBar ? ' now' : ''}`, title: 'Tap: next pattern · hold: remove', onclick: () => { a[i] = (p + 1) % PATTERNS; changed('song'); } }, PATTERN_NAMES[p]);
    onLongPress(c, () => { if (a.length > 1) { a.splice(i, 1); changed('song'); } });
    return c;
  });
  return h('section', { class: 'arrangement' },
    h('div', { class: 'row-head' }, h('span', { class: 'eyebrow' }, 'Arrangement'),
      h('span', { class: 'muted small' }, `${a.length} bar${a.length > 1 ? 's' : ''}`),
      h('div', { class: 'spacer' }),
      btn('Add current', () => { a.push(S.song.current); changed('song'); }, { icon: 'plus', cls: 'ghost small' })),
    h('div', { class: 'arr-chips' }, chips));
}

export function renderBeat(root) {
  const pat = pattern();
  const tools = h('div', { class: 'toolbar' },
    segmented([['steps', 'Steps'], ['pads', 'Pads']], mode, (v) => { mode = v; renderBeat(root); }),
    mode === 'steps' ? segmented([['toggle', 'Draw'], ['velocity', 'Velocity']], edit, (v) => { edit = v; }, 'compact') : null,
    mode === 'steps' ? segmented(STEP_OPTIONS.map((n) => [n, String(n)]), S.song.steps, (v) => { setStepCount(S.song, v); changed('song'); }, 'compact') : null,
    h('div', { class: 'spacer' }),
    iconBtn('copy', 'Copy pattern', () => { clip = JSON.stringify(pat); toast(`Pattern ${PATTERN_NAMES[S.song.current]} copied`); }),
    iconBtn('paste', 'Paste pattern', () => { if (!clip) return toast('Copy a pattern first'); Object.assign(S.song.patterns[S.song.current], JSON.parse(clip)); changed('song'); }),
    iconBtn('dice', 'Randomize', randomize),
    iconBtn('trash', 'Clear pattern', () => { for (const id of Object.keys(pat.steps)) pat.steps[id].fill(0); changed('song'); }));
  root.replaceChildren(h('div', { class: 'deck beat' }, tools, mode === 'steps' ? stepsView() : padsView(), arrangement()));
}

export function beatPlayhead(p) {
  document.querySelectorAll('.led.now, .step.now').forEach((el) => el.classList.remove('now'));
  if (!p) return;
  const showing = S.song.mode === 'song' ? p.pattern === S.song.current : true;
  if (!showing) return;
  document.querySelector(`.led[data-step="${p.step}"]`)?.classList.add('now');
  for (const t of S.song.tracks) cells.get(`${t.id}:${p.step}`)?.classList.add('now');
}

function randomize() {
  const pat = pattern(), n = S.song.steps;
  const dens = { kick: 0.3, snare: 0.15, clap: 0.12, chat: 0.6, ohat: 0.15, tom: 0.1, rim: 0.15, cowbell: 0.08 };
  for (const t of S.song.tracks) {
    const d = t.kind === 'drum' ? dens[t.ref] : 0.2;
    pat.steps[t.id] = Array.from({ length: n }, (_, i) => {
      if (t.ref === 'kick' && i % 8 === 0) return 1;
      if ((t.ref === 'snare' || t.ref === 'clap') && i % 8 === 4) return 0.9;
      return Math.random() < d ? Math.round((0.45 + Math.random() * 0.5) * 100) / 100 : 0;
    });
  }
  changed('song');
}

// ---- Track editor ------------------------------------------------------------------------------------

export function addTrackSheet() {
  const samples = [...S.samples.values()].sort((a, b) => b.created - a.created);
  const pick = (kind, ref, name) => {
    const t = addTrack(S.song, kind, ref, name);
    if (!t) return toast(`Up to ${MAX_TRACKS} tracks`);
    S.selectedTrack = t.id;
    s.close();
    changed('song');
  };
  const s = sheet('Add track', h('div', {},
    h('p', { class: 'eyebrow' }, 'Drum synth'),
    h('div', { class: 'chips' }, DRUMS.map((d) => btn(d.name, () => pick('drum', d.id, d.name), { cls: 'chip' }))),
    h('p', { class: 'eyebrow' }, 'From the library'),
    samples.length ? h('div', { class: 'list' }, samples.slice(0, 60).map((x) => h('button', { type: 'button', class: 'list-item', onclick: () => pick('sample', x.id, x.name) },
      x.thumb ? h('img', { class: 'thumb', src: x.thumb, alt: '' }) : h('span', { class: 'swatch big', style: { '--tc': x.color } }),
      h('span', { class: 'li-main' }, h('strong', {}, x.name), h('small', {}, x.tags.map((t) => `#${t}`).join(' ') || `${x.duration.toFixed(2)} s`)))))
      : h('p', { class: 'muted' }, 'No samples yet. Chop a video or import audio in the Library.')));
}

export function editTrack(id) {
  const t = track(id);
  if (!t) return;
  S.selectedTrack = id;
  const P = CHANNEL_PARAMS;
  const k = (key, label, f, exp) => knob({ label, min: P[key][0], max: P[key][1], def: P[key][2], value: t[key], fmt: f, exp, onchange: (v) => { t[key] = v; changed('param'); } }).el;
  const sourceChoices = t.kind === 'drum'
    ? h('div', { class: 'chips' }, DRUMS.map((d) => h('button', { type: 'button', class: `chip${t.ref === d.id ? ' on' : ''}`, onclick: () => { t.ref = d.id; changed('song'); s.close(); editTrack(id); } }, d.name)))
    : h('div', { class: 'chips' }, [...S.samples.values()].slice(0, 40).map((x) => h('button', { type: 'button', class: `chip${t.ref === x.id ? ' on' : ''}`, onclick: () => { t.ref = x.id; changed('song'); s.close(); editTrack(id); } }, x.name)));
  const s = sheet(t.name, h('div', { class: 'track-editor' },
    h('div', { class: 'row' },
      btn('Rename', async () => { const n = await prompt('Track name', t.name); if (n) { t.name = n.slice(0, 40); changed('song'); s.close(); editTrack(id); } }, { icon: 'edit', cls: 'ghost small' }),
      btn('Play', () => hitTrack(id), { icon: 'play', cls: 'ghost small' }),
      h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: t.choke || null, onchange: (e) => { t.choke = e.target.checked; changed('song'); } }), 'Choke (mono)')),
    h('p', { class: 'eyebrow' }, 'Colour'),
    h('div', { class: 'colors' }, COLORS.map((c) => h('button', { type: 'button', class: `color${t.color === c ? ' on' : ''}`, style: { '--tc': c }, 'aria-label': c, onclick: () => { t.color = c; changed('song'); s.close(); editTrack(id); } }))),
    h('p', { class: 'eyebrow' }, t.kind === 'drum' ? 'Voice' : 'Sample'),
    sourceChoices,
    h('p', { class: 'eyebrow' }, 'Sound'),
    h('div', { class: 'knobs' }, k('vol', 'Level', fmt.pct), k('pan', 'Pan', fmt.pan), k('pitch', 'Pitch', fmt.st), k('decay', 'Length', fmt.pct),
      k('cutoff', 'Filter', fmt.pct), k('reso', 'Reso', (v) => v.toFixed(1)), k('drive', 'Drive', fmt.pct), k('crush', 'Crush', fmt.pct)),
    h('p', { class: 'eyebrow' }, 'EQ and sends'),
    h('div', { class: 'knobs' }, k('low', 'Low', fmt.db), k('mid', 'Mid', fmt.db), k('high', 'High', fmt.db), k('delay', 'Delay', fmt.pct), k('reverb', 'Reverb', fmt.pct))),
  { actions: [
    btn('Delete track', async () => { if (await confirmSheet('Delete track?', `“${t.name}” and its steps in every pattern will be removed.`)) { removeTrack(S.song, id); s.close(); changed('song'); } }, { cls: 'danger ghost', icon: 'trash' }),
    btn('Done', () => s.close(), { cls: 'primary' }),
  ] });
}

export { getSample };
