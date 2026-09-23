package com.jodk.acx.api.v1alpha1;

/**
 * Well-known names/ports of the injected agent-runtime sidecar. Single source of truth shared by
 * the operator (injection), the manager (mount client) and the sidecar itself.
 */
public final class AgentRuntimeDefaults {

    private AgentRuntimeDefaults() {
    }

    /** TCP port the agent-runtime HTTP service listens on inside the pod. */
    public static final int PORT = 49983;

    /** Container name injected into a sandbox pod when agent-runtime is enabled. */
    public static final String CONTAINER_NAME = "agent-runtime";

    /** Shared emptyDir volume carrying the runtime and the dynamic mount tree. */
    public static final String SHARE_VOLUME_NAME = "agent-runtime-share";

    /** Mount path of the shared volume inside every participating container. */
    public static final String SHARE_MOUNT_PATH = "/mnt/envd";

    /** Dynamic mounts are created under this sub-tree so they propagate into the app container. */
    public static final String MOUNT_ROOT = "/mnt/envd/volumes";

    /** Env var set on the app container pointing at {@link #MOUNT_ROOT}. */
    public static final String ENV_MOUNT_ROOT = "ACX_MOUNT_ROOT";

    /** Default sidecar image; overridable via the operator env AGENT_RUNTIME_IMAGE. */
    public static final String DEFAULT_IMAGE = "image.ac.com:5000/acx/acx-agent-runtime:latest";

    /** Volume-name prefix for hostPath roots injected into the agent-runtime sidecar only. */
    public static final String HOST_VOLUME_PREFIX = "acx-hostmount-";
}
