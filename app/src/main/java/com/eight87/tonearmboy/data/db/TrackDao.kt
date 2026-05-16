package com.eight87.tonearmboy.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

  @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
  fun observeAll(): Flow<List<TrackEntity>>

  @Query("SELECT * FROM tracks WHERE id = :id")
  suspend fun getById(id: Long): TrackEntity?

  @Query("SELECT id FROM tracks")
  suspend fun allIds(): List<Long>

  /**
   * D.9b.1 — number of tracks for the given (album, albumArtist) key.
   * `IFNULL(albumArtist, artist) IS :artist` matches the same join
   * predicate used by [AlbumDao.observeAlbumsWithCounts] so the count
   * stays consistent.
   */
  @Query(
    """
    SELECT COUNT(*) FROM tracks
    WHERE album = :album AND IFNULL(albumArtist, artist) IS :artist
    """
  )
  suspend fun countForAlbum(album: String, artist: String?): Int

  @Query("SELECT * FROM tracks WHERE id IN (:ids)")
  suspend fun getByIds(ids: List<Long>): List<TrackEntity>

  /**
   * Cover-art Phase C.5 — first track on disk for the given
   * (album, albumArtist) key. Used by [AlbumArtFetcher] to feed the
   * YouTube provider a representative filename for the embedded-ID
   * fast path. `ORDER BY trackNumber ASC, id ASC LIMIT 1` makes the
   * choice deterministic across rescans.
   */
  @Query(
    """
    SELECT * FROM tracks
    WHERE album = :album AND IFNULL(albumArtist, artist) IS :artist
    ORDER BY IFNULL(trackNumber, 9999) ASC, id ASC
    LIMIT 1
    """
  )
  suspend fun firstByAlbum(album: String, artist: String?): TrackEntity?

  @Upsert
  suspend fun upsert(tracks: List<TrackEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAll(tracks: List<TrackEntity>)

  @Query("DELETE FROM tracks WHERE id IN (:ids)")
  suspend fun deleteByIds(ids: List<Long>)

  @Query("DELETE FROM tracks")
  suspend fun deleteAll()

  /**
   * FTS4 MATCH search. Falls back to LIKE on the same columns when the
   * MATCH expression rejects the input (e.g. a single dangling quote);
   * see [LibraryDao.searchTracks] for the wrapped form.
   */
  @Query(
    """
    SELECT t.* FROM tracks t
    JOIN tracks_fts fts ON fts.rowid = t.id
    WHERE tracks_fts MATCH :match
    ORDER BY t.title COLLATE NOCASE ASC
    """
  )
  fun searchFts(match: String): Flow<List<TrackEntity>>

  @Query(
    """
    SELECT * FROM tracks
    WHERE title LIKE :pattern COLLATE NOCASE
       OR artist LIKE :pattern COLLATE NOCASE
       OR album LIKE :pattern COLLATE NOCASE
    ORDER BY title COLLATE NOCASE ASC
    """
  )
  fun searchLike(pattern: String): Flow<List<TrackEntity>>
}
