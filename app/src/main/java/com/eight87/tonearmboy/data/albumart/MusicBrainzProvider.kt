package com.eight87.tonearmboy.data.albumart

/**
 * Cover-art Phase A — [CoverArtProvider] adapter around the existing
 * [MusicBrainzClient]. Two-call protocol stays inside the adapter so
 * the chain orchestrator stays single-step-per-provider.
 */
class MusicBrainzProvider(
  private val client: MusicBrainzClient = MusicBrainzClient(),
) : CoverArtProvider {
  override val kind: ProviderKind = ProviderKind.MusicBrainz

  override suspend fun findCoverUrl(req: CoverArtRequest): String? {
    // MB's release search needs both artist + album to score usefully;
    // without album-artist it would search every release in the
    // catalogue.
    val artist = req.albumArtist?.takeIf { it.isNotBlank() } ?: return null
    val mbid = client.findReleaseId(artist, req.albumName, req.musicBrainzMinScore)
      ?: return null
    return client.coverArtUrl(mbid)
  }
}

/**
 * Cover-art Phase A — [CoverArtProvider] adapter around the existing
 * [ITunesClient].
 */
class ITunesProvider(
  private val client: ITunesClient = ITunesClient(),
) : CoverArtProvider {
  override val kind: ProviderKind = ProviderKind.ITunes

  override suspend fun findCoverUrl(req: CoverArtRequest): String? =
    client.findCoverUrl(req.albumArtist, req.albumName)
}
