package com.jodk.acx.manager.model;

import java.util.List;

/** Rich sandbox representation for the management UI. */
public class SandboxView {
    public String sandboxID;
    public String name;
    public String namespace;
    public String pool;
    public String image;
    public String phase;
    public String state;
    public String reason;
    public String owner;
    public boolean claimed;
    public boolean paused;
    public String podIP;
    public String nodeName;
    public String nodeIP;
    public String createdAt;
    public String shutdownTime;
    public String adjust;
    public List<String> mounts;
    public boolean agentRuntime;
    /** Host roots injected into the sidecar at pod creation (the only dirs a bind mount can reach). */
    public List<String> hostMounts;
    /** Extra app-container paths where the shared dynamic-mount tree is surfaced (e.g. /data). */
    public List<String> dynamicRoots;
    public List<ActiveMount> activeMounts;

    public SandboxView() {
    }
}
