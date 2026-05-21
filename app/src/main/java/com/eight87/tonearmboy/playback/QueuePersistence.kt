package com.eight87.tonearmboy.playback

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Phase E.5 — persist the current queue + playback position so the
 * service can rebuild state across process death and after the OS
 * reaps the foreground service.
 *
 * Storage: a small DataStore Preferences file. The queue is a single
 * JSON-encoded string; the rest are scalar prefs. Writes are
 * idempotent and cheap — a position write is ~30 bytes through
 * DataStore's atomic file replace, and we debounce position saves
 * from `PlaybackService` so we are not hitting the disk every 250 ms.
 *
 * What we persist per item: media id, source URI, title, artist,
 * album, album artist, artwork URI. That is the round-trip the
 * `onPlaybackResumption` callback hands back to Media3 as a
 * [MediaSession.MediaItemsWithStartPosition].
 *
 * We deliberately do NOT persist position more often than ~2 s —
 * `PlaybackService` is responsible for the debounce; this class is a
 * dumb store.
 */
class QueuePersistence(private val context: Context) {

  /** A single queue item, serialised as JSON inside the prefs string. */
  @Serializable
  data class Entry(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val artworkUri: String? = null,
    /**
     * D.20.4 — MediaStore album id, when the source surface attached
     * one. Persisted so the album-palette tint keeps working after a
     * cold restore. Null is fine: tracks without a MediaStore album
     * (Field Recordings) just don't get the tint.
     */
    val mediaStoreAlbumId: Long? = null,
  )

  /** A snapshot of everything we restore on reconnect. */
  data class Snapshot(
    val items: List<Entry>,
    val startIndex: Int,
    val startPositionMs: Long,
  ) {
    fun isEmpty(): Boolean = items.isEmpty()

    /** Convert to the Media3 type the resumption callback returns. */
    fun toMediaItemsWithStartPosition(): MediaSession.MediaItemsWithStartPosition =
      MediaSession.MediaItemsWithStartPosition(
        items.map { it.toMediaItem() },
        startIndex.coerceAtLeast(0),
        startPositionMs.coerceAtLeast(0),
      )

    companion object {
      val Empty = Snapshot(emptyList(), 0, 0L)
    }
  }

  /** Replace the full queue. Position resets to (index, 0). */
  suspend fun saveQueue(items: List<Entry>, startIndex: Int) {
    val json = JSON.encodeToString(LIST_SERIALIZER, items)
    context.tonearmboyPlaybackDataStore.edit { prefs ->
      prefs[KEY_QUEUE_JSON] = json
      prefs[KEY_INDEX] = startIndex.coerceAtLeast(0)
      prefs[KEY_POSITION] = 0L
    }
  }

  /** Update only the (index, position) tuple — called on tick. */
  suspend fun savePosition(index: Int, positionMs: Long) {
    context.tonearmboyPlaybackDataStore.edit { prefs ->
      prefs[KEY_INDEX] = index.coerceAtLeast(0)
      prefs[KEY_POSITION] = positionMs.coerceAtLeast(0)
    }
  }

  /**
   * Drop the persisted queue entirely.
   *
   * D.26.4 — preserves [KEY_SHUFFLE_ENABLED] and [KEY_REPEAT_MODE] so
   * the user's chosen shuffle / repeat survive a queue wipe (the user
   * expects their toggles to stay set across a stop / clear). Only the
   * queue JSON + position scalars are wiped.
   */
  suspend fun clear() {
    context.tonearmboyPlaybackDataStore.edit { prefs ->
      prefs.remove(KEY_QUEUE_JSON)
      prefs.remove(KEY_INDEX)
      prefs.remove(KEY_POSITION)
    }
  }

  /**
   * D.26.4 — persist shuffle on/off. Lives next to the queue snapshot
   * because it's session-scoped playback state, not a user preference;
   * the user expects "the way I left the player" to come back.
   */
  suspend fun saveShuffle(enabled: Boolean) {
    context.tonearmboyPlaybackDataStore.edit { prefs ->
      prefs[KEY_SHUFFLE_ENABLED] = enabled
    }
  }

  /**
   * D.26.4 — persist repeat mode (`Player.REPEAT_MODE_OFF` / `_ALL` /
   * `_ONE` as 0 / 1 / 2). Stored as Int for direct round-trip with the
   * Media3 constants; out-of-range values fall back to OFF on load.
   */
  suspend fun saveRepeatMode(mode: Int) {
    val sanitized = when (mode) {
      Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> mode
      else -> Player.REPEAT_MODE_OFF
    }
    context.tonearmboyPlaybackDataStore.edit { prefs ->
      prefs[KEY_REPEAT_MODE] = sanitized
    }
  }

  /**
   * D.26.4 — load shuffle on/off. Defaults to `false` when the key is
   * absent (cold first install).
   */
  suspend fun loadShuffle(): Boolean {
    val prefs = context.tonearmboyPlaybackDataStore.data.first()
    return prefs[KEY_SHUFFLE_ENABLED] ?: false
  }

  /**
   * D.26.4 — load repeat mode. Defaults to `REPEAT_MODE_OFF` when the
   * key is absent or carries a non-Media3 value.
   */
  suspend fun loadRepeatMode(): Int {
    val prefs = context.tonearmboyPlaybackDataStore.data.first()
    val raw = prefs[KEY_REPEAT_MODE] ?: return Player.REPEAT_MODE_OFF
    return when (raw) {
      Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL -> raw
      else -> Player.REPEAT_MODE_OFF
    }
  }

  /** One-shot load, used by the resumption callback. */
  suspend fun load(): Snapshot {
    val prefs = context.tonearmboyPlaybackDataStore.data.first()
    val raw = prefs[KEY_QUEUE_JSON] ?: return Snapshot.Empty
    val items = runCatching {
      JSON.decodeFromString(LIST_SERIALIZER, raw)
    }.getOrDefault(emptyList())
    if (items.isEmpty()) return Snapshot.Empty
    return Snapshot(
      items = items,
      startIndex = prefs[KEY_INDEX] ?: 0,
      startPositionMs = prefs[KEY_POSITION] ?: 0L,
    )
  }

  companion object {
    internal val KEY_QUEUE_JSON = stringPreferencesKey("queue_json")
    internal val KEY_INDEX = intPreferencesKey("queue_index")
    internal val KEY_POSITION = longPreferencesKey("queue_position_ms")
    /** D.26.4 — persisted shuffle on/off. */
    internal val KEY_SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
    /** D.26.4 — persisted repeat mode (0/1/2 → REPEAT_MODE_OFF/ONE/ALL). */
    internal val KEY_REPEAT_MODE = intPreferencesKey("repeat_mode")

    internal val JSON = Json {
      ignoreUnknownKeys = true
      encodeDefaults = false
    }
    internal val LIST_SERIALIZER = ListSerializer(Entry.serializer())

    /**
     * D.20.3 — minimum ms between persisted position updates while
     * playing. Originally 2_000; reduced to 500 so that when the user
     * closes the app (or the OS reaps it) the persisted position is
     * within ~0.5 s of where they were rather than potentially missing
     * the last 2 s. Combined with the synchronous flush in
     * `PlaybackService.onDestroy` / `onTaskRemoved`, the worst-case
     * gap is now bounded by the time between the last tick and the
     * shutdown call rather than the debounce window.
     */
    const val POSITION_DEBOUNCE_MS: Long = 500L

    /**
     * Convert a Media3 [MediaItem] into the persisted [Entry]. Only
     * the fields required to rehydrate playback are kept.
     */
    fun fromMediaItem(item: MediaItem): Entry {
      val md = item.mediaMetadata
      val uri = item.localConfiguration?.uri?.toString().orEmpty()
      // D.20.4 — round-trip the MediaStore album id extra so the
      // palette tint can extract a cover bitmap after a cold restore.
      val albumId = md.extras
        ?.getLong("tonearmboy.mediaStoreAlbumId", -1L)
        ?.takeIf { it >= 0 }
      return Entry(
        mediaId = item.mediaId,
        uri = uri,
        title = md.title?.toString(),
        artist = md.artist?.toString(),
        album = md.albumTitle?.toString(),
        albumArtist = md.albumArtist?.toString(),
        artworkUri = md.artworkUri?.toString(),
        mediaStoreAlbumId = albumId,
      )
    }
  }
}

/** Serialisation helper — used by the companion's [Snapshot.toMediaItemsWithStartPosition]. */
internal fun QueuePersistence.Entry.toMediaItem(): MediaItem {
  // D.20.4 — restore the MediaStore album-id extra so the palette
  // tint can find a cover bitmap. The metadata Builder doesn't take
  // a Bundle directly until you call setExtras, so we materialise
  // it lazily.
  val extras = android.os.Bundle().apply {
    mediaId.toLongOrNull()?.let { tid ->
      putLong(PlaybackUiController.EXTRA_TRACK_ID, tid)
    }
    if (mediaStoreAlbumId != null) {
      putLong(PlaybackUiController.EXTRA_MEDIA_STORE_ALBUM_ID, mediaStoreAlbumId)
    }
  }
  val metadata = MediaMetadata.Builder()
    .setTitle(title)
    .setArtist(artist)
    .setAlbumTitle(album)
    .setAlbumArtist(albumArtist)
    .also { if (artworkUri != null) it.setArtworkUri(Uri.parse(artworkUri)) }
    .setExtras(extras)
    .build()
  return MediaItem.Builder()
    .setMediaId(mediaId)
    .setUri(uri)
    .setMediaMetadata(metadata)
    .build()
}

internal val Context.tonearmboyPlaybackDataStore: DataStore<Preferences> by preferencesDataStore(name = "tonearmboy_playback")
