package zed.rainxch.core.data.local.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import zed.rainxch.core.domain.model.InstallSource

@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    @PrimaryKey val packageName: String,
    val repoId: Long,
    val repoName: String,
    val repoOwner: String,
    val repoOwnerAvatarUrl: String,
    val repoDescription: String?,
    val primaryLanguage: String?,
    val repoUrl: String,
    val installedVersion: String,
    val installedAssetName: String?,
    val installedAssetUrl: String?,
    val latestVersion: String?,
    val latestAssetName: String?,
    val latestAssetUrl: String?,
    val latestAssetSize: Long?,
    val appName: String,
    val installSource: InstallSource,
    val signingFingerprint: String?,
    val installedAt: Long,
    val lastCheckedAt: Long,
    val lastUpdatedAt: Long,
    val isUpdateAvailable: Boolean,
    val updateCheckEnabled: Boolean = true,
    val releaseNotes: String? = "",
    val systemArchitecture: String,
    val fileExtension: String,
    val isPendingInstall: Boolean = false,
    val installedVersionName: String? = null,
    val installedVersionCode: Long = 0L,
    val latestVersionName: String? = null,
    val latestVersionCode: Long? = null,
    val latestReleasePublishedAt: String? = null,
    val includePreReleases: Boolean = false,
    /**
     * Per-app regex applied to asset (file) names. When non-null, only assets
     * whose name matches the pattern are considered installable for this app.
     * Used to track a single app inside a monorepo that ships multiple apps
     * (e.g. `ente-auth.*` against `ente-io/ente`).
     */
    val assetFilterRegex: String? = null,
    /**
     * When true, the update checker walks backward through past releases until
     * it finds one whose assets match [assetFilterRegex]. Required for
     * monorepos where the latest release is for a *different* app.
     *
     * `@ColumnInfo(defaultValue = "0")` matches `MIGRATION_9_10`'s
     * `DEFAULT 0` clause so Room's schema validator doesn't flag the
     * column on freshly-built databases.
     */
    @ColumnInfo(defaultValue = "0")
    val fallbackToOlderReleases: Boolean = false,
    /**
     * Stable identifier for the asset variant the user wants to track —
     * for example `arm64-v8a`, `universal`, or `x86_64`. Derived from the
     * picked asset filename by stripping the version segment, so it
     * survives release-to-release version bumps.
     *
     * `null` means "use the platform installer's automatic picker"
     * (today's behaviour). When non-null, [checkForUpdates] resolves the
     * matching asset on every check; if no asset in the new release
     * matches the variant, [preferredVariantStale] is flipped to true.
     */
    val preferredAssetVariant: String? = null,
    /**
     * Set to true by the update checker when the persisted
     * [preferredAssetVariant] cannot be found in the latest release's
     * assets — typically because the maintainer renamed or restructured
     * the artefacts. The UI surfaces this with a "variant changed —
     * pick again" prompt and clears it once the user picks a new variant.
     *
     * `@ColumnInfo(defaultValue = "0")` matches `MIGRATION_10_11`'s
     * `DEFAULT 0` clause so Room's schema validator doesn't flag a
     * mismatch between the migrated table and the freshly-created one.
     */
    @ColumnInfo(defaultValue = "0")
    val preferredVariantStale: Boolean = false,
)
