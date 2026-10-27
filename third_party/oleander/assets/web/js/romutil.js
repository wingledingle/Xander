// ROM helpers shared by the page and the import worker.

export const ROM_EXT = /\.(gba|agb|bin|mb|zip|gz)$/i;

export function parseHeader(rom) {
  const txt = (a, b) => String.fromCharCode(...rom.slice(a, b)).replace(/\0.*$/s, '').replace(/[^\x20-\x7e]/g, '').trim();
  return { title: txt(0xA0, 0xAC), code: txt(0xAC, 0xB0), maker: txt(0xB0, 0xB2) };
}

export function prettyName(file) {
  return file.replace(/^.*[\\/]/, '').replace(/\.(gba|agb|bin|mb|zip|gz)$/i, '').replace(/[_]+/g, ' ').trim();
}

/// Identity of a file on disk, used to skip files that were already imported
/// without reading them again.
export function fileKey(it) {
  const size = it.size ?? it.file?.size;
  const mtime = it.lastModified ?? it.file?.lastModified;
  if (size === undefined) return null;
  return `${it.source || ''}|${it.name}|${size}|${mtime || 0}`;
}

/// Parses a clrmamepro DAT into { byCrc: {CRC: [name, serial]}, bySerial: {SERIAL: name} }.
export function parseDat(text) {
  const byCrc = {}, bySerial = {};
  for (const block of text.split(/\n(?=game \()/)) {
    if (!block.startsWith('game (')) continue;
    const name = /name "([^"]*)"/.exec(block)?.[1];
    if (!name) continue;
    const crcs = [...block.matchAll(/\bcrc ([0-9A-Fa-f]{8})/g)].map((m) => m[1].toUpperCase());
    const serial = /serial "([^"]*)"/.exec(block)?.[1] || '';
    for (const crc of crcs) byCrc[crc] = [name, serial];
    // Prefer USA/Europe names for serial matches (first seen wins otherwise).
    if (serial && (!bySerial[serial] || (/USA|Europe/.test(name) && !/USA|Europe/.test(bySerial[serial])))) bySerial[serial] = name;
  }
  return { byCrc, bySerial };
}

/// libretro-thumbnails file names replace these characters with '_'.
export function thumbName(name) {
  return name.replace(/[&*/:`<>?\\|"]/g, '_');
}

export function cleanTitle(name) {
  // "Pokemon - Emerald Version (USA, Europe)" -> "Pokemon - Emerald Version"
  return name.replace(/\s*\([^)]*\)/g, '').replace(/\s*\[[^\]]*\]/g, '').trim();
}

