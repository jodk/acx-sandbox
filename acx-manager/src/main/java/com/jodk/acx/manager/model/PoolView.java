package com.jodk.acx.manager.model;

import java.util.List;

/** Summary of a warm pool (SandboxSet) for the management UI. */
public class PoolView {
    public String name;
    public String namespace;
    public int replicas;
    public int available;
    public int creating;
    public int claimed;
    public String image;
    public String templateHash;
    public boolean agentRuntime;
    /** Node host roots exposed to the agent-runtime sidecar (bind dynamic mounts). */
    public List<String> hostMounts;
    /** Extra app-container paths where the shared dynamic-mount tree is surfaced (e.g. /data). */
    public List<String> dynamicRoots;

    public PoolView() {
    }
}
