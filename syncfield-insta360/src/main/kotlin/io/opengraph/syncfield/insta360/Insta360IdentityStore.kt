package io.opengraph.syncfield.insta360

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disk-backed cache for Insta360 camera identity that survives app cold starts.
 *
 * Bluetooth peripheral UUIDs may rotate across launches, but the BLE advertised
 * camera name contains the stable serial suffix we need for wake-by-serial.
 * This store keeps both so callers can accept either identity.
 *
 * Mirrors `Insta360IdentityStore.swift`. The on-disk JSON schema uses
 * snake_case field names so cross-platform readers can parse either platform's
 * file without translation. WiFi credentials storage is intentionally deferred
 * to a later release (Android consumers use EncryptedSharedPreferences in
 * the host app layer).
 *
 * Thread safety: all mutating methods are `suspend` and serialized by a [Mutex].
 *
 * @param file Path to the JSON records file. Tests pass a tmpFolder-allocated
 *             file; production callers should use [Companion.shared].
 */
class Insta360IdentityStore internal constructor(internal val file: File) {

    @Serializable
    data class Record(
        @SerialName("serial_last_6") val serialLast6: String,
        @SerialName("last_known_uuid") var lastKnownUUID: String? = null,
        @SerialName("last_known_ble_name") var lastKnownBLEName: String,
        @SerialName("first_paired_at_ms") val firstPairedAtMs: Long,
        @SerialName("last_seen_at_ms") var lastSeenAtMs: Long,
        @SerialName("phone_authorized_at_ms") var phoneAuthorizedAtMs: Long? = null,
        @SerialName("phone_authorization_failed_at_ms") var phoneAuthorizationFailedAtMs: Long? = null,
    )

    private val mutex = Mutex()
    private val records: MutableMap<String, Record>
    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    init {
        file.parentFile?.mkdirs()
        records = loadFromDisk(file, json).toMutableMap()
    }

    /** Insert or update a record by serial. No-op if [serialLast6] is not exactly 6 chars or [bleName] is empty. */
    suspend fun upsert(
        serialLast6: String,
        uuid: String?,
        bleName: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (serialLast6.length != 6 || bleName.isEmpty()) return
        mutex.withLock {
            val existing = records[serialLast6]
            val updated = existing?.copy(
                lastKnownUUID = uuid ?: existing.lastKnownUUID,
                lastKnownBLEName = bleName,
                lastSeenAtMs = nowMs,
            ) ?: Record(
                serialLast6 = serialLast6,
                lastKnownUUID = uuid,
                lastKnownBLEName = bleName,
                firstPairedAtMs = nowMs,
                lastSeenAtMs = nowMs,
            )
            records[serialLast6] = updated
            saveToDisk()
        }
    }

    /** Bump lastSeenAt for an existing record. No-op if the record doesn't exist. */
    suspend fun touch(serialLast6: String, nowMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            records[serialLast6]?.let {
                records[serialLast6] = it.copy(lastSeenAtMs = nowMs)
                saveToDisk()
            }
        }
    }

    suspend fun recordForSerial(serial: String): Record? = mutex.withLock { records[serial] }

    suspend fun recordForUUID(uuid: String): Record? = mutex.withLock {
        records.values.firstOrNull { it.lastKnownUUID == uuid }
    }

    suspend fun all(): List<Record> = mutex.withLock { records.values.toList() }

    suspend fun remove(serialLast6: String) {
        mutex.withLock {
            if (records.remove(serialLast6) != null) saveToDisk()
        }
    }

    suspend fun clear() {
        mutex.withLock {
            records.clear()
            saveToDisk()
        }
    }

    /** Mark phone authorization success. Clears any prior failure marker. */
    suspend fun markPhoneAuthorized(serialLast6: String, atMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            records[serialLast6]?.let {
                records[serialLast6] = it.copy(
                    phoneAuthorizedAtMs = atMs,
                    phoneAuthorizationFailedAtMs = null,
                    lastSeenAtMs = atMs,
                )
                saveToDisk()
            }
        }
    }

    /** Mark phone authorization as failed (rejection or timeout). Clears any prior success. */
    suspend fun clearPhoneAuthorization(serialLast6: String, atMs: Long = System.currentTimeMillis()) {
        mutex.withLock {
            records[serialLast6]?.let {
                records[serialLast6] = it.copy(
                    phoneAuthorizedAtMs = null,
                    phoneAuthorizationFailedAtMs = atMs,
                    lastSeenAtMs = atMs,
                )
                saveToDisk()
            }
        }
    }

    suspend fun isPhoneAuthorized(serialLast6: String): Boolean = mutex.withLock {
        records[serialLast6]?.phoneAuthorizedAtMs != null
    }

    /**
     * Resolve cached phone-authorization state by either UUID or BLE name.
     *
     * Lookup order (mirrors Swift):
     *  1. By extracted serial from [bleName]
     *  2. By [uuid] (matches `lastKnownUUID`)
     *  3. By extracted serial (already covered by 1; kept for cross-record matches)
     */
    suspend fun cachedPhoneAuthState(uuid: String?, bleName: String?): PhoneAuthorizationCacheState {
        val serial = bleName?.let { Insta360KnownCameraIdentity.extractSerialLast6(it) }
        val record: Record? = mutex.withLock {
            if (serial != null) records[serial]?.let { return@withLock it }
            if (uuid != null) records.values.firstOrNull { it.lastKnownUUID == uuid }?.let { return@withLock it }
            null
        }
        if (record == null) return PhoneAuthorizationCacheState.Unknown
        record.phoneAuthorizedAtMs?.let { return PhoneAuthorizationCacheState.Authorized(it) }
        if (record.phoneAuthorizationFailedAtMs != null) return PhoneAuthorizationCacheState.Failed
        return PhoneAuthorizationCacheState.Unknown
    }

    // --- Disk I/O (called under mutex) -------------------------------------

    private fun saveToDisk() {
        val payload = json.encodeToString(recordsSerializer, records)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(payload)
        // Atomic rename — survives a crash mid-write.
        if (!tmp.renameTo(file)) {
            // Fallback: overwrite directly if rename fails (e.g. cross-fs)
            file.writeText(payload)
            tmp.delete()
        }
    }

    companion object {
        // Process-wide singleton — initialized once per Context (host app calls
        // .shared(context) at startup; tests bypass via the internal constructor).
        @Volatile private var instance: Insta360IdentityStore? = null

        fun shared(context: Context): Insta360IdentityStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: createDefault(context).also { instance = it }
            }
        }

        private fun createDefault(context: Context): Insta360IdentityStore {
            val dir = File(context.filesDir, "insta360")
            return Insta360IdentityStore(File(dir, "identities.json"))
        }

        /** Test-only — clears the singleton so the next [shared] call rebuilds it. */
        internal fun resetForTest() {
            instance = null
        }

        internal val recordsSerializer = MapSerializer(String.serializer(), Record.serializer())

        private fun loadFromDisk(file: File, json: Json): Map<String, Record> {
            if (!file.exists()) return emptyMap()
            return runCatching {
                json.decodeFromString(recordsSerializer, file.readText())
            }.getOrElse { emptyMap() }
        }
    }
}
