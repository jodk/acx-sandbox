package com.jodk.acx.manager.mount;

/** Address of a backing pod used to reach its agent-runtime sidecar. */
public record PodKey(String namespace, String name, String uid, String podIp) {
}
