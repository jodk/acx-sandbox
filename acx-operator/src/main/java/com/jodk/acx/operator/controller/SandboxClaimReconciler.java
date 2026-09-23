package com.jodk.acx.operator.controller;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxClaim;
import com.jodk.acx.api.v1alpha1.SandboxClaimPhase;
import com.jodk.acx.api.v1alpha1.SandboxClaimStatus;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Claims a batch of sandboxes from a SandboxSet pool. */
@ControllerConfiguration(name = "sandboxclaim", generationAwareEventProcessing = false)
public class SandboxClaimReconciler implements Reconciler<SandboxClaim> {

    private final KubernetesClient client;

    public SandboxClaimReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public UpdateControl<SandboxClaim> reconcile(SandboxClaim claim, Context<SandboxClaim> context) {
        String namespace = claim.getMetadata().getNamespace();
        String claimName = claim.getMetadata().getName();
        int desired = claim.getSpec().getReplicas() == null ? 1 : claim.getSpec().getReplicas();

        SandboxClaimStatus status = status(claim);
        status.setPhase(SandboxClaimPhase.Claiming);

        KubernetesResourceList<Sandbox> claimed = client.resources(Sandbox.class).inNamespace(namespace)
                .withLabel(ApiConstants.LABEL_SANDBOX_CLAIM_NAME, claimName).list();
        int claimedCount = claimed.getItems().size();
        status.setClaimedReplicas(claimedCount);

        if (claimedCount >= desired) {
            status.setPhase(SandboxClaimPhase.Completed);
            status.setMessage("claimed " + desired + " sandboxes");
            return UpdateControl.patchStatus(claim);
        }

        KubernetesResourceList<Sandbox> available = client.resources(Sandbox.class).inNamespace(namespace)
                .withLabel(ApiConstants.LABEL_SANDBOX_TEMPLATE, claim.getSpec().getTemplateName())
                .withLabel(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE)
                .list();
        List<Sandbox> candidates = available.getItems();

        int remaining = desired - claimedCount;
        for (Sandbox candidate : candidates) {
            if (remaining <= 0) {
                break;
            }
            client.resources(Sandbox.class).inNamespace(namespace)
                    .withName(candidate.getMetadata().getName())
                    .edit(s -> markClaimed(s, claim));
            remaining--;
        }
        return UpdateControl.patchStatus(claim);
    }

    private static Sandbox markClaimed(Sandbox sandbox, SandboxClaim claim) {
        Map<String, String> labels = sandbox.getMetadata().getLabels();
        if (labels == null) {
            labels = new HashMap<>();
            sandbox.getMetadata().setLabels(labels);
        }
        labels.put(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.TRUE);
        labels.put(ApiConstants.LABEL_SANDBOX_CLAIM_NAME, claim.getMetadata().getName());
        // Once claimed, the sandbox no longer belongs to the pool it was created in:
        // the pool controller must neither count nor garbage-collect it.
        OperatorSupport.detachFromPool(sandbox);

        Map<String, String> annotations = sandbox.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            sandbox.getMetadata().setAnnotations(annotations);
        }
        annotations.put(ApiConstants.ANNOTATION_OWNER, claim.getMetadata().getUid());
        annotations.put(ApiConstants.ANNOTATION_CLAIM_TIME, java.time.Instant.now().toString());

        if (claim.getSpec().getShutdownTime() != null) {
            sandbox.getSpec().setShutdownTime(claim.getSpec().getShutdownTime());
        }
        return sandbox;
    }

    private static SandboxClaimStatus status(SandboxClaim claim) {
        if (claim.getStatus() == null) {
            claim.setStatus(new SandboxClaimStatus());
        }
        return claim.getStatus();
    }
}
