// Builds the HTML page that drives the REAL reference renderer (chimahon's renderer.js + base.css)
// with a dictionary payload, so headless Chrome can screenshot what the WebView would show.
//
// Usage: node build_page.mjs --payload=<payload.json> --out=<page.html> [--dict-css=<path>]
//        [--font="MS PGothic"] [--font-file=...] [--width=461] [--font-size=14]
//
// The payload is the exact schema DictionaryPayloadBuilder.kt emits (config + inline results), so
// this exercises the same code path the WebView does - including renderer.js's own CSS scoping,
// which is a large part of the behaviour under test.

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const REPO = resolve(process.argv[1], '..', '..', '..');
const ASSETS = resolve(REPO, 'chimahon', 'src', 'main', 'assets', 'dictionary');

const opt = Object.fromEntries(
  process.argv.slice(2).filter((a) => a.startsWith('--')).map((a) => {
    const i = a.indexOf('=');
    return i < 0 ? [a.slice(2), 'true'] : [a.slice(2, i), a.slice(i + 1)];
  }),
);

const payloadPath = opt.payload;
const outPath = opt.out;
if (!payloadPath || !outPath) {
  console.error('usage: node build_page.mjs --payload=<p.json> --out=<page.html> [--dict-css=<f>]');
  process.exit(2);
}

const baseCss = readFileSync(resolve(ASSETS, 'base.css'), 'utf8');
const rendererJs = readFileSync(resolve(ASSETS, 'renderer.js'), 'utf8');
const kanjiJs = readFileSync(resolve(ASSETS, 'kanji-renderer.js'), 'utf8');

const payload = JSON.parse(readFileSync(payloadPath, 'utf8'));
// Per-dictionary stylesheets come from the dictionary's own style.json in the real app; here they
// are supplied explicitly so both renderers see identical CSS.
if (opt['dict-css']) {
  const extra = readFileSync(resolve(opt['dict-css']), 'utf8');
  payload.styles = [{ dictName: payload.dictNameForStyles || '', styles: extra }];
}

// MS PGothic is one face inside the msgothic.ttc collection, which @font-face cannot address by
// index, so the oracle names the installed family instead. Naming the family also matters for
// `line-height`: Compose clamps the line pitch down to the font's natural line height while CSS
// honours a line-height smaller than it. MS PGothic's natural height is 1.0em, so both engines
// resolve `line-height: 1.4` to 1.4em and the comparison measures the renderer rather than that
// engine difference.
const fontName = opt.font || 'MS PGothic';
const fontFile = opt['font-file'] || '';
const width = Number(opt.width || 461);
// The gloss font size is a property of the case, so the page has to be told it: without this the
// reference always laid out at the base.css default while Compose used the case's value, and any
// probe that varied the font size was silently comparing two different renderings.
const fontSize = Number(opt['font-size'] || 14);

// The app injects its ColorScheme in DictionaryBootstrapHtml. The fixture has no
// app theme, so supply a fixed dark palette instead of inheriting light defaults.
const themeOverride = payload.isDark ? `
:root[data-theme="dark"] {
  --bg: #000000;
  --fg: #e3e3e3;
  --secondary: #bdbdbd;
  --border: rgba(255, 255, 255, 0.10);
  --hover-bg: rgba(255, 255, 255, 0.07);
  --tab-bg: #0d0d0d;
}
` : '';

const fontOverride = `
${fontFile ? `@font-face { font-family: 'RefCJK'; src: url('file:///${fontFile.replace(/\\/g, '/')}'); }` : ''}
html, body, #entries, #entries * { font-family: ${fontFile ? "'RefCJK'" : `'${fontName.replace(/'/g, "\\'")}'`} !important; }
html, body { margin: 0; padding: 0; background: ${payload.isDark ? '#000000' : '#ffffff'}; }
  #entries { width: ${width}px; font-size: ${fontSize}px; }

`;

const html = `<!doctype html>
<html data-theme="${payload.isDark ? 'dark' : 'light'}" data-chima-eink-mode="false" data-chima-paginated-scrolling="false" data-chima-scroll-behavior="auto" data-compact-glossary="false" data-glossary-layout-mode="default">
<head>
<meta charset="utf-8">
<style>${baseCss}</style>
<style id="dynamic-theme">${themeOverride}</style>
<style id="font-override">${fontOverride}</style>
<style id="dictionary-styles"></style>
<style id="chima-custom-css"></style>
<script>
  window.AnkiBridge = { addToAnki: function(){}, openInAnki: function(){} };
</script>
</head>
<body>
<main id="entries" class="entries"></main>
<script>${rendererJs.replace(/<\/script/gi, '<\\/script')}</script>
<script>${kanjiJs.replace(/<\/script/gi, '<\\/script')}</script>
<script>
  const PAYLOAD = ${JSON.stringify(payload)};
  window.__rendered = false;
  try {
    window.DictionaryRenderer.render(PAYLOAD);
    window.__rendered = true;
  } catch (e) {
    window.__renderError = String(e && e.stack || e);
  }
  // Expose the geometry the comparison needs: the gloss content box for every structured-content
  // span, in CSS px, relative to the document.
  // Whether the reference underlines links at all, and what it resolves to. The UA stylesheet
  // underlines a:link, so the answer decides whether the Compose renderer should decorate a link
  // or leave it plain - guessing this wrong adds or drops an underline on every link in a gloss.
  window.__tagHistogram = () => {
    const root = document.querySelector('.structured-content, .gloss-content') || document.body;
    const counts = {};
    for (const el of root.querySelectorAll('*')) {
      const key = el.tagName.toLowerCase() + (el.dataset.scContent ? '[' + el.dataset.scContent.split(' ')[0] + ']' : '');
      counts[key] = (counts[key] || 0) + 1;
    }
    const sample = Array.from(root.querySelectorAll('*'))
      .filter((e) => e.hasAttribute('href') || e.dataset.href || e.dataset.scContent === 'link')
      .slice(0, 3)
      .map((e) => e.outerHTML.slice(0, 160));
    return { counts, sample };
  };
  window.__linkMetrics = () => {
    const as = Array.from(document.querySelectorAll('#entries a, .gloss-content a')).slice(0, 5);
    return as.map((a) => {
      const cs = getComputedStyle(a);
      return {
        href: (a.getAttribute('href') || '').slice(0, 40),
        isLink: a.matches(':link'),
        decorationLine: cs.textDecorationLine,
        decorationThickness: cs.textDecorationThickness,
        decorationColor: cs.textDecorationColor,
        color: cs.color,
        inlineStyle: a.getAttribute('style') || '',
      };
    });
  };
  window.__glossRects = () => {
    return Array.from(document.querySelectorAll('.structured-content')).map((el, i) => {
      const r = el.getBoundingClientRect();
      return { index: i, x: r.x, y: r.y, width: r.width, height: r.height };
    });
  };
  // The browser is the reference implementation, so read the values it actually resolved instead of
  // inferring them from pixels: list indentation, marker placement and the margins that separate
  // list items are exactly what the Compose renderer has to reproduce, and guessing them from band
  // positions produced a 2px-per-item drift that was impossible to attribute.
  // Glyph advance widths for the SAME strings the Compose font probe measures. If the two engines
  // disagree per character, every wrap point and therefore every pixel score is decided by that
  // mismatch rather than by the renderer, so it is worth measuring rather than inferring.
  // Exact line break indices, per line, for the gloss text. Comparing the character offset at which
  // each engine breaks is far more precise than comparing ink extents: it separates "wrapped a
  // different amount of text" from "drew the same text at a different x".
  // Baseline offset of every vertical-align:super/sub run, in em of the PARENT font size.
  // CSS 2.1 leaves the super/sub offsets to the UA, so the value cannot be looked up in a spec
  // and must be measured against the reference renderer. A zero-width, zero-height inline-block sits
  // with its bottom margin edge on the baseline, so its rect top IS the baseline y; inserting one is
  // layout-neutral (no width, no height) and can be removed again.
  // The reference renderer's RESOLVED style for every element in the gloss. Comparing this against what
  // the Compose renderer resolves from the same CSS is far more precise than comparing pixels: it names
  // the exact property that disagrees, and it is a conformance check against CSS rather than a guess.
  window.__computedStyles = () => {
    const root = document.querySelector('.structured-content, .gloss-content');
    if (!root) return [];
    const props = [
      'display', 'font-size', 'line-height', 'font-weight', 'font-style', 'color',
      'margin-top', 'margin-bottom', 'margin-left', 'margin-right',
      'padding-top', 'padding-bottom', 'padding-left', 'padding-right',
      'vertical-align', 'list-style-type', 'list-style-position', 'text-align',
      'text-decoration-line', 'font-style', 'background-color', 'border-top-width',
      'font-family',
    ];
    const seen = new Map();
    let i = 0;
    const walk = (el) => {
      const cs = getComputedStyle(el);
      const o = {
        i: i++,
        tag: el.tagName.toLowerCase(),
        data: el.dataset ? (el.dataset.scContent || el.dataset.content || '') : '',
        text: (el.textContent || '').trim().slice(0, 14),
      };
      for (const p of props) o[p] = cs.getPropertyValue(p);
      // Only the first few of each (tag, data) shape: a gloss repeats identical list items, and a
      // per-element dump of a 150-item gloss is unreadable.
      const shape = o.tag + '|' + o.data + '|' + o.display;
      if (!seen.has(shape)) { seen.set(shape, o); }
      for (const c of el.children) walk(c);
    };
    for (const c of root.children) walk(c);
    return Array.from(seen.values());
  };
  window.__baselineShifts = () => {
    const root = document.querySelector('.structured-content, .gloss-content');
    if (!root) return [];
    const out = [];
    for (const el of root.querySelectorAll('*')) {
      const va = getComputedStyle(el).verticalAlign;
      if (va !== 'super' && va !== 'sub') continue;
      const parentSize = parseFloat(getComputedStyle(el.parentElement).fontSize) || 0;
      if (!parentSize) continue;
      const ownSize = parseFloat(getComputedStyle(el).fontSize) || parentSize;
      const probe = document.createElement('span');
      probe.style.cssText =
        'display:inline-block;width:0;height:0;vertical-align:baseline;overflow:hidden';
      const after = document.createRange();
      after.setStart(el, 0);
      // Put the probe inside the element, at its start, so it shares the element's own vertical-align.
      el.insertBefore(probe, el.firstChild);
      const innerBaseline = probe.getBoundingClientRect().top;
      probe.remove();
      const outer = document.createElement('span');
      outer.style.cssText =
        'display:inline-block;width:0;height:0;vertical-align:baseline;overflow:hidden';
      el.insertAdjacentElement('afterend', outer);
      const outerBaseline = outer.getBoundingClientRect().top;
      outer.remove();
      out.push({
        align: va,
        em: +((innerBaseline - outerBaseline) / parentSize).toFixed(4),
        px: +(innerBaseline - outerBaseline).toFixed(2),
        parentFontSize: parentSize,
        ownFontSize: ownSize,
        text: (el.textContent || '').slice(0, 12),
      });
    }
    return out;
  };
  window.__lineBreaks = () => {
    const root = document.querySelector('.structured-content, .gloss-content');
    if (!root) return [];
    // Walk the whole gloss once, in document order. Walking each block element separately would count
    // a line once per element it spans, so deeply nested glosses would report several times the real
    // number of lines and the line-count comparison against Compose would be meaningless.
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const chars = [];
    let n;
    while ((n = walker.nextNode())) {
      for (let i = 0; i < n.data.length; i++) {
        if (/\s/.test(n.data[i])) continue;
        const r = document.createRange();
        r.setStart(n, i);
        r.setEnd(n, i + 1);
        const box = r.getBoundingClientRect();
        if (!box.width && !box.height) continue;
        chars.push({ top: box.top, left: box.left, right: box.right, ch: n.data[i] });
      }
    }
    if (!chars.length) return [];
    // Group by vertical position: a new line starts where the baseline jumps. The tolerance has to
    // absorb a vertical-align:super run - a 0.7em note raised 0.4em sits several px above its
    // neighbours while still being on the SAME line - but must stay well under the line pitch
    // (1.4 * 14 = 19.6px) so real lines never merge.
    const lines = [];
    let cur = null;
    for (const c of chars) {
      if (!cur || Math.abs(c.top - cur.top) > 6) {
        cur = { top: c.top, left: c.left, right: c.right, start: c.ch, n: 0 };
        lines.push(cur);
      }
      cur.right = Math.max(cur.right, c.right);
      cur.start = cur.n === 0 ? c.ch : cur.start;
      cur.end = c.ch;
      cur.n += 1;
    }
    return [{
      chars: chars.length,
      lines: lines.map((l) => ({
        left: +l.left.toFixed(2), right: +l.right.toFixed(2),
        start: l.start, end: l.end, n: l.n,
      })),
    }];
  };
  window.__textWidths = (strings) => {
    // Clone the style off an element that actually renders gloss text, not off the container:
    // the container's own font-size can differ from the text's, which silently halves the widths.
    const src = document.querySelector('.gloss-content .structured-content, .structured-content, .gloss-content')
      || document.body;
    const s = getComputedStyle(src);
    const host = document.createElement('div');
    host.style.cssText = 'position:absolute;visibility:hidden;white-space:pre;left:-9999px;top:0';
    host.style.fontFamily = s.fontFamily;
    host.style.fontSize = s.fontSize;
    host.style.fontWeight = s.fontWeight;
    host.style.letterSpacing = s.letterSpacing;
    document.body.appendChild(host);
    const out = strings.map((t) => {
      host.textContent = t;
      return { text: t, width: host.getBoundingClientRect().width, fontSize: s.fontSize };
    });
    host.remove();
    return out;
  };
  window.__listMetrics = () => {
    const pick = (el, props) => {
      const cs = getComputedStyle(el);
      const out = {};
      for (const p of props) out[p] = cs.getPropertyValue(p);
      return out;
    };
    const box = ['margin-top', 'margin-bottom', 'margin-left', 'padding-left', 'padding-top',
      'list-style-type', 'list-style-position', 'font-size', 'line-height', 'display'];
    const ul = document.querySelector('.structured-content ul, .structured-content ol');
    const items = Array.from(document.querySelectorAll('.structured-content li')).slice(0, 4);
    return {
      ul: ul ? { ...pick(ul, box), rect: ul.getBoundingClientRect().toJSON() } : null,
      items: items.map((li) => {
        const r = li.getBoundingClientRect();
        // Where the marker's box sits relative to the item's content box.
        const markerProbe = document.createElement('span');
        markerProbe.textContent = '\\u25AA';
        li.insertBefore(markerProbe, li.firstChild);
        const mr = markerProbe.getBoundingClientRect();
        markerProbe.remove();
        return {
          ...pick(li, box),
          rect: { x: r.x, y: r.y, w: r.width, h: r.height },
          markerX: mr.x - r.x,
          markerW: mr.width,
        };
      }),
    };
  };
  // The rect to compare against: the union of every gloss block that actually has content,
  // PLUS the headword row.
  //
  // The headword selector used to be excluded, and so was the Compose side (renderCase drew only
  // the gloss body). That silently cropped furigana out of every comparison: the headword's
  // reading size, its ruby column and its row height were all unverifiable - and the row's height
  // is the vertical origin for every band below it. Both sides now include it.
  //
  // A single querySelector('.gloss-content') is not safe: a lookup can produce several gloss
  // blocks and the first one is often an empty slot (or a collapsed/hidden one) that measures 1px
  // tall, which silently reduces the whole comparison to one blank row.
  window.__compareRect = () => {
    const blocks = Array.from(document.querySelectorAll('.gloss-content, .structured-content, .headword'))
      .filter((el) => (el.innerText || '').trim().length > 0);
    if (blocks.length === 0) return null;
    let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
    for (const el of blocks) {
      const r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) continue;
      x0 = Math.min(x0, r.left); y0 = Math.min(y0, r.top);
      x1 = Math.max(x1, r.right); y1 = Math.max(y1, r.bottom);
    }
    if (!isFinite(x0)) return null;
    return { x: x0, y: y0, width: x1 - x0, height: y1 - y0, blocks: blocks.length };
  };
  window.__domSummary = () => {
    const root = document.getElementById('entries');
    const cs = (sel) => {
      const el = root.querySelector(sel);
      if (!el) return null;
      const s = getComputedStyle(el);
      return { fontSize: s.fontSize, lineHeight: s.lineHeight, color: s.color, family: s.fontFamily.slice(0, 40) };
    };
    return {
      rendered: window.__rendered,
      error: window.__renderError || null,
      entriesHTMLLength: root.innerHTML.length,
      glossCount: root.querySelectorAll('.structured-content').length,
      body: cs('body'), gloss: cs('.structured-content'),
    };
  };
</script>
</body>
</html>
`;

writeFileSync(outPath, html, 'utf8');
console.log(JSON.stringify({ ok: true, out: outPath, bytes: html.length, width, font: fontName }));
