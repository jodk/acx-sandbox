package com.jodk.acx.agentruntime;

/**
 * SPI implemented by every dynamic-mount executor.
 *
 * <p>Today both registered executors run inside the pod (Route A). A future Route-B node-level
 * agent serves the same {@code mount}/{@code umount} contract from the host — it only needs a new
 * {@code RuntimeMountClient} on the manager side and a new {@code MountExecutor} here.</p>
 */
public interface MountExecutor {

    /** Driver key used to route {@link MountRequest}s, e.g. {@code bind} or {@code tmpfs}. */
    String driver();

    MountResult mount(MountRequest request);

    MountResult umount(String volumeId);
}
