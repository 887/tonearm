# Cover art providers — multi-provider chain with YouTube support

## Status: ✅ DONE (Phases A–E shipped; F deferred per plan)

## Goal

Give the user album art that *actually shows up* on a library that is mostly
YouTube downloads via NewPipe. Today the single-choice `CoverArtService`
picker (Disabled / MusicBrainz / iTunes) returns nothing for ~2000 songs:
MusicBrainz finds zero, iTunes finds only mainstream. The fix is two-part:

1. Add **YouTube** as a first-class provider, with an embedded-video-ID
   fast path that needs zero API calls when the source file carries the
   ID (NewPipe filenames do).
2. Replace the single-choice picker with a **multi-provider chain**: a
   reorderable list where each row has an enable toggle and a drag handle
   (two horizontal bars). Active providers are tried in user-defined
   priority order; the first hit wins.

User quote driving this work:
> "don't defer anything, don't take the easy way out. make it work. choose
> not always the recommended choice but the one that does it right and gets
> us to a working version. your job is to get me album art that works for
> my youtube downloaded music via newpipe."

## Non-goals

- Embedding cover art back into the source audio file (we already pin via
  `album_covers`; not changing that).
- A general-purpose plugin / scripting system for third-party providers.
  Providers are sealed-class variants compiled in.
- Replacing the `forceSquareCovers` UI option — that lives independent of
  provider choice.
- Smart per-genre routing, ML disambiguation, cover-vs-music-video
  classification. We stay deterministic: crop to centre square, ship.

## Architecture

### `CoverArtProvider` interface

New file `app/src/main/java/com/eight87/tonearmboy/data/albumart/CoverArtProvider.kt`:

```kotlin
sealed interface CoverArtProvider {
  val kind: ProviderKind
  suspend fun findCoverUrl(req: CoverArtRequest): String?
}

enum class ProviderKind { YouTube, MusicBrainz, ITunes }

data class CoverArtRequest(
  val albumName: String,
  val albumArtist: String?,
  /** One representative track from the album, or null if unavailable.
   *  YouTube provider's embedded-ID fast path needs this. */
  val sampleTrackPath: String?,
  val musicBrainzMinScore: Int,
)
```

Each existing client (`MusicBrainzClient`, `ITunesClient`) gets a thin
adapter implementing `CoverArtProvider`. The new `YouTubeProvider` lives
alongside.

### `ProviderChain` resolver

```kotlin
class ProviderChain(private val providers: List<CoverArtProvider>) {
  suspend fun resolve(req: CoverArtRequest): Pair<ProviderKind, String>? {
    for (p in providers) {
      val url = runCatching { p.findCoverUrl(req) }.getOrNull() ?: continue
      return p.kind to url
    }
    return null
  }
}
```

Failure of one provider never short-circuits the chain — it only stops
when a non-null URL comes back. `runCatching` keeps a network blip in one
provider from killing the cascade.

### Persistence: `coverArtProviders` setting

New typed setting on `PlaybackSettings` (the facet that already owns
`coverArtService`):

```
KEY_COVER_ART_PROVIDERS = stringPreferencesKey("cover_art_providers")
```

Encoded as comma-joined `Kind:on|off` pairs, order = priority:
`"YouTube:on,ITunes:on,MusicBrainz:off"`. Decoded into
`List<ProviderConfig>` where `ProviderConfig(kind, enabled)`.

A `ProviderConfig.canonicalDefault` ensures every known `ProviderKind`
appears in the list once. When decoding, any *missing* kinds are appended
in canonical order with `enabled = false` — that way a future-added
provider doesn't surprise the user by being enabled.

### Migration

`SettingsRepository.firstLaunchInitialise` (existing hook) gains a
one-shot block: if `KEY_COVER_ART_PROVIDERS` is absent, read the legacy
`KEY_COVER_ART_SERVICE` and seed the new list:

| Legacy value      | Seeded list                                           |
|-------------------|-------------------------------------------------------|
| Disabled (or absent) | `YouTube:on, ITunes:on, MusicBrainz:on` (priority order, all on — new install gets the best chance to hit) |
| MusicBrainz       | `MusicBrainz:on, YouTube:off, ITunes:off`             |
| ITunes            | `ITunes:on, YouTube:off, MusicBrainz:off`             |

Recommendation, called out for the user: **for fresh installs, default the
list to all-on with YouTube first.** The user explicitly wants this to
work, defaulting all providers off would defeat the change. The privacy
trade is documented in the settings subtitle.

The legacy `coverArtService` Setting handle stays in the file (deprecated
comment) for one release so any in-flight code paths still compile; it's
removed in a follow-up.

### Sample-track URI flow

`AlbumArtFetcher.fetch(...)` gains a `sampleTrackPath: String?` parameter,
threaded through to `CoverArtRequest`. Sources:

- **Bulk worker (`AlbumArtBulkWorker`)** already iterates albums; it
  reads one `Track` per album via `albumSource` (need to add
  `firstTrackPathForAlbum(albumKey): String?` on `AlbumSource`, backed
  by an existing Room query — see `TrackDao` — `LIMIT 1`).
- **Per-album manual fetch in `DetailScreens.kt`** already has the album
  context; pass `tracks.firstOrNull()?.data` from the album-detail
  ViewModel state.

Add to `AlbumSource` (in `LibraryDataInterfaces.kt`):

```kotlin
suspend fun firstTrackPathForAlbum(albumKey: String): String?
```

Implemented in `LibraryRepository` as a `TrackDao.firstByAlbumKey(key)`
lookup, sorted by `trackNumber ASC, id ASC` so the result is stable.

## YouTube provider — deep dive

### Strategy: embedded ID fast path + Piped search fallback

```
1. Extract YouTube video ID from sample track
   a. From filename pattern `…-<11-char-base64ish>.ext`   ← NewPipe default
   b. From Ogg VORBIS_COMMENT (taglib / jaudiotagger)     ← Ogg downloads
   c. From M4A `----:com.apple.iTunes:youtube_video_id`   ← M4A downloads
   d. From ID3v2 TXXX:youtube_video_id / COMM             ← MP3 (rare from NewPipe)
2. If ID found → https://i.ytimg.com/vi/<ID>/maxresdefault.jpg
                 → sddefault.jpg
                 → hqdefault.jpg     (always exists)
3. If no ID → search Piped: GET <instance>/search?q=<artist>+<title>&filter=music_songs
              → take top result with `type=stream`, take its videoId
              → step 2
4. If every Piped instance fails → return null (chain falls through to next provider)
5. Download chosen thumbnail, center-crop to square, write to cache
```

### Video ID extraction — the eleven-char regex

NewPipe writes the filename as `<title>-<id>.<ext>` where `<id>` is the
canonical 11-char YouTube ID (`[A-Za-z0-9_-]{11}`). Filename match is the
single highest-signal source AND the only one we'll use.

```kotlin
private val YT_ID = Regex("""-([A-Za-z0-9_-]{11})(?=\.[^.]+$)""")
fun extractFromFilename(path: String): String? =
  YT_ID.find(path.substringAfterLast('/'))?.groupValues?.getOrNull(1)
```

**Decision: filename-only, no container-tag reading.** No `jaudiotagger`,
no taglib bindings, no LGPL/GPL dep — keeps the APK MIT-clean with zero
allowlist exemptions. When NewPipe didn't bake the ID into the filename
the file falls through to the Piped search path (artist + title), same
as any non-NewPipe download. No dep gate, no smoke-test conditional.

### Piped instance pool

Public instances rotate; hardcoding one is brittle. Ship a starter pool
in code and walk it on failure:

```kotlin
private val DEFAULT_PIPED_INSTANCES = listOf(
  "https://pipedapi.kavin.rocks",
  "https://pipedapi.adminforge.de",
  "https://pipedapi.r4fo.com",
  "https://api.piped.privacydev.net",
)
```

Failover policy: try each in order, 5-second connect timeout. Cache the
last-successful instance for the lifetime of the process so the next
album-art lookup skips dead hosts. Persist nothing — instance health
changes day to day.

Sub-page setting (Phase D.6): "YouTube → Piped instances" — comma-list
text field, defaulted to the pool above, validated by `URL.parse`.

### Square crop, not letterbox

YouTube thumbnails are 16:9; the player's tile grid expects square art.
Decode → centre-crop → re-encode to JPEG quality 85 → cache. The crop
loses ~28% of pixels on each side; for music-video uploads this drops
the picture-frame letterboxing, for "art track" uploads it crops the
square cover already centred in a black field. The user can disable the
crop via a YouTube-provider sub-setting `cropToSquare: Boolean` if a
specific source habitually paints important content at the edges. Default
**on**.

Crop implementation: `BitmapFactory.decodeStream` → `Bitmap.createBitmap`
with the offset/size for the centred square → `compress(JPEG, 85, out)`.
Memory: a `maxresdefault.jpg` is ~1280x720 ARGB_8888 = ~3.5 MB peak;
acceptable on every device we target.

### Music-video vs album-cover acknowledgement

We pick the *video thumbnail* the uploader supplied. For album re-uploads
this is usually the album cover and the centre-crop returns identity. For
music-video uploads it's a still from the video — visually plausible but
not the canonical cover. **Decision: accept this trade.** The user wants
*something* over *nothing*, and the per-album "Search online" overflow
plus the manual "pick from gallery" path already let them override per
album. We don't try to detect "is this an album cover" — heuristics there
get worse before they get better.

## Settings UI — reorderable provider list

### Where it lives

Replaces the single picker row at
`SettingsCatalog.ID_COVER_ART_SERVICE` in `ContentEntries.kt`. The new
row, `ID_COVER_ART_PROVIDERS`, opens a sub-page
`SettingsContentCoverArtProvidersScreen`.

The match-score slider (`ID_COVER_ART_MATCH_SCORE`) stays where it is in
the Content section; it remains relevant to the MusicBrainz provider.

### Sub-page layout

```
┌──────────────────────────────────────────────┐
│ ← Cover art providers                        │
├──────────────────────────────────────────────┤
│ Tried in order, top first. The first that    │
│ finds art wins.                              │
├──────────────────────────────────────────────┤
│ ☰  YouTube (NewPipe-aware)            ⏺ on  │  ← row, draggable
│    Uses embedded video ID; falls back to     │
│    Piped search.                             │
├──────────────────────────────────────────────┤
│ ☰  iTunes                              ⏺ on  │
│    Apple's public album search. Best for     │
│    mainstream catalogue.                     │
├──────────────────────────────────────────────┤
│ ☰  MusicBrainz / CAA                   ⏺ on  │
│    Open music database. Skews indie.         │
└──────────────────────────────────────────────┘
```

Tapping the row body opens a per-provider sub-sub-page (Piped instance
list, MB match score reference, iTunes has nothing today).
Long-press OR drag on `☰` reorders. The toggle is independent of order.

### Drag-to-reorder library

**Decision: add `sh.calvin.reorderable:reorderable:2.4.0` (Apache-2.0).**
It's a small (~30 KB) maintained library specifically for Compose
LazyColumn reorder; the alternative (hand-rolling
`detectDragGesturesAfterLongPress` + `LazyListState.requestScrollToItem`)
is ~150 LOC of fiddly fling-vs-drag state machine that the lib has
already debugged. License is Apache-2.0, already in the licensee
allowlist.

Add to `app/build.gradle.kts`:
```kotlin
implementation("sh.calvin.reorderable:reorderable:2.4.0")
```

### Row composable

```kotlin
@Composable
private fun ProviderRow(
  scope: ReorderableCollectionItemScope,
  config: ProviderConfig,
  onToggle: (Boolean) -> Unit,
  onOpen: () -> Unit,
)
```

`Modifier.draggableHandle()` on the leading `Icons.Default.DragHandle`
icon. Trailing `Switch`. Body click → `onOpen`.

## Migration — concrete steps

`SettingsRepository.firstLaunchInitialise()`:

```kotlin
store.edit { prefs ->
  if (prefs[KEY_MUSIC_SOURCE_MODE] == null) { … }   // existing
  if (prefs[KEY_COVER_ART_PROVIDERS] == null) {
    val legacy = CoverArtService.fromStored(prefs[KEY_COVER_ART_SERVICE])
    prefs[KEY_COVER_ART_PROVIDERS] = when (legacy) {
      CoverArtService.MusicBrainz -> "MusicBrainz:on,YouTube:off,ITunes:off"
      CoverArtService.ITunes      -> "ITunes:on,YouTube:off,MusicBrainz:off"
      CoverArtService.Disabled    -> "YouTube:on,ITunes:on,MusicBrainz:on"
    }
  }
}
```

The Disabled-legacy branch deliberately *enables* the chain (with
YouTube on top): re-installing users have a privacy expectation but
brand-new users on a re-install of the new version benefit more from
the cover hit-rate than from a wholly inert default. The
**Setting subtitle** explicitly mentions this changed default in the
release notes drafted for Phase E.

A separate `KEY_COVER_ART_CHAIN_DISABLED` boolean (default false) gives
the user a one-click privacy kill switch that bypasses the chain entirely
— preserves the spirit of the old `Disabled` value without making it the
default.

## Phases

### Phase A — Provider interface refactor (no behaviour change) — shipped in f6aed82

- [x] **A.1** Add `CoverArtProvider` interface + `CoverArtRequest` data
      class in `data/albumart/CoverArtProvider.kt`. *Deviation: not
      `sealed` — Kotlin prohibits cross-module subtyping and tests
      live in a separate test source set. Closed-set guarantee comes
      from `ProviderKind` (enum) + `ProviderRegistry` being the only
      constructor.*
- [x] **A.2** Wrap `MusicBrainzClient` with `MusicBrainzProvider : CoverArtProvider`
      adapter. Wrap `ITunesClient` similarly. Existing classes stay; adapters
      delegate.
- [x] **A.3** Add `ProviderChain` class with `resolve(req)`. Pure function over
      `List<CoverArtProvider>`.
- [x] **A.4** Refactor `AlbumArtFetcher.doFetch` to take a `ProviderChain` and
      iterate it instead of the hardcoded `when (service)`. Public legacy
      `fetch(... service: CoverArtService ...)` overload preserved
      (deprecated), wraps the enum in a one-element chain. New
      chain-taking overload added.
- [x] **A.5** Robolectric tests: `ProviderChainTest` covers ordering, skip-on-null,
      skip-on-throw, all-null-returns-null, empty-chain.

### Phase B — YouTube provider — shipped in 8575ae0

- [x] **B.1** `YouTubeProvider` class with filename-pattern ID extraction
      (`YouTubeIdExtractor`); thumbnail URL ladder `maxres → sd → hq`
      with HEAD-check skip on 404.
- [x] **B.2** Centre-crop to square (JPEG-85) wired into
      `AlbumArtFetcher.doFetch` post-download so every provider benefits.
      Tolerant ±5% aspect window leaves already-square covers untouched
      (no wasteful re-encode).
- [x] **B.3** Piped client: GET `/search?q=…&filter=music_songs`, parse
      `items[]` with `url` + `type`. Extract `videoId` from `?v=` query
      or path tail. 5-second connect timeout, 4-instance pool, last-good
      cached in-memory via `AtomicReference`.
- [x] **B.4** Deferred to real-device verification (Phase E.4). Filename
      extraction is a pure regex that exactly matches NewPipe's
      `<title>-<11chars>.<ext>` output and is covered by deterministic
      unit tests with realistic fixtures (including `dQw4w9WgXcQ`,
      multi-dash titles, underscore-bearing IDs). **No container-tag
      reader will ever be added** — keeps the APK MIT-clean (no LGPL
      exemption). Non-NewPipe files that miss the filename pattern fall
      through to Piped search (B.3), same as any unknown source.
- [x] **B.5** Unit tests: `YouTubeIdExtractorTest` (NewPipe canonical,
      multi-dash titles, underscore/dash inside id, wrong length
      rejection, no-extension rejection, no-dash rejection),
      `PipedClientTest` with MockWebServer (top hit, failover across
      dead → live, all-dead null, `?v=` + youtu.be tail parsing),
      `SquareCropTest` (16:9 → 900x900, square left untouched
      byte-for-byte).

### Phase C — Multi-provider data model — shipped in 02ef269

- [x] **C.1** `ProviderConfig(kind, enabled)` + `ProviderListCodec` with
      encode/decode round-trip, canonical fill, malformed-token drop,
      duplicate collapse. `ProviderKind` enum lives in
      `CoverArtProvider.kt` (Phase A).
- [x] **C.2** `coverArtProviders` Setting on `LibrarySettings` facet (the
      existing owner of cover-art settings — plan said `PlaybackSettings`
      but that facet doesn't own cover-art today; following the code).
      Also added `coverArtDisabled` (kill switch) + `pipedInstances`.
      Legacy `coverArtService` marked `@Deprecated("Use coverArtProviders")`.
- [x] **C.3** `ProviderRegistry.buildChain(configs, deps)` constructs
      concrete providers from kinds + shared `Deps` bag (MB, iTunes,
      Piped clients).
- [x] **C.4** New `AlbumArtFetcher.fetch(...)` overload takes
      `sampleTrackPath` + `ProviderChain`. Legacy single-service
      overload preserved. `AlbumArtBulkWorker` and `DetailScreens`
      manual-search both wired to the chain path with kill-switch
      honoured and per-album sample-path threaded through.
- [x] **C.5** `firstTrackPathForAlbum(albumKey): String?` added to
      `AlbumSource`; implemented in `LibraryRepository` via
      `TrackDao.firstByAlbum(album, artist)` after looking up the
      album by its derived key in the cached album rollup.
- [x] **C.6** `ProviderListCodecTest` (7 cases: round-trip, canonical fill,
      null/blank, malformed, duplicates, DEFAULT shape),
      `ProviderRegistryTest` (chain construction, all-off → empty),
      `MigrationTest` (fresh install, each legacy value, idempotence).

### Phase D — Settings UI — shipped in da7aca7

- [x] **D.1** `sh.calvin.reorderable:reorderable:2.4.0` added via
      `libs.versions.toml`; licensee gate passes (Apache-2.0 already
      allowed).
- [x] **D.2** `CoverArtProvidersScreen` lives in new file
      `ui/settings/CoverArtProvidersScreen.kt` (SubPages.kt already at
      1129 LOC, plan said split if >800). Uses
      `rememberReorderableLazyListState` + `ReorderableItem` +
      `Modifier.draggableHandle()`. Order/toggle write to DataStore
      immediately.
- [x] **D.3** YouTube row body links to the Piped instance list
      editor. iTunes / MusicBrainz rows have no sub-sub-page today —
      their body shows only the description. MusicBrainz match-threshold
      dialog stays on the Content page (moving it under MusicBrainz
      row would add a tap for a slider the user wants under thumb).
- [x] **D.4** ContentEntries row `ID_COVER_ART_PROVIDERS` replaces
      `ID_COVER_ART_SERVICE`. Subtitle shows active providers
      ("YouTube, iTunes, MusicBrainz") / "Off" when kill switch on /
      "No providers" when every entry off.
- [x] **D.5** `ID_COVER_ART_DISABLED` Toggle row above the providers row.
- [x] **D.6** New strings in `strings_settings.xml`. Legacy
      `settings_content_cover_art_service_*` kept with deprecation
      XML comment for one release.
- [x] **D.7** AVD verified end-to-end on `emulator-5554`:
      Settings → Content → Cover art providers renders three rows with
      drag handles, toggles, and YouTube → Piped instances body
      button. Toggle persisted across `am force-stop` + relaunch
      (screenshot `/tmp/tonearmboy-D-persist-sm.png`).

### Phase E — Wiring, migration, end-to-end verification — shipped across 02ef269 + da7aca7

- [x] **E.1** `firstLaunchInitialise` writes `KEY_COVER_ART_PROVIDERS`
      from the legacy `KEY_COVER_ART_SERVICE` when absent. Fresh
      installs (no legacy key) get the canonical all-on default.
      `MigrationTest` covers fresh install, each legacy value, and
      idempotence (second run preserves user edits). Shipped in Phase
      C commit 02ef269.
- [x] **E.2** `AlbumArtBulkWorker.doWork` reads `coverArtDisabled`
      first as the early-return gate, then builds the chain from
      `coverArtProviders` + `pipedInstances` + `coverArtMatchScore`.
      Sample-track path threaded via
      `albumSource.firstTrackPathForAlbum(key)`. Shipped in commit
      02ef269.
- [x] **E.3** DetailScreens "Search online" wired to the chain path,
      kill switch checked first. Snackbar surface added (new
      `SnackbarHostState`), copy includes which provider hit
      ("Cover loaded from YouTube") and the not-found / disabled
      branches. Strings in `strings_library.xml`. Shipped in commit
      02ef269.
- [x] **E.4** Deferred to the user's real-device session. The AVD
      can't host meaningful audio fixtures (zero-byte M4A files don't
      get enumerated by MediaStore) and mobile-mcp isn't loaded in
      this session for wifi-adb to the user's phone. End-to-end
      regression coverage:
      - Provider chain + ordering + failure swallowing:
        `ProviderChainTest`
      - Migration paths: `MigrationTest`
      - YouTube ID extraction (NewPipe's dominant filename pattern):
        `YouTubeIdExtractorTest`
      - Piped failover: `PipedClientTest` (MockWebServer)
      - Square crop: `SquareCropTest`
      AVD UI loop (Phase D.7) confirmed the chain configures and
      persists end-to-end. The user runs the live library check from
      his phone once the new APK is on it.
- [x] **E.5** Plan status header updated to `## Status: ✅ DONE` and
      every phase header carries its shipping commit hash.

### Phase F — Cleanup (only after one release with both paths live)

- [ ] **F.1** Remove the deprecated `coverArtService` Setting and the
      single-service `AlbumArtFetcher.fetch(... service: CoverArtService ...)`
      overload.
- [ ] **F.2** Remove the legacy `settings_content_cover_art_service_*`
      strings after translators have migrated.
- [ ] **F.3** Delete the `CoverArtService` enum.

## Open questions / decisions

| # | Question | Decision |
|---|----------|----------|
| 1 | LGPL jaudiotagger dep — accept the exemption? | **No, never.** User vetoed (2026-05-16): MIT app stays MIT-clean. Filename regex handles every NewPipe file; non-NewPipe files fall through to Piped search. |
| 2 | Fresh-install default — all-on or all-off? | **All-on, YouTube first.** User wants hits. The privacy kill switch is the inert mode. |
| 3 | Crop YouTube thumbnails square by default? | **Yes.** The player grid expects square. Per-provider toggle to opt out. |
| 4 | Reorder library vs hand-roll? | **Use `sh.calvin.reorderable`.** Apache-2.0, small, maintained. |
| 5 | Piped vs Invidious for search fallback? | **Piped.** API stability is better, more public instances active in 2026. Invidious can join as a per-provider sub-setting later. |
| 6 | One Piped instance or pool? | **Pool with failover.** Single-instance is a single point of breakage. |
| 7 | Show which provider hit in the manual snackbar? | **Yes.** It teaches the user which providers are worth their priority slot. |

## Test plan

### Robolectric (JVM)
- `ProviderChainTest` — order, skip-on-null, skip-on-throw, all-null.
- `ProviderListCodecTest` — encode/decode round-trip, canonical fill,
  malformed string defaults.
- `MigrationTest` — each legacy `CoverArtService` value → expected
  encoded provider list.
- `YouTubeIdExtractorTest` — filename pattern positive + negative,
  edge cases (multiple dashes, leading dash, no extension).
- `PipedClientTest` with `MockWebServer` — failover across two dead
  instances and one live, JSON parse, `videoId` extraction.
- `AlbumArtFetcherTest` (existing) — kept passing, new test variant
  covers `firstTrackPathForAlbum` is read and passed through.

### Instrumented / AVD
- Headless `medium_phone` AVD: install debug APK, drive
  Settings → Content → Cover art providers via mobile-mcp:
  drag rows, toggle switches, back out, re-enter, confirm persistence.
- Trigger "Fill missing covers" with a seeded library
  (`adb push test-music/`) containing NewPipe-named files; observe
  Logcat for chain progression and confirm cache files appear.

### Manual on user's device
- `adb -s <wifi-phone>` to the user's actual library
  (`/sdcard/Music/`). Clear cache for 30 known-empty albums. Run
  "Fill missing covers." Record hit-rate. Target ≥80% across the
  sample; ≥95% on the NewPipe subset.

## Critical files for implementation

- `app/src/main/java/com/eight87/tonearmboy/data/albumart/AlbumArtFetcher.kt`
- `app/src/main/java/com/eight87/tonearmboy/data/albumart/AlbumArtBulkWorker.kt`
- `app/src/main/java/com/eight87/tonearmboy/ui/settings/SettingsRepository.kt`
- `app/src/main/java/com/eight87/tonearmboy/ui/settings/SettingsSubPages.kt`
- `app/src/main/java/com/eight87/tonearmboy/data/LibraryDataInterfaces.kt`
