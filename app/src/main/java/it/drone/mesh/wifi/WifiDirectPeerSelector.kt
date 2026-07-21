package it.drone.mesh.wifi

enum class WifiDirectPeerState {
    CONNECTED,
    INVITED,
    AVAILABLE,
    FAILED,
    UNAVAILABLE,
}

data class WifiDirectPeer(
    val address: String,
    val isGroupOwner: Boolean,
    val state: WifiDirectPeerState,
)

object WifiDirectPeerSelector {
    private val addressPattern = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
    private val stateRank = mapOf(
        WifiDirectPeerState.CONNECTED to 0,
        WifiDirectPeerState.INVITED to 1,
        WifiDirectPeerState.AVAILABLE to 2,
    )

    @JvmStatic
    fun selectGroupOwner(peers: Collection<WifiDirectPeer>): String? = peers
        .asSequence()
        .filter { it.isGroupOwner && addressPattern.matches(it.address) && stateRank.containsKey(it.state) }
        .sortedWith(compareBy<WifiDirectPeer> { stateRank.getValue(it.state) }.thenBy { it.address })
        .map { it.address }
        .firstOrNull()
}