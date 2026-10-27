// IndexedDB persistence: ROMs, game metadata, battery saves, save states,
// screenshots, cheats and settings. Everything stays on the device.

const DB_NAME = 'oleander';
const DB_VERSION = 1;
const STORES = {
  games: { keyPath: 'id' },        // metadata (title, code, playtime, favorite, cover, per-game settings)
  roms: {},                        // id -> Uint8Array
  saves: {},                       // id -> { data, updated }
  states: {},                      // `${id}:${slot}` -> { data, thumb: Blob, created }
  screenshots: { autoIncrement: true }, // { gameId, blob, created }
  cheats: {},                      // id -> [{ name, code, format, enabled }]
  kv: {},                          // settings, cheat database, directory handles ...
};

let dbp = null;

function open() {
  if (dbp) return dbp;
  dbp = new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, DB_VERSION);
    req.onupgradeneeded = () => {
      const db = req.result;
      for (const [name, opts] of Object.entries(STORES)) {
        if (!db.objectStoreNames.contains(name)) {
          const s = db.createObjectStore(name, opts);
          if (name === 'screenshots') s.createIndex('gameId', 'gameId');
        }
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
  if (typeof window !== 'undefined' && navigator.storage?.persist) navigator.storage.persist().catch(() => {});
  return dbp;
}

function wrap(req) {
  return new Promise((resolve, reject) => {
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

async function store(name, mode = 'readonly') {
  const db = await open();
  return db.transaction(name, mode).objectStore(name);
}

export const db = {
  async get(name, key) { return wrap((await store(name)).get(key)); },
  async put(name, value, key) { return wrap((await store(name, 'readwrite')).put(value, key)); },
  async del(name, key) { return wrap((await store(name, 'readwrite')).delete(key)); },
  async all(name) { return wrap((await store(name)).getAll()); },
  async keys(name) { return wrap((await store(name)).getAllKeys()); },
  async clear(name) { return wrap((await store(name, 'readwrite')).clear()); },
  /// Read-modify-write in a single transaction (avoids lost updates when the
  /// page and the import worker change the same record). `fn` gets the
  /// current value (or undefined) and returns the new one (or undefined to skip).
  async update(name, key, fn) {
    const db = await open();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(name, 'readwrite');
      const s = tx.objectStore(name);
      const req = s.get(key);
      let result;
      req.onsuccess = () => {
        result = fn(req.result);
        if (result !== undefined) {
          if (s.keyPath) s.put(result); else s.put(result, key);
        }
      };
      tx.oncomplete = () => resolve(result);
      tx.onerror = () => reject(tx.error);
    });
  },
  async byIndex(name, index, value) { return wrap((await store(name)).index(index).getAll(value)); },
  async entries(name) {
    const s = await store(name);
    const [keys, values] = await Promise.all([wrap(s.getAllKeys()), wrap(s.getAll())]);
    return keys.map((k, i) => [k, values[i]]);
  },
  stores: Object.keys(STORES),
};

// ---- convenience helpers ----

export async function getSetting(key, fallback) {
  const v = await db.get('kv', 'setting:' + key);
  return v === undefined ? fallback : v;
}

export async function setSetting(key, value) {
  return db.put('kv', value, 'setting:' + key);
}

export async function estimate() {
  if (!navigator.storage?.estimate) return null;
  return navigator.storage.estimate();
}

/// Stable game id: CRC32 of the ROM + size (computed in JS).
export function crc32(bytes) {
  let table = crc32.table;
  if (!table) {
    table = crc32.table = new Uint32Array(256);
    for (let i = 0; i < 256; i++) {
      let c = i;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xEDB88320 ^ (c >>> 1) : c >>> 1;
      table[i] = c >>> 0;
    }
  }
  let crc = 0xFFFFFFFF;
  for (let i = 0; i < bytes.length; i++) crc = table[(crc ^ bytes[i]) & 0xFF] ^ (crc >>> 8);
  return (crc ^ 0xFFFFFFFF) >>> 0;
}
