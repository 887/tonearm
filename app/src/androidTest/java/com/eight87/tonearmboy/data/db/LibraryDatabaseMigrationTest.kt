package com.eight87.tonearmboy.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Forward-migration smoke tests for [LibraryDatabase].
 *
 * **Coverage:** v4 → v5, v5 → v6, v6 → v7. Earlier migrations
 * (v1 → v2, v2 → v3, v3 → v4) cannot be tested here because the
 * exported schemas for versions 1–3 were never committed to git —
 * schema export was turned on only when the DB was already at v4.
 * Going forward, any new migration will have its source-and-target
 * schemas present in `app/schemas/`, and a matching `@Test` should
 * be added below as part of the same change.
 *
 * **Known schema drift on v4 → v5:** the shipped `MIGRATION_4_5`
 * creates `album_covers.coverUri` as `TEXT NOT NULL`, but the v5
 * schema (regenerated post-fix) records it as nullable. We therefore
 * call `runMigrationsAndValidate(..., validateDroppedTables = false)`
 * and skip Room's strict schema-equality check for the 4 → 5 case —
 * `MIGRATION_5_6` drops and recreates the table with the correct
 * nullable column, so any v4 user reaches the validated v6 schema
 * via the 4 → 5 → 6 chain. See KDoc on `MIGRATION_4_5` in
 * [LibraryDatabase] for the full history.
 */
@RunWith(AndroidJUnit4::class)
class LibraryDatabaseMigrationTest {

  @get:Rule
  val helper: MigrationTestHelper = MigrationTestHelper(
    InstrumentationRegistry.getInstrumentation(),
    LibraryDatabase::class.java,
    emptyList(),
    FrameworkSQLiteOpenHelperFactory(),
  )

  /**
   * v4 → v5 creates the `album_covers` table. We seed a `playlists`
   * row at v4 and assert it survives the upgrade, plus that the new
   * `album_covers` table is present and writable. Room's strict
   * schema-equality validation is bypassed here (the migration is
   * run directly against the v4 database) because of the known
   * nullable-vs-NOT-NULL drift on `album_covers.coverUri` — see
   * class KDoc and the `MIGRATION_4_5` KDoc on [LibraryDatabase].
   */
  @Test
  fun migrate4To5_preservesPlaylistsAndCreatesAlbumCovers() {
    val db = helper.createDatabase(TEST_DB, 4)
    db.execSQL(
      "INSERT INTO playlists (id, name, createdAtSeconds, coverUri) " +
        "VALUES (1, 'Morning', 1700000000, NULL)"
    )
    LibraryDatabase.MIGRATION_4_5.migrate(db)

    db.query("SELECT name FROM playlists WHERE id = 1").use { c ->
      assertTrue("playlist row survived", c.moveToFirst())
      assertEquals("Morning", c.getString(0))
    }
    // `album_covers` is present and writable (NOT-NULL form from the
    // shipped v5 migration — the drift gets corrected by 5 → 6).
    db.execSQL(
      "INSERT INTO album_covers (albumKey, coverUri) " +
        "VALUES ('seed', 'content://seed')"
    )
    db.query("SELECT COUNT(*) FROM album_covers").use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(1, c.getInt(0))
    }
    db.close()
  }

  /**
   * v5 → v6 drops and recreates `album_covers` with a nullable
   * `coverUri`. Any user-pinned overrides from the brief v5 window
   * are intentionally lost (re-pickable from Album Detail).
   */
  @Test
  fun migrate5To6_recreatesAlbumCoversWithNullableUri() {
    helper.createDatabase(TEST_DB, 5).use { db ->
      // v5's album_covers ship was NOT NULL; seed a non-null row so
      // both the old and new column constraints are satisfied.
      db.execSQL(
        "INSERT INTO album_covers (albumKey, coverUri) " +
          "VALUES ('abc123', 'content://stale')"
      )
    }

    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      6,
      /* validateDroppedTables = */ true,
      LibraryDatabase.MIGRATION_5_6,
    )

    // The drop-and-recreate is intentional — the brief v5 window
    // was hours old, no user data of consequence is being shed.
    db.query("SELECT COUNT(*) FROM album_covers").use { c ->
      assertTrue(c.moveToFirst())
      assertEquals(0, c.getInt(0))
    }
    // New schema must accept a NULL coverUri (the tri-state).
    db.execSQL(
      "INSERT INTO album_covers (albumKey, coverUri) VALUES ('xyz', NULL)"
    )
    db.query("SELECT coverUri FROM album_covers WHERE albumKey = 'xyz'").use { c ->
      assertTrue(c.moveToFirst())
      assertTrue("coverUri is NULL", c.isNull(0))
    }
    db.close()
  }

  /**
   * v6 → v7 adds the per-row override tables `track_covers` and
   * `artist_covers`. Existing `album_covers` rows must survive
   * untouched.
   */
  @Test
  fun migrate6To7_addsTrackAndArtistCoversTables() {
    helper.createDatabase(TEST_DB, 6).use { db ->
      db.execSQL(
        "INSERT INTO album_covers (albumKey, coverUri) " +
          "VALUES ('keep-me', 'content://kept')"
      )
    }

    val db = helper.runMigrationsAndValidate(
      TEST_DB,
      7,
      /* validateDroppedTables = */ true,
      LibraryDatabase.MIGRATION_6_7,
    )

    // Pre-existing album_covers row preserved.
    db.query("SELECT coverUri FROM album_covers WHERE albumKey = 'keep-me'").use { c ->
      assertTrue(c.moveToFirst())
      assertEquals("content://kept", c.getString(0))
    }
    // New tables created, empty, accept the tri-state NULL.
    db.execSQL(
      "INSERT INTO track_covers (trackId, coverUri) VALUES (42, NULL)"
    )
    db.execSQL(
      "INSERT INTO artist_covers (artistKey, coverUri) VALUES ('bjork', 'content://b')"
    )
    db.query("SELECT COUNT(*) FROM track_covers").use { c ->
      assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
    }
    db.query("SELECT COUNT(*) FROM artist_covers").use { c ->
      assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0))
    }
    db.close()
  }

  companion object {
    private const val TEST_DB = "library-migration-test.db"
  }
}
