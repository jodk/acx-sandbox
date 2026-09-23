package com.jodk.acx.common.sandbox;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxPhase;
import com.jodk.acx.api.v1alpha1.SandboxSpec;
import com.jodk.acx.api.v1alpha1.SandboxStatus;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.OwnerReference;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxUtilsTest {

    private static Sandbox sandbox() {
        Sandbox sandbox = new Sandbox();
        sandbox.getMetadata().setNamespace("ns");
        sandbox.getMetadata().setName("sbx-1");
        sandbox.getMetadata().setUid("uid-1");
        return sandbox;
    }

    private static Sandbox ready(Sandbox sandbox) {
        SandboxStatus status = new SandboxStatus();
        Condition ready = new Condition();
        ready.setType("Ready");
        ready.setStatus("True");
        status.setConditions(new ArrayList<>(List.of(ready)));
        sandbox.setStatus(status);
        return sandbox;
    }

    @Test
    void sandboxId() {
        assertEquals("ns--sbx-1", SandboxUtils.getSandboxId(sandbox()));
    }

    @Test
    void controlledBySandboxSet() {
        Sandbox sandbox = sandbox();
        assertFalse(SandboxUtils.isControlledBySandboxSet(sandbox));
        OwnerReference ref = new OwnerReference();
        ref.setKind("SandboxSet");
        ref.setApiVersion("agents.kruise.io/v1alpha1");
        ref.setController(true);
        sandbox.getMetadata().setOwnerReferences(new ArrayList<>(List.of(ref)));
        assertTrue(SandboxUtils.isControlledBySandboxSet(sandbox));
    }

    @Test
    void readyCondition() {
        Sandbox sandbox = sandbox();
        assertFalse(SandboxUtils.isSandboxReady(sandbox));
        ready(sandbox);
        assertTrue(SandboxUtils.isSandboxReady(sandbox));
    }

    @Test
    void stateDerivation() {
        Sandbox deleted = ready(sandbox());
        deleted.getMetadata().setDeletionTimestamp("2020-01-01T00:00:00Z");
        assertEquals(ApiConstants.SANDBOX_STATE_DEAD, SandboxUtils.getSandboxState(deleted).state());

        Sandbox pending = sandbox();
        pending.setStatus(new SandboxStatus());
        pending.getStatus().setPhase(SandboxPhase.Pending);
        assertEquals(ApiConstants.SANDBOX_STATE_CREATING, SandboxUtils.getSandboxState(pending).state());

        Sandbox available = ready(sandbox());
        OwnerReference ref = new OwnerReference();
        ref.setKind("SandboxSet");
        ref.setApiVersion("agents.kruise.io/v1alpha1");
        ref.setController(true);
        available.getMetadata().setOwnerReferences(new ArrayList<>(List.of(ref)));
        available.getStatus().setPhase(SandboxPhase.Running);
        assertEquals(ApiConstants.SANDBOX_STATE_AVAILABLE, SandboxUtils.getSandboxState(available).state());

        Sandbox running = ready(sandbox());
        running.getStatus().setPhase(SandboxPhase.Running);
        assertEquals(ApiConstants.SANDBOX_STATE_RUNNING, SandboxUtils.getSandboxState(running).state());

        Sandbox paused = ready(sandbox());
        paused.getStatus().setPhase(SandboxPhase.Running);
        paused.setSpec(new SandboxSpec());
        paused.getSpec().setPaused(true);
        assertEquals(ApiConstants.SANDBOX_STATE_PAUSED, SandboxUtils.getSandboxState(paused).state());
    }
}
