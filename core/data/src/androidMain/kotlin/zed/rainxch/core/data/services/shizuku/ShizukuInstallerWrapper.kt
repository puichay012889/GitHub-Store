package zed.rainxch.core.data.services.shizuku

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import zed.rainxch.core.domain.model.GithubAsset
import zed.rainxch.core.domain.model.InstallerType
import zed.rainxch.core.domain.model.SystemArchitecture
import zed.rainxch.core.domain.repository.ThemesRepository
import zed.rainxch.core.domain.system.Installer
import zed.rainxch.core.domain.system.InstallerInfoExtractor

/**
 * Wrapper around [Installer] that transparently intercepts `install()` and `uninstall()`
 * calls to use Shizuku when available and enabled by the user.
 *
 * All other methods (asset selection, architecture detection, Obtainium/AppManager support)
 * delegate directly to the underlying [androidInstaller] unchanged.
 *
 * Fallback behavior: if Shizuku install/uninstall fails for any reason, falls back
 * to the standard [androidInstaller] implementation silently.
 */
class ShizukuInstallerWrapper(
    private val androidInstaller: Installer,
    private val shizukuServiceManager: ShizukuServiceManager,
    private val themesRepository: ThemesRepository
) : Installer {

    companion object {
        private const val TAG = "ShizukuInstaller"
    }

    /**
     * Cached installer type preference, updated by flow collection.
     * Defaults to DEFAULT so Shizuku is never used unless explicitly opted in.
     */
    @Volatile
    private var cachedInstallerType: InstallerType = InstallerType.DEFAULT

    /**
     * Start observing the installer type preference from DataStore.
     * Call once after construction (from DI setup).
     */
    fun observeInstallerPreference(scope: CoroutineScope) {
        scope.launch {
            themesRepository.getInstallerType().collect { type ->
                cachedInstallerType = type
                Logger.d(TAG) { "Installer type changed to: $type" }
            }
        }
    }

    // ==================== Delegated methods (always go to AndroidInstaller) ====================

    override suspend fun isSupported(extOrMime: String): Boolean =
        androidInstaller.isSupported(extOrMime)

    override fun isAssetInstallable(assetName: String): Boolean =
        androidInstaller.isAssetInstallable(assetName)

    override fun choosePrimaryAsset(assets: List<GithubAsset>): GithubAsset? =
        androidInstaller.choosePrimaryAsset(assets)

    override fun detectSystemArchitecture(): SystemArchitecture =
        androidInstaller.detectSystemArchitecture()

    override fun isObtainiumInstalled(): Boolean =
        androidInstaller.isObtainiumInstalled()

    override fun openInObtainium(
        repoOwner: String,
        repoName: String,
        onOpenInstaller: () -> Unit
    ) = androidInstaller.openInObtainium(repoOwner, repoName, onOpenInstaller)

    override fun isAppManagerInstalled(): Boolean =
        androidInstaller.isAppManagerInstalled()

    override fun openInAppManager(
        filePath: String,
        onOpenInstaller: () -> Unit
    ) = androidInstaller.openInAppManager(filePath, onOpenInstaller)

    override fun getApkInfoExtractor(): InstallerInfoExtractor =
        androidInstaller.getApkInfoExtractor()

    override fun openApp(packageName: String): Boolean =
        androidInstaller.openApp(packageName)

    override fun openWithExternalInstaller(filePath: String) =
        androidInstaller.openWithExternalInstaller(filePath)

    // ==================== Overridden methods (may use Shizuku) ====================

    override suspend fun ensurePermissionsOrThrow(extOrMime: String) {
        if (shouldUseShizuku()) {
            // Shizuku installs don't need the "install from unknown sources" permission
            Logger.d(TAG) { "Shizuku active — skipping unknown sources permission check" }
            return
        }
        androidInstaller.ensurePermissionsOrThrow(extOrMime)
    }

    override suspend fun install(filePath: String, extOrMime: String) {
        if (shouldUseShizuku()) {
            try {
                val service = shizukuServiceManager.getService()
                if (service != null) {
                    Logger.d(TAG) { "Installing via Shizuku: $filePath" }
                    val result = service.installPackage(filePath)
                    if (result == 0) {
                        Logger.d(TAG) { "Shizuku install succeeded: $filePath" }
                        return
                    }
                    Logger.w(TAG) { "Shizuku install returned error code: $result" }
                    throw IllegalStateException("Shizuku install failed (code: $result)")
                } else {
                    Logger.w(TAG) { "Shizuku service unavailable, falling back to standard installer" }
                }
            } catch (e: IllegalStateException) {
                // Re-throw Shizuku errors so the caller knows it failed
                throw e
            } catch (e: Exception) {
                Logger.w(TAG) { "Shizuku install error, falling back: ${e.message}" }
            }
        }
        // Fallback to standard installer
        androidInstaller.install(filePath, extOrMime)
    }

    override fun uninstall(packageName: String) {
        if (isShizukuEnabled() && shizukuServiceManager.status.value == ShizukuStatus.READY) {
            try {
                val service = shizukuServiceManager.installerService
                if (service != null) {
                    Logger.d(TAG) { "Uninstalling via Shizuku: $packageName" }
                    val result = service.uninstallPackage(packageName)
                    if (result == 0) {
                        Logger.d(TAG) { "Shizuku uninstall succeeded: $packageName" }
                        return
                    }
                    Logger.w(TAG) { "Shizuku uninstall returned error code: $result, falling back" }
                }
            } catch (e: Exception) {
                Logger.w(TAG) { "Shizuku uninstall error, falling back: ${e.message}" }
            }
        }
        // Fallback to standard uninstall dialog
        androidInstaller.uninstall(packageName)
    }

    // ==================== Internal helpers ====================

    private suspend fun shouldUseShizuku(): Boolean {
        return isShizukuEnabled() && shizukuServiceManager.status.value == ShizukuStatus.READY
    }

    private fun isShizukuEnabled(): Boolean {
        return cachedInstallerType == InstallerType.SHIZUKU
    }
}
