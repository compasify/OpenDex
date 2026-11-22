package dev.opendex.platform

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Standalone ADB shell server (started via `adb shell app_process`) that hosts
 * [FreeformShellService] under `uid 2000` (shell) without requiring Shizuku.
 */
object OpenDexShellServer {
    private const val DEFAULT_PACKAGE = "dev.opendex"
    private const val POLL_INTERVAL_MS = 1_500L

    @JvmStatic
    fun main(args: Array<String>) {
        if (!isAuthorizedBackendUid(Process.myUid())) {
            System.err.println("OpenDexShellServer must run under ADB shell (uid 2000) or root in Root edition, got uid=${Process.myUid()}")
            exitProcess(1)
        }
        val packageName = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_PACKAGE
        @Suppress("DEPRECATION")
        Looper.prepareMainLooper()

        val systemContext = createSystemContext() ?: run {
            System.err.println("OpenDexShellServer: failed to obtain system context")
            exitProcess(2)
        }
        val appContext = runCatching {
            systemContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY)
        }.getOrElse {
            System.err.println("OpenDexShellServer: package $packageName not found: ${it.message}")
            exitProcess(3)
        }
        val callerContext = if (Process.myUid() == ROOT_UID) {
            systemContext
        } else {
            runCatching {
                systemContext.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            }.getOrElse { systemContext }
        }

        val service = FreeformShellService(appContext)
        val registrar = Thread({
            runRegistrationLoop(callerContext, packageName, service)
        }, "opendex-adb-registrar").apply { isDaemon = true }
        registrar.start()

        Looper.loop()
    }

    private fun createSystemContext(): Context? = runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val thread = activityThreadClass.getMethod("systemMain").invoke(null)
        activityThreadClass.getMethod("getSystemContext").invoke(thread) as? Context
    }.getOrNull()

    private fun runRegistrationLoop(shellContext: Context, packageName: String, service: FreeformShellService) {
        var firstAttempt = true
        while (true) {
            if (!firstAttempt) {
                waitUntilProcessRunning(packageName)
            }
            firstAttempt = false

            val appToken = registerBinder(shellContext, packageName, service)
            if (appToken == null) {
                SystemClock.sleep(POLL_INTERVAL_MS)
                continue
            }

            println("OpenDex ADB server connected to $packageName.")
            val died = CountDownLatch(1)
            val recipient = IBinder.DeathRecipient { died.countDown() }
            val linked = runCatching { appToken.linkToDeath(recipient, 0) }.isSuccess
            if (linked && appToken.isBinderAlive) {
                runCatching { died.await() }
            }
            service.releaseAllDisplays()
            SystemClock.sleep(500)
        }
    }

    private fun registerBinder(shellContext: Context, packageName: String, service: FreeformShellService): IBinder? {
        val uri = Uri.parse("content://$packageName.directadb")
        val extras = Bundle().apply {
            putBinder(DirectAdbBridge.EXTRA_BINDER, service)
        }
        return runCatching {
            val reply = shellContext.contentResolver.call(uri, DirectAdbBridge.METHOD_REGISTER, null, extras)
            reply?.getBinder(DirectAdbBridge.EXTRA_APP_TOKEN)
        }.getOrNull()
    }

    private fun waitUntilProcessRunning(packageName: String) {
        while (!isProcessRunning(packageName)) {
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
    }

    private fun isProcessRunning(packageName: String): Boolean {
        val process = try {
            ProcessBuilder("/system/bin/pidof", packageName).redirectErrorStream(true).start()
        } catch (_: Exception) {
            return true
        }
        return try {
            val output = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotEmpty()
        } catch (_: Exception) {
            false
        } finally {
            process.destroyForcibly()
        }
    }
}
