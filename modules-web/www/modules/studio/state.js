// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Shared app state: the song, the audio engine, the sample library in memory, undo history and saving.

import { normalizeSong, createHistory, demoSong } from './core.js';
import { createEngine, createSequencer } from './audio.js';
import { library } from './library.js';
import { host } from '../host/xander.js';
import { lowLatency } from './native.js';

const SONG_KEY = 'gs-studio-song';
const listeners = new Set();

export const S = {
  song: loadSong(),
  ctx: null, engine: null, seq: null,
  samples: new Map(), // id → record (with .buffer AudioBuffer once decoded)
  selectedTrack: null,
  selectedSample: null,
  playhead: null, // {step, pattern, bar} while playing
  rec: false,
  metronome: false,
  view: 'beat',
  prefs: loadPrefs(),
  history: createHistory(),
};
S.history.push(JSON.stringify(S.song));

function loadSong() { try { return normalizeSong(JSON.parse(localStorage.getItem(SONG_KEY))); } catch { return demoSong(); } }
function loadPrefs() { try { return { lowLatencyPads: true, ...JSON.parse(localStorage.getItem('gs-studio-prefs')) }; } catch { return { lowLatencyPads: true }; } }
export function savePrefs() { try { localStorage.setItem('gs-studio-prefs', JSON.stringify(S.prefs)); } catch {} }

// kind: 'song' (structure: tracks/patterns), 'param' (knob moves), 'library', 'play', 'view'
export const subscribe = (fn) => { listeners.add(fn); return () => listeners.delete(fn); };
export const emit = (kind) => listeners.forEach((fn) => fn(kind));

let saveTimer = 0, histTimer = 0;
export function changed(kind = 'song') {
  S.engine?.apply(S.song);
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => { try { localStorage.setItem(SONG_KEY, JSON.stringify(S.song)); } catch {} }, 250);
  clearTimeout(histTimer);
  histTimer = setTimeout(() => { S.history.push(JSON.stringify(S.song)); if (kind === 'param' || kind === 'steps') emit(kind); }, kind === 'param' ? 500 : 0);
  emit(kind);
}

export function replaceSong(next, kind = 'song') {
  S.song = normalizeSong(next);
  if (!S.song.tracks.some((t) => t.id === S.selectedTrack)) S.selectedTrack = S.song.tracks[0]?.id ?? null;
  changed(kind);
}
export function undo() { const s = S.history.undo(); if (s) { S.song = normalizeSong(JSON.parse(s)); S.engine?.apply(S.song); emit('song'); } }
export function redo() { const s = S.history.redo(); if (s) { S.song = normalizeSong(JSON.parse(s)); S.engine?.apply(S.song); emit('song'); } }

// The AudioContext starts on the first user gesture (browsers require it).
export function audio() {
  if (!S.ctx) {
    S.ctx = new AudioContext({ latencyHint: 'interactive' });
    S.engine = createEngine(S.ctx);
    S.engine.apply(S.song);
    S.seq = createSequencer(S.engine, () => S.song, getSample, {
      onStep: (p) => { S.playhead = p; emit('playhead'); },
    });
    S.seq.metronome = S.metronome;
  }
  if (S.ctx.state === 'suspended') S.ctx.resume();
  return S.engine;
}

export function getSample(id) {
  const s = S.samples.get(id);
  return s?.buffer ? s : null;
}

// ---- Library in memory -------------------------------------------------------------------------------

function toBuffer(rec) {
  const ctx = S.ctx || new OfflineAudioContext(1, 1, rec.sampleRate);
  const b = ctx.createBuffer(rec.data.length, rec.data[0].length, rec.sampleRate);
  rec.data.forEach((c, i) => b.copyToChannel(c, i));
  return b;
}

export async function loadLibrary() {
  try {
    for (const rec of await library.all()) { rec.buffer = toBuffer(rec); S.samples.set(rec.id, rec); }
  } catch (e) { console.warn('library unavailable', e); }
  emit('library');
}

// Adds or updates a sample. rec.data = Float32Array[] per channel. Stores without the AudioBuffer.
export async function putSample(rec) {
  rec.buffer = toBuffer(rec);
  rec.settings ??= { start: 0, end: rec.data[0].length / rec.sampleRate, gain: 1, pitch: 0, attack: 0.002, release: 0.01, reverse: false, loop: false };
  S.samples.set(rec.id, rec);
  const { buffer, ...stored } = rec;
  try { await library.put(stored); } catch (e) { host.toast('Could not save to library'); console.warn(e); }
  lowLatency.forget(rec.id);
  emit('library');
  return rec;
}

let metaTimer = new Map();
// Saves label/tags/settings edits (debounced, audio unchanged).
export function touchSample(rec) {
  clearTimeout(metaTimer.get(rec.id));
  metaTimer.set(rec.id, setTimeout(() => { const { buffer, ...stored } = rec; library.put(stored).catch(() => {}); }, 300));
  lowLatency.forget(rec.id);
  emit('library');
}

export async function deleteSample(id) {
  S.samples.delete(id);
  await library.delete(id).catch(() => {});
  emit('library');
}

export function bufferToRecord(buffer, meta) {
  return {
    id: meta.id || Math.random().toString(36).slice(2, 12), name: meta.name || 'Sample', tags: meta.tags || [], color: meta.color || '#4ea8ff',
    created: Date.now(), favorite: false, sampleRate: buffer.sampleRate, source: meta.source || null, thumb: meta.thumb || null,
    data: Array.from({ length: buffer.numberOfChannels }, (_, i) => buffer.getChannelData(i).slice()),
    duration: buffer.duration, settings: meta.settings,
  };
}
