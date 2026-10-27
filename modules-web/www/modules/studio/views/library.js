// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Library deck: every sample with its label, tags, colour and source, searchable by words or #tags.

import { S, audio, touchSample, deleteSample, changed } from '../state.js';
import { addTrack, MAX_TRACKS, COLORS } from '../core.js';
import { searchSamples, allTags, normTags } from '../library.js';
import { encodeWav, fmtTime } from '../dsp.js';
import { playSample } from '../audio.js';
import { h, btn, iconBtn, icon, sheet, confirmSheet, toast } from '../ui.js';
import { importAudioFiles } from '../actions.js';
import { host } from '../../host/xander.js';

let query = '', tag = '', voice = null;

function preview(r) {
  const e = audio();
  voice?.stop(e.ctx.currentTime);
  voice = playSample(e.ctx, e.preview, r.buffer, e.ctx.currentTime + 0.005, r.settings);
}

function miniWave(r) {
  const c = h('canvas', { class: 'mini-wave', width: 160, height: 90 }), g = c.getContext('2d'), d = r.data[0];
  const a = Math.floor((r.settings?.start || 0) * r.sampleRate), b = Math.ceil((r.settings?.end || r.duration) * r.sampleRate);
  g.fillStyle = r.color;
  for (let x = 0; x < 160; x++) {
    let p = 0;
    for (let i = a + Math.floor((x / 160) * (b - a)), end = a + Math.floor(((x + 1) / 160) * (b - a)); i < end; i += 8) p = Math.max(p, Math.abs(d[i] || 0));
    g.fillRect(x, 45 - p * 42, 1, Math.max(1, p * 84));
  }
  return c;
}

function assign(r, go) {
  const t = addTrack(S.song, 'sample', r.id, r.name, r.color);
  if (!t) return toast(`Up to ${MAX_TRACKS} tracks`);
  S.selectedTrack = t.id;
  changed('song');
  toast(`Track “${r.name}” added to the Beat deck`);
  go?.('beat');
}

export function editSample(r, go) {
  const name = h('input', { class: 'field', value: r.name, 'aria-label': 'Label' });
  const tags = h('input', { class: 'field', value: r.tags.join(', '), placeholder: 'kick, vocal, 90bpm', 'aria-label': 'Tags' });
  let color = r.color;
  const save = () => { r.name = name.value.trim().slice(0, 60) || r.name; r.tags = normTags(tags.value); r.color = color; touchSample(r); };
  const src = r.source;
  const s = sheet('Sample', h('div', { class: 'form' },
    r.thumb ? h('img', { class: 'thumb big', src: r.thumb, alt: '' }) : miniWave(r),
    h('label', { class: 'field-label' }, 'Label', name),
    h('label', { class: 'field-label' }, 'Tags (comma separated)', tags),
    h('div', { class: 'colors' }, COLORS.map((c) => h('button', { type: 'button', class: `color${color === c ? ' on' : ''}`, style: { '--tc': c }, 'aria-label': c, onclick: (e) => { color = c; e.currentTarget.parentNode.querySelectorAll('.color').forEach((x) => x.classList.toggle('on', x === e.currentTarget)); } }))),
    h('p', { class: 'muted small' }, `${r.duration.toFixed(2)} s · ${r.sampleRate / 1000} kHz · ${r.data.length === 2 ? 'stereo' : 'mono'}`,
      src ? ` · from ${src.kind === 'video' ? 'video' : src.kind} “${src.name || '?'}”${src.t0 != null ? ` at ${fmtTime(src.t0)}` : ''}` : ''),
    h('div', { class: 'row wrap' },
      btn('Play', () => preview(r), { icon: 'play', cls: 'ghost small' }),
      btn('Add to Beat', () => { save(); s.close(); assign(r, go); }, { icon: 'plus', cls: 'ghost small' }),
      btn('Edit in Sampler', () => { save(); s.close(); S.selectedSample = r.id; go?.('sampler'); }, { icon: 'wave', cls: 'ghost small' }),
      btn('Export WAV', async () => { const res = await host.save(`${r.name.replace(/[^\w\- ]+/g, '').replace(/\s+/g, '-')}.wav`, new Blob([encodeWav(r.data, r.sampleRate)], { type: 'audio/wav' }), { folder: 'Groovesmith/Samples' }); toast(res.path ? `Saved to ${res.path}` : 'WAV downloaded'); }, { icon: 'save', cls: 'ghost small' }),
      src?.kind === 'video' && src.path && host.native ? btn('Open source video', async () => { s.close(); go?.('video', { path: src.path, at: src.t0 }); }, { icon: 'movie', cls: 'ghost small' }) : null)),
  { actions: [
    btn('Delete', async () => {
      const used = S.song.tracks.filter((t) => t.kind === 'sample' && t.ref === r.id).length;
      if (await confirmSheet('Delete sample?', used ? `“${r.name}” is used by ${used} track${used > 1 ? 's' : ''}. They will go silent.` : `“${r.name}” will be removed from the library.`)) { s.close(); deleteSample(r.id); }
    }, { cls: 'danger ghost', icon: 'trash' }),
    btn('Save', () => { save(); s.close(); }, { cls: 'primary' }),
  ] });
}

export function renderLibrary(root, go) {
  const all = [...S.samples.values()], tags = allTags(all), list = searchSamples(all, query, tag);
  const results = h('div', { class: 'sample-grid' });
  const draw = () => {
    const items = searchSamples(all, query, tag);
    results.replaceChildren(...(items.length ? items.map((r) => h('div', { class: 'sample-card', style: { '--tc': r.color } },
      h('button', { type: 'button', class: 'sc-art', onclick: () => preview(r), 'aria-label': `Play ${r.name}` }, r.thumb ? h('img', { src: r.thumb, alt: '' }) : miniWave(r), h('span', { class: 'sc-dur' }, `${(r.settings ? r.settings.end - r.settings.start : r.duration).toFixed(2)}s`), icon('play', 'sc-play')),
      h('div', { class: 'sc-body' },
        h('div', { class: 'sc-title' }, h('strong', { class: 'ellipsis' }, r.name),
          h('button', { type: 'button', class: `star${r.favorite ? ' on' : ''}`, 'aria-label': 'Favourite', 'aria-pressed': String(!!r.favorite), onclick: () => { r.favorite = !r.favorite; touchSample(r); } }, icon('star'))),
        h('div', { class: 'sc-tags' }, r.tags.slice(0, 4).map((t) => h('button', { type: 'button', class: 'tag', onclick: () => { tag = tag === t ? '' : t; renderLibrary(root, go); } }, `#${t}`))),
        h('div', { class: 'sc-actions' }, btn('Add to Beat', () => assign(r), { cls: 'ghost xsmall' }), iconBtn('more', 'Edit sample', () => editSample(r, go))))))
      : [h('div', { class: 'empty' }, icon('library', 'big'), h('p', {}, all.length ? 'No samples match.' : 'Your library is empty. Import audio or video, or save chops from the Video deck.'))]));
  };
  const search = h('input', { class: 'field search', type: 'search', value: query, placeholder: 'Search labels, tags, sources…', 'aria-label': 'Search samples', oninput: (e) => { query = e.target.value; draw(); } });
  root.replaceChildren(h('div', { class: 'deck library' },
    h('div', { class: 'toolbar' }, h('div', { class: 'search-wrap grow' }, icon('search'), search),
      btn('Import', async () => importAudioFiles(await host.pick({ accept: ['audio/*', 'video/*'], multiple: true })), { icon: 'import', cls: 'primary small' })),
    tags.length ? h('div', { class: 'chips scroll' }, h('button', { type: 'button', class: `chip${!tag ? ' on' : ''}`, onclick: () => { tag = ''; renderLibrary(root, go); } }, `All ${all.length}`),
      tags.slice(0, 30).map((t) => h('button', { type: 'button', class: `chip${tag === t ? ' on' : ''}`, onclick: () => { tag = tag === t ? '' : t; renderLibrary(root, go); } }, `#${t}`))) : null,
    h('p', { class: 'muted small' }, `${list.length} of ${all.length} samples`),
    results));
  draw();
}
