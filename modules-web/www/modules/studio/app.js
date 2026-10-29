// Copyright 2026 Callback contributors (Groovesmith) and Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Groovesmith Studio shell: top bar, transport, deck navigation, project menu and file-manager launch.

import { S, subscribe, changed, undo, redo, audio, loadLibrary, savePrefs } from './state.js';
import { PATTERNS, PATTERN_NAMES, clamp, tapTempo } from './core.js';
import { h, $, icon, iconBtn, btn, sheet, prompt, toast } from './ui.js';
import { togglePlay, stop, exportWav, bounceToLibrary, saveProject, openProject, newProject, importAudioFiles } from './actions.js';
import { renderBeat, beatPlayhead, refreshCells } from './views/beat.js';
import { renderSynth, synthPlayhead } from './views/synth.js';
import { renderSampler } from './views/sampler.js';
import { renderVideo, openVideo } from './views/video.js';
import { renderLibrary } from './views/library.js';
import { renderMixer, drawMeters } from './views/mixer.js';
import { lowLatency } from './native.js';
import { host } from '../host/xander.js';

const VIEWS = [
  ['beat', 'Beat', 'grid'], ['synth', 'Synth', 'piano'], ['sampler', 'Sampler', 'wave'],
  ['video', 'Video', 'movie'], ['library', 'Library', 'library'], ['mixer', 'Mixer', 'sliders'],
];
const main = $('#main');

function go(view, arg) {
  S.view = view;
  try { localStorage.setItem('gs-studio-view', view); } catch {}
  document.querySelectorAll('.nav button').forEach((b) => b.setAttribute('aria-current', String(b.dataset.view === view)));
  render();
  main.scrollTop = 0;
  if (view === 'video' && arg?.path) host.openPath?.(arg.path).then((f) => f && openVideo(f)).catch((e) => toast(e.message));
}

function render() {
  ({ beat: renderBeat, synth: renderSynth, sampler: renderSampler, video: renderVideo, library: (r) => renderLibrary(r, go), mixer: renderMixer })[S.view](main);
  renderTransport();
}

// ---- Top bar + transport -----------------------------------------------------------------------------

// Cheap per-change update of the transport (no DOM rebuild).
function updateTransport() {
  $('#title').textContent = S.song.name;
  $('#undo').disabled = !S.history.canUndo;
  $('#redo').disabled = !S.history.canRedo;
  const v = document.querySelector('.bpm .lcd-v');
  if (v) v.textContent = String(Math.round(S.song.bpm * 10) / 10);
}

const taps = [];
function renderTransport() {
  const sg = S.song, playing = !!S.seq?.playing;
  $('#title').textContent = sg.name;
  $('#undo').disabled = !S.history.canUndo;
  $('#redo').disabled = !S.history.canRedo;
  const bpm = h('button', { type: 'button', class: 'lcd bpm', 'aria-label': `Tempo ${sg.bpm} BPM, tap to change`, onclick: tempoSheet },
    h('span', { class: 'lcd-v' }, String(Math.round(sg.bpm * 10) / 10)), h('span', { class: 'lcd-u' }, 'BPM'));
  const pats = h('div', { class: 'pattern-chips', role: 'radiogroup', 'aria-label': 'Pattern' }, Array.from({ length: PATTERNS }, (_, i) => {
    const used = Object.values(sg.patterns[i].steps).some((a) => a.some((v) => v > 0)) || sg.patterns[i].notes.some((n) => n >= 0);
    const now = playing && S.playhead && S.playhead.pattern === i;
    return h('button', { type: 'button', role: 'radio', class: `pchip p${i}${used ? ' used' : ''}${now ? ' now' : ''}`, 'aria-checked': String(sg.current === i),
      onclick: () => { sg.current = i; changed('view'); } }, PATTERN_NAMES[i]);
  }));
  requestAnimationFrame(() => pats.querySelector('[aria-checked="true"]')?.scrollIntoView({ inline: 'nearest', block: 'nearest' }));
  $('#transport').replaceChildren(
    h('button', { type: 'button', class: `play-btn${playing ? ' on' : ''}`, 'aria-label': playing ? 'Stop' : 'Play', onclick: togglePlay }, icon(playing ? 'stop' : 'play')),
    h('button', { type: 'button', class: `rec-btn${S.rec ? ' on' : ''}`, 'aria-label': 'Record pad hits', 'aria-pressed': String(S.rec), onclick: () => { S.rec = !S.rec; renderTransport(); toast(S.rec ? 'Recording: pad and track hits are written to the playing step' : 'Recording off'); } }, icon('rec')),
    bpm,
    pats,
    h('canvas', { id: 'scope', class: 'scope', width: 240, height: 64, 'aria-hidden': 'true' }));
}

function tempoSheet() {
  const sg = S.song;
  const val = h('input', { class: 'field big-num', type: 'number', inputmode: 'decimal', min: 40, max: 240, step: 0.1, value: sg.bpm, 'aria-label': 'Tempo in BPM' });
  const set = (v) => { sg.bpm = Math.round(clamp(v, 40, 240) * 10) / 10; val.value = sg.bpm; changed('param'); renderTransport(); };
  val.addEventListener('change', () => set(Number(val.value) || sg.bpm));
  const swing = h('input', { type: 'range', min: 0, max: 50, value: Math.round(sg.swing * 100), 'aria-label': 'Swing', oninput: (e) => { sg.swing = e.target.value / 100; sw.textContent = `${e.target.value}%`; changed('param'); } });
  const sw = h('span', { class: 'mono' }, `${Math.round(sg.swing * 100)}%`);
  sheet('Tempo', h('div', { class: 'form' },
    h('div', { class: 'row' }, btn('−1', () => set(sg.bpm - 1), { cls: 'ghost' }), val, btn('+1', () => set(sg.bpm + 1), { cls: 'ghost' })),
    btn('Tap tempo', () => { taps.push(performance.now() / 1000); if (taps.length > 8) taps.shift(); const b = tapTempo(taps); if (b) set(b); }, { cls: 'primary wide', icon: 'tap' }),
    h('label', { class: 'field-label' }, h('span', {}, 'Swing ', sw), swing),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', checked: S.metronome || null, onchange: (e) => { S.metronome = e.target.checked; if (S.seq) S.seq.metronome = S.metronome; } }), 'Metronome')));
}

function menu() {
  const item = (ic, label, fn, sub) => h('button', { type: 'button', class: 'menu-item', onclick: () => { s.close(); fn(); } }, icon(ic), h('span', {}, label, sub ? h('small', {}, sub) : null));
  const s = sheet('Project', h('div', { class: 'menu' },
    item('edit', 'Rename project', async () => { const n = await prompt('Project name', S.song.name); if (n) { S.song.name = n.slice(0, 80); changed('song'); } }),
    item('save', 'Save project', () => saveProject().catch((e) => toast(e.message)), 'Song and the samples it uses, as one .gsproj file'),
    item('folder', 'Open project', async () => { const [f] = await host.pick({ accept: ['application/json', 'application/octet-stream', '*/*'] }); if (f) openProject(f).catch((e) => toast(e.message, 5000)); }),
    item('export', 'Export pattern as WAV', () => exportWav(false).catch((e) => toast(e.message))),
    item('export', 'Export song as WAV', () => exportWav(true).catch((e) => toast(e.message)), 'Plays the arrangement once'),
    item('library', 'Bounce pattern to library', () => bounceToLibrary().catch((e) => toast(e.message)), 'One bar loop you can chop or sequence'),
    item('plus', 'New empty project', () => newProject(false)),
    item('dice', 'Load demo song', () => newProject(true)),
    h('label', { class: 'menu-item check' }, h('input', { type: 'checkbox', checked: S.prefs.lowLatencyPads || null, disabled: !lowLatency.available || null,
      onchange: (e) => { S.prefs.lowLatencyPads = e.target.checked; savePrefs(); } }), h('span', {}, 'Low-latency pads',
      h('small', {}, lowLatency.available ? `Native audio path${lowLatency.latencyMs() ? `, about ${Math.round(lowLatency.latencyMs())} ms output latency` : ''}. Sends (delay/reverb) are skipped on pad hits.` : 'Available when running inside Xander on Android.'))),
    h('p', { class: 'hint' }, 'Groovesmith Studio. All sounds are synthesized or come from your own files. Apache-2.0.')));
}

// ---- Oscilloscope ------------------------------------------------------------------------------------

const scopeBuf = new Float32Array(2048);
function frame() {
  const cv = $('#scope');
  if (cv && S.engine) {
    const g = cv.getContext('2d'), W = cv.width, H = cv.height;
    S.engine.analyser.getFloatTimeDomainData(scopeBuf);
    g.clearRect(0, 0, W, H);
    g.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue('--accent2');
    g.lineWidth = 2; g.beginPath();
    let start = 0;
    for (let i = 1; i < 1024; i++) if (scopeBuf[i - 1] < 0 && scopeBuf[i] >= 0) { start = i; break; }
    for (let x = 0; x < W; x++) { const v = scopeBuf[start + Math.floor((x / W) * 1024)] || 0; const y = H / 2 - v * H * 0.45; x ? g.lineTo(x, y) : g.moveTo(x, y); }
    g.stroke();
  }
  if (S.view === 'mixer') drawMeters();
  requestAnimationFrame(frame);
}

// ---- Wiring ------------------------------------------------------------------------------------------

let lastPat = -1;
subscribe((kind) => {
  if (kind === 'playhead') {
    const p = S.playhead;
    if (S.view === 'beat') beatPlayhead(p);
    if (S.view === 'synth') synthPlayhead(p);
    const pat = p?.pattern ?? -1;
    if (pat !== lastPat) { lastPat = pat; document.querySelectorAll('.pchip').forEach((b, i) => b.classList.toggle('now', i === pat)); }
    return;
  }
  // Knob moves and step edits update in place: rebuilding the deck on every change is what makes UIs stutter.
  if (kind === 'param') return updateTransport();
  if (kind === 'steps') { updateTransport(); if (S.view === 'beat') refreshCells(); return; }
  if (kind === 'library' && !['library', 'sampler', 'beat'].includes(S.view)) return;
  if (kind === 'library' && S.view === 'sampler' && document.activeElement?.closest('.deck')) return; // don't rebuild while editing
  render();
});

$('#nav').replaceChildren(...VIEWS.map(([id, label, ic]) => h('button', { type: 'button', 'data-view': id, 'aria-current': 'false', onclick: () => go(id) }, icon(ic), h('span', {}, label))));
$('#undo').addEventListener('click', undo);
$('#redo').addEventListener('click', redo);
$('#menu').addEventListener('click', menu);
$('#back').addEventListener('click', () => { stop(); host.exit(); });
$('#title').addEventListener('click', async () => { const n = await prompt('Project name', S.song.name); if (n) { S.song.name = n.slice(0, 80); changed('song'); } });
document.addEventListener('pointerdown', () => audio(), { once: true, capture: true });
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
  else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); }
  else if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); }
});
window.XanderModule = { onBack: () => { stop(); return false; } };

let initial = 'beat';
try { initial = localStorage.getItem('gs-studio-view') || 'beat'; } catch {}
if (!VIEWS.some(([v]) => v === initial)) initial = 'beat';
go(initial);
requestAnimationFrame(frame);

// Opened from the file manager: videos go to the Video deck, audio to the library, .gsproj opens the project.
loadLibrary().then(async () => {
  const f = host.launch;
  if (!f) return;
  const ext = f.name.split('.').pop().toLowerCase();
  try {
    if (ext === 'gsproj') { await openProject(f); go('beat'); }
    else if (f.mime?.startsWith('video/')) { go('video'); await openVideo(f); }
    else if (f.mime?.startsWith('audio/')) { const [r] = await importAudioFiles([f]); if (r) { S.selectedSample = r.id; go('sampler'); } }
  } catch (e) { toast(e.message, 5000); }
});
