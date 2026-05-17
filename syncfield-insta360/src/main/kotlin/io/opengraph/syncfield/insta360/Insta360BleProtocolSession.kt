package io.opengraph.syncfield.insta360

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import com.clj.fastble.BleManager
import com.clj.fastble.callback.BleMtuChangedCallback
import com.clj.fastble.callback.BleNotifyCallback
import com.clj.fastble.callback.BleWriteCallback
import com.clj.fastble.data.BleDevice
import com.clj.fastble.exception.BleException
import io.opengraph.syncfield.insta360.logging.InstaLog
import io.opengraph.syncfield.insta360.logging.InstaLogCategory
import io.opengraph.syncfield.insta360.logging.InstaLogLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Post-GATT Insta360 BLE protocol session.
 *
 * Mirrors the handshake the SDK's `BleConnectCmd.notifyAndRead` performs
 * after fastble brings up the GATT link: MTU bump → discover the
 * `be80` service → enable notify on the RW characteristic → write the
 * sync packet → wait for the camera to acknowledge with a frame
 * starting with `ff0641`. Without this handshake, the camera tears the
 * GATT link down within milliseconds (HCI status=19).
 *
 * Wake-up authorization (the `ff0d03 ...` round-trip that puts the
 * camera's LCD into "Approve this phone?" mode) is **not** performed
 * here yet — its outbound packet format is built inside the SDK's
 * `OneDriver` JNI layer (`sendWakeUpAuthorization(uuid)`) and we
 * cannot synthesize it without that native code. First-pair UX work
 * is gated on figuring that out; see the plan file's risk register.
 */
internal class Insta360BleProtocolSession private constructor(
    private val device: BleDevice,
    val gatt: BluetoothGatt,
    private val serviceUuid: String,
    private val rwCharUuid: String,
    private val notifyCharUuid: String,
) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val notifications = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /** All notify-channel frames the camera has sent since establish(). */
    val notificationFlow: SharedFlow<ByteArray> = notifications.asSharedFlow()

    @Volatile private var closed: Boolean = false

    /**
     * Write [payload] to the RW characteristic. Splits into MTU-bounded
     * chunks as [Insta360ProtocolPackets.WRITE_MAX_LEN] requires. Suspends
     * until each chunk's write callback resolves, then returns.
     *
     * Does NOT wait for a response on the notify channel — callers that
     * need a reply should observe [notificationFlow] in parallel.
     */
    suspend fun sendCommand(payload: ByteArray) {
        if (closed) throw Insta360Error.CommandFailed("protocol session closed")
        if (payload.isEmpty()) return
        val chunks = splitForWrite(payload)
        val bleManager = BleManager.getInstance()
        for ((index, chunk) in chunks.withIndex()) {
            val ack = CompletableDeferred<Unit>()
            bleManager.write(
                device, serviceUuid, rwCharUuid,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                chunk, /* split = */ false,
                object : BleWriteCallback() {
                    override fun onWriteSuccess(current: Int, total: Int, justWrite: ByteArray) {
                        if (!ack.isCompleted) ack.complete(Unit)
                    }

                    override fun onWriteFailure(exception: BleException) {
                        if (!ack.isCompleted) {
                            ack.completeExceptionally(
                                Insta360Error.CommandFailed(
                                    "protocol write failed code=${exception.code} desc=${exception.description}"
                                )
                            )
                        }
                    }
                },
            )
            withTimeout(5_000) { ack.await() }
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "protocol_session_write_ack",
                fields = mapOf("chunk" to (index + 1), "of" to chunks.size, "bytes" to chunk.size),
            )
        }
    }

    /** Tear down the session. Idempotent. */
    fun close() {
        if (closed) return
        closed = true
        sessionScope.coroutineContext[Job]?.cancel()
        runCatching {
            BleManager.getInstance().stopNotify(device, serviceUuid, notifyCharUuid)
        }
        InstaLog.log(InstaLogCategory.BLE, event = "protocol_session_closed")
    }

    internal fun deliverNotification(data: ByteArray) {
        // Internal hook for the notify callback; visible for the
        // factory to wire incoming frames once notify is subscribed.
        if (closed) return
        notifications.tryEmit(data)
    }

    companion object {

        /**
         * Drive the post-GATT handshake to completion. Returns a ready
         * session on success; throws [Insta360Error] on any
         * handshake-stage failure (MTU change, service missing,
         * notify subscribe, sync timeout).
         */
        suspend fun establish(
            device: BleDevice,
            gatt: BluetoothGatt,
        ): Insta360BleProtocolSession {
            val bleManager = BleManager.getInstance()

            // ── Step 1: MTU negotiation ─────────────────────────
            val mtuDeferred = CompletableDeferred<Int>()
            bleManager.setMtu(device, Insta360ProtocolPackets.MTU,
                object : BleMtuChangedCallback() {
                    override fun onSetMTUFailure(exception: BleException) {
                        if (!mtuDeferred.isCompleted) {
                            mtuDeferred.completeExceptionally(
                                Insta360Error.CommandFailed(
                                    "setMtu failed code=${exception.code} desc=${exception.description}"
                                )
                            )
                        }
                    }

                    override fun onMtuChanged(mtu: Int) {
                        if (!mtuDeferred.isCompleted) mtuDeferred.complete(mtu)
                    }
                })
            val actualMtu = withTimeout(5_000) { mtuDeferred.await() }
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "protocol_session_mtu_set",
                fields = mapOf("requested" to Insta360ProtocolPackets.MTU, "actual" to actualMtu),
            )

            // ── Step 2: locate `be80` service + its chars ───────
            val service: BluetoothGattService = gatt.services
                ?.firstOrNull { it.uuid.toString().contains(Insta360ProtocolPackets.SERVICE_UUID_16BIT) }
                ?: throw Insta360Error.CommandFailed(
                    "protocol service ${Insta360ProtocolPackets.SERVICE_UUID_16BIT} not found; " +
                        "discovered=${gatt.services?.joinToString(",") { it.uuid.toString() } ?: "<null>"}"
                )

            var rwChar: BluetoothGattCharacteristic? = null
            var notifyChar: BluetoothGattCharacteristic? = null
            for (ch in service.characteristics) {
                val role = pickCharacteristicRole(ch.properties)
                if (role.isReadWrite && rwChar == null) rwChar = ch
                if (role.isNotify && notifyChar == null) notifyChar = ch
            }
            val finalRw = rwChar
                ?: throw Insta360Error.CommandFailed(
                    "no RW characteristic on service ${service.uuid}"
                )
            val finalNotify = notifyChar ?: finalRw
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "protocol_session_chars_resolved",
                fields = mapOf(
                    "service" to service.uuid.toString(),
                    "rw_char" to finalRw.uuid.toString(),
                    "notify_char" to finalNotify.uuid.toString(),
                ),
            )

            val session = Insta360BleProtocolSession(
                device = device,
                gatt = gatt,
                serviceUuid = service.uuid.toString(),
                rwCharUuid = finalRw.uuid.toString(),
                notifyCharUuid = finalNotify.uuid.toString(),
            )

            // ── Step 3: subscribe to notify ─────────────────────
            val syncResponse = CompletableDeferred<Unit>()
            val notifySubscribed = CompletableDeferred<Unit>()
            bleManager.notify(
                device,
                service.uuid.toString(),
                finalNotify.uuid.toString(),
                object : BleNotifyCallback() {
                    override fun onNotifySuccess() {
                        if (!notifySubscribed.isCompleted) notifySubscribed.complete(Unit)
                        InstaLog.log(
                            InstaLogCategory.BLE,
                            event = "protocol_session_notify_subscribed",
                            fields = mapOf("service" to Insta360ProtocolPackets.SERVICE_UUID_16BIT),
                        )
                    }

                    override fun onNotifyFailure(exception: BleException) {
                        val err = Insta360Error.CommandFailed(
                            "notify subscribe failed code=${exception.code} desc=${exception.description}"
                        )
                        if (!notifySubscribed.isCompleted) {
                            notifySubscribed.completeExceptionally(err)
                        }
                        if (!syncResponse.isCompleted) {
                            syncResponse.completeExceptionally(err)
                        }
                    }

                    override fun onCharacteristicChanged(data: ByteArray) {
                        // Surface every frame to subscribers regardless of
                        // sync state — useful for hardware bring-up logs.
                        session.deliverNotification(data)
                        if (isSyncResponse(data) && !syncResponse.isCompleted) {
                            syncResponse.complete(Unit)
                            InstaLog.log(
                                InstaLogCategory.BLE,
                                event = "protocol_session_sync_received",
                                fields = mapOf(
                                    "prefix" to Insta360ProtocolPackets.SYNC_RESPONSE_PREFIX_HEX,
                                    "frame_len" to data.size,
                                ),
                            )
                        } else if (!syncResponse.isCompleted) {
                            InstaLog.log(
                                InstaLogCategory.BLE, level = InstaLogLevel.DEBUG,
                                event = "protocol_session_notify_before_sync",
                                fields = mapOf("bytes" to data.size, "hex" to data.toHex()),
                            )
                        }
                    }
                },
            )
            withTimeout(5_000) { notifySubscribed.await() }

            // ── Step 4: write sync packet ───────────────────────
            session.sendCommand(Insta360ProtocolPackets.SYNC_PACKET)
            InstaLog.log(
                InstaLogCategory.BLE,
                event = "protocol_session_sync_sent",
                fields = mapOf("bytes" to Insta360ProtocolPackets.SYNC_PACKET.size),
            )

            // ── Step 5: await sync response ─────────────────────
            withTimeout(Insta360ProtocolPackets.SYNC_TIMEOUT_MS) { syncResponse.await() }

            InstaLog.log(
                InstaLogCategory.BLE,
                event = "protocol_session_ready",
                fields = mapOf(
                    "service" to service.uuid.toString(),
                    "rw_char" to finalRw.uuid.toString(),
                    "mtu" to actualMtu,
                ),
            )
            return session
        }

        // ── Pure helpers (unit-tested) ─────────────────────────

        /**
         * True iff [frame] begins with [Insta360ProtocolPackets.SYNC_RESPONSE_PREFIX_HEX]
         * (`ff 06 41`). Mirrors `BleConnectCmd.parseSync(byte[])`.
         */
        @JvmStatic
        fun isSyncResponse(frame: ByteArray): Boolean {
            val expected = hexToBytes(Insta360ProtocolPackets.SYNC_RESPONSE_PREFIX_HEX)
            if (frame.size < expected.size) return false
            for (i in expected.indices) {
                if (frame[i] != expected[i]) return false
            }
            return true
        }

        /**
         * Split [payload] into MTU-bounded chunks matching the SDK's
         * `BleConnectCmd.onWrite()` loop. Last chunk may be shorter.
         */
        @JvmStatic
        fun splitForWrite(payload: ByteArray): List<ByteArray> {
            if (payload.isEmpty()) return emptyList()
            val max = Insta360ProtocolPackets.WRITE_MAX_LEN
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < payload.size) {
                val len = minOf(max, payload.size - offset)
                chunks += payload.copyOfRange(offset, offset + len)
                offset += len
            }
            return chunks
        }

        /**
         * Classify a characteristic's properties bitmask so the
         * handshake can pick the right one for write vs notify roles.
         * GO 3S exposes one char with all three; some firmware splits.
         */
        @JvmStatic
        fun pickCharacteristicRole(properties: Int): CharRole {
            val read = BluetoothGattCharacteristic.PROPERTY_READ
            val write = BluetoothGattCharacteristic.PROPERTY_WRITE
            val notify = BluetoothGattCharacteristic.PROPERTY_NOTIFY
            return CharRole(
                isReadWrite = (properties and read) != 0 && (properties and write) != 0,
                isNotify = (properties and notify) != 0,
            )
        }

        private fun hexToBytes(hex: String): ByteArray {
            require(hex.length % 2 == 0)
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Characteristic property classification (helper output). */
    data class CharRole(val isReadWrite: Boolean, val isNotify: Boolean)
}
