package io.opengraph.syncfield.insta360

import android.content.Context
import android.provider.Settings
import java.util.UUID

/**
 * Stable per-install identifier sent as the `uniqueId` to
 * `OneDriver.checkAuthorization`. Equivalent to iOS's
 * `INSConnectionUtils.authorizationId()`.
 *
 * Strategy:
 *   - First call seeds [Settings.Secure.ANDROID_ID] if available
 *     (most stable; survives app reinstall on the same device user).
 *   - Falls back to a randomly-generated UUID stored in our own
 *     SharedPreferences. Persists across launches but resets if the
 *     user wipes app data.
 *
 * The camera firmware bonds against this string — using a different
 * one next time means the user has to re-approve on the LCD.
 */
object Insta360PhoneAuthDeviceId {

    private const val PREFS = "insta360_auth"
    private const val KEY_DEVICE_ID = "phone_auth_device_id"

    fun stableId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val seed = runCatching {
            Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ANDROID_ID,
            )
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" /* legacy buggy value */ }
            ?: UUID.randomUUID().toString().replace("-", "")

        prefs.edit().putString(KEY_DEVICE_ID, seed).apply()
        return seed
    }
}
