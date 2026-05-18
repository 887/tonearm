package com.eight87.tonearmboy.ui.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-driven JVM tests for the unified [SettingsRepository].
 * Exercises the real DataStore Preferences file under
 * `Context.tonearmboyDataStore` so we know the keys, defaults, and
 * round-trip parsing all line up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  private val repo = SettingsRepository(context)

  @Before
  fun resetState() = runTest {
    // The `preferencesDataStore` delegate caches the DataStore by name,
    // so deleting the on-disk file isn't enough — we explicitly reset
    // every key the suite touches before each test runs. This keeps
    // tests independent of declaration order and JUnit's per-JVM
    // method-ordering quirks.
    repo.setIntelligentSorting(true)
    repo.setForceSquareCovers(false)
    repo.setCustomBarAction(CustomBarAction.Default)
    repo.setCustomNotificationAction(CustomNotificationAction.Default)
    repo.setPauseOnRepeat(false)
    repo.setPlayFromLibrary(PlayFromLibrary.Default)
    repo.setPlayFromItemDetails(PlayFromItemDetails.Default)
    repo.setHideCollaborators(false)
    repo.setLibraryTabs(LibraryTab.DefaultOrder)
    LibraryTab.entries.forEach { repo.setTabSort(it, TabSort.Default) }
    repo.setTheme(ThemePreference.System)
    repo.setMultiValueSeparators(MultiValueSeparator.Default)
    repo.setMusicSourceUris(emptySet())
    repo.setMusicSourceMode(MusicSourceMode.Default)
    repo.setAutomaticReloading(false)
    repo.setBaseTheme(BaseTheme.Default)
    repo.setAlbumArtTintEnabled(true)
  }

  @After
  fun tearDown() {
    context.filesDir.resolve("datastore").deleteRecursively()
  }

  @Test
  fun defaults_match_documented_per_setting() = runTest {
    // R.B.5 — SettingsSnapshot is gone; assert each Setting<T> emits
    // its documented default when no key has been written.
    assertEquals(ThemePreference.Default, repo.theme.flow.first())
    assertEquals(LibraryTab.DefaultOrder, repo.libraryTabs.flow.first())
    assertEquals(true, repo.intelligentSorting.flow.first())
    assertEquals(false, repo.forceSquareCovers.flow.first())
    assertEquals(CustomBarAction.Default, repo.customBarAction.flow.first())
    assertEquals(CustomNotificationAction.Default, repo.customNotificationAction.flow.first())
    assertEquals(false, repo.pauseOnRepeat.flow.first())
    assertEquals(PlayFromLibrary.Default, repo.playFromLibrary.flow.first())
    assertEquals(PlayFromItemDetails.Default, repo.playFromItemDetails.flow.first())
    assertEquals(false, repo.hideCollaborators.flow.first())
    assertEquals(ReplayGainStrategy.Default, repo.replayGainStrategy.flow.first())
    assertEquals(0f, repo.replayGainPreampDb.flow.first())
    assertEquals(AlbumCoversMode.Default, repo.albumCoversMode.flow.first())
    assertEquals(MultiValueSeparator.Default, repo.multiValueSeparators.flow.first())
    assertEquals(emptySet<String>(), repo.musicSourceUris.flow.first())
    assertEquals(MusicSourceMode.Default, repo.musicSourceMode.flow.first())
    assertEquals(false, repo.automaticReloading.flow.first())
    assertEquals(BaseTheme.Default, repo.baseTheme.flow.first())
    assertEquals(true, repo.albumArtTintEnabled.flow.first())
  }

  @Test
  fun toggles_round_trip() = runTest {
    repo.setIntelligentSorting(false)
    repo.setForceSquareCovers(true)
    assertEquals(false, repo.intelligentSorting.flow.first())
    assertEquals(true, repo.forceSquareCovers.flow.first())
  }

  @Test
  fun perTabSort_persistsIndependently() = runTest {
    repo.setTabSort(LibraryTab.Songs, TabSort(SortKey.Duration, SortDirection.Descending))
    repo.setTabSort(LibraryTab.Albums, TabSort(SortKey.Date, SortDirection.Ascending))
    val songs = repo.tabSort(LibraryTab.Songs).first()
    val albums = repo.tabSort(LibraryTab.Albums).first()
    val artists = repo.tabSort(LibraryTab.Artists).first()
    assertEquals(SortKey.Duration, songs.key)
    assertEquals(SortDirection.Descending, songs.direction)
    assertEquals(SortKey.Date, albums.key)
    assertEquals(TabSort.Default, artists)
  }

  @Test
  fun libraryTabsOrder_roundTrips_visible_set() = runTest {
    // Round 12 — tabs not in the visible list are now persisted as
    // hidden (after the `_hidden_` marker) and dropped on read so the
    // user's toggle-off actually sticks. The reader returns ONLY the
    // visible portion; hidden tabs no longer reappear.
    repo.setLibraryTabs(listOf(LibraryTab.Albums, LibraryTab.Songs))
    val order = repo.libraryTabs.flow.first()
    assertEquals(listOf(LibraryTab.Albums, LibraryTab.Songs), order)
  }

  @Test
  fun zz_d9a_pickers_and_toggles_default_correctly_and_round_trip() = runTest {
    assertEquals(CustomBarAction.SkipNext, repo.customBarAction.flow.first())
    assertEquals(
      CustomNotificationAction.RepeatMode,
      repo.customNotificationAction.flow.first(),
    )
    assertEquals(false, repo.pauseOnRepeat.flow.first())
    assertEquals(PlayFromLibrary.AllSongs, repo.playFromLibrary.flow.first())
    assertEquals(PlayFromItemDetails.ShownItem, repo.playFromItemDetails.flow.first())
    assertEquals(false, repo.hideCollaborators.flow.first())

    repo.setCustomBarAction(CustomBarAction.ShuffleToggle)
    repo.setCustomNotificationAction(CustomNotificationAction.Shuffle)
    repo.setPauseOnRepeat(true)
    repo.setPlayFromLibrary(PlayFromLibrary.ItemOnly)
    repo.setPlayFromItemDetails(PlayFromItemDetails.Album)
    repo.setHideCollaborators(true)

    assertEquals(CustomBarAction.ShuffleToggle, repo.customBarAction.flow.first())
    assertEquals(CustomNotificationAction.Shuffle, repo.customNotificationAction.flow.first())
    assertEquals(true, repo.pauseOnRepeat.flow.first())
    assertEquals(PlayFromLibrary.ItemOnly, repo.playFromLibrary.flow.first())
    assertEquals(PlayFromItemDetails.Album, repo.playFromItemDetails.flow.first())
    assertEquals(true, repo.hideCollaborators.flow.first())
  }

  @Test
  fun zz_hideCollaborators_flow_emits_independently() = runTest {
    assertEquals(false, repo.hideCollaborators.flow.first())
    repo.setHideCollaborators(true)
    assertEquals(true, repo.hideCollaborators.flow.first())
  }

  @Test
  fun libraryTabsParser_handlesUnknownToken() {
    val parsed = LibraryTabOrder.fromStored("Albums,Garbage,Songs")
    assertEquals(LibraryTab.Albums, parsed[0])
    assertEquals(LibraryTab.Songs, parsed[1])
    assertTrue(parsed.containsAll(LibraryTab.entries))
  }

  @Test
  fun zz_multiValueSeparators_default_is_semicolon_and_slash() = runTest {
    val initial = repo.multiValueSeparators.flow.first()
    assertEquals(MultiValueSeparator.Default, initial)
    assertEquals(setOf(MultiValueSeparator.Semicolon, MultiValueSeparator.Slash), initial)
  }

  @Test
  fun zz_multiValueSeparators_round_trip_persists_user_selection() = runTest {
    val picked = setOf(
      MultiValueSeparator.Comma,
      MultiValueSeparator.Ampersand,
      MultiValueSeparator.Feat,
    )
    repo.setMultiValueSeparators(picked)
    assertEquals(picked, repo.multiValueSeparators.flow.first())
  }

  @Test
  fun zz_multiValueSeparators_empty_set_disables_all_splitting() = runTest {
    repo.setMultiValueSeparators(emptySet())
    assertEquals(emptySet<MultiValueSeparator>(), repo.multiValueSeparators.flow.first())
  }

  // R.B.1 — smoke tests for Setting<T>: prove the abstraction shares
  // storage with the legacy mutators (writes either way are visible
  // from the other side) and survives the round trip.

  // G+ — `colorScheme` and `blackTheme` round-trip tests removed.
  // Both settings were subsumed by `BaseTheme.PureBlack` /
  // `BaseTheme.Custom(seed)` in D.20.4 / D.25.1; the corresponding
  // Setting<T> fields had no consumers anywhere, just kept the
  // legacy DataStore keys around. The round-trip tests were
  // testing dead code.
}
