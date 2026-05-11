package io.opengraph.syncfield.insta360

/** Public capability probe for host apps and bridges. */
object Insta360Support {
    val available: Boolean
        get() = Insta360OneSDKBridge.available
}
