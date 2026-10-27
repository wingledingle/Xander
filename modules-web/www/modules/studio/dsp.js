// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Offline DSP on plain Float32Arrays: waveform peaks, chop/slice finding, sample edits and WAV encoding.
// No Web Audio here, so Node tests cover all of it.

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

// Min/max pairs for drawing a waveform `width` columns wide from samples [from, to).
export function peaks(data, width, from = 0, to = data.length) {
  const out = new Float32Array(width * 2), span = Math.max(1, (to - from) / width);
  for (let x = 0; x < width; x++) {
    let lo = 0, hi = 0;
    const a = Math.floor(from + x * span), b = Math.min(to, Math.floor(from + (x + 1) * span));
    // honey: strided scan over long columns keeps redraws O(width·64), visually identical for waveforms.
    const stride = Math.max(1, Math.floor((b - a) / 64));
    for (let i = a; i < b; i += stride) { const v = data[i]; if (v < lo) lo = v; if (v > hi) hi = v; }
    out[x * 2] = lo; out[x * 2 + 1] = hi;
  }
  return out;
}

export function mixDown(channels) {
  if (channels.length === 1) return channels[0];
  const n = channels[0].length, out = new Float32Array(n);
  for (const c of channels) for (let i = 0; i < n; i++) out[i] += c[i] / channels.length;
  return out;
}

// Onset (transient) detection: spectral-flux-free energy novelty, good enough for drums, plucks and speech.
// Returns sample indices. sensitivity 0..1 (higher = more chops); minGap in seconds.
export function detectOnsets(data, sampleRate, { sensitivity = 0.5, minGap = 0.08, max = 64 } = {}) {
  const hop = Math.max(64, Math.round(sampleRate * 0.005)), frames = Math.floor(data.length / hop);
  if (frames < 3) return [];
  const env = new Float32Array(frames);
  for (let f = 0; f < frames; f++) {
    let e = 0;
    for (let i = f * hop, end = i + hop; i < end; i++) e += data[i] * data[i];
    env[f] = Math.sqrt(e / hop);
  }
  // Novelty = positive change in log energy; adaptive threshold = local mean + k·deviation.
  const nov = new Float32Array(frames);
  for (let f = 1; f < frames; f++) nov[f] = Math.max(0, Math.log10(env[f] + 1e-4) - Math.log10(env[f - 1] + 1e-4));
  const win = 20, k = 2.5 - 2.2 * clamp(sensitivity, 0, 1), floor = 0.002 + 0.02 * (1 - sensitivity);
  const gap = Math.max(1, Math.round((minGap * sampleRate) / hop));
  const hitsOut = [];
  let last = -gap;
  for (let f = 1; f < frames - 1; f++) {
    let sum = 0, sq = 0, n = 0;
    for (let j = Math.max(1, f - win); j < Math.min(frames, f + win); j++) { sum += nov[j]; sq += nov[j] * nov[j]; n++; }
    const mean = sum / n, sd = Math.sqrt(Math.max(0, sq / n - mean * mean));
    if (nov[f] > mean + k * sd && nov[f] >= nov[f - 1] && nov[f] >= nov[f + 1] && env[f] > floor && f - last >= gap) {
      hitsOut.push(Math.max(0, (f - 1) * hop));
      last = f;
    }
  }
  return pickStrongest(hitsOut, data, max);
}

function pickStrongest(idx, data, max) {
  if (idx.length <= max) return idx;
  const peak = (i) => { let p = 0; for (let j = i; j < Math.min(data.length, i + 2048); j++) p = Math.max(p, Math.abs(data[j])); return p; };
  return idx.map((i) => [i, peak(i)]).sort((a, b) => b[1] - a[1]).slice(0, max).map((x) => x[0]).sort((a, b) => a - b);
}

// N equal chops between [start, end).
export function equalSlices(start, end, n) {
  n = Math.max(1, Math.floor(n));
  const len = (end - start) / n;
  return Array.from({ length: n }, (_, i) => ({ start: Math.round(start + i * len), end: Math.round(start + (i + 1) * len) }));
}

// Chops on the beat grid: one per `division` sixteenths at `bpm`, starting at `start`.
export function gridSlices(start, end, sampleRate, bpm, division = 4) {
  const len = (60 / bpm / 4) * division * sampleRate, out = [];
  for (let s = start; s + len * 0.25 < end && out.length < 128; s += len) out.push({ start: Math.round(s), end: Math.round(Math.min(end, s + len)) });
  return out;
}

// Turns marker positions into back-to-back regions that cover [start, end).
export function markersToSlices(markers, start, end) {
  const m = [...new Set(markers.filter((x) => x > start && x < end).map(Math.round))].sort((a, b) => a - b);
  const edges = [start, ...m, end];
  return edges.slice(0, -1).map((s, i) => ({ start: s, end: edges[i + 1] })).filter((r) => r.end - r.start > 32);
}

// Moves a cut point to the nearest zero crossing within `radius` samples so chops don't click.
export function snapToZero(data, i, radius = 256) {
  for (let d = 0; d <= radius; d++) {
    for (const j of [i - d, i + d]) if (j > 0 && j < data.length && (data[j - 1] <= 0) !== (data[j] <= 0)) return j;
  }
  return i;
}

export function region(channels, start, end) {
  return channels.map((c) => c.slice(clamp(start, 0, c.length), clamp(end, 0, c.length)));
}

export function reverse(channels) { return channels.map((c) => c.slice().reverse()); }

export function normalize(channels, targetDb = -0.3) {
  let p = 0;
  for (const c of channels) for (let i = 0; i < c.length; i++) p = Math.max(p, Math.abs(c[i]));
  if (p < 1e-6) return channels.map((c) => c.slice());
  const g = 10 ** (targetDb / 20) / p;
  return channels.map((c) => c.map((v) => v * g));
}

// Linear fade in/out, in samples. Stops clicks at chop edges.
export function fade(channels, fadeIn, fadeOut) {
  return channels.map((c) => {
    const o = c.slice(), n = o.length;
    for (let i = 0; i < Math.min(fadeIn, n); i++) o[i] *= i / fadeIn;
    for (let i = 0; i < Math.min(fadeOut, n); i++) o[n - 1 - i] *= i / fadeOut;
    return o;
  });
}

// Bit depth + sample-rate reduction. bits 1..16, hold = keep every Nth sample.
export function bitcrush(channels, bits, hold = 1) {
  const q = 2 ** (clamp(Math.round(bits), 1, 16) - 1);
  return channels.map((c) => {
    const o = new Float32Array(c.length);
    let v = 0;
    for (let i = 0; i < c.length; i++) { if (i % hold === 0) v = Math.round(c[i] * q) / q; o[i] = v; }
    return o;
  });
}

// Trim leading/trailing audio quieter than `db`.
export function trimSilence(channels, db = -50) {
  const th = 10 ** (db / 20), m = mixDown(channels);
  let a = 0, b = m.length;
  while (a < b && Math.abs(m[a]) < th) a++;
  while (b > a && Math.abs(m[b - 1]) < th) b--;
  return { start: a, end: b };
}

export function peakDb(channels) {
  let p = 0;
  for (const c of channels) for (let i = 0; i < c.length; i++) p = Math.max(p, Math.abs(c[i]));
  return p ? 20 * Math.log10(p) : -Infinity;
}

// 16-bit PCM WAV from one Float32Array per channel.
export function encodeWav(channels, sampleRate) {
  const n = channels[0].length, ch = channels.length;
  const buf = new ArrayBuffer(44 + n * ch * 2), v = new DataView(buf);
  const w = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  w(0, 'RIFF'); v.setUint32(4, 36 + n * ch * 2, true); w(8, 'WAVE');
  w(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, ch, true);
  v.setUint32(24, sampleRate, true); v.setUint32(28, sampleRate * ch * 2, true); v.setUint16(32, ch * 2, true); v.setUint16(34, 16, true);
  w(36, 'data'); v.setUint32(40, n * ch * 2, true);
  let o = 44;
  for (let i = 0; i < n; i++) for (let c = 0; c < ch; c++, o += 2) v.setInt16(o, clamp(channels[c][i], -1, 1) * 0x7fff, true);
  return buf;
}

export const fmtTime = (sec) => {
  if (!Number.isFinite(sec)) return '0:00.00';
  const m = Math.floor(sec / 60), s = sec - m * 60;
  return `${m}:${s < 10 ? '0' : ''}${s.toFixed(2)}`;
};
