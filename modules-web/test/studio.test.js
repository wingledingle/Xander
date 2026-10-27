// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Unit tests for Groovesmith Studio's pure modules: song model, DSP, library search, video FX params.
// Browser flows are covered by proof.js.   node --test test/*.test.js

import test from 'node:test';
import assert from 'node:assert/strict';
import * as C from '../www/modules/studio/core.js';
import * as D from '../www/modules/studio/dsp.js';
import { searchSamples, normTags, allTags, uniqueName } from '../www/modules/studio/library.js';
import { normalizeVideoFx, defaultVideoFx, VIDEO_PRESETS, FX_KEYS } from '../www/modules/studio/videofx.js';

// ---- Song model ----

test('demo song is valid and survives a JSON round trip', () => {
  const s = C.demoSong();
  assert.deepEqual(C.normalizeSong(JSON.parse(JSON.stringify(s))), s);
  assert.equal(s.tracks.length, 8);
  assert.equal(s.patterns.length, C.PATTERNS);
});

test('normalizeSong clamps numbers, drops junk and fixes step lengths', () => {
  const s = C.demoSong();
  const t = s.tracks[0];
  const bad = JSON.parse(JSON.stringify(s));
  Object.assign(bad, { bpm: 999, swing: -1, steps: 17, current: 42, arrangement: [0, 9, 'x', 2] });
  bad.tracks[0].vol = 50; bad.tracks[0].color = 'red; background:url(x)';
  bad.patterns[0].steps[t.id] = [2, -1, 'a'];
  bad.patterns[0].notes = [99, 3];
  bad.synth.scale = 'nope';
  const n = C.normalizeSong(bad);
  assert.equal(n.bpm, 240);
  assert.equal(n.swing, 0);
  assert.equal(n.steps, 16);
  assert.equal(n.current, C.PATTERNS - 1);
  assert.deepEqual(n.arrangement, [0, 2]);
  assert.equal(n.tracks[0].vol, C.CHANNEL_PARAMS.vol[1]);
  assert.match(n.tracks[0].color, /^#[0-9a-f]{6}$/i);
  assert.equal(n.patterns[0].steps[t.id].length, 16);
  assert.deepEqual(n.patterns[0].steps[t.id].slice(0, 3), [1, 0, 0]);
  assert.deepEqual(n.patterns[0].notes.slice(0, 2), [-1, 3]);
  assert.equal(n.synth.scale, 'minor');
  assert.equal(C.normalizeSong(null).tracks.length, 8);
});

test('Groovesmith 1 songs migrate: booleans become velocities, levels and mutes carry over', () => {
  const v1 = { bpm: 100, swing: 0.2, current: 1, levels: { kick: 0.5 }, mutes: { snare: true },
    patterns: [{ drums: { kick: [true, false, true] }, notes: [0, -1, 5] }, { drums: {}, notes: [] }] };
  const s = C.normalizeSong(v1);
  const kick = s.tracks.find((t) => t.ref === 'kick'), snare = s.tracks.find((t) => t.ref === 'snare');
  assert.equal(s.version, 2);
  assert.equal(s.bpm, 100);
  assert.equal(kick.vol, 0.5);
  assert.equal(snare.mute, true);
  assert.deepEqual(s.patterns[0].steps[kick.id].slice(0, 3), [0.78, 0, 0.78]);
  assert.deepEqual(s.patterns[0].notes.slice(0, 3), [0, -1, 5]);
  assert.equal(s.current, 1);
});

test('setStepCount repeats a 16-step bar into 32 and trims back', () => {
  const s = C.demoSong(), id = s.tracks[0].id, before = [...s.patterns[0].steps[id]];
  C.setStepCount(s, 32);
  assert.deepEqual(s.patterns[0].steps[id], [...before, ...before]);
  assert.equal(s.patterns[0].notes.length, 32);
  C.setStepCount(s, 16);
  assert.deepEqual(s.patterns[0].steps[id], before);
});

test('tracks: add up to the limit, remove clears steps everywhere', () => {
  const s = C.demoSong();
  while (C.addTrack(s, 'sample', 'x', 'S')) {}
  assert.equal(s.tracks.length, C.MAX_TRACKS);
  const id = s.tracks.at(-1).id;
  C.removeTrack(s, id);
  assert.ok(s.patterns.every((p) => !(id in p.steps)));
});

test('solo silences everything not soloed; mute always wins', () => {
  const s = C.demoSong();
  s.tracks[1].solo = true;
  assert.deepEqual([...C.audible(s)], [s.tracks[1].id]);
  s.tracks[1].mute = true;
  assert.equal(C.audible(s).size, 0);
});

test('song mode walks the arrangement and reports the end', () => {
  const s = C.demoSong();
  s.mode = 'song'; s.arrangement = [2, 0, 3];
  assert.deepEqual([0, 1, 2].map((b) => C.patternAt(s, b).pattern), [2, 0, 3]);
  assert.equal(C.patternAt(s, 3).done, true);
  s.mode = 'pattern'; s.current = 5;
  assert.deepEqual(C.patternAt(s, 7), { pattern: 5, done: false });
});

test('barEvents applies swing to off-beats and skips muted tracks', () => {
  const s = C.demoSong();
  s.swing = 0.5;
  const kick = s.tracks[0];
  s.patterns[0].steps[kick.id] = Array(16).fill(0); s.patterns[0].steps[kick.id][1] = 1;
  const dur = C.stepDuration(s.bpm);
  const ev = C.barEvents(s, 0, 10).filter((e) => e.track === kick.id);
  assert.equal(ev.length, 1);
  assert.ok(Math.abs(ev[0].time - (10 + dur * 1.5)) < 1e-9);
  kick.mute = true;
  assert.equal(C.barEvents(s, 0, 0).filter((e) => e.track === kick.id).length, 0);
});

test('tap tempo averages recent taps and ignores long gaps', () => {
  assert.equal(C.tapTempo([0, 0.5, 1, 1.5]), 120);
  assert.equal(C.tapTempo([0, 5, 5.5]), 120);
  assert.equal(C.tapTempo([1]), null);
});

test('undo history: push, undo, redo, and a new edit drops the redo branch', () => {
  const h = C.createHistory();
  h.push('a'); h.push('b'); h.push('c');
  assert.equal(h.undo(), 'b');
  assert.equal(h.redo(), 'c');
  assert.equal(h.undo(), 'b');
  h.push('d');
  assert.equal(h.canRedo, false);
  assert.equal(h.undo(), 'b');
});

test('degreeToMidi follows the scale across octaves', () => {
  assert.equal(C.degreeToMidi(0, 57, 'minor'), 57);
  assert.equal(C.degreeToMidi(7, 57, 'minor'), 69);
  assert.equal(C.degreeToMidi(2, 60, 'major'), 64);
  assert.equal(C.noteName(69), 'A4');
});

// ---- DSP ----

const SR = 44100;
function bursts(times, len = 8 * SR) {
  const d = new Float32Array(len);
  for (const t of times) {
    const s = Math.round(t * SR);
    for (let i = 0; i < SR * 0.3 && s + i < len; i++) d[s + i] = Math.sin((2 * Math.PI * 330 * i) / SR) * Math.exp(-i / (SR * 0.06));
  }
  return d;
}

test('detectOnsets finds each hit within 10 ms and nothing in the decay', () => {
  const times = [0.25, 0.8, 1.1, 2, 2.35, 3.5];
  const on = D.detectOnsets(bursts(times), SR, { sensitivity: 0.5 }).map((i) => i / SR);
  assert.equal(on.length, times.length, `got ${on.map((x) => x.toFixed(3))}`);
  on.forEach((t, i) => assert.ok(Math.abs(t - times[i]) < 0.01, `${t} vs ${times[i]}`));
});

test('detectOnsets ignores quiet noise and caps the count by strength', () => {
  const noise = Float32Array.from({ length: SR * 2 }, () => (Math.random() - 0.5) * 0.002);
  assert.equal(D.detectOnsets(noise, SR).length, 0);
  const many = bursts(Array.from({ length: 30 }, (_, i) => 0.1 + i * 0.25));
  assert.equal(D.detectOnsets(many, SR, { max: 8 }).length, 8);
});

test('slicing helpers cover the region back to back', () => {
  assert.deepEqual(D.equalSlices(0, 100, 4).map((r) => [r.start, r.end]), [[0, 25], [25, 50], [50, 75], [75, 100]]);
  const g = D.gridSlices(0, SR * 2, SR, 120, 4); // quarter notes at 120 bpm = 0.5 s
  assert.equal(g.length, 4);
  assert.equal(g[1].start, SR / 2);
  const m = D.markersToSlices([3000, 1000, 1000, 99999], 0, 5000);
  assert.deepEqual(m.map((r) => [r.start, r.end]), [[0, 1000], [1000, 3000], [3000, 5000]]);
});

test('snapToZero moves a cut onto a zero crossing', () => {
  const d = Float32Array.from({ length: 1000 }, (_, i) => Math.sin(i / 20));
  const j = D.snapToZero(d, 70);
  assert.ok(Math.sign(d[j - 1]) !== Math.sign(d[j]));
  assert.ok(Math.abs(j - 70) < 70);
});

test('normalize, reverse, fade, bitcrush, trimSilence', () => {
  const c = [Float32Array.from([0, 0.25, -0.5, 0.1])];
  assert.ok(Math.abs(Math.max(...D.normalize(c, 0)[0].map(Math.abs)) - 1) < 1e-6);
  assert.deepEqual([...D.reverse(c)[0]], [...c[0]].reverse());
  const f = D.fade([new Float32Array(12).fill(1)], 5, 5)[0];
  assert.equal(f[0], 0); assert.equal(f[11], 0); assert.equal(f[6], 1);
  const q = D.bitcrush([Float32Array.from([0.3, 0.6, -0.9])], 2)[0];
  assert.ok([...q].every((v) => [0, 0.5, 1, -0.5, -1].includes(v)));
  assert.deepEqual(D.trimSilence([Float32Array.from([0, 0, 0.5, 0.2, 0, 0])], -40), { start: 2, end: 4 });
});

test('peaks returns min/max per column', () => {
  const p = D.peaks(Float32Array.from([0, 1, -1, 0.5]), 2);
  assert.deepEqual([...p], [0, 1, -1, 0.5]);
});

test('encodeWav writes a valid 16-bit header and clamps', () => {
  const buf = Buffer.from(D.encodeWav([Float32Array.from([0, 2, -2])], 48000));
  assert.equal(buf.toString('ascii', 0, 4), 'RIFF');
  assert.equal(buf.readUInt32LE(24), 48000);
  assert.equal(buf.readUInt16LE(34), 16);
  assert.equal(buf.readInt16LE(46), 32767);
  assert.equal(buf.readInt16LE(48), -32767);
});

// ---- Library ----

const lib = [
  { id: 'a', name: 'Bell hit', tags: ['bell', 'lead-tone'], created: 1, favorite: false, source: { name: 'clip.webm' } },
  { id: 'b', name: 'Kick 1', tags: ['drums'], created: 3, favorite: false },
  { id: 'c', name: 'Vocal chop', tags: ['vocal', 'drums'], created: 2, favorite: true, source: { name: 'interview.mp4' } },
];

test('library search: words, #tags, tag filter, favourites first', () => {
  assert.deepEqual(searchSamples(lib, 'bell').map((s) => s.id), ['a']);
  assert.deepEqual(searchSamples(lib, '#drums').map((s) => s.id), ['c', 'b']);
  assert.deepEqual(searchSamples(lib, 'interview').map((s) => s.id), ['c']);
  assert.deepEqual(searchSamples(lib, '', 'lead-tone').map((s) => s.id), ['a']);
  assert.deepEqual(searchSamples(lib, '#drum').map((s) => s.id), []);
});

test('tags normalise and count; names stay unique', () => {
  assert.deepEqual(normTags(' Lead Tone, bell,#BELL,,'), ['lead-tone', 'bell']);
  assert.deepEqual(allTags(lib).slice(0, 1), ['drums']);
  assert.equal(uniqueName('Kick 1', ['Kick 1', 'Kick 2']), 'Kick 3');
  assert.equal(uniqueName('Snare', ['Kick']), 'Snare');
});

// ---- Video FX ----

test('video fx params clamp and presets only use known keys', () => {
  const fx = normalizeVideoFx({ contrast: 9, hue: 'x', bogus: 1 });
  assert.equal(fx.contrast, 2);
  assert.equal(fx.hue, 0);
  assert.ok(!('bogus' in fx));
  assert.deepEqual(normalizeVideoFx(null), defaultVideoFx());
  for (const p of Object.values(VIDEO_PRESETS)) for (const k of Object.keys(p)) assert.ok(FX_KEYS.includes(k), k);
});

test('sound pack index names and tags come from file names', async () => {
  const { describe: d } = await import('../tools/sound-index.js');
  assert.deepEqual(d('impactBell_heavy_001.ogg'), { name: 'Impact bell heavy 2', tags: ['impact', 'bell', 'heavy'] });
  assert.deepEqual(d('laserSmall_000.ogg'), { name: 'Laser small 1', tags: ['laser', 'small'] });
  const { readFileSync, existsSync } = await import('node:fs');
  const idx = JSON.parse(readFileSync(new URL('../www/modules/studio/sounds/index.json', import.meta.url)));
  assert.ok(idx.sounds.length > 400);
  for (const s of idx.sounds) assert.ok(existsSync(new URL(`../www/modules/studio/sounds/${s.p}/${s.f}`, import.meta.url)), s.f);
});
