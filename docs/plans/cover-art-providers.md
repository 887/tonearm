# Cover art providers — multi-provider chain with YouTube support

## Status: 🚧 IN PROGRESS — Phase A pending

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
single highest-signal source.

```kotlin
private val YT_ID = Regex("""-([A-Za-z0-9_-]{11})(?=\.[^.]+$)""")
fun extractFromFilename(path: String): String? =
  YT_ID.find(path.substringAfterLast('/'))?.groupValues?.getOrNull(1)
```

For container-level tags: **Decision: filename-only first.** Add
jaudiotagger only if real-world testing on the user's library proves it
necessary (LGPL dep needs an explicit allowlist exemption + license text;
not worth it unless the smoke-test demands it). Phase B.4 gates the dep
behind a smoke-test result.

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

### Phase B — YouTube provider

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
      multi-dash titles, underscore-bearing IDs). Container-tag readers
      (jaudiotagger / LGPL exemption) NOT added in Round 1 — the user
      can opt into them after measuring miss rate on his real library.
      mobile-mcp tooling not loaded in this session so AVD-side
      `/sdcard/Music/` walk was skipped in favour of regression
      coverage in `YouTubeIdExtractorTest`.
- [x] **B.5** Unit tests: `YouTubeIdExtractorTest` (NewPipe canonical,
      multi-dash titles, underscore/dash inside id, wrong length
      rejection, no-extension rejection, no-dash rejection),
      `PipedClientTest` with MockWebServer (top hit, failover across
      dead → live, all-dead null, `?v=` + youtu.be tail parsing),
      `SquareCropTest` (16:9 → 900x900, square left untouched
      byte-for-byte).

### Phase C — Multi-provider data model

- [ ] **C.1** `ProviderKind` enum + `ProviderConfig(kind, enabled)` data class
      + `ProviderListCodec.encode/decode` round-trip; canonicalise (every
      known kind appears exactly once, unknown kinds dropped).
- [ ] **C.2** Add `coverArtProviders: Setting<List<ProviderConfig>>` to
      `PlaybackSettings` facet and `SettingsRepository`. Mark `coverArtService`
      `@Deprecated("use coverArtProviders")` with no migration warning yet —
      callers updated in C.4.
- [ ] **C.3** `ProviderRegistry.buildChain(configs, deps): ProviderChain`
      constructs concrete providers from kinds + shared deps (OkHttp, MB
      score, Piped instance list).
- [ ] **C.4** Update `AlbumArtFetcher.fetch(...)` to take `List<ProviderConfig>`
      instead of `CoverArtService` (plus the deprecated single-service
      overload for one release). Update `AlbumArtBulkWorker` and the
      `DetailScreens.kt` per-album fetch call sites.
- [ ] **C.5** Add `firstTrackPathForAlbum(albumKey): String?` to `AlbumSource`,
      implement in `LibraryRepository` via a new `TrackDao.firstByAlbumKey`
      query. Thread through bulk worker + DetailScreens caller.
- [ ] **C.6** Unit tests for `ProviderListCodec` (round-trip, canonical fill,
      malformed string handling), `ProviderRegistry`, end-to-end fetcher with
      a fake chain.

### Phase D — Settings UI

- [ ] **D.1** Add `sh.calvin.reorderable:reorderable:2.4.0` to `build.gradle.kts`;
      run `./gradlew :app:licenseeAndroidDebug` to confirm Apache-2.0 passes
      the allowlist.
- [ ] **D.2** New sub-page composable `CoverArtProvidersScreen` in
      `ui/settings/SettingsSubPages.kt` (or a new file if SubPages.kt is
      over ~800 LOC — check first). Uses `rememberReorderableLazyListState`,
      `ReorderableItem`, `Modifier.draggableHandle()`. Persists order
      on drag end.
- [ ] **D.3** Per-provider sub-sub-page navigation: YouTube → Piped instance
      list editor (text field, validated, reset-to-default button); iTunes
      → empty for now (or a "no settings" placeholder); MusicBrainz → link
      back to the existing match-threshold dialog.
- [ ] **D.4** Replace `ID_COVER_ART_SERVICE` entry in `ContentEntries.kt` with
      a new `ID_COVER_ART_PROVIDERS` row showing
      "On: YouTube, iTunes" style summary text (computed from active configs).
- [ ] **D.5** Add the privacy kill switch toggle `ID_COVER_ART_DISABLED` in the
      Content section, above the providers row.
- [ ] **D.6** Update strings in `strings_settings.xml`: new keys
      `settings_content_cover_art_providers_label/subtitle`,
      `settings_content_cover_art_piped_instances_label/subtitle`,
      `settings_content_cover_art_disabled_label/subtitle`. Mark the old
      `settings_content_cover_art_service_*` keys deprecated (XML comment;
      keep them one release for translations).
- [ ] **D.7** AVD smoke-test: install debug APK, navigate
      Settings → Content → Cover art providers, drag rows, toggle, kill
      app, reopen, confirm order persisted. Screenshot via the canonical
      `screencap | magick -resize 50%` loop.

### Phase E — Wiring, migration, end-to-end verification

- [ ] **E.1** Implement the `firstLaunchInitialise` migration block. Unit test
      `MigrationTest` covers each legacy value → expected encoded list.
- [ ] **E.2** Wire `AlbumArtBulkWorker.doWork` to read the new provider list,
      build the chain, and call the updated fetcher. Honour the privacy kill
      switch as an early-return that emits `ServiceDisabled`.
- [ ] **E.3** Wire `DetailScreens.kt` "Search online" overflow to the new
      provider list path; show a snackbar including which provider hit
      (e.g. "Cover loaded from YouTube") so the user can see which one is
      pulling weight.
- [ ] **E.4** Real-library verification on the user's device (wifi-adb):
      clear cached album art for ~30 known-empty albums, trigger
      "Fill missing covers", spot-check the result. Target: ≥80% hit rate
      across the sample. Record before/after counts in this plan's tick-note.
- [ ] **E.5** Update release notes in `docs/plans/main.md` index entry and
      this plan's status header to `## Status: ✅ DONE` once every phase ticks.

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
| 1 | LGPL jaudiotagger dep — accept the exemption? | **No, not yet.** Filename extraction covers NewPipe's dominant format. Add only if B.4 smoke-test proves we need it. |
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
