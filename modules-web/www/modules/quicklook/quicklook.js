// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Quick Look: a preview for files Xander has no viewer for (SVG, fonts, Markdown, HTML), a hex view of any
// file (read in pages with Range requests, so huge files are fine) and file info with a SHA-256 checksum.

import { host } from '../host/xander.js';
import { marked } from './vendor/marked.esm.js';

const $ = (s) => document.querySelector(s);
const main = $('#main');
const h = (tag, attrs = {}, ...kids) => { const el = Object.assign(document.createElement(tag), attrs); el.append(...kids.flat().filter((k) => k != null)); return el; };
let file = null, tab = 'preview', hexAt = 0;
const PAGE = 4096;

const extOf = (n) => (n.includes('.') ? n.split('.').pop().toLowerCase() : '');
const KIND = { svg: 'svg', ttf: 'font', otf: 'font', woff: 'font', woff2: 'font', md: 'markdown', markdown: 'markdown', html: 'html', htm: 'html', xhtml: 'html' };
const fmtSize = (n) => (n == null ? '?' : n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : n < 1073741824 ? `${(n / 1048576).toFixed(1)} MB` : `${(n / 1073741824).toFixed(2)} GB`);

async function bytes(from, to) {
  const r = await fetch(file.url, { headers: { Range: `bytes=${from}-${to - 1}` } });
  const b = new Uint8Array(await r.arrayBuffer());
  return r.status === 206 ? b : b.subarray(from, to);
}
const whole = async () => (await fetch(file.url)).arrayBuffer();
const text = async () => new TextDecoder().decode(await whole());
const doc = (html) => h('iframe', { className: 'doc', sandbox: '', srcdoc: html, title: file.name });

// ---- Preview ------------------------------------------------------------------------------------------
async function preview() {
  const kind = KIND[extOf(file.name)];
  if (kind === 'svg') return h('div', { className: 'center checker' }, h('img', { src: URL.createObjectURL(new Blob([await whole()], { type: 'image/svg+xml' })), alt: file.name }));
  if (kind === 'markdown') return doc(`<meta name=viewport content="width=device-width"><style>body{font:16px/1.6 system-ui,sans-serif;max-width:46em;margin:0 auto;padding:16px;color:#1b1d22}img{max-width:100%}pre{background:#f2f3f5;padding:12px;overflow:auto;border-radius:8px}code{background:#f2f3f5;padding:1px 4px;border-radius:4px}table{border-collapse:collapse}td,th{border:1px solid #ccd;padding:4px 8px}</style>${marked.parse(await text())}`);
  if (kind === 'html') return doc(await text());
  if (kind === 'font') return font();
  return h('div', { className: 'center muted' }, h('p', {}, `No preview for .${extOf(file.name) || 'this type'} files. The Hex and Info tabs work for every file.`));
}

async function font() {
  const face = new FontFace('QL', await whole());
  await face.load(); document.fonts.add(face);
  const sample = h('div', { className: 'sample', style: 'font-family: QL; font-size: 40px' }, 'The quick brown fox jumps over the lazy dog');
  const size = h('input', { type: 'range', min: 12, max: 120, value: 40, 'aria-label': 'Size', oninput: () => { sample.style.fontSize = `${size.value}px`; } });
  const edit = h('textarea', { placeholder: 'Type to try the font', oninput: () => { sample.textContent = edit.value || 'The quick brown fox jumps over the lazy dog'; } });
  const glyphs = [...'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789&@?!%€$'].map((c) => h('span', { style: 'font-family: QL' }, c));
  return h('div', { className: 'pad font' }, h('h2', { style: 'font-family: QL; font-size: 30px' }, file.name.replace(/\.[^.]+$/, '')), size, edit, sample,
    h('p', { style: 'font-family: QL; font-size: 18px' }, 'ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz 0123456789'), h('div', { className: 'glyphs' }, glyphs));
}

// ---- Hex ----------------------------------------------------------------------------------------------
async function hex() {
  const size = file.size ?? Infinity;
  hexAt = Math.max(0, Math.min(hexAt, Number.isFinite(size) ? Math.max(0, size - 1) : hexAt));
  const b = await bytes(hexAt, Number.isFinite(size) ? Math.min(size, hexAt + PAGE) : hexAt + PAGE);
  const lines = [];
  for (let i = 0; i < b.length; i += 16) {
    const row = b.subarray(i, i + 16);
    const hx = [...row].map((x) => x.toString(16).padStart(2, '0')).join(' ').padEnd(47);
    const asc = [...row].map((x) => (x >= 32 && x < 127 ? String.fromCharCode(x) : '·')).join('');
    lines.push(h('div', {}, h('span', { className: 'o' }, (hexAt + i).toString(16).padStart(8, '0')), `  ${hx}  `, h('span', { className: 'a' }, asc)));
  }
  const go = h('input', { inputMode: 'text', placeholder: 'Go to offset (hex, e.g. 1f40)', 'aria-label': 'Offset',
    onkeydown: (e) => { if (e.key === 'Enter') { const v = parseInt(go.value, 16); if (Number.isFinite(v)) { hexAt = v - (v % 16); show(); } } } });
  const prev = h('button', { disabled: hexAt === 0, onclick: () => { hexAt = Math.max(0, hexAt - PAGE); show(); } }, '◀');
  const next = h('button', { disabled: b.length < PAGE, onclick: () => { hexAt += PAGE; show(); } }, '▶');
  return h('div', {}, h('div', { className: 'hexbar' }, prev, go, next), h('div', { className: 'hex' }, lines.length ? lines : h('span', { className: 'muted' }, 'Empty file')));
}

// ---- Info ---------------------------------------------------------------------------------------------
const MAGIC = [[[0x89, 0x50, 0x4e, 0x47], 'PNG image'], [[0xff, 0xd8, 0xff], 'JPEG image'], [[0x47, 0x49, 0x46, 0x38], 'GIF image'], [[0x25, 0x50, 0x44, 0x46], 'PDF document'],
  [[0x50, 0x4b, 0x03, 0x04], 'ZIP archive (also APK, DOCX, EPUB, JAR…)'], [[0x52, 0x61, 0x72, 0x21], 'RAR archive'], [[0x37, 0x7a, 0xbc, 0xaf], '7-Zip archive'], [[0x1f, 0x8b], 'gzip'],
  [[0x7f, 0x45, 0x4c, 0x46], 'ELF executable / library'], [[0x4d, 0x5a], 'Windows executable'], [[0x49, 0x44, 0x33], 'MP3 audio'], [[0x66, 0x4c, 0x61, 0x43], 'FLAC audio'],
  [[0x4f, 0x67, 0x67, 0x53], 'Ogg media'], [[0x1a, 0x45, 0xdf, 0xa3], 'Matroska / WebM video'], [[0x67, 0x6c, 0x54, 0x46], 'glTF binary (GLB) model'], [[0x53, 0x51, 0x4c, 0x69], 'SQLite database'],
  [[0x00, 0x01, 0x00, 0x00], 'TrueType font'], [[0x4f, 0x54, 0x54, 0x4f], 'OpenType font'], [[0x77, 0x4f, 0x46, 0x32], 'WOFF2 font'], [[0x77, 0x4f, 0x46, 0x46], 'WOFF font']];
function sniff(b) {
  for (const [sig, name] of MAGIC) if (sig.every((x, i) => b[i] === x)) return name;
  if (b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70) return 'MP4 / MOV / HEIC (ISO media)';
  if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46) return 'RIFF (WAV, AVI or WebP)';
  return [...b.subarray(0, 512)].every((x) => x === 9 || x === 10 || x === 13 || x >= 32) ? 'Text' : 'Binary data';
}
async function info() {
  const head = await bytes(0, 512);
  const sha = h('dd', {}, 'calculating…');
  const dl = h('dl', { className: 'info' },
    h('dt', {}, 'Name'), h('dd', {}, file.name), h('dt', {}, 'Size'), h('dd', {}, `${fmtSize(file.size)}${file.size != null ? ` (${file.size.toLocaleString()} bytes)` : ''}`),
    h('dt', {}, 'Type'), h('dd', {}, sniff(head)), h('dt', {}, 'MIME'), h('dd', {}, file.mime || '?'),
    file.path ? [h('dt', {}, 'Path'), h('dd', {}, decodeURIComponent(file.path))] : null, h('dt', {}, 'SHA-256'), sha);
  if (!crypto.subtle) sha.textContent = 'unavailable';
  else if ((file.size ?? Infinity) > 64 * 1048576) sha.textContent = 'not calculated for files over 64 MB (it would need the whole file in memory)';
  else whole().then((b) => crypto.subtle.digest('SHA-256', b)).then((d) => { sha.textContent = [...new Uint8Array(d)].map((x) => x.toString(16).padStart(2, '0')).join(''); }).catch((e) => { sha.textContent = e.message; });
  return h('div', { className: 'pad' }, dl);
}

async function show() {
  document.querySelectorAll('.tabs button').forEach((b) => b.setAttribute('aria-selected', String(b.dataset.tab === tab)));
  if (!file) { main.replaceChildren(h('div', { className: 'center muted' }, 'Open a file with ⋯')); return; }
  try { main.replaceChildren(await ({ preview, hex, info })[tab]()); } catch (e) { main.replaceChildren(h('div', { className: 'center muted' }, `Couldn't show this file: ${e.message}`)); }
}

async function open(f) {
  file = f; hexAt = 0;
  if (f && f.size == null) { const r = await fetch(f.url, { method: 'HEAD' }).catch(() => null); const n = +r?.headers.get('content-length'); if (n) f.size = n; }
  $('#name').textContent = f?.name || 'Quick Look';
  $('#sub').textContent = f ? `${fmtSize(f.size)}${f.mime ? ` · ${f.mime}` : ''}` : '';
  tab = f && !KIND[extOf(f.name)] ? 'hex' : 'preview';
  show();
}

document.querySelectorAll('.tabs button').forEach((b) => { b.onclick = () => { tab = b.dataset.tab; show(); }; });
$('#back').onclick = () => host.exit();
$('#open').onclick = async () => { const [f] = await host.pick({ accept: ['*/*'] }); if (f) open(f); };
// In a desktop browser (tests): ?file=<url> opens that file.
const q = new URLSearchParams(location.search).get('file');
open(host.launch || (q ? { url: q, name: q.split('/').pop() } : null));
