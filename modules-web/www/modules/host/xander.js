// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Bridge between a web module and the Xander file manager. Every web module imports this one file.
// Inside the app it talks to the native `XanderHost` interface (WebModuleScreen.kt); in a plain browser
// it falls back to <input type=file> and downloads, so modules can be built and tested on a desktop.
//
// File objects: { url, name, mime, size, token?, path? } — `url` streams the file (with seeking).

const A = typeof window !== 'undefined' ? window.XanderHost : undefined;
const pending = new Map();
let seq = 0;
if (A) {
  window.__xanderHost = {
    done(id, json) { pending.get(id)?.res(json); pending.delete(id); },
    fail(id, msg) { pending.get(id)?.rej(new Error(msg)); pending.delete(id); },
  };
}
const call = (fn) => new Promise((res, rej) => { const id = ++seq; pending.set(id, { res, rej }); fn(id); });

function pickInBrowser(accept, multiple) {
  return new Promise((res) => {
    const input = Object.assign(document.createElement('input'), { type: 'file', accept: accept.join(','), multiple });
    input.addEventListener('change', () => res([...input.files].map((f) => ({ url: URL.createObjectURL(f), name: f.name, mime: f.type, size: f.size }))));
    input.addEventListener('cancel', () => res([]));
    input.click();
  });
}

function toBase64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export const host = {
  native: !!A,
  /** The file this module was opened with from the file manager, or null. */
  launch: A ? JSON.parse(A.launchFile() || 'null') : null,
  /** Lets the user choose files. accept: MIME patterns like ['audio/*', 'video/*']. */
  pick({ accept = ['*/*'], multiple = false } = {}) {
    return A ? call((id) => A.pick(id, JSON.stringify(accept), multiple)) : pickInBrowser(accept, multiple);
  },
  /** Decodes the audio track of an audio/video file to WAV on the device (any codec the phone supports).
   *  Returns { url, startSec, durationSec }. In a browser the original file URL is returned for decodeAudioData. */
  decodeAudio(file, { startSec = 0, maxSec = 300 } = {}) {
    if (!A || !file.token) return Promise.resolve({ url: file.url, startSec: 0 });
    return call((id) => A.decodeAudio(id, file.token, startSec * 1000, maxSec * 1000));
  },
  /** Saves a Blob into the user's storage (Music/<folder> by default). Returns { path } or { downloaded: true }. */
  async save(name, blob, { folder = 'Groovesmith' } = {}) {
    if (!A) {
      const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(blob), download: name });
      document.body.append(a); a.click(); a.remove();
      setTimeout(() => URL.revokeObjectURL(a.href), 10000);
      return { downloaded: true };
    }
    const sid = A.saveBegin(name, blob.type || 'application/octet-stream', folder);
    const CHUNK = 768 * 1024;
    for (let o = 0; o < blob.size; o += CHUNK) A.saveChunk(sid, toBase64(new Uint8Array(await blob.slice(o, o + CHUNK).arrayBuffer())));
    return call((id) => A.saveEnd(id, sid));
  },
  toast(msg) { if (A) A.toast(String(msg)); },
  keepScreenOn(on) { A?.keepScreenOn(!!on); },
  exit() { if (A) A.exit(); else history.back(); },
};
