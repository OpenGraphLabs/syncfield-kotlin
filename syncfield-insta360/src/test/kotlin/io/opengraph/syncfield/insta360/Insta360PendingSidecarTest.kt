package io.opengraph.syncfield.insta360

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Insta360PendingSidecarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `write and scan round-trip preserves all fields`() {
        val ep = tmp.newFolder("ep_20260427_test_abc")
        val sidecar = Insta360PendingSidecar(
            streamId = "cam_wrist_left",
            bleUuid = "uuid-1234",
            bleName = "Insta360 GO ABC",
            cameraFileURI = "/DCIM/100GOPRO/clip.mp4",
            bleAckMonotonicNs = 1_234_567_890L,
        )
        Insta360PendingSidecar.write(ep, sidecar)

        val scanned = Insta360PendingSidecar.scan(ep)
        assertThat(scanned).hasSize(1)
        assertThat(scanned.single()).isEqualTo(sidecar)
    }

    @Test
    fun `delete removes the sidecar file`() {
        val ep = tmp.newFolder("ep_20260427_test_def")
        Insta360PendingSidecar.write(
            ep,
            Insta360PendingSidecar("cam_wrist_right", "u", "n", "/x", 1L)
        )
        assertThat(Insta360PendingSidecar.delete(ep, "cam_wrist_right")).isTrue()
        assertThat(Insta360PendingSidecar.scan(ep)).isEmpty()
    }

    @Test
    fun `scanRecursive returns sidecars across episode dirs`() {
        val a = tmp.newFolder("ep_20260427_a")
        val b = tmp.newFolder("ep_20260427_b")
        val notEp = tmp.newFolder("not_an_episode")
        Insta360PendingSidecar.write(a,
            Insta360PendingSidecar("cam_a", "ua", "na", "/a", 1L))
        Insta360PendingSidecar.write(b,
            Insta360PendingSidecar("cam_b", "ub", "nb", "/b", 2L))
        Insta360PendingSidecar.write(notEp,
            Insta360PendingSidecar("cam_x", "ux", "nx", "/x", 3L))

        val all = Insta360PendingSidecar.scanRecursive(tmp.root)
        assertThat(all.map { it.sidecar.streamId }).containsExactly("cam_a", "cam_b", "cam_x")
    }

    @Test
    fun `write helper fills role and saved timestamp`() {
        val ep = tmp.newFolder("ep_20260427_test_role")
        Insta360PendingSidecar.write(
            episodeDir = ep,
            streamId = "cam_wrist_left",
            cameraFileURI = "/clip.mp4",
            bleUuid = "uuid-left",
            bleName = "GO 3S",
            role = "left",
            bleAckNs = 42L,
        )

        val sidecar = Insta360PendingSidecar.scan(ep).single()
        assertThat(sidecar.role).isEqualTo("left")
        assertThat(sidecar.savedAt).isNotEmpty()
        assertThat(sidecar.bleAckMonotonicNs).isEqualTo(42L)
    }

    @Test
    fun `scan reads legacy snake case sidecars`() {
        val ep = tmp.newFolder("ep_20260427_test_legacy")
        ep.resolve("cam_wrist_left.pending.json").writeText(
            """
            {
              "stream_id": "cam_wrist_left",
              "ble_uuid": "uuid-left",
              "ble_name": "GO 3S",
              "camera_file_uri": "/DCIM/Camera01/VID_20260515_113613_00_214.mp4",
              "ble_ack_monotonic_ns": 1234,
              "saved_at": "2026-05-18T00:00:00Z"
            }
            """.trimIndent()
        )

        val sidecar = Insta360PendingSidecar.scan(ep).single()
        assertThat(sidecar.streamId).isEqualTo("cam_wrist_left")
        assertThat(sidecar.bleUuid).isEqualTo("uuid-left")
        assertThat(sidecar.cameraFileURI).isEqualTo("/DCIM/Camera01/VID_20260515_113613_00_214.mp4")
        assertThat(sidecar.bleAckMonotonicNs).isEqualTo(1234L)
        assertThat(sidecar.savedAt).isEqualTo("2026-05-18T00:00:00Z")
    }

    @Test
    fun `write unresolved replaces tentative sidecar with pending sidecar`() {
        val ep = tmp.newFolder("ep_20260427_test_unresolved")
        Insta360PendingSidecar.writeTentative(
            episodeDir = ep,
            streamId = "cam_wrist_right",
            role = "right",
            deviceUuid = "uuid-right",
            deviceName = "GO 3S",
            bleAckNs = 88L,
        )

        Insta360PendingSidecar.writeUnresolved(
            episodeDir = ep,
            streamId = "cam_wrist_right",
            bleUuid = "uuid-right",
            bleName = "GO 3S",
            role = "right",
            bleAckNs = 88L,
            stopFailureReason = "stop record acked without uri",
        )

        assertThat(ep.resolve("cam_wrist_right.pending.tentative.json").exists()).isFalse()
        val sidecar = Insta360PendingSidecar.scan(ep).single()
        assertThat(sidecar.cameraFileURI).isEqualTo(Insta360PendingSidecar.unresolvedCameraFileURI)
        assertThat(sidecar.stopFailureReason).isEqualTo("stop record acked without uri")
    }
}
