package zed.rainxch.core.domain.util

import zed.rainxch.core.domain.model.GithubAsset

/**
 * Stable identifier extracted from a GitHub release asset filename — the
 * tail that remains constant across releases (architecture, packaging
 * flavour, etc.). The "preferred variant" feature uses these to remember
 * which APK out of a multi-asset release the user wants installed, even
 * as version numbers in the filename change from one release to the next.
 *
 * Examples:
 *   `ente-auth-3.2.5-arm64-v8a.apk`     → `arm64-v8a`
 *   `myapp-v1.2.3-universal.apk`        → `universal`
 *   `App_2.0.0_x86_64.apk`              → `x86_64`
 *   `bestapp-1.0.0.apk`                 → `""`            (empty: no variant in name)
 *   `no-version-here.apk`               → `null`          (no version anchor at all)
 *
 * Empty string and `null` are different: empty means "we found a version
 * but nothing came after it" (so the asset has no variant) — apps with a
 * single-asset release land here. `null` means "the filename has no
 * version-looking segment we can anchor on" — likely a non-standard name
 * we shouldn't try to match against.
 */
object AssetVariant {
    /**
     * Matches the FIRST version-looking segment in a filename. We require:
     *
     *   - A leading separator (`-`, `_`, or space) so we don't false-match
     *     on names like `app2-installer` where `2` is part of the app name
     *   - An optional `v`/`V` prefix (e.g. `-v1.2.3`)
     *   - At least **two** dotted digit groups (`\d+(?:\.\d+)+`) so we don't
     *     swallow architecture tokens like `_64`, `-v8`, or `-v7a` that have
     *     no dots and are common in APK filenames
     *   - A trailing token boundary (a separator or end-of-string) so we
     *     don't accept partial matches like `1.2.3pre` (which would otherwise
     *     leak `pre` into the variant tail)
     *
     * Examples that **do** match (and what gets captured):
     *
     *   `app-1.2.3`           → `-1.2.3`
     *   `myapp-v2.0.1-arm64`  → `-v2.0.1`
     *   `App_3.4.5_universal` → `_3.4.5`
     *
     * Examples that **don't** match (and why):
     *
     *   `arm64-v8a-app-1.2.3` →  `-v8` is rejected (no dot); `-1.2.3` matches
     *                            instead, leaving an empty variant tail —
     *                            preferable to extracting `a` as the variant
     *   `app_64bit_v1.2.3`    →  `_64` is rejected (no dot); `_v1.2.3` matches
     *   `app-1`               →  No match — single-digit versions are too
     *                            ambiguous; the auto-picker handles them
     *   `app-1.2.3pre`        →  No match — the trailing `pre` (no separator)
     *                            isn't a clean token boundary
     */
    private val VERSION_SEGMENT =
        Regex("[-_ ]v?\\d+(?:\\.\\d+)+(?=[-_. ]|$)", RegexOption.IGNORE_CASE)

    private val LEADING_SEPARATORS = charArrayOf('-', '_', ' ', '.')

    fun extract(assetName: String): String? {
        val withoutExt = assetName.substringBeforeLast('.')
        val match = VERSION_SEGMENT.find(withoutExt) ?: return null
        // Take everything after the matched version segment, drop any
        // leading separators so the result is the bare variant tag.
        val tail =
            withoutExt
                .substring(match.range.last + 1)
                .trimStart(*LEADING_SEPARATORS)
                .trim()
        return tail
    }

    /**
     * Returns the asset whose extracted variant equals [preferredVariant],
     * or `null` if either no preference is set, the preference is blank,
     * or no asset matches. Comparison is case-insensitive because some
     * maintainers flip casing release-over-release (`Arm64-V8a` vs
     * `arm64-v8a`).
     */
    fun resolvePreferredAsset(
        assets: List<GithubAsset>,
        preferredVariant: String?,
    ): GithubAsset? {
        val target = preferredVariant?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return assets.firstOrNull { asset ->
            extract(asset.name)?.equals(target, ignoreCase = true) == true
        }
    }

    /**
     * Pulls the variant tag out of a sample asset filename and returns
     * it normalised, or `null` when the name doesn't carry a meaningful
     * variant. Skips the work entirely when [siblingAssetCount] is 1 or 0
     * because single-asset releases have nothing to remember.
     *
     * Single-asset releases and "no variant suffix" filenames both return
     * `null` rather than the empty string — there's nothing to pin.
     */
    fun deriveFromPickedAsset(
        pickedAssetName: String,
        siblingAssetCount: Int,
    ): String? {
        if (siblingAssetCount <= 1) return null
        val variant = extract(pickedAssetName) ?: return null
        return variant.takeIf { it.isNotEmpty() }
    }
}
