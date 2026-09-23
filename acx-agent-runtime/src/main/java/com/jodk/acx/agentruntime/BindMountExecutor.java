package com.jodk.acx.agentruntime;

import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;

import java.util.List;
import java.util.Map;

/**
 * Binds a directory that is already visible inside the sidecar container into the shared dynamic
 * mount tree via {@code mount --bind}. The source is a node host root that the operator exposed to
 * the agent-runtime sidecar through a hostPath volume — typically a directory the worker node
 * already has mounted, such as an existing NFS export (e.g. {@code /gridview-niesl113-10033113}).
 *
 * <p>Because bind mounts carry the source superblock, the app container that receives the propagated
 * mount sees the real file-system type (e.g. {@code nfs4}) and live server data — while requiring
 * <b>no</b> network route from the pod to the storage and <b>no</b> {@code mount.nfs} helper.
 */
public final class BindMountExecutor extends AbstractMountExecutor {

    public static final String DRIVER = "bind";

    public BindMountExecutor(MountTracker tracker) {
        super(tracker);
    }

    @Override
    public String driver() {
        return DRIVER;
    }

    @Override
    protected String defaultFsType() {
        return "bind";
    }

    @Override
    protected void validate(CsiNodePublishRequest request) {
        Map<String, String> ctx = request.getVolumeContext();
        if (ctx == null) {
            throw new IllegalArgumentException("bind mounts require volumeContext with 'path'");
        }
        String path = ctx.get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("volumeContext['path'] is required for bind mounts");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("bind source must be an absolute path, got: " + path);
        }
    }

    @Override
    protected String sourceOf(CsiNodePublishRequest request) {
        return request.getVolumeContext().get("path");
    }

    /**
     * Creates the (possibly nested) source sub-directory before the bind. The source root is a
     * hostPath volume injected into the sidecar at the same absolute path, so creating a subdir here
     * writes through to the worker node — enabling subPath targets such as {@code /data/data1/zdk}
     * that do not exist yet.
     */
    @Override
    protected void prepareSource(CsiNodePublishRequest request) {
        String source = sourceOf(request);
        if (source != null && !source.isBlank()) {
            AbstractMountExecutor.run(List.of("mkdir", "-p", source));
            // Errors are intentionally ignored: if the source still cannot be created, the mount
            // command below reports the real cause (e.g. host root not injected for this pool).
        }
    }

    @Override
    List<String> buildMountCommand(MountRequest mountRequest) {
        CsiNodePublishRequest req = mountRequest.getRequest();
        return List.of("mount", "--bind", sourceOf(req), req.getTargetPath());
    }

    @Override
    protected List<String> postMountCommand(CsiNodePublishRequest request, String target) {
        if (request.isReadOnly()) {
            return List.of("mount", "-o", "remount,bind,ro", target);
        }
        return List.of();
    }
}
