package dev.omalauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncthingSupervisorTest {
    @Test fun connectedPeer() = assertEquals(
        true,
        anySyncthingPeerConnected("""{"connections":{"a":{"connected":false},"b":{"connected":true}}}"""),
    )

    @Test fun disconnectedPeer() = assertEquals(
        false,
        anySyncthingPeerConnected("""{"connections":{"a":{"connected":false}}}"""),
    )

    @Test fun noPeers() = assertEquals(false, anySyncthingPeerConnected("""{"connections":{}}"""))

    @Test fun corruptResponse() = assertEquals(null, anySyncthingPeerConnected("not JSON"))
}
