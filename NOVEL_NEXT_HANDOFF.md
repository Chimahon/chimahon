# Novel Integration Handoff

Updated: 2026-06-21

## Current baseline

- Repo: `F:\sohil\github\mihon`
- Branch: `novel-wired`
- HEAD: `905ddf15a1` (`feat: expand novel model interfaces, add IReaderNovelSource adapter, fix remaining compilation errors`)
- IReader source API reference: `C:\tmp\IReader`
- IReader extension reference: `C:\tmp\IReader-extensions`
- Published dependency: `io.github.ireaderorg:source-api:1.5.1`
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
- Source preferences are persisted through Mihon's existing preference store under per-package namespaces.
- `IReaderNovelSource` now supplies real HTML to:
  - `Command.Detail.Fetch`
  - `Command.Chapter.Fetch`
  - `Command.Content.Fetch`
- Commands are supplied only when the source declares the corresponding command.
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

- `.\gradlew :app:compileDebugKotlin` passed on 2026-06-21 after the user explicitly requested compile verification.
- `git diff --check` passes.
- Real repov2 index, APK manifest, class metadata, version naming, and signer were inspected directly.
- Static API inspection against `C:\tmp\IReader` is complete for the runtime types used here.
- Compile exposed that `C:\tmp\IReader` master had moved beyond published `1.5.1`; runtime construction was corrected against IReader commit `1a6a203`, where `packageVersion = "1.5.1"`.

## Remaining work

1. Improve extension details behavior for novel extensions only if necessary; do not create source-preference UI unless a real IReader APK contract supports it.
2. Verify `PageUrl` behavior against a real extension that returns indirection pages.
3. Runtime test with at least one real lib-2 APK should cover:
   - repo listing and icon
   - private/system install
   - trust/update matching
   - source appearing under Novel Sources
   - popular/search/detail/chapters/content
   - a source declaring all three Fetch commands
   - manga extension install/browse/update regression

## Deferred by design

- LNReader JavaScript
- Kavita extension runtime changes
- Komga extension runtime changes
- OPDS extension runtime changes
- Any second extension/browser engine
- Speculative IReader source-preference UI
