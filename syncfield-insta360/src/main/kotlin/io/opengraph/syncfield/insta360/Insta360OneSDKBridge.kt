package io.opengraph.syncfield.insta360

import android.app.Application
import android.content.Context
import com.arashivision.sdkcamera.InstaCameraSDK
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.log.LogLv
import com.arashivision.sdkcamera.log.LogManager
import com.clj.fastble.data.BleDevice
import java.io.File

/**
 * Typed bridge to Insta360's official Android SDK v1.10.1.
 *
 * The SDK is Maven-distributed (`com.arashivision.sdk:sdkcamera`) and
 * requires `InstaCameraSDK.init(Application)` before any BLE or camera
 * command. SyncField initialises it lazily from the host app context so
 * React Native apps do not need to subclass their Application only for
 * Insta360 setup.
 */
internal object Insta360OneSDKBridge {

    val available: Boolean by lazy {
        runCatching {
            Class.forName("com.arashivision.sdkcamera.camera.InstaCameraManager")
            Class.forName("com.arashivision.sdkcamera.InstaCameraSDK")
        }.isSuccess
    }

    private val initLock = Any()
    @Volatile private var initialized = false

    fun setup(context: Context) {
        if (!available) throw Insta360Error.FrameworkNotLinked
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext as? Application
                ?: throw Insta360Error.CommandFailed(
                    "InstaCameraSDK.init requires an Application context")
            InstaCameraSDK.init(app)
            val logDir = File(app.filesDir, "insta360_logs").apply { mkdirs() }
            LogManager.instance.logRootPath = logDir.absolutePath
            LogManager.instance.setLogCacheLevel(LogLv.WARN)
            LogManager.instance.setLogPrintLevel(LogLv.WARN)
            initialized = true
        }
    }

    val manager: InstaCameraManager
        get() {
            if (!available) throw Insta360Error.FrameworkNotLinked
            return InstaCameraManager.getInstance()
        }

    fun stableId(device: BleDevice): String =
        listOf(device.key, device.mac, device.name)
            .firstOrNull { !it.isNullOrBlank() }
            ?: "unknown"
}
