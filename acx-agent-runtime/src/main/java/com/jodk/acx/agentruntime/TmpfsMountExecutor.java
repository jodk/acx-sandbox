package com.jodk.acx.agentruntime;

import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;

/**
 * Mounts an anonymous in-memory file system via {@code mount -t tmpfs}. Carries no server state of
 * its own — used to prove the propagation mechanism (a mount appearing inside the shared tree while
 * the pod keeps running) with zero external dependencies.
 */
public final class TmpfsMountExecutor extends AbstractMountExecutor {

    public static final String DRIVER = "tmpfs";

    public TmpfsMountExecutor(MountTracker tracker) {
        super(tracker);
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    @Override
    protected String defaultFsType() {
        return "tmpfs";
    }

    @Override
    protected String sourceOf(CsiNodePublishRequest request) {
        return "tmpfs";
    }
}
