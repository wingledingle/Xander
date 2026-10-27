// WebGL2 renderer with filter shaders; falls back to Canvas 2D.

const VS = `#version 300 es
in vec2 pos;
out vec2 uv;
void main() {
  uv = vec2(pos.x * 0.5 + 0.5, 0.5 - pos.y * 0.5);
  gl_Position = vec4(pos, 0.0, 1.0);
}`;

const COMMON = `#version 300 es
precision highp float;
in vec2 uv;
out vec4 color;
uniform sampler2D tex;
uniform vec2 srcSize;
uniform vec2 outSize;
uniform float brightness;
uniform float saturation;
uniform int correct;
vec3 post(vec3 c) {
  if (correct == 1) {
    // Approximation of the GBA LCD: darker gamma and colour bleed.
    c = pow(c, vec3(2.2));
    c = vec3(0.82 * c.r + 0.24 * c.g - 0.06 * c.b,
             0.09 * c.r + 0.67 * c.g + 0.24 * c.b,
             0.15 * c.r + 0.10 * c.g + 0.75 * c.b);
    c = pow(clamp(c, 0.0, 1.0), vec3(1.0 / 2.0));
  }
  float l = dot(c, vec3(0.299, 0.587, 0.114));
  c = mix(vec3(l), c, saturation);
  return clamp(c * brightness, 0.0, 1.0);
}
`;

const SHADERS = {
  pixel: `void main() { color = vec4(post(texture(tex, uv).rgb), 1.0); }`,
  smooth: `void main() { color = vec4(post(texture(tex, uv).rgb), 1.0); }`,
  // Sharp bilinear: nearest within texels, linear only at texel borders.
  sharp: `void main() {
    vec2 texel = uv * srcSize;
    vec2 scale = max(floor(outSize / srcSize), vec2(1.0));
    vec2 f = fract(texel);
    vec2 region = 0.5 - 0.5 / scale;
    vec2 off = (clamp(f - region, 0.0, 1.0 - 2.0 * region) / (1.0 - 2.0 * region)) - f;
    color = vec4(post(texture(tex, (texel + off) / srcSize).rgb), 1.0);
  }`,
  lcd: `void main() {
    vec2 texel = uv * srcSize;
    vec3 c = texture(tex, (floor(texel) + 0.5) / srcSize).rgb;
    vec2 f = fract(texel);
    float grid = smoothstep(0.0, 0.12, f.x) * smoothstep(0.0, 0.12, f.y);
    c *= mix(0.72, 1.0, grid);
    color = vec4(post(c), 1.0);
  }`,
  scanlines: `void main() {
    vec2 texel = uv * srcSize;
    vec3 c = texture(tex, (floor(texel) + 0.5) / srcSize).rgb;
    float s = 0.75 + 0.25 * cos(fract(texel.y) * 6.2831853);
    color = vec4(post(c * s), 1.0);
  }`,
  crt: `void main() {
    vec2 p = uv * 2.0 - 1.0;
    p *= 1.0 + 0.06 * dot(p.yx, p.yx);
    vec2 q = p * 0.5 + 0.5;
    if (q.x < 0.0 || q.y < 0.0 || q.x > 1.0 || q.y > 1.0) { color = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 texel = q * srcSize;
    vec3 c = texture(tex, q).rgb;
    float s = 0.7 + 0.3 * pow(sin(fract(texel.y) * 3.14159), 0.6);
    float mask = 0.9 + 0.1 * sin(gl_FragCoord.x * 2.094);
    float vig = 1.0 - 0.25 * dot(p, p);
    color = vec4(post(c * s * mask * vig * 1.15), 1.0);
  }`,
};

export class Renderer {
  constructor(canvas) {
    this.canvas = canvas;
    this.shader = 'pixel';
    this.params = { brightness: 1, saturation: 1, correct: false };
    const gl = canvas.getContext('webgl2', { antialias: false, alpha: false, preserveDrawingBuffer: true, desynchronized: true });
    if (gl) this.initGL(gl);
    else this.ctx = canvas.getContext('2d');
    this.image = this.ctx ? new ImageData(240, 160) : null;
  }

  get webgl() { return !!this.gl; }

  initGL(gl) {
    this.gl = gl;
    this.programs = {};
    const buf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    this.tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, 240, 160, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
  }

  program(name) {
    if (this.programs[name]) return this.programs[name];
    const gl = this.gl;
    const compile = (type, src) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, src);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, VS));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, COMMON + (SHADERS[name] || SHADERS.pixel)));
    gl.bindAttribLocation(p, 0, 'pos');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p));
    const u = (n) => gl.getUniformLocation(p, n);
    this.programs[name] = { p, srcSize: u('srcSize'), outSize: u('outSize'), brightness: u('brightness'), saturation: u('saturation'), correct: u('correct') };
    return this.programs[name];
  }

  setShader(name) {
    this.shader = SHADERS[name] ? name : 'pixel';
    this.canvas.style.imageRendering = this.shader === 'smooth' ? 'auto' : 'pixelated';
  }

  /// Sizes the canvas for the available area.
  layout(availW, availH, scaling, aspect) {
    const dpr = window.devicePixelRatio || 1;
    let w, h;
    if (scaling === 'stretch' && aspect === 'free') {
      w = availW; h = availH;
    } else {
      let s = Math.min(availW / 240, availH / 160);
      if (scaling === 'integer') s = Math.max(1, Math.floor(s * dpr) / dpr);
      w = 240 * s; h = 160 * s;
    }
    w = Math.floor(w); h = Math.floor(h);
    this.canvas.style.width = w + 'px';
    this.canvas.style.height = h + 'px';
    const pw = Math.round(w * dpr), ph = Math.round(h * dpr);
    if (this.gl) {
      if (this.canvas.width !== pw || this.canvas.height !== ph) { this.canvas.width = pw; this.canvas.height = ph; }
    } else if (this.canvas.width !== 240) {
      this.canvas.width = 240; this.canvas.height = 160;
    }
  }

  draw(rgba) {
    if (!this.gl) {
      this.image.data.set(rgba);
      this.ctx.putImageData(this.image, 0, 0);
      if (this.params.brightness !== 1) this.canvas.style.filter = `brightness(${this.params.brightness})`;
      return;
    }
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D, this.tex);
    gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, 240, 160, gl.RGBA, gl.UNSIGNED_BYTE, rgba);
    const linear = this.shader === 'smooth' || this.shader === 'sharp' || this.shader === 'crt';
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, linear ? gl.LINEAR : gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, linear ? gl.LINEAR : gl.NEAREST);
    const pr = this.program(this.shader);
    gl.useProgram(pr.p);
    gl.uniform2f(pr.srcSize, 240, 160);
    gl.uniform2f(pr.outSize, this.canvas.width, this.canvas.height);
    gl.uniform1f(pr.brightness, this.params.brightness);
    gl.uniform1f(pr.saturation, this.params.saturation);
    gl.uniform1i(pr.correct, this.params.correct ? 1 : 0);
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
  }
}
