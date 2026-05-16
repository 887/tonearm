package com.eight87.tonearmboy.data.albumart

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.eight87.tonearmboy.data.AlbumCoverChoice
import com.eight87.tonearmboy.data.AlbumSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SquareCropTest {

  private class StubAlbumSource : AlbumSource {
    override fun observeAlbums(): Flow<List<com.eight87.tonearmboy.data.model.Album>> =
      flowOf(emptyList())
    override fun albumsMatching(criteria: com.eight87.tonearmboy.data.FilterCriteria):
      Flow<List<com.eight87.tonearmboy.data.model.Album>> = flowOf(emptyList())
    override fun albumCoverChoice(albumKey: String): Flow<AlbumCoverChoice> =
      flowOf(AlbumCoverChoice.NoChoice)
    override suspend fun setAlbumCoverUri(albumKey: String, uri: String) = Unit
    override suspend fun clearAlbumCoverIntentional(albumKey: String) = Unit
    override suspend fun resetAlbumCover(albumKey: String) = Unit
    override suspend fun firstTrackPathForAlbum(albumKey: String): String? = null
  }

  @Test
  fun `crops a 16x9 bitmap to centre square`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.cacheDir, "crop-test.jpg")
    val src = Bitmap.createBitmap(1600, 900, Bitmap.Config.ARGB_8888)
    file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }

    AlbumArtFetcher(StubAlbumSource()).cropToSquareIfNeeded(file)

    val out = BitmapFactory.decodeFile(file.absolutePath)
    assertEquals(out.width, out.height)
    assertEquals(900, out.width)
  }

  @Test
  fun `leaves square bitmaps untouched`() {
    val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    val file = File(context.cacheDir, "square.jpg")
    val src = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
    file.outputStream().use { src.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    val sizeBefore = file.length()

    AlbumArtFetcher(StubAlbumSource()).cropToSquareIfNeeded(file)

    val out = BitmapFactory.decodeFile(file.absolutePath)
    assertEquals(500, out.width)
    assertEquals(500, out.height)
    // Re-encode would change the byte size (slightly); leaving alone
    // preserves it exactly.
    assertTrue("file should be untouched", file.length() == sizeBefore)
  }
}
