package com.jodk.acx.operator.controller;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxSet;
import com.jodk.acx.api.v1alpha1.SandboxSetStatus;
import com.jodk.acx.common.sandbox.SandboxUtils;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

import java.util.List;

/** Maintains a pool of unused sandboxes for a SandboxSet. */
@ControllerConfiguration(name = "sandboxset", generationAwareEventProcessing = false)
public class SandboxSetReconciler implements Reconciler<SandboxSet> {

    private final KubernetesClient client;

    public SandboxSetReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<SandboxSet> reconcile(SandboxSet set, Context<SandboxSet> context) {
        String namespace = set.getMetadata().getNamespace();
        String setName = set.getMetadata().getName();
        int desired = set.getSpec().getReplicas() == null ? 0 : set.getSpec().getReplicas();

        // Only idle (claimed=false) members still belong to the pool. Sandboxes that were
        // claimed for an application pod are detached from the pool (owner-ref + pool label removed).
        KubernetesResourceList<Sandbox> owned = client.resources(Sandbox.class)
                .inNamespace(namespace)
                .withLabel(ApiConstants.LABEL_SANDBOX_POOL, setName)
                .withLabel(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE)
                .list();
        List<Sandbox> items = owned.getItems();
        int current = items.size();

        if (current < desired) {
            for (int i = current; i < desired; i++) {
                client.resources(Sandbox.class).inNamespace(namespace)
                        .resource(OperatorSupport.newSandboxFromSandboxSet(client, set))
                        .create();
            }
        } else if (current > desired) {
            for (int i = 0; i < current - desired; i++) {
                client.resources(Sandbox.class).inNamespace(namespace)
                        .resource(items.get(i)).delete();
            }
        }

        int available = 0;
        for (Sandbox member : items) {
            if (SandboxUtils.isSandboxReady(member)) {
                available++;
            }
        }

        SandboxSetStatus status = new SandboxSetStatus();
        status.setReplicas(desired);
        status.setAvailableReplicas(available);
        status.setSelector(ApiConstants.LABEL_SANDBOX_POOL + "=" + setName + "," + ApiConstants.LABEL_SANDBOX_IS_CLAIMED + "=false");
        status.setObservedGeneration(set.getMetadata().getGeneration());
        set.setStatus(status);
        return UpdateControl.patchStatus(set);
    }
}
