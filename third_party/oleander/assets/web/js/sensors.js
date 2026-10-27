// Motion and light sensors for cartridges with tilt (Yoshi Topsy-Turvy),
// gyro (WarioWare: Twisted!) and solar (Boktai) hardware.
import { settings } from './settings.js';
import { input } from './input.js';

export class Sensors {
  constructor() {
    this.tilt = { x: 0, y: 0 };
    this.gyro = 0;
    this.listening = false;
    this.ambient = null;
  }

  /// Must be called from a user gesture on iOS (permission prompt).
  async enable() {
    if (this.listening) return true;
    try {
      if (typeof DeviceMotionEvent !== 'undefined' && DeviceMotionEvent.requestPermission) {
        const r = await DeviceMotionEvent.requestPermission();
        if (r !== 'granted') return false;
      }
    } catch { /* not supported */ }
    window.addEventListener('devicemotion', (e) => {
      const g = e.accelerationIncludingGravity;
      if (g && g.x !== null) {
        // Normalise roughly to -1..1 (9.81 m/s² = 1g). Landscape devices swap axes.
        const land = innerWidth > innerHeight;
        const ax = land ? g.y : -g.x;
        const ay = land ? g.x : g.y;
        this.tilt.x = Math.max(-1, Math.min(1, ax / 9.81));
        this.tilt.y = Math.max(-1, Math.min(1, ay / 9.81));
      }
      const r = e.rotationRate;
      if (r && r.alpha !== null) this.gyro = Math.max(-1, Math.min(1, (r.alpha || 0) / 360));
    });
    // Ambient Light Sensor (where supported) drives the solar sensor.
    try {
      if ('AmbientLightSensor' in window) {
        const s = new window.AmbientLightSensor();
        s.addEventListener('reading', () => { this.ambient = Math.min(255, Math.round(Math.log10(1 + s.illuminance) * 60)); });
        s.start();
      }
    } catch { /* permission denied or unsupported */ }
    this.listening = true;
    return true;
  }

  /// Current values: [tiltX, tiltY, gyroZ, solar].
  read() {
    const src = settings.get('tiltSource');
    let tx = 0, ty = 0, gz = 0;
    if (src === 'device') { tx = this.tilt.x; ty = this.tilt.y; gz = this.gyro; }
    else if (src === 'keys') { tx = input.tiltKeys.x; ty = input.tiltKeys.y; gz = input.tiltKeys.z; }
    else if (src === 'mouse') { tx = input.mouse.x; ty = input.mouse.y; gz = input.mouse.x; }
    const solar = this.ambient ?? settings.get('solarLevel');
    return [tx, ty, gz, solar];
  }
}

export const sensors = new Sensors();
