package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class Insta360IdentityStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Insta360IdentityStore {
        val file = File(tmp.newFolder("insta360"), "identities.json")
        return Insta360IdentityStore(file)
    }

    @Test
    fun upsert_roundTrip() = runTest {
        val store = newStore()
        store.upsert(serialLast6 = "ABC123", uuid = "uuid-1", bleName = "GO ABC123", nowMs = 1000L)
        val r = store.recordForSerial("ABC123")
        assertThat(r).isNotNull()
        assertThat(r!!.lastKnownUUID).isEqualTo("uuid-1")
        assertThat(r.lastKnownBLEName).isEqualTo("GO ABC123")
        assertThat(r.firstPairedAtMs).isEqualTo(1000L)
        assertThat(r.lastSeenAtMs).isEqualTo(1000L)
    }

    @Test
    fun upsert_rejectsInvalidSerialOrEmptyName() = runTest {
        val store = newStore()
        store.upsert(serialLast6 = "AB", uuid = null, bleName = "GO AB", nowMs = 1L)
        assertThat(store.all()).isEmpty()
        store.upsert(serialLast6 = "ABC123", uuid = null, bleName = "", nowMs = 1L)
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun upsert_preservesFirstPairedAtOnUpdate() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-1", "GO ABC123", nowMs = 1000L)
        store.upsert("ABC123", "uuid-2", "GO ABC123", nowMs = 2000L)
        val r = store.recordForSerial("ABC123")!!
        assertThat(r.firstPairedAtMs).isEqualTo(1000L) // not updated
        assertThat(r.lastSeenAtMs).isEqualTo(2000L)
        assertThat(r.lastKnownUUID).isEqualTo("uuid-2") // new UUID accepted
    }

    @Test
    fun upsert_nullUuidPreservesExisting() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-1", "GO ABC123", nowMs = 1L)
        store.upsert("ABC123", null, "GO ABC123", nowMs = 2L)
        assertThat(store.recordForSerial("ABC123")!!.lastKnownUUID).isEqualTo("uuid-1")
    }

    @Test
    fun touch_onlyUpdatesLastSeen() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-1", "GO ABC123", nowMs = 1L)
        store.touch("ABC123", nowMs = 999L)
        val r = store.recordForSerial("ABC123")!!
        assertThat(r.lastSeenAtMs).isEqualTo(999L)
        assertThat(r.firstPairedAtMs).isEqualTo(1L)
    }

    @Test
    fun touch_missingRecordIsNoOp() = runTest {
        val store = newStore()
        store.touch("NOPE12", nowMs = 1L)
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun recordForUUID_lookup() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-A", "GO ABC123", 1L)
        store.upsert("DEF456", "uuid-B", "GO DEF456", 1L)
        assertThat(store.recordForUUID("uuid-B")?.serialLast6).isEqualTo("DEF456")
        assertThat(store.recordForUUID("nope")).isNull()
    }

    @Test
    fun remove_andClear() = runTest {
        val store = newStore()
        store.upsert("ABC123", null, "GO ABC123", 1L)
        store.upsert("DEF456", null, "GO DEF456", 1L)
        store.remove("ABC123")
        assertThat(store.all().map { it.serialLast6 }).containsExactly("DEF456")
        store.clear()
        assertThat(store.all()).isEmpty()
    }

    @Test
    fun markPhoneAuthorized_clearsFailureAndSetsTimestamp() = runTest {
        val store = newStore()
        store.upsert("ABC123", null, "GO ABC123", 1L)
        store.clearPhoneAuthorization("ABC123", atMs = 100L) // simulate prior failure
        store.markPhoneAuthorized("ABC123", atMs = 200L)
        val r = store.recordForSerial("ABC123")!!
        assertThat(r.phoneAuthorizedAtMs).isEqualTo(200L)
        assertThat(r.phoneAuthorizationFailedAtMs).isNull()
        assertThat(store.isPhoneAuthorized("ABC123")).isTrue()
    }

    @Test
    fun cachedPhoneAuthState_unknownForMissingRecord() = runTest {
        val store = newStore()
        assertThat(store.cachedPhoneAuthState(uuid = null, bleName = "GO ABC123"))
            .isEqualTo(PhoneAuthorizationCacheState.Unknown)
        assertThat(store.cachedPhoneAuthState(uuid = "x", bleName = null))
            .isEqualTo(PhoneAuthorizationCacheState.Unknown)
    }

    @Test
    fun cachedPhoneAuthState_authorizedHit() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-1", "GO ABC123", 1L)
        store.markPhoneAuthorized("ABC123", atMs = 555L)
        val state = store.cachedPhoneAuthState(uuid = null, bleName = "GO ABC123")
        assertThat(state).isInstanceOf(PhoneAuthorizationCacheState.Authorized::class.java)
        assertThat((state as PhoneAuthorizationCacheState.Authorized).atEpochMs).isEqualTo(555L)
    }

    @Test
    fun cachedPhoneAuthState_failedHitByUUID() = runTest {
        val store = newStore()
        store.upsert("ABC123", "uuid-1", "GO ABC123", 1L)
        store.clearPhoneAuthorization("ABC123", atMs = 700L)
        assertThat(store.cachedPhoneAuthState(uuid = "uuid-1", bleName = null))
            .isEqualTo(PhoneAuthorizationCacheState.Failed)
    }

    @Test
    fun persistence_acrossInstancesViaSamePath() = runTest {
        val folder = tmp.newFolder("insta360-persist")
        val path = File(folder, "identities.json")
        val s1 = Insta360IdentityStore(path)
        s1.upsert("ABC123", "uuid-1", "GO ABC123", 1L)
        s1.markPhoneAuthorized("ABC123", atMs = 100L)

        // New instance reading the same file
        val s2 = Insta360IdentityStore(path)
        assertThat(s2.recordForSerial("ABC123")).isNotNull()
        assertThat(s2.isPhoneAuthorized("ABC123")).isTrue()
    }

    @Test
    fun corruptedFile_returnsEmptyStoreAndOverwritesOnUpsert() = runTest {
        val folder = tmp.newFolder("insta360-corrupt")
        val path = File(folder, "identities.json")
        path.writeText("{ this is not valid json")
        val store = Insta360IdentityStore(path)
        assertThat(store.all()).isEmpty() // graceful fallback
        store.upsert("ABC123", null, "GO ABC123", 1L)
        // File should now be readable
        val replay = Insta360IdentityStore(path)
        assertThat(replay.recordForSerial("ABC123")).isNotNull()
    }
}
