# Novel Integration Handoff

Updated: 2026-07-20

## Current baseline

- Repo: `/Users/manhhao/GitHub/chimahon` (app package `app.chimahon`, dev variant `app.chimahon.dev`)
- Branch: `novel-wired`
- HEAD: `042668b143` (`fix(novel): align IReader detail and reader fallback`)
- Working tree at last update carried uncommitted changes to the browse/detail novel UI (see "2026-07-20 session" below); these were installed to a device but not committed.
- Published dependency: `io.github.ireaderorg:source-api:1.5.1`
- IReader source-api and extensions are cloned on demand into the session scratchpad, not kept in-tree. The extensions repo builds with **JDK 21** (Gradle 8.13 cannot parse JDK 25 — it fails with a bare version string under "What went wrong"). Build one source with `./gradlew :extensions:individual:jp:kakuyomu:assembleDebug -Dorg.gradle.java.home=<jdk21>`.
- Do not build unless the user explicitly asks.
- `AGENTS.md` and the old `novel-integration-plan.md` are no longer present in this simplified branch; this handoff is the current local source of task state.

## Architecture decision

Use Mihon's existing `ExtensionManager`, installer, receiver, trust model, update flow, and extension UI for both extension families, while keeping runtime loading and source registration type-safe:

- Manga APKs continue through the unchanged `tachiyomi.extension` loading branch and expose Mihon `Source` objects.
- IReader APKs use their real `ireader.extension` feature and `source.*` manifest metadata, instantiate `ireader.core.source.CatalogSource`, and expose wrapped `NovelSource` objects.
- Novel sources are registered only in `NovelSourceManager`; they are never forced into Mihon's manga `Source` interface.
- No second extension manager, installer, browser engine, or speculative preference UI is being added.

## Important corrected findings

- Current IReader APKs do **not** claim `tachiyomi.extension`.
- Verified manifest contract from a real repov2 APK:
  - features: `ireader`, `ireader.extension`
  - metadata: `source.class`, `source.name`, `source.lang`, `source.nsfw`
  - generated source class: `tachiyomix.extension.Extension`
  - generated constructor takes `ireader.core.source.Dependencies`
- Current repov2 index:
  - `https://raw.githubusercontent.com/IReaderorg/IReader-extensions/repov2/index.min.json`
  - 141 entries at inspection time; 128 lib-2 and 13 lib-1 entries.
- Official repov2 APK signer SHA-256 was verified against both lib-1 and lib-2 APKs:
  - `f4527fa6edd6de2a8ec987f9967bfdb8836dfef88e437a87ac81bba70557c17c`

## Completed in the current working tree

### Shared extension model and repository discovery

- Added `Extension.ContentType` with `MANGA` and `NOVEL`.
- Defaults remain `MANGA`, preserving existing constructors and manga behavior.
- `Extension.Installed` can carry `novelSources` separately from manga `sources`.
- `ExtensionApi` now fetches and normalizes the official IReader repov2 index in addition to configured manga repos.
- IReader APK/icon URLs use the existing Mihon install/download path.
- IReader available entries use the verified official signer fingerprint.

### Dedicated IReader APK loader branch inside `ExtensionLoader`

- Existing manga feature detection, lib range, no-argument construction, source factory handling, and source list handling remain on the manga branch.
- IReader is detected only by `ireader.extension`.
- Private and system-wide IReader APKs now participate in the same discovery/install/receiver lifecycle.
- IReader lib version parsing accepts the current lib-2 contract and legacy lib-1 entries from repov2.
- Officially signed IReader APKs are trusted by their pinned signer; differently signed APKs still use Mihon's normal explicit trust flow.
- IReader classes are created with `Dependencies` and wrapped as `IReaderNovelSource`.

### IReader runtime and command fetch support

- Added one shared `IReaderRuntime` using IReader source-api's own:
  - `HttpClients`
  - `BrowserEngine`
  - `WebViewManger`
  - cookie storage/synchronization
- Browser fetches are serialized with a mutex. IReader's Android browser implementation performs WebView work on `Dispatchers.Main`.
- Relative URLs are resolved against the source's `HttpSource.baseUrl`.
- Browser fetches now synchronize cookies before and after navigation and pass the WebView's final redirected URL to the Fetch command.
- Source preferences are persisted through Mihon's existing preference store under per-package namespaces.
- `IReaderNovelSource` follows IReader's normal request order: call the extension natively with no commands first, then supply browser HTML through these commands only when the native result fails or is empty:
  - `Command.Detail.Fetch`
  - `Command.Chapter.Fetch`
  - `Command.Content.Fetch`
- This avoids forcing every extension request through WebView, which can return different HTML, trigger avoidable 404s, or replace stable relative source keys with absolute browser URLs.
- Commands are still supplied only when the source declares the corresponding command.
- Detail mapping preserves the original stable novel key and retains existing fields when a parser returns blanks.
- If both native content parsing and browser fallback return no usable pages, the adapter now reports an error instead of generating and caching a blank reader chapter.
- IReader extension network/parsing calls run on `Dispatchers.IO`; only the source-api WebView and cookie synchronization are moved to `Dispatchers.Main`.
- `PageUrl` is resolved through IReader `HttpSource.getPage()` before conversion when supported.
- `ImageBase64` is converted to an image data URL and passed through the same image-container pipeline as large remote images.
- IReader `MovieUrl` and `Subtitle` pages are explicitly ignored by the novel adapter; the stale `ponytail:` catch-all comment was removed.
- Popular/latest browsing falls back to the source's first declared listing when a source does not use the exact `Popular`/`Latest` labels.

### Manager and browse UI registration

- `ExtensionManager` exposes filtered installed/available/untrusted novel extension flows.
- `NovelSourceManager` combines enabled server sources with installed IReader extension sources.
- The existing Novel Sources tab now renders a separate “Extension Sources” section and opens the existing `BrowseNovelSourceScreen`.
- Manga's `AndroidSourceManager`, source-to-extension lookup, and global-search extension filter now consume an explicit `installedMangaExtensionsFlow`.
- Novel extensions therefore cannot enter or trigger rebuilds in the manga source registry.

### Novel detail screen behavior

- Compared Chimahon's novel detail flow against IReader's `BookDetailViewModel`, `GetBookDetail`, and summary/header components.
- The selected novel is now displayed immediately while remote metadata and chapters refresh, matching IReader's existing-book-first behavior.
- Metadata and chapter requests are handled independently:
  - chapter failure no longer discards successfully fetched title, author, cover, status, description, or genres;
  - detail failure no longer prevents the chapter request from running;
  - failures are shown inline in the relevant detail/chapter section instead of replacing the whole screen.
- Genres returned by IReader extensions are now rendered on the detail screen; they were previously mapped into `SNNovel.genre` but never displayed.
- Cancellation is rethrown instead of being converted into a source failure during detail refresh.

### Manga regression audit

- Compared the manga branch in `ExtensionLoader` against `905ddf15a1`.
- Manga still uses:
  - only the `tachiyomi.extension` feature;
  - the original 1.4–1.5 lib range;
  - `tachiyomi.extension.*` metadata;
  - no-argument source/factory construction;
  - the same trust repository lookup;
  - the same `ChildFirstPathClassLoader`;
  - the same installed/available/update UI models.
- The configured manga repository fetch and JSON conversion are unchanged; IReader results are appended afterward.
- Manga and IReader repository requests run concurrently, so adding IReader does not add a serial network wait to normal extension refresh.
- Manga-specific source consumers now use the filtered manga flow. Shared extension management UI and update counts intentionally include both content types.

## Files changed for this task

- `app/src/main/java/eu/kanade/tachiyomi/extension/model/Extension.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/api/ExtensionApi.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/util/ExtensionLoader.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/ExtensionManager.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/ireader/IReaderExtensionConstants.kt`
- `app/src/main/java/eu/kanade/tachiyomi/extension/ireader/IReaderRuntime.kt`
- `app/src/main/java/eu/kanade/tachiyomi/sourcenovel/extension/IReaderNovelSource.kt`
- `app/src/main/java/chimahon/novel/manager/NovelSourceManager.kt`
- `app/src/main/java/chimahon/novel/ui/browse/NovelSourcesTab.kt`
- `app/src/main/java/eu/kanade/tachiyomi/di/AppModule.kt`
- `app/src/main/java/eu/kanade/tachiyomi/source/AndroidSourceManager.kt`
- `app/src/main/java/eu/kanade/domain/source/model/Source.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/browse/source/globalsearch/SearchScreenModel.kt`

## Verification status

- `./gradlew :app:installDebug` builds and installs on 2026-07-20; the 2026-07-20 session fixes were exercised live on a device (browse, search, detail, chapters, reader, covers).
- Real repov2 index, APK manifest, class metadata, version naming, and signer were inspected directly (see Japanese findings for the current index contents).
- Static API inspection against the IReader source-api reference is complete for the runtime types used here.
- Earlier note retained for context: an IReader master checkout had moved beyond published `1.5.1`; runtime construction was corrected against IReader commit `1a6a203`, where `packageVersion = "1.5.1"`.

## Japanese extension runtime findings

- The `IReaderorg/IReader-extensions` **master** source tree contains two Japanese (`jp`) lib-2 sources: Kakuyomu and Syosetu.
- **Kakuyomu is NOT in the published `repov2` index.** As of 2026-07-20 the live `index.min.json` has one `jp` entry only: `ireader.syosetu.jp` (Syosetu v2.1). Kakuyomu builds cleanly and upstream's own `not-working-source.json` marks it `"Working"`, yet it has never appeared in repov2 despite repeated rebuilds. Cause unconfirmed — most likely the chunked CI matrix (`settings.gradle.kts` splits sources by `CI_CHUNK_NUM`/`CI_CHUNK_SIZE`) drops it. An earlier theory that a missing `serialization-json` dependency broke its compile was **disproved** — it builds with no dependency edit; `kotlinx.serialization.json` resolves transitively through `ktor-serialization`.
- Consequence: to use Kakuyomu you must **build the APK yourself** (see baseline). It is debug-signed, so it does not match the pinned signer in `IReaderExtensionConstants.kt` and installs through Mihon's explicit-trust flow (the loader branch already handles this). Its manifest matches the verified contract: features `ireader`/`ireader.extension`, `source.class = tachiyomix.extension.Extension`, `source.lang = jp`.
- The user asked to ignore Syosetu and use Kakuyomu as the Japanese runtime target. Syosetu remains the only published `jp` source and is the fallback if a build isn't available.
- Live-site check (2026-07-20) drove two real Kakuyomu source fixes; see "2026-07-20 session".
- Missing thumbnails in Japanese browse results are not an adapter regression: Kakuyomu ranking pages contain **no cover images at all** (text + a colored block; the ranking page is plain server HTML with no embedded Apollo state). Covers exist only on detail pages via `adminCoverImageUrl` (a real Hatena-CDN URL). This is now handled app-side (lazy browse covers, see session notes).

## 2026-07-20 session

Runtime-tested Kakuyomu on a physical device (`app.chimahon.dev`) with a self-built v2.2 APK. Browse, search, detail, chapters, reader, and covers were exercised live. Fixes below; app-side changes are uncommitted in the working tree.

### Kakuyomu source fixes (in the extensions clone, not in this repo — port upstream via PR)

- **Rankings returned empty.** Not a selector break — `/rankings/*` now 302-redirects to add `?work_variation=long`, and the redirect target is `http://` (downgraded from https), which ktor refuses to follow, so the parse ran on nothing. Fix: request `?work_variation=long` directly (200, no redirect). Applied in `getMangaList(sort)`, `getMangaList(filters)`, and the `exploreFetchers` endpoint; paging switched to `&page=`. Only `/rankings/*` is affected — search and `/works/<id>` detail return 200 directly and were not touched. Bumped `versionCode` to 2 for update detection.
- Native content parsing is fine: `.widget-episodeBody` matches on live episode pages (147 `<p>`, 200, no redirect). The browser fallback is NOT required for Kakuyomu content.

### App-side fixes (working tree, uncommitted)

- **Chapter open threw "left the composition."** `openChapter` in `NovelDetailScreen.kt` launched on `rememberCoroutineScope()`; leaving composition mid-fetch cancelled it, and the generic `catch (Exception)` reported the resulting `CancellationException` as a failure toast. Fix: launch on `screenModel.screenModelScope`, rethrow `CancellationException` before the toast, drop the now-unused `scope` param.
- **Chapter open was slow.** `SourceChapterBookBuilder.build` prefetches every chapter before the reader opens (a 741-episode Kakuyomu work = 741 fetches). Two changes: moved the cache-copy path above `semaphore.acquire()` so cached chapters don't burn a fetch permit, and raised `FETCH_CONCURRENCY` from 4 to 12. (A windowed-fetch approach was prototyped and reverted in favor of the simpler, fully-reversible concurrency bump — the whole book is still built, just faster.)
- **Blank browse covers.** Fixed app-side because the data isn't in ranking HTML. `BrowseNovelSourceScreenModel.requestCover(novel)` lazily calls `source.getNovelDetails(novel)` for entries with no cover, throttled to 4, guarded by `coverRequested` (cleared on reset), and patches the one entry immutably. Both grids fire `LaunchedEffect(novel.url) { onRequestCover(novel) }`, so only cards scrolled into view fetch. Source-generic: Komga/Kavita/OPDS and search results already carry covers and skip it.
- **Search button did nothing.** `SearchToolbar` opens its field only when `searchQuery != null`, but the browse screen passed a constant `null` and a no-op `onSearchQueryChange`, so the icon tap had nothing to write. Fix: screen now holds `searchQuery` (`rememberSaveable`); toolbar `onClickCloseSearch` clears the query (was hardcoded to `navigateUp`, which popped the whole screen) and clearing reloads Popular.

## Remaining work

1. **Source filters (highest value-to-effort).** `IReaderNovelSource.getFilterList()` returns an empty `FilterList()` instead of translating the extension's `getFilters()`, and `getSearchNovels` ignores its `filters` param. The browse UI has no filter sheet. Net: genre/period/sort browsing is impossible even though sources support it (Kakuyomu exposes Genre + Period). Wiring: translate IReader `Filter`s ↔ Mihon `FilterList`, pass them through `getSearchNovels`, add a filter sheet to `BrowseNovelSourceScreen`.
2. **Background library updates.** No `NovelUpdateJob`; the manga `LibraryUpdateJob` ignores novels; there is no novel Updates feed. Favorited novels never auto-detect new chapters. Largest remaining piece (job + Updates feed + notifications).
3. **Downloads / offline.** No download queue or downloaded-badge UI; offline reading works only for chapters already opened (reader cache).
4. **Global search & migration exclude novels.** `globalsearch` and `migration` have no novel path — no cross-source novel search, no source-to-source migration.
5. **No tracking** (MAL/AniList/Kitsu) for novels — manga-only. Likely deliberate.
6. Verify `PageUrl` behavior against a real extension that returns indirection pages.
7. Audit cover requests for IReader sources that require custom headers/referrers; do not alter manga's cover fetch path.

## Deferred by design

- LNReader JavaScript
- Kavita extension runtime changes
- Komga extension runtime changes
- OPDS extension runtime changes
- Any second extension/browser engine
- Speculative IReader source-preference UI
