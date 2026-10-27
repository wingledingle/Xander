// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Library → On phone: the videos and audio files Xander's storage index already knows about (no second scan).
// Open a video in the Video deck to chop it, or add an audio file to the library.

import { h, btn, icon, segmented, toast } from '../ui.js';
import { importAudioFiles } from '../actions.js';
import { host } from '../../host/xander.js';
import { S } from '../state.js';

let kind = 'video', query = '';
const cache = new Map(); // kind → rows

const size = (n) => (n > 1e9 ? `${(n / 1e9).toFixed(1)} GB` : n > 1e6 ? `${(n / 1e6).toFixed(1)} MB` : `${Math.max(1, Math.round(n / 1e3))} KB`);

export function renderPhone(root, go) {
  if (!host.mediaIndex) { root.replaceChildren(h('p', { class: 'muted' }, 'Your phone\'s videos and audio are listed here inside the Xander app.')); return; }
  if (!cache.has(kind)) cache.set(kind, host.mediaIndex(kind));
  const rows = cache.get(kind);
  const list = h('div', { class: 'pack-list' });
  const draw = () => {
    const words = query.toLowerCase().split(/\s+/).filter(Boolean);
    const items = rows.filter((r) => words.every((w) => r.path.toLowerCase().includes(w)));
    list.replaceChildren(
      h('p', { class: 'muted small' }, `${items.length} ${kind === 'video' ? 'videos' : 'audio files'} on this phone`),
      ...items.slice(0, 200).map((r) => h('div', { class: 'pack-row' },
        h('span', { class: 'pack-play', 'aria-hidden': 'true' }, icon(kind === 'video' ? 'movie' : 'wave')),
        h('span', { class: 'li-main' }, h('strong', {}, r.name), h('small', {}, `${r.path.split('/').slice(-2, -1)[0] || ''} · ${size(r.size)}`)),
        kind === 'video'
          ? btn('Chop', () => go('video', { path: r.path }), { cls: 'ghost small' })
          : btn('Add', async () => {
            const f = await host.openPath(r.path);
            if (!f) return toast('Could not open that file');
            const [rec] = await importAudioFiles([f]);
            if (rec) { S.selectedSample = rec.id; toast(`“${r.name}” added to the library`); }
          }, { cls: 'ghost small' }))),
      items.length > 200 ? h('p', { class: 'muted small' }, 'Showing the 200 newest, search to narrow down.') : null);
  };
  const search = h('input', { class: 'field search', type: 'search', value: query, placeholder: kind === 'video' ? 'Search videos…' : 'Search audio…', 'aria-label': 'Search files on the phone', oninput: (e) => { query = e.target.value; draw(); } });
  root.replaceChildren(
    h('div', { class: 'toolbar' }, segmented([['video', 'Videos'], ['audio', 'Audio']], kind, (v) => { kind = v; renderPhone(root, go); })),
    h('div', { class: 'search-wrap' }, icon('search'), search), list);
  draw();
}
