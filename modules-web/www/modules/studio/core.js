// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Groovesmith Studio: song model, timing and arrangement. Pure functions, no Web Audio, so Node tests run them.

export const MAX_TRACKS = 16;
export const PATTERNS = 8;
export const STEP_OPTIONS = [16, 32];
export const ROWS = 21; // synth piano-roll rows: three octaves of a 7-note scale
export const PATTERN_NAMES = 'ABCDEFGH';

export const DRUMS = [
  { id: 'kick', name: 'Kick' }, { id: 'snare', name: 'Snare' }, { id: 'clap', name: 'Clap' },
  { id: 'chat', name: 'Closed hat' }, { id: 'ohat', name: 'Open hat' }, { id: 'tom', name: 'Tom' },
  { id: 'rim', name: 'Rimshot' }, { id: 'cowbell', name: 'Cowbell' },
];
export const DRUM_IDS = DRUMS.map((d) => d.id);

export const SCALES = {
  minor: [0, 2, 3, 5, 7, 8, 10], major: [0, 2, 4, 5, 7, 9, 11], dorian: [0, 2, 3, 5, 7, 9, 10],
  phrygian: [0, 1, 3, 5, 7, 8, 10], harmonic: [0, 2, 3, 5, 7, 8, 11], pentatonic: [0, 3, 5, 7, 10], blues: [0, 3, 5, 6, 7, 10],
};
export const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
export const WAVES = ['sine', 'triangle', 'sawtooth', 'square'];
export const COLORS = ['#ff5a4e', '#ff9f43', '#ffd166', '#7bd88f', '#3ddbd9', '#4ea8ff', '#9d7bff', '#ff6ec7'];

// [min, max, default] for every knob. Validation and the UI both read these tables.
export const SYNTH_PARAMS = {
  detune: [0, 50, 9], cutoff: [0, 1, 0.42], reso: [0, 25, 9], envAmt: [0, 1, 0.55],
  attack: [0.001, 1, 0.004], decay: [0.02, 1.5, 0.22], sustain: [0, 1, 0.35], release: [0.01, 2, 0.18],
  gate: [0.1, 1, 0.7], glide: [0, 0.3, 0],
};
export const CHANNEL_PARAMS = {
  vol: [0, 1.2, 0.8], pan: [-1, 1, 0], pitch: [-24, 24, 0], decay: [0.05, 1, 1],
  cutoff: [0, 1, 1], reso: [0, 20, 0.7], drive: [0, 1, 0], crush: [0, 1, 0],
  low: [-15, 15, 0], mid: [-15, 15, 0], high: [-15, 15, 0], delay: [0, 1, 0], reverb: [0, 1, 0],
};
export const MASTER_PARAMS = {
  vol: [0, 1.2, 0.85], low: [-12, 12, 0], mid: [-12, 12, 0], high: [-12, 12, 0],
  threshold: [-40, 0, -14], ratio: [1, 20, 3], delayTime: [1, 8, 3], delayFb: [0, 0.9, 0.38], reverbSize: [0.3, 6, 2.4],
};

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
export const noteFreq = (midi) => 440 * 2 ** ((midi - 69) / 12);
export const noteName = (midi) => NOTE_NAMES[((midi % 12) + 12) % 12] + (Math.floor(midi / 12) - 1);
export const cutoffHz = (c) => 40 * 2 ** (c * 9); // 0..1 → 40 Hz..20 kHz
export const stepDuration = (bpm) => 60 / bpm / 4; // one sixteenth
// Swing delays every off-beat sixteenth by a fraction of a step (0 = straight, 0.5 = triplet feel).
export const swingOffset = (step, stepDur, swing) => (step % 2 ? swing * stepDur : 0);
export const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);

export function degreeToMidi(degree, root, scaleName) {
  const s = SCALES[scaleName] || SCALES.minor;
  return root + 12 * Math.floor(degree / s.length) + s[degree % s.length];
}

const defaults = (table) => Object.fromEntries(Object.entries(table).map(([k, [, , d]]) => [k, d]));
const num = (v, lo, hi, d) => (typeof v === 'number' && Number.isFinite(v) ? clamp(v, lo, hi) : d);
const str = (v, d, max = 60) => (typeof v === 'string' && v.trim() ? v.trim().slice(0, max) : d);
const params = (src, table) => Object.fromEntries(Object.entries(table).map(([k, [lo, hi, d]]) => [k, num(src?.[k], lo, hi, d)]));

export function newTrack(kind, ref, name, color) {
  return { id: uid(), kind, ref, name, color, mute: false, solo: false, choke: kind === 'drum' && ref === 'chat', ...defaults(CHANNEL_PARAMS) };
}

export const emptyPattern = (tracks, steps) => ({
  steps: Object.fromEntries(tracks.map((t) => [t.id, Array(steps).fill(0)])),
  notes: Array(steps).fill(-1), // scale degree per step, -1 = rest
});

// Step values are velocities: 0 = off, otherwise 0.05..1.
const hits = (steps, on, accents = []) => Array.from({ length: steps }, (_, i) => (accents.includes(i) ? 1 : on.includes(i) ? 0.78 : 0));
const notes = (steps, map) => Array.from({ length: steps }, (_, i) => (i in map ? map[i] : -1));

export function demoSong() {
  const tracks = DRUMS.map((d, i) => newTrack('drum', d.id, d.name, COLORS[i]));
  const id = Object.fromEntries(tracks.map((t) => [t.ref, t.id]));
  const S = 16;
  const pats = Array.from({ length: PATTERNS }, () => emptyPattern(tracks, S));
  const set = (p, map) => Object.entries(map).forEach(([k, v]) => { pats[p].steps[id[k]] = v; });
  set(0, { kick: hits(S, [4, 8, 12], [0]), clap: hits(S, [4, 12]), chat: hits(S, [1, 3, 5, 7, 9, 11, 13, 15], [0]), ohat: hits(S, [2, 6, 10, 14]), rim: hits(S, [3, 11, 14]) });
  pats[0].notes = notes(S, { 0: 7, 3: 7, 6: 14, 8: 9, 10: 11, 11: 10, 14: 14 });
  set(1, { kick: hits(S, [2, 10, 11], [0]), snare: hits(S, [4, 15], [12]), chat: hits(S, [0, 2, 4, 6, 8, 10, 12, 14]), ohat: hits(S, [7]), tom: hits(S, [13, 14]) });
  pats[1].notes = notes(S, { 0: 7, 2: 14, 4: 12, 7: 11, 8: 9, 10: 11, 12: 7, 14: 16 });
  set(2, { kick: hits(S, [6, 10], [0]), snare: hits(S, [4, 12]), clap: hits(S, [12]), chat: hits(S, [0, 2, 4, 6, 8, 10, 12, 14]), cowbell: hits(S, [3, 7, 8, 14]) });
  pats[2].notes = notes(S, { 0: 14, 1: 14, 3: 16, 6: 14, 8: 18, 9: 16, 12: 14, 14: 11 });
  set(3, { kick: hits(S, [3, 10], [0]), snare: hits(S, [], [8]), chat: hits(S, [0, 2, 4, 6, 8, 10, 12, 14]), rim: hits(S, [14]) });
  pats[3].notes = notes(S, { 0: 7, 4: 9, 8: 10, 12: 11, 14: 13 });
  return {
    version: 2, name: 'Untitled beat', bpm: 122, swing: 0.12, steps: S, current: 0, mode: 'pattern',
    arrangement: [0, 0, 1, 1, 2, 3], tracks, patterns: pats,
    synth: { ...defaults(SYNTH_PARAMS), ...defaults(CHANNEL_PARAMS), vol: 0.6, delay: 0.28, reverb: 0.18, root: 33, scale: 'minor', wave1: 'sawtooth', wave2: 'square', mute: false, solo: false },
    master: defaults(MASTER_PARAMS),
  };
}

function normTrack(o, i) {
  const kind = o?.kind === 'sample' ? 'sample' : 'drum';
  const ref = kind === 'drum' ? (DRUM_IDS.includes(o?.ref) ? o.ref : 'kick') : str(o?.ref, '', 40);
  return {
    id: str(o?.id, uid(), 40), kind, ref, name: str(o?.name, kind === 'drum' ? DRUMS.find((d) => d.id === ref).name : 'Sample', 40),
    color: /^#[0-9a-f]{6}$/i.test(o?.color) ? o.color : COLORS[i % COLORS.length],
    mute: o?.mute === true, solo: o?.solo === true, choke: o?.choke === true, ...params(o, CHANNEL_PARAMS),
  };
}

// Groovesmith 1 stored booleans per drum id; Studio stores velocities per track id.
function fromV1(o) {
  const s = demoSong();
  s.bpm = Math.round(num(o.bpm, 40, 240, s.bpm));
  s.swing = num(o.swing, 0, 0.5, s.swing);
  const byRef = Object.fromEntries(s.tracks.map((t) => [t.ref, t]));
  for (const t of s.tracks) {
    t.vol = num(o.levels?.[t.ref], 0, 1, t.vol);
    t.mute = o.mutes?.[t.ref] === true;
  }
  s.patterns = s.patterns.map((def, i) => {
    const p = o.patterns?.[i];
    if (!p) return emptyPattern(s.tracks, 16);
    const out = emptyPattern(s.tracks, 16);
    for (const ref of DRUM_IDS) out.steps[byRef[ref].id] = out.steps[byRef[ref].id].map((_, j) => (p.drums?.[ref]?.[j] === true ? 0.78 : 0));
    out.notes = out.notes.map((_, j) => (Number.isInteger(p.notes?.[j]) && p.notes[j] >= 0 && p.notes[j] < 14 ? p.notes[j] : -1));
    return out;
  });
  s.current = Math.floor(num(o.current, 0, 3, 0));
  s.arrangement = [s.current];
  return s;
}

// Builds a valid song from untrusted input (storage, an imported project). Bad fields fall back to defaults,
// numbers are clamped and step arrays forced to the song's length.
export function normalizeSong(input) {
  const o = input && typeof input === 'object' ? input : null;
  if (!o) return demoSong();
  if (o.version !== 2) return fromV1(o);
  const s = demoSong();
  s.name = str(o.name, s.name, 80);
  s.bpm = Math.round(num(o.bpm, 40, 240, s.bpm) * 10) / 10;
  s.swing = num(o.swing, 0, 0.5, s.swing);
  s.steps = STEP_OPTIONS.includes(o.steps) ? o.steps : 16;
  s.mode = o.mode === 'song' ? 'song' : 'pattern';
  s.current = Math.floor(num(o.current, 0, PATTERNS - 1, 0));
  if (Array.isArray(o.tracks)) s.tracks = o.tracks.slice(0, MAX_TRACKS).map(normTrack);
  const ids = new Set();
  s.tracks = s.tracks.filter((t) => !ids.has(t.id) && ids.add(t.id));
  s.patterns = Array.from({ length: PATTERNS }, (_, i) => {
    const src = o.patterns?.[i];
    const p = emptyPattern(s.tracks, s.steps);
    for (const t of s.tracks) p.steps[t.id] = p.steps[t.id].map((_, j) => num(src?.steps?.[t.id]?.[j], 0, 1, 0));
    p.notes = p.notes.map((_, j) => { const n = src?.notes?.[j]; return Number.isInteger(n) && n >= 0 && n < ROWS ? n : -1; });
    return p;
  });
  if (Array.isArray(o.arrangement)) {
    const a = o.arrangement.filter((n) => Number.isInteger(n) && n >= 0 && n < PATTERNS).slice(0, 256);
    s.arrangement = a.length ? a : [0];
  }
  const sy = o.synth || {};
  Object.assign(s.synth, params(sy, SYNTH_PARAMS), params(sy, CHANNEL_PARAMS), {
    root: Math.round(num(sy.root, 24, 60, s.synth.root)),
    scale: sy.scale in SCALES ? sy.scale : s.synth.scale,
    wave1: WAVES.includes(sy.wave1) ? sy.wave1 : s.synth.wave1,
    wave2: WAVES.includes(sy.wave2) ? sy.wave2 : s.synth.wave2,
    mute: sy.mute === true, solo: sy.solo === true,
  });
  s.master = params(o.master, MASTER_PARAMS);
  return s;
}

// Changes the pattern length, keeping existing steps (32 → 16 drops the second half).
export function setStepCount(song, steps) {
  if (!STEP_OPTIONS.includes(steps) || steps === song.steps) return;
  const fit = (a, fill) => Array.from({ length: steps }, (_, i) => (i < a.length ? a[i] : steps > a.length ? a[i % a.length] ?? fill : fill));
  for (const p of song.patterns) {
    for (const id of Object.keys(p.steps)) p.steps[id] = fit(p.steps[id], 0);
    p.notes = fit(p.notes, -1);
  }
  song.steps = steps;
}

export function addTrack(song, kind, ref, name, color) {
  if (song.tracks.length >= MAX_TRACKS) return null;
  const t = newTrack(kind, ref, name, color || COLORS[song.tracks.length % COLORS.length]);
  song.tracks.push(t);
  for (const p of song.patterns) p.steps[t.id] = Array(song.steps).fill(0);
  return t;
}

export function removeTrack(song, id) {
  song.tracks = song.tracks.filter((t) => t.id !== id);
  for (const p of song.patterns) delete p.steps[id];
}

// Which channels sound: a soloed channel silences every non-soloed one.
export function audible(song) {
  const chans = [...song.tracks, { id: 'synth', ...song.synth }];
  const anySolo = chans.some((c) => c.solo);
  return new Set(chans.filter((c) => !c.mute && (!anySolo || c.solo)).map((c) => c.id));
}

// The pattern playing at bar `bar` (0-based) and whether the song has ended.
export function patternAt(song, bar) {
  if (song.mode !== 'song') return { pattern: song.current, done: false };
  const a = song.arrangement;
  return { pattern: a[bar % a.length], done: bar >= a.length };
}

export const songBars = (song) => (song.mode === 'song' ? song.arrangement.length : 1);
export const barSeconds = (song) => stepDuration(song.bpm) * song.steps;

// Step events for one bar: [{track, step, vel, time}] plus synth notes, at song-relative seconds.
export function barEvents(song, patternIndex, barStart) {
  const pat = song.patterns[patternIndex], dur = stepDuration(song.bpm), on = audible(song), out = [];
  for (let i = 0; i < song.steps; i++) {
    const t = barStart + i * dur + swingOffset(i, dur, song.swing);
    for (const tr of song.tracks) {
      const v = pat.steps[tr.id]?.[i] || 0;
      if (v > 0 && on.has(tr.id)) out.push({ track: tr.id, step: i, vel: v, time: t });
    }
    if (pat.notes[i] >= 0 && on.has('synth')) out.push({ track: 'synth', step: i, degree: pat.notes[i], time: t });
  }
  return out;
}

// Tap tempo: BPM from the last few tap times (seconds). Ignores gaps over 2 s.
export function tapTempo(times) {
  const recent = [];
  for (let i = times.length - 1; i > 0 && recent.length < 4; i--) {
    const d = times[i] - times[i - 1];
    if (d > 2 || d <= 0.15) break;
    recent.push(d);
  }
  if (!recent.length) return null;
  return Math.round(clamp(60 / (recent.reduce((a, b) => a + b) / recent.length), 40, 240));
}

// Snapshot undo stack (JSON strings). Cheap, and the song is small.
export function createHistory(limit = 80) {
  const past = [], future = [];
  return {
    push(snap) { if (past[past.length - 1] !== snap) { past.push(snap); if (past.length > limit) past.shift(); future.length = 0; } },
    undo(current) { if (past.length < 2) return null; future.push(past.pop()); return past[past.length - 1] ?? current; },
    redo() { const s = future.pop(); if (s) past.push(s); return s ?? null; },
    get canUndo() { return past.length > 1; },
    get canRedo() { return future.length > 0; },
  };
}
