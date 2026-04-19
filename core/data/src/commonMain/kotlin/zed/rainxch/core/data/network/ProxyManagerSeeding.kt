package zed.rainxch.core.data.network

/**
 * Marker type that represents "the [ProxyManager] has been seeded
 * with the user's persisted per-scope proxy configs." Any component
 * that constructs an HTTP client whose proxy is read from
 * [ProxyManager] at creation time must inject this so Koin resolves
 * the seeding step first — it forces the DI graph dependency to be
 * explicit instead of depending on registration order.
 */
class ProxyManagerSeeding internal constructor()
