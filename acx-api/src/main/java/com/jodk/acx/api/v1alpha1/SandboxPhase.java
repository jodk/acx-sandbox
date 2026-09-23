package com.jodk.acx.api.v1alpha1;

/** Sandbox lifecycle phase. */
public enum SandboxPhase {
    Pending,
    Running,
    Paused,
    Resuming,
    Succeeded,
    Failed,
    Terminating,
    Restoring
}
