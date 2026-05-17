package com.eight87.tonearmboy.ui.library.tabs

import androidx.test.core.app.ApplicationProvider
import com.eight87.tonearmboy.data.model.Album
import com.eight87.tonearmboy.data.model.Artist
import com.eight87.tonearmboy.data.model.Genre
import com.eight87.tonearmboy.data.model.Playlist
import com.eight87.tonearmboy.data.model.Track
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.SortDirection
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R.D.3 — pure-logic contract tests for the five [TabSpec]
 * implementations. These cover the non-Composable surface
 * (id / sectionKey / toTile / supportsTileMode / testTag /
 * emptyMessage). [toTile] now takes a `Resources` (T.A.3) so the
 * subtitle can be translated, which is why this test runs under
 * Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TabSpecContractTest {

  private val byName = TabSort(SortKey.Name, SortDirection.Ascending)
  private val byArtist = TabSort(SortKey.Artist, SortDirection.Ascending)
  private val byDuration = TabSort(SortKey.Duration, SortDirection.Ascending)

  private val resources by lazy {
    ApplicationProvider.getApplicationContext<android.content.Context>().resources
  }

  // -- Albums ---------------------------------------------------------

  @Test
  fun albums_spec_section_keys_track_each_sort_mode() {
    val spec = AlbumsTabSpec(AlbumCoversMode.Default)
    val a = Album(id = 1, name = "Aerial Roots", artist = "X",
      trackCount = 4, year = 2024, mediaStoreAlbumId = null)
    // Name sort → first-letter section.
    assertEquals("A", spec.sectionKey(a, byName, intelligentSorting = false))
    // Artist sort → first-letter of artist name (initials).
    assertNotNull(spec.sectionKey(a, byArtist, intelligentSorting = false))
    // Duration sort actually sorts by track count → count bucket.
    assertNotNull(spec.sectionKey(a, byDuration, intelligentSorting = false))
  }

  @Test
  fun albums_spec_supports_tile_mode_and_carries_album_art_id() {
    val spec = AlbumsTabSpec(AlbumCoversMode.Default)
    val a = Album(id = 7, name = "Beach", artist = "X",
      trackCount = 4, year = 2024, mediaStoreAlbumId = 42L)
    val tile = spec.toTile(a, resources)
    assertNotNull(tile)
    assertEquals(7L, tile!!.id)
    assertEquals("Beach", tile.title)
    assertEquals(42L, tile.albumArtId)
    assertTrue(spec.supportsTileMode)
    assertEquals("albums_tab", spec.testTag)
  }

  @Test
  fun albums_spec_falls_back_to_unknown_artist_when_blank() {
    val spec = AlbumsTabSpec(AlbumCoversMode.Default)
    val a = Album(id = 1, name = "X", artist = null,
      trackCount = 4, year = 2024, mediaStoreAlbumId = null)
    assertEquals("Unknown artist", spec.toTile(a, resources)!!.subtitle)
  }

  // -- Artists --------------------------------------------------------

  @Test
  fun artists_spec_sections_by_name_or_artist_sort() {
    val a = Artist(id = 9, name = "Zaza", albumCount = 1, trackCount = 3)
    assertEquals("Z", ArtistsTabSpec.sectionKey(a, byName, intelligentSorting = false))
    assertEquals("Z", ArtistsTabSpec.sectionKey(a, byArtist, intelligentSorting = false))
    // Artists' Duration sort actually sorts by trackCount → count bucket.
    assertNotNull(ArtistsTabSpec.sectionKey(a, byDuration, intelligentSorting = false))
  }

  @Test
  fun artists_spec_tile_has_no_album_art() {
    val a = Artist(id = 9, name = "Zaza", albumCount = 1, trackCount = 3)
    val tile = ArtistsTabSpec.toTile(a, resources)
    assertNotNull(tile)
    assertNull(tile!!.albumArtId)
    assertEquals("artists_tab", ArtistsTabSpec.testTag)
    assertTrue(ArtistsTabSpec.supportsTileMode)
  }

  // -- Genres ---------------------------------------------------------

  @Test
  fun genres_spec_sections_match_sort_mode() {
    val g = Genre(id = 1, name = "ambient", trackCount = 12)
    assertEquals("A", GenresTabSpec.sectionKey(g, byName, intelligentSorting = false))
    // Genres' Duration sort actually sorts by trackCount → count bucket.
    assertNotNull(GenresTabSpec.sectionKey(g, byDuration, intelligentSorting = false))
    assertEquals("genres_tab", GenresTabSpec.testTag)
  }

  // -- Playlists ------------------------------------------------------

  @Test
  fun playlists_spec_does_not_support_tile_mode_and_returns_null_tile() {
    val p = Playlist(id = 1, name = "Mix", trackCount = 5, createdAtSeconds = 0)
    assertFalse(PlaylistsTabSpec.supportsTileMode)
    assertNull(PlaylistsTabSpec.toTile(p, resources))
    assertEquals("M", PlaylistsTabSpec.sectionKey(p, byName, intelligentSorting = false))
    assertEquals("playlists_tab", PlaylistsTabSpec.testTag)
  }

  // -- Tracks ---------------------------------------------------------

  @Test
  fun tracks_spec_sections_track_each_sort_mode() {
    val spec = TracksTabSpec(AlbumCoversMode.Default, onAction = { _, _ -> })
    val t = Track(
      id = 1, title = "Apricot", artist = "x", album = "y", albumArtist = null,
      durationMs = 1000, trackNumber = null, year = null, genre = null,
      data = "/x", dateAddedSeconds = 0,
    )
    assertEquals("A", spec.sectionKey(t, byName, intelligentSorting = false))
    // Tracks' Duration sort produces a length bucket (e.g. "< 2 min").
    assertNotNull(spec.sectionKey(t, byDuration, intelligentSorting = false))
    assertEquals("tracks_tab", spec.testTag)
    assertTrue(spec.supportsTileMode)
  }

  @Test
  fun tracks_spec_tile_carries_album_art_id_and_artist_subtitle() {
    val spec = TracksTabSpec(AlbumCoversMode.Default, onAction = { _, _ -> })
    val t = Track(
      id = 1, title = "Apricot", artist = "Solo", album = "y", albumArtist = null,
      durationMs = 1000, trackNumber = null, year = null, genre = null,
      data = "/x", dateAddedSeconds = 0, mediaStoreAlbumId = 99L,
    )
    val tile = spec.toTile(t, resources)
    assertNotNull(tile)
    assertEquals(99L, tile!!.albumArtId)
    assertEquals("Solo", tile.subtitle)
  }

  @Test
  fun tracks_spec_blank_artist_drops_subtitle() {
    val spec = TracksTabSpec(AlbumCoversMode.Default, onAction = { _, _ -> })
    val t = Track(
      id = 1, title = "x", artist = "  ", album = null, albumArtist = null,
      durationMs = 1, trackNumber = null, year = null, genre = null,
      data = "/x", dateAddedSeconds = 0,
    )
    assertNull(spec.toTile(t, resources)!!.subtitle)
  }
}
