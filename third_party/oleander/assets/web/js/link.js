// Link cable emulation between emulator instances.
//
// Transports:
//  * local      – several consoles in this page (split screen)
//  * channel    – other tabs/windows on this device (BroadcastChannel)
//  * relay      – other devices on the LAN or internet through
//                 `oleander-linkserver` (WebSocket)
//  * native     – Android app only: phone to phone over Wi-Fi/hotspot (TCP)
//                 or Bluetooth; the hosting phone relays (LinkHub.java)
//
// Consoles exchange link-port messages plus frame counters; an instance that
// runs more than `maxLead` frames ahead of the slowest peer waits, keeping
// the linked games synchronised.
import { el, showDialog, toast, closeDialog } from './ui.js';
import { settings } from './settings.js';

const clientId = Math.random().toString(36).slice(2, 10);

export class Link {
  constructor(player) {
    this.player = player;
    this.mode = 'off';
    this.transport = null;
    this.peers = new Map(); // id -> { joined, frame, seen }
    this.roster = [];
    this.joined = Date.now();
    this.maxLead = 3;
    this.room = '';
    player.linkHold = () => this.hold();
    player.linkPump = () => this.pump();
    player.beforeStart = () => {
      if (this.remote) { this.sendHello(); this.updateRoster(); }
    };
    this.android = window.OleanderAndroid?.linkHostWifi ? window.OleanderAndroid : null;
    if (this.android) {
      window.oleanderLinkRecv = (lines) => {
        for (const l of lines) { try { this.receive(JSON.parse(l)); } catch { /* ignore */ } }
      };
      window.oleanderLinkStatus = (type, detail) => this.nativeStatus(type, detail);
    }
  }

  get active() { return this.mode !== 'off'; }
  get remote() { return this.mode === 'channel' || this.mode === 'relay' || this.mode === 'native'; }

  status() {
    if (this.mode === 'off') return 'Not connected';
    if (this.mode === 'local') return `Local: ${this.player.units.length} consoles linked`;
    const me = this.roster.indexOf(clientId);
    if (this.mode === 'native') {
      const where = this.nativeInfo ? ` – ${this.nativeInfo}` : '';
      return `${this.room}${where}: player ${me + 1} of ${this.roster.length}`;
    }
    return `${this.mode === 'relay' ? 'Online' : 'Same device'} room "${this.room}": player ${me + 1} of ${this.roster.length}`;
  }

  // ---- local split screen ----

  enableLocal() {
    this.disconnect();
    const n = this.player.units.length;
    this.player.units.forEach((u, i) => u.con.linkEnable(i, n));
    this.mode = 'local';
  }

  // ---- remote ----

  connectChannel(room) {
    this.disconnect();
    this.room = room;
    const bc = new BroadcastChannel('oleander-link-' + room);
    bc.onmessage = (e) => this.receive(e.data);
    this.transport = { send: (m) => bc.postMessage(m), close: () => bc.close() };
    this.mode = 'channel';
    this.maxLead = 2;
    this.startHeartbeat();
  }

  connectRelay(url, room) {
    this.disconnect();
    this.room = room;
    const ws = new WebSocket(`${url.replace(/\/$/, '')}/${encodeURIComponent(room)}`);
    ws.binaryType = 'arraybuffer';
    ws.onmessage = (e) => {
      try { this.receive(JSON.parse(typeof e.data === 'string' ? e.data : new TextDecoder().decode(e.data))); } catch { /* ignore */ }
    };
    ws.onopen = () => { toast('Connected to link server'); this.sendHello(); };
    ws.onclose = () => { if (this.mode === 'relay') { toast('Link server disconnected', 'error'); this.disconnect(); } };
    ws.onerror = () => toast('Could not reach the link server', 'error');
    this.transport = { send: (m) => ws.readyState === 1 && ws.send(JSON.stringify(m)), close: () => ws.close() };
    this.mode = 'relay';
    this.maxLead = 8;
    this.startHeartbeat();
  }

  /// Android: phone-to-phone link. kind: 'wifi-host' | 'wifi-join' | 'bt-host' | 'bt-join'.
  connectNative(kind, address = '') {
    this.disconnect();
    const a = this.android;
    const host = kind.endsWith('host');
    this.room = { 'wifi-host': 'Hosting on Wi-Fi', 'wifi-join': `Joined ${address}`, 'bt-host': 'Hosting on Bluetooth', 'bt-join': 'Joined over Bluetooth' }[kind];
    this.nativeInfo = '';
    // The hosting phone is player 1.
    this.joined = host ? 0 : Date.now();
    this.transport = { send: (m) => a.linkSend(JSON.stringify(m)), close: () => a.linkClose() };
    this.mode = 'native';
    this.maxLead = 6;
    if (kind === 'wifi-host') a.linkHostWifi();
    else if (kind === 'wifi-join') a.linkJoinWifi(address);
    else if (kind === 'bt-host') a.linkHostBluetooth();
    else a.linkJoinBluetooth(address);
    this.startHeartbeat();
  }

  nativeStatus(type, detail) {
    if (this.mode !== 'native') return;
    switch (type) {
      case 'hosting': this.nativeInfo = detail; toast(`Waiting for the other phone – ${detail}`); break;
      case 'connected': toast(`Link connected: ${detail}`); this.sendHello(); break;
      case 'peer-joined': toast(`${detail} joined the link`); this.sendHello(); break;
      case 'peer-left': toast(`${detail} left the link`); break;
      case 'closed': toast(detail || 'Link closed', 'error'); this.disconnect(); return;
      case 'error': toast(detail, 'error'); this.disconnect(); return;
    }
    this.player.dispatchEvent(new Event('link'));
  }

  disconnect() {
    clearInterval(this.heartbeat);
    if (this.transport) {
      this.transport.send({ t: 'bye', from: clientId });
      this.transport.close();
    }
    this.transport = null;
    this.peers.clear();
    this.roster = [];
    if (this.mode !== 'off') for (const u of this.player.units) u.con.linkDisable();
    this.mode = 'off';
  }

  startHeartbeat() {
    this.sendHello();
    this.heartbeat = setInterval(() => {
      this.sendHello();
      const now = Date.now();
      let changed = false;
      for (const [id, p] of this.peers) if (now - p.seen > 5000) { this.peers.delete(id); changed = true; }
      if (changed) this.updateRoster();
    }, 1000);
  }

  sendHello() {
    this.transport?.send({ t: 'hello', from: clientId, joined: this.joined, game: this.player.main?.game.code || '' });
  }

  receive(m) {
    if (!m || m.from === clientId) return;
    const now = Date.now();
    switch (m.t) {
      case 'hello': {
        const prev = this.peers.get(m.from);
        this.peers.set(m.from, { ...(prev || { frame: 0 }), joined: m.joined, seen: now, game: m.game });
        if (!prev) { this.sendHello(); toast('A console joined the link'); }
        if (!prev || prev.game !== m.game) {
          // A peer started or left a game: restart frame sync from here.
          const p = this.peers.get(m.from);
          p.offset = null;
          p.frame = 0;
          p.waitSince = 0;
          this.updateRoster();
        }
        break;
      }
      case 'roster': // authoritative order from the relay server
        this.serverRoster = m.players;
        this.updateRoster();
        break;
      case 'bye':
        this.peers.delete(m.from);
        this.updateRoster();
        break;
      case 'frame': {
        const p = this.peers.get(m.from);
        if (p) { p.frame = m.f; p.seen = now; }
        break;
      }
      case 'link': {
        const con = this.player.con;
        if (con) for (const msg of m.d) con.linkDeliver(new Uint8Array(msg));
        break;
      }
    }
  }

  updateRoster() {
    // Player numbers follow the order consoles joined the room (stable, the
    // relay server decides); the "connected" count only includes consoles
    // that are running a game, which is what the cable's ready line shows.
    let ids = [clientId, ...this.peers.keys()];
    if (this.serverRoster) ids = this.serverRoster.filter((id) => ids.includes(id)).concat(ids.filter((id) => !this.serverRoster.includes(id)));
    else ids.sort((a, b) => (this.joinedOf(a) - this.joinedOf(b)) || a.localeCompare(b));
    this.roster = ids.slice(0, 4);
    const me = this.roster.indexOf(clientId);
    const playing = this.roster.filter((id) => (id === clientId ? !!this.player.con : !!this.peers.get(id)?.game)).length;
    const con = this.player.con;
    if (con && me >= 0) con.linkEnable(me, Math.max(1, playing));
    this.player.dispatchEvent(new Event('link'));
  }

  joinedOf(id) { return id === clientId ? this.joined : this.peers.get(id)?.joined ?? 0; }

  /// Moves messages between consoles; called before every emulation slice.
  pump() {
    if (this.mode === 'off') return;
    const units = this.player.units;
    if (this.mode === 'local') {
      for (let i = 0; i < units.length; i++) {
        for (const msg of units[i].con.linkTake()) {
          for (let j = 0; j < units.length; j++) if (j !== i) units[j].con.linkDeliver(msg);
        }
      }
      return;
    }
    const con = this.player.con;
    if (!con || !this.transport) return;
    const out = con.linkTake();
    if (out.length) this.transport.send({ t: 'link', from: clientId, d: out.map((b) => Array.from(b)) });
    this.transport.send({ t: 'frame', from: clientId, f: this.player.frame });
  }

  /// True while this instance must wait for slower peers.
  hold() {
    if (!this.remote) return false;
    let lead = -Infinity;
    const now = Date.now();
    for (const p of this.peers.values()) {
      if (!p.game) continue;
      if (!p.frame) {
        // The peer is starting a game but has not run a frame yet: wait for
        // it (briefly), otherwise we would talk to a console that isn't there.
        p.waitSince ||= now;
        if (now - p.waitSince < 3000) return true;
        continue;
      }
      p.waitSince = 0;
      // Frame counters start at different times; measure the drift from the
      // moment both consoles were running.
      if (p.offset === null || p.offset === undefined) p.offset = this.player.frame - p.frame;
      lead = Math.max(lead, this.player.frame - p.frame - p.offset);
    }
    return lead > this.maxLead;
  }

  dialog() {
    const status = el('p', {}, this.status());
    const room = el('input', { type: 'text', value: this.room || 'trade', style: { width: '160px' } });
    const server = el('input', { type: 'url', value: settings.global.linkServer || defaultServer(), style: { width: '280px' } });
    const body = el('div', {},
      status,
      el('div', { class: 'section' }, el('h3', {}, 'This device'),
        el('p', { class: 'sub' }, 'Link with another tab or window on this device (open Oleander twice and join the same room), or play two games side by side.'),
        el('div', { class: 'row' }, el('label', {}, 'Room'), room,
          el('button', { type: 'button', onclick: () => { this.connectChannel(room.value || 'trade'); closeDialog(); toast('Waiting for other consoles…'); } }, 'Join (other tabs)')),
        el('div', { class: 'row' }, el('button', { type: 'button', onclick: () => { closeDialog(); this.player.dispatchEvent(new Event('splitscreen')); } }, 'Split screen with a second game…'))),
      this.android ? this.nativeSection() : null,
      el('div', { class: 'section' }, el('h3', {}, this.android ? 'Internet (link server)' : 'Wi-Fi / Internet'),
        el('p', { class: 'sub' }, 'Run oleander-linkserver on a computer in your network (or online) and enter its address. Everyone joining the same room is linked; player 1 joins first.'),
        el('div', { class: 'row' }, el('label', {}, 'Server'), server),
        el('div', { class: 'row' }, el('label', {}, 'Room'), el('span', {}, '(same as above)'),
          el('button', { type: 'button', class: 'primary', onclick: async () => {
            await settings.set('linkServer', server.value);
            this.connectRelay(server.value, room.value || 'trade');
            closeDialog();
          } }, 'Connect'))),
      el('div', { class: 'dlg-actions' }, el('button', { type: 'button', class: 'danger', disabled: !this.active, onclick: () => { this.disconnect(); closeDialog(); toast('Link disconnected'); } }, 'Disconnect')));
    return showDialog('Link cable', body);
  }
}

Link.prototype.nativeSection = function nativeSection() {
  const ip = el('input', { type: 'text', inputmode: 'decimal', placeholder: '192.168.1.20', value: settings.global.linkPeer || '', style: { width: '160px' } });
  const devices = el('div', { class: 'row' });
  const go = (kind, addr) => { this.connectNative(kind, addr); closeDialog(); };
  window.oleanderBtDevices = (list) => {
    devices.replaceChildren(...(list.length
      ? list.map((d) => el('button', { type: 'button', onclick: () => go('bt-join', d.address) }, d.name))
      : [el('span', { class: 'sub' }, 'No paired phones. Pair the phones in Android\'s Bluetooth settings first.')]));
  };
  return el('div', { class: 'section' }, el('h3', {}, 'Phone to phone'),
    el('p', { class: 'sub' }, 'One phone hosts, the other joins; the host is player 1. Both phones need the same game running. Wi-Fi: both on the same network, or one phone\'s hotspot.'),
    el('div', { class: 'row' }, el('label', {}, 'Wi-Fi'),
      el('button', { type: 'button', class: 'primary', onclick: () => go('wifi-host') }, 'Host'),
      ip,
      el('button', { type: 'button', onclick: async () => { if (!ip.value.trim()) return toast('Enter the host phone\'s address (shown on the host)', 'error'); await settings.set('linkPeer', ip.value.trim()); go('wifi-join', ip.value.trim()); } }, 'Join')),
    el('div', { class: 'row' }, el('label', {}, 'Bluetooth'),
      el('button', { type: 'button', class: 'primary', onclick: () => go('bt-host') }, 'Host'),
      el('button', { type: 'button', onclick: () => { devices.replaceChildren(el('span', { class: 'sub' }, 'Loading paired devices…')); this.android.bluetoothDevices(); } }, 'Join…')),
    devices);
};

function defaultServer() {
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  return `${proto}://${location.hostname || 'localhost'}:8765`;
}
