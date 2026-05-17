package io.opengraph.syncfield.insta360

import android.app.Application
import android.content.Context
import android.net.Network
import com.arashivision.sdkcamera.InstaCameraSDK
import com.arashivision.sdkcamera.camera.InstaCameraManager
import com.arashivision.sdkcamera.log.LogLv
import com.arashivision.sdkcamera.log.LogManager
import com.clj.fastble.data.BleDevice
import com.clj.fastble.utils.BleLog
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
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
        Insta360OneSDKAvailability.areClassesAvailable(
            listOf(
                "com.arashivision.sdkcamera.camera.InstaCameraManager",
                "com.arashivision.sdkcamera.InstaCameraSDK",
            )
        )
    }

    private val initLock = Any()
    @Volatile private var initialized = false

    /**
     * Initialize the Insta360 SDK exactly once. `initCameraSupportConfig` is
     * deliberately NOT called here — it requires an already-connected camera
     * (the SDK uses it to download per-device capability JSON), so calling
     * it before BLE scan logs `BaseCamera is empty, the camera may have been
     * disconnected`. Discovery uses the native [Insta360NativeBleScanner]
     * which doesn't depend on per-device support config.
     */
    suspend fun setup(context: Context) {
        if (!available) throw Insta360Error.FrameworkNotLinked
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val app = context.applicationContext as? Application
                ?: throw Insta360Error.CommandFailed(
                    "InstaCameraSDK.init requires an Application context")
            InstaCameraSDK.init(app)
            // `Insta360SupportConfigWrapper` was installed here while we
            // were attempting to make the SDK's high-level `connectBle()`
            // accept GO 3S. That path is now bypassed entirely (we drive
            // GATT via fastble in [Insta360GattHandshake]), so the wrapper
            // is no longer wired. Kept in the source tree for reference.
            val externalRoot = app.getExternalFilesDir(null) ?: app.filesDir
            val logDir = File(externalRoot, "insta360_logs").apply { mkdirs() }
            LogManager.instance.logRootPath = logDir.absolutePath
            // VERBOSE while the protocol handshake is still in bring-up.
            // Revert to INFO once first-pair + identify + record work
            // end-to-end on a real GO 3S.
            LogManager.instance.setLogCacheLevel(LogLv.VERBOSE)
            LogManager.instance.setLogPrintLevel(LogLv.VERBOSE)
            BleLog.enableLog(true)
            android.util.Log.i(
                "INSTA360_BRIDGE",
                "sdk_file_log_root path=${logDir.absolutePath}",
            )
            initialized = true
            InstaLog.log(InstaLogCategory.BRIDGE, event = "sdk_init_complete")
        }
    }

    val manager: InstaCameraManager
        get() {
            if (!available) throw Insta360Error.FrameworkNotLinked
            return InstaCameraManager.getInstance()
        }

    fun bindNetwork(network: Network) {
        if (!available) return
        manager.setNetIdToCamera(network.networkHandle)
    }

    fun stableId(device: BleDevice): String =
        listOf(device.key, device.mac, device.name)
            .firstOrNull { !it.isNullOrBlank() }
            ?: "unknown"
}

internal object Insta360OneSDKAvailability {
    fun areClassesAvailable(
        classNames: List<String>,
        classLoader: ClassLoader =
            Insta360OneSDKAvailability::class.java.classLoader ?: ClassLoader.getSystemClassLoader(),
    ): Boolean = classNames.all { name ->
        runCatching { Class.forName(name, false, classLoader) }.isSuccess
    }
}
