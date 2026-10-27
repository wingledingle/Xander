// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Waveform editor used by the Sampler and Video decks: zoom, pan, In/Out handles, chop regions, markers,
// cursor and playhead. Times are seconds on the owner's timeline (`offset` = timeline time of sample 0).

import { h, iconBtn } from '../ui.js';
import { peaks, mixDown, clamp } from '../dsp.js';

export function waveView({ height = 150, onSeek, onSelect, onMarker, markerMode = () => false } = {}) {
  const canvas = h('canvas', { class: 'wave-canvas', height: height * 2 });
  let buf = null, mono = null, offset = 0, duration = 0, v0 = 0, v1 = 0;
  let st = { regions: [], selection: null, markers: [], cursor: null, playhead: null };
  const css = () => getComputedStyle(document.documentElement);

  const W = () => canvas.width, x2t = (x) => v0 + (x / W()) * (v1 - v0), t2x = (t) => ((t - v0) / (v1 - v0)) * W();

  function draw() {
    const dpr = 2, w = Math.max(1, Math.round(canvas.clientWidth * dpr));
    if (canvas.width !== w) canvas.width = w;
    const g = canvas.getContext('2d'), H = canvas.height, c = css();
    g.clearRect(0, 0, w, H);
    g.fillStyle = c.getPropertyValue('--wave-bg'); g.fillRect(0, 0, w, H);
    if (!mono) { g.fillStyle = c.getPropertyValue('--muted'); g.font = `${13 * dpr}px system-ui`; g.fillText('No audio loaded', 16 * dpr, H / 2); return; }
    // Regions under the waveform.
    for (const r of st.regions) {
      const a = t2x(r.start), b = t2x(r.end);
      if (b < 0 || a > w) continue;
      g.fillStyle = r.color + (r.active ? '55' : '26'); g.fillRect(a, 0, b - a, H);
      g.fillStyle = r.color; g.fillRect(a, 0, Math.max(2, dpr), H);
      if (r.label && b - a > 40 * dpr) { g.font = `600 ${11 * dpr}px system-ui`; g.fillText(r.label.slice(0, 24), a + 5 * dpr, 14 * dpr); }
    }
    // Waveform.
    const sr = buf.sampleRate, a = Math.floor((v0 - offset) * sr), b = Math.ceil((v1 - offset) * sr);
    const lo = clamp(a, 0, mono.length), hi = clamp(b, 0, mono.length);
    if (hi > lo) {
      const xa = t2x(offset + lo / sr), xb = t2x(offset + hi / sr), cols = Math.max(1, Math.round(xb - xa));
      const pk = peaks(mono, cols, lo, hi), mid = H / 2;
      g.fillStyle = c.getPropertyValue('--wave');
      for (let x = 0; x < cols; x++) {
        const y0 = mid - pk[x * 2 + 1] * mid * 0.92, y1 = mid - pk[x * 2] * mid * 0.92;
        g.fillRect(xa + x, y0, 1, Math.max(1, y1 - y0));
      }
    }
    // Selection (In/Out), dimming outside it.
    if (st.selection) {
      const sa = t2x(st.selection.start), sb = t2x(st.selection.end), acc = c.getPropertyValue('--accent');
      g.fillStyle = 'rgba(0,0,0,.45)'; g.fillRect(0, 0, Math.max(0, sa), H); g.fillRect(sb, 0, w - sb, H);
      g.fillStyle = acc; g.fillRect(sa - dpr, 0, 2 * dpr, H); g.fillRect(sb - dpr, 0, 2 * dpr, H);
      g.fillRect(sa - 6 * dpr, H - 18 * dpr, 7 * dpr, 18 * dpr); g.fillRect(sb - dpr, H - 18 * dpr, 7 * dpr, 18 * dpr);
    }
    g.fillStyle = c.getPropertyValue('--accent2');
    for (const m of st.markers) { const x = t2x(m); g.fillRect(x - dpr / 2, 0, dpr, H); g.beginPath(); g.moveTo(x - 5 * dpr, 0); g.lineTo(x + 5 * dpr, 0); g.lineTo(x, 7 * dpr); g.fill(); }
    if (st.cursor != null) { g.fillStyle = '#fff'; g.fillRect(t2x(st.cursor) - dpr / 2, 0, dpr, H); }
    if (st.playhead != null) { g.fillStyle = c.getPropertyValue('--rec'); g.fillRect(t2x(st.playhead) - dpr, 0, 2 * dpr, H); }
    // Overview bar showing the zoom window.
    if (v1 - v0 < duration - 1e-6) {
      g.fillStyle = 'rgba(255,255,255,.15)'; g.fillRect(0, H - 4 * dpr, w, 4 * dpr);
      g.fillStyle = c.getPropertyValue('--accent'); g.fillRect(((v0 - offset) / duration) * w, H - 4 * dpr, ((v1 - v0) / duration) * w, 4 * dpr);
    }
  }

  function zoom(f, center = st.cursor ?? (v0 + v1) / 2) {
    const span = clamp((v1 - v0) / f, Math.min(0.05, duration), duration);
    v0 = clamp(center - (center - v0) * (span / (v1 - v0)), offset, offset + duration - span);
    v1 = v0 + span;
    draw();
  }

  // Gestures: drag an In/Out handle, tap to seek (or add/remove a marker in marker mode), drag to pan,
  // two-finger pinch to zoom.
  const touches = new Map();
  let drag = null;
  canvas.addEventListener('pointerdown', (e) => {
    if (!mono) return;
    canvas.setPointerCapture(e.pointerId);
    touches.set(e.pointerId, e.clientX);
    const r = canvas.getBoundingClientRect(), x = (e.clientX - r.left) * (W() / r.width), dpr = W() / r.width;
    if (touches.size === 2) { const [a, b] = [...touches.values()]; drag = { pinch: Math.abs(a - b), span: v1 - v0, c: x2t(x) }; return; }
    const near = (t) => t != null && Math.abs(t2x(t) - x) < 18 * dpr;
    if (st.selection && near(st.selection.start)) drag = { edge: 'start' };
    else if (st.selection && near(st.selection.end)) drag = { edge: 'end' };
    else drag = { x0: e.clientX, v0, moved: false, r };
  });
  canvas.addEventListener('pointermove', (e) => {
    if (!drag || !touches.has(e.pointerId)) return;
    touches.set(e.pointerId, e.clientX);
    const r = canvas.getBoundingClientRect(), x = (e.clientX - r.left) * (W() / r.width);
    if (drag.pinch != null && touches.size === 2) {
      const [a, b] = [...touches.values()], f = Math.abs(a - b) / Math.max(10, drag.pinch);
      const span = clamp(drag.span / f, Math.min(0.05, duration), duration);
      v0 = clamp(drag.c - span / 2, offset, offset + duration - span); v1 = v0 + span; draw();
    } else if (drag.edge) {
      const t = clamp(x2t(x), offset, offset + duration), sel = { ...st.selection, [drag.edge]: t };
      if (sel.end - sel.start > 0.005) { st.selection = sel; draw(); onSelect?.(sel, false); }
    } else if (drag.x0 != null) {
      const dx = e.clientX - drag.x0;
      if (Math.abs(dx) > 6) drag.moved = true;
      if (drag.moved && v1 - v0 < duration) {
        const span = v1 - v0;
        v0 = clamp(drag.v0 - (dx / drag.r.width) * span, offset, offset + duration - span); v1 = v0 + span; draw();
      }
    }
  });
  const up = (e) => {
    touches.delete(e.pointerId);
    if (!drag) return;
    const r = canvas.getBoundingClientRect(), t = clamp(x2t((e.clientX - r.left) * (W() / r.width)), offset, offset + duration);
    if (drag.edge) onSelect?.(st.selection, true);
    else if (drag.x0 != null && !drag.moved) {
      if (markerMode()) {
        const hit = st.markers.find((m) => Math.abs(t2x(m) - t2x(t)) < 16 * (W() / r.width));
        onMarker?.(hit ?? t, hit != null);
      } else onSeek?.(t);
    }
    if (touches.size === 0) drag = null;
  };
  canvas.addEventListener('pointerup', up);
  canvas.addEventListener('pointercancel', up);
  canvas.addEventListener('wheel', (e) => { e.preventDefault(); const r = canvas.getBoundingClientRect(); zoom(e.deltaY < 0 ? 1.25 : 0.8, x2t((e.clientX - r.left) * (W() / r.width))); }, { passive: false });

  const el = h('div', { class: 'wave' }, canvas, h('div', { class: 'wave-zoom' },
    iconBtn('zoom-out', 'Zoom out', () => zoom(0.5)), iconBtn('zoom-in', 'Zoom in', () => zoom(2)), iconBtn('fit', 'Show all', () => { v0 = offset; v1 = offset + duration; draw(); })));
  new ResizeObserver(() => draw()).observe(canvas);

  return {
    el,
    setBuffer(b, off = 0, keepView = false) {
      buf = b; mono = b ? mixDown(Array.from({ length: b.numberOfChannels }, (_, i) => b.getChannelData(i))) : null;
      offset = off; duration = b ? b.duration : 0;
      if (!keepView || v1 <= v0) { v0 = offset; v1 = offset + duration; }
      draw();
    },
    set(next) { Object.assign(st, next); draw(); },
    get view() { return { start: v0, end: v1 }; },
    reveal(t) { if (t < v0 || t > v1) { const span = v1 - v0; v0 = clamp(t - span * 0.1, offset, offset + duration - span); v1 = v0 + span; } },
    draw,
  };
}
