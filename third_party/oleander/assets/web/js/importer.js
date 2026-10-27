// Page-side client of the import/scrape worker.
import { toast } from './ui.js';

class Importer extends EventTarget {
  constructor() {
    super();
    this.worker = null;
    this.jobs = new Map();
    this.nextId = 1;
    this.progressEl = null;
  }

  ensure() {
    if (this.worker) return this.worker;
    this.worker = new Worker(new URL('./worker.js', import.meta.url), { type: 'module' });
    this.worker.onmessage = (e) => this.onMessage(e.data);
    this.worker.onerror = (e) => { console.error('import worker', e); toast('Import worker failed: ' + (e.message || 'error'), 'error'); };
    return this.worker;
  }

  setPlaying(value) {
    this.ensure().postMessage({ type: 'playing', value });
  }

  onMessage(m) {
    const job = this.jobs.get(m.jobId);
    switch (m.type) {
      case 'progress':
        this.showProgress(m);
        job?.onProgress?.(m);
        break;
      case 'added':
      case 'updated':
        this.dispatchEvent(new CustomEvent('changed', { detail: m }));
        break;
      case 'error':
        if (job) job.errors.push(m.name ? `${m.name}: ${m.message}` : m.message);
        break;
      case 'done':
        this.hideProgress();
        if (job) { this.jobs.delete(m.jobId); job.resolve({ ...m, errors: job.errors }); }
        this.dispatchEvent(new CustomEvent('done', { detail: m }));
        break;
    }
  }

  run(msg, onProgress) {
    const jobId = this.nextId++;
    return new Promise((resolve) => {
      this.jobs.set(jobId, { resolve, onProgress, errors: [] });
      this.ensure().postMessage({ ...msg, jobId });
    });
  }

  /// items: [{ file } | { name, data } | { name, url, size, lastModified, source }]
  async import(items) {
    const norm = items.map((it) => (it instanceof File ? { name: it.name, file: it } : it));
    const res = await this.run({ type: 'import', items: norm });
    if (res.errors.length) toast(`${res.errors.length === 1 ? 'A file' : res.errors.length + ' files'} could not be imported: ${res.errors[0]}`, 'error');
    return res;
  }

  scrape(ids = null, force = false) {
    return this.run({ type: 'scrape', ids, force });
  }

  showProgress(m) {
    if (m.total <= 1 && !m.scrape) return;
    if (!this.progressEl) {
      this.progressEl = document.createElement('div');
      this.progressEl.className = 'import-progress';
      document.body.append(this.progressEl);
    }
    const pct = m.total ? Math.round((m.done / m.total) * 100) : 0;
    this.progressEl.innerHTML = `<span>${m.scrape ? 'Fetching covers' : 'Adding games'} ${m.done}/${m.total}</span><i style="width:${pct}%"></i>`;
  }

  hideProgress() {
    this.progressEl?.remove();
    this.progressEl = null;
  }
}

export const importer = new Importer();
