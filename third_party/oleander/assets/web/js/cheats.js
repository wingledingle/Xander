// Cheat management: per-game lists, add/edit, enable/disable, import of
// libretro .cht and Oleander .olc files, and a local cheat database.
import { db } from './storage.js';
import { el, showDialog, toast, pickFile, download } from './ui.js';
import { CHEAT_FORMATS } from './core.js';

export async function loadCheats(gameId) {
  return (await db.get('cheats', gameId)) || [];
}

async function store(gameId, list) {
  await db.put('cheats', list, gameId);
}

/// Applies the stored cheat list to a console. Returns error messages.
export function applyCheats(con, list) {
  con.clearCheats();
  const errors = [];
  list.forEach((c) => {
    try {
      const i = con.addCheat(c.name, c.code, c.format);
      con.enableCheat(i, c.enabled);
      c.detected = con.cheatFormat(i);
      c.error = null;
    } catch (e) {
      // Keep indices aligned with a disabled placeholder.
      try { const i = con.addCheat(c.name, '00000000:00', 'raw'); con.enableCheat(i, false); } catch { /* ignore */ }
      c.error = e.message;
      errors.push(`${c.name}: ${e.message}`);
    }
  });
  return errors;
}

export function parseCht(text) {
  const map = new Map();
  for (const line of text.split(/\r?\n/)) {
    const m = line.match(/^\s*cheat(\d+)_(desc|code|enable)\s*=\s*"?(.*?)"?\s*$/);
    if (!m) continue;
    const i = Number(m[1]);
    const e = map.get(i) || { name: `Cheat ${i}`, code: '', format: 'auto', enabled: false };
    if (m[2] === 'desc') e.name = m[3];
    if (m[2] === 'code') e.code = m[3].replace(/\+/g, '\n');
    if (m[2] === 'enable') e.enabled = m[3] === 'true';
    map.set(i, e);
  }
  return [...map.values()].filter((e) => e.code);
}

/// Parses an .olc database: returns { [gameCode]: { title, cheats: [] } }.
export function parseOlc(text) {
  const games = {};
  let game = null, cur = null, format = 'auto';
  const flush = () => { if (cur && game && cur.code.trim()) game.cheats.push({ ...cur, code: cur.code.trim() }); cur = null; };
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) continue;
    const g = line.match(/^\[([^\]]+)\]\s*(.*)$/);
    if (g) { flush(); game = games[g[1].trim().toUpperCase()] = { title: g[2], cheats: [] }; format = 'auto'; continue; }
    if (line.startsWith('format=')) { format = line.slice(7).trim(); if (cur) cur.format = format; continue; }
    if (line[0] === '+' || line[0] === '-') {
      flush();
      if (!game) game = games[''] = { title: '', cheats: [] };
      cur = { name: line.slice(1).trim(), code: '', format, enabled: line[0] === '+' };
      continue;
    }
    if (cur) cur.code += line + '\n';
  }
  flush();
  return games;
}

export function toOlc(code, title, list) {
  let out = `# Oleander cheats\n[${code}] ${title}\n`;
  for (const c of list) {
    out += `${c.enabled ? '+' : '-'}${c.name}\n`;
    if (c.format && c.format !== 'auto') out += `format=${c.format}\n`;
    out += c.code.trim() + '\n';
  }
  return out;
}

export async function cheatsDialog(con, game, onChange) {
  let list = await loadCheats(game.id);
  const body = el('div');
  const save = async () => {
    await store(game.id, list.map(({ name, code, format, enabled }) => ({ name, code, format, enabled })));
    const errs = applyCheats(con, list);
    if (errs.length) toast(errs[0], 'error');
    onChange?.();
    render();
  };

  const editor = (c = { name: '', code: '', format: 'auto', enabled: true }, index = -1) => {
    const name = el('input', { type: 'text', value: c.name, placeholder: 'Description', style: { width: '100%' } });
    const code = el('textarea', { placeholder: 'One code per line, e.g.\n82025BC4 270F\n02000000:FF\nXXXXXXXX YYYYYYYY' }, c.code);
    const fmt = el('select', {}, CHEAT_FORMATS.map((f) => el('option', { value: f, selected: f === c.format }, {
      auto: 'Auto-detect', raw: 'Raw (address:value)', gameshark: 'GameShark / Action Replay v1-v2',
      actionreplay: 'Action Replay v3 / GameShark SP', codebreaker: 'CodeBreaker', 'gameshark-raw': 'GameShark (decrypted)',
      'actionreplay-raw': 'Action Replay v3 (decrypted)',
    }[f])));
    return el('div', { class: 'section' },
      el('div', { class: 'row' }, name), code, el('div', { class: 'row' }, el('label', {}, 'Format'), fmt,
        el('button', { type: 'button', class: 'primary', onclick: async () => {
          const entry = { name: name.value || 'Cheat', code: code.value, format: fmt.value, enabled: true };
          try {
            const test = con.addCheat(entry.name, entry.code, entry.format);
            con.enableCheat(test, false);
          } catch (e) { return toast(e.message, 'error'); }
          if (index >= 0) list[index] = entry; else list.push(entry);
          await save();
        } }, index >= 0 ? 'Update' : 'Add cheat')));
  };

  const render = () => {
    body.replaceChildren();
    const items = el('div', { class: 'list' });
    list.forEach((c, i) => {
      const cb = el('input', { type: 'checkbox', checked: c.enabled });
      cb.onchange = async () => { c.enabled = cb.checked; await save(); };
      items.append(el('div', { class: 'item' }, cb,
        el('div', { class: 'grow' }, el('div', {}, c.name),
          el('div', { class: 'small mono' }, c.error ? `⚠ ${c.error}` : `${c.detected || c.format} · ${c.code.split('\n')[0]}${c.code.includes('\n') ? ' …' : ''}`)),
        el('button', { type: 'button', onclick: () => { body.querySelector('.editor')?.replaceChildren(editor(c, i)); } }, 'Edit'),
        el('button', { type: 'button', class: 'danger', onclick: async () => { list.splice(i, 1); await save(); } }, '✕')));
    });
    if (!list.length) items.append(el('p', { class: 'sub' }, 'No cheats yet.'));
    body.append(items, el('div', { class: 'editor' }, editor()));
    body.append(el('div', { class: 'dlg-actions' },
      el('button', { type: 'button', onclick: async () => {
        const f = await pickFile('.cht,.olc,.txt');
        if (!f) return;
        const text = await f.text();
        let found = [];
        if (/cheat\d+_code/.test(text)) found = parseCht(text);
        else {
          const g = parseOlc(text);
          found = (g[(game.code || '').toUpperCase()] || g[''] || Object.values(g)[0] || { cheats: [] }).cheats;
        }
        list.push(...found);
        await save();
        toast(`${found.length} cheat(s) imported`);
      } }, 'Import .cht / .olc'),
      el('button', { type: 'button', onclick: () => download(`${game.code || game.id}.olc`, toOlc(game.code, game.title, list), 'text/plain') }, 'Export'),
      el('button', { type: 'button', onclick: () => databaseDialog(game, async (picked) => { list.push(...picked); await save(); }) }, 'Cheat database…')));
  };
  applyCheats(con, list);
  render();
  return showDialog(`Cheats – ${game.title}`, body, { wide: true });
}

/// Local cheat database (an .olc file with many games imported by the user).
async function databaseDialog(game, onPick) {
  const dbText = await db.get('kv', 'cheatdb');
  const all = dbText ? parseOlc(dbText) : {};
  const entry = all[(game.code || '').toUpperCase()];
  const body = el('div');
  body.append(el('p', { class: 'sub' }, `Database: ${Object.keys(all).length} games. Import any .olc database file (e.g. exported from Oleander or converted from other tools).`));
  if (entry) {
    const items = el('div', { class: 'list' });
    for (const c of entry.cheats) {
      items.append(el('div', { class: 'item' }, el('div', { class: 'grow' }, c.name, el('div', { class: 'small mono' }, c.code.split('\n')[0])),
        el('button', { type: 'button', onclick: () => { onPick([{ ...c, enabled: false }]); toast(`Added "${c.name}"`); } }, 'Add')));
    }
    body.append(items);
  } else {
    body.append(el('p', {}, `No entries for ${game.code || 'this game'}.`));
  }
  body.append(el('div', { class: 'dlg-actions' },
    el('button', { type: 'button', onclick: async () => {
      const f = await pickFile('.olc,.txt');
      if (!f) return;
      const text = await f.text();
      const merged = (dbText || '') + '\n' + text;
      await db.put('kv', merged, 'cheatdb');
      toast(`Database updated (${Object.keys(parseOlc(merged)).length} games)`);
    } }, 'Import database…')));
  return showDialog('Cheat database', body);
}
