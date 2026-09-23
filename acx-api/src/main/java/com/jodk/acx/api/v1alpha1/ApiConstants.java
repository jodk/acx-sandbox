package com.jodk.acx.api.v1alpha1;

/**
 * Constants for the {@code agents.kruise.io/v1alpha1} API group, ported from the
 * Go constants in api/v1alpha1/*.go.
 */
public final class ApiConstants {

    private ApiConstants() {
    }

    public static final String GROUP = "agents.kruise.io";
    public static final String VERSION = "v1alpha1";

    // sandbox_types.go
    public static final String SANDBOX_HASH_WITHOUT_IMAGE_AND_RESOURCES = "sandbox.agents.kruise.io/hash-without-image-resources";
    public static final String POD_LABEL_TEMPLATE_HASH = "pod-template-hash";
    public static final String SANDBOX_ANNOTATION_PRIORITY = "agents.kruise.io/sandbox-priority";

    public static final String RUNTIME_CONFIG_FOR_INJECT_CSI_MOUNT = "csi";
    public static final String RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME = "agent-runtime";

    public static final String PERSISTENT_CONTENT_IP = "ip";
    public static final String PERSISTENT_CONTENT_MEMORY = "memory";
    public static final String PERSISTENT_CONTENT_FILESYSTEM = "filesystem";

    // sandboxset_types.go
    public static final String INTERNAL_PREFIX = "agents.kruise.io/";

    public static final String LABEL_SANDBOX_POOL = INTERNAL_PREFIX + "sandbox-pool";
    public static final String LABEL_SANDBOX_TEMPLATE = INTERNAL_PREFIX + "sandbox-template";
    public static final String LABEL_SANDBOX_IS_CLAIMED = INTERNAL_PREFIX + "sandbox-claimed";
    public static final String LABEL_SANDBOX_CLAIM_NAME = INTERNAL_PREFIX + "claim-name";
    public static final String LABEL_TEMPLATE_HASH = INTERNAL_PREFIX + "template-hash";

    public static final String ANNOTATION_LOCK = INTERNAL_PREFIX + "lock";
    public static final String ANNOTATION_OWNER = INTERNAL_PREFIX + "owner";
    public static final String ANNOTATION_CLAIM_TIME = INTERNAL_PREFIX + "claim-timestamp";
    public static final String ANNOTATION_INIT_RUNTIME_REQUEST = INTERNAL_PREFIX + "init-runtime-request";
    public static final String ANNOTATION_SANDBOX_ID = INTERNAL_PREFIX + "sandbox-id";

    /** Annotation carrying an explicit pod-adjust intent for the SandboxReconciler. */
    public static final String ANNOTATION_SANDBOX_ADJUST = INTERNAL_PREFIX + "adjust";
    public static final String ADJUST_INPLACE = "inplace";
    public static final String ADJUST_REBUILD = "rebuild";

    public static final String SANDBOX_STATE_CREATING = "creating";
    public static final String SANDBOX_STATE_AVAILABLE = "available";
    public static final String SANDBOX_STATE_RUNNING = "running";
    public static final String SANDBOX_STATE_PAUSED = "paused";
    public static final String SANDBOX_STATE_DEAD = "dead";

    // annotations.go
    public static final String ANNOTATION_RUNTIME_URL = INTERNAL_PREFIX + "runtime-url";
    public static final String ANNOTATION_RUNTIME_ACCESS_TOKEN = INTERNAL_PREFIX + "runtime-access-token";

    /** Sandbox annotation holding the currently active in-pod dynamic mounts (JSON array). */
    public static final String ANNOTATION_ACTIVE_MOUNTS = INTERNAL_PREFIX + "active-mounts";

    /** SandboxSet/Sandbox annotation carrying node host roots exposed to the agent-runtime sidecar
     *  as hostPath volumes (JSON array of absolute node paths, e.g. an existing node NFS mount). */
    public static final String ANNOTATION_HOST_MOUNTS = INTERNAL_PREFIX + "host-mounts";

    /** SandboxSet/Sandbox annotation carrying extra app-container paths where the shared dynamic-mount
     *  tree is surfaced at pod creation (JSON array of absolute container paths, e.g. {@code /data}).
     *  A dynamic mount created under {@code /mnt/envd/volumes/<id>} also appears at
     *  {@code <root>/volumes/<id>} in the app container, so apps can read mounts at a familiar path. */
    public static final String ANNOTATION_DYNAMIC_ROOTS = INTERNAL_PREFIX + "dynamic-roots";

    public static final String E2B_PREFIX = "e2b." + INTERNAL_PREFIX;
    public static final String ANNOTATION_ENVD_ACCESS_TOKEN = E2B_PREFIX + "envd-access-token";
    public static final String ANNOTATION_ENVD_URL = E2B_PREFIX + "envd-url";

    public static final String TRUE = "true";
    public static final String FALSE = "false";
}
