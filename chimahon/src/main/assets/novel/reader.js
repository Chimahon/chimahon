window.hoshiReader = {
    isRtl: false,
    continuousMode: false,
    pageHeight: 0,
    pageWidth: 0,
    nativeSelectionActive: false,
    nativeSelectionScrollPosition: null,
    // Character counting regex: keeps alphanumeric and CJK scripts (Japanese, Chinese, Korean).
    // Added \p{Script=Hangul} to support Korean and clarified the CJK ideograph property.
    ttuRegexNegated: /[^0-9A-Za-z○◯々-〇〻ぁ-ゖゝ-ゞァ-ヺー０-９Ａ-Ｚａ-ｚｦ-ﾝ\p{Radical}\p{Unified_Ideograph}\p{Script=Hangul}]+/gimu,

    isVertical: function() {
        return window.getComputedStyle(document.body).writingMode === "vertical-rl";
    },

    countChars: function(text) {
        return Array.from(text.replace(this.ttuRegexNegated, '')).length;
    },

    isMatchableChar: function(char) {
        return this.countChars(char) > 0;
    },

    textOffsetForCharCount: function(node, targetCount) {
        var text = node.textContent || '';
        var count = 0;
        var offset = 0;
        var fallbackOffset = 0;
        while (offset < text.length) {
            var char = String.fromCodePoint(text.codePointAt(offset));
            if (this.isMatchableChar(char)) {
                if (count >= targetCount) return offset;
                fallbackOffset = offset;
                count += 1;
            }
            offset += char.length;
        }
        return fallbackOffset;
    },

    getRect: function(target) {
        var rect = target.getClientRects()[0];
        return rect || target.getBoundingClientRect();
    },

    createWalker: function(rootNode) {
        const root = rootNode || document.body;
        return document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
            acceptNode: (n) => this.isFurigana(n) ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT
        });
    },

    getScrollContext: function() {
        var vertical = this.isVertical();
        var scrollEl = document.body;
        var pageSize = Math.max(1, vertical ? (this.pageHeight || window.innerHeight) : (this.pageWidth || window.innerWidth));
        var totalSize = vertical ? scrollEl.scrollHeight : scrollEl.scrollWidth;
        var maxScroll = Math.max(0, totalSize - pageSize);
        return { vertical: vertical, scrollEl: scrollEl, pageSize: pageSize, maxScroll: maxScroll };
    },

    getPagePosition: function(context) {
        return context.vertical ? context.scrollEl.scrollTop : context.scrollEl.scrollLeft;
    },

    alignToPage: function(context, offset) {
        return Math.floor(Math.max(0, offset) / context.pageSize) * context.pageSize;
    },

    countCharsBeforePagedViewport: function(node, context) {
        var text = node.textContent || '';
        var totalChars = this.countChars(text);
        if (totalChars <= 0) return 0;
        var range = document.createRange();
        range.selectNodeContents(node);
        var rects = range.getClientRects();
        if (!rects.length) return 0;
        var minStart = Infinity;
        var maxEnd = -Infinity;
        for (var i = 0; i < rects.length; i++) {
            var rect = rects[i];
            if (rect.width <= 0 || rect.height <= 0) continue;
            var start = context.vertical ? rect.top : rect.left;
            var end = context.vertical ? rect.bottom : rect.right;
            minStart = Math.min(minStart, start);
            maxEnd = Math.max(maxEnd, end);
        }
        if (maxEnd <= 0) return totalChars;
        if (minStart >= 0 || minStart === Infinity) return 0;
        return this.countCharsBeforePartialNode(node, text, (offset) =>
            this.isPagedTextOffsetBeforeViewport(node, offset, text, context)
        );
    },

    countCharsBeforeContinuousViewport: function(node, vertical) {
        var text = node.textContent || '';
        var totalChars = this.countChars(text);
        if (totalChars <= 0) return 0;
        var range = document.createRange();
        range.selectNodeContents(node);
        var rects = range.getClientRects();
        if (!rects.length) return 0;
        var minStart = Infinity;
        var maxEnd = -Infinity;
        for (var i = 0; i < rects.length; i++) {
            var rect = rects[i];
            if (rect.width <= 0 || rect.height <= 0) continue;
            var start = vertical ? rect.left : rect.top;
            var end = vertical ? rect.right : rect.bottom;
            minStart = Math.min(minStart, start);
            maxEnd = Math.max(maxEnd, end);
        }
        if (vertical) {
            if (minStart >= window.innerWidth) return totalChars;
            if (maxEnd <= window.innerWidth || minStart === Infinity) return 0;
        } else {
            if (maxEnd <= 0) return totalChars;
            if (minStart >= 0 || minStart === Infinity) return 0;
        }
        return this.countCharsBeforePartialNode(node, text, (offset) =>
            this.isContinuousTextOffsetBeforeViewport(node, offset, text, vertical)
        );
    },

    countCharsBeforePartialNode: function(node, text, isBeforeViewport) {
        var offsets = [];
        var prefixCounts = [0];
        var count = 0;
        var offset = 0;
        while (offset < text.length) {
            offsets.push(offset);
            var char = String.fromCodePoint(text.codePointAt(offset));
            offset += char.length;
            if (this.isMatchableChar(char)) count += 1;
            prefixCounts.push(count);
        }
        var low = 0;
        var high = offsets.length - 1;
        var firstVisible = offsets.length;
        while (low <= high) {
            var mid = Math.floor((low + high) / 2);
            if (isBeforeViewport(offsets[mid])) {
                low = mid + 1;
            } else {
                firstVisible = mid;
                high = mid - 1;
            }
        }
        return prefixCounts[firstVisible];
    },

    isPagedTextOffsetBeforeViewport: function(node, offset, text, context) {
        var char = String.fromCodePoint(text.codePointAt(offset));
        if (!char) return false;
        var range = document.createRange();
        range.setStart(node, offset);
        range.setEnd(node, offset + char.length);
        var rect = this.getRect(range);
        if (!rect || rect.width <= 0 || rect.height <= 0) return false;
        return (context.vertical ? rect.bottom : rect.right) <= 0;
    },

    isContinuousTextOffsetBeforeViewport: function(node, offset, text, vertical) {
        var char = String.fromCodePoint(text.codePointAt(offset));
        if (!char) return false;
        var range = document.createRange();
        range.setStart(node, offset);
        range.setEnd(node, offset + char.length);
        var rect = this.getRect(range);
        if (!rect || rect.width <= 0 || rect.height <= 0) return false;
        return vertical ? rect.left >= window.innerWidth : rect.bottom <= 0;
    },

    lockRootViewport: function() {
        var root = document.documentElement;
        var didScroll = false;
        if (root.scrollTop !== 0) {
            root.scrollTop = 0;
            didScroll = true;
        }
        if (root.scrollLeft !== 0) {
            root.scrollLeft = 0;
            didScroll = true;
        }
        if (window.scrollX !== 0 || window.scrollY !== 0) {
            window.scrollTo(0, 0);
            didScroll = true;
        }
        return didScroll;
    },

    assignPagePosition: function(context, position) {
        if (context.vertical) {
            context.scrollEl.scrollTop = position;
        } else {
            context.scrollEl.scrollLeft = position;
        }
        this.lockRootViewport();
    },

    setPagePosition: function(context, position) {
        var clamped = Math.min(Math.max(0, position), context.maxScroll);
        window.lastPageScroll = clamped;
        this.assignPagePosition(context, clamped);
        return clamped;
    },

    setNativeSelectionActive: function(active) {
        var context = this.getScrollContext();
        if (active) {
            this.nativeSelectionActive = true;
            this.nativeSelectionScrollPosition = this.getPagePosition(context);
            window.lastPageScroll = this.nativeSelectionScrollPosition;
            return;
        }
        if (this.nativeSelectionActive && this.nativeSelectionScrollPosition !== null && this.nativeSelectionScrollPosition !== undefined) {
            var lockedScroll = Math.min(Math.max(0, this.nativeSelectionScrollPosition), context.maxScroll);
            this.assignPagePosition(context, lockedScroll);
            window.lastPageScroll = lockedScroll;
        }
        this.nativeSelectionActive = false;
        this.nativeSelectionScrollPosition = null;
    },

    handlePagedBodyScroll: function() {
        this.lockRootViewport();
        var context = this.getScrollContext();
        if (context.pageSize <= 0) return;
        var currentScroll = this.getPagePosition(context);
        if (this.nativeSelectionActive) {
            var lockedScroll = this.nativeSelectionScrollPosition;
            if (lockedScroll === null || lockedScroll === undefined) {
                lockedScroll = window.lastPageScroll || 0;
            }
            lockedScroll = Math.min(Math.max(0, lockedScroll), context.maxScroll);
            if (Math.abs(currentScroll - lockedScroll) > 0.5) {
                this.assignPagePosition(context, lockedScroll);
            }
            window.lastPageScroll = lockedScroll;
            return;
        }
        var snappedScroll = Math.round(currentScroll / context.pageSize) * context.pageSize;
        snappedScroll = Math.min(Math.max(0, snappedScroll), context.maxScroll);
        if (Math.abs(currentScroll - snappedScroll) > 1) {
            this.assignPagePosition(context, window.lastPageScroll || 0);
        } else {
            window.lastPageScroll = snappedScroll;
        }
    },

    registerSnapScroll: function(initialScroll) {
        if (this.continuousMode || window.snapScrollRegistered) return;
        window.snapScrollRegistered = true;
        window.lastPageScroll = initialScroll;
        this.lockRootViewport();
        window.addEventListener('scroll', () => {
            if (this.lockRootViewport()) {
                requestAnimationFrame(() => this.lockRootViewport());
            }
        }, { passive: true });
        document.body.addEventListener('scroll', () => {
            this.handlePagedBodyScroll();
        }, { passive: true });
    },

    paginate: function(direction) {
        var context = this.getScrollContext();
        if (context.pageSize <= 0) return 'limit';
        var currentScroll = this.getPagePosition(context);

        if (direction === 'forward') {
            if (currentScroll < context.maxScroll - 1) {
                var target = Math.min(Math.round((currentScroll + context.pageSize) / context.pageSize) * context.pageSize, context.maxScroll);
                if (target <= currentScroll + 1) return 'limit';
                this.setPagePosition(context, target);
                return 'scrolled';
            }
            return 'limit';
        } else {
            if (currentScroll > 1) {
                var target = Math.max(0, Math.floor((currentScroll - 1) / context.pageSize) * context.pageSize);
                this.setPagePosition(context, target);
                return 'scrolled';
            }
            return 'limit';
        }
    },

    calculateProgress: function() {
        var context = this.continuousMode ? null : this.getScrollContext();
        var vertical = this.isVertical();
        var walker = this.createWalker();
        var totalChars = 0;
        var exploredChars = 0;
        var node;

        while (node = walker.nextNode()) {
            var nodeLen = this.countChars(node.textContent);
            totalChars += nodeLen;

            if (nodeLen > 0) {
                exploredChars += this.continuousMode
                    ? this.countCharsBeforeContinuousViewport(node, vertical)
                    : this.countCharsBeforePagedViewport(node, context);
            }
        }

        return totalChars > 0 ? exploredChars / totalChars : 0;
    },

    restoreProgress: async function(progress, isRtl) {
        this.isRtl = !!isRtl;
        var p = Math.min(1, Math.max(0, progress));
        if (document.fonts && document.fonts.ready) {
            try { await document.fonts.ready; } catch (e) {}
        }

        if (!this.continuousMode) {
            var context = this.getScrollContext();
            if (context.pageSize <= 0) {
                this.registerSnapScroll(0);
                this.notifyRestoreComplete();
                return;
            }

            if (p >= 0.99) {
                var lastPage = this.setPagePosition(context, context.maxScroll);
                requestAnimationFrame(() => {
                    lastPage = this.setPagePosition(context, context.maxScroll);
                    this.registerSnapScroll(lastPage);
                    this.notifyRestoreComplete();
                });
                return;
            }

            var walker = this.createWalker();
            var totalChars = 0;
            var node;
            while (node = walker.nextNode()) {
                totalChars += this.countChars(node.textContent);
            }

            if (totalChars <= 0) {
                var fallbackPage = this.setPagePosition(context, Math.round(context.maxScroll * p));
                this.registerSnapScroll(fallbackPage);
                this.notifyRestoreComplete();
                return;
            }

            var targetCharCount = Math.ceil(totalChars * p);
            var runningSum = 0;
            var targetNode = null;
            var targetOffset = 0;

            walker = this.createWalker();
            while (node = walker.nextNode()) {
                var nodeLen = this.countChars(node.textContent);
                if ((runningSum + nodeLen) > targetCharCount) {
                    targetNode = node;
                    targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
                    break;
                }
                runningSum += nodeLen;
            }

            if (targetNode) {
                var range = document.createRange();
                var targetText = targetNode.textContent || '';
                var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
                range.setStart(targetNode, targetOffset);
                range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
                var rect = this.getRect(range);
                var currentScroll = this.getPagePosition(context);
                var anchor = (context.vertical ? rect.top : rect.left) + currentScroll;
                var targetScroll = this.alignToPage(context, anchor);
                this.setPagePosition(context, targetScroll);
                requestAnimationFrame(() => {
                    targetScroll = this.setPagePosition(context, targetScroll);
                    this.registerSnapScroll(targetScroll);
                    this.notifyRestoreComplete();
                });
            } else {
                this.registerSnapScroll(0);
                this.notifyRestoreComplete();
            }
            return;
        }

        // Character-based restoration for continuous mode
        var walker = this.createWalker();
        var totalChars = 0;
        var node;
        while (node = walker.nextNode()) {
            totalChars += this.countChars(node.textContent);
        }

        if (totalChars <= 0) {
            this.notifyRestoreComplete();
            return;
        }

        var targetCharCount = Math.ceil(totalChars * p);
        var runningSum = 0;
        var targetNode = null;
        var targetOffset = 0;
        var lastTargetNode = null;

        walker = this.createWalker();
        while (node = walker.nextNode()) {
            var nodeLen = this.countChars(node.textContent);
            if (nodeLen > 0) lastTargetNode = node;
            if ((runningSum + nodeLen) > targetCharCount) {
                targetNode = node;
                targetOffset = this.textOffsetForCharCount(node, Math.max(0, targetCharCount - runningSum));
                break;
            }
            runningSum += nodeLen;
        }
        if (!targetNode) targetNode = lastTargetNode;

        if (targetNode) {
            if (p >= 0.999999 && targetNode.parentElement) {
                targetNode.parentElement.scrollIntoView({
                    block: 'end',
                    inline: 'nearest',
                    behavior: 'instant'
                });
            } else {
                var targetText = targetNode.textContent || '';
                var targetChar = String.fromCodePoint(targetText.codePointAt(targetOffset));
                var range = document.createRange();
                range.setStart(targetNode, targetOffset);
                range.setEnd(targetNode, Math.min(targetText.length, targetOffset + Math.max(1, targetChar.length)));
                var marker = document.createElement('span');
                marker.style.display = 'inline-block';
                marker.style.width = '1px';
                marker.style.height = '1px';
                range.insertNode(marker);
                marker.scrollIntoView({
                    block: 'start',
                    inline: 'nearest',
                    behavior: 'instant'
                });
                var parent = marker.parentNode;
                marker.remove();
                if (parent && parent.normalize) parent.normalize();
            }
        }

        requestAnimationFrame(() => {
            document.body.style.transform = 'translateZ(0)';
            requestAnimationFrame(() => {
                document.body.style.transform = '';
                this.notifyRestoreComplete();
            });
        });
    },

    notifyRestoreComplete: function() {
        requestAnimationFrame(() => {
            document.body.style.transform = 'translateZ(0)';
            requestAnimationFrame(() => {
                document.body.style.transform = '';
                if (window.ReaderAndroid && window.ReaderAndroid.restoreCompleted) {
                    window.ReaderAndroid.restoreCompleted(window.__readerRestoreEpoch || 0);
                    return;
                }
                if (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.restoreCompleted) {
                    window.webkit.messageHandlers.restoreCompleted.postMessage(null);
                }
            });
        });
    },

    registerCopyText: function() {
        if (window._hoshiCopy) return;
        window._hoshiCopy = true;
        const reader = this;
        document.addEventListener('copy', function(e) {
            const selection = window.getSelection();
            if (!selection || !selection.toString() || !e.clipboardData) return;
            e.preventDefault();
            e.clipboardData.setData('text/plain', reader.copySelectionText(selection));
        }, true);
    },

    copySelectionText: function(selection) {
        const fragments = [];
        let hasRuby = false;
        for (let i = 0; i < selection.rangeCount; i++) {
            const range = selection.getRangeAt(i);
            if (this.isFurigana(range.commonAncestorContainer)) {
                hasRuby = true;
                continue;
            }
            const fragment = range.cloneContents();
            if (fragment.querySelector('rt, rp')) hasRuby = true;
            fragment.querySelectorAll('rt, rp').forEach(node => node.remove());
            fragments.push(fragment);
        }
        if (!hasRuby) return selection.toString();

        const blocks = new Set(['P', 'DIV', 'SECTION', 'ARTICLE', 'LI', 'BLOCKQUOTE',
            'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'TR']);
        const plainText = node => {
            if (node.nodeType === Node.TEXT_NODE) return node.textContent;
            if (node.nodeType === Node.ELEMENT_NODE) {
                if (node.tagName === 'BR') return '\n';
                if (['SCRIPT', 'STYLE'].includes(node.tagName)) return '';
            }
            const text = Array.from(node.childNodes, plainText).join('');
            return text + (blocks.has(node.tagName) ? '\n' : '');
        };
        return fragments.map(plainText).join('\n').replace(/\n{3,}/g, '\n\n').replace(/\n+$/, '');
    },

    scanDelimiters: '。、！？…‥「」『』（）()【】〈〉《》〔〕｛｝{}［］[]・：；:;，,.─\n\r',
    sentenceDelimiters: '。！？.!?\n\r',

    isScanBoundary: function(char) {
        return /^[\s\u3000]$/.test(char) || this.scanDelimiters.includes(char);
    },

    isFurigana: function(node) {
        const el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return !!el?.closest('rt, rp');
    },

    findParagraph: function(node) {
        let el = node.nodeType === Node.TEXT_NODE ? node.parentElement : node;
        return el?.closest('p, div, .glossary-content') || null;
    },

    getCaretRange: function(x, y) {
        if (document.caretPositionFromPoint) {
            const pos = document.caretPositionFromPoint(x, y);
            if (!pos) return null;
            const range = document.createRange();
            range.setStart(pos.offsetNode, pos.offset);
            range.collapse(true);
            return range;
        } else if (document.caretRangeFromPoint) {
            return document.caretRangeFromPoint(x, y);
        }
        return null;
    },

    codePointStartOffset: function(text, offset) {
        if (offset > 0 && offset < text.length &&
            text.charCodeAt(offset) >= 0xDC00 && text.charCodeAt(offset) <= 0xDFFF &&
            text.charCodeAt(offset - 1) >= 0xD800 && text.charCodeAt(offset - 1) <= 0xDBFF) {
            return offset - 1;
        }
        return Math.max(0, Math.min(offset, text.length));
    },

    nextCodePointOffset: function(text, offset) {
        const start = this.codePointStartOffset(text, offset);
        return start < text.length ? start + String.fromCodePoint(text.codePointAt(start)).length : start;
    },

    getCharacterAtPoint: function(x, y) {
        const range = this.getCaretRange(x, y);
        if (!range || !range.startContainer) return null;
        const node = range.startContainer;
        const candidates = new Map();
        const addCandidate = (textNode, offset) => {
            if (!textNode || textNode.nodeType !== Node.TEXT_NODE || this.isFurigana(textNode)) return;
            const start = this.codePointStartOffset(textNode.textContent, offset);
            if (start >= textNode.textContent.length) return;
            if (!candidates.has(textNode)) candidates.set(textNode, new Set());
            candidates.get(textNode).add(start);
        };
        if (node.nodeType === Node.TEXT_NODE) {
            for (const offset of [range.startOffset, range.startOffset - 1, range.startOffset + 1]) {
                if (offset >= 0) addCandidate(node, offset);
            }
        }

        // Carets at inline/ruby boundaries may belong to the neighboring text node.
        const walker = this.createWalker(this.findParagraph(node) || document.body);
        const ruby = (node.nodeType === Node.TEXT_NODE ? node.parentElement : node).closest('ruby');
        for (const origin of ruby ? [node, ruby] : [node]) {
            walker.currentNode = origin;
            const previous = walker.previousNode();
            if (previous) addCandidate(previous, previous.textContent.length - 1);
            walker.currentNode = origin;
            addCandidate(walker.nextNode(), 0);
        }

        // A caret in an annotation can still overlap the base character's box.
        if (this.isFurigana(node)) {
            if (ruby) {
                const baseWalker = this.createWalker(ruby);
                let base;
                while (base = baseWalker.nextNode()) {
                    for (let offset = 0; offset < base.textContent.length; offset = this.nextCodePointOffset(base.textContent, offset)) {
                        addCandidate(base, offset);
                    }
                }
            }
        }

        let best = null;
        let distance = Infinity;
        for (const [textNode, offsets] of candidates) {
            const text = textNode.textContent;
            const ruby = textNode.parentElement.closest('ruby');
            const vertical = getComputedStyle(textNode.parentElement).writingMode.startsWith('vertical');
            const rubyRects = ruby ? Array.from(ruby.getClientRects()) : null;
            for (const offset of offsets) {
                const charRange = document.createRange();
                charRange.setStart(textNode, offset);
                charRange.setEnd(textNode, this.nextCodePointOffset(text, offset));
                for (const rect of charRange.getClientRects()) {
                    // Ruby overhang extends character ranges into adjacent text.
                    for (const bounds of rubyRects || [rect]) {
                        const left = ruby && !vertical ? Math.max(rect.left, bounds.left) : rect.left;
                        const right = ruby && !vertical ? Math.min(rect.right, bounds.right) : rect.right;
                        const top = ruby && vertical ? Math.max(rect.top, bounds.top) : rect.top;
                        const bottom = ruby && vertical ? Math.min(rect.bottom, bounds.bottom) : rect.bottom;
                        if (x < left || x > right || y < top || y > bottom) continue;
                        const delta = vertical ? Math.abs(y - (top + bottom) / 2) : Math.abs(x - (left + right) / 2);
                        if (delta < distance) {
                            best = { node: textNode, offset };
                            distance = delta;
                        }
                    }
                }
            }
        }
        return best && !this.isScanBoundary(String.fromCodePoint(best.node.textContent.codePointAt(best.offset))) ? best : null;
    },

    getSentence: function(startNode, startOffset) {
        const container = this.findParagraph(startNode) || document.body;
        const walker = this.createWalker(container);
        const trailingSentenceChars = '」』）】!?！？…';

        walker.currentNode = startNode;
        const partsBefore = [];
        let node = startNode;
        let limit = startOffset;

        while (node) {
            const text = node.textContent;
            let foundStart = false;
            for (let i = limit - 1; i >= 0; i--) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    partsBefore.push(text.slice(i + 1, limit));
                    foundStart = true;
                    break;
                }
            }
            if (foundStart) break;
            partsBefore.push(text.slice(0, limit));
            node = walker.previousNode();
            if (node) limit = node.textContent.length;
        }

        walker.currentNode = startNode;
        const partsAfter = [];
        node = startNode;
        let start = startOffset;

        while (node) {
            const text = node.textContent;
            let foundEnd = false;
            for (let i = start; i < text.length; i++) {
                if (this.sentenceDelimiters.includes(text[i])) {
                    let end = i + 1;
                    while (end < text.length) {
                        if (!trailingSentenceChars.includes(text[end])) break;
                        end += 1;
                    }
                    partsAfter.push(text.slice(start, end));
                    foundEnd = true;
                    break;
                }
            }
            if (foundEnd) break;
            partsAfter.push(text.slice(start));
            node = walker.nextNode();
            start = 0;
        }

        return (partsBefore.reverse().join('') + partsAfter.join('')).trim();
    },

    findImageElement: function(el) {
        if (!el || !el.tagName) return null;
        var tag = el.tagName.toLowerCase();
        if (tag === 'img' || tag === 'svg') return el;
        if (tag === 'image' && el.closest) return el.closest('svg');
        for (var i = 0; i < el.children.length; i++) {
            var childTag = el.children[i].tagName.toLowerCase();
            if (childTag === 'img' || childTag === 'svg') return el.children[i];
        }
        if (el.closest) {
            var anc = el.closest('figure, picture, svg');
            if (anc) {
                var found = anc.querySelector('img, svg');
                if (found) return found;
                if (anc.tagName && anc.tagName.toLowerCase() === 'svg') return anc;
            }
        }
        return null;
    },

    getImageSource: function(el) {
        if (!el || !el.tagName) return '';
        var tag = el.tagName.toLowerCase();
        var source = '';

        if (tag === 'img') {
            source = el.currentSrc || el.getAttribute('src') ||
                el.getAttribute('data-src') || el.getAttribute('data-original') || '';
        } else if (tag === 'svg') {
            var nested = el.querySelector('image');
            if (nested) {
                source = nested.getAttribute('href') ||
                    nested.getAttributeNS('http://www.w3.org/1999/xlink', 'href') ||
                    nested.getAttribute('xlink:href') || '';
            }
        }

        if (!source) return '';
        try { return new URL(source, document.baseURI).href; } catch (_) { return source; }
    },

    findImageAtPoint: function(clientX, clientY) {
        var points = [[clientX, clientY]];
        if (clientX > 4) points.push([clientX - 4, clientY]);
        if (clientY > 4) points.push([clientX, clientY - 4]);
        points.push([clientX + 4, clientY]);
        points.push([clientX, clientY + 4]);

        for (var i = 0; i < points.length; i++) {
            var hit = document.elementFromPoint(points[i][0], points[i][1]);
            var image = this.findImageElement(hit);
            if (image && this.getImageSource(image)) return image;
        }

        // Some EPUB styles put transparent overlays above illustrations. In
        // that case elementFromPoint cannot reach the image, but its rendered
        // bounds still tell us unambiguously that it was tapped.
        var images = document.querySelectorAll('img, svg');
        for (var j = images.length - 1; j >= 0; j--) {
            var candidate = images[j];
            var rect = candidate.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) continue;
            if (clientX >= rect.left && clientX <= rect.right &&
                clientY >= rect.top && clientY <= rect.bottom &&
                this.getImageSource(candidate)) {
                return candidate;
            }
        }
        return null;
    },

    handleImageTap: function(clientX, clientY) {
        try {
            var imgEl = this.findImageAtPoint(clientX, clientY);
            if (imgEl) {
                var isGaiji = imgEl.classList && (imgEl.classList.contains('gaiji') || imgEl.classList.contains('gaiji-line'));
                var nw = imgEl.naturalWidth || 0;
                var nh = imgEl.naturalHeight || 0;
                var isTiny = (nw > 0 && nw < 32) && (nh > 0 && nh < 32);
                var source = this.getImageSource(imgEl);
                if (!isGaiji && !isTiny && source && window.ReaderAndroid && window.ReaderAndroid.onImageTapped) {
                    window.ReaderAndroid.onImageTapped(source);
                    return true;
                }
            }
        } catch (_) {}
        return false;
    },

    handleTap: function(clientX, clientY) {
        if (this.handleImageTap(clientX, clientY)) return true;

        const hit = this.getCharacterAtPoint(clientX, clientY);
        if (!hit) {
            this.clearSelection();
            if (window.ReaderAndroid && window.ReaderAndroid.onBackgroundTap) {
                window.ReaderAndroid.onBackgroundTap(clientX, clientY);
            }
            return false;
        }

        if (this.selectionStartNode === hit.node && this.selectionStartOffset === hit.offset) {
            this.clearSelection();
            if (window.ReaderAndroid && window.ReaderAndroid.onBackgroundTap) {
                window.ReaderAndroid.onBackgroundTap(clientX, clientY);
            }
            return false;
        }

        const container = this.findParagraph(hit.node) || document.body;
        const walker = this.createWalker(container);

        let word = '';
        let node = hit.node;
        let offset = hit.offset;
        let ranges = [];
        let reachedSentenceBreak = false;

        walker.currentNode = node;
        while (!reachedSentenceBreak && node) {
            const content = node.textContent;
            let start = offset;
            while (offset < content.length) {
                const char = content[offset];
                if (this.sentenceDelimiters.includes(char)) {
                    reachedSentenceBreak = true;
                    break;
                }
                word += char;
                offset++;
            }
            if (offset > start) ranges.push({ node: node, start: start, end: offset });
            if (reachedSentenceBreak || offset < content.length) break;
            node = walker.nextNode();
            offset = 0;
        }
        word = word.trim();

        if (word.length > 0) {
            this.clearSelection();
            this.selectionStartNode = hit.node;
            this.selectionStartOffset = hit.offset;
            this.selectionRanges = ranges;
            const sentence = this.getSentence(hit.node, hit.offset);

            // Use Hoshi's approach: calculate bounding box based ONLY on the first character
            // This prevents the popup from jumping far away when a long phrase is selected.
            let minX = clientX, minY = clientY, maxX = clientX, maxY = clientY;
            if (ranges.length > 0) {
                const first = ranges[0];
                const range = document.createRange();
                range.setStart(first.node, first.start);
                range.setEnd(first.node, this.nextCodePointOffset(first.node.textContent, first.start));

                const rects = Array.from(range.getClientRects());
                const rect = rects.find(r => clientX >= r.left && clientX <= r.right && clientY >= r.top && clientY <= r.bottom)
                            || range.getBoundingClientRect();

                minX = rect.left;
                minY = rect.top;
                maxX = rect.right;
                maxY = rect.bottom;
            }

            if (window.ReaderAndroid && window.ReaderAndroid.onTextSelected) {
                window.ReaderAndroid.onTextSelected(word, sentence, minX, minY, maxX - minX, maxY - minY);
                return true;
            }
        }

        this.clearSelection();
        if (window.ReaderAndroid && window.ReaderAndroid.onBackgroundTap) {
            window.ReaderAndroid.onBackgroundTap(clientX, clientY);
        }
        return false;
    },

    highlightSelection: function(charCount) {
        if (!this.selectionRanges || !this.selectionRanges.length || !CSS.highlights) return;
        var highlights = [];
        var remaining = charCount;
        for (var i = 0; i < this.selectionRanges.length; i++) {
            var r = this.selectionRanges[i];
            if (remaining <= 0) break;
            var end = r.start;
            while (end < r.end && remaining > 0) {
                var char = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += char.length;
                remaining--;
            }
            var range = document.createRange();
            range.setStart(r.node, r.start);
            range.setEnd(r.node, end);
            highlights.push(range);
        }
        CSS.highlights.set('hoshi-selection', new Highlight(...highlights));
    },

    /**
     * Return selection rects as JSON array [{x,y,width,height}, ...]
     * for painting in a native Compose overlay.  Falls back to CSS.highlight
     * if the bridge is not available.
     */
    getSelectionRects: function(charCount, startOffset) {
        if (!this.selectionRanges || !this.selectionRanges.length) return [];
        if (startOffset === undefined) startOffset = 0;
        var remaining = startOffset;
        var skipRange = -1;
        var skipEnd = 0;

        // Skip `startOffset` characters through the ranges
        for (var i = 0; i < this.selectionRanges.length; i++) {
            var r = this.selectionRanges[i];
            var avail = r.end - r.start;
            if (remaining < avail) {
                skipRange = i;
                skipEnd = r.start + remaining;
                break;
            }
            remaining -= avail;
        }
        if (skipRange < 0) return [];

        // Now build ranges from the skip point, limited to charCount
        var ranges = [];
        remaining = charCount;
        for (var i = skipRange; i < this.selectionRanges.length; i++) {
            var r = this.selectionRanges[i];
            if (remaining <= 0) break;
            var start = (i === skipRange) ? skipEnd : r.start;
            var end = start;
            while (end < r.end && remaining > 0) {
                var ch = String.fromCodePoint(r.node.textContent.codePointAt(end));
                end += ch.length;
                remaining--;
            }
            var range = document.createRange();
            range.setStart(r.node, start);
            range.setEnd(r.node, end);
            ranges.push(range);
        }

        var rects = [];
        for (var ri = 0; ri < ranges.length; ri++) {
            var clientRects = ranges[ri].getClientRects();
            for (var ci = 0; ci < clientRects.length; ci++) {
                var cr = clientRects[ci];
                rects.push({x: cr.x, y: cr.y, width: cr.width, height: cr.height});
            }
        }

        return rects;
    },

    clearSelection: function() {
        if (CSS.highlights && CSS.highlights.has('hoshi-selection')) {
            CSS.highlights.get('hoshi-selection').clear();
        }
        this.selectionRanges = null;
        this.selectionStartNode = null;
        this.selectionStartOffset = null;
    },

    registerTextSelection: function() {
        // Disabled for now as per user request
    }
};
