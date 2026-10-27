// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Builds www/modules/studio/sounds/index.json from the bundled CC0 sound packs, so the Studio can list and
// search them without scanning assets. Run after adding or removing sounds:   node tools/sound-index.js

import { readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../www/modules/studio/sounds/', import.meta.url));
const PACKS = { 'impact-sounds': 'Impacts', 'interface-sounds': 'Interface', 'sci-fi-sounds': 'Sci-fi', 'digital-audio': 'Digital', 'rpg-audio': 'Foley', 'casino-audio': 'Casino' };

// "impactBell_heavy_001" → words ["impact", "bell", "heavy"] and number 1
export function describe(file) {
  const base = file.replace(/\.[^.]+$/, '');
  const num = /(\d+)$/.exec(base)?.[1];
  const words = base.replace(/\d+$/, '').replace(/([a-z])([A-Z])/g, '$1 $2').split(/[\s_\-]+/).map((w) => w.toLowerCase()).filter(Boolean);
  const name = words.map((w, i) => (i ? w : w[0].toUpperCase() + w.slice(1))).join(' ') + (num != null ? ` ${Number(num) + 1}` : '');
  return { name, tags: [...new Set(words)] };
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const out = [];
  for (const pack of Object.keys(PACKS)) {
    for (const file of readdirSync(ROOT + pack).filter((f) => f.endsWith('.ogg')).sort()) {
      const { name, tags } = describe(file);
      out.push({ p: pack, f: file, n: name, t: tags });
    }
  }
  writeFileSync(ROOT + 'index.json', JSON.stringify({ license: 'CC0-1.0 (Kenney, kenney.nl)', packs: PACKS, sounds: out }));
  console.log(`${out.length} sounds indexed`);
}
