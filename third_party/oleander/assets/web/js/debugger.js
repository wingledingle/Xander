// Debugger panel: registers, disassembly with breakpoints, stepping,
// memory viewer/editor, watchpoints, layer/channel toggles and statistics.
import { el, showDialog, toast } from './ui.js';

const REGIONS = [
  ['BIOS', 0x00000000], ['EWRAM', 0x02000000], ['IWRAM', 0x03000000], ['I/O', 0x04000000],
  ['Palette', 0x05000000], ['VRAM', 0x06000000], ['OAM', 0x07000000], ['ROM', 0x08000000], ['Save', 0x0E000000],
];
const hex = (v, n = 8) => (v >>> 0).toString(16).toUpperCase().padStart(n, '0');
const MODES = { 0x10: 'USR', 0x11: 'FIQ', 0x12: 'IRQ', 0x13: 'SVC', 0x17: 'ABT', 0x1B: 'UND', 0x1F: 'SYS' };

export function debuggerDialog(player) {
  const con = player.con;
  const breakpoints = player.breakpoints || (player.breakpoints = new Set());
  let memAddr = 0x02000000;
  let disasmAddr = null;

  const regsBox = el('div', { class: 'dbg-box' });
  const disBox = el('div', { class: 'dbg-box' });
  const memBox = el('div', { class: 'dbg-box' });
  const statsBox = el('div', { class: 'dbg-box' });
  const memInput = el('input', { type: 'text', value: hex(memAddr), style: { width: '110px' }, class: 'mono' });
  const disInput = el('input', { type: 'text', placeholder: 'PC', style: { width: '110px' }, class: 'mono' });

  const refresh = () => {
    const r = con.regs();
    const cpsr = r[16];
    const flags = [[31, 'N'], [30, 'Z'], [29, 'C'], [28, 'V'], [7, 'I'], [6, 'F'], [5, 'T']].map(([b, c]) => (cpsr >>> b) & 1 ? c : '-').join('');
    let txt = '';
    for (let i = 0; i < 16; i++) txt += `${(i === 13 ? 'sp' : i === 14 ? 'lr' : i === 15 ? 'pc' : 'r' + i).padEnd(3)} ${hex(r[i])}${i % 4 === 3 ? '\n' : '  '}`;
    txt += `cpsr ${hex(cpsr)} [${flags}] ${MODES[cpsr & 0x1F] || '?'}   spsr ${hex(r[17])}\n`;
    txt += `frame ${con.frameCount()}  cycles ${con.cycles()}`;
    regsBox.textContent = txt;

    const pc = r[15];
    const thumb = con.thumb();
    const start = disasmAddr ?? (pc - (thumb ? 16 : 32));
    disBox.replaceChildren();
    for (const line of con.disasm(start >>> 0, 24, thumb ? 1 : 0)) {
      const d = el('div', { class: `dbg-line ${line.addr === pc ? 'pc' : ''} ${breakpoints.has(line.addr) ? 'bp' : ''}`, title: 'Click to toggle breakpoint' },
        `${hex(line.addr)}  ${line.op.padEnd(8)}  ${line.text}`);
      d.onclick = () => {
        if (breakpoints.has(line.addr)) { breakpoints.delete(line.addr); con.breakpoint(line.addr, false); }
        else { breakpoints.add(line.addr); con.breakpoint(line.addr, true); }
        refresh();
      };
      disBox.append(d);
    }

    memBox.replaceChildren();
    const bytes = con.readMem(memAddr, 256);
    for (let row = 0; row < 16; row++) {
      const line = el('div', { class: 'dbg-line' }, hex(memAddr + row * 16) + '  ');
      let ascii = '';
      for (let c = 0; c < 16; c++) {
        const i = row * 16 + c;
        const b = bytes[i];
        const span = el('span', { class: 'hex-byte', title: 'Click to edit' }, hex(b, 2));
        span.onclick = () => {
          const v = prompt(`New value for ${hex(memAddr + i)}`, hex(b, 2));
          if (v !== null) { con.poke(memAddr + i, parseInt(v, 16) & 0xFF, 1); refresh(); }
        };
        line.append(span, ' ');
        ascii += b >= 0x20 && b < 0x7f ? String.fromCharCode(b) : '.';
      }
      line.append(' ' + ascii);
      memBox.append(line);
    }
    const s = player.stat;
    statsBox.textContent = `fps ${s.fps.toFixed(1)}  speed ${s.speed.toFixed(0)}%  emulation ${s.emu.toFixed(2)} ms/frame\n` +
      `audio buffer ${player.audio.bufferedMs().toFixed(0)} ms  link transfers ${con.linkTransfers()}\n` +
      `renderer ${player.main.renderer.webgl ? 'WebGL2' : 'Canvas 2D'}  rewind ${con.rewindSeconds().toFixed(1)} s`;
  };

  const layerBoxes = ['BG0', 'BG1', 'BG2', 'BG3', 'OBJ'].map((n) => el('label', {}, el('input', { type: 'checkbox', checked: true }), n));
  const chanBoxes = ['Sq1', 'Sq2', 'Wave', 'Noise', 'FIFO A', 'FIFO B'].map((n) => el('label', {}, el('input', { type: 'checkbox', checked: true }), n));
  const applyMasks = () => {
    con.setLayerMask(layerBoxes.reduce((m, l, i) => m | (l.firstChild.checked ? 1 << i : 0), 0));
    con.setChannelMask(chanBoxes.reduce((m, l, i) => m | (l.firstChild.checked ? 1 << i : 0), 0));
  };
  [...layerBoxes, ...chanBoxes].forEach((l) => { l.firstChild.onchange = applyMasks; });

  const watchStart = el('input', { type: 'text', placeholder: 'start', style: { width: '100px' }, class: 'mono' });
  const watchEnd = el('input', { type: 'text', placeholder: 'end (opt.)', style: { width: '100px' }, class: 'mono' });
  const watchKind = el('select', {}, el('option', { value: 'w' }, 'write'), el('option', { value: 'r' }, 'read'), el('option', { value: 'rw' }, 'read/write'));

  const ctl = (label, fn) => el('button', { type: 'button', onclick: () => { fn(); refresh(); } }, label);
  const body = el('div', {},
    el('div', { class: 'row' },
      ctl(player.paused ? '▶ Continue' : '⏸ Pause', () => player.setPaused(!player.paused)),
      ctl('Step', () => { player.setPaused(true); con.step(); player.present(); }),
      ctl('Step ×10', () => { player.setPaused(true); for (let i = 0; i < 10; i++) con.step(); player.present(); }),
      ctl('Frame', () => { player.setPaused(true); player.step(true); player.present(); }),
      ctl('Clear breakpoints', () => { breakpoints.clear(); con.clearBreakpoints(); }),
      el('span', {}, ' Disasm @ '), disInput, ctl('Go', () => { disasmAddr = disInput.value ? parseInt(disInput.value, 16) : null; })),
    el('div', { class: 'debug-grid' },
      el('div', {}, el('h3', {}, 'Registers'), regsBox, el('h3', {}, 'Statistics'), statsBox),
      el('div', {}, el('h3', {}, 'Disassembly'), disBox)),
    el('h3', {}, 'Memory'),
    el('div', { class: 'row' }, memInput, ctl('Go', () => { memAddr = parseInt(memInput.value, 16) >>> 0; }),
      ...REGIONS.map(([n, a]) => ctl(n, () => { memAddr = a; memInput.value = hex(a); })),
      ctl('◀', () => { memAddr = (memAddr - 256) >>> 0; memInput.value = hex(memAddr); }),
      ctl('▶', () => { memAddr = (memAddr + 256) >>> 0; memInput.value = hex(memAddr); })),
    memBox,
    el('h3', {}, 'Watchpoints'),
    el('div', { class: 'row' }, watchStart, watchEnd, watchKind,
      ctl('Add', () => {
        const s = parseInt(watchStart.value, 16);
        if (Number.isNaN(s)) return toast('Enter a hex address', 'error');
        const e = watchEnd.value ? parseInt(watchEnd.value, 16) : s;
        con.watchpoint(s, e, watchKind.value.includes('r'), watchKind.value.includes('w'));
        toast('Watchpoint added – execution pauses when it triggers');
      }),
      ctl('Clear', () => con.clearWatchpoints())),
    el('h3', {}, 'Layers & audio channels'),
    el('div', { class: 'row' }, ...layerBoxes), el('div', { class: 'row' }, ...chanBoxes));

  refresh();
  const timer = setInterval(() => { if (!player.paused) refresh(); }, 250);
  const onStop = (e) => { disasmAddr = null; refresh(); toast(`Stopped at ${e.detail.reason}`); };
  player.addEventListener('debugstop', onStop);
  return showDialog('Debugger', body, { wide: true, onClose: () => { clearInterval(timer); player.removeEventListener('debugstop', onStop); } });
}
