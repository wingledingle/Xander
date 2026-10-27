// Audio output: resamples the core's 32768 Hz stereo stream to the device
// rate and feeds an AudioWorklet ring buffer. The resampling ratio is
// nudged by up to ±0.5% to keep the buffer at the requested latency
// (dynamic rate control), which avoids both crackles and drift.

const WORKLET = `
class OleanderSink extends AudioWorkletProcessor {
  constructor() {
    super();
    this.size = 1 << 15;
    this.l = new Float32Array(this.size);
    this.r = new Float32Array(this.size);
    this.read = 0; this.write = 0; this.count = 0;
    this.port.onmessage = (e) => {
      const d = e.data;
      if (d.clear) { this.read = this.write = this.count = 0; return; }
      const n = d.l.length;
      for (let i = 0; i < n; i++) {
        if (this.count >= this.size) { this.read = (this.read + 1) % this.size; this.count--; }
        this.l[this.write] = d.l[i]; this.r[this.write] = d.r[i];
        this.write = (this.write + 1) % this.size; this.count++;
      }
    };
    this.frames = 0;
  }
  process(_, outputs) {
    const out = outputs[0];
    const L = out[0], R = out[1] || out[0];
    for (let i = 0; i < L.length; i++) {
      if (this.count > 0) {
        L[i] = this.l[this.read]; R[i] = this.r[this.read];
        this.read = (this.read + 1) % this.size; this.count--;
      } else { L[i] = 0; R[i] = 0; }
    }
    if ((this.frames++ & 7) === 0) this.port.postMessage(this.count);
    return true;
  }
}
registerProcessor('oleander-sink', OleanderSink);
`;

export class Audio {
  constructor() {
    this.ctx = null;
    this.node = null;
    this.gain = null;
    this.volume = 0.8;
    this.muted = false;
    this.latencyMs = 64;
    this.buffered = 0;
    this.phase = 0;
    this.ready = false;
    this.streamDest = null;
  }

  async start() {
    if (this.ctx) {
      if (this.ctx.state !== 'running') await this.ctx.resume().catch(() => {});
      return;
    }
    const AC = window.AudioContext || window.webkitAudioContext;
    if (!AC) return;
    this.ctx = new AC({ latencyHint: 'interactive' });
    try {
      const url = URL.createObjectURL(new Blob([WORKLET], { type: 'text/javascript' }));
      await this.ctx.audioWorklet.addModule(url);
      this.node = new AudioWorkletNode(this.ctx, 'oleander-sink', { outputChannelCount: [2] });
      this.node.port.onmessage = (e) => { this.buffered = e.data; };
      this.gain = this.ctx.createGain();
      this.node.connect(this.gain).connect(this.ctx.destination);
      this.applyVolume();
      this.ready = true;
    } catch (e) {
      console.warn('Audio unavailable', e);
    }
    if (this.ctx.state !== 'running') await this.ctx.resume().catch(() => {});
  }

  get rate() { return this.ctx ? this.ctx.sampleRate : 48000; }

  /// Buffered audio in milliseconds.
  bufferedMs() { return this.ctx ? (this.buffered / this.rate) * 1000 : 0; }

  setVolume(v) { this.volume = v; this.applyVolume(); }
  setMuted(m) { this.muted = m; this.applyVolume(); }
  applyVolume() { if (this.gain) this.gain.gain.value = this.muted ? 0 : this.volume; }

  clear() { this.node?.port.postMessage({ clear: true }); this.buffered = 0; }

  /// MediaStream of the output (for video recording).
  stream() {
    if (!this.ctx || !this.gain) return null;
    if (!this.streamDest) {
      this.streamDest = this.ctx.createMediaStreamDestination();
      this.gain.connect(this.streamDest);
    }
    return this.streamDest.stream;
  }

  /// Pushes core samples (Int16 interleaved stereo @ srcRate). `speed` is the
  /// emulation speed: audio is pitch-preserving only at 1x.
  push(samples, srcRate = 32768, speed = 1) {
    if (!this.ready || samples.length === 0) return;
    const target = this.latencyMs;
    const level = this.bufferedMs();
    // Dynamic rate control.
    let adjust = 1 + Math.max(-0.005, Math.min(0.005, (target - level) / target * 0.005));
    if (level > target * 3) { this.clear(); return; } // way behind: drop
    const step = (srcRate * speed) / (this.rate * adjust);
    const frames = samples.length >> 1;
    const outN = Math.floor((frames - this.phase) / step) + 1;
    if (outN <= 0) return;
    const L = new Float32Array(outN), R = new Float32Array(outN);
    let pos = this.phase;
    let n = 0;
    for (; n < outN; n++) {
      const i = Math.floor(pos);
      if (i >= frames) break;
      const f = pos - i;
      const l0 = samples[i * 2] / 32768;
      const r0 = samples[i * 2 + 1] / 32768;
      const l1 = i + 1 < frames ? samples[(i + 1) * 2] / 32768 : l0;
      const r1 = i + 1 < frames ? samples[(i + 1) * 2 + 1] / 32768 : r0;
      L[n] = l0 + (l1 - l0) * f;
      R[n] = r0 + (r1 - r0) * f;
      pos += step;
    }
    this.phase = Math.max(0, pos - frames);
    this.node.port.postMessage({ l: L.subarray(0, n), r: R.subarray(0, n) }, []);
  }
}
