// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Bundled sound packs: 473 CC0 (public domain) sound effects by Kenney. Browse, search, preview, and add any of
// them to the library or straight onto a Beat track.

import { S, audio, putSample, bufferToRecord, changed } from '../state.js';
import { addTrack, MAX_TRACKS, COLORS } from '../core.js';
import { trimSilence } from '../dsp.js';
import { uniqueName } from '../library.js';
import { playSample } from '../audio.js';
import { h, btn, iconBtn, icon, toast } from '../ui.js';

let index = null, query = '', pack = '', voice = null;
const buffers = new Map(); // `${pack}/${file}` → AudioBuffer

async function loadIndex() {
  index ??= fetch('sounds/index.json').then((r) => r.json());
  return index;
}
async function decode(s) {
  const key = `${s.p}/${s.f}`;
  if (!buffers.has(key)) buffers.set(key, fetch(`sounds/${key}`).then((r) => r.arrayBuffer()).then((b) => audio().ctx.decodeAudioData(b)));
  return buffers.get(key);
}
async function preview(s) {
  const buf = await decode(s), e = audio();
  voice?.stop(e.ctx.currentTime);
  voice = playSample(e.ctx, e.preview, buf, e.ctx.currentTime + 0.005, {});
}

export async function addPackSound(s, packs, { toBeat = false } = {}) {
  const buf = await decode(s);
  const existing = [...S.samples.values()].find((x) => x.source?.kind === 'pack' && x.source.name === `${s.p}/${s.f}`);
  const rec = existing || await putSample((() => {
    const r = bufferToRecord(buf, {
      name: uniqueName(s.n, [...S.samples.values()].map((x) => x.name)),
      tags: [packs[s.p].toLowerCase(), ...s.t, 'cc0'].filter((t, i, a) => a.indexOf(t) === i),
      color: COLORS[Object.keys(packs).indexOf(s.p) % COLORS.length],
      source: { kind: 'pack', name: `${s.p}/${s.f}` },
    });
    const { start, end } = trimSilence(r.data, -60);
    r.settings = { start: start / buf.sampleRate, end: Math.max(start + 64, end) / buf.sampleRate, gain: 1, pitch: 0, attack: 0.002, release: 0.01, reverse: false, loop: false };
    return r;
  })());
  if (!toBeat) return toast(existing ? `“${rec.name}” is already in your library` : `“${rec.name}” added to your library`);
  const t = addTrack(S.song, 'sample', rec.id, rec.name, rec.color);
  if (!t) return toast(`Up to ${MAX_TRACKS} tracks`);
  S.selectedTrack = t.id;
  changed('song');
  toast(`Track “${rec.name}” added to the Beat deck`);
}

export function renderPacks(root) {
  const list = h('div', { class: 'pack-list' }, h('p', { class: 'muted' }, 'Loading sounds…'));
  const draw = (data) => {
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const items = data.sounds.filter((s) => (!pack || s.p === pack) && words.every((w) => s.n.toLowerCase().includes(w) || s.t.includes(w.replace(/^#/, ''))));
    list.replaceChildren(
      h('p', { class: 'muted small' }, `${items.length} of ${data.sounds.length} sounds · CC0 public domain, by Kenney (kenney.nl)`),
      ...items.slice(0, 200).map((s) => h('div', { class: 'pack-row', style: { '--tc': COLORS[Object.keys(data.packs).indexOf(s.p) % COLORS.length] } },
        h('button', { type: 'button', class: 'pack-play', 'aria-label': `Play ${s.n}`, onclick: () => preview(s).catch((e) => toast(e.message)) }, icon('play')),
        h('span', { class: 'li-main' }, h('strong', {}, s.n), h('small', {}, `${data.packs[s.p]} · ${s.t.map((t) => `#${t}`).join(' ')}`)),
        iconBtn('library', 'Add to library', () => addPackSound(s, data.packs).catch((e) => toast(e.message))),
        iconBtn('plus', 'Add to Beat as a track', () => addPackSound(s, data.packs, { toBeat: true }).catch((e) => toast(e.message))))),
      ...(items.length > 200 ? [h('p', { class: 'muted small' }, 'Showing the first 200, search to narrow down.')] : []));
  };
  const search = h('input', { class: 'field search', type: 'search', value: query, placeholder: 'Search sound effects…', 'aria-label': 'Search sound packs', oninput: (e) => { query = e.target.value; loadIndex().then(draw); } });
  const chips = h('div', { class: 'chips scroll' });
  root.replaceChildren(h('div', { class: 'search-wrap' }, icon('search'), search), chips, list);
  loadIndex().then((data) => {
    const drawChips = () => chips.replaceChildren(
      h('button', { type: 'button', class: `chip${!pack ? ' on' : ''}`, onclick: () => { pack = ''; drawChips(); draw(data); } }, 'All packs'),
      ...Object.entries(data.packs).map(([id, label]) => h('button', { type: 'button', class: `chip${pack === id ? ' on' : ''}`, onclick: () => { pack = id; drawChips(); draw(data); } }, label)));
    drawChips();
    draw(data);
  }).catch((e) => list.replaceChildren(h('p', { class: 'muted' }, `Sound packs unavailable: ${e.message}`)));
}
