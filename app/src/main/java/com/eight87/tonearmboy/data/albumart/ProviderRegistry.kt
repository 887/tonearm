package com.eight87.tonearmboy.data.albumart

/**
 * Cover-art Phase C — single construction point for [CoverArtProvider]
 * instances.
 *
 * The chain-building helper takes the user's persisted
 * `List<ProviderConfig>` plus a shared dependency bag (HTTP clients,
 * Piped pool, MB user-agent) and returns a [ProviderChain] containing
 * only the enabled providers in user priority order.
 *
 * This is the only place that knows the kind → concrete-class
 * mapping; the rest of the codebase reads / writes
 * [ProviderConfig] lists and never instantiates a provider directly.
 */
object ProviderRegistry {

  /**
   * Shared dependencies used to construct provider instances. Default
   * values are the production wiring; tests inject fakes through here
   * without having to mock every concrete client.
   */
  data class Deps(
    val musicBrainz: MusicBrainzClient = MusicBrainzClient(),
    val iTunes: ITunesClient = ITunesClient(),
    val piped: PipedClient = PipedClient(),
  )

  fun buildChain(configs: List<ProviderConfig>, deps: Deps = Deps()): ProviderChain {
    val providers = configs
      .filter { it.enabled }
      .map { config -> build(config.kind, deps) }
    return ProviderChain(providers)
  }

  private fun build(kind: ProviderKind, deps: Deps): CoverArtProvider = when (kind) {
    ProviderKind.YouTube -> YouTubeProvider(piped = deps.piped)
    ProviderKind.MusicBrainz -> MusicBrainzProvider(deps.musicBrainz)
    ProviderKind.ITunes -> ITunesProvider(deps.iTunes)
  }
}
