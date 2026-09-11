# Changelog

All notable changes to Chimahon are documented here.

The format follows a Keep a Changelog style and uses Semantic Versioning.

## [v2.4.1]

### Changed
- Remove default novel extension repo

### Fixed
- Novel library updates crashing on Android 16
- Re-downloaded EPUB books staying invisible in library
- EPUB downloads leaving online novel unfavorited
- Novel history needing restart + wrong chapter numbers

## [v2.4.0]

### Added
- LNRReader extensions support
- EPUB novel extensions support
- DB-first novel library/reader/history
- Paddle OCR on-device engine

### Changed
- Point repo links to Chimahon org
- Bump chimahon-ffmpeg to v8.1.1.7
- Use Chimahon AniList and MAL client IDs

### Fixed
- Playback order and sentence audio failing on valid videos
- Anime downloads failing on HLS and referer-locked sources
- Korean recursive lookup not triggering
- Dictionary floating button showing in screen lookup captures

## [v2.3.4]

### Added
- Separate language filter for anime extensions and sources, independent from manga

### Changed
- Player gesture settings renamed and clarified to match Anikku
- Updated mpv/ffmpeg builds with improved sentence audio and animated AVIF mining
- Clearer error details when sentence audio capture fails

### Fixed
- YouTube videos stuck at low quality even when higher resolutions were available
- Dictionary floating button going off-screen after rotating the screen
- Screen lookup crop presets not producing correct crops
- Panel detection crashing while the AI model was still downloading
- Anki single-glossary options not showing sometimes
- Crash when loading cover colors in the background
- Discord presence crashing when it starts
- About screen crash when the app is backgrounded
- Picture-in-picture popups crashing when they don't fit the window

## [v2.3.3]

### Added
- Panel-by-panel navigation with runtime AI model detection
- Dedicated OCR settings screen with categorized sections (engine, boxes, overlay, behavior, cache)
- Active text and background opacity controls for OCR overlay
- Dynamic "App" theme option for dictionary popup
- Compact glossaries layout option for dictionary popup
- Reset button in custom theme palette personalizer
- Novel reading statistics autostart setting (Off / On open / On page turn)
- Library sync button in anime library toolbar
- Category visibility controls for novels and anime in library
- `selected-glossary-no-fallback` marker for Anki export templates
- Tap-to-retry action for failed items in the OCR processing queue

### Changed
- History tab reselect now resumes the most recently viewed media across anime and manga
- Screen lookup launch no longer triggers background library sync
- Two-finger tap OCR gesture in reader disabled by default
- Cached Anki duplicate card IDs to open and edit existing cards instantly
- Aligned OCR queue item title hierarchy with download queue items

### Fixed
- OCR on download failing or getting stuck during processing, with automatic retries for failed chapters
- Subtitle lookup popup positioned above full subtitle text block in player
- Anime player subtitle swipe controls (swipe up to replay subtitle, swipe down to toggle visibility)
- Subtitle timing accuracy and seek precision in anime player
- Reader settings page now shows flash delay and scroll-flash options
- 4-side E-Ink outer border for dictionary lookup popup
- Anki return-to-app behavior after opening a card
- UI icon text overlap in Data and Storage settings screen
- SAF read-only access for animated AVIF scene mining in player
- Restored manga "Sync library" toolbar label and gated novel TTU background sync
- Excluded E-Ink mode and screen flash toggle settings from backup and sync to keep them device-local

## [v2.3.2]

### Added
- Dictionary backup and restore via zip export/import
- Edit mpv.conf and input.conf directly from settings
- Size and opacity settings for the screen-lookup OCR capture button
- Configurable delay before the e-ink flash (manga and novel readers)
- Flash on scroll/movement for continuous readers
- Per-anime dictionary profile override (matching manga)
- Per-source dictionary profile override for anime extensions
- OCR word resolution lookup mode with per-language scan/search settings
- Clear OCR cache option on the entry three-dot menu
- Subtitle delay remembered per episode
- In-player Jimaku API key setup
- Animated AVIF scene mining for player lookup
- Strip leading French elisions in dictionary lookup variants
- Option to disable cross-screen search history

### Changed
- Anime and manga updates consolidated into one screen
- OCR re-runs immediately when the OCR source switches
- Google Lens OCR results now persist
- Milliseconds shown on controls-hide delay options

### Fixed
- Preferred audio language codes now validated like subtitles
- "Add" button showing both '+' icon and label
- Anime related-section divider displaying only half-width
- Spanish deinflection
- English reading order and text-line selection preserved in OCR
- Stale-subtitle flash
- Reserve space for dictionary recursive lookup tabs
- Player lifecycle hardening
- General YouTube and anime player fixes — sentence audio extraction, YouTube signed media URLs, NewPipe extractor update

## [v2.3.1]

### Added
- OCR source selection in the reader
- Cross-screen search history
- Long-press mode switcher on Browse navbar icon
- Animated AVIF scene mining for Anki

### Changed
- Enabled history tab swiping
- Reduced long-press duration for library/browse mode dropdown menus
- YouTube sentence audio mining now uses AAC transcode

### Fixed
- Audio path update failing on some devices
- Novel reader incorrectly triggering image taps on unrelated space
- Fix oversized images in novel reader
- Anime downloads not respecting extension quality preferences
- OCR lookup popup crash on narrow screens
- YouTube crash on some older Android versions
- Crash when adding anime repositories
- Anime cover images not loading for some sources
- MPV certificate storage on some devices

## [v2.3.0]

Rebased on latest Komikku upstream, bringing TachiyomiX 1.6 extension support and other upstream fixes.

### Added
- YouTube integration with option to auto-add watched channels to library (disabled by default)
- Novel image viewer with OCR lookup
- Kanji dictionary support
- Consolidated library quick navigation
- Configurable parallel OCR processing for chapters
- .mokuro files readable from inside archives
- Pre-OCR option for local manga via three-dot menu
- Aspect ratio crop presets for overlay and reader screenshot mode
- Configurable paginated scroll step size for dictionary popup
- Scroll behavior and volume key navigation settings for dictionary popup
- Option to disable two-finger OCR toggle gesture in reader
- Option to disable long-press screenshot in player

### Changed
- Anime continue watching button style unified with manga
- Further improvements to the Korean analyzer
- MangaBaka OAuth login

### Fixed
- Subtitle swipe reliability, history duplication, and single-tap to pause
- Playback hardening and automatic PiP closing
- Anime downloads ignoring extension quality preferences
- OCR data loss from interrupted saves with atomic writes and corrupt-file guard
- External audio track persistence and reload after player restart
- Dynamic volume button scaling by 5 percent
- Duplicate DictionaryTab cache timestamp
- Anki export pipeline hardening
- Anime download queue row isolation
- Anime browse and history label alignment
- Settings profile deletion cleaning up manga/source/novel overrides
- Dictionary type preservation when reordering filtered list
- Missing cache check in getDictionaryPaths
- Active search results refresh on profile changes
- Reader profile cache invalidation on profile content changes
- Browse tab position reset when switching modes
- External file name resolution crash safety
- Audio channels enum mapping values
- Stale local database handle recovery
- Duplicate subtitle rendering
- LinkageError crash from outdated extensions
- Downloads and OCR queue section layout overlap
- OCR coordinate remapping for rotation, center-margin, and split-wide transforms

## [v2.2.0]

Chimahon is open to contributors; issues and pull requests are welcome.

### Added
- Additional home-screen widgets
- Widget picker previews
- Refreshed Updates widget with Material You styling
- Consolidated Library and Browse mode dropdowns
- Option to keep Manga, Novels, and Anime as separate tabs
- Configurable subtitle swipe controls
- Popup swipe-to-dismiss and size controls
- Korean analyzer mode setting
- E-Ink refresh on novel page turns

### Changed
- Reorganized Learning settings into Dictionary, Popup, and Anki
- Anki settings now use standard preference controls
- Faster Anki card creation through JavaScript bridge
- Anki tags now support marker substitution
- Consolidated book and document-title markers into media-name
- Improved dictionary import reports, file picker support, and background handling
- Direct video downloads now use the resumable HTTP downloader
- Theme-aware frequency and pitch badges

### Fixed
- Dictionary import, storage, and SAF permission issues
- Local audio database permission issues
- Online audio saving to Anki cards
- Anime history, progress, library updates, and auto-download filtering
- MAL search for anime
- Anime UI alignment
- Anime browse columns and hoster sorting
- Subtitle lookup pause state and subtitle swipe conflicts
- FFmpeg error messages
- OCR page ordering, overlay coordinates, zoom scaling, and newlines
- E-Ink swipe animation enforcement
- Novel settings leaking from Manga in consolidated Library mode

## [v2.1.4]

### Fixed
- Novel reader bookmark restoration now uses character-based progress like Hoshi Reader
- Anime backup restore no longer fails when anime domain interactors are requested

## [v2.1.3]

### Changed
- Move Lens native libraries to OCR model download

### Fixed
- Novel reader bookmark progress is force-saved when the app closes

## [v2.1.2]

### Added
- Persistent recursive top bar
- Popup recursive mode
- Improved furigana filter

### Changed
- Anime UI alignment (tracking, migration, backup, library, history)
- Prefer original YouTube audio streams
- Reindex downloaded OCR status
- Count novel chapter transition progress

### Fixed
- Preserve dictionary CSS font-family declarations
- URI permission revocation and file picker crash on custom ROMs
- Anime player two-finger OCR tap
- Opening existing OCR cards in crop mode
- Lens OCR text merging
- Manga OCR tap hit testing
- Local manga cover generation
- Korean contraction deinflection
- English dictionary deinflection (capitalization, multi-step chains)
- Novel reader page drift
- Old mokuro files with empty lines_coords
- YouTube playback handoff

## [v2.1.1]

### Added
- Dictionary data warming on OCR/subtitle surfaces for faster lookups
- Second tap on active OCR/subtitle term dismisses lookup; playback resumes
- Anime downloads grouped by source with headers, drag reorder, and popup menu
- "Show Anime" button in download progress notification

### Changed
- Aligned anime library tabs and categories with manga behavior
- Anime pause now shows correct paused notification (matching manga)
- Screen capture permission lets user select a specific app
- Novel landscape columns default to auto

### Fixed
- Anime pause not working in notification and download queue
- Paused notification not dismissed on resume
- Subtitle regex presets not compiling correctly
- Stale episode progress reopening videos at the end
- Badge visibility toggles in anime library not actually controlling display
- Items landing on wrong category tab when categories empty
- Duplicate category rows on every category dialog confirm
- Anime library reading manga's default category preference
- YouTube crash on pre-Android 13
- Dictionary font-size stripping removed from JS
- Dictionary image rendering moved to CSS data attributes

## [v2.1.0]

### Added
- Built-in YouTube anime extension
- Anime categories and unified category management screen
- Season infrastructure for anime entries, including season display/settings defaults
- Episode options dialog with video quality and hoster selection
- Two-finger tap OCR lookup in the anime player
- Subtitle regex filters for speaker names, bracketed text, uppercase lines, music symbols, curly-braced text, multiline merging, and custom regex

### Changed
- Modernized anime library UI with updated toolbar, bottom actions, grids/lists, and tabs
- Reworked anime details UI to align with Anikku-style layouts
- Improved anime image handling with unified cover/background fetching
- Improved Jimaku title, season, episode, filename, and SRT matching
- Refactored OCR overlay components for reuse across screen lookup and video OCR
- Improved sentence audio mining by avoiding re-encoding and letting mining jobs continue after popup dismissal
- Player now remembers selected server/quality across episode changes
- Player now restores manually added and Jimaku subtitles after server/quality reloads
- Mini subtitle overlay now replaces the active subtitle line and supports lookup/mining

### Fixed
- App hang on startup from anime source manager initialization
- Anime extension classloader fallback for some devices/extensions
- Player subtitle selection resetting after video reload
- Mini subtitle overlay duplicating the active subtitle line
- Subtitle opaque box and background box styles in the Compose subtitle renderer
- Completed/stale episodes reopening at video end and leaving the player on a black screen
- Jimaku title overrides leaking between unrelated anime entries
- Host-loading failure updating the player to the wrong episode while the old video kept playing
- Korean OCR text cut off across webtoon page seams
- Video OCR line ordering and dictionary sentence order
- Anime browse settings button opening source preferences
- Mokuro sidecar copier injection crash
- Novel reader horizontal text page skip and margin bugs
- Word-audio/import issues on some devices
- Dictionary popup font size consistency across dictionaries
- Frequency chips staying unreadable in light theme
- Custom dictionary font fallback/loading reliability

## [v2.0.1]

### Added
- Subtitle text-filtering settings (whitelist, blacklist, preferred languages)
- OCR chunking for local

### Changed
- Reworked anime download system
- Consolidated font storage (removed duplicate mpv/fonts folder)
- Chimahon icon for torrent notification

### Fixed
- Word audio database SAF import reliability (4-attempt fallback)
- Subtitle delay not saving per-anime, keyboard not closing on Enter
- Lookup multiple words in same subtitle without dismissing popup
- Center active subtitle in list
- Seek subtitle cue timing
- Tab count change crash
- Anime torrent extension crash (proguard rules)
- Player speed button showing only 'Speed' label instead of value
- Missing title in anime browse compact grid
- Crash on tablet in player settings

## [v2.0.0]

### Added
- Anime support
- Local OCR model import from device storage (advanced settings)
- Use word audio from public storage without copying

### Changed
- Reworked screen lookup overlay (transparent background, tap-to-dismiss, no system bar crop)
- OCR crop remapper now reads from native decoder — matches displayed crop exactly
- Bundled armeabi-v7a and x86 hoshidict

### Fixed
- Reader image centering, padding, width, and EPUB style sanitization
- Novel reader image rendering and paginated restoration hangs
- Dictionary font-size setting now controls all structured content
- Sokuon (促音) pitch accent in Anki export
- OCR mixed-orientation text reordering
- MonetColorScheme crash when WallpaperManager returns null

## [v1.9.5]

### Fixed
- Android 16 screen lookup crash (VirtualDisplay/ImageReader lifecycle ordering)

## [v1.9.4]

### Added
- Title-specific statistics screen
- Profile-based filtering on the global stats screen
- Per-profile stats for library, novels, and added cards

### Changed
- Crop system bars from overlay OCR capture

### Fixed
- WebView force dark in reader causing wrong text rendering for dark text
- Anki model selection being overridden by Lapis (Chimahon) fallback
- VirtualDisplay crash on Android 16 (ImageReader close ordering)
- OCR auto-crop border detection aligned with C++ library
- Dictionary icon sizing and alignment to match Yomitan styles

## [v1.9.3]

### Fixed
- OCR popup positioning on edge-to-edge displays
- Japanese punctuation alignment in vertical OCR text
- MangaBaka API rate limiting and retry logic for 429 errors
- Anki pitch accent export CSS override conflict
- Android 16 compatibility for screen capture

## [v1.9.2]

### Added
- Batch tracker add flow in library
- Screen lookup tile (Quick Settings) for OCR
- Expanded Yomitan Anki export markers (dynamic frequency, part-of-speech, pitch-accent-graphs)
- E-Ink mode in reader

### Fixed
- Fix OOM during OCR model downloads
- Novel reader horizontal padding and gaiji image rendering
- Yomitan Anki export markers and media alignment
- Error 500 in mangabaka tracker
- Improved dictionary structured content display for Yomitan dictionaries
- Dictionary CSS style isolation and recursive lookup exclusions

## [v1.9.1]

### Added
- Screen overlay and context menu text lookup
- Sentence-furigana Anki markers
- Average frequency display in dictionary popup
- Two-finger OCR toggle gesture
- Mokuro support in downloads
- Novel reader custom theme management
- TTU sync
- Lapis as default Anki note type

### Changed
- Separate OCR box scale axes
- Strip justify alignment from imported EPUB CSS
- Skip OCR for mismatched source languages
- Bring back OCR outline border
- Increased zero opacity text visibility
- Novel tracking indicator location
### Fixed
- decoder for Anki screenshots
- Dictionary header selection toggle
- Accidental OCR selection panel trigger on swipe
- Korean deinflection jamo mismatches
- Dictionary CSS scope leak
- Image sizing
- Mokuro CBZ page matching

## [v1.9.0]

### Added
- OCR selection panel triggered with long-press to select and copy text
- OCR progress HUD
- Configurable OCR box opacity for inactive text blocks
- Support for more image formats in OCR: AVIF, HEIF, HEIC, JXL

### Changed
- Dictionary popup WebView warming refactored for lighter reader startup
- Improved dictionary popup rendering performance
- MangaBaka API updated with improved date-time formatting

### Fixed
- Clipped webtoon page rendering
- Novel category assignments lost during sync merge

## [v1.8.5]

### Added
- E-Ink swipe: instant page transitions

### Changed
- Replaced async OCR prefetch with structured scan and page-ready guard
- Updated hoshidicts (normalization processors, freq sorting)

### Fixed
- Reader crash and startup workaround
- Deduplicate manga page stats to prevent recount on back-navigation
- Preserve novel category assignments during sync merge
- Revert problematic NPE reader fixes
- General reader bug fixes (page viewer, Webtoon viewer)

## [v1.8.3]

### Added
- Per-manga stats sheet with session/today/all-time tracking
- Lower sensitivity swipe setting for E-ink

### Changed
- Separate paginated scrolling from e-ink mode in dictionary popup

### Fixed
- Prevent NPE on reader startup (Android 16 dispatchAttachedToWindow)
- Correct manga sentence export offset in Anki export
- Fix furigana spacing in Anki export
- Remove inline style overrides on table elements in Anki export

## [v1.8.2]

### Fixed
- Dictionary Reorder

## [v1.8.1]

### Added
- Mangabaka tracker integration
- Dictionary display name update
- Per-dictionary update notifications

### Changed
- Dictionary now uses directory names as stable keys

### Fixed
- Dictionary import UI
- Yomitan glossary HTML structure: always `<ol><li>`
## [v1.8.0]

### Added
- Dictionary auto-update 
- Dictionary type categorization.
- Paginated scrolling and improved E‑Ink border for the popup.
- Local OCR engine integration.
- Multi-file EPUB import support for novels.
- Arrow-key navigation and HUD/system-bar parity in the novel reader.
- Refactor and improve novel stats.

### Changed
- Novel categories aligned with manga categories.
- Faster word-audio database import.
- Pass OCR language locale to the manga text box for correct CJK rendering.

### Fixed
- Defer viewer visibility to avoid NPE crashes.
- Prevent the WebView from closing the keyboard on the Dictionary tab.
- Fix {selected-glossary} CSS for E‑Ink.

## [v1.7.7]

### Added
- popup theme setting
- novel and manga library parity — pull-to-refresh, debounce, multi-category

### Changed

- Novel library UI aligned with manga library.
- Lookup performance optimizations.

### Fixed
- popup not clearing old lookups.
- fix manga stats

## [v1.7.6]

### Fixed
- Word audio continuing to play after dictionary popup is dismissed.
- Word audio playing twice on lookup with autoplay enabled.


## [v1.7.5]

### Added
- AnkiDroid sync trigger on card creation.

### Changed
- Novel library UI aligned with manga library.
- Improved popup E-Ink mode.
- Lookup performance optimizations.

### Fixed
- Dictionary popup flicker and scroll delay.
- Edge-to-edge text at 0% padding in novel reader.
- Black background crop detection.
- Pitch accent ordering in popup and Anki exports.
- Glossary fallback when no dictionary selected.
- EPUB line-height stripping in CSS cleaning.
- Paged horizontal margins respecting line-height setting.


## [v1.7.3]

### Fixed
- Korean text handling for TTSU and dictionary deinflection.


## [v1.7.1]

### Added
- High-contrast E-ink mode for the dictionary popup.
- Active profile selection in the Dictionary tab.
- Advanced collapsible dictionary behavior in lookups.
- Ability to rename dictionaries.

### Changed
- Reorderable dictionary list via drag-and-drop.
- Refined dictionary popup positioning logic.

### Fixed
- Lookups for non-Japanese languages.
- Dictionary profile overrides in the Novel Reader.


## [v1.7.2]

### Added
- Statistics screen for novel/manga reading stats.
- Pitch accent deduplication option.

### Changed
- Make dictionary list draggable by a handle.
- System bar handling in Novel UI.
- Updated hoshidicts native library.

### Fixed
- Fix: horizontal paged reader behaviour.
- Revert OCR merge value regressions.
- Novel sync and language detection during import.
- Cascade fallback dictionaries in custom collapse mode.
- Korean deinflector text assembly bug.



## [v1.7.0]

### Added
- Cascading profiles that cascade from global down to per-reader context.
- Dictionary language support for Korean, English, and more.
- Auto kana input in dictionary search.
- Revamped novel library tab.
- Add button in the browse tab for importing local files.
- Popup position modes: Floating, Full-width, and Full-height.

### Changed
- Revamped popup positioning with priority-based floating (Below→Above→Right→Left).
- More sidecar formats (.mokuro) now supported — RAR, 7z, EPUB.
- OCR vowels displayed vertically for vertical text.
- Reader tap zones disabled by default.
- Renamed library tab to Manga.

### Fixed
- OCR text reconstruction for downloaded chapters — reading order is now correct and unrelated text bubbles are no longer merged together.
- Double-tap zoom now takes priority over single-tap actions.
- Improved popup margins for pitch accent and structured content.
- Anki `{SelectionText}` marker now preserves line breaks properly.
- Popup selection text not being cleared between lookups.

## [v1.6.2]

### Added
- Custom font support for the dictionary popup.
- Embedded scoped styles in Anki exports.
- Paragraph spacing option to the Novel Reader.
- System-themed sepia background setting for the Novel Reader.

### Changed
- Refined dictionary popup appearance better positioning and stable icons.
- Revamped Anki marker dropdown UX.
- Anki sentence exports now follow the natural OCR box reading order.
- Expanded the available range for line height adjustments in the Novel Reader.
- The "Shift Double Page" reader setting is now saved individually per manga.

### Fixed
- Fixed deinflection order.
- Fix pitch accent export in Anki.
- Resolved an issue causing incorrect page screenshots to be exported to Anki on double-page spreads.


## [v1.6.1]

### Added
- Added option to toggle visibility of dictionary navigation buttons in settings.

### Changed
- Enforced strict dictionary headword row layout (Audio -> Anki -> Open) across all render states.
- Optimized Anki status updates to prevent scroll resets and full WebView re-renders.
- Adjusted novel reader line height settings limit to a maximum of 1.8.
- Enhanced dictionary header selection visibility with a high-contrast accent bar (E-ink/B&W optimized).

### Fixed
- Fixed dictionary popup scroll position leaking between different word lookups.
- Resolved scroll state loss during recursive dictionary navigation.
- Fixed Anki marker "Single Glossary" sub-dropdown being unable to close.
- Improved Anki marker selection to automatically replace the empty `{}` placeholder.
- Fixed Anki duplicate check icon indicator failing to update to green after background check.
- Corrected Anki icon alignment and layout when the duplicate prevention policy is active.
- Fixed novel reader line height setting not applying unless advanced layout was enabled.


## [v1.6.0]

### Added
- Swipe-to-dismiss gesture for the dictionary popup.
- CSS highlighting for selected words in dictionary lookups.
- Support for additional audio formats in word audio databases.
- Modernized lookup UI and navigation stack.

### Changed
- Improved popup startup and lookup performance.
- Synchronized HUD animations with system navigation bars.
- Enhanced Anki markers flexibility and export sorting.
- Improved popup positioning and tap reliability.

### Fixed
- Fixed Novel Reader TOC navigation and layout shifting.
- Resolved inline image sizing issues in the Novel Reader.
- Fixed theme synchronization for various UI components (status/nav bars and bottom sheets).
- Improved OCR popup dismissal behavior on repeat taps.
- General stability improvements for the reader.


## [v1.5.0]

### Added
- Local Audio support.
- Novel progress and stats sync.
- Dict selection at at anki export marker.

### Fixed
- fixes to auto update.
- Update discord link to a working one.
- migration to the new wrapper for novels.


## [v1.4.2]

### Added
- Volume key navigation in Novel Reader.
- Customizable font size setting for the dictionary popup.
- Progress and reading statistics tracking for Novel Reader.
- Themed loading screen in Novel Reader matching selected background/text colors.
- More Anki markers.

### Changed
- Improve Anki markers.
- Improved auto-update reliability and download integrity checking.
- Optimized continuous mode scrolling and position restoration in Novel Reader.

### Fixed
- Inline image layout drops and viewport overflows in the Novel Reader.
- Navigation and boundary detection on image-only pages in Novel Reader.
- Dictionary popup dismissal logic when interacting with the Webtoon reader.

## [v1.4.1]

### Added
- Coordinate remapping for OCR blocks in Reader crop and double-page modes.

### Changed
- Improved flexibility and robustness of markers in Anki card templates.

### Fixed
- Horizontal pagination and margin calculation in Novel Reader.
- Novel Reader crash on release builds for specific books.
- Anki duplicate check reliability and model mismatch handling.
- Furigana stripping during recursive dictionary lookups.

## [v1.4.0]

### Added
- Novel Reader with lookup

### Changed
- Disable popup links

## [v1.3.6]

### Fixed
- revert broken bitmap capture

## [v1.3.5]

### Added
- Recursive dictionary lookups with enhanced search UX
- Pitch accent support in dictionary popup
- Anki and dictionary settings profiles
- Multiple dictionary import support
- Smart furigana distribution

### Changed
- OCR lookup and screenshot capture optimizations for Webtoon mode
- Image exports with WebP compression and resizing
- Anki dictionary mining UI with card navigation and sorting

### Fixed
- Paged reader panning while zoomed
- Popup dictionary order handling
- Webtoon reader gestures

## [v1.3.0]

### Added
- .mokuro OCR integration for reading .mokuro formatted manga files.
- Screenshot crop mode for Anki card creation with manual crop selection.
- Single glossary export with SINGLE_GLOSSARY marker support.
- Manga, chapter, and media Anki markers for card templates.
- Group terms toggle for dictionary popup definition grouping.

### Changed
- Single-tap OCR navigation: tap once on text to activate and show dictionary popup immediately.

### Improved
- Simplified OCR popup UI with better tap handling.

## [v1.2.2]

### Changed
- Enable updater and sync support for stable releases.
- Derive release app version name and version code from git tags in CI.

### Improved
- Refine chapter OCR status indicator badge behavior and layout.
- Improve OCR tap reliability across reader modes.

## [v1.2.1]

### Fixed
- Popup and Anki settings bugs.

## [v1.2.0]

### Added
- Webtoon OCR support.
- Arabic language support.

### Changed
- Improve OCR tap behavior.

### Fixed
- Popup and Anki UI issues.

## [v1.1.0]

### Added
- Per-line OCR rendering flow.
- OCR preprocessing and binary-search based text hit handling.
- URL interception for Anki bridge in OCR popup.

### Improved
- OWOCR merge quality.
- OCR rendering and preprocessing pipeline.

## [v1.0.0]

### Added
- Anki integration with auto-detect field mapping.
- OWOCR OCR engine processing.

### Changed
- Migration from Mihon base to Komikku base.
- App rebrand to Chimahon.

### Fixed
- Initial build and integration fixes around OCR and Anki.

## [v0.1.0]

### Added
- Initial Chimahon release baseline with integrated dictionary direction.
