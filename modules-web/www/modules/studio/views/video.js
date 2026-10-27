// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Video deck: watch a video, mark In/Out, chop it by hand or automatically, label and tag each chop, play
// chops from pads (picture and sound together), add video and audio effects, save chops to the library
// for the Beat deck, and export a chop as a video clip with its effects.

import { S, audio, putSample, bufferToRecord, emit } from '../state.js';
import { COLORS, uid, CHANNEL_PARAMS } from '../core.js';
import { detectOnsets, equalSlices, gridSlices, markersToSlices, snapToZero, mixDown, fmtTime } from '../dsp.js';
import { library, normTags, uniqueName } from '../library.js';
import { playSample, renderSample } from '../audio.js';
import { createVideoRenderer, VIDEO_FX, VIDEO_PRESETS, FX_KEYS, defaultVideoFx, normalizeVideoFx, needsShader, cssFilter, SHADER_KEYS } from '../videofx.js';
import { h, btn, iconBtn, icon, knob, segmented, toast, prompt, sheet, confirmSheet, onLongPress, fmt } from '../ui.js';
import { waveView } from './wave.js';
import { decodeFile } from '../actions.js';
import { host } from '../../host/xander.js';
import { nativeVideo } from '../nativevideo.js';

const AFX_DEFAULT = { vol: 1, pitch: 0, reverse: false, cutoff: 1, reso: 0.7, drive: 0, crush: 0, low: 0, mid: 0, high: 0, delay: 0, reverb: 0 };
const AUDIO_WINDOW = 60; // seconds of mono audio held at once; it follows the playhead

const V = {
  file: null, chops: [], fx: defaultVideoFx(), afx: { ...AFX_DEFAULT }, inPt: null, outPt: null, selected: null, tab: 'chops',
  buffer: null, audioStart: 0, decoding: false, autoMode: 'transient', autoAmount: 0.5, stopAt: null, padVoice: null,
};
// In the app the phone's own video player draws the picture (see nativevideo.js); in a browser it's a <video>.
const NATIVE = !!window.XanderHost?.videoAvailable?.();
const video = NATIVE ? nativeVideo(window.XanderHost) : h('video', { class: 'video-el', playsinline: true, preload: 'auto' });
const thumbVideo = h('video', { class: 'src-video', muted: true, preload: 'metadata', playsinline: true });
const canvas = h('canvas', { class: 'video-canvas', width: 1280, height: 720 });
// The WebGL renderer is created only when a shader effect is turned on (see applyFx).
let renderer = null;
const getRenderer = () => (renderer ??= createVideoRenderer(canvas, video));
let wave = null, rootEl = null, timeEl = null, playBtn = null, recording = null;

const chopColor = (i) => COLORS[i % COLORS.length];
const session = () => ({ name: V.file?.name, path: V.file?.path || null, chops: V.chops, fx: V.fx, afx: V.afx, inPt: V.inPt, outPt: V.outPt });
let saveTimer = 0;
function persist() { clearTimeout(saveTimer); saveTimer = setTimeout(() => library.set('video-session', session()).catch(() => {}), 400); }

// ---- Loading -----------------------------------------------------------------------------------------

export async function openVideo(file) {
  V.file = file; V.buffer = null; V.audioStart = 0; V.selected = null;
  const prev = await library.get('video-session').catch(() => null);
  const same = prev && (prev.path ? prev.path === file.path : prev.name === file.name);
  V.chops = same ? prev.chops || [] : [];
  V.fx = normalizeVideoFx(same ? prev.fx : null);
  V.afx = { ...AFX_DEFAULT, ...(same ? prev.afx : {}) };
  V.inPt = same ? prev.inPt : null; V.outPt = same ? prev.outPt : null;
  video.src = file.url; thumbVideo.removeAttribute('src'); // the browser-only thumbnail fallback loads it on demand
  video.load();
  host.log(`video open: ${file.name} ${file.mime || '?'} ${file.size ?? '?'} bytes`);
  await new Promise((res, rej) => { video.onloadedmetadata = res; video.onerror = () => rej(new Error(`This video can't be played here (error ${video.error?.code ?? '?'})`)); });
  host.log(`video ready: ${video.videoWidth}x${video.videoHeight}, ${video.duration?.toFixed(1)} s`);
  if (NATIVE) video.style.setProperty('--ar', `${video.videoWidth || 16} / ${video.videoHeight || 9}`);
  canvas.width = Math.min(960, video.videoWidth || 960); canvas.height = Math.round(canvas.width * ((video.videoHeight || 540) / (video.videoWidth || 960)));
  applyFx();
  persist();
  if (rootEl) renderVideo(rootEl);
  setTimeout(() => loadAudio(0), 800); // after the video's own decoder has started
  if (same && V.chops.length) toast(`Restored ${V.chops.length} chops for ${file.name}`);
}

const afxParams = () => ({ ...Object.fromEntries(Object.entries(CHANNEL_PARAMS).map(([k, [, , d]]) => [k, d])), ...V.afx, pitch: 0, pan: 0, mute: false, solo: false });
const applyAfx = () => { if (S.engine) S.engine.aux('video', afxParams()); video.volume = Math.min(1, V.afx.vol); persist(); };

// Plain playback is the phone's own video player with its own sound: nothing is copied per frame and the sound is
// not rerouted (both of those froze some phones). Colour FX are a CSS filter; shader FX switch the WebGL canvas on.
function applyFx() {
  if (NATIVE) { canvas.hidden = true; video.setFx(V.fx); persist(); return; } // the native player runs every effect as one GPU shader
  const shader = needsShader(V.fx);
  video.style.filter = shader ? '' : cssFilter(V.fx);
  canvas.hidden = !shader;
  video.classList.toggle('offscreen', shader);
  if (shader) { const r = getRenderer(); r.set(V.fx); r.start(); } else renderer?.stop();
  persist();
}

let loading = null;
async function loadAudio(fromSec) {
  if (!V.file) return;
  if (loading) return loading;
  V.decoding = true; refreshStatus();
  loading = (async () => {
    try {
      const { buffer, startSec } = await decodeFile(V.file, { startSec: Math.max(0, fromSec), maxSec: AUDIO_WINDOW, channels: 1 });
      V.buffer = null; // let the old window go before holding the new one
      V.buffer = buffer; V.audioStart = startSec;
      wave?.setBuffer(buffer, startSec);
      refreshWave();
    } catch (e) {
      toast(`Could not extract audio: ${e.message}`, 5000);
    } finally { V.decoding = false; loading = null; refreshStatus(); }
  })();
  return loading;
}
const inWindow = (a, b) => V.buffer && a >= V.audioStart - 0.01 && b <= V.audioStart + V.buffer.duration + 0.01;
// Makes sure [a, b] is in the decoded window, moving the window if needed.
async function ensureAudio(a, b) {
  if (inWindow(a, b)) return true;
  if (b - a > AUDIO_WINDOW - 2) return false;
  await loadAudio(Math.max(0, a - 2));
  return inWindow(a, b);
}

// ---- Thumbnails (a second, hidden video seeks so the main one never jumps) ---------------------------

const thumbQueue = [];
let thumbBusy = false;
function queueThumb(chop) { thumbQueue.push(chop); if (!thumbBusy) nextThumb(); }
async function nextThumb() {
  const chop = thumbQueue.shift();
  if (!chop) { thumbBusy = false; return; }
  thumbBusy = true;
  try {
    const native = await host.thumbnail(V.file, chop.start + 0.05).catch(() => null);
    if (native) {
      chop.thumb = native;
      persist();
      document.querySelectorAll(`[data-chop="${chop.id}"]`).forEach((el) => {
        if (el.classList.contains('thumb-pad')) el.style.backgroundImage = `url(${chop.thumb})`;
        el.querySelector('.thumb')?.replaceWith(h('img', { class: 'thumb', src: chop.thumb, alt: '' }));
      });
      return nextThumb();
    }
    if (!thumbVideo.src) thumbVideo.src = V.file.url;
    if (thumbVideo.readyState < 1) await new Promise((r) => { thumbVideo.onloadedmetadata = r; setTimeout(r, 3000); });
    await new Promise((r) => { thumbVideo.onseeked = r; thumbVideo.currentTime = Math.min(chop.start + 0.05, (thumbVideo.duration || chop.start) - 0.01); setTimeout(r, 3000); });
    const c = h('canvas', { width: 160, height: 90 }), g = c.getContext('2d');
    g.drawImage(thumbVideo, 0, 0, 160, 90);
    chop.thumb = c.toDataURL('image/jpeg', 0.72);
    persist();
    document.querySelectorAll(`[data-chop="${chop.id}"]`).forEach((el) => {
      if (el.classList.contains('thumb-pad')) el.style.backgroundImage = `url(${chop.thumb})`;
      el.querySelector('.thumb')?.replaceWith(h('img', { class: 'thumb', src: chop.thumb, alt: '' }));
    });
  } catch (e) { console.warn('thumb', e); }
  nextThumb();
}

// ---- Chops -------------------------------------------------------------------------------------------

function addChop(start, end, extra = {}) {
  if (end - start < 0.02) return null;
  const base = (V.file?.name || 'Video').replace(/\.[^.]+$/, '');
  const name = uniqueName(`${base} ${V.chops.length + 1}`, V.chops.map((c) => c.name));
  const chop = { id: uid(), name, tags: normTags(['video']), color: chopColor(V.chops.length), start, end, thumb: null, ...extra };
  V.chops.push(chop);
  V.chops.sort((a, b) => a.start - b.start);
  queueThumb(chop);
  persist();
  return chop;
}

function autoChop() {
  if (!V.buffer) return toast('Audio is still loading');
  const sr = V.buffer.sampleRate, a = V.inPt ?? V.audioStart, b = V.outPt ?? V.audioStart + V.buffer.duration;
  const s = Math.max(0, Math.round((a - V.audioStart) * sr)), e = Math.min(V.buffer.length, Math.round((b - V.audioStart) * sr));
  const m = mixDown(Array.from({ length: V.buffer.numberOfChannels }, (_, i) => V.buffer.getChannelData(i)));
  let regions;
  if (V.autoMode === 'transient') regions = markersToSlices(detectOnsets(m.subarray(s, e), sr, { sensitivity: V.autoAmount, max: 32 }).map((i) => snapToZero(m, i + s)), s, e);
  else if (V.autoMode === 'equal') regions = equalSlices(s, e, Math.round(2 + V.autoAmount * 30));
  else regions = gridSlices(s, e, sr, S.song.bpm, [1, 2, 4, 8, 16][Math.round(V.autoAmount * 4)]);
  const made = regions.slice(0, 64).map((r) => addChop(V.audioStart + r.start / sr, V.audioStart + r.end / sr)).filter(Boolean);
  toast(`${made.length} chops added`);
  renderVideo(rootEl);
}

// Plays a chop: the picture seeks and plays, and its sound comes from the decoded audio (with audio FX)
// so it starts on time. Without decoded audio the video's own sound is used.
export function playChop(chop) {
  const e = audio(), t = e.ctx.currentTime + 0.01;
  V.padVoice?.stop(t);
  V.selected = chop.id;
  const rate = 2 ** (V.afx.pitch / 12);
  video.playbackRate = Math.min(4, Math.max(0.25, rate));
  video.currentTime = chop.start;
  V.stopAt = chop.end;
  if (V.buffer && chop.start >= V.audioStart && chop.end <= V.audioStart + V.buffer.duration + 0.01) {
    video.muted = true; // the decoded audio (with audio FX) plays instead, on time
    V.padVoice = playSample(e.ctx, e.aux('video', afxParams()), V.buffer, t, {
      start: chop.start - V.audioStart, end: chop.end - V.audioStart, pitch: V.afx.pitch, reverse: V.afx.reverse, gain: V.afx.vol, attack: 0.003, release: 0.01,
    });
  } else { video.muted = false; ensureAudio(chop.start, chop.end); }
  video.play().catch(() => {});
  markSelected();
}

async function saveChops(list) {
  if (!V.buffer && !V.decoding) return toast('No audio in this video');
  const names = [...S.samples.values()].map((s) => s.name);
  let n = 0;
  toast(`Saving ${list.length} chop${list.length > 1 ? 's' : ''}…`, 10000);
  for (const c of [...list].sort((a, b) => a.start - b.start)) {
    if (!(await ensureAudio(c.start, c.end))) continue;
    const out = await renderSample(OfflineAudioContext, V.buffer, { start: c.start - V.audioStart, end: c.end - V.audioStart, gain: V.afx.vol, attack: 0.002, release: 0.008 }, V.afx);
    const name = uniqueName(c.name, names);
    names.push(name);
    await putSample(bufferToRecord(out, {
      name, tags: c.tags, color: c.color, thumb: c.thumb,
      source: { kind: 'video', name: V.file?.name, path: V.file?.path || null, t0: c.start, t1: c.end, fx: V.fx },
    }));
    n++;
  }
  toast(n ? `${n} chop${n > 1 ? 's' : ''} saved to the library — add them on the Beat deck` : 'Nothing to save (load the audio around those chops first)', 4000);
}

async function exportClip(chop) {
  if (recording) return;
  if (NATIVE) {
    recording = true;
    toast('Exporting clip…', 60000);
    try {
      const r = await host.exportVideo(chop.name, chop.start, chop.end);
      toast(`Clip saved to ${r.path}`, 5000);
    } catch (e) { toast(`Export failed: ${e.message}`, 5000); } finally { recording = null; }
    return;
  }
  if (!window.MediaRecorder || !canvas.captureStream) return toast('Video export is not supported on this device');
  const e = audio(), dest = e.ctx.createMediaStreamDestination();
  e.analyser.connect(dest);
  // Shader FX record the WebGL canvas; otherwise frames are drawn (with the colour filter) to a 2D canvas, only
  // while this export runs.
  let src = canvas, stopDraw = () => {};
  if (!needsShader(V.fx)) {
    src = h('canvas', { width: canvas.width, height: canvas.height });
    const g = src.getContext('2d');
    let raf = 0;
    const draw = () => { g.filter = cssFilter(V.fx); if (video.readyState >= 2) g.drawImage(video, 0, 0, src.width, src.height); raf = requestAnimationFrame(draw); };
    draw();
    stopDraw = () => cancelAnimationFrame(raf);
  }
  const stream = new MediaStream([...src.captureStream(30).getVideoTracks(), ...dest.stream.getAudioTracks()]);
  const mime = ['video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'].find((m) => MediaRecorder.isTypeSupported(m)) || '';
  const parts = [];
  recording = new MediaRecorder(stream, mime ? { mimeType: mime, videoBitsPerSecond: 6e6 } : {});
  recording.ondataavailable = (ev) => ev.data.size && parts.push(ev.data);
  const done = new Promise((r) => { recording.onstop = r; });
  recording.start(250);
  toast('Recording clip…', 60000);
  playChop(chop);
  const len = (chop.end - chop.start) / Math.min(4, Math.max(0.25, 2 ** (V.afx.pitch / 12)));
  setTimeout(() => recording.stop(), len * 1000 + 250);
  await done;
  stopDraw();
  e.analyser.disconnect(dest);
  recording = null;
  const blob = new Blob(parts, { type: 'video/webm' });
  const r = await host.save(`${chop.name.replace(/[^\w\- ]+/g, '').replace(/\s+/g, '-')}.webm`, blob, { folder: 'Groovesmith/Clips' });
  toast(r.path ? `Clip saved to ${r.path}` : 'Clip downloaded');
}

function editChop(chop) {
  const tagsIn = h('input', { class: 'field', value: chop.tags.join(', '), placeholder: 'drums, vocal, intro', 'aria-label': 'Tags' });
  const nameIn = h('input', { class: 'field', value: chop.name, 'aria-label': 'Label' });
  const s = sheet('Chop', h('div', { class: 'form' },
    chop.thumb ? h('img', { class: 'thumb big', src: chop.thumb, alt: '' }) : null,
    h('label', { class: 'field-label' }, 'Label', nameIn),
    h('label', { class: 'field-label' }, 'Tags (comma separated)', tagsIn),
    h('p', { class: 'muted small mono' }, `${fmtTime(chop.start)} – ${fmtTime(chop.end)} · ${(chop.end - chop.start).toFixed(2)} s`),
    h('div', { class: 'colors' }, COLORS.map((c) => h('button', { type: 'button', class: `color${chop.color === c ? ' on' : ''}`, style: { '--tc': c }, 'aria-label': c, onclick: (e) => { chop.color = c; e.currentTarget.parentNode.querySelectorAll('.color').forEach((x) => x.classList.toggle('on', x === e.currentTarget)); } }))),
    h('div', { class: 'row wrap' },
      btn('Set In/Out to chop', () => { V.inPt = chop.start; V.outPt = chop.end; refreshWave(); }, { cls: 'ghost small' }),
      btn('Update from In/Out', () => { if (V.inPt != null && V.outPt > V.inPt) { chop.start = V.inPt; chop.end = V.outPt; queueThumb(chop); } }, { cls: 'ghost small' }),
      btn('Export clip', () => { s.close(); exportClip(chop); }, { icon: 'movie', cls: 'ghost small' }))),
  { actions: [
    btn('Delete', async () => { if (await confirmSheet('Delete chop?', `“${chop.name}” will be removed from this video.`)) { V.chops = V.chops.filter((c) => c !== chop); s.close(); persist(); renderVideo(rootEl); } }, { cls: 'danger ghost', icon: 'trash' }),
    btn('Save to library', () => { commit(); s.close(); saveChops([chop]); }, { cls: 'ghost' }),
    btn('Done', () => { commit(); s.close(); }, { cls: 'primary' }),
  ] });
  function commit() { chop.name = nameIn.value.trim().slice(0, 60) || chop.name; chop.tags = normTags(tagsIn.value); persist(); renderVideo(rootEl); }
}

// ---- Rendering ---------------------------------------------------------------------------------------

function refreshWave() {
  wave?.set({
    regions: V.chops.map((c) => ({ start: c.start, end: c.end, color: c.color, label: c.name, active: c.id === V.selected })),
    selection: V.inPt != null && V.outPt != null && V.outPt > V.inPt ? { start: V.inPt, end: V.outPt } : null,
    markers: [V.inPt, V.outPt].filter((x) => x != null && !(V.outPt > V.inPt)),
    cursor: video.currentTime,
  });
}
function refreshStatus() {
  const el = document.querySelector('#video-audio-status');
  if (!el) return;
  const dur = video.duration || 0;
  el.textContent = V.decoding ? 'Extracting audio…' : V.buffer
    ? `Audio ${fmtTime(V.audioStart)}–${fmtTime(V.audioStart + V.buffer.duration)}${dur > V.buffer.duration + 1 ? ` of ${fmtTime(dur)}` : ''}`
    : 'No audio';
  document.querySelector('#video-load-here')?.toggleAttribute('hidden', !(V.buffer && dur > V.buffer.duration + 1));
}
function markSelected() {
  document.querySelectorAll('[data-chop]').forEach((el) => el.classList.toggle('selected', el.dataset.chop === V.selected));
  refreshWave();
}

function chopList() {
  if (!V.chops.length) return h('p', { class: 'muted pad-text' }, 'No chops yet. Mark In and Out and tap Add chop, or use Auto-chop.');
  return h('div', { class: 'chop-list' }, V.chops.map((c) => {
    const row = h('div', { class: `chop${c.id === V.selected ? ' selected' : ''}`, 'data-chop': c.id, style: { '--tc': c.color } },
      h('button', { type: 'button', class: 'chop-main', onclick: () => playChop(c) },
        c.thumb ? h('img', { class: 'thumb', src: c.thumb, alt: '' }) : h('span', { class: 'thumb ph' }, icon('movie')),
        h('span', { class: 'li-main' }, h('strong', {}, c.name), h('small', {}, `${fmtTime(c.start)} · ${(c.end - c.start).toFixed(2)}s ${c.tags.map((t) => `#${t}`).join(' ')}`))),
      iconBtn('more', 'Edit chop', () => editChop(c)));
    return row;
  }));
}

function chopPads() {
  const list = V.chops.slice(0, 16);
  return h('div', { class: 'pads' }, Array.from({ length: 16 }, (_, i) => {
    const c = list[i];
    if (!c) return h('div', { class: 'pad empty' });
    const p = h('button', { type: 'button', class: `pad thumb-pad${c.id === V.selected ? ' selected' : ''}`, 'data-chop': c.id, style: { '--tc': c.color, backgroundImage: c.thumb ? `url(${c.thumb})` : null }, 'aria-label': c.name },
      h('span', { class: 'pad-n' }, String(i + 1)), h('span', { class: 'pad-l' }, c.name));
    p.addEventListener('pointerdown', (e) => { e.preventDefault(); playChop(c); p.classList.remove('hit'); void p.offsetWidth; p.classList.add('hit'); });
    onLongPress(p, () => editChop(c));
    return p;
  }));
}

function videoFxPanel() {
  const knobs = FX_KEYS.map((k) => {
    const [min, max, def, label] = VIDEO_FX[k];
    return knob({ label, min, max, def, value: V.fx[k], fmt: k === 'hue' ? (v) => `${Math.round(v)}°` : k === 'zoom' ? (v) => `${v.toFixed(2)}×` : fmt.pct, onchange: (v) => { V.fx[k] = v; applyFx(); } }).el;
  });
  return h('div', {},
    h('div', { class: 'chips' }, Object.entries(VIDEO_PRESETS).map(([name, p]) => btn(name, () => { V.fx = normalizeVideoFx({ ...defaultVideoFx(), ...p }); applyFx(); renderVideo(rootEl); }, { cls: 'chip' }))),
    h('div', { class: 'knobs' }, knobs),
    h('p', { class: 'hint' }, `Bright, Contrast, Sat, Hue and Invert are light on the phone. ${SHADER_KEYS.map((k) => VIDEO_FX[k][3]).join(', ')} use the GPU and switch on only while turned up.`));
}

function audioFxPanel() {
  const P = { vol: [0, 2, 1], pitch: [-24, 24, 0], ...CHANNEL_PARAMS };
  const k = (key, label, f) => knob({ label, min: P[key][0], max: P[key][1], def: P[key][2], value: V.afx[key], fmt: f, onchange: (v) => { V.afx[key] = v; applyAfx(); } }).el;
  return h('div', {},
    h('div', { class: 'row' }, h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: V.afx.reverse || null, onchange: (e) => { V.afx.reverse = e.target.checked; persist(); } }), 'Reverse chops'),
      btn('Reset', () => { V.afx = { ...AFX_DEFAULT }; applyAfx(); renderVideo(rootEl); }, { cls: 'ghost small' })),
    h('div', { class: 'knobs' }, k('vol', 'Level', fmt.pct), k('pitch', 'Pitch', fmt.st), k('cutoff', 'Filter', fmt.pct), k('reso', 'Reso', (v) => v.toFixed(1)),
      k('drive', 'Drive', fmt.pct), k('crush', 'Crush', fmt.pct), k('low', 'Low', fmt.db), k('mid', 'Mid', fmt.db), k('high', 'High', fmt.db), k('delay', 'Delay', fmt.pct), k('reverb', 'Reverb', fmt.pct)),
    h('p', { class: 'hint' }, 'Pitch also changes the video speed on pads. Pitch and Reverse apply to pads and saved chops; the other effects also apply while the video plays.'));
}

export function renderVideo(root) {
  rootEl = root;
  const pick = async () => { const [f] = await host.pick({ accept: ['video/*'] }); if (f) openVideo(f).catch((e) => toast(e.message, 5000)); };
  if (!V.file) {
    root.replaceChildren(h('div', { class: 'deck video' }, h('div', { class: 'empty' }, icon('movie', 'big'),
      h('h2', {}, 'Sample a video'), h('p', {}, 'Open a video, chop it into labelled samples, add video and audio effects, and play the chops in the Beat deck.'),
      btn('Open video', pick, { icon: 'import', cls: 'primary' }))));
    return;
  }
  wave = waveView({
    height: 110,
    onSeek: (t) => { video.currentTime = t; V.stopAt = null; refreshWave(); },
    onSelect: (sel) => { V.inPt = sel.start; V.outPt = sel.end; persist(); },
  });
  if (V.buffer) wave.setBuffer(V.buffer, V.audioStart);
  timeEl = h('span', { class: 'lcd' }, fmtTime(video.currentTime));
  playBtn = iconBtn(video.paused ? 'play' : 'pause', 'Play or pause', () => { if (video.paused) { V.stopAt = null; V.padVoice?.stop(S.ctx?.currentTime ?? 0); video.muted = false; video.playbackRate = 1; video.play(); } else video.pause(); }, 'big');
  const step = (d) => { video.pause(); video.currentTime = Math.max(0, video.currentTime + d); refreshWave(); };
  const tabs = { chops: chopList, pads: chopPads, video: videoFxPanel, audio: audioFxPanel };
  root.replaceChildren(h('div', { class: 'deck video' },
    h('div', { class: 'toolbar' }, h('strong', { class: 'grow ellipsis' }, V.file.name), btn('Open', pick, { icon: 'import', cls: 'ghost small' })),
    h('div', { class: 'video-stage' }, canvas, video, thumbVideo),
    h('div', { class: 'toolbar transport-v' },
      iconBtn('frame-back', 'Back one frame', () => step(-1 / 30)), playBtn, iconBtn('frame-fwd', 'Forward one frame', () => step(1 / 30)), timeEl,
      h('div', { class: 'spacer' }),
      btn('In', () => { V.inPt = video.currentTime; if (V.outPt != null && V.outPt <= V.inPt) V.outPt = null; refreshWave(); persist(); }, { cls: 'ghost small mark' }),
      btn('Out', () => { V.outPt = video.currentTime; if (V.inPt != null && V.inPt >= V.outPt) V.inPt = null; refreshWave(); persist(); }, { cls: 'ghost small mark' })),
    wave.el,
    h('div', { class: 'toolbar wrap' },
      h('span', { id: 'video-audio-status', class: 'muted small' }),
      btn('Load audio here', () => loadAudio(Math.max(0, video.currentTime - 2)), { cls: 'ghost small', id: 'video-load-here' }),
      h('div', { class: 'spacer' }),
      btn('Add chop', () => {
        const a = V.inPt ?? video.currentTime, b = V.outPt != null && V.outPt > a ? V.outPt : a + (60 / S.song.bpm);
        const c = addChop(a, b);
        if (c) { V.selected = c.id; V.inPt = null; V.outPt = null; renderVideo(root); }
      }, { icon: 'scissors', cls: 'primary small' })),
    h('section', { class: 'panel' },
      h('div', { class: 'row-head' }, h('span', { class: 'eyebrow' }, 'Auto-chop'), h('span', { class: 'muted small' }, V.inPt != null && V.outPt > V.inPt ? 'In → Out' : 'loaded audio')),
      h('div', { class: 'toolbar wrap' },
        segmented([['transient', 'Transients'], ['equal', 'Equal'], ['grid', 'Beat grid']], V.autoMode, (v) => { V.autoMode = v; renderVideo(root); }, 'compact'),
        knob({ label: V.autoMode === 'transient' ? 'Sensitivity' : V.autoMode === 'equal' ? 'Chops' : 'Grid', min: 0, max: 1, value: V.autoAmount, size: 'mini',
          fmt: (v) => (V.autoMode === 'equal' ? String(Math.round(2 + v * 30)) : V.autoMode === 'grid' ? ['1/16', '1/8', '1/4', '1/2', '1 bar'][Math.round(v * 4)] : fmt.pct(v)), onchange: (v) => { V.autoAmount = v; } }).el,
        btn('Auto-chop', autoChop, { icon: 'scissors', cls: 'ghost small' }),
        V.chops.length ? btn('Clear chops', async () => { if (await confirmSheet('Clear all chops?', 'Every chop on this video will be removed. Samples already in the library stay.', 'Clear')) { V.chops = []; persist(); renderVideo(root); } }, { cls: 'ghost small' }) : null)),
    h('div', { class: 'tabs-inline' }, segmented([['chops', `Chops ${V.chops.length || ''}`], ['pads', 'Pads'], ['video', 'Video FX'], ['audio', 'Audio FX']], V.tab, (v) => { V.tab = v; renderVideo(root); })),
    h('section', { class: 'panel' }, tabs[V.tab](),
      V.tab === 'chops' || V.tab === 'pads' ? h('div', { class: 'row wrap' }, V.chops.length ? btn(`Save all ${V.chops.length} to library`, () => saveChops(V.chops), { icon: 'save', cls: 'primary small' }) : null) : null)));
  refreshWave();
  refreshStatus();
}

// Picture, time display and auto-stop at the chop end.
function tick() {
  if (V.file) {
    if (V.stopAt != null && video.currentTime >= V.stopAt) { video.pause(); V.stopAt = null; }
    if (timeEl?.isConnected) timeEl.textContent = fmtTime(video.currentTime);
    if (S.view === 'video' && wave && !video.paused) { wave.reveal(video.currentTime); wave.set({ cursor: video.currentTime }); }
    // Keep the decoded window ahead of normal playback (not during pad hits, which load their own).
    if (!video.paused && V.stopAt == null && V.buffer && !V.decoding && video.currentTime > V.audioStart + V.buffer.duration - 5 && video.currentTime < (video.duration || 0) - 1) loadAudio(video.currentTime - 2);
  }
  requestAnimationFrame(tick);
}
requestAnimationFrame(tick);
video.addEventListener('play', () => { if (playBtn) playBtn.querySelector('use').setAttribute('href', '#i-pause'); });
video.addEventListener('pause', () => { if (playBtn) playBtn.querySelector('use').setAttribute('href', '#i-play'); refreshWave(); });
video.addEventListener('seeked', () => { renderer?.frame(); });

export const videoState = V;

video.addEventListener('error', () => host.log(`video error ${video.error?.code}: ${video.error?.message || ''} at ${video.currentTime.toFixed(2)} s`));
video.addEventListener('stalled', () => host.log(`video stalled at ${video.currentTime.toFixed(2)} s`));
