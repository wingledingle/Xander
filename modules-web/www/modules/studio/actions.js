// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Actions shared by several decks: auditioning and recording pad hits, imports, bounces and project files.

import { S, audio, changed, getSample, putSample, bufferToRecord, replaceSong } from './state.js';
import { renderSong } from './audio.js';
import { encodeWav, trimSilence } from './dsp.js';
import { normTags, uniqueName } from './library.js';
import { audible, PATTERN_NAMES, uid, COLORS } from './core.js';
import { lowLatency } from './native.js';
import { host } from '../host/xander.js';
import { toast } from './ui.js';

const safe = (s) => s.replace(/[^\w\- ]+/g, '').trim().replace(/\s+/g, '-') || 'groovesmith';

// Plays a track now (pad, track header). While recording and playing, writes the hit to the nearest step.
export function hitTrack(id, vel = 0.9) {
  const e = audio(), tr = S.song.tracks.find((t) => t.id === id);
  if (!tr) return;
  const smp = tr.kind === 'sample' ? getSample(tr.ref) : null;
  if (!(S.prefs.lowLatencyPads && audible(S.song).has(id) && lowLatency.trigger(tr, vel, smp))) {
    e.trigger(id, e.ctx.currentTime + 0.005, vel, getSample);
  }
  if (S.rec && S.seq?.playing) {
    const at = S.seq.stepAt(e.ctx.currentTime - (e.ctx.outputLatency || 0) - (e.ctx.baseLatency || 0));
    if (at) {
      const pat = S.song.mode === 'song' ? at.pattern : S.song.current;
      S.song.patterns[pat].steps[id][at.step] = Math.max(0.05, Math.min(1, vel));
      changed('song');
    }
  }
}

export function play() {
  audio();
  if (S.seq.playing) return;
  lowLatency.warm(S.song, getSample);
  S.seq.start(0);
  host.keepScreenOn(true);
  changed('play');
}
export function stop() {
  if (!S.seq?.playing) return;
  S.seq.stop();
  host.keepScreenOn(false);
  changed('play');
}
export const togglePlay = () => (S.seq?.playing ? stop() : play());

export async function exportWav(whole) {
  toast(whole ? 'Bouncing song…' : 'Bouncing 4 bars…', 8000);
  const buf = await renderSong(OfflineAudioContext, S.song, getSample, { bars: 4, whole });
  const wav = encodeWav([buf.getChannelData(0), buf.getChannelData(1)], buf.sampleRate);
  const name = `${safe(S.song.name)}-${whole ? 'song' : `pattern-${PATTERN_NAMES[S.song.current]}`}-${Math.round(S.song.bpm)}bpm.wav`;
  const r = await host.save(name, new Blob([wav], { type: 'audio/wav' }));
  toast(r.path ? `Saved to ${r.path}` : 'WAV downloaded');
  return buf;
}

// Bounces the current pattern into the library as a new loop sample.
export async function bounceToLibrary() {
  const buf = await renderSong(OfflineAudioContext, S.song, getSample, { bars: 1, whole: false });
  const name = uniqueName(`${S.song.name} ${PATTERN_NAMES[S.song.current]} loop`, [...S.samples.values()].map((s) => s.name));
  const rec = bufferToRecord(buf, { name, tags: ['loop', 'bounce', `${Math.round(S.song.bpm)}bpm`], color: '#ffb02e', source: { kind: 'bounce', name: S.song.name } });
  rec.settings = { start: 0.02, end: 0.02 + (60 / S.song.bpm) * 4, gain: 1, pitch: 0, attack: 0.002, release: 0.01, reverse: false, loop: false };
  await putSample(rec);
  toast(`“${name}” added to the library`);
}

async function decode(file, opts) {
  const d = await host.decodeAudio(file, opts);
  const res = await fetch(d.url);
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const buf = await audio().ctx.decodeAudioData(await res.arrayBuffer());
  return { buffer: buf, startSec: d.startSec || 0 };
}
export { decode as decodeFile };

export async function importAudioFiles(files) {
  const added = [];
  for (const f of files) {
    try {
      toast(`Importing ${f.name}…`, 10000);
      const { buffer } = await decode(f, { maxSec: 120 });
      const rec = bufferToRecord(buffer, {
        name: uniqueName(f.name.replace(/\.[^.]+$/, ''), [...S.samples.values()].map((s) => s.name)),
        tags: normTags(['import']), color: COLORS[added.length % COLORS.length], source: { kind: 'file', name: f.name, path: f.path || null },
      });
      const { start, end } = trimSilence(rec.data, -60);
      rec.settings = { start: start / buffer.sampleRate, end: Math.max(start + 64, end) / buffer.sampleRate, gain: 1, pitch: 0, attack: 0.002, release: 0.01, reverse: false, loop: false };
      added.push(await putSample(rec));
    } catch (e) {
      toast(`Could not read ${f.name}: ${e.message}`, 5000);
    }
  }
  if (added.length) toast(`${added.length} sample${added.length > 1 ? 's' : ''} imported`);
  return added;
}

// ---- Projects: song + every sample it uses, in one JSON file ----------------------------------------

const b64 = (buf) => { const u = new Uint8Array(buf); let s = ''; for (let i = 0; i < u.length; i += 0x8000) s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000)); return btoa(s); };

export async function saveProject() {
  const used = new Set(S.song.tracks.filter((t) => t.kind === 'sample').map((t) => t.ref));
  const samples = [...used].map((id) => S.samples.get(id)).filter(Boolean).map(({ buffer, data, ...meta }) => ({ ...meta, wav: b64(encodeWav(data, meta.sampleRate)) }));
  const json = JSON.stringify({ format: 'groovesmith-project', version: 2, song: S.song, samples });
  const r = await host.save(`${safe(S.song.name)}.gsproj`, new Blob([json], { type: 'application/json' }));
  toast(r.path ? `Project saved to ${r.path}` : 'Project downloaded');
}

export async function openProject(file) {
  const data = JSON.parse(await (await fetch(file.url)).text());
  if (data?.format !== 'groovesmith-project') throw new Error('Not a Groovesmith project');
  const ctx = audio().ctx;
  for (const s of data.samples || []) {
    if (S.samples.has(s.id)) continue;
    const bin = Uint8Array.from(atob(s.wav), (c) => c.charCodeAt(0));
    const buffer = await ctx.decodeAudioData(bin.buffer);
    const { wav, ...meta } = s;
    await putSample({ ...bufferToRecord(buffer, meta), ...meta, data: Array.from({ length: buffer.numberOfChannels }, (_, i) => buffer.getChannelData(i).slice()) });
  }
  replaceSong(data.song);
  toast(`Opened “${S.song.name}”`);
}

export function newProject(demo) {
  stop();
  replaceSong(demo ? null : { version: 2, name: 'Untitled beat', tracks: S.song.tracks.filter((t) => t.kind === 'drum').map((t) => ({ ...t, id: uid() })), patterns: [], arrangement: [0] });
}
