package zed.rainxch.core.data.services.shizuku

import android.content.pm.IPackageInstaller
import android.content.pm.IPackageInstallerSession
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.VersionedPackage
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import rikka.shizuku.SystemServiceHelper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shizuku UserService implementation that runs in a privileged process (shell/root).
 * Provides silent package install/uninstall via the system PackageInstaller API.
 *
 * This class runs in Shizuku's process, NOT in the app's process.
 * It has shell-level (UID 2000) or root-level (UID 0) privileges.
 *
 * MUST have a default no-arg constructor for Shizuku's UserService framework.
 */
class ShizukuInstallerServiceImpl() : IShizukuInstallerService.Stub() {

    companion object {
        private const val INSTALL_TIMEOUT_SECONDS = 120L
        private const val UNINSTALL_TIMEOUT_SECONDS = 60L

        // PackageInstaller status codes
        private const val STATUS_SUCCESS = 0
        private const val STATUS_FAILURE = -1
        private const val STATUS_FAILURE_ABORTED = -2
        private const val STATUS_FAILURE_BLOCKED = -3
        private const val STATUS_FAILURE_CONFLICT = -4
        private const val STATUS_FAILURE_INCOMPATIBLE = -5
        private const val STATUS_FAILURE_INVALID = -6
        private const val STATUS_FAILURE_STORAGE = -7
        private const val STATUS_FAILURE_TIMEOUT = -8
    }

    private fun getPackageInstaller(): IPackageInstaller {
        val binder: IBinder = SystemServiceHelper.getSystemService("package")
        val pm = android.content.pm.IPackageManager.Stub.asInterface(binder)
        return pm.packageInstaller
    }

    override fun installPackage(apkPath: String): Int {
        val file = File(apkPath)
        if (!file.exists()) return STATUS_FAILURE_INVALID

        return try {
            val installer = getPackageInstaller()
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(file.length())

            val installerPackageName = "com.android.shell"
            val sessionId = installer.createSession(params, installerPackageName, null, android.os.Process.myUid())

            val session = IPackageInstallerSession.Stub.asInterface(
                installer.openSession(sessionId)
            )

            // Write APK to session
            val sizeBytes = file.length()
            val pfd = session.openWrite("base.apk", 0, sizeBytes)
            val output = ParcelFileDescriptor.AutoCloseOutputStream(pfd)

            FileInputStream(file).use { input ->
                output.use { out ->
                    input.copyTo(out, bufferSize = 65536)
                    out.flush()
                }
            }

            // Commit session with a status receiver via LocalSocket
            val resultCode = AtomicInteger(STATUS_FAILURE_TIMEOUT)
            val latch = CountDownLatch(1)

            val socketName = "shizuku_install_${SystemClock.elapsedRealtimeNanos()}"
            val serverSocket = LocalServerSocket(socketName)

            // Use a thread to listen for the result
            val listenerThread = Thread {
                try {
                    val client = serverSocket.accept()
                    val input = DataInputStream(client.inputStream)
                    val status = input.readInt()
                    resultCode.set(mapInstallStatus(status))
                    input.close()
                    client.close()
                } catch (e: Exception) {
                    resultCode.set(STATUS_FAILURE)
                } finally {
                    try { serverSocket.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
            listenerThread.isDaemon = true
            listenerThread.start()

            // Create an IntentSender using a LocalSocket-based approach
            val statusReceiver = createStatusReceiver(socketName)
            session.commit(statusReceiver, false)

            // Wait for result
            if (!latch.await(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                resultCode.set(STATUS_FAILURE_TIMEOUT)
                try { serverSocket.close() } catch (_: Exception) {}
            }

            resultCode.get()
        } catch (e: Exception) {
            STATUS_FAILURE
        }
    }

    override fun uninstallPackage(packageName: String): Int {
        return try {
            val installer = getPackageInstaller()

            val resultCode = AtomicInteger(STATUS_FAILURE_TIMEOUT)
            val latch = CountDownLatch(1)

            val socketName = "shizuku_uninstall_${SystemClock.elapsedRealtimeNanos()}"
            val serverSocket = LocalServerSocket(socketName)

            val listenerThread = Thread {
                try {
                    val client = serverSocket.accept()
                    val input = DataInputStream(client.inputStream)
                    val status = input.readInt()
                    resultCode.set(mapInstallStatus(status))
                    input.close()
                    client.close()
                } catch (e: Exception) {
                    resultCode.set(STATUS_FAILURE)
                } finally {
                    try { serverSocket.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
            listenerThread.isDaemon = true
            listenerThread.start()

            val statusReceiver = createStatusReceiver(socketName)
            val versionedPackage = VersionedPackage(packageName, PackageManager.VERSION_CODE_HIGHEST)
            installer.uninstall(
                versionedPackage,
                "com.android.shell",
                0,
                statusReceiver,
                0
            )

            if (!latch.await(UNINSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                resultCode.set(STATUS_FAILURE_TIMEOUT)
                try { serverSocket.close() } catch (_: Exception) {}
            }

            resultCode.get()
        } catch (e: Exception) {
            STATUS_FAILURE
        }
    }

    /**
     * Creates an IntentSender that reports the install/uninstall status
     * back to the given local socket. This is the standard approach for
     * getting synchronous results from PackageInstaller in a Shizuku UserService.
     */
    private fun createStatusReceiver(socketName: String): android.content.IntentSender {
        // Use a Binder-based callback approach since we're in a privileged process.
        // We create a lightweight Intent with an IIntentSender that writes the result
        // to a LocalSocket.
        val binder = object : android.content.IIntentSender.Stub() {
            override fun send(
                code: Int,
                intent: android.content.Intent?,
                resolvedType: String?,
                whitelistToken: IBinder?,
                finishedReceiver: android.content.IIntentReceiver?,
                requiredPermission: String?,
                options: android.os.Bundle?
            ) {
                val status = intent?.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                ) ?: PackageInstaller.STATUS_FAILURE

                try {
                    val socket = LocalSocket()
                    socket.connect(LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT))
                    val output = DataOutputStream(socket.outputStream)
                    output.writeInt(status)
                    output.flush()
                    output.close()
                    socket.close()
                } catch (_: Exception) {
                    // Socket may already be closed
                }
            }
        }

        return android.content.IntentSender(binder)
    }

    private fun mapInstallStatus(status: Int): Int {
        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> STATUS_SUCCESS
            PackageInstaller.STATUS_FAILURE_ABORTED -> STATUS_FAILURE_ABORTED
            PackageInstaller.STATUS_FAILURE_BLOCKED -> STATUS_FAILURE_BLOCKED
            PackageInstaller.STATUS_FAILURE_CONFLICT -> STATUS_FAILURE_CONFLICT
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> STATUS_FAILURE_INCOMPATIBLE
            PackageInstaller.STATUS_FAILURE_INVALID -> STATUS_FAILURE_INVALID
            PackageInstaller.STATUS_FAILURE_STORAGE -> STATUS_FAILURE_STORAGE
            else -> STATUS_FAILURE
        }
    }

    override fun destroy() {
        // Cleanup — called when Shizuku unbinds the service
    }
}
