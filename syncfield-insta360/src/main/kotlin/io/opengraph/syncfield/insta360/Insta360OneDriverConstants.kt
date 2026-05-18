package io.opengraph.syncfield.insta360

/**
 * Stable Kotlin-side names for the integer constants the SDK's
 * `OneDriverInfo` exposes via deeply-nested static classes. Centralising
 * them here keeps call-sites readable and lets refactors find them
 * without grepping for magic numbers.
 *
 * Reference: minicamera 11.9.1 `OneDriverInfo.java`.
 */
internal object OneDriverInfoConstants {

    // ── Response.Authenticate (line ~479) — returned by checkAuthorization
    /** Camera already trusts this phone identifier. */
    const val AUTHENTICATE_AUTHORIZED: Int = 0
    /** Camera will now display the "Allow this app?" LCD prompt. */
    const val AUTHENTICATE_UNAUTHORIZED: Int = 1
    /** Camera can't process auth right now; caller may retry. */
    const val AUTHENTICATE_SYSTEMBUSY: Int = 2

    // ── Notification.Authorization (line ~135) — user decision delivered
    //    via `onDriverInfoNotify(what=NOTIFY_AUTHORIZATION(80), err=...)`
    const val AUTH_RESULT_SUCCESS: Int = 0
    const val AUTH_RESULT_REJECT: Int = 1
    const val AUTH_RESULT_TIMEOUT: Int = 2
    const val AUTH_RESULT_SYSTEM_BUSY: Int = 3

    // ── Notification.AuthorizationOperationType (line ~142)
    const val AUTH_OP_BLE_CONNECT: Int = 0
    const val AUTH_OP_WIFI_PWD_SETTING: Int = 1
    const val AUTH_OP_CLOUD_BIND: Int = 2
}
