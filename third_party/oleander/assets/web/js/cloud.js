// Cloud save synchronisation over WebDAV (Nextcloud, ownCloud, Synology,
// any WebDAV/HTTP server that supports PUT/GET with CORS).
//
// Layout on the server:  <url>/oleander/saves/<gameId>.sav
//                        <url>/oleander/index.json  { gameId: { updated, title } }
// The newer copy (by modification time) wins; conflicts keep both by
// writing the older local copy to <gameId>.<time>.bak.sav.
import { db } from './storage.js';
import { settings } from './settings.js';
import { el, toast } from './ui.js';

function base() {
  const u = settings.global.cloudUrl.trim().replace(/\/$/, '');
  if (!u) throw new Error('No cloud URL configured');
  return u + '/oleander';
}

function headers(extra = {}) {
  const h = { ...extra };
  const user = settings.global.cloudUser, pass = settings.global.cloudPass;
  if (user) h.Authorization = 'Basic ' + btoa(unescape(encodeURIComponent(`${user}:${pass}`)));
  else if (pass) h.Authorization = 'Bearer ' + pass;
  return h;
}

async function req(method, path, body, type) {
  const r = await fetch(base() + path, { method, body, headers: headers(type ? { 'Content-Type': type } : {}) });
  if (!r.ok && !(method === 'MKCOL' && (r.status === 405 || r.status === 409)) && !(method === 'GET' && r.status === 404)) {
    throw new Error(`${method} ${path}: HTTP ${r.status}`);
  }
  return r;
}

async function ensureDirs() {
  await req('MKCOL', '').catch(() => {});
  await req('MKCOL', '/saves').catch(() => {});
}

export async function syncNow({ quiet = false } = {}) {
  try {
    await ensureDirs();
    const r = await req('GET', '/index.json');
    const remote = r.status === 404 ? {} : await r.json();
    const games = await db.all('games');
    let up = 0, down = 0;
    for (const g of games) {
      const local = await db.get('saves', g.id);
      const rem = remote[g.id];
      if (local && (!rem || local.updated > rem.updated + 1000)) {
        await req('PUT', `/saves/${g.id}.sav`, local.data, 'application/octet-stream');
        remote[g.id] = { updated: local.updated, title: g.title };
        up++;
      } else if (rem && (!local || rem.updated > local.updated + 1000)) {
        const s = await req('GET', `/saves/${g.id}.sav`);
        if (s.ok) {
          const data = new Uint8Array(await s.arrayBuffer());
          if (local) await req('PUT', `/saves/${g.id}.${local.updated}.bak.sav`, local.data, 'application/octet-stream');
          await db.put('saves', { data, updated: rem.updated }, g.id);
          down++;
        }
      }
    }
    await req('PUT', '/index.json', JSON.stringify(remote), 'application/json');
    await db.put('kv', Date.now(), 'cloudLastSync');
    if (!quiet || up || down) toast(`Cloud sync: ${up} uploaded, ${down} downloaded`);
    return { up, down };
  } catch (e) {
    if (!quiet) toast(`Cloud sync failed: ${e.message}`, 'error');
    throw e;
  }
}

export function cloudSettings(pane) {
  const url = el('input', { type: 'url', value: settings.global.cloudUrl, placeholder: 'https://cloud.example.com/remote.php/dav/files/me', style: { width: '100%' } });
  const user = el('input', { type: 'text', value: settings.global.cloudUser, placeholder: 'user (blank for bearer token)' });
  const pass = el('input', { type: 'password', value: settings.global.cloudPass, placeholder: 'password / app token' });
  const auto = el('input', { type: 'checkbox', checked: settings.global.cloudAuto });
  const last = el('span', { class: 'sub' }, '');
  db.get('kv', 'cloudLastSync').then((t) => { last.textContent = t ? `Last sync: ${new Date(t).toLocaleString()}` : 'Never synced'; });
  const saveAll = async () => {
    await settings.set('cloudUrl', url.value.trim());
    await settings.set('cloudUser', user.value);
    await settings.set('cloudPass', pass.value);
    await settings.set('cloudAuto', auto.checked);
  };
  pane.append(el('div', { class: 'section' }, el('h3', {}, 'Cloud save sync (WebDAV)'),
    el('p', { class: 'sub' }, 'Synchronises battery saves with your own WebDAV storage. The server must allow cross-origin requests from this site. Credentials are stored only on this device.'),
    el('div', { class: 'row' }, url),
    el('div', { class: 'row' }, user, pass),
    el('div', { class: 'row' }, el('label', {}, auto, ' Sync automatically after saving')),
    el('div', { class: 'row' },
      el('button', { type: 'button', onclick: saveAll }, 'Save'),
      el('button', { type: 'button', class: 'primary', onclick: async () => { await saveAll(); await syncNow().catch(() => {}); } }, 'Sync now'),
      last)));
}

let pending = null;
/// Debounced automatic sync after a battery save.
export function scheduleAutoSync() {
  if (!settings.global.cloudAuto || !settings.global.cloudUrl || !navigator.onLine) return;
  clearTimeout(pending);
  pending = setTimeout(() => syncNow({ quiet: true }).catch(() => {}), 10000);
}
