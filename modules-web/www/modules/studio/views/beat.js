// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Beat deck: step grid with per-step velocity, a 4×4 pad view for finger drumming, track editor, arrangement.

import { S, changed, getSample } from '../state.js';
import { DRUMS, PATTERNS, PATTERN_NAMES, COLORS, STEP_OPTIONS, CHANNEL_PARAMS, MAX_TRACKS, addTrack, removeTrack, setStepCount } from '../core.js';
import { h, icon, btn, iconBtn, knob, segmented, sheet, prompt, confirmSheet, onLongPress, toast, fmt } from '../ui.js';
import { hitTrack } from '../actions.js';

let edit = 'toggle', clip = null, page = 0, mode = 'steps';
try { mode = localStorage.getItem('gs-beat-mode') === 'pads' ? 'pads' : 'steps'; } catch {}
const PAGE = 16; // steps shown at once; 32-step patterns flip between two pages
const cells = new Map(); // `${trackId}:${step}` → editor cell
const minis = new Map(); // `${trackId}:${step}` → track-list mini cell

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
  // Cells are already painted; 'steps' saves and updates undo without rebuilding the grid.
  const end = () => { if (painting) { painting = null; changed('steps'); } };
  root.addEventListener('pointerup', end);
  root.addEventListener('pointercancel', end);
}

// Track list: one 48 px row per track (tap to edit its steps above, hold for its sound), with a mini view of the
// pattern so you can see the whole beat at once without the cells being too small to hit.
function trackRow(t) {
  const n = S.song.steps, pat = pattern();
  const smp = t.kind === 'sample' ? S.samples.get(t.ref) : null;
  const sel = h('button', { type: 'button', class: 'track-sel', title: 'Tap to edit · hold for sound settings', 'aria-label': `Edit ${t.name}`, onclick: () => selectTrack(t.id) },
    h('span', { class: 'tn' }, t.name, t.kind === 'sample' && !smp ? h('span', { class: 'warn', title: 'Sample missing' }, ' !') : null),
    h('span', { class: 'mini', style: { '--n': n } }, Array.from({ length: n }, (_, i) => {
      const m = h('span', { class: `m b${Math.floor(i / 4) % 4}`, 'data-step': i });
      paintMini(m, pat.steps[t.id][i]);
      minis.set(`${t.id}:${i}`, m);
      return m;
    })));
  onLongPress(sel, () => editTrack(t.id));
  return h('div', { class: `track-row${S.selectedTrack === t.id ? ' selected' : ''}${t.mute ? ' muted' : ''}`, 'data-id': t.id, style: { '--tc': t.color } }, sel,
    h('button', { type: 'button', class: `ms m${t.mute ? ' on' : ''}`, 'aria-pressed': String(t.mute), 'aria-label': `Mute ${t.name}`, onclick: () => { t.mute = !t.mute; changed('song'); } }, 'M'),
    h('button', { type: 'button', class: `ms s${t.solo ? ' on' : ''}`, 'aria-pressed': String(t.solo), 'aria-label': `Solo ${t.name}`, onclick: () => { t.solo = !t.solo; changed('song'); } }, 'S'));
}
const paintMini = (m, v) => m.classList.toggle('on', v > 0);

function selectTrack(id) {
  S.selectedTrack = id;
  if (!S.seq?.playing) hitTrack(id, 0.8);
  document.querySelectorAll('.track-row').forEach((el) => el.classList.toggle('selected', el.dataset.id === id));
  document.querySelectorAll('.pads.beat-pads .pad').forEach((el) => el.classList.toggle('selected', el.dataset.id === id));
  const ed = document.querySelector('.editor');
  if (ed) ed.replaceWith(editor());
}

// The step editor: the selected track's 16 steps as a 4×4 block of big cells (one bar, one row per beat).
// 32-step patterns flip between two pages.
function editor() {
  cells.clear();
  const t = track(S.selectedTrack) || S.song.tracks[0];
  if (!t) return h('div', { class: 'editor empty' }, h('p', {}, 'No tracks yet.'), btn('Add a track', addTrackSheet, { icon: 'plus', cls: 'primary' }));
  S.selectedTrack = t.id;
  const n = S.song.steps, pat = pattern(), from = n > PAGE ? page * PAGE : 0;
  const g = h('div', { class: `step-grid${edit === 'velocity' ? ' vel' : ''}`, style: { '--tc': t.color } }, Array.from({ length: Math.min(n, PAGE) }, (_, k) => {
    const i = from + k;
    const c = h('div', { class: `step b${Math.floor(i / 4) % 4}`, 'data-track': t.id, 'data-step': i, role: 'button', 'aria-label': `Step ${i + 1}` }, h('i'), h('b', {}, String(i + 1)));
    paintCell(c, pat.steps[t.id][i]);
    cells.set(`${t.id}:${i}`, c);
    return c;
  }));
  grid(g);
  const name = h('button', { type: 'button', class: 'ed-name', title: 'Tap to play · hold for sound settings', onclick: () => hitTrack(t.id, 0.9) }, h('span', { class: 'swatch' }), h('span', { class: 'tn' }, t.name));
  onLongPress(name, () => editTrack(t.id));
  return h('section', { class: 'editor', style: { '--tc': t.color } },
    h('div', { class: 'ed-head' }, name,
      n > PAGE ? segmented([[0, '1–16'], [1, '17–32']], page, (v) => { page = v; document.querySelector('.editor')?.replaceWith(editor()); }, 'compact') : null,
      btn('Sound', () => editTrack(t.id), { icon: 'sliders', cls: 'ghost small' })),
    g);
}

// Finger drumming: 4×4 pads, one per track. Pressure (or where on the pad you hit) sets velocity.
function padsView() {
  const pads = S.song.tracks.slice(0, 16).map((t, i) => {
    const pad = h('button', { type: 'button', class: `pad${S.selectedTrack === t.id ? ' selected' : ''}`, 'data-id': t.id, style: { '--tc': t.color }, 'aria-label': `Pad ${i + 1}: ${t.name}` },
      h('span', { class: 'pad-n' }, String(i + 1)), h('span', { class: 'pad-l' }, t.name));
    pad.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      const r = pad.getBoundingClientRect();
      const vel = e.pressure && e.pressure !== 0.5 ? 0.3 + e.pressure * 0.7 : 0.55 + 0.45 * (1 - (e.clientY - r.top) / r.height);
      hitTrack(t.id, vel);
      S.selectedTrack = t.id;
      document.querySelectorAll('.track-row').forEach((el) => el.classList.toggle('selected', el.dataset.id === t.id));
      document.querySelectorAll('.pads.beat-pads .pad').forEach((el) => el.classList.toggle('selected', el.dataset.id === t.id));
      pad.classList.remove('hit'); void pad.offsetWidth; pad.classList.add('hit');
    });
    onLongPress(pad, () => editTrack(t.id));
    return pad;
  });
  if (pads.length < 16 && S.song.tracks.length < MAX_TRACKS) pads.push(h('button', { type: 'button', class: 'pad add', onclick: addTrackSheet, 'aria-label': 'Add track' }, icon('plus')));
  return h('section', { class: 'pads beat-pads' }, pads);
}

function arrangement() {
  const a = S.song.arrangement, playingBar = S.song.mode === 'song' && S.playhead ? S.playhead.bar : -1;
  const chips = a.map((p, i) => {
    const c = h('button', { type: 'button', class: `arr-chip p${p}${i === playingBar ? ' now' : ''}`, title: 'Tap: next pattern · hold: remove', onclick: () => { a[i] = (p + 1) % PATTERNS; changed('song'); } }, PATTERN_NAMES[p]);
    onLongPress(c, () => { if (a.length > 1) { a.splice(i, 1); changed('song'); } });
    return c;
  });
  return h('section', { class: 'arrangement' }, h('span', { class: 'eyebrow' }, 'Song'), h('div', { class: 'arr-chips' }, chips,
    h('button', { type: 'button', class: 'arr-chip add', title: 'Add the current pattern', 'aria-label': 'Add the current pattern to the song', onclick: () => { a.push(S.song.current); changed('song'); } }, icon('plus'))));
}

function patternSheet() {
  const pat = pattern(), name = PATTERN_NAMES[S.song.current];
  const act = (ic, label, fn, sub) => h('button', { type: 'button', class: 'menu-item', onclick: () => { s.close(); fn(); } }, icon(ic), h('span', {}, label, sub ? h('small', {}, sub) : null));
  const s = sheet(`Pattern ${name}`, h('div', { class: 'menu' },
    h('div', { class: 'row' }, h('span', { class: 'grow' }, 'Steps per pattern'), segmented(STEP_OPTIONS.map((n) => [n, String(n)]), S.song.steps, (v) => { setStepCount(S.song, v); s.close(); changed('song'); })),
    act('copy', 'Copy pattern', () => { clip = JSON.stringify(pat); toast(`Pattern ${name} copied`); }),
    act('paste', 'Paste pattern', () => { if (!clip) return toast('Copy a pattern first'); Object.assign(S.song.patterns[S.song.current], JSON.parse(clip)); changed('song'); }, clip ? 'Replaces this pattern' : 'Copy one first'),
    act('dice', 'Randomize', randomize, 'A new beat on every track'),
    act('trash', 'Clear pattern', () => { for (const id of Object.keys(pat.steps)) pat.steps[id].fill(0); changed('song'); }),
    S.song.tracks.length < MAX_TRACKS ? act('plus', 'Add track', addTrackSheet) : null));
}

export function renderBeat(root) {
  cells.clear(); minis.clear();
  if (S.song.steps <= PAGE) page = 0;
  const steps = mode === 'steps';
  const tools = h('div', { class: 'toolbar beat-tools' },
    segmented([['steps', 'Steps'], ['pads', 'Pads']], mode, (v) => { mode = v; try { localStorage.setItem('gs-beat-mode', v); } catch {} renderBeat(root); }),
    steps ? segmented([['toggle', 'Draw'], ['velocity', 'Velocity']], edit, (v) => { edit = v; document.querySelector('.step-grid')?.classList.toggle('vel', v === 'velocity'); }) : null,
    h('div', { class: 'tools-end' }, h('button', { type: 'button', class: 'chip-toggle', 'aria-pressed': String(S.song.mode === 'song'), title: 'Play the song arrangement instead of one pattern',
      onclick: () => { S.song.mode = S.song.mode === 'song' ? 'pattern' : 'song'; changed('song'); } }, 'Song'),
    iconBtn('more', 'Pattern actions', patternSheet)));
  root.replaceChildren(h('div', { class: `deck beat mode-${mode}` }, tools, S.song.mode === 'song' ? arrangement() : null,
    steps ? editor() : padsView(),
    h('section', { class: 'tracks' }, h('p', { class: 'eyebrow' }, `Tracks · pattern ${PATTERN_NAMES[S.song.current]}`), S.song.tracks.map(trackRow),
      S.song.tracks.length < MAX_TRACKS ? h('button', { type: 'button', class: 'track-add', onclick: addTrackSheet }, icon('plus'), 'Add track') : null)));
}

// Repaints step cells from the song (after a recorded hit or undo of a step edit) without rebuilding the grid.
export function refreshCells() {
  const pat = pattern();
  for (const [k, el] of cells) { const [id, i] = k.split(':'); paintCell(el, pat.steps[id]?.[+i] || 0); }
  for (const [k, el] of minis) { const [id, i] = k.split(':'); paintMini(el, pat.steps[id]?.[+i] || 0); }
}

export function beatPlayhead(p) {
  document.querySelectorAll('.step.now, .mini .now').forEach((el) => el.classList.remove('now'));
  if (!p) return;
  if (S.song.mode === 'song' && p.pattern !== S.song.current) return;
  // Follow the playhead across pages of a 32-step pattern.
  if (S.song.steps > PAGE && Math.floor(p.step / PAGE) !== page && document.querySelector('.editor')) { page = Math.floor(p.step / PAGE); document.querySelector('.editor').replaceWith(editor()); }
  for (const t of S.song.tracks) { cells.get(`${t.id}:${p.step}`)?.classList.add('now'); minis.get(`${t.id}:${p.step}`)?.classList.add('now'); }
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
