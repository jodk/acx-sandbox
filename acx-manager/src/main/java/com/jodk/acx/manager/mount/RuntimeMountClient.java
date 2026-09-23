package com.jodk.acx.manager.mount;

import com.jodk.acx.agentruntime.MountResult;
import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;

/**
 * SPI that delivers dynamic-mount operations to whichever runtime executes them.
 *
 * <p>Route A (implemented by {@link SidecarRuntimeMountClient}) reaches the in-pod agent-runtime
 * sidecar through a Kubernetes port-forward. A future Route-B node-level agent only needs a new
 * implementation of this interface speaking the same {@code /v1/mount|umount} contract — nothing
 * else in the manager changes.</p>
 */
public interface RuntimeMountClient {

    MountResult mount(PodKey pod, String driver, CsiNodePublishRequest request);

    MountResult umount(PodKey pod, String volumeId);
}
