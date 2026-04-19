package zed.rainxch.core.domain.model

/**
 * Independent proxy "channels" — each scope has its own configurable
 * [ProxyConfig] so users can, for example, route GitHub API traffic
 * through a corporate proxy while keeping APK downloads direct.
 *
 * Every outbound request in the app belongs to exactly one scope:
 *
 * - [DISCOVERY]    — GitHub REST API calls: search, home, details,
 *                    repo metadata, user profiles, starred, installed
 *                    apps update checks. Basically everything that
 *                    hits `api.github.com`.
 * - [DOWNLOAD]     — APK file downloads: manual installs from Details,
 *                    one-tap updates from the Installed Apps list, and
 *                    the Android auto-update worker.
 * - [TRANSLATION]  — README translation requests (currently Google
 *                    Translate). Kept separate because translation
 *                    services are often blocked/unblocked independently
 *                    of GitHub in restricted networks.
 */
enum class ProxyScope {
    DISCOVERY,
    DOWNLOAD,
    TRANSLATION,
}
