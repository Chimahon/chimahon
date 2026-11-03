// Renders HTML files to PNG with headless Chrome via the DevTools Protocol, optionally clipped to a
// CSS selector. Uses only Node built-ins (Node 22 ships global WebSocket + fetch), so there is
// nothing to npm install.
//
// Single file:
//   node render.mjs <file.html> <out.png> [--selector=<css>] [--width=461] [--height=900] [--eval=<js>]
//
// Batch (one Chrome process for every job - ~40x faster and avoids Windows locking
// DevToolsActivePort when a previous instance has not fully exited):
//   node render.mjs --batch=<jobs.json>
//   jobs.json: [{ "id": "...", "html": "...", "out": "...", "width": 461, "height": 1200,
//                 "selector": ".structured-content", "eval": "..." }]

import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';

const argv = process.argv.slice(2);
const opt = Object.fromEntries(
  argv.filter((a) => a.startsWith('--')).map((a) => {
    const i = a.indexOf('=');
    return i < 0 ? [a.slice(2), 'true'] : [a.slice(2, i), a.slice(i + 1)];
  }),
);

const jobs = opt.batch
  ? JSON.parse(readFileSync(opt.batch, 'utf8')).map((j) => ({
      id: j.id || 'job',
      html: j.html,
      out: j.out,
      width: Number(j.width || 461),
      height: Number(j.height || 1200),
      selector: j.selector || null,
      rectExpr: j.rectExpr || null,
      eval: j.eval || null,
      dsf: j.dsf || 1,
    }))
  : [{
      id: 'single',
      html: argv[0],
      out: argv[1],
      width: Number(opt.width || 461),
      height: Number(opt.height || 900),
      selector: opt.selector || null,
      rectExpr: opt['rect-expr'] || null,
      eval: opt.eval || null,
    }];

for (const j of jobs) {
  if (!j.html || !j.out) {
    console.error('each job needs html and out');
    process.exit(2);
  }
  if (!existsSync(CHROME)) throw new Error(`chrome not found at ${CHROME}`);
  if (!existsSync(j.html)) throw new Error(`html not found: ${j.html}`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Resolves once Chrome writes its DevTools port into the user-data dir. */
async function waitForPort(userDataDir, timeoutMs = 30000) {
  const portFile = join(userDataDir, 'DevToolsActivePort');
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    try {
      const lines = readFileSync(portFile, 'utf8').split('\n');
      if (lines.length >= 2 && lines[0].trim()) {
        const port = Number(lines[0].trim());
        if (Number.isFinite(port) && port > 0) return port;
      }
    } catch {
      // Chrome can hold the file open briefly; retry.
    }
    await sleep(50);
  }
  throw new Error('timed out waiting for Chrome DevTools port');
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.events = [];
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data);
      if (msg.id !== undefined && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(msg.error.message));
        else resolve(msg.result);
      } else if (msg.method) {
        this.events.push(msg);
      }
    });
  }

  send(method, params = {}) {
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  /** Polls an expression until truthy, so we never screenshot a half-rendered page. */
  async waitFor(expression, timeoutMs = 20000, label = expression) {
    const deadline = Date.now() + timeoutMs;
    let last = null;
    while (Date.now() < deadline) {
      const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
      last = r.exceptionDetails ? r.exceptionDetails.text : null;
      if (r.result && r.result.value) return r.result.value;
      await sleep(60);
    }
    throw new Error(`timed out waiting for: ${label}${last ? ' (last error: ' + last + ')' : ''}`);
  }

  close() {
    try { this.ws.close(); } catch { /* already closed */ }
  }
}

function connect(url) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    ws.addEventListener('open', () => resolve(new Cdp(ws)));
    ws.addEventListener('error', () => reject(new Error('ws error')));
  });
}

const userDataDir = mkdtempSync(join(tmpdir(), 'dict-oracle-'));
const maxW = Math.max(...jobs.map((j) => j.width));
const maxH = Math.max(...jobs.map((j) => j.height));

const chrome = spawn(CHROME, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  '--disable-extensions',
  '--disable-dev-shm-usage',
  '--allow-file-access-from-files',
  '--force-device-scale-factor=1',
  '--font-render-hinting=none',
  // Grayscale antialiasing. Without these Chrome still uses LCD/subpixel AA for text, which paints
  // RGB fringes on every glyph edge; those fringes alone move the pixel similarity by ~10% and have
  // nothing to do with the renderer, so they must be off before the comparison means anything.
  '--disable-lcd-text',
  '--disable-font-subpixel-positioning',
  '--force-color-profile=srgb',
  `--user-data-dir=${userDataDir}`,
  `--window-size=${maxW},${maxH}`,
  '--remote-debugging-port=0',
  'about:blank',
], { stdio: ['ignore', 'pipe', 'pipe'] });

let chromeErr = '';
chrome.stderr.on('data', (d) => { chromeErr += d.toString(); });

const results = [];
let exitCode = 0;
try {
  const port = await waitForPort(userDataDir);

  for (const job of jobs) {
    const entry = { id: job.id, out: job.out };
    let cdp = null;
    try {
      const fileUrl = 'file:///' + job.html.replace(/\\/g, '/');
      // Chrome >= 111 requires PUT for /json/new.
      const target = await (await fetch(
        `http://127.0.0.1:${port}/json/new?${encodeURIComponent(fileUrl)}`,
        { method: 'PUT' },
      )).json();

      cdp = await connect(target.webSocketDebuggerUrl);
      await cdp.send('Page.enable');
      await cdp.send('Runtime.enable');
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        // deviceScaleFactor must match the Compose side's density: CSS px layout is identical at any
      // scale, but the raster is not, and comparing a 1x browser against a 2x Compose render would
      // compare different images rather than different engines.
      width: job.width, height: job.height, deviceScaleFactor: job.dsf || 1, mobile: false,
      });

      await cdp.waitFor("document.readyState === 'complete'", 20000, 'readyState');
      await cdp.waitFor("document.fonts.status === 'loaded'", 20000, 'fonts');

      if (job.eval) {
        const r = await cdp.send('Runtime.evaluate', {
          expression: job.eval, returnByValue: true, awaitPromise: true,
        });
        entry.probe = r.result?.value;
        if (r.exceptionDetails) {
          entry.probe = { __error: r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description || '') };
        }
      }

      let clip = null;
      // --rect-expr takes precedence over --selector: it lets the page compute the rect itself
      // (e.g. a union over several blocks), which a single querySelector cannot do.
      if (job.rectExpr || job.selector) {
        const expr = job.rectExpr
          // Require a real block, not a 1px sliver: right after navigation the gloss has not been
          // laid out yet and measures 1px tall, which would silently reduce the whole comparison
          // to a single blank row.
          ? `(() => { const r = ${job.rectExpr}; return r && r.width >= 8 && r.height >= 8 ? r : null; })()`
          : `(() => { const el = document.querySelector(${JSON.stringify(job.selector)});
              if (!el) return null;
              const r = el.getBoundingClientRect();
              if (r.width < 1 || r.height < 1) return null;
              return { x: r.x, y: r.y, width: r.width, height: r.height }; })()`;
        const rect = await cdp.waitFor(expr, 20000, job.rectExpr || `selector ${job.selector}`);
        clip = {
          x: Math.max(0, Math.floor(rect.x)),
          y: Math.max(0, Math.floor(rect.y)),
          width: Math.ceil(rect.width),
          height: Math.ceil(rect.height),
          scale: 1,
        };
        entry.rectBlocks = rect.blocks;
      }

      const shot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        ...(clip ? { clip, captureBeyondViewport: true } : {}),
      });
      writeFileSync(job.out, Buffer.from(shot.data, 'base64'));
      entry.ok = true;
      entry.clip = clip;
      entry.consoleErrors = cdp.events
        .filter((e) => e.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(e.params?.type))
        .map((e) => (e.params.args || []).map((a) => a.value ?? a.description ?? '').join(' '))
        .slice(0, 6);
    } catch (err) {
      entry.ok = false;
      entry.error = String((err && err.message) || err);
    } finally {
      if (cdp) cdp.close();
    }
    results.push(entry);
  }
} catch (err) {
  exitCode = 1;
  results.push({ id: 'fatal', ok: false, error: String((err && err.message) || err), chromeErr: chromeErr.slice(-2000) });
} finally {
  try { chrome.kill(); } catch { /* already gone */ }
}

console.log(JSON.stringify({ results }, null, 1));
if (results.some((r) => !r.ok)) exitCode = 1;
process.exit(exitCode);
