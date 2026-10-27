// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Sampler deck: trim, pitch, envelope, reverse and loop for one library sample, plus chopping it into slices
// (transients, equal parts, beat grid or hand-placed markers) that play from 16 pads and save as new samples.

import { S, audio, changed, putSample, touchSample, bufferToRecord } from '../state.js';
import { addTrack, MAX_TRACKS, COLORS } from '../core.js';
import { detectOnsets, equalSlices, gridSlices, markersToSlices, snapToZero, normalize, region, fade, trimSilence, mixDown, fmtTime } from '../dsp.js';
import { uniqueName } from '../library.js';
import { playSample } from '../audio.js';
import { h, btn, knob, segmented, toggle, toast, fmt, icon, onLongPress } from '../ui.js';
import { waveView } from './wave.js';
import { importAudioFiles } from '../actions.js';
import { host } from '../../host/xander.js';

let chopMode = 'transient', amount = 0.5, voice = null, wave = null;

const rec = () => S.samples.get(S.selectedSample) || null;

function audition(r, start, end) {
  const e = audio();
  voice?.stop(e.ctx.currentTime);
  voice = playSample(e.ctx, e.preview, r.buffer, e.ctx.currentTime + 0.005, { ...r.settings, start, end });
  const t0 = e.ctx.currentTime, rate = 2 ** ((r.settings.pitch || 0) / 12), v = voice;
  const tick = () => {
    if (voice !== v) return;
    const pos = start + (e.ctx.currentTime - t0) * rate;
    if (pos > end) { wave?.set({ playhead: null }); return; }
    wave?.set({ playhead: r.settings.reverse ? end - (pos - start) : pos });
    requestAnimationFrame(tick);
  };
  tick();
}

function chop(r) {
  const sr = r.sampleRate, s = Math.round(r.settings.start * sr), e = Math.round(r.settings.end * sr), m = mixDown(r.data);
  let regions;
  if (chopMode === 'transient') {
    const on = detectOnsets(m.subarray(s, e), sr, { sensitivity: amount, max: 16 }).map((i) => snapToZero(m, i + s));
    regions = markersToSlices(on, s, e);
  } else if (chopMode === 'equal') regions = equalSlices(s, e, Math.round(2 + amount * 14));
  else if (chopMode === 'grid') regions = gridSlices(s, e, sr, S.song.bpm, [1, 2, 4, 8][Math.round(amount * 3)]);
  else regions = markersToSlices((r.markers || []).map((t) => Math.round(t * sr)), s, e);
  r.slices = regions.slice(0, 16).map((x) => ({ start: x.start / sr, end: x.end / sr }));
  r.markers = r.slices.slice(1).map((x) => x.start);
  touchSample(r);
}

async function saveSlices(r, which) {
  const list = which == null ? r.slices : [r.slices[which]];
  const names = [...S.samples.values()].map((s) => s.name);
  let n = 0;
  for (const [i, sl] of list.entries()) {
    const sr = r.sampleRate, data = fade(region(r.data, Math.round(sl.start * sr), Math.round(sl.end * sr)), 32, 64);
    const ctx = audio().ctx, b = ctx.createBuffer(data.length, data[0].length, sr);
    data.forEach((c, j) => b.copyToChannel(c, j));
    const name = uniqueName(`${r.name} ${(which ?? i) + 1}`, names);
    names.push(name);
    await putSample(bufferToRecord(b, { name, tags: [...r.tags, 'chop'], color: r.color, thumb: r.thumb, source: { ...(r.source || {}), kind: r.source?.kind || 'chop', parent: r.id, t0: (r.source?.t0 || 0) + sl.start, t1: (r.source?.t0 || 0) + sl.end } }));
    n++;
  }
  toast(`${n} slice${n > 1 ? 's' : ''} saved to the library`);
}

function useOnTrack(r) {
  const t = addTrack(S.song, 'sample', r.id, r.name, r.color);
  if (!t) return toast(`Up to ${MAX_TRACKS} tracks`);
  S.selectedTrack = t.id;
  changed('song');
  toast(`Added track “${r.name}” to the Beat deck`);
}

export function renderSampler(root) {
  const r = rec();
  const samples = [...S.samples.values()].sort((a, b) => b.created - a.created);
  const picker = h('label', { class: 'pick grow' }, 'Sample', h('select', { onchange: (e) => { S.selectedSample = e.target.value; renderSampler(root); } },
    h('option', { value: '' }, samples.length ? 'Choose a sample…' : 'Library is empty'),
    samples.map((s) => h('option', { value: s.id, selected: s.id === S.selectedSample || null }, s.name))));
  const head = h('div', { class: 'toolbar' }, picker,
    btn('Import', async () => { const files = await host.pick({ accept: ['audio/*', 'video/*'], multiple: true }); const added = await importAudioFiles(files); if (added[0]) { S.selectedSample = added[0].id; renderSampler(root); } }, { icon: 'import', cls: 'ghost small' }));
  if (!r) {
    root.replaceChildren(h('div', { class: 'deck sampler' }, head, h('div', { class: 'empty' }, icon('wave', 'big'),
      h('p', {}, 'Pick a sample from the library, import audio, or chop a video in the Video deck.'))));
    return;
  }
  const st = r.settings;
  wave = waveView({
    height: 140,
    markerMode: () => chopMode === 'manual',
    onSeek: (t) => audition(r, t, st.end),
    onSelect: (sel, done) => { st.start = sel.start; st.end = sel.end; info.textContent = summary(); if (done) touchSample(r); },
    onMarker: (t, remove) => {
      r.markers = remove ? r.markers.filter((m) => m !== t) : [...(r.markers || []), t].sort((a, b) => a - b);
      chop(r); refresh();
    },
  });
  const summary = () => `${fmtTime(st.start)} – ${fmtTime(st.end)} · ${(st.end - st.start).toFixed(3)} s · ${r.sampleRate / 1000} kHz`;
  const info = h('span', { class: 'muted small mono' }, summary());
  const k = (key, label, min, max, def, f, exp) => knob({ label, min, max, def, value: st[key], fmt: f, exp, onchange: (v) => { st[key] = v; touchSample(r); } }).el;
  const pads = h('div', { class: 'pads small' });
  function refresh() {
    wave.set({ selection: { start: st.start, end: st.end }, markers: r.markers || [], regions: (r.slices || []).map((s, i) => ({ ...s, color: COLORS[i % 8], label: String(i + 1) })) });
    pads.replaceChildren(...Array.from({ length: 16 }, (_, i) => {
      const sl = r.slices?.[i];
      if (!sl) return h('div', { class: 'pad empty' });
      const p = h('button', { type: 'button', class: 'pad', style: { '--tc': COLORS[i % 8] }, 'aria-label': `Slice ${i + 1}` }, h('span', { class: 'pad-n' }, String(i + 1)), h('span', { class: 'pad-l' }, `${(sl.end - sl.start).toFixed(2)}s`));
      p.addEventListener('pointerdown', (e) => { e.preventDefault(); audition(r, sl.start, sl.end); });
      onLongPress(p, () => saveSlices(r, i));
      return p;
    }));
  }
  const amountLabel = { transient: 'Sensitivity', equal: 'Slices', grid: 'Grid', manual: '' };
  root.replaceChildren(h('div', { class: 'deck sampler' },
    head,
    h('div', { class: 'sample-title' }, h('span', { class: 'swatch', style: { '--tc': r.color } }), h('strong', {}, r.name), info),
    wave.el,
    h('div', { class: 'toolbar wrap' },
      btn('Play', () => audition(r, st.start, st.end), { icon: 'play', cls: 'primary small' }),
      toggle('Reverse', !!st.reverse, (v) => { st.reverse = v; touchSample(r); }, 'reverse'),
      toggle('Loop', !!st.loop, (v) => { st.loop = v; touchSample(r); }, 'loop'),
      btn('Trim silence', () => { const sr = r.sampleRate, x = trimSilence(region(r.data, Math.round(st.start * sr), Math.round(st.end * sr)), -50); st.end = st.start + x.end / sr; st.start += x.start / sr; touchSample(r); refresh(); info.textContent = summary(); }, { cls: 'ghost small' }),
      btn('Normalize', async () => { r.data = normalize(r.data); await putSample(r); toast('Normalized to −0.3 dB'); renderSampler(root); }, { cls: 'ghost small' }),
      btn('Use on track', () => useOnTrack(r), { icon: 'plus', cls: 'ghost small' })),
    h('section', { class: 'panel' }, h('div', { class: 'knobs' },
      k('gain', 'Gain', 0, 2, 1, fmt.pct), k('pitch', 'Pitch', -24, 24, 0, fmt.st), k('attack', 'Attack', 0.001, 1, 0.002, fmt.secs, true), k('release', 'Release', 0.005, 2, 0.01, fmt.secs, true))),
    h('section', { class: 'panel' },
      h('div', { class: 'row-head' }, h('span', { class: 'eyebrow' }, 'Chop'), h('div', { class: 'spacer' }),
        r.slices?.length ? btn(`Save ${r.slices.length} to library`, () => saveSlices(r), { icon: 'save', cls: 'ghost small' }) : null),
      h('div', { class: 'toolbar wrap' },
        segmented([['transient', 'Transients'], ['equal', 'Equal'], ['grid', 'Beat grid'], ['manual', 'Markers']], chopMode, (v) => { chopMode = v; renderSampler(root); }, 'compact'),
        chopMode !== 'manual' ? knob({ label: amountLabel[chopMode], min: 0, max: 1, value: amount, fmt: (v) => (chopMode === 'equal' ? String(Math.round(2 + v * 14)) : chopMode === 'grid' ? ['1/16', '1/8', '1/4', '1 bar'][Math.round(v * 3)] : fmt.pct(v)), onchange: (v) => { amount = v; }, size: 'mini' }).el
          : h('span', { class: 'muted small' }, 'Tap the waveform to add or remove a cut.'),
        chopMode !== 'manual' ? btn('Chop', () => { chop(r); refresh(); }, { icon: 'scissors', cls: 'primary small' }) : null,
        r.slices?.length ? btn('Clear', () => { r.slices = []; r.markers = []; touchSample(r); refresh(); }, { cls: 'ghost small' }) : null),
      pads, h('p', { class: 'hint' }, 'Tap a slice pad to hear it. Hold (or right-click) a pad to save just that slice.'))));
  wave.setBuffer(r.buffer, 0);
  refresh();
}
