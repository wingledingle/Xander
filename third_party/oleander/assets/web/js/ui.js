// Small UI helpers: element builder, dialogs, toasts, downloads.

export function el(tag, attrs = {}, ...children) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k === 'class') e.className = v;
    else if (k === 'style' && typeof v === 'object') {
      for (const [sk, sv] of Object.entries(v)) {
        if (sk.startsWith('--')) e.style.setProperty(sk, String(sv));
        else e.style[sk] = sv;
      }
    }
    else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
    else if (k === 'html') e.innerHTML = v;
    else e.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat()) {
    if (c === null || c === undefined || c === false) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

export function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

const dlg = () => document.getElementById('dialog');

/// Shows a modal with `content` (Node). Resolves with the dialog's returnValue
/// when it closes. `title` adds a header with a close button.
export function showDialog(title, content, { actions = [], onClose, wide = false } = {}) {
  const d = dlg();
  const body = document.getElementById('dialog-body');
  body.replaceChildren();
  if (title) body.append(el('div', { class: 'dlg-title' }, el('span', {}, title), el('button', { class: 'icon', value: 'close', 'aria-label': 'Close' }, '✕')));
  body.append(content);
  if (actions.length) {
    body.append(el('div', { class: 'dlg-actions' }, actions.map((a) => el('button', { value: a.value, class: a.class || '' }, a.label))));
  }
  d.style.width = wide ? 'min(1100px, 98vw)' : '';
  return new Promise((resolve) => {
    d.onclose = () => { onClose?.(); resolve(d.returnValue); };
    d.returnValue = '';
    if (!d.open) d.showModal();
  });
}

export function closeDialog(value = '') {
  const d = dlg();
  if (d.open) d.close(value);
}

export async function confirmDialog(message, okLabel = 'OK', danger = false) {
  const r = await showDialog('', el('p', {}, message), { actions: [{ value: 'cancel', label: 'Cancel' }, { value: 'ok', label: okLabel, class: danger ? 'danger' : 'primary' }] });
  return r === 'ok';
}

export async function promptDialog(message, value = '') {
  const input = el('input', { type: 'text', value, style: { width: '100%' } });
  const p = showDialog('', el('div', {}, el('p', {}, message), input), { actions: [{ value: 'cancel', label: 'Cancel' }, { value: 'ok', label: 'OK', class: 'primary' }] });
  setTimeout(() => input.focus(), 30);
  const r = await p;
  return r === 'ok' ? input.value : null;
}

export function toast(msg, kind = '') {
  const t = el('div', { class: 'toast ' + kind }, msg);
  document.getElementById('toasts').append(t);
  setTimeout(() => t.remove(), kind === 'error' ? 6000 : 2800);
}

/// Drops pending info toasts (e.g. library messages when a game starts).
export function clearToasts() {
  for (const t of document.querySelectorAll('#toasts .toast:not(.error)')) t.remove();
}

export function download(name, data, type = 'application/octet-stream') {
  const blob = data instanceof Blob ? data : new Blob([data], { type });
  const android = window.OleanderAndroid;
  if (android?.saveBegin) {
    // Android app: blob downloads are not supported by WebView; stream the
    // bytes to the native side in chunks (backups can be hundreds of MB),
    // which writes them to Downloads/Oleander.
    saveAndroid(android, name, blob, blob.type || type);
    return;
  }
  if (android?.saveFile) {
    const r = new FileReader();
    r.onload = () => android.saveFile(name, blob.type || type, String(r.result).split(',')[1] || '');
    r.readAsDataURL(blob);
    return;
  }
  const a = el('a', { href: URL.createObjectURL(blob), download: name });
  document.body.append(a);
  a.click();
  setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
}

async function saveAndroid(android, name, blob, type) {
  const CHUNK = 768 * 1024;
  const id = android.saveBegin(name, type);
  if (id < 0) return;
  try {
    for (let off = 0; off < blob.size; off += CHUNK) {
      const bytes = new Uint8Array(await blob.slice(off, off + CHUNK).arrayBuffer());
      let bin = '';
      for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      if (!android.saveChunk(id, btoa(bin))) return;
    }
    android.saveEnd(id, true);
  } catch (err) {
    android.saveEnd(id, false);
    toast('Export failed: ' + err.message, 'error');
  }
}

export function pickFile(accept = '*', multiple = false) {
  return new Promise((resolve) => {
    const i = el('input', { type: 'file', accept, multiple });
    i.onchange = () => resolve(multiple ? [...i.files] : i.files[0] || null);
    i.click();
  });
}

export function formatDuration(sec) {
  sec = Math.floor(sec || 0);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60);
  if (h) return `${h}h ${m}m`;
  if (m) return `${m}m`;
  return `${sec}s`;
}

export function formatDate(ms) {
  if (!ms) return 'never';
  return new Date(ms).toLocaleString();
}

/// Converts RGBA pixels (240x160) to a PNG blob via a canvas.
export async function pixelsToBlob(rgba, w = 240, h = 160, type = 'image/png') {
  const c = document.createElement('canvas');
  c.width = w; c.height = h;
  c.getContext('2d').putImageData(new ImageData(new Uint8ClampedArray(rgba), w, h), 0, 0);
  return new Promise((r) => c.toBlob(r, type, 0.85));
}
