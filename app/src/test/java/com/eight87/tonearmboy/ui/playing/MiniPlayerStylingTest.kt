package com.eight87.tonearmboy.ui.playing

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import com.eight87.tonearmboy.playback.PlaybackUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * D.21.1 / D.26.1 — pin the polished mini-player layout against the
 * Auxio-style comparison feedback the user filed:
 *  - dedicated cover thumb (testTag `mini_player_cover`)
 *  - title in `bodyLarge` (we assert via the dedicated `mini_player_title`
 *    testTag and the rendered text)
 *  - "artist · album" subtitle in `bodySmall`
 *  - thin LinearProgressIndicator (testTag `mini_player_progress`).
 *    The draggable Slider was promoted to NowPlaying; the mini-player
 *    keeps only a non-interactive progress line.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniPlayerStylingTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun playing() = PlaybackUiState(
    hasMedia = true,
    title = "Cipher Light",
    artist = "The Synth Foxes",
    album = "Velvet Den",
    isPlaying = true,
    positionMs = 30_000,
    durationMs = 120_000,
    hasNext = true,
    hasPrevious = false,
  )

  @Test
  fun renders_cover_title_subtitle_and_progress_bar() {
    composeRule.setContent {
      MaterialTheme {
        Surface {
          MiniPlayer(
            state = playing(),
            onTogglePlayPause = {},
            onClose = {},
            onExpand = {},
          )
        }
      }
    }

    // Album thumb is its own slot now (vs. the old plain `Box`). The
    // testTags live on inner nodes that merge up into the parent
    // mini_player Row's semantics; `useUnmergedTree = true` lets the
    // finders see them.
    composeRule.onNodeWithTag("mini_player_cover", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithTag("mini_player_title", useUnmergedTree = true).assertExists()
    composeRule.onNodeWithTag("mini_player_subtitle", useUnmergedTree = true).assertExists()

    // Subtitle joins artist + album with the literal " · " separator,
    // matching the NowPlaying screen's contract.
    composeRule.onNodeWithText("The Synth Foxes · Velvet Den").assertIsDisplayed()
    composeRule.onNodeWithText("Cipher Light").assertIsDisplayed()

    // The draggable slider must exist when state has duration.
    composeRule.onNodeWithTag("mini_player_progress", useUnmergedTree = true).assertExists()
  }

  @Test
  fun slider_renders_even_when_duration_zero() {
    // Defensive: a very-short / unknown-duration track should render
    // an empty (0%) slider rather than crashing on a divide-by-zero.
    composeRule.setContent {
      MaterialTheme {
        Surface {
          MiniPlayer(
            state = playing().copy(positionMs = 0, durationMs = 0),
            onTogglePlayPause = {},
            onClose = {},
            onExpand = {},
          )
        }
      }
    }
    composeRule.onNodeWithTag("mini_player_progress", useUnmergedTree = true).assertExists()
  }

  @Test
  fun subtitle_falls_back_when_artist_and_album_blank() {
    composeRule.setContent {
      MaterialTheme {
        Surface {
          MiniPlayer(
            state = playing().copy(artist = "", album = ""),
            onTogglePlayPause = {},
            onClose = {},
            onExpand = {},
          )
        }
      }
    }
    composeRule.onNodeWithText("Unknown artist").assertIsDisplayed()
  }
}
