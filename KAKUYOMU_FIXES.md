# Kakuyomu extension — fixes to make it work

A step-by-step, replicable record of everything done to get the Kakuyomu IReader
novel source working in this app. Two parts:

1. Getting it to work at all — building the extension and fixing empty browse.
2. Making covers show in the browse grid.

Nothing else is covered here.

**Status:**
- Part 1 (Kakuyomu extension: build + rankings redirect fix) is **NOT done here** —
  it lives in a local `IReader-extensions` clone and must be **replicated** by
  whoever sets this up. Follow the steps below.
- Part 2 (covers) is **already done** — committed to this repo in `808b574762`.
  Documented here for reference only; no action needed.

---

## Part 1 — Making Kakuyomu work at all

### 1a. Why it needs a manual build

Kakuyomu exists in the `IReaderorg/IReader-extensions` **master** source tree
(`sources/jp/kakuyomu/`) but is **not** in the published `repov2` index. As of
2026-07-20 the live `index.min.json` has exactly one `jp` entry: Syosetu. Kakuyomu
compiles cleanly and upstream's own `not-working-source.json` marks it `"Working"`,
yet it never appears in the built repo — most likely the chunked CI matrix
(`settings.gradle.kts` splits sources by `CI_CHUNK_NUM`/`CI_CHUNK_SIZE`) drops it.

So there is no APK to download. You build it yourself.

### 1b. Build the APK

Clone the extensions repo (a shallow clone is enough):

    git clone --depth 1 https://github.com/IReaderorg/IReader-extensions
    cd IReader-extensions

Point Gradle at an Android SDK by writing `local.properties`:

    sdk.dir=/path/to/Android/sdk

**Use JDK 21.** The repo's Gradle wrapper is 8.13, which cannot parse JDK 25 — it
fails with a bare version string (e.g. `25.0.2`) under "What went wrong". Build the
single source module (the `:extensions:individual:<lang>:<name>` path comes from
`settings.gradle.kts`, which maps `sources/<lang>/<name>` when `CI` is unset):

    ./gradlew :extensions:individual:jp:kakuyomu:assembleDebug \
      -Dorg.gradle.java.home=/path/to/jdk-21

Use `assembleDebug` (auto-signed with the debug key, installable immediately), not
`assembleRelease` (unsigned). The APK lands at:

    sources/jp/kakuyomu/build/outputs/apk/jp/debug/ireader-jp-kakuyomu-v<ver>.apk

Note: an earlier theory that Kakuyomu failed to build because of a missing
`serialization-json` dependency was **wrong** — it builds with no dependency edit.
`kotlinx.serialization.json` resolves transitively through `ktor-serialization`.

### 1c. Install and trust

    adb install -r <path-to-apk>

The APK is debug-signed, so it does **not** match the pinned official signer in
`IReaderExtensionConstants.kt`. It installs through the app's explicit-trust flow
(the loader branch already handles differently-signed IReader APKs). The
`source.icon` URL points at a repov2 path that doesn't exist, so the extension icon
404s — cosmetic only.

Manifest sanity check (matches the app's IReader loader contract):

- features: `ireader`, `ireader.extension`
- `source.class` = `tachiyomix.extension.Extension`
- `source.lang` = `jp`, source class id = `92L`

### 1d. Fix: browse tab shows nothing (rankings redirect)

**Symptom:** the extension installs and loads, but the browse grid is empty.

**Cause — NOT a selector break.** The ranking page markup is unchanged and the
parser selectors still match. The failure is in the HTTP request:
`https://kakuyomu.jp/rankings/*` now returns a **302 redirect** that appends
`?work_variation=long`, and the redirect target is **`http://`** (downgraded from
https). Ktor refuses to follow an https→http downgrade by default, so the request
dead-ends, the parse runs on nothing, and browse renders empty with no error.

How to confirm (any HTTP client):

    # 302 -> http://kakuyomu.jp/rankings/all/entire?work_variation=long
    curl -s -D- -o /dev/null "https://kakuyomu.jp/rankings/all/entire"

    # 200, no redirect
    curl -s -D- -o /dev/null "https://kakuyomu.jp/rankings/all/entire?work_variation=long"

Only `/rankings/*` is affected. `/search?q=` and `/works/<id>` (detail) return 200
directly and need no change.

**Fix:** request the post-redirect URL directly by adding `?work_variation=long` to
the ranking URLs, and switch paging from `?page=` to `&page=` (the query already has
a param). In
`sources/jp/kakuyomu/main/src/ireader/kakuyomu/Kakuyomu.kt`:

`getMangaList(sort, page)`:

    // before
    val url = "$baseUrl/rankings/all/entire" + if (page > 1) "?page=$page" else ""
    // after
    val url = "$baseUrl/rankings/all/entire?work_variation=long" + if (page > 1) "&page=$page" else ""

`getMangaList(filters, page)` (the genre/period branch):

    // before
    val url = "$baseUrl/rankings/$genre/$period" + if (page > 1) "?page=$page" else ""
    // after
    val url = "$baseUrl/rankings/$genre/$period?work_variation=long" + if (page > 1) "&page=$page" else ""

`exploreFetchers` endpoint:

    // before
    endpoint = "/rankings/all/entire",
    // after
    endpoint = "/rankings/all/entire?work_variation=long",

Bump `versionCode` in `sources/jp/kakuyomu/build.gradle.kts` (e.g. `1` → `2`) so the
app detects the rebuild as an update, then rebuild (1b) and reinstall (1c).

This fix lives in the extensions clone, not in this app repo. It is a genuine
upstream bug — worth a PR to `IReaderorg/IReader-extensions`.

**Note on content:** native content parsing is fine — `.widget-episodeBody` matches
on live episode pages (200, no redirect). The WebView browser fallback is not
required for Kakuyomu content.

---

## Part 2 — Making covers show in the browse grid

### 2a. Why covers were blank, and why it can't be fixed in the extension

Kakuyomu ranking pages contain **no cover images at all** — each card is text plus a
solid color block, and the ranking page is plain server-rendered HTML with no
embedded Next.js/Apollo data to mine. So the extension returning `cover = ""` for
ranking entries is correct; there is nothing on that page to parse.

Covers exist only on each work's **detail** page, in `__NEXT_DATA__` →
`__APOLLO_STATE__` → the `Work` object's `adminCoverImageUrl` (a real Hatena-CDN
URL). The extension's `getMangaDetails` already maps that field, so a novel gets a
cover once its detail is fetched.

Therefore the fix is app-side: fetch each visible entry's detail lazily to obtain
its cover. It is source-generic — Komga/Kavita/OPDS and Kakuyomu search results
already carry covers, so they skip the fetch.

### 2b. The app-side change (committed)

Files:
- `app/src/main/java/chimahon/novel/ui/browse/BrowseNovelSourceScreenModel.kt`
- `app/src/main/java/chimahon/novel/ui/browse/BrowseNovelSourceScreen.kt`

**Screen model** — add a throttled, guarded, lazy cover fetch. It fetches detail
only for entries with a blank cover, at most once each, and patches that one entry
immutably so the card recomposes and Coil loads the image:

    private val coverSemaphore = Semaphore(4)
    private val coverRequested = mutableSetOf<String>()

    fun requestCover(novel: SNNovel) {
        if (!novel.thumbnail_url.isNullOrBlank()) return
        val src = source ?: return
        if (!coverRequested.add(novel.url)) return
        screenModelScope.launch {
            try {
                val cover = coverSemaphore.withPermit { src.getNovelDetails(novel).thumbnail_url }
                if (cover.isNullOrBlank()) return@launch
                val current = mutableState.value.novels
                val index = current.indexOfFirst { it.url == novel.url }
                if (index < 0 || !current[index].thumbnail_url.isNullOrBlank()) return@launch
                mutableState.value = mutableState.value.copy(
                    novels = current.toMutableList()
                        .apply { this[index] = this[index].copy(thumbnail_url = cover) }
                        .toImmutableList(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                coverRequested.remove(novel.url)
            }
        }
    }

The guard set is cleared on `reset` in `loadListing`, so switching Popular/Latest or
running a search re-enables fetching for entries that reappear without a cover.
On failure the guard entry is removed so a transient error retries next time the card
is shown. Requires `import kotlinx.coroutines.sync.Semaphore`,
`kotlinx.coroutines.sync.withPermit`, and `kotlinx.coroutines.CancellationException`.

**Screen** — fire the request per card, so only cards actually scrolled into view
fetch (a Kakuyomu ranking page has ~100 entries; eager fetching would hit detail 100
times per page). Thread an `onRequestCover: (SNNovel) -> Unit` from `Content` down
into both grids, and in each grid's `items(novels, key = { it.url })` block add:

    LaunchedEffect(novel.url) { onRequestCover(novel) }

`LaunchedEffect` runs when the card enters composition (scrolls into view) and is
disposed when it leaves — that is what makes the fetch lazy and per-visible-item.
Wire `onRequestCover = screenModel::requestCover` at the `Content` call site.

### 2c. Behavior and trade-off

- Covers populate with a visible delay (each is a full detail fetch), not instantly.
- Fast-scrolling many cards drives many background detail requests. If that's too
  heavy, cap how aggressively `requestCover` fires.
- The card reads `MangaCover(ogUrl = novel.thumbnail_url)`, so a null thumbnail
  logs `MangaCoverFetcher: No cover specified` — expected before the lazy fetch
  fills it in, harmless.
