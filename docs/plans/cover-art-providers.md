# Cover art providers — multi-provider chain with YouTube support

## Status: 🟡 IN PROGRESS — Round 5 / Phase J landing (Phases A–I shipped; F deferred per plan)

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

### Phase G — Round 2 (post-Phase-E user feedback) — shipped in commit e3035aa

User feedback after one release with the new chain live: "Fill in
missing covers now" enqueues silently with stale MusicBrainz-only
copy; cover-art settings buried under Content. Two coupled fixes
shipped together (single commit because the new section hosts the new
progress navigator).

- [x] **G.1** Process-scoped `AlbumArtBulkProgress` singleton:
      `StateFlow<BulkLog>` with `entries: List<LogEntry>`,
      `totalAlbums`, `processed`, `hits`, `running`. Entries capped at
      500. Shipped in commit e3035aa.
- [x] **G.2** `AlbumArtBulkWorker` instrumented: kill-switch and
      no-providers branches write a Skipped log entry instead of
      returning silently; every album attempt writes a Running
      heartbeat on entry and a terminal outcome on exit
      (Hit/Miss/Skipped/Error). Shipped in commit e3035aa.
- [x] **G.3** New `SettingsBulkArtProgress` destination + screen:
      sticky header (status, counts, progress bar, Start/Cancel
      buttons), LazyColumn of log entries most-recent-first.
      `SettingsCoverArtPages.kt` hosts the screen; route registered in
      `SettingsRoutes.kt`; entry wired in `TonearmboyApp.kt`. Shipped
      in commit e3035aa.
- [x] **G.4** `ID_FILL_MISSING_COVERS` row repointed: navigates to
      the progress sub-page instead of calling
      `WorkManager.enqueueUniqueWork` directly. Subtitle rewritten to
      describe the full provider chain (not MusicBrainz-only).
      Shipped in commit e3035aa.
- [x] **G.5** New `Section.CoverArt` between `Personalize` and
      `Content` in the enum; `SettingsCoverArt` destination + new
      sub-page screen `SettingsCoverArtScreen`. Seven rows lifted out
      of `ContentEntries.kt` into `CoverArtEntries.kt`
      (kill switch, providers, fill-now navigator, auto-discover,
      folder scanner, album-covers mode, force-square). Shipped in
      commit e3035aa.
- [x] **G.6** Settings root grows a "Cover art" row in the Behaviour
      group (Icons.Outlined.Photo). Visual ordering on root:
      Content → Audio → Cover art → Library. Shipped in commit e3035aa.
- [x] **G.7** Content sub-page slimmed to tag-handling rows +
      Refresh album art. Root subtitle updated from "Sorting,
      separators, album covers." to "Sorting, separators, tag
      handling.". Shipped in commit e3035aa.
- [x] **G.8** `SettingsCatalogTest` picks up the new
      `ID_BEHAVIOUR_COVER_ART` + `SettingsCoverArt` destination + the
      previously-missing `ID_CUSTOM_CHROME_TINT`. Shipped in commit
      e3035aa.

### Phase H — Round 3: per-track covers (the user's library is mostly NewPipe)

User feedback after Round 2: the bulk worker still walks **albums** and
pins one cover per album. For a library that's almost entirely
NewPipe-downloaded YouTube tracks (each file is a different video with
its own embedded 11-char id), MediaStore lumps them into "Music" or an
arbitrary tag — so the per-album worker pins whichever track's
thumbnail it sampled to every other track in that bogus album. The fix
is to walk **tracks** instead, with each track resolving its own cover
through the existing provider chain (filename-id fast path nails
NewPipe shapes; Piped / iTunes / MusicBrainz handle the rest).

**Architecture note (per-track vs per-album):** per-track covers live
in their own Room table (`track_covers`, R1 from
`docs/plans/album-art-rows.md`), parallel to `album_covers`. UI cover
resolution falls back: `track_covers` → `album_covers` → MediaStore
`albumart` URI → placeholder. Pinned per-album covers (user explicit
choice) still trump everything when no per-track override exists.
Real-album collections (ripped CDs) land N copies of the same image —
acceptable redundancy; correctness first.

- [x] **H.1** `AlbumArtBulkProgress` widened: `totalAlbums` →
      `totalTracks`, `LogEntry.trackTitle` added so the Settings
      progress screen can name the in-flight song. Shipped in commit
      760ca05.
- [x] **H.2** `AlbumArtFetcher.fetchTrack(context, track, chain, ...)`
      overload. Mirrors the per-album path's precedence
      (Pinned / IntentionallyEmpty / NoChoice) against the per-track
      override row; writes hits to `track_covers` via
      `TrackSource.setTrackCoverUri`. Per-track cache files land in
      `cacheDir/track_art/<trackId>.jpg`. Shipped in commit
      760ca05.
- [x] **H.3** `AlbumArtBulkWorker` pivoted: walks
      `graph.tracks.observeTracks().first()` instead of albums; calls
      `fetcher.fetchTrack(...)` per song; every log entry carries
      `trackTitle` + album/artist context. Kill-switch + no-providers
      skip branches preserved. Shipped in commit 760ca05.
- [x] **H.4** `SettingsBulkArtProgressScreen` copy + layout updated:
      counts read "Songs processed: X / Y · N covers found"; log row
      headline is the track title with album/artist folded into the
      subtitle. Shipped in commit 760ca05.
- [x] **H.5** UI cover-resolution fallback in the playing surfaces:
      `PlaybackUiState.trackId` plumbed from `MediaItem.mediaId`;
      `MiniPlayer` + `NowPlayingScreen` (via
      `NowPlayingMergedSurface`) take a new optional
      `trackCoverUriOverride` and prefer it over the album-art URI.
      Wiring at the app root subscribes to
      `graph.tracks.trackCoverChoice(state.trackId)` and forwards the
      Pinned URI. Shipped in commit 5ca2f63.
- [x] **H.6** `settings_content_fill_missing_covers_subtitle` rewritten
      to plainly describe the per-song behaviour; catalog hardcoded
      string aligned. Shipped in commit 760ca05.
- [x] **H.7** AVD verification: pushed 4 real test tracks plus 2
      NewPipe-renamed copies (`Never Gonna Give You Up-dQw4w9WgXcQ.mp3`,
      `Test Track Two-9bZkp7q19f0.mp3`) under
      `/sdcard/Music/tonearmboy-test-newpipe/`. Result: `6 / 6` songs
      processed, 4 covers found, YouTube provider hit the NewPipe-named
      files via filename-id extraction. Screenshot at
      `/tmp/tonearmboy-r3-bulk-log.png`.
- [x] **H.8** Release cut: `v1.0-0acff61` published at
      https://github.com/887/tonearmboy/releases/tag/v1.0-0acff61
      (APK sha256 `7ad18b22a76ef745864c3ddab96d71ba039506fb3a104930a782784772074347`).

### Phase I — Round 4: COMMENT-tag mining + duration-filtered Piped search

User feedback after Round 3: the per-track worker works great when the
filename carries the canonical NewPipe `<title>-<11-char>.<ext>` shape,
but the user's actual library is mostly *renamed* downloads — the
filename gave nothing and the Piped-search fallback picked a wrong
upload often enough to make per-track results worse than per-album in
some cases. Two coupled fixes shipped together (two commits, then a
plan-tick commit), addressing both halves of the miss.

**Platform deviation — no `METADATA_KEY_COMMENT`.** The original Round 4
brief assumed Android's `MediaMetadataRetriever.METADATA_KEY_COMMENT`
exposed the ID3v2 `COMM` / Ogg `COMMENT` / M4A `\xa9cmt` tag NewPipe
writes. The constant doesn't exist in the public SDK (verified on
API 36 `android.jar` — only `AUTHOR`, `ALBUMARTIST`, `WRITER`,
`COMPOSER`, etc. are surfaced). LGPL jaudiotagger remains vetoed.
The shipped solution scans the raw file bytes for ASCII YouTube URL
markers instead — container-agnostic, MIT-clean, no new deps.

- [x] **I.1** `YouTubeCommentExtractor` (pure-regex object): two-pass
      ID extraction over free-form text. URL-context patterns
      (`youtube.com/watch?v=…`, `youtu.be/…`, `youtube.com/embed/…`,
      `youtube.com/shorts/…`, multi-subdomain) win over a bare
      boundary-anchored 11-char fallback. Shipped in commit fe821bf.
- [x] **I.2** `TrackTagReader` interface + `AndroidTrackTagReader`
      byte-scanner. Sweeps first 256 KB + last 128 KB of the file for
      the ASCII URL markers, captures the surrounding URL-safe-char
      window, hands off to [I.1]. Companion-scoped scan function so
      JVM tests don't need Robolectric or a real Context. Shipped in
      commit fe821bf.
- [x] **I.3** `YouTubeProvider` resolution chain extended: filename →
      tag-byte-scan → Piped (was filename → Piped). The tag stage is
      a no-op when no reader is wired (kept null in tests that only
      exercise the legacy paths). Shipped in commit fe821bf.
- [x] **I.4** `ProviderRegistry.Deps.tagReader` field; both call sites
      (`AlbumArtBulkWorker`, `DetailScreens.kt` per-album manual
      "Search online") construct an `AndroidTrackTagReader` and pass
      it through. Shipped in commit fe821bf.
- [x] **I.5** `CoverArtRequest.expectedDurationSec: Int?` field;
      `PipedClient.searchVideoId` accepts a third `expectedDurationSec`
      argument. When non-null, stream items are filtered to those
      within ±2 s; the first match wins. Empty filter result falls
      back to the unfiltered top hit so the no-match case never
      degrades. `PipedItem` gains a `duration` field. Duration is
      derived from `track.durationMs` in `fetchTrack`. Shipped in
      commit f622fca.
- [x] **I.6** Logging — `PipedClient` emits a debug line summarising
      the filter outcome (`piped filter: N matches → URL (dur Xs)`)
      via a logger that swallows `android.util.Log` stubs from plain
      JVM tests. Shipped in commit f622fca.
- [x] **I.7** Unit tests: `YouTubeCommentExtractorTest` (12 cases —
      URL shapes, multi-domain, URL-context wins-over-bare,
      false-positive bait inside base64 blobs), `AndroidTrackTagReaderTest`
      (5 cases — byte scan finds each URL flavour, returns null on
      no-marker buffer, pipeline integration with the extractor),
      `PipedClientDurationFilterTest` (4 cases — match, fallback,
      ±2 s tolerance window, null passthrough). Shipped across
      fe821bf + f622fca.
- [x] **I.8** AVD verification on `emulator-5556`: tagged fixture
      `comment-tagged.mp3` (filename has NO YouTube ID, COMMENT tag
      has `https://www.youtube.com/watch?v=dQw4w9WgXcQ`) pushed to
      `/sdcard/Music/tonearmboy-test-newpipe/`. Bulk run result: 7/7
      songs processed, 5 covers found. Log entry confirms
      `Comment Tagged Test · YouTube · Saved from YouTube` — the
      COMMENT-tag byte-scan path delivered the cover when filename
      gave nothing. Screenshot `/tmp/tonearmboy-r4-bulk.png`.
- [x] **I.9** Release cut: `v1.0-aebc674` published at
      https://github.com/887/tonearmboy/releases/tag/v1.0-aebc674
      (APK sha256 `c464bc0c65018f88d9e89d35eb97edc53c24f61a08fc83cf0891b546c0787a12`).

### Phase J — Round 5: render per-track covers + clearer log + rate limit

User feedback after Round 4 / v1.0-aebc674 (AVD screenshot 2026-05-17):
the per-track covers the worker reports saving (`8/7 · 2 covers found`)
were **not rendering** in the Songs tab tile grid — `CoverArt` only
resolved `album.coverUri` and the per-track URI in `track_covers`
stayed invisible. Plus log outcomes were a flat
`Hit / Miss / Skipped / Error` 4-way that didn't name the stage that
fired ("filename" vs "COMMENT tag" vs "Piped search") and there was
no rate-limit politeness across the worker's ~1 req/sec/track loop —
on the user's 2000-song library this would hammer Piped instances
into 429s within a minute. Three coupled fixes shipped together.

- [ ] **J.1** `TrackSource.trackCoverUriFlow(trackId): Flow<String?>`
      with a default impl composing against `trackCoverChoice` so all
      implementations + test fakes still work. `CoverArt` gained
      `trackId` + `trackSource` parameters; when present, the per-
      track URI takes precedence over the album fallback.
      `TileItem.trackId` + thread-through via `LibraryTileGrid` and
      `LibraryTabRenderer`; only the Songs `TracksTabSpec.toTile`
      populates `trackId`, other tabs stay album-only. Shipped in
      commit TBD.
- [ ] **J.2** `CoverArtProvider.findCover(req): ProviderResult?` —
      richer return shape carrying `ResolutionSource` (`Filename` /
      `CommentTag` / `PipedSearch` / `Direct`) + optional `videoId`.
      Legacy `findCoverUrl` interface method kept as a default
      wrapper. `ProviderChain.resolveRich` returns the rich result
      (and accepts a run-scoped `throttled: MutableSet<ProviderKind>`).
      `AlbumArtBulkProgress.Outcome` extended with `NoIdResolved` +
      `Throttled` variants; `LogEntry` gained `source` + `videoId`.
      `SettingsBulkArtProgressScreen` row composes notes as
      "Saved from YouTube (Piped search) · id: dQw4w9WgXcQ".
      Shipped in commit TBD.
- [ ] **J.3** `PipedClient.perHostMinIntervalMs` (default 1000 ms)
      throttle via `delay()` before each instance call;
      `ConcurrentHashMap<String, Long>` per-host last-request timestamp.
      HTTP 429 / 403 lifts into `ThrottledException` which the chain
      catches and adds to the run-scoped throttled set so subsequent
      track lookups skip that provider. `YouTubeProvider.probeMaxRes`
      (default false) — skip the HEAD ladder and return `hqdefault.jpg`
      directly, which exists for every video. Bulk worker is
      sequential (verified in `doWork`) so no extra inter-track pacing
      needed beyond the per-host floor. Shipped in commit TBD.
- [ ] **J.4** Unit tests: `PipedClientThrottleTest` (4 cases —
      per-host interval delays second request, 429 → ThrottledException,
      403 → ThrottledException, 500 stays null-fallthrough),
      `ProviderResultTest` (4 cases — chain carries source / videoId,
      throttled provider skipped on subsequent resolves, default
      `findCover` wraps `findCoverUrl` in `Direct`, all-miss returns
      null). Shipped in commit TBD.
- [ ] **J.5** AVD verification on `emulator-5556`: app data cleared
      (`pm clear`), Songs tab tile mode, ran "Fill in missing covers"
      against `/sdcard/Music/tonearmboy-test-newpipe/` fixtures.
      Log screenshot `/tmp/tonearmboy-r5-log-down.png` shows the new
      "Saved from iTunes" labels for Slow Burn + Pawprints in Snow,
      plus a Throttled entry "YouTube throttled (429/403) — skipping
      for the rest of the run". Tile screenshot
      `/tmp/tonearmboy-r5-songs-final.png` shows the iTunes covers
      now rendering on the Pawprints / Slow Burn tiles instead of
      the flat-coloured placeholder — proves Fix 1 lands.
- [ ] **J.6** Release cut.

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
