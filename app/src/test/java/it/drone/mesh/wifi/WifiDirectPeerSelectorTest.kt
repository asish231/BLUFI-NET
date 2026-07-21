package it.drone.mesh.wifi

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WifiDirectPeerSelectorTest {
    @Test
    fun `given multiple group owners when selecting one then connected peer wins deterministically`() {
        val peers = listOf(
            WifiDirectPeer("02:00:00:00:00:03", true, WifiDirectPeerState.AVAILABLE),
            WifiDirectPeer("02:00:00:00:00:02", true, WifiDirectPeerState.CONNECTED),
            WifiDirectPeer("02:00:00:00:00:01", true, WifiDirectPeerState.CONNECTED),
        )

        assertEquals("02:00:00:00:00:01", WifiDirectPeerSelector.selectGroupOwner(peers))
    }

    @Test
    fun `given peers without a usable group owner when selecting one then none is returned`() {
        val peers = listOf(
            WifiDirectPeer("02:00:00:00:00:01", false, WifiDirectPeerState.AVAILABLE),
            WifiDirectPeer("02:00:00:00:00:02", true, WifiDirectPeerState.UNAVAILABLE),
            WifiDirectPeer("", true, WifiDirectPeerState.CONNECTED),
        )

        assertNull(WifiDirectPeerSelector.selectGroupOwner(peers))
    }
}