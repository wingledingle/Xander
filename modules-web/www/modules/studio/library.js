// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Sample library: every chop, import and bounce, with a label, tags and colour for quick recall.
// Stored in IndexedDB (audio as Float32Array per channel). Search and tag helpers are pure for tests.

const DB = 'groovesmith-studio', VER = 1;

export function normTags(input) {
  const list = Array.isArray(input) ? input : String(input || '').split(/[,#]/);
  return [...new Set(list.map((t) => String(t).trim().toLowerCase().replace(/\s+/g, '-')).filter(Boolean))].slice(0, 12);
}

// Matches every word of the query against name, tags and source; `#tag` words must match a tag exactly.
export function searchSamples(samples, query = '', tag = '') {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  return samples.filter((s) => {
    if (tag && !s.tags.includes(tag)) return false;
    const hay = `${s.name} ${s.tags.join(' ')} ${s.source?.name || ''}`.toLowerCase();
    return words.every((w) => (w.startsWith('#') ? s.tags.includes(w.slice(1)) : hay.includes(w)));
  }).sort((a, b) => (b.favorite - a.favorite) || (b.created - a.created));
}

export function allTags(samples) {
  const n = new Map();
  for (const s of samples) for (const t of s.tags) n.set(t, (n.get(t) || 0) + 1);
  return [...n].sort((a, b) => b[1] - a[1]).map(([t]) => t);
}

// Next free "Name 2", "Name 3"… so recalled labels stay unique.
export function uniqueName(name, taken) {
  const set = new Set(taken);
  if (!set.has(name)) return name;
  const base = name.replace(/\s+\d+$/, '');
  for (let i = 2; ; i++) if (!set.has(`${base} ${i}`)) return `${base} ${i}`;
}

let dbp = null;
function db() {
  dbp ??= new Promise((res, rej) => {
    const r = indexedDB.open(DB, VER);
    r.onupgradeneeded = () => {
      r.result.createObjectStore('samples', { keyPath: 'id' });
      r.result.createObjectStore('kv');
    };
    r.onsuccess = () => res(r.result);
    r.onerror = () => rej(r.error);
  });
  return dbp;
}
const tx = async (store, mode, fn) => {
  const d = await db();
  return new Promise((res, rej) => {
    const t = d.transaction(store, mode), req = fn(t.objectStore(store));
    t.oncomplete = () => res(req?.result);
    t.onerror = () => rej(t.error);
  });
};

export const library = {
  all: () => tx('samples', 'readonly', (s) => s.getAll()),
  put: (sample) => tx('samples', 'readwrite', (s) => s.put(sample)),
  delete: (id) => tx('samples', 'readwrite', (s) => s.delete(id)),
  get: (key) => tx('kv', 'readonly', (s) => s.get(key)),
  set: (key, value) => tx('kv', 'readwrite', (s) => s.put(value, key)),
};
