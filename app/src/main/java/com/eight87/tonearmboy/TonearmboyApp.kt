package com.eight87.tonearmboy

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import kotlinx.coroutines.Dispatchers

/**
 * Process-scoped Application. Installs a bounded-parallelism Coil
 * `ImageLoader` so cover-art loading can't saturate the IO pool or
 * stack arbitrarily many concurrent bitmap decodes — both of which
 * starved the main thread under fast LazyColumn scrolling.
 *
 * Concurrency budget — picked to leave headroom for everything else
 * that uses `Dispatchers.IO` / `Dispatchers.Default` (Room flows,
 * MediaStore queries, ReplayGain decoders, palette extraction):
 *  - fetcher: 4 (mostly ContentResolver reads, occasionally network)
 *  - decoder: 2 (bitmap decode is CPU + allocation heavy)
 *
 * `limitedParallelism(N)` returns a view over the parent dispatcher
 * that admits at most N coroutines at a time. The parent pool still
 * does the work-stealing; this view just caps how many of its threads
 * Coil can monopolise. When more requests arrive than the budget
 * allows, the excess suspend — and Coil cancels the suspension as
 * soon as the bound `AsyncImage` leaves composition, so off-screen
 * cells never block on-screen cells.
 */
class TonearmboyApp : Application(), SingletonImageLoader.Factory {

  override fun newImageLoader(context: PlatformContext): ImageLoader {
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val fetcherCtx = Dispatchers.IO.limitedParallelism(4)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val decoderCtx = Dispatchers.Default.limitedParallelism(2)
    return ImageLoader.Builder(context)
      .fetcherCoroutineContext(fetcherCtx)
      .decoderCoroutineContext(decoderCtx)
      .memoryCache {
        MemoryCache.Builder()
          .maxSizePercent(context, 0.25)
          .build()
      }
      .diskCache {
        DiskCache.Builder()
          .directory(cacheDir.resolve("coil3_image_cache"))
          .maxSizeBytes(100L * 1024 * 1024)
          .build()
      }
      .crossfade(true)
      .build()
  }
}
