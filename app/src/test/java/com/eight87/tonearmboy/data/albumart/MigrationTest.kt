package com.eight87.tonearmboy.data.albumart

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.eight87.tonearmboy.ui.settings.SettingsRepository
import com.eight87.tonearmboy.ui.settings.tonearmboyDataStore
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MigrationTest {

  private lateinit var ctx: Context

  @Before
  fun setUp() = runTest {
    ctx = ApplicationProvider.getApplicationContext()
    // Robolectric reuses the same Application across tests in a class
    // run, so wipe the preferences file before each case.
    ctx.tonearmboyDataStore.edit { it.clear() }
  }

  @After
  fun tearDown() = runTest {
    ctx.tonearmboyDataStore.edit { it.clear() }
  }

  @Test
  fun `fresh install seeds canonical all-on default`() = runTest {
    val repo = SettingsRepository(ctx)
    repo.firstLaunchInitialise()
    val configs = repo.coverArtProviders.flow.first()
    assertEquals(ProviderListCodec.DEFAULT, configs)
  }

  @Test
  fun `legacy MusicBrainz seeds MusicBrainz-first all-others-off`() = runTest {
    ctx.tonearmboyDataStore.edit { prefs ->
      prefs[SettingsRepository.KEY_COVER_ART_SERVICE] = "MusicBrainz"
    }
    val repo = SettingsRepository(ctx)
    repo.firstLaunchInitialise()
    val configs = repo.coverArtProviders.flow.first()
    assertEquals(ProviderKind.MusicBrainz, configs.first().kind)
    assertTrue(configs.first().enabled)
    assertTrue("only MusicBrainz on", configs.count { it.enabled } == 1)
  }

  @Test
  fun `legacy iTunes seeds iTunes-first all-others-off`() = runTest {
    ctx.tonearmboyDataStore.edit { prefs ->
      prefs[SettingsRepository.KEY_COVER_ART_SERVICE] = "ITunes"
    }
    val repo = SettingsRepository(ctx)
    repo.firstLaunchInitialise()
    val configs = repo.coverArtProviders.flow.first()
    assertEquals(ProviderKind.ITunes, configs.first().kind)
    assertTrue(configs.first().enabled)
    assertTrue(configs.count { it.enabled } == 1)
  }

  @Test
  fun `legacy Disabled-stored seeds every provider off`() = runTest {
    ctx.tonearmboyDataStore.edit { prefs ->
      prefs[SettingsRepository.KEY_COVER_ART_SERVICE] = "Disabled"
    }
    val repo = SettingsRepository(ctx)
    repo.firstLaunchInitialise()
    val configs = repo.coverArtProviders.flow.first()
    assertTrue("everything off", configs.all { !it.enabled })
  }

  @Test
  fun `migration is idempotent — second run does not clobber user edits`() = runTest {
    val repo = SettingsRepository(ctx)
    repo.firstLaunchInitialise()
    repo.coverArtProviders.set(listOf(
      ProviderConfig(ProviderKind.ITunes, true),
      ProviderConfig(ProviderKind.YouTube, false),
      ProviderConfig(ProviderKind.MusicBrainz, false),
    ))
    repo.firstLaunchInitialise()
    val configs = repo.coverArtProviders.flow.first()
    assertEquals(ProviderKind.ITunes, configs.first().kind)
    assertTrue(configs.first().enabled)
  }
}
