// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Low-latency pads. WebView audio adds roughly 20–100 ms between a touch and the sound, which is fine for
// the sequencer (scheduled ahead on the audio clock) but noticeable when finger-drumming. Inside Xander,
// pad hits go to a native AudioTrack mixer instead (LowLatencyPads.kt): each track's hit is pre-rendered
// once through its channel strip (filter, drive, EQ, pitch, decay) and uploaded as 16-bit PCM; a touch then
// costs one bridge call. Sends (delay/reverb) are not applied on this path. Outside the app it is unavailable
// and callers fall back to Web Audio.

import { createEngine } from './audio.js';

const A = typeof window !== 'undefined' ? window.XanderHost : undefined;
const available = !!A?.padAvailable?.();
const loaded = new Map(); // track id → param key it was rendered with
const rendering = new Set();

function key(track, smp) { return JSON.stringify([track.kind, track.ref, track.pitch, track.decay, track.cutoff, track.reso, track.drive, track.crush, track.low, track.mid, track.high, smp?.settings]); }

function pcm16Base64(buffer) {
  const n = buffer.length, ch = Math.min(2, buffer.numberOfChannels), out = new Int16Array(n * ch);
  const data = Array.from({ length: ch }, (_, c) => buffer.getChannelData(c));
  for (let i = 0; i < n; i++) for (let c = 0; c < ch; c++) out[i * ch + c] = Math.max(-1, Math.min(1, data[c][i])) * 0x7fff;
  const bytes = new Uint8Array(out.buffer);
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

// One hit of `track` at full velocity, centre pan, unity fader, through its insert FX.
async function renderHit(track, smp) {
  const rate = 48000;
  const len = track.kind === 'drum' ? 1.4 * track.decay + 0.1 : Math.min(30, ((smp.settings.end - smp.settings.start) / 2 ** ((smp.settings.pitch + track.pitch) / 12)) * track.decay + 0.05);
  const ctx = new OfflineAudioContext(2, Math.ceil(len * rate), rate);
  const e = createEngine(ctx);
  const t = { ...track, id: 'x', vol: 1, pan: 0, delay: 0, reverb: 0, mute: false, solo: false };
  e.apply({ tracks: [t], bpm: 120, synth: { vol: 0, pan: 0, cutoff: 1, reso: 0.7, drive: 0, crush: 0, low: 0, mid: 0, high: 0, delay: 0, reverb: 0, mute: true },
    master: { vol: 1, low: 0, mid: 0, high: 0, threshold: 0, ratio: 1, delayTime: 3, delayFb: 0, reverbSize: 1 } });
  e.trigger('x', 0, 1, () => smp);
  return ctx.startRendering();
}

export const lowLatency = {
  available,
  latencyMs: () => (available ? A.padLatencyMs() : null),
  // Renders and uploads the track's hit if its sound changed. Returns true when the native copy is current.
  prepare(track, smp) {
    if (!available || (track.kind === 'sample' && !smp)) return false;
    const k = key(track, smp);
    if (loaded.get(track.id) === k) return true;
    if (!rendering.has(track.id)) {
      rendering.add(track.id);
      renderHit(track, smp).then((buf) => {
        A.padLoad(track.id, buf.sampleRate, 2, pcm16Base64(buf));
        loaded.set(track.id, k);
      }).catch((e) => console.warn('pad render failed', e)).finally(() => rendering.delete(track.id));
    }
    return false;
  },
  // Plays a pad hit natively. Returns false if the caller should play it with Web Audio instead.
  trigger(track, vel, smp) {
    if (!this.prepare(track, smp)) return false;
    A.padTrigger(track.id, vel * track.vol, track.pan);
    return true;
  },
  warm(song, getSample) { for (const t of song.tracks) this.prepare(t, t.kind === 'sample' ? getSample(t.ref) : null); },
  forget() { loaded.clear(); },
  stopAll() { if (available) A.padStopAll(); },
};
