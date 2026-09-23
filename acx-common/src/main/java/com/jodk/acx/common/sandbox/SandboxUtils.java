package com.jodk.acx.common.sandbox;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxPhase;
import com.jodk.acx.api.v1alpha1.SandboxSpec;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.OwnerReference;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/** State derivation for {@link Sandbox} resources, ported from pkg/utils/sandboxutils. */
public final class SandboxUtils {

    private SandboxUtils() {
    }

    public static SandboxStateInfo getSandboxState(Sandbox sandbox) {
        if (sandbox.getMetadata().getDeletionTimestamp() != null) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "ResourceDeleted");
        }
        String shutdownTime = spec(sandbox).getShutdownTime();
        if (isInPast(shutdownTime)) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "ShutdownTimeReached");
        }

        SandboxPhase phase = sandbox.getStatus() != null ? sandbox.getStatus().getPhase() : null;
        if (phase == SandboxPhase.Pending) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_CREATING, "ResourcePending");
        }
        if (phase == SandboxPhase.Succeeded) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "ResourceSucceeded");
        }
        if (phase == SandboxPhase.Failed) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "ResourceFailed");
        }
        if (phase == SandboxPhase.Terminating) {
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "ResourceTerminating");
        }

        boolean ready = isSandboxReady(sandbox);
        if (isControlledBySandboxSet(sandbox)) {
            if (ready) {
                return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_AVAILABLE, "ResourceControlledBySbsAndReady");
            }
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_CREATING, "ResourceControlledBySbsButNotReady");
        }

        if (phase == SandboxPhase.Running) {
            if (Boolean.TRUE.equals(spec(sandbox).getPaused())) {
                return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_PAUSED, "RunningResourceClaimedAndPaused");
            }
            if (ready) {
                return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_RUNNING, "RunningResourceClaimedAndReady");
            }
            return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_DEAD, "RunningResourceClaimedButNotReady");
        }
        // Paused and Resuming phases are both treated as paused state
        return new SandboxStateInfo(ApiConstants.SANDBOX_STATE_PAUSED, "NotRunningResourceClaimed");
    }

    public static boolean isControlledBySandboxSet(Sandbox sandbox) {
        if (sandbox.getMetadata().getOwnerReferences() == null) {
            return false;
        }
        for (OwnerReference ref : sandbox.getMetadata().getOwnerReferences()) {
            if ("SandboxSet".equals(ref.getKind()) && "agents.kruise.io/v1alpha1".equals(ref.getApiVersion())) {
                return true;
            }
        }
        return false;
    }

    public static String getSandboxId(Sandbox sandbox) {
        return sandbox.getMetadata().getNamespace() + "--" + sandbox.getMetadata().getName();
    }

    public static boolean isSandboxReady(Sandbox sandbox) {
        if (sandbox.getStatus() == null || sandbox.getStatus().getConditions() == null) {
            return false;
        }
        for (Condition condition : sandbox.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equals(condition.getStatus());
            }
        }
        return false;
    }

    private static SandboxSpec spec(Sandbox sandbox) {
        return sandbox.getSpec() != null ? sandbox.getSpec() : new SandboxSpec();
    }

    private static boolean isInPast(String timeString) {
        if (timeString == null || timeString.isEmpty()) {
            return false;
        }
        try {
            return Instant.parse(timeString).isBefore(Instant.now());
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
