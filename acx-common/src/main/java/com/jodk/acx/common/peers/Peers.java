package com.jodk.acx.common.peers;

import java.util.List;

/** Peer discovery abstraction (memberlist gossip replaced by K8s pod listing). */
public interface Peers {

    /** Alive peers, excluding self. */
    List<Peer> getPeers();

    default List<Peer> getAllMembers() {
        return getPeers();
    }
}
