package io.opengraph.syncfield.insta360

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Tracks which Insta360 BLE UUID is bound to which wrist role
 * (`ego` / `left` / `right`). Mirrors `Insta360RoleRegistry.swift`.
 *
 * The registry is the source of truth that prevents two cameras from
 * claiming the same role across an `assignWristRole` race.
 */
class Insta360RoleRegistry {

    private val mutex = Mutex()
    private val roleToUuid: MutableMap<String, String> = mutableMapOf()

    suspend fun tryClaim(role: String, uuid: String): Boolean = mutex.withLock {
        val existing = roleToUuid[role]
        if (existing != null && existing != uuid) return@withLock false
        roleToUuid[role] = uuid
        true
    }

    suspend fun release(role: String) = mutex.withLock {
        roleToUuid.remove(role)
        Unit
    }

    suspend fun claimedRoles(): List<String> = mutex.withLock { roleToUuid.keys.toList() }

    suspend fun uuidForRole(role: String): String? = mutex.withLock { roleToUuid[role] }

    suspend fun clearAll() = mutex.withLock {
        roleToUuid.clear()
        Unit
    }
}
