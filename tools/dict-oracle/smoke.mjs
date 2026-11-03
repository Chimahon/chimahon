// Smoke test for the JS oracle: feed renderer.js a real Jitendex gloss and report the geometry of
// the .structured-content box, so we know the reference side actually rendered something.
import { execFileSync } from 'node:child_process';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const TMP = mkdtempSync(join(tmpdir(), 'oracle-smoke-'));
const REPO = 'F:\\sohil\\github\\mihon-worktree\\mihon-worktree-compose';

const payload = {
  isDark: false,
  groupTerms: true,
  showPitchDiagram: false,
  showPitchNumber: false,
  showPitchText: false,
  showFrequencyHarmonic: false,
  showFrequencyAverage: false,
  wordAudioEnabled: false,
  renderRecursiveChrome: false,
  showNavigationButtons: false,
  dictionaryOrder: [],
  tabs: [],
  existingExpressions: [],
  mediaDataUris: {},
  dictionaryCollapseMode: 'expand_all',
  dictionaryDisplayModes: {},
  styles: [],
  results: [
    {
      index: 0,
      matched: '人間',
      deinflected: '人間',
      process: [],
      term: {
        expression: '人間',
        reading: 'にんげん',
        rules: '',
        ruleTags: [],
        glossaries: [
          {
            dictName: 'Jitendex.org',
            glossary: JSON.stringify({
              type: 'structured-content',
              content: [
                'だ人间',
                {
                  tag: 'span',
                  data: { class: 'tag', code: 'n', content: 'part-of-speech-info' },
                  content: 'noun',
                },
                { tag: 'br' },
                { tag: 'ruby', content: ['人', { tag: 'rt', content: 'にん' }] },
                { tag: 'ruby', content: ['間', { tag: 'rt', content: 'げん' }] },
                ' のあだ名。',
              ],
            }),
            definitionTags: '',
            definitionTagList: [],
            termTags: '',
          },
        ],
        frequencies: [],
        pitches: [],
      },
    },
  ],
};

const payloadPath = join(TMP, 'payload.json');
const pagePath = join(TMP, 'page.html');
const pngPath = join(TMP, 'shot.png');
writeFileSync(payloadPath, JSON.stringify(payload), 'utf8');

const build = JSON.parse(execFileSync('node', [
  join(REPO, 'tools', 'dict-oracle', 'build_page.mjs'),
  `--payload=${payloadPath}`, `--out=${pagePath}`, '--width=420',
], { encoding: 'utf8' }));

const shot = JSON.parse(execFileSync('node', [
  join(REPO, 'tools', 'dict-oracle', 'render.mjs'),
  pagePath, pngPath,
  '--width=440', '--height=400',
  '--selector=.structured-content',
  '--eval=JSON.stringify(window.__domSummary())',
], { encoding: 'utf8' }));

console.log(JSON.stringify({ build, shot }, null, 1));
console.log('TMP=' + TMP);
