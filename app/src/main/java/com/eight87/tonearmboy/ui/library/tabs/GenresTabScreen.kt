package com.eight87.tonearmboy.ui.library.tabs

import android.content.res.Resources
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import com.eight87.tonearmboy.R
import com.eight87.tonearmboy.data.GenreSource
import com.eight87.tonearmboy.data.model.Genre
import com.eight87.tonearmboy.ui.library.TileItem
import com.eight87.tonearmboy.ui.library.countBucket
import com.eight87.tonearmboy.ui.library.initialKey
import com.eight87.tonearmboy.ui.library.sortGenres
import com.eight87.tonearmboy.ui.settings.AlbumCoversMode
import com.eight87.tonearmboy.ui.settings.SortKey
import com.eight87.tonearmboy.ui.settings.TabSort
import com.eight87.tonearmboy.ui.settings.ViewMode

/**
 * D.28 — Genres tab dispatcher. Sections by initial letter when sort
 * is anything other than Duration; tile mode shows a letter-avatar
 * fallback (no representative cover per genre on this pass).
 */
@Composable
fun GenresTabScreen(
  repository: GenreSource,
  sort: TabSort,
  viewMode: ViewMode,
  // D.30.2 — when non-empty, the underlying genres Flow is filtered.
  filter: com.eight87.tonearmboy.data.FilterCriteria = com.eight87.tonearmboy.data.FilterCriteria(),
  onGenreClick: (Genre) -> Unit = {},
) {
  val genres by remember(filter) {
    if (filter.isEmpty()) repository.observeGenres() else repository.genresMatching(filter)
  }.collectAsState(initial = emptyList())
  val sorted = remember(genres, sort) { sortGenres(genres, sort) }
  val selection = rememberSelectionState<Long>()
  BackHandler(enabled = selection.inSelectionMode) { selection.clear() }
  Column(modifier = Modifier.fillMaxSize().semantics { testTag = "genres_screen" }) {
    if (selection.inSelectionMode) {
      MultiSelectBar(
        count = selection.size,
        onClose = { selection.clear() },
        onAddToPlaylist = null,
        onDelete = null,
      )
    }
    Box(modifier = Modifier.weight(1f)) {
      LibraryTabRenderer(
        spec = GenresTabSpec,
        items = sorted,
        sort = sort,
        viewMode = viewMode,
        intelligentSorting = false,
        albumCoversMode = AlbumCoversMode.Default,
        onItemClick = { genre ->
          if (selection.inSelectionMode) selection.toggle(genre.id) else onGenreClick(genre)
        },
        onItemLongClick = { genre -> selection.add(genre.id) },
        selection = selection,
      )
    }
  }
}

/** Pre-D.28 wrapper retained for callers / tests. */
@Composable
fun GenresListScreen(
  repository: GenreSource,
  sort: TabSort,
  onGenreClick: (Genre) -> Unit = {},
) {
  GenresTabScreen(
    repository = repository,
    sort = sort,
    viewMode = ViewMode.List,
    onGenreClick = onGenreClick,
  )
}

/** R.D.3 — TabSpec for the Genres tab. */
internal object GenresTabSpec : TabSpec<Genre> {
  override val testTag: String = "genres_tab"
  override val emptyMessageRes: Int = com.eight87.tonearmboy.R.string.library_empty_genres
  override val supportsTileMode: Boolean = true

  override fun id(item: Genre): Long = item.id

  override fun sectionKey(item: Genre, sort: TabSort, intelligentSorting: Boolean): String? =
    when (sort.key) {
      // Genres' Duration sort actually sorts by trackCount (see
      // `sortGenres`); bucket the count so headers track the sort.
      SortKey.Duration -> countBucket(item.trackCount)
      else -> initialKey(item.name.uppercase())
    }

  override fun toTile(item: Genre, resources: Resources): TileItem = TileItem(
    id = item.id,
    title = item.name,
    subtitle = resources.getQuantityString(
      R.plurals.library_genre_subtitle_tracks,
      item.trackCount,
      item.trackCount,
    ),
    artUri = null,
    albumArtId = null,
  )

  @Composable
  override fun ListRow(
    item: Genre,
    selected: Boolean,
    inSelectionMode: Boolean,
    onClick: (Genre) -> Unit,
    onLongClick: (Genre) -> Unit,
  ) {
    TwoLineRow(
      primary = item.name,
      secondary = pluralStringResource(R.plurals.library_genre_subtitle_tracks, item.trackCount, item.trackCount),
      onClick = { onClick(item) },
      onLongClick = { onLongClick(item) },
    )
  }
}
