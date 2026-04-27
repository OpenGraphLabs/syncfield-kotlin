package io.opengraph.syncfield.insta360

/**
 * Reflection-based bridge to Insta360's official `INSCameraSDK` Android
 * AAR. Lets `syncfield-insta360` compile cleanly even when host apps
 * have not added `OneSDK.aar` to their `libs/` directory.
 *
 * Host apps that need Insta360 support add the AAR to `app/libs/` plus
 * a `flatDir` entry in their `settings.gradle.kts`:
 *
 * ```kotlin
 * dependencyResolutionManagement {
 *     repositories {
 *         flatDir { dirs("libs") }
 *     }
 * }
 * dependencies {
 *     implementation(name = "OneSDK", ext = "aar")
 * }
 * ```
 *
 * Once the AAR is on the classpath every call below resolves through
 * reflection. When it isn't, [available] returns `false` and every
 * other method throws [Insta360Error.FrameworkNotLinked]. This mirrors
 * the iOS behaviour where `canImport(INSCameraServiceSDK)` gates the
 * production code paths.
 *
 * The reflective surface is intentionally narrow — only the symbols
 * the SyncField stream genuinely needs. Adding a new call means adding
 * a new method here, with one explicit caller; we don't expose a
 * generic `Class<*>.invoke(method, args)` because that would let
 * arbitrary host code reach into the SDK without a typed contract.
 */
internal object Insta360OneSDKBridge {

    /**
     * `true` when the OneSDK classes resolve. Checked lazily so the
     * cost is paid only on the first Insta360 call.
     */
    val available: Boolean by lazy {
        runCatching {
            Class.forName("com.arashivision.sdkcamera.camera.InstaCameraManager")
        }.isSuccess
    }

    /**
     * Initialise the SDK singleton. Equivalent of
     * `INSCameraManager.shared().setup()` on iOS.
     */
    fun setup() {
        if (!available) throw Insta360Error.FrameworkNotLinked
        // TODO[host]: when wiring the AAR in:
        //   InstaCameraManager.getInstance().registerSomething(...)
    }

    /**
     * Invoke a no-arg static method on a fully qualified class name and
     * return the result. Used by the BLE controller to bridge into
     * `InstaCameraManager.getInstance()`-style entry points without
     * importing the SDK at compile time.
     */
    fun staticCall(className: String, method: String): Any? {
        if (!available) throw Insta360Error.FrameworkNotLinked
        val cls = Class.forName(className)
        val m = cls.getMethod(method)
        return m.invoke(null)
    }
}
