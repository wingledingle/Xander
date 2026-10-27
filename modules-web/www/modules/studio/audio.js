// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Web Audio engine: mixer (channel strips, sends, master bus), drum synthesis, sample and synth voices,
// the look-ahead sequencer and offline bounce. Every function takes any BaseAudioContext, so live playback
// and WAV export run the same graph.

import {
  DRUMS, clamp, cutoffHz, stepDuration, swingOffset, noteFreq, degreeToMidi, audible, patternAt, songBars, barSeconds,
} from './core.js';

// ---- Shared buffers ----------------------------------------------------------------------------------

const noiseCache = new WeakMap();
function noise(ctx) {
  if (!noiseCache.has(ctx)) {
    const b = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate), d = b.getChannelData(0);
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1;
    noiseCache.set(ctx, b);
  }
  return noiseCache.get(ctx);
}
function impulse(ctx, seconds) {
  const len = Math.max(1, Math.floor(ctx.sampleRate * seconds)), b = ctx.createBuffer(2, len, ctx.sampleRate);
  for (let c = 0; c < 2; c++) {
    const d = b.getChannelData(c);
    for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / len) ** 3.2;
  }
  return b;
}
const reversed = new WeakMap();
export function reverseBuffer(ctx, buf) {
  if (!reversed.has(buf)) {
    const r = ctx.createBuffer(buf.numberOfChannels, buf.length, buf.sampleRate);
    for (let c = 0; c < buf.numberOfChannels; c++) r.getChannelData(c).set(buf.getChannelData(c).slice().reverse());
    reversed.set(buf, r);
  }
  return reversed.get(buf);
}

// Drive (tanh saturation) and crush (amplitude quantisation) share one waveshaper curve.
const curveCache = new Map();
function shaperCurve(drive, crush) {
  const key = `${drive.toFixed(2)}|${crush.toFixed(2)}`;
  if (!curveCache.has(key)) {
    const n = 4096, c = new Float32Array(n), k = 1 + drive * 30, norm = Math.tanh(k);
    const q = crush > 0 ? 2 ** Math.round(16 - crush * 14) / 2 : 0;
    for (let i = 0; i < n; i++) {
      const x = (i / (n - 1)) * 2 - 1;
      let y = drive > 0 ? Math.tanh(k * x) / norm : x;
      if (q) y = Math.round(y * q) / q;
      c[i] = y;
    }
    curveCache.set(key, c);
  }
  return curveCache.get(key);
}

// ---- Mixer -------------------------------------------------------------------------------------------

function eq3(ctx, lowHz = 180, midHz = 1200, highHz = 6000) {
  const low = ctx.createBiquadFilter(), mid = ctx.createBiquadFilter(), high = ctx.createBiquadFilter();
  low.type = 'lowshelf'; low.frequency.value = lowHz;
  mid.type = 'peaking'; mid.frequency.value = midHz; mid.Q.value = 0.9;
  high.type = 'highshelf'; high.frequency.value = highHz;
  low.connect(mid).connect(high);
  return { input: low, output: high, set(p, t) { low.gain.setTargetAtTime(p.low, t, 0.02); mid.gain.setTargetAtTime(p.mid, t, 0.02); high.gain.setTargetAtTime(p.high, t, 0.02); } };
}

// input → filter → drive/crush → EQ → pan → fader → (meter, master, delay send, reverb send)
function channelStrip(ctx, bus) {
  const input = ctx.createGain(), filt = ctx.createBiquadFilter(), shaper = ctx.createWaveShaper(), eq = eq3(ctx);
  const pan = ctx.createStereoPanner(), fader = ctx.createGain(), meter = ctx.createAnalyser();
  const dSend = ctx.createGain(), rSend = ctx.createGain();
  filt.type = 'lowpass';
  meter.fftSize = 256;
  input.connect(filt).connect(shaper).connect(eq.input);
  eq.output.connect(pan).connect(fader);
  fader.connect(bus.sum); fader.connect(meter);
  fader.connect(dSend).connect(bus.delayIn); fader.connect(rSend).connect(bus.verbIn);
  let shape = '';
  return {
    input, meter,
    set(p, live) {
      const t = ctx.currentTime;
      filt.frequency.setTargetAtTime(p.cutoff >= 0.999 ? 20000 : cutoffHz(p.cutoff), t, 0.02);
      filt.Q.setTargetAtTime(p.reso, t, 0.02);
      const s = `${p.drive}|${p.crush}`;
      if (s !== shape) { shape = s; shaper.curve = p.drive || p.crush ? shaperCurve(p.drive, p.crush) : null; shaper.oversample = p.drive ? '2x' : 'none'; }
      eq.set(p, t);
      pan.pan.setTargetAtTime(p.pan, t, 0.02);
      fader.gain.setTargetAtTime(live ? p.vol : 0, t, 0.015);
      dSend.gain.setTargetAtTime(p.delay, t, 0.02);
      rSend.gain.setTargetAtTime(p.reverb * 0.8, t, 0.02);
    },
    disconnect() { input.disconnect(); fader.disconnect(); },
  };
}

export function createEngine(ctx) {
  const g = (v = 1) => { const n = ctx.createGain(); n.gain.value = v; return n; };
  const sum = g(), eq = eq3(ctx, 120, 1500, 8000), comp = ctx.createDynamicsCompressor(), master = g(0.85);
  comp.knee.value = 6; comp.attack.value = 0.005; comp.release.value = 0.12;
  const limit = ctx.createDynamicsCompressor();
  limit.threshold.value = -1.5; limit.knee.value = 0; limit.ratio.value = 20; limit.attack.value = 0.001; limit.release.value = 0.06;
  const analyser = ctx.createAnalyser();
  analyser.fftSize = 2048;
  sum.connect(eq.input); eq.output.connect(comp).connect(master).connect(limit).connect(analyser).connect(ctx.destination);

  const delayIn = g(), delay = ctx.createDelay(4), fb = g(0.38), tone = ctx.createBiquadFilter();
  tone.frequency.value = 4200;
  delayIn.connect(delay).connect(tone).connect(fb).connect(delay);
  tone.connect(sum);
  const verbIn = g(), verb = ctx.createConvolver();
  let verbSize = 0;
  verbIn.connect(verb).connect(sum);

  const preview = g(0.9); // library / pad auditions skip the channel strips
  preview.connect(sum);

  const bus = { sum, delayIn, verbIn };
  const strips = new Map(), voices = new Map();
  let song = null, lastFreq = 0;

  function strip(id) {
    if (!strips.has(id)) strips.set(id, channelStrip(ctx, bus));
    return strips.get(id);
  }
  function track(id) { return id === 'synth' ? song.synth : song.tracks.find((t) => t.id === id); }
  function addVoice(id, v) {
    const list = voices.get(id) || [];
    list.push(v);
    if (list.length > 24) list.shift();
    voices.set(id, list);
  }
  function choke(id, t) { for (const v of voices.get(id)?.splice(0) || []) v.stop(t); }

  const engine = {
    ctx, analyser, preview,
    meters: () => new Map([...strips].map(([id, s]) => [id, s.meter])),
    // Syncs the graph to the song: params on every strip, new strips for new tracks, old ones removed.
    apply(s) {
      song = s;
      const t = ctx.currentTime, on = audible(s), ids = new Set([...s.tracks.map((x) => x.id), 'synth']);
      for (const [id, st] of strips) if (!ids.has(id) && !id.startsWith('aux:')) { st.disconnect(); strips.delete(id); }
      for (const tr of s.tracks) strip(tr.id).set(tr, on.has(tr.id));
      strip('synth').set(s.synth, on.has('synth'));
      const m = s.master;
      eq.set(m, t);
      comp.threshold.setTargetAtTime(m.threshold, t, 0.02);
      comp.ratio.setTargetAtTime(m.ratio, t, 0.02);
      master.gain.setTargetAtTime(m.vol, t, 0.02);
      delay.delayTime.setTargetAtTime(stepDuration(s.bpm) * m.delayTime, t, 0.05);
      fb.gain.setTargetAtTime(m.delayFb, t, 0.02);
      if (Math.abs(verbSize - m.reverbSize) > 0.05) { verbSize = m.reverbSize; verb.buffer = impulse(ctx, verbSize); }
    },
    // Plays track `id` (drum or sample) at time t with velocity vel. getSample(ref) → {buffer, settings}.
    trigger(id, t, vel, getSample) {
      const tr = track(id);
      if (!tr || id === 'synth') return;
      const out = strip(id).input;
      if (tr.choke) choke(id, t);
      if (tr.kind === 'drum') {
        if (tr.ref === 'chat') for (const o of song.tracks) if (o.kind === 'drum' && o.ref === 'ohat') choke(o.id, t);
        addVoice(id, DRUM_VOICES[tr.ref](ctx, out, t, vel, 2 ** (tr.pitch / 12), tr.decay));
      } else {
        const smp = getSample(tr.ref);
        if (smp) addVoice(id, playSample(ctx, out, smp.buffer, t, { ...smp.settings, vel, pitch: (smp.settings?.pitch || 0) + tr.pitch, decay: tr.decay }));
      }
    },
    // Extra strip outside the song (the Video deck's audio FX). Returns its input node.
    aux(id, p) { const st = strip(`aux:${id}`); st.set(p, true); return st.input; },
    note(freq, t, vel = 1) {
      const v = synthVoice(ctx, strip('synth').input, song.synth, freq, t, vel, lastFreq);
      lastFreq = freq;
      return v;
    },
    stopAll(t = ctx.currentTime) { for (const id of voices.keys()) choke(id, t); },
  };
  return engine;
}

// ---- Voices ------------------------------------------------------------------------------------------

function env(ctx, out, t, peak, decay) {
  const a = ctx.createGain();
  a.gain.setValueAtTime(0, t);
  a.gain.linearRampToValueAtTime(peak, t + 0.002);
  a.gain.exponentialRampToValueAtTime(0.0001, t + decay);
  a.connect(out);
  return a;
}
function osc(ctx, type, freq, t, end, dest) {
  const o = ctx.createOscillator();
  o.type = type; o.frequency.setValueAtTime(freq, t);
  o.connect(dest); o.start(t); o.stop(end);
  return o;
}
function noiseHit(ctx, dest, t, end, type, freq, q = 1) {
  const s = ctx.createBufferSource(), f = ctx.createBiquadFilter();
  s.buffer = noise(ctx); f.type = type; f.frequency.value = freq; f.Q.value = q;
  s.connect(f).connect(dest); s.start(t); s.stop(end);
}
// Every drum voice returns {stop(t)} for choke groups. p = pitch ratio, d = decay scale.
function voice(ctx, out, t, build) {
  const vca = ctx.createGain();
  vca.connect(out);
  build(vca);
  return { stop(at) { vca.gain.setTargetAtTime(0, Math.max(at, t), 0.006); } };
}
export const DRUM_VOICES = {
  kick: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    const k = osc(ctx, 'sine', 160 * p, t, t + 0.6 * d + 0.05, env(ctx, o, t, v * 1.1, 0.55 * d));
    k.frequency.exponentialRampToValueAtTime(42 * p, t + 0.13);
    osc(ctx, 'triangle', 1200, t, t + 0.01, env(ctx, o, t, v * 0.25, 0.008));
  }),
  snare: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    const s = osc(ctx, 'triangle', 220 * p, t, t + 0.2, env(ctx, o, t, v * 0.5, 0.12 * d));
    s.frequency.exponentialRampToValueAtTime(160 * p, t + 0.08);
    noiseHit(ctx, env(ctx, o, t, v * 0.7, 0.22 * d), t, t + 0.25 * d + 0.02, 'highpass', 1400 * p);
  }),
  clap: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    for (const [dt, len] of [[0, 0.03], [0.011, 0.03], [0.022, 0.2 * d]]) noiseHit(ctx, env(ctx, o, t + dt, v * 0.8, len), t + dt, t + dt + len, 'bandpass', 1300 * p, 1.4);
  }),
  chat: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => noiseHit(ctx, env(ctx, o, t, v * 0.45, 0.05 * d), t, t + 0.07 * d + 0.01, 'highpass', 8000 * p)),
  ohat: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => noiseHit(ctx, env(ctx, o, t, v * 0.4, 0.38 * d), t, t + 0.4 * d + 0.02, 'highpass', 7500 * p)),
  tom: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    const m = osc(ctx, 'sine', 210 * p, t, t + 0.4 * d + 0.02, env(ctx, o, t, v * 0.9, 0.35 * d));
    m.frequency.exponentialRampToValueAtTime(105 * p, t + 0.3 * d);
  }),
  rim: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    const f = ctx.createBiquadFilter();
    f.type = 'bandpass'; f.frequency.value = 1750 * p; f.Q.value = 4;
    f.connect(env(ctx, o, t, v * 1.2, 0.04 * d + 0.01));
    osc(ctx, 'square', 1700 * p, t, t + 0.05, f);
    osc(ctx, 'triangle', 480 * p, t, t + 0.05, env(ctx, o, t, v * 0.3, 0.03));
  }),
  cowbell: (ctx, out, t, v, p, d) => voice(ctx, out, t, (o) => {
    const f = ctx.createBiquadFilter();
    f.type = 'bandpass'; f.frequency.value = 900 * p; f.Q.value = 1.5;
    f.connect(env(ctx, o, t, v * 0.45, 0.32 * d));
    osc(ctx, 'square', 540 * p, t, t + 0.35 * d + 0.02, f);
    osc(ctx, 'square', 800 * p, t, t + 0.35 * d + 0.02, f);
  }),
};
export const DRUM_LIST = DRUMS;

// Sample voice. settings: start/end (seconds in the buffer), gain, attack, release (s), reverse, pitch (semitones).
export function playSample(ctx, out, buffer, t, s = {}) {
  const src = ctx.createBufferSource(), vca = ctx.createGain();
  const rev = s.reverse === true;
  src.buffer = rev ? reverseBuffer(ctx, buffer) : buffer;
  const rate = 2 ** ((s.pitch || 0) / 12);
  src.playbackRate.value = rate;
  let a = clamp(s.start ?? 0, 0, buffer.duration), b = clamp(s.end ?? buffer.duration, a, buffer.duration);
  if (rev) [a, b] = [buffer.duration - b, buffer.duration - a];
  const len = (b - a) * (s.decay ?? 1), dur = len / rate, peak = (s.gain ?? 1) * (s.vel ?? 1);
  const att = Math.min(s.attack ?? 0.002, dur / 2), rel = Math.min(s.release ?? 0.01, dur / 2);
  vca.gain.setValueAtTime(0, t);
  vca.gain.linearRampToValueAtTime(peak, t + att);
  vca.gain.setValueAtTime(peak, t + dur - rel);
  vca.gain.linearRampToValueAtTime(0, t + dur);
  src.connect(vca).connect(out);
  if (s.loop) { src.loop = true; src.loopStart = a; src.loopEnd = b; src.start(t, a); }
  else src.start(t, a, len + 0.005);
  return { src, stop(at) { const x = Math.max(at, t); vca.gain.cancelScheduledValues(x); vca.gain.setTargetAtTime(0, x, 0.008); src.stop(x + 0.06); } };
}

// Two detuned oscillators → resonant low-pass with its own envelope → amp ADSR. Optional glide from the last note.
function synthVoice(ctx, out, p, freq, t, vel, from) {
  const peak = 0.3 * vel, amp = ctx.createGain(), filt = ctx.createBiquadFilter();
  filt.type = 'lowpass'; filt.Q.value = p.reso;
  const base = cutoffHz(p.cutoff * 0.9), top = Math.min(18000, base + p.envAmt * 9000);
  filt.frequency.setValueAtTime(base, t);
  filt.frequency.linearRampToValueAtTime(top, t + p.attack);
  filt.frequency.setTargetAtTime(base + (top - base) * p.sustain, t + p.attack, p.decay / 3);
  amp.gain.setValueAtTime(0, t);
  amp.gain.linearRampToValueAtTime(peak, t + p.attack);
  amp.gain.setTargetAtTime(peak * p.sustain, t + p.attack, p.decay / 3);
  filt.connect(amp).connect(out);
  const oscs = [[p.wave1, -p.detune / 2], [p.wave2, p.detune / 2]].map(([type, cents]) => {
    const o = ctx.createOscillator();
    o.type = type; o.detune.value = cents;
    if (p.glide > 0 && from) { o.frequency.setValueAtTime(from, t); o.frequency.exponentialRampToValueAtTime(freq, t + p.glide); }
    else o.frequency.value = freq;
    o.connect(filt); o.start(t);
    return o;
  });
  return {
    release(at) {
      const end = Math.max(at, t + p.attack);
      amp.gain.setTargetAtTime(0, end, p.release / 3);
      filt.frequency.setTargetAtTime(base, end, p.release / 3);
      oscs.forEach((o) => o.stop(end + p.release * 2 + 0.05));
    },
  };
}

function click(ctx, t, accent) {
  const o = ctx.createOscillator(), a = ctx.createGain();
  o.frequency.value = accent ? 1800 : 1200;
  a.gain.setValueAtTime(0.25, t); a.gain.exponentialRampToValueAtTime(0.0001, t + 0.04);
  o.connect(a).connect(ctx.destination); o.start(t); o.stop(t + 0.05);
}

// Schedules one step of one pattern.
export function scheduleStep(engine, song, pattern, step, t, getSample) {
  const pat = song.patterns[pattern], on = audible(song), dur = stepDuration(song.bpm);
  for (const tr of song.tracks) {
    const v = pat.steps[tr.id]?.[step] || 0;
    if (v > 0 && on.has(tr.id)) engine.trigger(tr.id, t, v, getSample);
  }
  const deg = pat.notes[step];
  if (deg >= 0 && on.has('synth')) {
    engine.note(noteFreq(degreeToMidi(deg, song.synth.root, song.synth.scale)), t).release(t + dur * song.synth.gate);
  }
}

// ---- Live sequencer ----------------------------------------------------------------------------------

export function createSequencer(engine, getSong, getSample, { onStep, onEnd } = {}) {
  const ctx = engine.ctx, AHEAD = 0.14;
  let timer = 0, bar = 0, step = 0, next = 0, metro = false, loop = true;
  const drawn = []; // {time, step, pattern, bar} — for the playhead and live recording
  function tick() {
    const song = getSong(), dur = stepDuration(song.bpm);
    while (next < ctx.currentTime + AHEAD) {
      const { pattern, done } = patternAt(song, bar);
      if (done) {
        if (!loop) { seq.stop(); onEnd?.(); return; }
        bar = 0; continue;
      }
      const t = next + swingOffset(step, dur, song.swing);
      scheduleStep(engine, song, pattern, step, t, getSample);
      if (metro && step % 4 === 0) click(ctx, next, step === 0);
      drawn.push({ time: t, step, pattern, bar });
      if (drawn.length > 64) drawn.shift();
      next += dur;
      if (++step >= song.steps) { step = 0; bar++; }
    }
  }
  function frame() {
    if (!timer) return;
    const now = ctx.currentTime - (ctx.outputLatency || 0);
    let cur = null;
    for (const d of drawn) if (d.time <= now) cur = d;
    if (cur) onStep?.(cur);
    requestAnimationFrame(frame);
  }
  const seq = {
    get playing() { return !!timer; },
    set metronome(v) { metro = v; },
    set loop(v) { loop = v; },
    start(fromBar = 0) {
      if (timer) return;
      bar = fromBar; step = 0; next = ctx.currentTime + 0.06; drawn.length = 0;
      timer = setInterval(tick, 20);
      tick();
      requestAnimationFrame(frame);
    },
    stop() {
      clearInterval(timer); timer = 0;
      engine.stopAll();
      onStep?.(null);
    },
    // Quantised position for live recording: the step nearest to audio time t.
    stepAt(t) {
      let best = null;
      for (const d of drawn) if (!best || Math.abs(d.time - t) < Math.abs(best.time - t)) best = d;
      return best;
    },
  };
  return seq;
}

// ---- Offline bounce ----------------------------------------------------------------------------------

// Renders the pattern (bars times) or the whole arrangement to an AudioBuffer, with a 2 s tail.
export async function renderSong(OfflineCtx, song, getSample, { bars = 4, sampleRate = 44100, whole = song.mode === 'song' } = {}) {
  const dur = stepDuration(song.bpm), n = whole ? songBars(song) : bars, tail = 2;
  const ctx = new OfflineCtx(2, Math.ceil((n * barSeconds(song) + tail) * sampleRate), sampleRate);
  const engine = createEngine(ctx);
  engine.apply(song);
  for (let b = 0; b < n; b++) {
    const pattern = whole ? song.arrangement[b] : song.current;
    for (let s = 0; s < song.steps; s++) scheduleStep(engine, song, pattern, s, 0.02 + (b * song.steps + s) * dur + swingOffset(s, dur, song.swing), getSample);
  }
  return ctx.startRendering();
}

// Renders one sample through its settings plus offline FX (used when saving video chops to the library).
export async function renderSample(OfflineCtx, buffer, settings, fx = {}) {
  const rate = 2 ** (((settings.pitch || 0) + (fx.pitch || 0)) / 12);
  const len = ((settings.end ?? buffer.duration) - (settings.start ?? 0)) / rate;
  const tail = fx.reverb ? 1.5 : fx.delay ? 1 : 0.02;
  const ctx = new OfflineCtx(buffer.numberOfChannels > 1 ? 2 : 1, Math.max(1, Math.ceil((len + tail) * buffer.sampleRate)), buffer.sampleRate);
  const engine = createEngine(ctx);
  const t = { id: 'x', kind: 'sample', ref: 'x', vol: 1, pan: 0, pitch: fx.pitch || 0, decay: 1, cutoff: fx.cutoff ?? 1, reso: fx.reso ?? 0.7,
    drive: fx.drive || 0, crush: fx.crush || 0, low: fx.low || 0, mid: fx.mid || 0, high: fx.high || 0, delay: fx.delay || 0, reverb: fx.reverb || 0, mute: false, solo: false };
  engine.apply({ tracks: [t], synth: { vol: 0, pan: 0, cutoff: 1, reso: 0.7, drive: 0, crush: 0, low: 0, mid: 0, high: 0, delay: 0, reverb: 0, mute: true }, bpm: 120,
    master: { vol: 1, low: 0, mid: 0, high: 0, threshold: 0, ratio: 1, delayTime: 3, delayFb: 0.3, reverbSize: 1.6 } });
  engine.trigger('x', 0, 1, () => ({ buffer, settings: { ...settings, reverse: !!settings.reverse !== !!fx.reverse } }));
  return ctx.startRendering();
}
