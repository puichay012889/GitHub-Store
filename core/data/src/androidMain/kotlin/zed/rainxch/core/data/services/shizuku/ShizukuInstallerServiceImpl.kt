package zed.rainxch.core.data.services.shizuku

import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import rikka.shizuku.SystemServiceHelper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
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
 * Uses reflection to access hidden Android framework APIs, since their method
 * signatures vary across Android versions and hidden-api-stub versions.
 *
 * MUST have a default no-arg constructor for Shizuku's UserService framework.
 */
class ShizukuInstallerServiceImpl() : IShizukuInstallerService.Stub() {

    companion object {
        private const val TAG = "ShizukuService"
        private const val INSTALL_TIMEOUT_SECONDS = 120L
        private const val UNINSTALL_TIMEOUT_SECONDS = 60L

        private const val STATUS_SUCCESS = 0
        private const val STATUS_FAILURE = -1
        private const val STATUS_FAILURE_ABORTED = -2
        private const val STATUS_FAILURE_BLOCKED = -3
        private const val STATUS_FAILURE_CONFLICT = -4
        private const val STATUS_FAILURE_INCOMPATIBLE = -5
        private const val STATUS_FAILURE_INVALID = -6
        private const val STATUS_FAILURE_STORAGE = -7
        private const val STATUS_FAILURE_TIMEOUT = -8

        /** Log helper — runs in Shizuku process so use android.util.Log directly */
        private fun log(msg: String) = android.util.Log.d(TAG, msg)
        private fun logW(msg: String) = android.util.Log.w(TAG, msg)
        private fun logE(msg: String, e: Throwable? = null) = android.util.Log.e(TAG, msg, e)
    }

    /**
     * Obtains the system IPackageInstaller binder via IPackageManager (reflection).
     */
    private fun getPackageInstallerBinder(): Any {
        log("getPackageInstallerBinder() — getting 'package' system service...")
        val binder: IBinder = SystemServiceHelper.getSystemService("package")
        log("Got package service binder: ${binder.javaClass.name}")

        // IPackageManager.Stub.asInterface(binder)
        val ipmClass = Class.forName("android.content.pm.IPackageManager\$Stub")
        val asInterface = ipmClass.getMethod("asInterface", IBinder::class.java)
        val pm = asInterface.invoke(null, binder)
        log("Got IPackageManager: ${pm?.javaClass?.name}")

        // pm.getPackageInstaller()
        val getInstaller = pm!!.javaClass.getMethod("getPackageInstaller")
        val installer = getInstaller.invoke(pm)!!
        log("Got IPackageInstaller: ${installer.javaClass.name}")
        return installer
    }

    override fun installPackage(pfd: ParcelFileDescriptor, fileSize: Long): Int {
        log("installPackage() called with fd, fileSize=$fileSize")
        log("Process UID: ${android.os.Process.myUid()}, PID: ${android.os.Process.myPid()}")

        return try {
            log("Getting PackageInstaller binder...")
            val installer = getPackageInstallerBinder()
            val installerClass = installer.javaClass
            log("Got PackageInstaller: ${installerClass.name}")

            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setSize(fileSize)

            // createSession — try various signatures across Android versions
            log("Creating install session...")
            val sessionId = createSession(installer, installerClass, params)
            log("Session created with ID: $sessionId")

            // openSession returns an IBinder for the session
            log("Opening session $sessionId...")
            val openSessionMethod = installerClass.getMethod("openSession", Int::class.javaPrimitiveType)
            val sessionBinder = openSessionMethod.invoke(installer, sessionId)

            // IPackageInstallerSession.Stub.asInterface(binder)
            val sessionStubClass = Class.forName("android.content.pm.IPackageInstallerSession\$Stub")
            val sessionAsInterface = sessionStubClass.getMethod("asInterface", IBinder::class.java)
            val session = sessionAsInterface.invoke(null, sessionBinder as IBinder)
            val sessionClass = session.javaClass
            log("Session opened, class: ${sessionClass.name}")

            // Write APK from the ParcelFileDescriptor to the session
            log("Writing APK to session from file descriptor...")
            val openWrite = sessionClass.getMethod(
                "openWrite",
                String::class.java,
                Long::class.javaPrimitiveType,
                Long::class.javaPrimitiveType
            )
            val sessionPfd = openWrite.invoke(session, "base.apk", 0L, fileSize) as ParcelFileDescriptor
            val output = ParcelFileDescriptor.AutoCloseOutputStream(sessionPfd)

            var bytesWritten = 0L
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                output.use { out ->
                    val buffer = ByteArray(65536)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        out.write(buffer, 0, read)
                        bytesWritten += read
                    }
                    out.flush()
                }
            }
            log("APK written to session: $bytesWritten bytes (expected: $fileSize)")

            // Set up LocalSocket for synchronous result callback
            val resultCode = AtomicInteger(STATUS_FAILURE_TIMEOUT)
            val latch = CountDownLatch(1)
            val socketName = "shizuku_install_${SystemClock.elapsedRealtimeNanos()}"
            val serverSocket = LocalServerSocket(socketName)
            log("LocalServerSocket created: $socketName")

            val listenerThread = Thread {
                try {
                    log("Listener thread waiting for install result...")
                    val client = serverSocket.accept()
                    val dis = DataInputStream(client.inputStream)
                    val rawStatus = dis.readInt()
                    val mappedStatus = mapInstallStatus(rawStatus)
                    log("Install result received — raw: $rawStatus (${statusToString(rawStatus)}), mapped: $mappedStatus")
                    resultCode.set(mappedStatus)
                    dis.close()
                    client.close()
                } catch (e: Exception) {
                    logE("Listener thread error", e)
                    resultCode.set(STATUS_FAILURE)
                } finally {
                    try { serverSocket.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
            listenerThread.isDaemon = true
            listenerThread.start()

            val statusReceiver = createStatusReceiver(socketName)
            log("Status receiver created, committing session...")

            // session.commit(intentSender, false)
            val commitMethod = sessionClass.getMethod(
                "commit",
                IntentSender::class.java,
                Boolean::class.javaPrimitiveType
            )
            commitMethod.invoke(session, statusReceiver, false)
            log("Session committed, waiting for result (timeout: ${INSTALL_TIMEOUT_SECONDS}s)...")

            if (!latch.await(INSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logW("Install timed out after ${INSTALL_TIMEOUT_SECONDS}s!")
                resultCode.set(STATUS_FAILURE_TIMEOUT)
                try { serverSocket.close() } catch (_: Exception) {}
            }

            val finalResult = resultCode.get()
            log("installPackage() final result: $finalResult (${if (finalResult == 0) "SUCCESS" else "FAILURE"})")
            finalResult
        } catch (e: Exception) {
            logE("installPackage() exception", e)
            STATUS_FAILURE
        }
    }

    override fun uninstallPackage(packageName: String): Int {
        log("uninstallPackage() called for: $packageName")
        return try {
            val installer = getPackageInstallerBinder()
            val installerClass = installer.javaClass

            val resultCode = AtomicInteger(STATUS_FAILURE_TIMEOUT)
            val latch = CountDownLatch(1)
            val socketName = "shizuku_uninstall_${SystemClock.elapsedRealtimeNanos()}"
            val serverSocket = LocalServerSocket(socketName)

            val listenerThread = Thread {
                try {
                    log("Uninstall listener waiting for result...")
                    val client = serverSocket.accept()
                    val dis = DataInputStream(client.inputStream)
                    val rawStatus = dis.readInt()
                    val mappedStatus = mapInstallStatus(rawStatus)
                    log("Uninstall result — raw: $rawStatus (${statusToString(rawStatus)}), mapped: $mappedStatus")
                    resultCode.set(mappedStatus)
                    dis.close()
                    client.close()
                } catch (e: Exception) {
                    logE("Uninstall listener error", e)
                    resultCode.set(STATUS_FAILURE)
                } finally {
                    try { serverSocket.close() } catch (_: Exception) {}
                    latch.countDown()
                }
            }
            listenerThread.isDaemon = true
            listenerThread.start()

            val statusReceiver = createStatusReceiver(socketName)
            performUninstall(installer, installerClass, packageName, statusReceiver)
            log("Uninstall requested, waiting for result (timeout: ${UNINSTALL_TIMEOUT_SECONDS}s)...")

            if (!latch.await(UNINSTALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                logW("Uninstall timed out after ${UNINSTALL_TIMEOUT_SECONDS}s!")
                resultCode.set(STATUS_FAILURE_TIMEOUT)
                try { serverSocket.close() } catch (_: Exception) {}
            }

            val finalResult = resultCode.get()
            log("uninstallPackage() final result: $finalResult (${if (finalResult == 0) "SUCCESS" else "FAILURE"})")
            finalResult
        } catch (e: Exception) {
            logE("uninstallPackage() exception", e)
            STATUS_FAILURE
        }
    }

    /**
     * Calls IPackageInstaller.createSession with the correct signature for the
     * current Android version. Tries multiple overloads.
     */
    private fun createSession(
        installer: Any,
        installerClass: Class<*>,
        params: PackageInstaller.SessionParams
    ): Int {
        val callerPackage = "com.android.shell"
        val uid = android.os.Process.myUid()
        log("createSession() — callerPackage=$callerPackage, uid=$uid")

        // API 33+: createSession(SessionParams, String, String, int)
        try {
            val method = installerClass.getMethod(
                "createSession",
                PackageInstaller.SessionParams::class.java,
                String::class.java,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            val sessionId = method.invoke(installer, params, callerPackage, null, uid) as Int
            log("createSession() succeeded with API 33+ signature, sessionId=$sessionId")
            return sessionId
        } catch (_: NoSuchMethodException) {
            log("createSession() API 33+ signature not found, trying API 26-32...")
        }

        // API 26-32: createSession(SessionParams, String, String)
        try {
            val method = installerClass.getMethod(
                "createSession",
                PackageInstaller.SessionParams::class.java,
                String::class.java,
                String::class.java
            )
            val sessionId = method.invoke(installer, params, callerPackage, null) as Int
            log("createSession() succeeded with API 26-32 signature, sessionId=$sessionId")
            return sessionId
        } catch (_: NoSuchMethodException) {
            logE("createSession() API 26-32 signature also not found!")
        }

        throw IllegalStateException("Could not find createSession method")
    }

    /**
     * Calls IPackageInstaller.uninstall with the correct signature for the
     * current Android version. Tries multiple overloads.
     */
    private fun performUninstall(
        installer: Any,
        installerClass: Class<*>,
        packageName: String,
        statusReceiver: IntentSender
    ) {
        log("performUninstall() — packageName=$packageName")
        val versionedPackageClass = Class.forName("android.content.pm.VersionedPackage")
        val versionedPackage = versionedPackageClass
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance(packageName, -1) // VERSION_CODE_HIGHEST = -1

        val callerPackage = "com.android.shell"

        // API 33+: uninstall(VersionedPackage, String, int, IntentSender, int)
        try {
            val method = installerClass.getMethod(
                "uninstall",
                versionedPackageClass,
                String::class.java,
                Int::class.javaPrimitiveType,
                IntentSender::class.java,
                Int::class.javaPrimitiveType
            )
            method.invoke(installer, versionedPackage, callerPackage, 0, statusReceiver, 0)
            log("performUninstall() succeeded with API 33+ signature")
            return
        } catch (_: NoSuchMethodException) {
            log("performUninstall() API 33+ signature not found, trying API 26-32...")
        }

        // API 26-32: uninstall(VersionedPackage, String, int, IntentSender)
        try {
            val method = installerClass.getMethod(
                "uninstall",
                versionedPackageClass,
                String::class.java,
                Int::class.javaPrimitiveType,
                IntentSender::class.java
            )
            method.invoke(installer, versionedPackage, callerPackage, 0, statusReceiver)
            log("performUninstall() succeeded with API 26-32 signature")
            return
        } catch (_: NoSuchMethodException) {
            logE("performUninstall() API 26-32 signature also not found!")
        }

        throw IllegalStateException("Could not find uninstall method")
    }

    /**
     * Creates an IntentSender that reports install/uninstall status back
     * through a LocalSocket. Uses reflection to construct IntentSender
     * from an IIntentSender proxy, since these are hidden APIs.
     */
    private fun createStatusReceiver(socketName: String): IntentSender {
        log("createStatusReceiver() — socketName=$socketName")
        val iIntentSenderClass = Class.forName("android.content.IIntentSender")

        // Create a dynamic proxy for IIntentSender that writes result to LocalSocket
        val proxy = Proxy.newProxyInstance(
            iIntentSenderClass.classLoader,
            arrayOf(iIntentSenderClass)
        ) { _, method, args ->
            when (method.name) {
                "send" -> {
                    log("IIntentSender.send() called! args count: ${args?.size ?: 0}")
                    // Extract the Intent argument (second param in all known signatures)
                    val intent = args?.filterIsInstance<Intent>()?.firstOrNull()
                    val status = intent?.getIntExtra(
                        PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE
                    ) ?: PackageInstaller.STATUS_FAILURE
                    val statusMessage = intent?.getStringExtra(
                        PackageInstaller.EXTRA_STATUS_MESSAGE
                    )
                    log("IIntentSender.send() — status=$status (${statusToString(status)}), message=$statusMessage")

                    // Log all extras for debugging
                    intent?.extras?.let { extras ->
                        for (key in extras.keySet()) {
                            log("  Intent extra: $key = ${extras.get(key)}")
                        }
                    }

                    try {
                        val socket = LocalSocket()
                        socket.connect(
                            LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT)
                        )
                        val dos = DataOutputStream(socket.outputStream)
                        dos.writeInt(status)
                        dos.flush()
                        dos.close()
                        socket.close()
                        log("Status written to LocalSocket successfully")
                    } catch (e: Exception) {
                        logE("Failed to write status to LocalSocket", e)
                    }

                    // Return 0 if method returns int, null otherwise
                    if (method.returnType == Int::class.javaPrimitiveType) 0 else null
                }
                "asBinder" -> {
                    // Return the proxy itself as a binder stand-in
                    null
                }
                else -> {
                    log("IIntentSender proxy — unexpected method: ${method.name}")
                    null
                }
            }
        }

        // IntentSender(IIntentSender) — hidden constructor, use reflection
        val constructor = IntentSender::class.java.getDeclaredConstructor(iIntentSenderClass)
        constructor.isAccessible = true
        return constructor.newInstance(proxy)
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

    private fun statusToString(status: Int): String = when (status) {
        PackageInstaller.STATUS_SUCCESS -> "SUCCESS"
        PackageInstaller.STATUS_FAILURE -> "FAILURE"
        PackageInstaller.STATUS_FAILURE_ABORTED -> "ABORTED"
        PackageInstaller.STATUS_FAILURE_BLOCKED -> "BLOCKED"
        PackageInstaller.STATUS_FAILURE_CONFLICT -> "CONFLICT"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "INCOMPATIBLE"
        PackageInstaller.STATUS_FAILURE_INVALID -> "INVALID"
        PackageInstaller.STATUS_FAILURE_STORAGE -> "STORAGE"
        PackageInstaller.STATUS_PENDING_USER_ACTION -> "PENDING_USER_ACTION"
        else -> "UNKNOWN($status)"
    }

    override fun destroy() {
        log("destroy() — service being unbound")
    }
}
