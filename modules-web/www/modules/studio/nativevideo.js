// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Stand-in for <video> when the app provides its native player (XanderHost.videoCmd): a placeholder <div> with the
// parts of the HTMLMediaElement API the Video deck uses. The app draws the video (with FX) in a native view laid
// over this box, so the WebView never decodes or composites video frames. Time is extrapolated between the
// native state updates (sent on every change and 4x a second while playing).

export function nativeVideo(A) {
  const el = document.createElement('div');
  el.className = 'video-el native-video';
  let st = { t: 0, playing: false, dur: NaN, w: 0, h: 0, rate: 1, ready: false, at: performance.now() };
  let src = '', muted = false, volume = 1, err = null, lastRect = '';
  const send = (o) => A.videoCmd(JSON.stringify(o));
  const sendVol = () => send({ op: 'volume', v: volume, muted });
  window.__xanderVideo = {
    state(s) {
      st = { ...st, ...s, at: performance.now() };
      if (s.event === 'error') err = { code: s.error, message: s.message || s.error };
      if (s.event) el.dispatchEvent(new Event(s.event === 'loadedmetadata' ? 'loadedmetadata' : s.event));
    },
  };
  const now = () => (st.playing ? Math.min(Number.isFinite(st.dur) ? st.dur : Infinity, st.t + ((performance.now() - st.at) / 1000) * st.rate) : st.t);
  Object.defineProperties(el, {
    src: { get: () => src, set(v) { src = v; err = null; st = { ...st, ready: false, t: 0, playing: false }; } },
    currentTime: { get: now, set(v) { st = { ...st, t: Math.max(0, +v || 0), at: performance.now() }; send({ op: 'seek', t: st.t }); } },
    duration: { get: () => st.dur },
    paused: { get: () => !st.playing },
    readyState: { get: () => (st.ready ? 4 : 0) },
    videoWidth: { get: () => st.w },
    videoHeight: { get: () => st.h },
    error: { get: () => err },
    playbackRate: { get: () => st.rate, set(v) { st = { ...st, t: now(), rate: v, at: performance.now() }; send({ op: 'rate', r: v }); } },
    muted: { get: () => muted, set(v) { muted = !!v; sendVol(); } },
    volume: { get: () => volume, set(v) { volume = v; sendVol(); } },
  });
  el.load = () => { const m = /\/host\/file\/([^/]+)/.exec(src); if (m) send({ op: 'open', token: m[1] }); };
  el.play = () => { st = { ...st, t: now(), playing: true, at: performance.now() }; send({ op: 'play' }); return Promise.resolve(); };
  el.pause = () => { st = { ...st, t: now(), playing: false, at: performance.now() }; send({ op: 'pause' }); };
  el.setFx = (fx) => send({ op: 'fx', fx });
  el.native = true;

  // Keep the native view on this box; hide it when the box is off screen or something (a sheet, a menu) covers it.
  const track = () => {
    let r = null;
    if (el.isConnected && el.offsetParent !== null) {
      const b = el.getBoundingClientRect();
      const top = document.elementFromPoint(b.left + b.width / 2, b.top + Math.min(b.height / 2, 20));
      if (b.width > 1 && b.height > 1 && b.bottom > 0 && b.top < innerHeight && (top === el || el.contains(top))) r = b;
    }
    const key = r ? `${Math.round(r.left)},${Math.round(r.top)},${Math.round(r.width)},${Math.round(r.height)}` : '';
    if (key !== lastRect) {
      lastRect = key;
      send(r ? { op: 'rect', show: true, x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) } : { op: 'rect', show: false });
    }
    requestAnimationFrame(track);
  };
  requestAnimationFrame(track);
  return el;
}
