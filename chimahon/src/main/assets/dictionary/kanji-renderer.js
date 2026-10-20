(function() {
  'use strict';

  function createEntryArticle(character, entry) {
    var article = document.createElement('article');
    article.className = 'entry kanji-entry';
    var definitions = Array.isArray(entry.definitions) ? entry.definitions : [];

    // ── Glyph ──
    var glyphRow = document.createElement('div');
    glyphRow.className = 'entry-body-section entry-headword-row';

    var glyph = document.createElement('span');
    glyph.className = 'kanji-glyph';
    glyph.textContent = character;
    glyphRow.appendChild(glyph);

    var iconGroup = document.createElement('div');
    iconGroup.className = 'entry-icon-group';
    glyphRow.appendChild(iconGroup);
    article.appendChild(glyphRow);

    // ── Body ──
    var body = document.createElement('div');
    body.className = 'entry-body';
    article.appendChild(body);

    // ── Dictionary tag ──
    var tagList = document.createElement('div');
    tagList.className = 'kanji-tag-list';
    var tag = document.createElement('span');
    tag.className = 'tag';
    tag.dataset.category = 'dictionary';
    var inner = document.createElement('span');
    inner.className = 'tag-label';
    var content = document.createElement('span');
    content.className = 'tag-label-content';
    content.textContent = entry.dictName || 'Unknown';
    inner.appendChild(content);
    tag.appendChild(inner);
    tagList.appendChild(tag);
    if ((entry.dictName || '').toUpperCase().startsWith('KANJIDIC') && definitions.length > 0) {
      var copyButton = document.createElement('button');
      copyButton.className = 'dictionary-copy-btn';
      copyButton.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M19 21H8c-1.1 0-2-.9-2-2V7h2v12h11v2zM16 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12H4V5h12v10z"/></svg>';
      copyButton.title = 'Copy meanings';
      copyButton.onclick = function(e) {
        e.stopPropagation();
        if (!window.DictionaryClipboard) return;
        window.DictionaryClipboard.copyTranslation(definitions.join('\n'));
        copyButton.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"/></svg>';
        setTimeout(function() { copyButton.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="24" fill="currentColor"><path d="M19 21H8c-1.1 0-2-.9-2-2V7h2v12h11v2zM16 3H4c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 12H4V5h12v10z"/></svg>'; }, 1000);
      };
      tagList.appendChild(copyButton);
    }
    body.appendChild(tagList);

    // ── Definitions (meanings) ──
    if (definitions.length > 0) {
      var glossHeader = document.createElement('div');
      glossHeader.className = 'kanji-section-header';
      glossHeader.textContent = 'Meanings';
      body.appendChild(glossHeader);

      var glossList = document.createElement('ol');
      glossList.className = 'kanji-gloss-list';
      for (var di = 0; di < definitions.length; di++) {
        var li = document.createElement('li');
        li.className = 'kanji-gloss-item';
        var gc = document.createElement('div');
        gc.className = 'kanji-gloss-content';
        gc.textContent = definitions[di];
        li.appendChild(gc);
        glossList.appendChild(li);
      }
      body.appendChild(glossList);
    }

    // ── Readings ──
    var onyomi = Array.isArray(entry.onyomi) ? entry.onyomi : [];
    var kunyomi = Array.isArray(entry.kunyomi) ? entry.kunyomi : [];
    if (onyomi.length > 0 || kunyomi.length > 0) {
      var readingsHeader = document.createElement('div');
      readingsHeader.className = 'kanji-section-header';
      readingsHeader.textContent = 'Readings';
      body.appendChild(readingsHeader);

      var readingsContainer = document.createElement('div');
      readingsContainer.className = 'kanji-readings';
      body.appendChild(readingsContainer);

      if (onyomi.length > 0) {
        var onyomiSection = document.createElement('dl');
        onyomiSection.className = 'kanji-readings-chinese';
        for (var oi = 0; oi < onyomi.length; oi++) {
          var reading = document.createElement('span');
          reading.className = 'kanji-reading';
          reading.textContent = onyomi[oi];
          onyomiSection.appendChild(reading);
          if (oi < onyomi.length - 1) {
            onyomiSection.appendChild(document.createTextNode(', '));
          }
        }
        readingsContainer.appendChild(onyomiSection);
      }

      if (kunyomi.length > 0) {
        var kunyomiSection = document.createElement('dl');
        kunyomiSection.className = 'kanji-readings-japanese';
        for (var ki = 0; ki < kunyomi.length; ki++) {
          var reading = document.createElement('span');
          reading.className = 'kanji-reading';
          reading.textContent = kunyomi[ki];
          kunyomiSection.appendChild(reading);
          if (ki < kunyomi.length - 1) {
            kunyomiSection.appendChild(document.createTextNode(', '));
          }
        }
        readingsContainer.appendChild(kunyomiSection);
      }
    }

    // ── Stats ──
    var stats = entry.stats;
    var statEntries = typeof stats === 'object' && stats !== null ? Object.entries(stats) : [];
    if (statEntries.length > 0) {
      var statsHeader = document.createElement('div');
      statsHeader.className = 'kanji-section-header';
      statsHeader.textContent = 'Statistics';
      body.appendChild(statsHeader);

      var statsTable = document.createElement('table');
      statsTable.className = 'kanji-info-table';
      var tbody = document.createElement('tbody');
      statsTable.appendChild(tbody);
      for (var si = 0; si < statEntries.length; si++) {
        var stat = statEntries[si];
        var tr = document.createElement('tr');
        tr.className = 'kanji-info-table-item';
        var nameCell = document.createElement('td');
        nameCell.className = 'kanji-info-table-item-header';
        nameCell.textContent = stat[0];
        var valueCell = document.createElement('td');
        valueCell.className = 'kanji-info-table-item-value';
        valueCell.textContent = String(stat[1] != null ? stat[1] : '');
        tr.appendChild(nameCell);
        tr.appendChild(valueCell);
        tbody.appendChild(tr);
      }
      body.appendChild(statsTable);
    }

    return article;
  }

  window.KanjiRenderer = {
    render: function(kanjiResult) {
      if (!kanjiResult) return;
      var container = document.getElementById('entries');
      if (!container) return;

      var character = kanjiResult.character || '';
      var entries = Array.isArray(kanjiResult.entries) ? kanjiResult.entries : [];

      for (var ei = 0; ei < entries.length; ei++) {
        container.appendChild(createEntryArticle(character, entries[ei]));
      }
    },

    renderEntry: function(kanjiData, character) {
      return createEntryArticle(character, kanjiData);
    },

    clear: function() {
      var container = document.getElementById('entries');
      if (!container) return;
      var kanjiEntries = container.querySelectorAll('.kanji-entry');
      for (var i = kanjiEntries.length - 1; i >= 0; i--) {
        kanjiEntries[i].remove();
      }
    }
  };
})();
