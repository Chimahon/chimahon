(function () {
  'use strict';

  const DEBUG = false;
  const DEBUG_VERBOSE_NODES = false;
  const SCOPED_STYLE_CACHE_LIMIT = 24;
  const scopedStyleCache = new Map();

  // ── Recursive lookup state ────────────────────────────────────────────────
  const CHIMA_SCHEME = 'chima:';
  const MAX_SCAN_CHARS = 24;   // chars to extract forward from tap point

  let _lastSelection = '';
  let _pendingPopupSelection = '';
  let _selectedDictionaries = {}; // entryIndex -> dictName
  let _wordAudioEnabled = true;
  let _autoplayGuard = false;
  let _listenersInstalled = false;
  let _lastAnkiDupAction = -1; // -1 = unknown/not set
  let _dictionaryMediaObserver = null;
  const _prewarmedFontFaces = typeof WeakSet === 'function' ? new WeakSet() : null;
  const HAS_NATIVE_SCOPE = (() => {
    try {
      if (!window.CSS || !CSS.supports) return false;
      return CSS.supports('selector(:scope)') && (() => {
        try {
          const sheet = new CSSStyleSheet();
          sheet.replaceSync('@scope (.test) { .a { color: red; } }');
          return sheet.cssRules.length > 0;
        } catch (e) {
          return false;
        }
      })();
    } catch (e) {
      return false;
    }
  })();

  const HAS_CONSTRUCTED_SHEETS = (() => {
    try {
      const sheet = new CSSStyleSheet();
      sheet.replaceSync('body { color: red; }');
      return sheet.cssRules.length > 0;
    } catch (e) {
      return false;
    }
  })();

  function toDebugText(value) {
    if (typeof value === 'string') return value;
    if (value === null) return 'null';
    if (typeof value === 'undefined') return 'undefined';
    if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    try {
      return JSON.stringify(value);
    } catch (e) {
      return String(value);
    }
  }

  function debugLog(...args) {
    if (!DEBUG) return;
    console.log('[DictionaryRenderJS][debug]', args.map(toDebugText).join(' '));
  }

  function debugWarn(...args) {
    if (!DEBUG) return;
    console.warn('[DictionaryRenderJS][debug]', args.map(toDebugText).join(' '));
  }

  function sanitizeDictionaryStyles(cssText, dictName) {
    if (!cssText || typeof cssText !== 'string') return '';
    let processed = cssText;

    // Fix potential body tag leaks — remap to the content root class.
    processed = processed.replace(/^\s*body\s*\{/gm, '.dict-content-root {');

    return processed;
  }


  function splitSelectorList(selectorText) {
    const out = [];
    let current = '';
    let parenDepth = 0;
    let bracketDepth = 0;
    let quote = null;

    for (let i = 0; i < selectorText.length; i++) {
      const ch = selectorText[i];
      const prev = i > 0 ? selectorText[i - 1] : '';

      if (quote) {
        current += ch;
        if (ch === quote && prev !== '\\') quote = null;
        continue;
      }

      if (ch === '"' || ch === "'") {
        quote = ch;
        current += ch;
        continue;
      }

      if (ch === '(') parenDepth++;
      if (ch === ')') parenDepth = Math.max(0, parenDepth - 1);
      if (ch === '[') bracketDepth++;
      if (ch === ']') bracketDepth = Math.max(0, bracketDepth - 1);

      if (ch === ',' && parenDepth === 0 && bracketDepth === 0) {
        if (current.trim()) out.push(current.trim());
        current = '';
        continue;
      }

      current += ch;
    }

    if (current.trim()) out.push(current.trim());
    return out;
  }

  function prefixSelectorList(selectorText, scopeSelector) {
    return splitSelectorList(selectorText)
      .map((sel) => {
        const trimmed = sel.trim();

        // Handle modern CSS nesting '&' selector
        if (trimmed.includes('&')) {
          return trimmed.replace(/&/g, scopeSelector);
        }

        // Dictionary CSS often defines custom properties at :root/body/html.
        // Prefixing as "[scope] :root" is invalid, so inject scope right after
        // the root/html/body head selector.
        const rootHeadMatch = trimmed.match(/^((?:\:root|html|body)(?:[\w\-\[\]="'().:#]*)?)([\s\S]*)$/i);
        if (rootHeadMatch) {
          const head = rootHeadMatch[1];
          const tail = rootHeadMatch[2] || '';

          if (!tail.trim() && /^(?:\:root|html|body)$/i.test(head)) {
            return scopeSelector;
          }

          return `${head} ${scopeSelector}${tail}`;
        }

        return `${scopeSelector} ${trimmed}`;
      })
      .join(', ');
  }

  function combineNestedSelectorList(selectorText, parentSelectors) {
    const parents = Array.isArray(parentSelectors) ? parentSelectors.filter(Boolean) : [];
    if (parents.length === 0) return selectorText;
    return splitSelectorList(selectorText)
      .flatMap((sel) => {
        const trimmed = sel.trim();
        if (!trimmed) return [];
        return parents.map((parent) => {
          if (trimmed.includes('&')) {
            return trimmed.replace(/&/g, parent);
          }
          return `${parent} ${trimmed}`;
        });
      })
      .join(', ');
  }

  function getCachedScopedStyle(dictName, css, scopeSelector) {
    const cacheKey = `${dictName}\u0000${css}\u0000${HAS_NATIVE_SCOPE}`;
    if (scopedStyleCache.has(cacheKey)) {
      return {value: scopedStyleCache.get(cacheKey), cacheHit: true};
    }

    const value = buildDictionaryScopedStyle(css, scopeSelector);
    scopedStyleCache.set(cacheKey, value);
    if (scopedStyleCache.size > SCOPED_STYLE_CACHE_LIMIT) {
      const oldestKey = scopedStyleCache.keys().next().value;
      if (oldestKey) scopedStyleCache.delete(oldestKey);
    }
    return {value, cacheHit: false};
  }

  function findMatchingBrace(text, openBraceIndex) {
    let depth = 0;
    let quote = null;

    for (let i = openBraceIndex; i < text.length; i++) {
      const ch = text[i];
      const prev = i > 0 ? text[i - 1] : '';

      if (quote) {
        if (ch === quote && prev !== '\\') quote = null;
        continue;
      }

      if (ch === '"' || ch === "'") {
        quote = ch;
        continue;
      }

      if (ch === '{') depth++;
      if (ch === '}') {
        depth--;
        if (depth === 0) return i;
      }
    }

    return -1;
  }

  function findTopLevelChar(text, charToFind, startIndex) {
    let quote = null;
    let parenDepth = 0;
    let bracketDepth = 0;

    for (let i = startIndex; i < text.length; i++) {
      const ch = text[i];
      const prev = i > 0 ? text[i - 1] : '';

      if (quote) {
        if (ch === quote && prev !== '\\') quote = null;
        continue;
      }

      if (ch === '"' || ch === "'") {
        quote = ch;
        continue;
      }

      if (ch === '(') parenDepth++;
      if (ch === ')') parenDepth = Math.max(0, parenDepth - 1);
      if (ch === '[') bracketDepth++;
      if (ch === ']') bracketDepth = Math.max(0, bracketDepth - 1);

      if (ch === charToFind && parenDepth === 0 && bracketDepth === 0) return i;
    }

    return -1;
  }

  function splitDeclarationsAndNestedRules(blockContent) {
    let declarations = '';
    let nestedRules = '';
    let i = 0;

    while (i < blockContent.length) {
      while (i < blockContent.length && /\s/.test(blockContent[i])) i++;
      if (i >= blockContent.length) break;

      const nextSemi = findTopLevelChar(blockContent, ';', i);
      const nextBrace = findTopLevelChar(blockContent, '{', i);

      if (nextBrace !== -1 && (nextSemi === -1 || nextBrace < nextSemi)) {
        const closeBrace = findMatchingBrace(blockContent, nextBrace);
        if (closeBrace === -1) {
          declarations += blockContent.slice(i);
          break;
        }
        nestedRules += `${blockContent.slice(i, closeBrace + 1)}\n`;
        i = closeBrace + 1;
        continue;
      }

      if (nextSemi !== -1) {
        declarations += `${blockContent.slice(i, nextSemi + 1)}\n`;
        i = nextSemi + 1;
      } else {
        declarations += blockContent.slice(i);
        break;
      }
    }

    return {declarations: declarations.trim(), nestedRules: nestedRules.trim()};
  }

  function scopeCssBlocks(cssText, scopeSelector, parentSelectors) {
    let i = 0;
    let out = '';

    while (i < cssText.length) {
      while (i < cssText.length && /\s/.test(cssText[i])) i++;
      if (i >= cssText.length) break;

      if (cssText.startsWith('/*', i)) {
        const end = cssText.indexOf('*/', i + 2);
        if (end === -1) break;
        i = end + 2;
        continue;
      }

      let preludeStart = i;
      while (i < cssText.length && cssText[i] !== '{' && cssText[i] !== ';') i++;
      if (i >= cssText.length) break;

      const stopChar = cssText[i];
      const prelude = cssText.slice(preludeStart, i).trim();
      if (!prelude) {
        i++;
        continue;
      }

      if (stopChar === ';') {
        out += `${prelude};\n`;
        i++;
        continue;
      }

      const openBraceIndex = i;
      const closeBraceIndex = findMatchingBrace(cssText, openBraceIndex);
      if (closeBraceIndex === -1) {
        debugWarn('scopeCssBlocks.unmatchedBrace', {scopeSelector, prelude});
        break;
      }

      const body = cssText.slice(openBraceIndex + 1, closeBraceIndex);
      if (prelude.startsWith('@')) {
        if (/^@(media|supports|layer|document)\b/i.test(prelude)) {
          const scopedInner = scopeCssBlocks(body, scopeSelector, parentSelectors);
          out += `${prelude} {\n${scopedInner}\n}\n`;
        } else {
          out += `${prelude} {\n${body}\n}\n`;
        }
      } else {
        const scopedSelectors = parentSelectors && parentSelectors.length > 0
          ? combineNestedSelectorList(prelude, parentSelectors)
          : prefixSelectorList(prelude, scopeSelector);
        const {declarations, nestedRules} = splitDeclarationsAndNestedRules(body);
        if (declarations) {
          out += `${scopedSelectors} {\n${declarations}\n}\n`;
        }
        if (nestedRules) {
          out += `${scopeCssBlocks(nestedRules, scopeSelector, splitSelectorList(scopedSelectors))}\n`;
        }
      }

      i = closeBraceIndex + 1;
    }

    return out.trim();
  }

  function scopeCssWithCSSOM(cssText, scopeSelector) {
    const sheet = new CSSStyleSheet();
    sheet.replaceSync(cssText);
    const parts = [];
    processRulesFromCSSOM(sheet.cssRules, scopeSelector, parts);
    return parts.join('\n');
  }

  function processRulesFromCSSOM(rules, scope, out) {
    for (let i = 0; i < rules.length; i++) {
      const rule = rules[i];
      const type = rule.type;

      if (type === CSSRule.STYLE_RULE) {
        const prefixed = splitSelectorList(rule.selectorText)
          .map(s => prefixSelectorForCSSOM(s.trim(), scope))
          .join(', ');
        if (rule.style.cssText.trim()) {
          out.push(`${prefixed} { ${rule.style.cssText} }`);
        }
        if (rule.cssRules && rule.cssRules.length > 0) {
          processRulesFromCSSOM(rule.cssRules, prefixed, out);
        }
      } else if (type === CSSRule.MEDIA_RULE) {
        const inner = [];
        processRulesFromCSSOM(rule.cssRules, scope, inner);
        out.push(`@media ${rule.media.mediaText} {\n${inner.join('\n')}\n}`);
      } else if (type === CSSRule.SUPPORTS_RULE) {
        const inner = [];
        processRulesFromCSSOM(rule.cssRules, scope, inner);
        const condition = rule.conditionText || '';
        out.push(`@supports ${condition} {\n${inner.join('\n')}\n}`);
      } else {
        out.push(rule.cssText);
      }
    }
  }

  function prefixSelectorForCSSOM(sel, scope) {
    if (sel.includes('&')) {
      return combineNestedSelectorList(sel, splitSelectorList(scope));
    }

    // :root / html / body alone → scope selector
    if (/^(:root|html|body)$/i.test(sel)) {
      return scope;
    }

    // :root[data-theme="dark"] .foo → :root[data-theme="dark"] [scope] .foo
    const rootWithAttr = sel.match(/^(:root|html|body)(\[[^\]]+\])([\s\S]*)$/i);
    if (rootWithAttr) {
      const head = rootWithAttr[1];
      const attr = rootWithAttr[2];
      const tail = rootWithAttr[3] || '';
      if (!tail.trim()) {
        return `${head}${attr} ${scope}`;
      }
      return `${head}${attr} ${scope}${tail}`;
    }

    // :root .foo → [scope] .foo
    const rootDesc = sel.match(/^(:root|html|body)\s+([\s\S]+)$/i);
    if (rootDesc) {
      return `${scope} ${rootDesc[2]}`;
    }

    // Regular selector → [scope] selector
    return combineNestedSelectorList(sel, splitSelectorList(scope));
  }

  function buildDictionaryScopedStyle(css, scopeSelector) {
    const trimmed = css.trim();
    if (!trimmed) return '';

    // Declarations-only chunks can be wrapped directly.
    if (!trimmed.includes('{')) {
      const wrapped = `${scopeSelector} {\n${trimmed}\n}`;
      debugLog('buildDictionaryScopedStyle.declarationsOnly', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: wrapped.length,
      });
      return wrapped;
    }

    // Three-tier scoping: native @scope > CSSOM > string parser
    if (HAS_NATIVE_SCOPE) {
      const scoped = `@scope (${scopeSelector}) {\n${trimmed}\n}`;
      debugLog('buildDictionaryScopedStyle.nativeScope', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: scoped.length,
      });
      return scoped;
    }

    if (HAS_CONSTRUCTED_SHEETS) {
      try {
        const scoped = scopeCssWithCSSOM(trimmed, scopeSelector);
        if (scoped) {
          debugLog('buildDictionaryScopedStyle.cssom', {
            scopeSelector,
            inputLength: trimmed.length,
            outputLength: scoped.length,
          });
          return scoped;
        }
      } catch (e) {
        debugWarn('buildDictionaryScopedStyle.cssomFailed', {
          scopeSelector,
          error: e.message,
        });
      }
    }

    // Fallback: string-based CSS parser
    const scoped = scopeCssBlocks(trimmed, scopeSelector);
    if (scoped) {
      debugLog('buildDictionaryScopedStyle.fallback', {
        scopeSelector,
        inputLength: trimmed.length,
        outputLength: scoped.length,
      });
      return scoped;
    }

    const lastResort = `${scopeSelector} {\n${trimmed}\n}`;
    debugWarn('buildDictionaryScopedStyle.lastResort', {
      scopeSelector,
      inputLength: trimmed.length,
      outputLength: lastResort.length,
    });
    return lastResort;
  }

  function buildScopedDictionaryCss(stylesPayload) {
    const parts = [];
    debugLog('buildScopedDictionaryCss.start', {
      styleEntries: Array.isArray(stylesPayload) ? stylesPayload.length : 0
    });

    for (const styleEntry of stylesPayload) {
      if (typeof styleEntry === 'string') {
        const legacyCss = sanitizeDictionaryStyles(styleEntry, 'Legacy');
        if (legacyCss) {
          parts.push(legacyCss);
          debugLog('buildScopedDictionaryCss.legacy', {
            cssLength: legacyCss.length,
            hasBackground: /background/i.test(legacyCss)
          });
        }
        continue;
      }
      if (!styleEntry || typeof styleEntry !== 'object') continue;

      const dictName = typeof styleEntry.dictName === 'string' ? styleEntry.dictName : '';
      const rawCss = typeof styleEntry.styles === 'string' ? styleEntry.styles : '';
      const css = sanitizeDictionaryStyles(rawCss, dictName);
      if (!dictName || !css) continue;

      const escapedName = cssDoubleQuotedContent(dictName);
      const selector = `[data-dictionary="${escapedName}"]`;
      const {value: scoped, cacheHit} = getCachedScopedStyle(dictName, css, selector);
      parts.push(scoped);
      debugLog('buildScopedDictionaryCss.dictionary', {
        dictName,
        cssLength: css.length,
        scopedLength: scoped.length,
        cacheHit,
        hasBackground: /background/i.test(css)
      });
    }
    const method = HAS_NATIVE_SCOPE ? 'native-scope' : (HAS_CONSTRUCTED_SHEETS ? 'cssom' : 'string-parser');
    const output = parts.join('\n\n');
    debugLog('buildScopedDictionaryCss.done', {outputLength: output.length, method});
    return output;
  }

  function trimFloat(value) {
    if (!Number.isFinite(value)) return '0';
    const rounded = Math.round(value * 10000) / 10000;
    return Number.isInteger(rounded) ? String(rounded) : String(rounded);
  }

  function sanitizeHref(href) {
    if (!href) return null;
    const value = String(href).trim();
    if (/^javascript:/i.test(value)) return null;
    return value;
  }

  function cssDoubleQuotedContent(value) {
    return String(value)
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\A ')
      .replace(/\r/g, '');
  }

  function hashString(value) {
    const text = String(value);
    let hash = 0;
    for (let i = 0; i < text.length; i++) {
      hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0;
    }
    return `${text.length}:${hash >>> 0}`;
  }

  function applyDataAttributes(node, data) {
    if (!data || typeof data !== 'object') return;
    for (const key of Object.keys(data)) {
      const value = data[key];
      if (value === null || typeof value === 'undefined') continue;
      if (key === 'class') {
        const classText = String(value);
        classText.split(' ').map((s) => s.trim()).filter(Boolean).forEach((klass) => node.classList.add(klass));
        // Keep legacy class selectors and data-sc-class selectors both working.
        node.setAttribute('data-sc-class', classText);
        if (DEBUG_VERBOSE_NODES) {
          debugLog('applyDataAttributes.class', {
            tag: node.tagName,
            className: node.className,
            source: classText
          });
        }
        continue;
      }
      let datasetKey = key.length > 0 ? `${key[0].toUpperCase()}${key.substring(1)}` : key;
      datasetKey = `sc${datasetKey}`;
      try {
        // Keep empty-string values to preserve attribute-presence selectors.
        node.dataset[datasetKey] = String(value);
      } catch (e) {
        debugWarn('applyDataAttributes.datasetFailed', {
          tag: node.tagName,
          key,
          datasetKey,
          error: e.message
        });
        continue;
      }

      if (key === 'content' || key === 'headword' || key === 'example' || key === 'meaning') {
        debugLog('applyDataAttributes.attr', {
          tag: node.tagName,
          datasetKey,
          value: String(value)
        });
      }
    }
  }

  function applyStyle(node, style) {
    if (!style || typeof style !== 'object') return;
    const map = {
      fontStyle: 'fontStyle',
      fontWeight: 'fontWeight',
      color: 'color',
      background: 'background',
      backgroundColor: 'backgroundColor',
      textDecorationStyle: 'textDecorationStyle',
      textDecorationColor: 'textDecorationColor',
      borderColor: 'borderColor',
      borderStyle: 'borderStyle',
      borderRadius: 'borderRadius',
      borderWidth: 'borderWidth',
      clipPath: 'clipPath',
      verticalAlign: 'verticalAlign',
      textAlign: 'textAlign',
      textEmphasis: 'textEmphasis',
      textShadow: 'textShadow',
      margin: 'margin',
      padding: 'padding',
      paddingTop: 'paddingTop',
      paddingLeft: 'paddingLeft',
      paddingRight: 'paddingRight',
      paddingBottom: 'paddingBottom',
      whiteSpace: 'whiteSpace',
      wordBreak: 'wordBreak',
      cursor: 'cursor',
      listStyleType: 'listStyleType'
    };

    for (const [sourceKey, cssKey] of Object.entries(map)) {
      const value = style[sourceKey];
      if (typeof value === 'string' && value.length > 0) {
        node.style[cssKey] = value;
      }
    }

    // Margin sides: Jitendex uses numeric values which are treated as em units.
    const emSpacing = ['marginTop', 'marginLeft', 'marginRight', 'marginBottom'];
    for (const key of emSpacing) {
      const value = style[key];
      if (typeof value === 'number') {
        node.style[key] = `${value}em`;
      } else if (typeof value === 'string' && value.length > 0) {
        node.style[key] = value;
      }
    }

    const decorationLine = style.textDecorationLine;
    if (Array.isArray(decorationLine)) {
      node.style.textDecoration = decorationLine.join(' ');
    } else if (typeof decorationLine === 'string' && decorationLine.length > 0) {
      node.style.textDecoration = decorationLine;
    }
    if (typeof style.textDecoration === 'string' && style.textDecoration.length > 0) {
      node.style.textDecoration = style.textDecoration;
    }
  }

  function parseMaybeJson(text) {
    const trimmed = text.trim();
    if (!(trimmed.startsWith('{') || trimmed.startsWith('['))) return null;
    try {
      return JSON.parse(trimmed);
    } catch (e) {
      return null;
    }
  }

  function normalizePlainText(text) {
    return String(text)
      .replace(/\r\n?/g, '\n')
      .replace(/\\n/g, '\n');
  }

  function appendTextWithLineBreaks(parent, text) {
    const normalized = normalizePlainText(text);
    const parts = normalized.split('\n');
    for (let i = 0; i < parts.length; i += 1) {
      if (i > 0) {
        parent.appendChild(document.createElement('br'));
      }
      if (parts[i].length > 0) {
        parent.appendChild(document.createTextNode(parts[i]));
      }
    }
  }

  // ── Word extraction ───────────────────────────────────────────────────────

  function isCJK(ch) {
    const cp = ch.codePointAt(0);
    // CJK Unified, Katakana, Hiragana, Katakana ext, CJK compat, fullwidth
    // + Hangul (Jamo, Compatibility Jamo, Syllables, Jamo Extended-A/B) for Korean recursive lookup
    return (cp >= 0x3000 && cp <= 0x9FFF) ||
           (cp >= 0xF900 && cp <= 0xFAFF) ||
           (cp >= 0xFF00 && cp <= 0xFFEF) ||
           (cp >= 0x1100 && cp <= 0x11FF) ||
           (cp >= 0x3130 && cp <= 0x318F) ||
           (cp >= 0xAC00 && cp <= 0xD7AF) ||
           (cp >= 0xA960 && cp <= 0xA97F) ||
           (cp >= 0xD7B0 && cp <= 0xD7FF);
  }

  function isKanjiCodepoint(cp) {
    return (cp >= 0x3400 && cp <= 0x4DBF) ||
           (cp >= 0x4E00 && cp <= 0x9FFF) ||
           (cp >= 0xF900 && cp <= 0xFAFF);
  }

  function isWordChar(ch) {
    return isCJK(ch) || /[\w\u00C0-\u024F\u0600-\u06FF\u1100-\u11FF\u3130-\u318F\uAC00-\uD7AF\uA960-\uA97F\uD7B0-\uD7FF]/.test(ch);
  }

  const scanDelimiters = '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r';
  function isScanBoundary(ch) {
    return /^[\s\u3000]$/.test(ch) || scanDelimiters.indexOf(ch) !== -1;
  }

  function isFurigana(node) {
    const el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
    return !!(el && el.closest('rt, rp'));
  }

  function caretRangeAtPoint(x, y) {
    if (document.caretRangeFromPoint) {
      return document.caretRangeFromPoint(x, y);
    }
    if (document.caretPositionFromPoint) {
      const pos = document.caretPositionFromPoint(x, y);
      if (pos) {
        const range = document.createRange();
        range.setStart(pos.offsetNode, pos.offset);
        return range;
      }
    }
    return null;
  }

  const sentenceDelimiters = '\u3002\uff01\uff1f.!?\n\r';

  function getSentenceContextAtPoint(x, y) {
    const range = caretRangeAtPoint(x, y);
    if (!range) return null;

    const node = range.startContainer;
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;

    const container = node.parentElement?.closest?.('.definition-item, .entry-body-section, .entry-body, article') || document.body;
    const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
      acceptNode: (n) => {
        const parent = n.parentElement;
        if (!parent) return NodeFilter.FILTER_REJECT;
        if (isFurigana(n) || parent.closest('button, .lookup-tabs, .entry-icon-group')) return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });

    let fullText = '';
    let targetOffset = -1;
    let current = walker.nextNode();
    while (current) {
      const text = current.textContent || '';
      if (current === node) {
        targetOffset = fullText.length + Math.min(range.startOffset, text.length);
      }
      fullText += text;
      current = walker.nextNode();
    }

    if (targetOffset < 0 || fullText.trim().length === 0) return null;

    let start = targetOffset;
    while (start > 0 && sentenceDelimiters.indexOf(fullText[start - 1]) === -1) start--;

    let end = targetOffset;
    while (end < fullText.length && sentenceDelimiters.indexOf(fullText[end]) === -1) end++;
    if (end < fullText.length && sentenceDelimiters.indexOf(fullText[end]) !== -1) end++;

    const rawSentence = fullText.slice(start, end);
    const leadingTrim = rawSentence.length - rawSentence.trimStart().length;
    const sentence = rawSentence.trim();
    if (!sentence) return null;

    return {
      sentence,
      offset: Math.max(0, Math.min(sentence.length, targetOffset - start - leadingTrim))
    };
  }

  /**
   * Returns up to MAX_SCAN_CHARS of text starting at the tapped position.
   * For CJK the full forward slice is useful (backend handles deinflection).
   * For space-separated scripts we expand left+right to word boundaries.
   */
  function extractTextAtPoint(x, y) {
    let range = caretRangeAtPoint(x, y);
    if (!range) return null;

    const node = range.startContainer;
    if (!node || node.nodeType !== Node.TEXT_NODE) return null;

    const text = node.textContent || '';
    const offset = Math.min(range.startOffset, text.length);
    if (offset >= text.length) return null;

    const ch = text[offset];
    if (!isWordChar(ch) || isScanBoundary(ch) || isFurigana(node)) return null;

    if (isCJK(ch)) {
      // Collect forward text across nodes, skipping furigana (<rt>)
      const container = node.parentElement.closest('.entry-body, .headword, .gloss-content') || document.body;
      const walker = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, {
        acceptNode: (n) => isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
      });

      walker.currentNode = node;
      let result = '';
      let currNode = node;
      let currOffset = offset;

      while (currNode && result.length < MAX_SCAN_CHARS) {
        const content = currNode.textContent || '';
        const slice = content.slice(currOffset);

        for (let i = 0; i < slice.length && result.length < MAX_SCAN_CHARS; i++) {
          const char = slice[i];
          if (isScanBoundary(char)) {
            return result.length > 0 ? result : null;
          }
          result += char;
        }

        if (result.length >= MAX_SCAN_CHARS) break;
        currNode = walker.nextNode();
        currOffset = 0;
      }
      return result.length > 0 ? result : null;
    }

    // Space-separated: expand to word boundaries
    let start = offset;
    let end = offset;
    while (start > 0 && isWordChar(text[start - 1]) && !isScanBoundary(text[start - 1])) start--;
    while (end < text.length && isWordChar(text[end]) && !isScanBoundary(text[end])) end++;
    const word = text.slice(start, end).trim();
    return word.length > 0 ? word : null;
  }

  // ── Tab bar ───────────────────────────────────────────────────────────────

  let _tabs = [];          // [{label, active}]
  let _tabsEl = null;      // the .lookup-tabs DOM node
  const _scrollPositions = new Map(); // 'navMode-activeIndex' -> scrollTop

  function getOrCreateTabsEl() {
    if (_tabsEl) return _tabsEl;
    _tabsEl = document.createElement('div');
    _tabsEl.className = 'lookup-tabs';
    _tabsEl.style.transition = 'transform 0.32s cubic-bezier(0.4,0,0.2,1)';
    return _tabsEl;
  }

  let _navMode = 'tabs';

  function renderTabBar(tabs, navMode) {
    const container = document.getElementById('entries');
    if (!container) return;

    // Find the index of the currently active tab from the CURRENT tabs array (not stale _tabs)
    const activeIndex = tabs.findIndex(t => t.active);

    const el = getOrCreateTabsEl();
    el.textContent = '';
    el.className = 'lookup-tabs';
    el.style.transition = 'transform 0.32s cubic-bezier(0.4,0,0.2,1)';
    el.style.transform = 'translateY(0)';

    if (_navMode === 'stack') {
      if (activeIndex > 0) {
        const btn = document.createElement('button');
        btn.className = 'lookup-tab stack-button';
        btn.title = 'Back';
        btn.innerHTML = '<span>← Back</span>';
        btn.onclick = (e) => {
          e.stopPropagation();
          navigateTo(CHIMA_SCHEME + '//back');
        };
        el.appendChild(btn);
      }
    } else {
      tabs.forEach((tab, i) => {
        const btn = document.createElement('button');
        btn.className = 'lookup-tab' + (tab.active ? ' active' : '');
        btn.title = tab.label;
        btn.setAttribute('data-tab-index', String(i));

        const label = document.createElement('span');
        label.textContent = tab.label.length > 20 ? tab.label.slice(0, 20) + '…' : tab.label;
        btn.appendChild(label);

        if (!tab.active) {
          btn.onclick = (e) => {
            e.stopPropagation();
            navigateTo(CHIMA_SCHEME + '//tab?index=' + i);
          };
        }
        el.appendChild(btn);
      });
    }

    // Prepend into #entries (insertBefore because textContent='' may have removed it)
    if (!el.parentElement) {
      container.insertBefore(el, container.firstChild);
    }
    const tabsHeight = el.offsetHeight || 40;
    // The tab bar is fixed and therefore removed from normal layout flow.
    // Reserve its measured height so the first result is never rendered beneath it.
    container.style.paddingTop = tabsHeight + 'px';
    document.documentElement.style.setProperty('--lookup-tabs-height', tabsHeight + 'px');

    if (activeIndex >= 0) {
      requestAnimationFrame(() => {
        const activeBtn = el.querySelector(`[data-tab-index="${activeIndex}"]`);
        if (activeBtn) {
          const isEink = document.documentElement.dataset.chimaEinkMode === 'true';
          const targetLeft = activeBtn.offsetLeft - (el.clientWidth - activeBtn.clientWidth) / 2;
          el.scrollTo({
            left: Math.max(0, targetLeft),
            behavior: isEink ? 'instant' : 'smooth',
          });
        }
      });
    }
  }


  // ── Navigation helper ─────────────────────────────────────────────────────

  function navigateTo(url) {
    // Proactively save scroll position before navigating
    const activeIndex = _tabs.findIndex(t => t.active);
    if (activeIndex >= 0) {
      const activeTab = _tabs[activeIndex];
      const scrollKey = (_navMode || 'tabs') + '-' + activeIndex + '-' + activeTab.label;
      _scrollPositions.set(scrollKey, window.scrollY);
      debugLog('navigateTo.saveScroll', {key: scrollKey, y: window.scrollY});
    }
    window.location.href = url;
  }

  function navigateStructuredLink(href, internal) {
    if (!href) return;
    if (internal) {
      try {
        const queryStart = href.indexOf('?');
        const params = queryStart >= 0 ? new URLSearchParams(href.slice(queryStart + 1)) : null;
        const query = params ? (params.get('query') || params.get('q') || params.get('term')) : null;
        if (query) {
          navigateTo(CHIMA_SCHEME + '//lookup?q=' + encodeURIComponent(query));
          return;
        }
      } catch (e) {
        debugWarn('navigateStructuredLink.internalFailed', {href, error: e.message});
      }
      return;
    }
    navigateTo(href);
  }

  // ── Touch / tap listener (delegated on document) ──────────────────────────

  let _lookupEnabled = false;
  let _recursiveSelectionStart = null;
  let _recursiveHighlightNodes = [];

  function clearRecursiveHighlight() {
    _recursiveHighlightNodes.forEach((node) => node.remove());
    _recursiveHighlightNodes = [];
  }

  function clearRecursiveSelection() {
    clearRecursiveHighlight();
    _recursiveSelectionStart = null;
  }

  function rememberRecursiveSelectionAtPoint(x, y) {
    const range = caretRangeAtPoint(x, y);
    const node = range?.startContainer;
    if (!range || !node || node.nodeType !== Node.TEXT_NODE) {
      _recursiveSelectionStart = null;
      return;
    }

    const text = node.textContent || '';
    let offset = Math.min(range.startOffset, text.length);
    if (offset < text.length && !isCJK(text[offset])) {
      while (offset > 0 && isWordChar(text[offset - 1]) && !isScanBoundary(text[offset - 1])) offset--;
    }
    _recursiveSelectionStart = { node, offset };
  }

  function resolveRecursiveSelection(codePointCount) {
    clearRecursiveHighlight();
    const start = _recursiveSelectionStart;
    if (!start || !Number.isFinite(codePointCount) || codePointCount <= 0) return null;

    const root = start.node.parentElement?.closest('.entry-body, .headword, .gloss-content') || document.body;
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
      acceptNode: (node) => isFurigana(node) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
    });
    walker.currentNode = start.node;

    let remaining = Math.floor(codePointCount);
    let current = start.node;
    let currentOffset = start.offset;
    let endNode = start.node;
    let endOffset = start.offset;

    while (current && remaining > 0) {
      const text = current.textContent || '';
      let consumedUnits = 0;
      for (const character of text.slice(currentOffset)) {
        if (remaining <= 0) break;
        consumedUnits += character.length;
        remaining--;
      }
      endNode = current;
      endOffset = currentOffset + consumedUnits;
      if (remaining <= 0) break;
      current = walker.nextNode();
      currentOffset = 0;
    }

    if (endNode === start.node && endOffset <= start.offset) return null;

    const target = document.createRange();
    target.setStart(start.node, start.offset);
    target.setEnd(endNode, endOffset);
    const rects = Array.from(target.getClientRects()).filter((rect) => rect.width > 0 && rect.height > 0);
    for (const rect of rects) {
      const marker = document.createElement('span');
      marker.className = 'recursive-lookup-highlight';
      marker.style.left = `${rect.left + window.scrollX}px`;
      marker.style.top = `${rect.bottom + window.scrollY - 2}px`;
      marker.style.width = `${rect.width}px`;
      _recursiveHighlightNodes.push(marker);
      document.body.appendChild(marker);
    }
    if (rects.length === 0) return null;

    const left = Math.min(...rects.map((rect) => rect.left));
    const top = Math.min(...rects.map((rect) => rect.top));
    const right = Math.max(...rects.map((rect) => rect.right));
    const bottom = Math.max(...rects.map((rect) => rect.bottom));
    return JSON.stringify({ x: left, y: top, width: right - left, height: bottom - top });
  }

  let _touchStartX = 0;
  let _touchStartY = 0;
  const TAP_MOVE_THRESHOLD = 10;  // px — ignore if finger moved too far (scroll)

  function installTapListener() {
    if (_listenersInstalled) return;
    _listenersInstalled = true;

    document.addEventListener('click', (e) => {
      const target = e.target;
      if (!target) return;

      // If tapping on a rendered kanji-tappable span, route as kanji-only lookup
      const kanjiSpan = target.closest('.kanji-tappable');
      if (kanjiSpan) {
        navigateTo(CHIMA_SCHEME + '//kanji?q=' + encodeURIComponent(kanjiSpan.textContent));
        e.stopPropagation();
        return;
      }

      // If the user has an active text selection, don't trigger a lookup.
      if (window.getSelection().toString().trim().length > 0) return;

      // Skip interactive controls — buttons, dict tags, inflection toggles, etc.
      if (target.closest('button, .anki-add-btn, .lookup-tab, .entry-deinflection-row, .tag, .dictionary-header, details, summary, a, .gloss-link, .gloss-sc-a')) return;

      const word = extractTextAtPoint(e.clientX, e.clientY);
      if (!word) return;

      rememberRecursiveSelectionAtPoint(e.clientX, e.clientY);

      const sentenceContext = getSentenceContextAtPoint(e.clientX, e.clientY);
      let url = CHIMA_SCHEME + '//lookup?q=' + encodeURIComponent(word);
      if (sentenceContext && sentenceContext.sentence) {
        url += '&sentence=' + encodeURIComponent(sentenceContext.sentence);
        url += '&offset=' + encodeURIComponent(String(sentenceContext.offset || 0));
      }
      url += '&x=' + Math.round(e.clientX);
      url += '&y=' + Math.round(e.clientY);
      navigateTo(url);
    }, {passive: false});

    document.addEventListener('selectionchange', () => {
      const selection = window.getSelection();
      if (selection && !selection.isCollapsed) {
        _lastSelection = selection.toString();
      } else {
        _lastSelection = '';
      }
    });
  }

  function consumePopupSelection() {
    const selection = window.getSelection();
    const current = selection && !selection.isCollapsed ? selection.toString() : '';
    const value = current || _pendingPopupSelection || _lastSelection || '';
    _pendingPopupSelection = '';
    _lastSelection = '';
    return value;
  }

  function capturePopupSelectionForButton() {
    const selection = window.getSelection();
    if (selection && !selection.isCollapsed) {
      _pendingPopupSelection = selection.toString();
    }
  }

  function mediaCandidates(path) {
    const list = new Set();
    const add = (value) => {
      if (!value) return;
      list.add(value);
      list.add(value.replace(/\\/g, '/'));
      if (value.startsWith('./')) list.add(value.slice(2));
      if (value.startsWith('/')) list.add(value.slice(1));
    };
    add(path);
    try {
      add(decodeURIComponent(path));
    } catch (e) {
      // ignore malformed URI paths
    }
    return Array.from(list);
  }

  function resolveMediaSrc(mediaMap, dictName, path) {
    if (!path) return null;
    for (const candidate of mediaCandidates(path)) {
      const key = `${dictName}\u0000${candidate}`;
      if (Object.prototype.hasOwnProperty.call(mediaMap, key)) {
        return mediaMap[key];
      }
    }
    return null;
  }

  function observeDictionaryMedia(target, load) {
    if (!target || typeof load !== 'function') return;
    if (typeof IntersectionObserver !== 'function') {
      load();
      return;
    }
    if (!_dictionaryMediaObserver) {
      _dictionaryMediaObserver = new IntersectionObserver((entries, observer) => {
        for (const entry of entries) {
          if (!entry.isIntersecting) continue;
          observer.unobserve(entry.target);
          const fn = entry.target._loadDictionaryMedia;
          delete entry.target._loadDictionaryMedia;
          fn?.();
        }
      }, { root: null, rootMargin: '200px' });
    }
    target._loadDictionaryMedia = load;
    _dictionaryMediaObserver.observe(target);
  }

  function resetDictionaryMediaObserver() {
    _dictionaryMediaObserver?.disconnect();
    _dictionaryMediaObserver = null;
  }

  function applyMediaToImageLink(link, mediaMap) {
    if (!link || link.dataset.imageLoadState === 'loaded') return;
    const path = link.dataset.path || '';
    const dictName = link.dataset.dictName || '';
    const src = resolveMediaSrc(mediaMap || {}, dictName, path);
    if (!src) return;

    observeDictionaryMedia(link, () => {
      const bg = link.querySelector('.gloss-image-background');
      if (bg) bg.style.setProperty('--image', `url("${src}")`);

      const img = link.querySelector('img.gloss-image');
      if (img) img.src = src;

      link.dataset.imageLoadState = 'loaded';
    });
  }

  function postContentReady() {
    requestAnimationFrame(() => {
      try {
        if (window.DictionaryReadyBridge && window.DictionaryReadyBridge.contentReady) {
          window.DictionaryReadyBridge.contentReady();
        }
      } catch (e) {
        console.warn('[DictionaryRenderJS] contentReady bridge failed:', e);
      }
    });
  }

  function prewarmFonts() {
    if (!document.fonts) return;
    const loads = [];
    try {
      document.fonts.forEach((face) => {
        if (!face || face.status !== 'unloaded' || typeof face.load !== 'function') return;
        if (_prewarmedFontFaces) {
          if (_prewarmedFontFaces.has(face)) return;
          _prewarmedFontFaces.add(face);
        }
        try {
          loads.push(face.load().catch(() => {}));
        } catch (e) {
          // Ignore fonts the browser refuses to load eagerly.
        }
      });
    } catch (e) {
      return;
    }
    if (!loads.length) return;
    Promise.all(loads).then(postContentReady, postContentReady);
  }

  const POS_TAGS = new Set(['n', 'vs', 'vi', 'vt', 'adj-i', 'adj-na', 'adv', 'v1', 'v5k', 'v5s', 'v5m', 'v5n', 'v5r', 'v5t', 'exp', 'int']);
  const ARCH_TAGS = new Set(['arch', 'obs', 'rare', 'ok', 'oK']);
  const POPULAR_TAGS = new Set(['P', 'popular', 'frequent', 'common']);



  function resolveTagCategory(label, defaultCategory) {
    if (defaultCategory && defaultCategory !== 'default') return defaultCategory;
    const t = label.toLowerCase();
    if (t.includes('arch') || t.includes('obs') || t.includes('hist')) return 'archaism';
    if (t.includes('P') || t.includes('popular')) return 'popular';
    if (t.includes('freq')) return 'frequent';
    if (t.includes('col') || t.includes('pol') || t.includes('hon') || t.includes('fam')) return 'style';
    if (t.includes('dial')) return 'dialect';
    if (t.includes('v5') || t.includes('v1') || t.includes('adj') || t.includes('n') || t.includes('adv')) return 'partOfSpeech';
    return 'default';
  }

  function createTag(label, category) {
    const resolvedCategory = resolveTagCategory(label, category);
    const span = document.createElement('span');
    span.className = 'tag';
    span.dataset.category = resolvedCategory;
    span.dataset.details = label;

    const inner = document.createElement('span');
    inner.className = 'tag-label';

    const content = document.createElement('span');
    content.className = 'tag-label-content';
    content.textContent = label;

    inner.appendChild(content);
    span.appendChild(inner);
    return span;
  }

  function createTagWithBody(label, body, category) {
    const span = createTag(label, category);
    span.classList.add('tag-has-body');
    // Round right of label when body follows
    const labelEl = span.querySelector('.tag-label');
    if (labelEl) {
      labelEl.style.borderTopRightRadius = '0';
      labelEl.style.borderBottomRightRadius = '0';
    }
    const bodyNode = document.createElement('span');
    bodyNode.className = 'tag-body';
    const bodyContent = document.createElement('span');
    bodyContent.className = 'tag-body-content';
    bodyContent.textContent = body;
    bodyNode.appendChild(bodyContent);
    span.appendChild(bodyNode);
    return span;
  }

  function buildGlossaryNode(rawGlossary, dictName, mediaMap) {
    const parsed = parseMaybeJson(rawGlossary);
    if (parsed === null) {
      const span = document.createElement('span');
      span.className = 'gloss-plain-text';
      const normalized = normalizePlainText(rawGlossary);
      if (containsJapaneseText(normalized)) {
        span.lang = 'ja';
      }
      appendTextWithLineBreaks(span, normalized);
      return span;
    }
    return renderStructured(parsed, dictName, mediaMap);
  }

  function containsJapaneseText(text) {
    return typeof text === 'string' && /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff]/.test(text);
  }

  function getNodeText(node) {
    if (node === null || typeof node === 'undefined') return '';
    if (typeof node === 'string' || typeof node === 'number' || typeof node === 'boolean') {
      return String(node);
    }
    if (Array.isArray(node)) {
      return node.map((item) => getNodeText(item)).join('');
    }
    if (typeof node !== 'object') return '';
    if (node && node.data && node.data.content === 'attribution') return '';
    if (node.type === 'structured-content') return getNodeText(node.content);
    return getNodeText(node.content);
  }

  function normalizeTableContent(content) {
    const items = Array.isArray(content) ? content : [content];
    const hasRows = items.some((item) => item && item.tag === 'tr');
    if (!hasRows) return content;
    const hasSections = items.some((item) => item && (item.tag === 'tbody' || item.tag === 'thead' || item.tag === 'tfoot'));
    if (hasSections) return content;
    return [{tag: 'tbody', content: items}];
  }

  function renderStructured(content, dictName, mediaMap) {
    const fragment = document.createDocumentFragment();
    appendStructured(fragment, content, dictName, mediaMap, null);
    const wrapper = document.createElement('span');
    wrapper.className = 'structured-content';
    wrapper.appendChild(fragment);
    return wrapper;
  }

  // Registry-based dispatch for known tags; unknown valid tags still fall back to generic renderer.
  const GENERIC_ELEMENT_TAGS = [
    'br', 'ruby', 'rt', 'rp', 'table', 'thead', 'tbody', 'tfoot', 'tr', 'th', 'td',
    'div', 'span', 'ol', 'ul', 'li', 'details', 'summary', 'p', 'strong', 'em',
    'b', 'i', 'u', 'small', 'sub', 'sup', 'code', 'pre', 'blockquote', 's', 'mark',
    'ins', 'del', 'kbd', 'samp', 'var', 'time', 'q', 'cite', 'abbr'
  ];

  function getTagInfo(node, fallbackTag) {
    const raw = typeof node.tag === 'string' && node.tag.trim().length > 0
      ? node.tag.trim()
      : fallbackTag;
    const lower = raw.toLowerCase();
    return {raw, lower};
  }

  function createGenericElementHandler(defaultTagLower) {
    return (node, dictName, mediaMap, language) => {
      const {raw, lower} = getTagInfo(node, defaultTagLower);
      return createGenericStructuredElement(node, raw, lower, dictName, mediaMap, language);
    };
  }

  function createElementRegistry() {
    const registry = {
      img: (node, dictName, mediaMap) => createImageNode(node, dictName, mediaMap),
      a: (node, dictName, mediaMap, language) => createLinkNode(node, dictName, mediaMap, language)
    };

    for (const tag of GENERIC_ELEMENT_TAGS) {
      registry[tag] = createGenericElementHandler(tag);
    }

    return Object.freeze(registry);
  }

  const ELEMENT_REGISTRY = createElementRegistry();

  function isValidTagName(tagName) {
    return /^[a-z][a-z0-9:-]*$/i.test(tagName);
  }

  const SVG_NAMESPACE = 'http://www.w3.org/2000/svg';
  const svgTags = new Set([
    'svg', 'g', 'path', 'rect', 'circle', 'ellipse', 'line', 'polyline', 'polygon',
    'text', 'tspan', 'textpath', 'defs', 'symbol', 'use', 'marker', 'pattern',
    'mask', 'clippath', 'lineargradient', 'radialgradient', 'stop', 'view', 'foreignobject'
  ]);

  const svgAttributeMap = {
    viewBox: 'viewBox',
    preserveAspectRatio: 'preserveAspectRatio',
    width: 'width',
    height: 'height',
    x: 'x',
    y: 'y',
    x1: 'x1',
    y1: 'y1',
    x2: 'x2',
    y2: 'y2',
    cx: 'cx',
    cy: 'cy',
    r: 'r',
    rx: 'rx',
    ry: 'ry',
    d: 'd',
    points: 'points',
    fill: 'fill',
    stroke: 'stroke',
    strokeWidth: 'stroke-width',
    strokeLinecap: 'stroke-linecap',
    strokeLinejoin: 'stroke-linejoin',
    strokeMiterlimit: 'stroke-miterlimit',
    strokeDasharray: 'stroke-dasharray',
    strokeDashoffset: 'stroke-dashoffset',
    transform: 'transform',
    opacity: 'opacity',
    fillRule: 'fill-rule',
    clipRule: 'clip-rule',
    xmlns: 'xmlns',
    href: 'href'
  };

  function isSvgTag(tagNameLower) {
    return svgTags.has(tagNameLower);
  }

  function createElementForTag(tagName, isSvgElement) {
    return isSvgElement
      ? document.createElementNS(SVG_NAMESPACE, tagName)
      : document.createElement(tagName);
  }

  function applySvgAttributes(element, node) {
    for (const [sourceKey, attrName] of Object.entries(svgAttributeMap)) {
      const value = node[sourceKey];
      if (typeof value === 'string' || typeof value === 'number') {
        element.setAttribute(attrName, String(value));
      }
    }

    // Keep SVG sizing behavior data-driven from dictionary styles/content.
  }

  function applyCommonStructuredAttributes(element, node) {
    if (node.lang && typeof node.lang === 'string') {
      element.lang = node.lang;
    }
    if (node.title && typeof node.title === 'string') {
      element.title = node.title;
    }
    applyDataAttributes(element, node.data);
    applyStyle(element, node.style);
  }

  function createGenericStructuredElement(node, tagNameRaw, tagNameLower, dictName, mediaMap, language) {
    const svgElement = isSvgTag(tagNameLower);
    const elementTagName = svgElement ? tagNameRaw : tagNameLower;
    const element = createElementForTag(elementTagName, svgElement);
    element.classList.add(`gloss-sc-${tagNameLower}`);

    if (svgElement) {
      applySvgAttributes(element, node);
    }

    if (node.lang && typeof node.lang === 'string') {
      language = node.lang;
    }

    if ((tagNameLower === 'th' || tagNameLower === 'td') && Number.isFinite(node.colSpan)) {
      element.colSpan = node.colSpan;
    }
    if ((tagNameLower === 'th' || tagNameLower === 'td') && Number.isFinite(node.rowSpan)) {
      element.rowSpan = node.rowSpan;
    }
    if (tagNameLower === 'details' && node.open === true) {
      element.setAttribute('open', '');
    }

    applyCommonStructuredAttributes(element, node);

    if (DEBUG_VERBOSE_NODES && node && node.data && (Object.prototype.hasOwnProperty.call(node.data, 'content') || Object.prototype.hasOwnProperty.call(node.data, 'class'))) {
      debugLog('createGenericStructuredElement.node', {
        dictName,
        tag: tagNameLower,
        className: element.className,
        dataContent: Object.prototype.hasOwnProperty.call(node.data, 'content') ? String(node.data.content) : '',
        dataClass: Object.prototype.hasOwnProperty.call(node.data, 'class') ? String(node.data.class) : ''
      });
    }

    const contentToRender = tagNameLower === 'table' ? normalizeTableContent(node.content) : node.content;

    if (tagNameLower !== 'br') {
      appendStructured(element, contentToRender, dictName, mediaMap, language);
    }

    if (tagNameLower === 'table') {
      const wrapper = document.createElement('div');
      wrapper.className = 'gloss-sc-table-container';
      wrapper.appendChild(element);
      return wrapper;
    }

    return element;
  }

  function renderStructuredObjectNode(content, dictName, mediaMap, language) {
    if (!content || typeof content !== 'object') return null;
    if (content.data && content.data.content === 'attribution') return null;

    let node = content;
    if (node.type === 'structured-content') {
      return {kind: 'nested', content: node.content};
    }
    if (node.type === 'image' && !node.tag) {
      node = Object.assign({}, node, {tag: 'img'});
    }

    const tagNameRaw = typeof node.tag === 'string' ? node.tag.trim() : '';
    if (!tagNameRaw) {
      return {kind: 'nested', content: node.content};
    }

    if (!isValidTagName(tagNameRaw)) {
      return {kind: 'nested', content: node.content};
    }

    const tagNameLower = tagNameRaw.toLowerCase();
    const handler = ELEMENT_REGISTRY[tagNameLower];
    if (handler) {
      return {kind: 'node', node: handler(node, dictName, mediaMap, language)};
    }

    return {
      kind: 'node',
      node: createGenericStructuredElement(node, tagNameRaw, tagNameLower, dictName, mediaMap, language)
    };
  }

  function appendStructured(parent, content, dictName, mediaMap, language) {
    if (content === null || typeof content === 'undefined') return;

    if (typeof content === 'string' || typeof content === 'number' || typeof content === 'boolean') {
      appendTextWithLineBreaks(parent, content);
      return;
    }

    if (Array.isArray(content)) {
      const isStringArray = content.every((item) => typeof item === 'string');
      const parentTag = parent && parent.tagName;
      const isBlockParent = parentTag === 'DIV' || parentTag === 'SECTION' || parentTag === 'ARTICLE' || !parentTag;
      
      // Only auto-wrap in UL if we are in a block-level container.
      // If we are already in a list (UL/OL/LI) or an inline container (SPAN), just append.
      if (isStringArray && content.length > 1 && isBlockParent) {
        const ul = document.createElement('ul');
        ul.classList.add('glossary-list');
        content.forEach((child) => {
          const li = document.createElement('li');
          appendTextWithLineBreaks(li, child);
          ul.appendChild(li);
        });
        parent.appendChild(ul);
        return;
      }

      for (const item of content) appendStructured(parent, item, dictName, mediaMap, language);
      return;
    }

    if (typeof content !== 'object') return;

    const rendered = renderStructuredObjectNode(content, dictName, mediaMap, language);
    if (!rendered) return;
    if (rendered.kind === 'nested') {
      appendStructured(parent, rendered.content, dictName, mediaMap, language);
      return;
    }
    parent.appendChild(rendered.node);
  }

  function createLinkNode(node, dictName, mediaMap, language) {
    const href = sanitizeHref(node.href);
    const internal = href && !/^https?:\/\//i.test(href);

    const a = document.createElement('span');
    a.className = 'gloss-link gloss-sc-a';
    a.dataset.external = String(!internal);
    if (href) {
      a.dataset.href = href;
      a.setAttribute('href', href);
      a.tabIndex = 0;
      a.setAttribute('role', 'link');
      a.addEventListener('click', (e) => {
        e.preventDefault();
        e.stopPropagation();
        navigateStructuredLink(href, internal);
      });
      a.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault();
        navigateStructuredLink(href, internal);
      });
    }
    if (typeof node.title === 'string') a.title = node.title;
    if (typeof node.lang === 'string') {
      a.lang = node.lang;
      language = node.lang;
    }

    applyCommonStructuredAttributes(a, node);

    if (DEBUG_VERBOSE_NODES) {
      debugLog('createLinkNode', {dictName, href, internal});
    }

    const text = document.createElement('span');
    text.className = 'gloss-link-text';
    appendStructured(text, node.content, dictName, mediaMap, language);
    a.appendChild(text);
    return a;
  }

  function createImageNode(node, dictName, mediaMap) {
    const path = typeof node.path === 'string' ? node.path : (typeof node.src === 'string' ? node.src : '');
    const src = resolveMediaSrc(mediaMap, dictName, path) || (path.startsWith('data:') ? path : '');

    if (DEBUG_VERBOSE_NODES) {
      debugLog('createImageNode', {
        dictName,
        path,
        resolved: src,
        resolvedFromMap: !!src && src !== path
      });
    }

    const preferredWidth = Number(node.preferredWidth ?? (node.data && node.data.preferredWidth));
    const preferredHeight = Number(node.preferredHeight ?? (node.data && node.data.preferredHeight));
    const width = Number(node.width ?? (node.data && node.data.width) ?? 100);
    const height = Number(node.height ?? (node.data && node.data.height) ?? 100);

    const ratioWidth = Number.isFinite(preferredWidth) && preferredWidth > 0 ? preferredWidth : width;
    const ratioHeight = Number.isFinite(preferredHeight) && preferredHeight > 0 ? preferredHeight : height;
    const invAspectRatio = Math.max(0.01, ratioHeight / Math.max(1, ratioWidth));

    const usedWidth = Number.isFinite(preferredWidth) && preferredWidth > 0
      ? preferredWidth
      : (Number.isFinite(preferredHeight) && preferredHeight > 0 ? preferredHeight / invAspectRatio : width);

    const imageRendering = typeof node.imageRendering === 'string'
      ? node.imageRendering
      : (node.pixelated === true ? 'pixelated' : 'auto');

    const link = document.createElement('span');
    link.className = 'gloss-image-link gloss-sc-a';
    link.dataset.path = path;
    link.dataset.dictName = dictName;
    link.dataset.imageLoadState = 'unloaded';
    link.dataset.hasAspectRatio = 'true';
    link.dataset.imageRendering = imageRendering;
    link.dataset.appearance = typeof node.appearance === 'string' ? node.appearance : 'auto';
    link.dataset.background = String(node.background !== false);
    const explicitSizeUnits = typeof node.sizeUnits === 'string'
      ? node.sizeUnits
      : (node.data && typeof node.data.sizeUnits === 'string' ? node.data.sizeUnits : null);
    if (explicitSizeUnits) {
      link.dataset.sizeUnits = explicitSizeUnits;
    }
    
    // Width is driven by the container (which uses em units with reduced font-size).
    // Do NOT set width on the link — let the container dictate sizing, matching Yomitan/Hoshi.
    link.style.maxWidth = '100%';
    if (typeof node.verticalAlign === 'string') {
      link.dataset.verticalAlign = node.verticalAlign;
      link.style.verticalAlign = node.verticalAlign;
    }
    if (typeof node.title === 'string') link.title = node.title;

    applyCommonStructuredAttributes(link, node);

    const container = document.createElement('span');
    container.className = 'gloss-image-container';
    container.style.width = `${trimFloat(usedWidth)}em`;
    container.style.maxWidth = '100%';
    if (typeof node.border === 'string') container.style.border = node.border;
    if (typeof node.borderRadius === 'string') container.style.borderRadius = node.borderRadius;

    const sizer = document.createElement('span');
    sizer.className = 'gloss-image-sizer';
    sizer.style.paddingTop = `${trimFloat(invAspectRatio * 100)}%`;
    container.appendChild(sizer);

    const bg = document.createElement('span');
    bg.className = 'gloss-image-background';
    container.appendChild(bg);

    const img = document.createElement('img');
    img.className = 'gloss-image gloss-sc-img';
    img.loading = 'lazy';
    img.style.maxWidth = '100%';
    img.decoding = 'async';
    if (typeof node.title === 'string') img.alt = node.title;
    container.appendChild(img);

    if (src) {
      observeDictionaryMedia(link, () => {
        bg.style.setProperty('--image', `url("${src}")`);
        img.src = src;
        link.dataset.imageLoadState = 'loaded';
      });
    }

    link.appendChild(container);
    return link;
  }

  function isKana(ch) {
    return (ch >= '\u3041' && ch <= '\u3096') || (ch >= '\u30a1' && ch <= '\u30fa');
  }

  function katakanaToHiragana(str) {
    return str.replace(/[\u30a1-\u30fa]/g, function(ch) {
      return String.fromCharCode(ch.charCodeAt(0) - 0x60);
    });
  }

  function distributeFurigana(expression, reading) {
    if (!reading || reading === expression) {
      return [{text: expression, reading: ''}];
    }

    const readingNorm = katakanaToHiragana(reading);

    const groups = [];
    let cur = null;
    for (const c of expression) {
      const kana = isKana(c);
      if (cur && cur.kana === kana) { cur.text += c; }
      else { cur = {kana, text: c}; groups.push(cur); }
    }

    if (groups.length === 1) {
      if (groups[0].kana) return [{text: expression, reading: ''}];
      return [{text: expression, reading: reading}];
    }

    // Pass 1: strict Yomitan-style matching (kana must appear in reading)
    function tryMatch(groupIdx, readIdx) {
      if (groupIdx >= groups.length) {
        return readIdx >= reading.length ? [] : null;
      }
      const group = groups[groupIdx];
      if (group.kana) {
        const kn = katakanaToHiragana(group.text);
        if (readingNorm.startsWith(kn, readIdx)) {
          const rest = tryMatch(groupIdx + 1, readIdx + group.text.length);
          if (rest !== null) return [{text: group.text, reading: ''}].concat(rest);
        }
        return null;
      } else {
        let result = null;
        for (let i = reading.length; i >= readIdx + group.text.length; --i) {
          const rest = tryMatch(groupIdx + 1, i);
          if (rest !== null) {
            if (result !== null) return null;
            result = [{text: group.text, reading: reading.substring(readIdx, i)}].concat(rest);
          }
        }
        return result;
      }
    }

    const strict = tryMatch(0, 0);
    if (strict !== null) return strict;

    // Pass 2: edge-match kana from start/end, distribute middle to kanji
    let readPos = 0;
    let gFront = 0;
    while (gFront < groups.length && groups[gFront].kana) {
      const kn = katakanaToHiragana(groups[gFront].text);
      if (readingNorm.startsWith(kn, readPos)) { readPos += groups[gFront].text.length; gFront++; }
      else break;
    }

    let gBack = groups.length - 1;
    let readBack = reading.length;
    while (gBack >= gFront && groups[gBack].kana) {
      const kn = katakanaToHiragana(groups[gBack].text);
      const endPos = readBack - groups[gBack].text.length;
      if (endPos >= readPos && readingNorm.substring(endPos, readBack) === kn) { readBack = endPos; gBack--; }
      else break;
    }

    const result = [];
    for (let i = 0; i < gFront; i++) result.push({text: groups[i].text, reading: ''});

    const middle = groups.slice(gFront, gBack + 1);
    if (middle.length > 0) {
      const midReading = reading.substring(readPos, readBack);
      const kanjiGroups = middle.filter(g => !g.kana);
      const totalKanjiLen = kanjiGroups.reduce((s, g) => s + g.text.length, 0);
      if (totalKanjiLen > 0) {
        let rp = readPos;
        for (const g of middle) {
          if (g.kana) {
            result.push({text: g.text, reading: ''});
          } else {
            const take = Math.round((g.text.length / totalKanjiLen) * midReading.length);
            const segEnd = Math.min(rp + Math.max(take, g.text.length), readBack);
            result.push({text: g.text, reading: reading.substring(rp, segEnd)});
            rp = segEnd;
          }
        }
      } else {
        for (const g of middle) result.push({text: g.text, reading: ''});
      }
    }

    for (let i = gBack + 1; i < groups.length; i++) result.push({text: groups[i].text, reading: ''});

    if (result.some(s => s.reading !== '')) return result;

    return [{text: expression, reading: reading}];
  }

  function appendWithKanjiSpans(parent, text) {
    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (isKanjiCodepoint(ch.codePointAt(0))) {
        const span = document.createElement('span');
        span.className = 'kanji-tappable';
        span.textContent = ch;
        parent.appendChild(span);
      } else {
        parent.appendChild(document.createTextNode(ch));
      }
    }
  }

  function createHeadwordNode(expression, reading, termTags) {
    const headword = document.createElement('span');
    headword.className = 'headword';
    
    const popularityClass = (() => {
        const t = termTags ? termTags.toLowerCase() : '';
        if (t.includes('popular') || t.includes(' p ')) return 'popular';
        if (t.includes('rare') || t.includes('arch') || t.includes('obs')) return 'rare';
        return '';
    })();

    const segments = distributeFurigana(expression, reading);

    for (const segment of segments) {
      if (segment.reading) {
        const ruby = document.createElement('ruby');
        ruby.className = 'headword-text-container headword-term';
        if (popularityClass) ruby.classList.add(popularityClass);
        appendWithKanjiSpans(ruby, segment.text);

        const rt = document.createElement('rt');
        rt.className = 'headword-furigana';
        rt.textContent = segment.reading;

        ruby.appendChild(rt);
        headword.appendChild(ruby);
      } else {
        const termNode = document.createElement('span');
        termNode.className = 'headword-term';
        if (popularityClass) termNode.classList.add(popularityClass);
        appendWithKanjiSpans(termNode, segment.text);
        headword.appendChild(termNode);
      }
    }

    if (termTags) {
      const tagList = document.createElement('span');
      tagList.className = 'headword-tag-list';
      termTags.split(/\s+/).forEach(t => {
        if (t) tagList.appendChild(createTag(t));
      });
      headword.appendChild(tagList);
    }

    return headword;
  }

  function createDeinflectionRow(process, rules, ruleTags) {
    if (!process || !Array.isArray(process) || process.length === 0) return null;

    const row = document.createElement('div');
    row.className = 'entry-deinflection-row';

    // Clean name only for summary chips
    const cleanProcess = process.map(p => p.name);

    const label = document.createElement('span');
    label.className = 'deinflection-label';
    // Left→right: surface→base. process[0] is the last step applied so reverse.
    label.textContent = cleanProcess.slice().reverse().join(' » ');
    row.appendChild(label);

    // ── Details panel ─────────────────────────────────────────
    const details = document.createElement('div');
    details.className = 'inflection-details';
    details.style.display = 'none';

    // Word-class rules chips at the top (e.g. "v5" "adj-i")
    const ruleSet = Array.isArray(ruleTags) ? ruleTags.filter(Boolean) : (rules ? [...new Set(rules.split(/\s+/).filter(Boolean))] : []);
    if (ruleSet.length > 0) {
      const rulesEl = document.createElement('div');
      rulesEl.className = 'inflection-rules-row';
      ruleSet.forEach(r => {
        const chip = document.createElement('span');
        chip.className = 'deinflection-tag';
        chip.textContent = r;
        rulesEl.appendChild(chip);
      });
      details.appendChild(rulesEl);
    }

    // Each deinflection step as its own card — makes clear where one rule ends
    // and the next begins. Reversed so card 1 = first transformation applied.
    const stepsToShow = [...process].reverse();
    stepsToShow.forEach((p, i) => {
      const name = p.name;
      const desc = p.description;

      const card = document.createElement('div');
      card.className = 'inflection-step-card';

      const header = document.createElement('div');
      header.className = 'inflection-step-header';

      const idx = document.createElement('span');
      idx.className = 'inflection-step-index';
      idx.textContent = String(i + 1);

      const nameEl = document.createElement('span');
      nameEl.className = 'inflection-step-name';
      nameEl.textContent = name;

      header.appendChild(idx);
      header.appendChild(nameEl);
      card.appendChild(header);

      if (desc) {
        const descEl = document.createElement('p');
        descEl.className = 'inflection-step-desc';
        descEl.textContent = desc;
        card.appendChild(descEl);
      }

      details.appendChild(card);
    });

    let expanded = false;
    row.onclick = (e) => {
      e.stopPropagation();
      expanded = !expanded;
      details.style.display = expanded ? 'block' : 'none';
    };

    return { row, details };
  }

  const ICONS = {
    volume_up: '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-7-8.77z"/></svg>',
    add_circle: '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm5 11h-4v5h-2v-5H7v-2h4V7h2v4h4v2z"/></svg>',
    check_circle: '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>',
    content_copy: '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M19 21H8c-1.1 0-2-.9-2-2V7h2v12h11v2zM16 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12H4V5h12v10z"/></svg>',
    expand_more: '<svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor"><path d="M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z"/></svg>',
    menu_book: '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path transform="translate(0, 24) scale(0.025)" d="M260-320q47 0 91.5 10.5T440-278v-394q-41-24-87-36t-93-12q-36 0-71.5 7T120-692v396q35-12 69.5-18t70.5-6Zm260 42q44-21 88.5-31.5T700-320q36 0 70.5 6t69.5 18v-396q-33-14-68.5-21t-71.5-7q-47 0-93 12t-87 36v394Zm-40 118q-48-38-104-59t-116-21q-42 0-82.5 11T100-198q-21 11-40.5-1T40-234v-482q0-11 5.5-21T62-752q46-24 96-36t102-12q58 0 113.5 15T480-740q51-30 106.5-45T700-800q52 0 102 12t96 36q11 5 16.5 15t5.5 21v482q0 23-19.5 35t-40.5 1q-37-20-77.5-31T700-240q-60 0-116 21t-104 59ZM280-494Z"/></svg>',
    arrow_upward: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M4 12l1.41 1.41L11 7.83V20h2V7.83l5.58 5.59L20 12l-8-8-8 8z"/></svg>',
    arrow_downward: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M20 12l-1.41-1.41L13 16.17V4h-2v12.17l-5.58-5.59L4 12l8 8 8-8z"/></svg>',
    arrow_back: '<svg viewBox="0 0 24 24" width="24" height="24" fill="currentColor"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"/></svg>'
  };

  function createAudioButton(expression, reading) {
    const audioBtn = document.createElement('button');
    audioBtn.className = 'word-audio-btn';
    audioBtn.innerHTML = ICONS.volume_up;
    audioBtn.title = 'Play Word Audio';
    audioBtn.dataset.expression = expression;
    audioBtn.dataset.reading = reading;
    
    audioBtn.onclick = (e) => {
      e.stopPropagation();
      if (typeof WordAudioBridge !== 'undefined') {
        const callbackId = 'audio_' + Date.now();
        audioBtn.classList.add('loading');
        
        window.DictionaryRenderer.onAudioResults = (id, results) => {
          if (id !== callbackId) return;
          audioBtn.classList.remove('loading');
          
          if (results && results.length > 0) {
            WordAudioBridge.playAudio(results[0].url);
          } else {
            audioBtn.classList.add('error');
            setTimeout(() => audioBtn.classList.remove('error'), 1000);
          }
        };
        
        WordAudioBridge.fetchAudio(expression, reading, callbackId);
      }
    };
    return audioBtn;
  }

  function buildOrderedDictionaryNames(groups, dictionaryOrder) {
    const groupNames = Array.from(groups.keys());
    const ordered = [];
    const seen = new Set();
    if (Array.isArray(dictionaryOrder)) {
      for (const name of dictionaryOrder) {
        if (groups.has(name) && !seen.has(name)) {
          ordered.push(name);
          seen.add(name);
        }
      }
    }
    for (const name of groupNames) {
      if (!seen.has(name)) ordered.push(name);
    }
    return ordered;
  }

  function computeDictionaryCollapseStates(groups, collapseConfig) {
    const mode = collapseConfig && collapseConfig.mode ? collapseConfig.mode : 'expand_all';
    const dictionaryOrder = collapseConfig && Array.isArray(collapseConfig.dictionaryOrder) ? collapseConfig.dictionaryOrder : [];
    const displayModes = collapseConfig && collapseConfig.displayModes && typeof collapseConfig.displayModes === 'object'
      ? collapseConfig.displayModes
      : {};
    const orderedNames = buildOrderedDictionaryNames(groups, dictionaryOrder);
    const open = new Set();

    if (mode === 'collapse_all') {
      return open;
    }

    if (mode === 'expand_first_available') {
      if (orderedNames.length > 0) open.add(orderedNames[0]);
      return open;
    }

    if (mode === 'custom') {
      // Cascade: iterate priority order once.
      // Each dictionary opens iff no preceding non-collapsed dict has already opened.
      //   always_expanded  → always opens (blocks subsequent fallbacks)
      //   fallback         → opens only if nothing above it opened yet
      //   always_collapsed → never opens, does NOT block fallbacks
      let contentOpened = false;
      for (const dictName of orderedNames) {
        const dictMode = displayModes[dictName] || 'fallback';
        if (dictMode === 'always_collapsed') continue;
        if (dictMode === 'always_expanded' || !contentOpened) {
          open.add(dictName);
          contentOpened = true;
        }
      }
      return open;
    }

    for (const dictName of orderedNames) open.add(dictName);
    return open;
  }

  function appendDefinitionsSection(body, glossaries, mediaMap, group, collapseConfig) {
    if (!glossaries || glossaries.length === 0) return;

    const groups = new Map();
    glossaries.forEach((gloss) => {
      const name = gloss.dictName || 'Unknown';
      if (!groups.has(name)) groups.set(name, []);
      groups.get(name).push(gloss);
    });
    const openDictionaries = computeDictionaryCollapseStates(groups, collapseConfig);

    for (const [dictName, dictGlossaries] of groups) {
      const dictSection = document.createElement('div');
      dictSection.className = 'dictionary-group';
      dictSection.dataset.dictionary = dictName;
      dictSection.dataset.collapsed = String(!openDictionaries.has(dictName));

      const dictHeader = document.createElement('div');
      dictHeader.className = 'dictionary-header';
      
      const arrow = document.createElement('span');
      arrow.className = 'dict-arrow';
      arrow.innerHTML = ICONS.expand_more;
      dictHeader.appendChild(arrow);

      const label = document.createElement('span');
      label.className = 'dict-name-label';
      label.textContent = dictName;
      dictHeader.appendChild(label);

      let _collapseTogglePending = false;
      dictHeader.onclick = (e) => {
        e.stopPropagation(); // Prevent recursive lookup on header click
        // Long-press (handled by pointerdown timer) sets this flag to select dict
        // Short tap toggles collapse
        if (_collapseTogglePending) { _collapseTogglePending = false; return; }
        const isCollapsed = dictSection.dataset.collapsed === 'true';
        dictSection.dataset.collapsed = String(!isCollapsed);
      };

      // Long-press to select dictionary for Anki export (preserved from original)
      let _pressTimer = null;
      dictHeader.addEventListener('pointerdown', () => {
        _pressTimer = setTimeout(() => {
          _collapseTogglePending = true;
          const entryArticle = dictSection.closest('article');
          const entryIdx = entryArticle ? entryArticle.dataset.index : null;
          if (entryIdx == null) return;
          const prevDictName = _selectedDictionaries[entryIdx];
          if (prevDictName === dictName) {
            delete _selectedDictionaries[entryIdx];
            dictHeader.classList.remove('selected');
          } else {
            if (prevDictName) {
              const prevHeader = entryArticle.querySelector('.dictionary-header.selected');
              if (prevHeader) prevHeader.classList.remove('selected');
            }
            _selectedDictionaries[entryIdx] = dictName;
            dictHeader.classList.add('selected');
          }
        }, 400);
      });
      const cancelPress = () => { clearTimeout(_pressTimer); };
      dictHeader.addEventListener('pointerup', cancelPress);
      dictHeader.addEventListener('pointercancel', cancelPress);

      const copyButton = document.createElement('button');
      copyButton.className = 'dictionary-copy-btn';
      copyButton.innerHTML = ICONS.content_copy;
      copyButton.title = 'Copy translations';
      copyButton.addEventListener('pointerdown', (e) => {
        e.stopPropagation();
        cancelPress();
      });
      copyButton.onclick = (e) => {
        e.stopPropagation();
        const translations = Array.from(ol.querySelectorAll('.gloss-content'))
          .flatMap((content) => {
            const glossaryNodes = content.querySelectorAll('[data-sc-content="glossary"]');
            const textNodes = glossaryNodes.length > 0 ? glossaryNodes : [content];
            return Array.from(textNodes, (node) => node.innerText.trim());
          })
          .filter(Boolean)
          .join('\n');
        if (!translations || !window.DictionaryClipboard) return;
        window.DictionaryClipboard.copyTranslation(translations);
        copyButton.innerHTML = ICONS.check_circle;
        setTimeout(() => { copyButton.innerHTML = ICONS.content_copy; }, 1000);
      };
      dictHeader.appendChild(copyButton);
      
      dictSection.appendChild(dictHeader);

      const ol = document.createElement('ol');
      ol.className = 'definition-list';
      ol.dataset.count = String(dictGlossaries.length);

      dictGlossaries.forEach((gloss) => {
        const li = document.createElement('li');
        li.className = 'definition-item';
        li.dataset.dictionary = dictName;
        
        const content = document.createElement('div');
        content.className = 'definition-item-content';
        
        const definitionTags = Array.isArray(gloss.definitionTagList)
          ? gloss.definitionTagList
          : (gloss.definitionTags ? gloss.definitionTags.split(/\s+/).filter(Boolean) : []);
        if (definitionTags.length > 0) {
          const tagList = document.createElement('div');
          tagList.className = 'definition-tag-list';
          definitionTags.forEach(t => {
            if (t) tagList.appendChild(createTag(t));
          });
          content.appendChild(tagList);
        }

        const glossContainer = document.createElement('div');
        glossContainer.className = 'gloss-content';
        glossContainer.appendChild(buildGlossaryNode(String(gloss.glossary || ''), dictName, mediaMap));
        content.appendChild(glossContainer);
        
        li.appendChild(content);
        ol.appendChild(li);
      });

      dictSection.appendChild(ol);
      body.appendChild(dictSection);
    }
  }

  function collectFrequencyNumbers(frequencies) {
    const numbers = [];
    const seen = new Set();
    for (const group of frequencies) {
      if (seen.has(group.dictName)) continue;
      seen.add(group.dictName);
      const items = Array.isArray(group.frequencies) ? group.frequencies : [];
      for (const item of items) {
        if (item && item.value > 0) {
          numbers.push(item.value);
          break;
        }
      }
    }
    return numbers;
  }

  function formatFrequencyRank(value) {
    if (!Number.isFinite(value) || value <= 0) return null;
    const rounded = Math.round(value * 10) / 10;
    return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
  }

  function appendAverageFrequencyChip(section, frequencies, averageRank) {
    let average = Number.isFinite(averageRank) && averageRank > 0 ? averageRank : null;
    if (average === null) {
      const numbers = collectFrequencyNumbers(frequencies);
      if (numbers.length > 0) {
        average = numbers.reduce((sum, num) => sum + num, 0) / numbers.length;
      }
    }

    const averageText = formatFrequencyRank(average);
    if (averageText !== null) {
      const tag = createTagWithBody('avg', averageText, 'frequency');
      tag.classList.add('frequency-group-item');
      tag.dataset.details = 'Average';
      const labelEl = tag.querySelector('.tag-label');
      if (labelEl) {
        labelEl.dataset.details = 'Average';
      }
      section.appendChild(tag);
    }
  }

  function appendFrequenciesSection(body, frequencies, showHarmonic, showAverage, harmonicRank, averageRank) {
    if (frequencies.length === 0) return;

    const section = document.createElement('div');
    section.className = 'entry-body-section frequency-top-section';
    section.dataset.sectionType = 'frequencies';

    // If showHarmonic is true, calculate and display harmonic mean instead of full list
    if (showHarmonic) {
      let harmonic = Number.isFinite(harmonicRank) && harmonicRank > 0 ? Math.floor(harmonicRank) : null;
      if (harmonic === null) {
        const numbers = collectFrequencyNumbers(frequencies);
        if (numbers.length > 0) {
          const n = numbers.length;
          let reciprocalSum = 0;
          for (const num of numbers) {
            reciprocalSum += 1 / num;
          }
          harmonic = Math.floor(n / reciprocalSum);
        }
      }

      if (harmonic !== null) {
        const tag = createTagWithBody('freq', `harmonic: ${harmonic}`, 'frequency');
        section.appendChild(tag);
      }
      if (showAverage) {
        appendAverageFrequencyChip(section, frequencies, averageRank);
      }
      if (section.childElementCount > 0) {
        body.appendChild(section);
      }
      return;
    }

    if (showAverage) {
      appendAverageFrequencyChip(section, frequencies, averageRank);
    }

    // Default: show compact top-row chips like Yomitan frequency groups.
    for (const group of frequencies) {
      const dictName = String(group.dictName || '').trim();
      const displayText = typeof group.displayValueText === 'string'
        ? group.displayValueText
        : (() => {
          const items = Array.isArray(group.frequencies) ? group.frequencies : [];
          const values = [];
          items.forEach((item) => {
            const value = item && item.displayValue ? item.displayValue : String(item && item.value ? item.value : '');
            if (value) values.push(value);
          });
          return values.join(', ');
        })();

      if (!dictName || !displayText) {
        continue;
      }

      const chip = createTagWithBody(dictName, displayText, 'frequency');
      chip.classList.add('frequency-group-item');
      section.appendChild(chip);
    }

    if (section.childElementCount > 0) {
      body.appendChild(section);
    }
  }



  // ── Pitch accent helpers (exact port of Yomitan's japanese.js) ───────────

  // Exact small-kana set from japanese.js — Set lookup is faster than RegExp
  // and this matches the canonical source character-for-character.
  const SMALL_KANA_SET = new Set('ぁぃぅぇぉゃゅょゎァィゥェォャュョヮ');

  // DIACRITIC_MAPPING — built from the same kana triplet string used in
  // japanese.js.  Each triplet is: base, dakuten-form, handakuten-form ('-' = none).
  // Maps voiced/semi-voiced kana → {character (unvoiced base), type}.
  const _kana = 'うゔ-かが-きぎ-くぐ-けげ-こご-さざ-しじ-すず-せぜ-そぞ-ただ-ちぢ-つづ-てで-とど-はばぱひびぴふぶぷへべぺほぼぽワヷ-ヰヸ-ウヴ-ヱヹ-ヲヺ-カガ-キギ-クグ-ケゲ-コゴ-サザ-シジ-スズ-セゼ-ソゾ-タダ-チヂ-ツヅ-テデ-トド-ハバパヒビピフブプヘベペホボポ';
  const DIACRITIC_MAPPING = new Map();
  for (let _i = 0, _ii = _kana.length; _i < _ii; _i += 3) {
    const _base     = _kana[_i];
    const _dakuten  = _kana[_i + 1];
    const _handakuten = _kana[_i + 2];
    DIACRITIC_MAPPING.set(_dakuten,   {character: _base, type: 'dakuten'});
    if (_handakuten !== '-') {
      DIACRITIC_MAPPING.set(_handakuten, {character: _base, type: 'handakuten'});
    }
  }

  /**
   * getKanaMorae — exact port of japanese.js getKanaMorae.
   * Uses SMALL_KANA_SET (a Set) instead of a RegExp for correctness and speed.
   * @param {string} text
   * @returns {string[]}
   */
  function getMorae(text) {
    const morae = [];
    let i;
    for (const c of text) {
      if (SMALL_KANA_SET.has(c) && (i = morae.length) > 0) {
        morae[i - 1] += c;
      } else {
        morae.push(c);
      }
    }
    return morae;
  }

  /**
   * isMoraPitchHigh — exact port of japanese.js isMoraPitchHigh.
   * String pitch values: index into the H/L string directly, no clamping
   * (out-of-bounds → undefined !== 'H' → false, matching JS source behaviour).
   * @param {number} moraIndex
   * @param {number|string} pitchAccentValue
   * @returns {boolean}
   */
  function isMoraPitchHigh(moraIndex, pitchAccentValue) {
    if (typeof pitchAccentValue === 'string') {
      return pitchAccentValue[moraIndex] === 'H';
    }
    switch (pitchAccentValue) {
      case 0:  return (moraIndex > 0);
      case 1:  return (moraIndex < 1);
      default: return (moraIndex > 0 && moraIndex < pitchAccentValue);
    }
  }

  /**
   * getKanaDiacriticInfo — exact port of japanese.js getKanaDiacriticInfo.
   * Returns {character, type} for voiced/semi-voiced kana, null otherwise.
   * The `type` field ('dakuten' | 'handakuten') is included for full parity
   * even though the nasal indicator only uses `character`.
   * @param {string} character
   * @returns {{character: string, type: string}|null}
   */
  function getKanaDiacriticInfo(character) {
    const info = DIACRITIC_MAPPING.get(character);
    return typeof info !== 'undefined' ? {character: info.character, type: info.type} : null;
  }

  // ── SVG graph (Yomitan createPronunciationGraph parity) ──────────────────

  function _graphCircle(className, x, y, r) {
    const c = document.createElementNS(SVG_NAMESPACE, 'circle');
    c.setAttribute('class', className);
    c.setAttribute('cx', String(x));
    c.setAttribute('cy', String(y));
    c.setAttribute('r', String(r));
    return c;
  }

  /**
   * Exact port of PronunciationGenerator.createPronunciationGraph.
   * viewBox: 0 0 (50*(n+1)) 100  — dots at y=25 (high) or y=75 (low).
   */
  function createPitchDiagram(morae, pitchPositions) {
    const ii = morae.length;
    const svg = document.createElementNS(SVG_NAMESPACE, 'svg');
    svg.setAttribute('xmlns', SVG_NAMESPACE);
    svg.setAttribute('class', 'pronunciation-graph');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('viewBox', `0 0 ${50 * (ii + 1)} 100`);

    if (ii <= 0) return svg;

    // Two paths appended first so dots render on top (matches Yomitan order).
    const path1 = document.createElementNS(SVG_NAMESPACE, 'path');
    svg.appendChild(path1);
    const path2 = document.createElementNS(SVG_NAMESPACE, 'path');
    svg.appendChild(path2);

    const pathPoints = [];
    for (let i = 0; i < ii; i++) {
      const highPitch     = isMoraPitchHigh(i,     pitchPositions);
      const highPitchNext = isMoraPitchHigh(i + 1, pitchPositions);
      const x = i * 50 + 25;
      const y = highPitch ? 25 : 75;

      if (highPitch && !highPitchNext) {
        // Downstep dot: outer ring + inner filled circle
        svg.appendChild(_graphCircle('pronunciation-graph-dot-downstep1', x, y, 15));
        svg.appendChild(_graphCircle('pronunciation-graph-dot-downstep2', x, y, 5));
      } else {
        svg.appendChild(_graphCircle('pronunciation-graph-dot', x, y, 15));
      }
      pathPoints.push(`${x} ${y}`);
    }

    path1.setAttribute('class', 'pronunciation-graph-line');
    path1.setAttribute('d', `M${pathPoints.join(' L')}`);

    // Tail segment from last mora to the "next mora" position
    pathPoints.splice(0, ii - 1);
    {
      const highPitch = isMoraPitchHigh(ii, pitchPositions);
      const x = ii * 50 + 25;
      const y = highPitch ? 25 : 75;
      // Triangle marker at tail end
      const tri = document.createElementNS(SVG_NAMESPACE, 'path');
      tri.setAttribute('class', 'pronunciation-graph-triangle');
      tri.setAttribute('d', 'M0 13 L15 -13 L-15 -13 Z');
      tri.setAttribute('transform', `translate(${x},${y})`);
      svg.appendChild(tri);
      pathPoints.push(`${x} ${y}`);
    }

    path2.setAttribute('class', 'pronunciation-graph-line-tail');
    path2.setAttribute('d', `M${pathPoints.join(' L')}`);

    return svg;
  }

  // ── Downstep notation [N] span (Yomitan createPronunciationDownstepPosition) ─

  function createDownstepNotation(pitchPositions) {
    const pos = String(pitchPositions);
    const n1 = document.createElement('span');
    n1.className = 'pronunciation-downstep-notation';
    n1.dataset.downstepPosition = pos;

    let n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-prefix';
    n2.textContent = '[';
    n1.appendChild(n2);

    n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-number';
    n2.textContent = pos;
    n1.appendChild(n2);

    n2 = document.createElement('span');
    n2.className = 'pronunciation-downstep-notation-suffix';
    n2.textContent = ']';
    n1.appendChild(n2);

    return n1;
  }

  // ── Mora text line (Yomitan createPronunciationText parity) ──────────────

  /**
   * Full port of PronunciationGenerator.createPronunciationText.
   * Supports nasalPositions and devoicePositions arrays (1-based, matching
   * Yomitan's convention where position 1 = first mora).
   * Each mora gets:
   *   - data-position, data-pitch, data-pitchNext on the .pronunciation-mora span
   *   - one .pronunciation-character span per character in the mora
   *   - optional .pronunciation-devoice-indicator span (devoiced morae)
   *   - optional .pronunciation-nasal-indicator + .pronunciation-nasal-diacritic
   *     wrapped in a .pronunciation-character-group (nasal morae)
   *   - .pronunciation-mora-line span (always last child, styled via CSS)
   */
  function createPitchTextLine(morae, pitchPositions, nasalPositions, devoicePositions) {
    const nasalSet   = (nasalPositions   && nasalPositions.length   > 0) ? new Set(nasalPositions)   : null;
    const devoiceSet = (devoicePositions && devoicePositions.length > 0) ? new Set(devoicePositions) : null;

    const container = document.createElement('span');
    container.className = 'pronunciation-text';

    for (let i = 0, ii = morae.length; i < ii; i++) {
      const i1 = i + 1; // 1-based position used by Yomitan for nasal/devoice sets
      const mora = morae[i];
      const highPitch     = isMoraPitchHigh(i,  pitchPositions);
      const highPitchNext = isMoraPitchHigh(i1, pitchPositions);
      const isNasal   = nasalSet   !== null && nasalSet.has(i1);
      const isDevoice = devoiceSet !== null && devoiceSet.has(i1);

      const moraSpan = document.createElement('span');
      moraSpan.className = 'pronunciation-mora';
      moraSpan.dataset.position = String(i);
      moraSpan.dataset.pitch     = highPitch     ? 'high' : 'low';
      moraSpan.dataset.pitchNext = highPitchNext ? 'high' : 'low';

      // One .pronunciation-character span per character in the mora
      const characterNodes = [];
      for (const ch of mora) {
        const charSpan = document.createElement('span');
        charSpan.className = 'pronunciation-character';
        charSpan.textContent = ch;
        moraSpan.appendChild(charSpan);
        characterNodes.push(charSpan);
      }

      // Devoice indicator — a dotted circle overlaid on the mora
      if (isDevoice) {
        moraSpan.dataset.devoice = 'true';
        const indicator = document.createElement('span');
        indicator.className = 'pronunciation-devoice-indicator';
        moraSpan.appendChild(indicator);
      }

      // Nasal indicator — replaces voiced kana with unvoiced base, adds
      // a hidden combining handakuten span + a visible small circle indicator,
      // all wrapped in a .pronunciation-character-group around the first char.
      if (isNasal && characterNodes.length > 0) {
        moraSpan.dataset.nasal = 'true';

        const group = document.createElement('span');
        group.className = 'pronunciation-character-group';

        const firstCharNode = characterNodes[0];
        const originalChar = firstCharNode.textContent;
        const diacriticInfo = getKanaDiacriticInfo(originalChar);
        if (diacriticInfo !== null) {
          moraSpan.dataset.originalText = mora;
          firstCharNode.dataset.originalText = originalChar;
          firstCharNode.textContent = diacriticInfo.character;
        }

        // Hidden combining handakuten (゜) — preserves copy-paste output
        const diacritic = document.createElement('span');
        diacritic.className = 'pronunciation-nasal-diacritic';
        diacritic.textContent = '\u309a';
        group.appendChild(diacritic);

        // Visible indicator dot
        const indicator = document.createElement('span');
        indicator.className = 'pronunciation-nasal-indicator';
        group.appendChild(indicator);

        // Replace firstCharNode in the mora span with the group, then
        // insert the character back as the first child of the group.
        firstCharNode.parentNode.replaceChild(group, firstCharNode);
        group.insertBefore(firstCharNode, group.firstChild);
      }

      // Pitch line — always the last child, displayed/hidden via CSS
      const line = document.createElement('span');
      line.className = 'pronunciation-mora-line';
      moraSpan.appendChild(line);

      container.appendChild(moraSpan);
    }

    return container;
  }

  // ── Pitches section ───────────────────────────────────────────────────────

  /**
   * pitches array entries now support:
   *   group.nasalPositions   — number[] (1-based mora indices), optional
   *   group.devoicePositions — number[] (1-based mora indices), optional
   *
   * The downstep notation span is always emitted (hidden by default via CSS,
   * shown when custom dictionary CSS targets .pronunciation-downstep-notation).
   */
  function appendPitchesSection(body, pitches, reading, showDiagram, showNumber, showText) {
    if (pitches.length === 0 || (!showDiagram && !showNumber && !showText)) return;

    const section = document.createElement('div');
    section.className = 'entry-body-section pitches-section';
    section.dataset.sectionType = 'pronunciations';

    const groupList = document.createElement('ul');
    groupList.className = 'pronunciation-group-list';
    groupList.style.listStyle = 'none';
    groupList.style.padding = '0';
    groupList.style.margin = '0';
    section.appendChild(groupList);

    for (const group of pitches) {
      const dictName       = String(group.dictName || '');
      const positions      = Array.isArray(group.pitchPositions)    ? group.pitchPositions    : [];
      const nasalPos       = Array.isArray(group.nasalPositions)     ? group.nasalPositions     : [];
      const devoicePos     = Array.isArray(group.devoicePositions)   ? group.devoicePositions   : [];
      if (positions.length === 0) continue;

      const groupContainer = document.createElement('li');
      groupContainer.className = 'pitch-entry';

      const tag = document.createElement('div');
      tag.className = 'pitch-group-tag pronunciation-group-tag-list';
      tag.textContent = dictName;
      groupContainer.appendChild(tag);

      const morae = getMorae(reading || '');

      for (const pos of positions) {
        const row = document.createElement('div');
        row.style.cssText = 'display:flex;align-items:center;gap:0.5em;flex-wrap:wrap';

        if (showDiagram && morae.length > 0) {
          row.appendChild(createPitchDiagram(morae, pos));
        }

        if (showText && morae.length > 0) {
          row.appendChild(createPitchTextLine(morae, pos, nasalPos, devoicePos));
        }

        // Number notation(s) after kana — [N] downstep notation always emitted
        // for CSS parity; showNumber controls legacy .pitch-number span.
        const downstepSpan = createDownstepNotation(pos);
        const downstepContainer = document.createElement('span');
        downstepContainer.className = 'pronunciation-downstep-notation-container';
        downstepContainer.appendChild(downstepSpan);
        row.appendChild(downstepContainer);

        if (showNumber) {
          const num = document.createElement('span');
          num.className = 'pitch-number';
          num.textContent = `[${pos}]`;
          row.appendChild(num);
        }

        groupContainer.appendChild(row);
      }

      groupList.appendChild(groupContainer);
    }

    // Compact layout when only one pitch dict: place name beside pattern
    if (groupList.childElementCount === 1) {
      section.dataset.compact = 'true';
    }

    if (section.childElementCount > 0) {
      body.appendChild(section);
    }
  }

  function renderEntry(result, mediaMap, showFrequencyHarmonic, showFrequencyAverage, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, groupTerms, ankiDupAction, collapseConfig) {
    const existingSet = Array.isArray(existingExpressions) ? existingExpressions : [];
    const article = document.createElement('article');
    article.className = 'entry';
    article.dataset.index = String(result.index || 0);
    
    // Add data-dictionary attribute for scoped CSS only when this article
    // represents a single dictionary (groupTerms=false). When groupTerms=true
    // the article bundles glossaries from multiple dictionaries, so the
    // attribute is set on each .dictionary-group div instead.
    if (!groupTerms) {
      const dictName = result.term && result.term.glossaries && result.term.glossaries[0] && result.term.glossaries[0].dictName;
      if (dictName) {
        article.dataset.dictionary = dictName;
      }
    }

    const body = document.createElement('div');
    body.className = 'entry-body';
    article.appendChild(body);

    const expression = (result.term && result.term.expression) || result.matched || '';
    const reading = result.term && result.term.reading ? result.term.reading : '';

    const headSection = document.createElement('div');
    headSection.className = 'entry-body-section entry-headword-row';
    const termTags = result.term ? result.term.termTags : '';
    headSection.appendChild(createHeadwordNode(expression, reading, termTags));

    // All icons go into a dedicated flex container so headword's flex:1 never
    // crowds or clips them. Order: Audio (first/leftmost) → Add → Book.
    const iconGroup = document.createElement('div');
    iconGroup.className = 'entry-icon-group';

    if (_wordAudioEnabled) {
      iconGroup.appendChild(createAudioButton(expression, reading));
    }

    if (ankiEnabled) {
      const ankiBtn = document.createElement('button');
      const isAlreadyAdded = existingSet.includes(expression);
      const shouldShowAdd = !isAlreadyAdded || ankiDupAction !== 'prevent';
      ankiBtn.className = isAlreadyAdded && shouldShowAdd ? 'anki-add-btn anki-added' : 'anki-add-btn';
      ankiBtn.innerHTML = isAlreadyAdded && shouldShowAdd ? ICONS.check_circle : ICONS.add_circle;
      ankiBtn.title = isAlreadyAdded ? 'Already in Anki' : 'Add to Anki';
      ankiBtn.style.display = shouldShowAdd ? '' : 'none';
      ankiBtn.setAttribute('data-index', String(result.index || 0));
      ankiBtn.setAttribute('data-expression', expression);
      ankiBtn.setAttribute('data-glossary', '-1');
      ankiBtn.addEventListener('pointerdown', capturePopupSelectionForButton);

      ankiBtn.onclick = (e) => {
        e.stopPropagation();
        if (typeof AnkiBridge !== 'undefined') {
          const entryIdx = ankiBtn.getAttribute('data-index');
          const selectedDict = _selectedDictionaries[entryIdx] || '';
          const selection = consumePopupSelection();
          AnkiBridge.addToAnki(entryIdx, '-1', selectedDict, selection);
        }
      };

      iconGroup.appendChild(ankiBtn);

      // Book always in DOM from the start — shown/hidden via display toggle in
      // updateAnkiStatus, never appended later (keeps order stable).
      const bookBtn = document.createElement('button');
      bookBtn.className = 'anki-open-btn';
      bookBtn.innerHTML = ICONS.menu_book;
      bookBtn.title = 'Open in Anki';
      bookBtn.style.display = isAlreadyAdded ? '' : 'none';
      bookBtn.addEventListener('pointerdown', capturePopupSelectionForButton);
      bookBtn.onclick = (e) => {
        e.stopPropagation();
        if (typeof AnkiBridge !== 'undefined') {
          const entryIdx = ankiBtn.getAttribute('data-index');
          const selectedDict = _selectedDictionaries[entryIdx] || '';
          const selection = consumePopupSelection();
          AnkiBridge.openInAnki(entryIdx, '-1', selectedDict, selection);
        }
      };
      iconGroup.appendChild(bookBtn);
    }

    headSection.appendChild(iconGroup);
    body.appendChild(headSection);

    const deinflection = createDeinflectionRow(result.process, result.term ? result.term.rules : '', result.term ? result.term.ruleTags : null);
    if (deinflection) {
      body.appendChild(deinflection.row);
      body.appendChild(deinflection.details);
    }

    const frequencies = result.term && Array.isArray(result.term.frequencies) ? result.term.frequencies : [];
    appendFrequenciesSection(
      body,
      frequencies,
      showFrequencyHarmonic,
      showFrequencyAverage,
      result.term ? result.term.frequencyHarmonicRank : null,
      result.term ? result.term.frequencyAverageRank : null
    );

    const pitches = result.term && Array.isArray(result.term.pitches) ? result.term.pitches : [];
    appendPitchesSection(body, pitches, reading, showPitchDiagram, showPitchNumber, showPitchText);

    const glossaries = (result.term && Array.isArray(result.term.glossaries)) ? result.term.glossaries : [];
    appendDefinitionsSection(body, glossaries, mediaMap, groupTerms, collapseConfig);

    return article;
  }

  function renderSplitEntries(result, mediaMap, showFrequencyHarmonic, showFrequencyAverage, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, ankiDupAction, collapseConfig) {
    const glossaries = (result.term && Array.isArray(result.term.glossaries)) ? result.term.glossaries : [];
    if (glossaries.length === 0) {
      return [renderEntry(result, mediaMap, showFrequencyHarmonic, showFrequencyAverage, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, false, ankiDupAction, collapseConfig)];
    }

    return glossaries.map((gloss) => {
      const splitResult = Object.assign({}, result, {
        term: Object.assign({}, result.term, {
          glossaries: [gloss]
        })
      });
      return renderEntry(splitResult, mediaMap, showFrequencyHarmonic, showFrequencyAverage, existingExpressions, ankiEnabled, showPitchDiagram, showPitchNumber, showPitchText, false, ankiDupAction, collapseConfig);
    });
  }

  function renderFloatingNav() {
    let container = document.getElementById('floating-nav');
    if (!container) {
      container = document.createElement('div');
      container.id = 'floating-nav';
      container.className = 'floating-nav-container';
      
      const upBtn = document.createElement('button');
      upBtn.className = 'floating-nav-btn';
      upBtn.innerHTML = ICONS.arrow_upward;
      upBtn.onclick = () => window.DictionaryRenderer.navigate(-1);

      const downBtn = document.createElement('button');
      downBtn.className = 'floating-nav-btn';
      downBtn.innerHTML = ICONS.arrow_downward;
      downBtn.onclick = () => window.DictionaryRenderer.navigate(1);

      container.appendChild(upBtn);
      container.appendChild(downBtn);
      document.body.appendChild(container);
    }
  }

  function removeFloatingNav() {
    const nav = document.getElementById('floating-nav');
    if (nav) nav.remove();
  }

  function resetTabBarLayout(container = document.getElementById('entries')) {
    if (_tabsEl && _tabsEl.parentElement) {
      _tabsEl.remove();
    }
    _tabsEl = null;
    if (container) {
      container.style.paddingTop = '';
    }
    document.documentElement.style.setProperty('--lookup-tabs-height', '0px');
  }

  function render(payload) {
    try {
      if (!payload || typeof payload !== 'object') {
        console.error('[DictionaryRenderJS] render: invalid payload:', payload);
        return;
      }
      
      const started = performance.now();
      const root = document.documentElement;
      _wordAudioEnabled = payload.wordAudioEnabled !== false;
      _autoplayGuard = false;
      _selectedDictionaries = {};
      resetDictionaryMediaObserver();
      if (payload.ankiDupAction !== undefined) {
        _lastAnkiDupAction = payload.ankiDupAction;
      }
      root.dataset.compactGlossary = String(payload.compactGlossary === true);
      root.dataset.glossaryLayoutMode = payload.compactGlossary === true ? 'compact' : 'default';

      debugLog('render.start', {
        isDark: !!payload.isDark,
        styles: Array.isArray(payload.styles) ? payload.styles.length : 0,
        results: Array.isArray(payload.results) ? payload.results.length : 0
      });

      const styleNode = document.getElementById('dictionary-styles');
      if (styleNode) {
        const stylesPayload = Array.isArray(payload.styles) ? payload.styles : [];

        // Create a cache key based on dict names and CSS content.
        const stylesKey = stylesPayload
          .map(s => `${s.dictName}:${hashString(s.styles || '')}`)
          .join('|');

        if (styleNode.dataset.cacheKey !== stylesKey) {
          const cssText = buildScopedDictionaryCss(stylesPayload);
          styleNode.textContent = cssText;
          styleNode.dataset.cacheKey = stylesKey;
          prewarmFonts();
          debugLog('render.stylesApplied', {styleTextLength: cssText.length});
        } else {
          debugLog('render.stylesCached', {cacheKey: stylesKey});
        }
      }

      const container = document.getElementById('entries');
      if (!container) return;

      const tabs = Array.isArray(payload.tabs) ? payload.tabs : [];
      const navMode = payload.recursiveNavMode;
      const activeIndex = tabs.findIndex(t => t.active);
      const freshRootLookup = tabs.length <= 1 && activeIndex <= 0;
      if (freshRootLookup) {
        _scrollPositions.clear();
        clearRecursiveSelection();
      }

      // Save scroll position of the previously active tab
      const oldActiveIndex = _tabs.findIndex(t => t.active);
      if (!freshRootLookup && oldActiveIndex >= 0) {
        const oldTab = _tabs[oldActiveIndex];
        const currentY = window.scrollY || document.documentElement.scrollTop || 0;
        const scrollKey = (_navMode || 'tabs') + '-' + oldActiveIndex + '-' + oldTab.label;
        
        // Only save if we have a non-zero scroll, or if it's the first time
        if (currentY > 0 || !_scrollPositions.has(scrollKey)) {
          _scrollPositions.set(scrollKey, currentY);
          debugLog('render.saveScroll', {key: scrollKey, y: currentY});
        }
      }

      // Update state
      _tabs = tabs;
      if (navMode) _navMode = navMode;
      
      // Clear container but preserve the style nodes in head
      container.textContent = '';
      
      
      const mediaMap = payload.mediaDataUris || {};
      const results = Array.isArray(payload.results) ? payload.results : [];

      // Determine whether to show the tab bar:
      // - tabs mode: need at least 2 tabs
      // - stack mode: need active tab to NOT be the first one (activeIndex > 0 means there's a back target)
      const renderRecursiveChrome = payload.renderRecursiveChrome !== false;
      const hasRecursiveChrome = (navMode === 'stack') ? activeIndex > 0 : tabs.length > 1;
      const showTabBar = renderRecursiveChrome && hasRecursiveChrome;
      if (showTabBar) {
        renderTabBar(tabs, navMode);
        // Note: tabs-container is a separate div now, so no need to insertBefore entries
      } else {
        resetTabBarLayout(container);
      }
      const dictionaryOrder = Array.isArray(payload.dictionaryOrder) ? payload.dictionaryOrder : [];

      const existingExpressions = Array.isArray(payload.existingExpressions) ? payload.existingExpressions : [];
      const groupTerms = payload.groupTerms !== false;
      const collapseConfig = {
        mode: payload.dictionaryCollapseMode || 'expand_all',
        dictionaryOrder,
        displayModes: payload.dictionaryDisplayModes || {}
      };

      if (results.length === 0) {
        const empty = document.createElement('div');
        empty.className = 'chima-empty';
        empty.textContent = payload.placeholder || '';
        container.appendChild(empty);
        removeFloatingNav();
        if (freshRootLookup) {
          window.scrollTo(0, 0);
        }
      } else {
        const fragment = document.createDocumentFragment();
        for (const result of results) {
          if (result.kanji) {
            fragment.appendChild(KanjiRenderer.renderEntry(result.kanji, result.matched));
            continue;
          }
          const diagram = payload.showPitchDiagram !== false;
          const number = payload.showPitchNumber !== false;
          const text = payload.showPitchText !== false;
          const average = payload.showFrequencyAverage === true;
          if (groupTerms) {
            fragment.appendChild(renderEntry(result, mediaMap, payload.showFrequencyHarmonic, average, existingExpressions, payload.ankiEnabled, diagram, number, text, groupTerms, payload.ankiDupAction, collapseConfig));
          } else {
            const articles = renderSplitEntries(result, mediaMap, payload.showFrequencyHarmonic, average, existingExpressions, payload.ankiEnabled, diagram, number, text, payload.ankiDupAction, collapseConfig);
            articles.forEach(a => fragment.appendChild(a));
          }
        }
        container.appendChild(fragment);

        // Show floating nav if there are multiple entries and it's enabled in settings
        if (results.length > 1 && payload.showNavigationButtons !== false) {
          renderFloatingNav();
        } else {
          removeFloatingNav();
        }

        // Restore scroll position for the newly active tab
        if (activeIndex >= 0) {
          const activeTab = tabs[activeIndex];
          const scrollKey = (navMode || 'tabs') + '-' + activeIndex + '-' + activeTab.label;
          const savedScroll = _scrollPositions.get(scrollKey) || 0;
          debugLog('render.restoreScroll', {key: scrollKey, y: savedScroll});
          
          if (savedScroll > 0) {
            _isJumping = true;
            requestAnimationFrame(() => {
              window.scrollTo(0, savedScroll);
              setTimeout(() => { _isJumping = false; }, 200);
            });
          } else {
            window.scrollTo(0, 0);
          }
        } else {
          // Fresh lookup — jump to top
          window.scrollTo(0, 0);
        }

        if (payload.wordAudioAutoplay && !_autoplayGuard) {
          _autoplayGuard = true;
          const firstBtn = container.querySelector('.word-audio-btn');
          if (firstBtn) firstBtn.click();
        }
      }

      const elapsed = Math.round(performance.now() - started);
      postContentReady();
      
      // Ensure recursive lookup tap listener is installed if enabled
      if (_lookupEnabled) {
        installTapListener();
      }
    } catch (e) {
      console.error('[DictionaryRenderJS] CRITICAL RENDER ERROR:', e);
      if (e.stack) console.error(e.stack);
    }
  }

  function clear() {
    _autoplayGuard = false;
    clearRecursiveSelection();
    resetDictionaryMediaObserver();
    const container = document.getElementById('entries');
    if (container) {
      container.textContent = '';
    }
    resetTabBarLayout(container);
    _tabs = [];
    removeFloatingNav();
    const styleNode = document.getElementById('dictionary-styles');
    if (styleNode) {
      styleNode.textContent = '';
      delete styleNode.dataset.cacheKey;
    }
  }

  window.DictionaryRenderer = {
    render,
    clear,
    prewarmFonts,

    replacePopupResults() {
      try {
        if (typeof PayloadBridge === 'undefined') {
          console.error('[DictionaryRenderJS] PayloadBridge not available');
          return;
        }
        const configJson = PayloadBridge.getPayloadJson();
        if (!configJson) return;
        const payload = JSON.parse(configJson);

        // Pull results lazily
        const results = [];
        const count = PayloadBridge.getEntryCount();
        for (let index = 0; index < count; index++) {
          const entryJson = PayloadBridge.getEntry(index);
          if (entryJson === null || entryJson === undefined) break;
          results.push(JSON.parse(entryJson));
        }
        payload.results = results;

        _selectedDictionaries = {};
        if (payload.ankiDupAction !== undefined) {
          _lastAnkiDupAction = payload.ankiDupAction;
        }
        render(payload);
      } catch (e) {
        console.error('[DictionaryRenderJS] replacePopupResults error:', e.message);
      }
    },

    setRecursiveLookupEnabled(enabled) {
      _lookupEnabled = enabled;
      if (enabled) installTapListener();
    },

    resolveRecursiveSelection,
    clearRecursiveSelection,

    navigate(delta) {
      const groups = document.querySelectorAll('.entry');
      if (groups.length === 0) return;

      // Simple heuristic: find the group closest to the top of the viewport
      let currentIndex = -1;
      let minDiff = Infinity;
      const viewportTop = 0;

      groups.forEach((el, index) => {
        const rect = el.getBoundingClientRect();
        const diff = Math.abs(rect.top - viewportTop);
        if (diff < minDiff) {
          minDiff = diff;
          currentIndex = index;
        }
      });

      let nextIndex = currentIndex + delta;
      if (nextIndex < 0) nextIndex = 0;
      if (nextIndex >= groups.length) nextIndex = groups.length - 1;

      if (groups[nextIndex]) {
        _isJumping = true;
        const scrollBehavior = document.documentElement.dataset.chimaScrollBehavior || 'smooth';
        groups[nextIndex].scrollIntoView({ behavior: scrollBehavior, block: 'start' });
        
        // Reset after the scroll finishes
        setTimeout(() => { 
          _isJumping = false; 
        }, 500);
      }
    },

    updateAnkiStatus(existingExpressionsValue) {
      try {
        const existing = typeof existingExpressionsValue === 'string'
          ? JSON.parse(existingExpressionsValue)
          : existingExpressionsValue;
        const existingSet = Array.isArray(existing) ? new Set(existing) : new Set();
        
        const addButtons = document.querySelectorAll('.anki-add-btn');
        addButtons.forEach(btn => {
          const expr = btn.getAttribute('data-expression');
          const isAdded = existingSet.has(expr);
          
          const shouldShowAdd = !isAdded || _lastAnkiDupAction !== 'prevent';
          btn.classList.toggle('anki-added', isAdded && shouldShowAdd);
          btn.innerHTML = isAdded && shouldShowAdd ? ICONS.check_circle : ICONS.add_circle;
          btn.title = isAdded ? 'Already in Anki' : 'Add to Anki';
          btn.style.display = shouldShowAdd ? '' : 'none';

          // Book button is always in the DOM inside .entry-icon-group — just show/hide.
          const iconGroup = btn.closest('.entry-icon-group');
          const bookBtn = iconGroup ? iconGroup.querySelector('.anki-open-btn') : null;
          if (bookBtn) {
            if (btn.nextElementSibling !== bookBtn) {
              iconGroup.insertBefore(btn, bookBtn);
            }
            bookBtn.style.display = isAdded ? '' : 'none';
          }
        });
      } catch (e) {
        console.error('[DictionaryRenderJS] updateAnkiStatus error:', e);
      }
    },

    updateMediaDataUris(mediaMap) {
      try {
        const map = mediaMap || {};
        document.querySelectorAll('.gloss-image-link[data-image-load-state="unloaded"]').forEach(link => {
          applyMediaToImageLink(link, map);
        });
        postContentReady();
      } catch (e) {
        console.error('[DictionaryRenderJS] updateMediaDataUris error:', e);
      }
    },

    onAudioResults: (id, results) => { /* overriden by UI */ },
  };

  // Scroll state guards for programmatic jumps.
  let _isJumping = false;

  // ── Reduced motion scrolling (paginated scrolling) ──────────────────────
  const _isPaginatedScrolling = () => document.documentElement.dataset.chimaPaginatedScrolling === 'true';

  const _getPaginatedStep = () => {
    const raw = document.documentElement.dataset.chimaPaginatedStep;
    const val = parseInt(raw, 10);
    return isNaN(val) ? 90 : Math.max(50, Math.min(100, val));
  };

  const _scrollByPopupHeight = (direction) => {
    const popupHeight = window.innerHeight;
    const maxScroll = Math.max(0, document.documentElement.scrollHeight - popupHeight);
    const pct = _getPaginatedStep() / 100;
    const overlap = Math.round(popupHeight * (1 - pct));
    const step = popupHeight - overlap;
    const target = Math.min(Math.max(0, window.scrollY + step * direction), maxScroll);

    _isJumping = true;
    window.scrollTo({ top: target, behavior: 'instant' });
    setTimeout(() => { _isJumping = false; }, 300);
  };

  window.addEventListener('wheel', (e) => {
    if (!_isPaginatedScrolling()) return;
    _scrollByPopupHeight(e.deltaY > 0 ? 1 : -1);
    e.preventDefault();
  }, { passive: false });

  window.addEventListener('touchstart', (e) => {
    if (!_isPaginatedScrolling() || e.touches.length !== 1) return;
    _touchStartY = e.touches[0].clientY;
  }, { passive: true });

  window.addEventListener('touchmove', (e) => {
    if (!_isPaginatedScrolling()) return;
    e.preventDefault();
  }, { passive: false });

  window.addEventListener('touchend', (e) => {
    if (!_isPaginatedScrolling() || _touchStartY === 0) return;
    const endY = e.changedTouches[0].clientY;
    const delta = _touchStartY - endY;
    _touchStartY = 0;
    if (Math.abs(delta) < 15) return;
    _scrollByPopupHeight(delta > 0 ? 1 : -1);
  }, { passive: true });
})();
