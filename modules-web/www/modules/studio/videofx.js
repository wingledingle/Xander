// Copyright 2026 Xander contributors
// SPDX-License-Identifier: Apache-2.0
//
// Real-time video effects: one WebGL fragment shader draws the video frame with every effect applied.
// The canvas it draws to is what the Video deck shows and what "Export clip" records.

export const VIDEO_FX = {
  // key: [min, max, default, label]
  brightness: [-1, 1, 0, 'Bright'], contrast: [0, 2, 1, 'Contrast'], saturation: [0, 2, 1, 'Sat'], hue: [-180, 180, 0, 'Hue'],
  rgbShift: [0, 1, 0, 'RGB split'], pixelate: [0, 1, 0, 'Pixelate'], scanlines: [0, 1, 0, 'Scanlines'], noise: [0, 1, 0, 'Grain'],
  glitch: [0, 1, 0, 'Glitch'], posterize: [0, 1, 0, 'Posterize'], vignette: [0, 1, 0, 'Vignette'], invert: [0, 1, 0, 'Invert'],
  mirror: [0, 1, 0, 'Mirror'], thermal: [0, 1, 0, 'Thermal'], zoom: [1, 3, 1, 'Zoom'], strobe: [0, 1, 0, 'Strobe'],
};
export const FX_KEYS = Object.keys(VIDEO_FX);
export const defaultVideoFx = () => Object.fromEntries(FX_KEYS.map((k) => [k, VIDEO_FX[k][2]]));
export function normalizeVideoFx(o) {
  const d = defaultVideoFx();
  for (const k of FX_KEYS) { const [lo, hi] = VIDEO_FX[k]; const v = o?.[k]; if (typeof v === 'number' && Number.isFinite(v)) d[k] = Math.min(hi, Math.max(lo, v)); }
  return d;
}
export const VIDEO_PRESETS = {
  Clean: {},
  VHS: { saturation: 1.3, rgbShift: 0.35, scanlines: 0.55, noise: 0.35, vignette: 0.5, contrast: 1.1 },
  Glitch: { rgbShift: 0.7, glitch: 0.75, pixelate: 0.15, contrast: 1.2 },
  Noir: { saturation: 0, contrast: 1.45, noise: 0.3, vignette: 0.8, brightness: -0.05 },
  Thermal: { thermal: 1, contrast: 1.2 },
  Dream: { saturation: 1.5, hue: 35, vignette: 0.6, brightness: 0.08, zoom: 1.08 },
  'Lo-fi': { posterize: 0.6, pixelate: 0.35, noise: 0.2, saturation: 1.2 },
  Kaleido: { mirror: 1, hue: -60, saturation: 1.4 },
};

const VERT = `attribute vec2 p; varying vec2 uv; void main(){ uv = p * 0.5 + 0.5; uv.y = 1.0 - uv.y; gl_Position = vec4(p, 0.0, 1.0); }`;
const FRAG = `precision mediump float;
varying vec2 uv; uniform sampler2D tex; uniform vec2 res; uniform float time;
uniform float brightness, contrast, saturation, hue, rgbShift, pixelate, scanlines, noise, glitch, posterize, vignette, invert, mirror, thermal, zoom, strobe;
float rnd(vec2 c){ return fract(sin(dot(c, vec2(12.9898, 78.233))) * 43758.5453); }
vec3 hueRot(vec3 c, float a){ float s = sin(a), k = cos(a); vec3 w = vec3(0.299, 0.587, 0.114);
  mat3 m = mat3(w.x+k*(1.-w.x)+s*(-w.x), w.x+k*(-w.x)+s*(0.143), w.x+k*(-w.x)+s*(-(1.-w.x)),
                w.y+k*(-w.y)+s*(-w.y), w.y+k*(1.-w.y)+s*(0.140), w.y+k*(-w.y)+s*(w.y),
                w.z+k*(-w.z)+s*(1.-w.z), w.z+k*(-w.z)+s*(-0.283), w.z+k*(1.-w.z)+s*(w.z)); return m * c; }
void main(){
  vec2 t = (uv - 0.5) / zoom + 0.5;
  if (mirror > 0.5) { t.x = t.x > 0.5 ? 1.0 - t.x : t.x; t.y = mirror > 0.75 && t.y > 0.5 ? 1.0 - t.y : t.y; }
  if (pixelate > 0.0) { float n = mix(res.x, 24.0, pixelate); vec2 b = vec2(n, n * res.y / res.x); t = (floor(t * b) + 0.5) / b; }
  if (glitch > 0.0) { float row = floor(t.y * 24.0); float g = step(1.0 - glitch * 0.35, rnd(vec2(row, floor(time * 12.0)))); t.x += g * (rnd(vec2(time, row)) - 0.5) * 0.25 * glitch; }
  float sh = rgbShift * 0.02 + glitch * 0.01 * sin(time * 40.0);
  vec3 c = vec3(texture2D(tex, t + vec2(sh, 0.0)).r, texture2D(tex, t).g, texture2D(tex, t - vec2(sh, 0.0)).b);
  c = hueRot(c, radians(hue));
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, saturation);
  c = (c - 0.5) * contrast + 0.5 + brightness;
  if (posterize > 0.0) { float lv = mix(32.0, 3.0, posterize); c = floor(c * lv) / lv; }
  if (thermal > 0.0) { float x = clamp(l, 0.0, 1.0); vec3 th = mix(vec3(0.0, 0.0, 0.5), vec3(0.0, 1.0, 1.0), smoothstep(0.0, 0.33, x));
    th = mix(th, vec3(1.0, 1.0, 0.0), smoothstep(0.33, 0.66, x)); th = mix(th, vec3(1.0, 0.0, 0.0), smoothstep(0.66, 1.0, x)); c = mix(c, th, thermal); }
  if (scanlines > 0.0) c *= 1.0 - scanlines * 0.45 * (0.5 + 0.5 * sin(uv.y * res.y * 3.14159));
  if (noise > 0.0) c += (rnd(uv * res + time) - 0.5) * noise * 0.35;
  if (vignette > 0.0) { vec2 d = uv - 0.5; c *= 1.0 - vignette * dot(d, d) * 2.2; }
  if (invert > 0.5) c = 1.0 - c;
  if (strobe > 0.0) c *= 1.0 - strobe * step(0.5, fract(time * 8.0));
  if (t.x < 0.0 || t.x > 1.0 || t.y < 0.0 || t.y > 1.0) c = vec3(0.0);
  gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}`;

// Draws `video` onto `canvas` with fx each frame. Falls back to a plain 2D draw when WebGL is unavailable.
export function createVideoRenderer(canvas, video) {
  const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true, premultipliedAlpha: false });
  let fx = defaultVideoFx(), raf = 0;
  const t0 = performance.now();
  if (!gl) {
    const c2 = canvas.getContext('2d');
    const draw = () => { if (video.readyState >= 2) c2.drawImage(video, 0, 0, canvas.width, canvas.height); raf = requestAnimationFrame(draw); };
    return { set(v) { fx = v; }, start() { cancelAnimationFrame(raf); draw(); }, stop() { cancelAnimationFrame(raf); }, webgl: false, frame: draw };
  }
  const sh = (type, src) => { const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s); return s; };
  const prog = gl.createProgram();
  gl.attachShader(prog, sh(gl.VERTEX_SHADER, VERT)); gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FRAG));
  gl.linkProgram(prog); gl.useProgram(prog);
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, 'p');
  gl.enableVertexAttribArray(loc); gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const tex = gl.createTexture();
  gl.bindTexture(gl.TEXTURE_2D, tex);
  for (const [k, v] of [[gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE], [gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
  const U = Object.fromEntries(['res', 'time', ...FX_KEYS].map((k) => [k, gl.getUniformLocation(prog, k)]));
  function frame() {
    if (video.readyState >= 2) {
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGB, gl.RGB, gl.UNSIGNED_BYTE, video);
      gl.uniform2f(U.res, canvas.width, canvas.height);
      gl.uniform1f(U.time, (performance.now() - t0) / 1000);
      for (const k of FX_KEYS) gl.uniform1f(U[k], fx[k]);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    }
  }
  const loop = () => { frame(); raf = requestAnimationFrame(loop); };
  return { set(v) { fx = v; }, start() { cancelAnimationFrame(raf); loop(); }, stop() { cancelAnimationFrame(raf); }, webgl: true, frame };
}
