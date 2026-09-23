package com.jodk.acx.operator.controller;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.PodInfo;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxPhase;
import com.jodk.acx.api.v1alpha1.SandboxStatus;
import io.fabric8.kubernetes.api.model.Condition;
import io.fabric8.kubernetes.api.model.ConditionBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.javaoperatorsdk.operator.api.reconciler.Cleaner;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Manages the backing pod lifecycle of a Sandbox.
 *
 * <p>Deletion is handled through JOSDK's {@link Cleaner} finalizer support: once a Sandbox is
 * marked for deletion ({@code deletionTimestamp}), the framework invokes {@link #cleanup} and then
 * removes the {@code agents.kruise.io/sandbox} finalizer by itself. Without implementing
 * {@code Cleaner} the framework never runs on deletion, so a finalizer added manually would block
 * the Sandbox (and its running pod) forever.
 *
 * <p>Adjustment intent is annotation-driven ({@code agents.kruise.io/adjust}):
 * <ul>
 *   <li>{@code inplace} — patch the running pod's container image(s) from the sandbox template
 *       (pod name / uid / ip stay, the container is restarted by the kubelet);</li>
 *   <li>{@code rebuild} — delete and recreate the pod so spec changes that Kubernetes forbids
 *       in place (new mounts, env, resources, command) take effect.</li>
 * </ul>
 */
@ControllerConfiguration(name = "sandbox", generationAwareEventProcessing = false,
        finalizerName = OperatorSupport.SANDBOX_FINALIZER)
public class SandboxReconciler implements Reconciler<Sandbox>, Cleaner<Sandbox> {

    private static final Duration POD_SETTLE = Duration.ofSeconds(4);

    private final KubernetesClient client;

    public SandboxReconciler(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public DeleteControl cleanup(Sandbox sandbox, Context<Sandbox> context) {
        String namespace = sandbox.getMetadata().getNamespace();
        String name = sandbox.getMetadata().getName();
        try {
            // The backing pod shares the sandbox name; delete it so the app is really stopped.
            // The pod also carries a controller owner-reference to the Sandbox, so Kubernetes GC
            // would remove it once the finalizer is dropped — this makes teardown immediate.
            client.pods().inNamespace(namespace).withName(name).delete();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw e;
            }
        }
        return DeleteControl.defaultDelete();
    }

    @Override
    public UpdateControl<Sandbox> reconcile(Sandbox sandbox, Context<Sandbox> context) {
        String namespace = sandbox.getMetadata().getNamespace();
        String name = sandbox.getMetadata().getName();

        if (isInPast(sandbox.getSpec().getShutdownTime())) {
            client.resources(Sandbox.class).inNamespace(namespace).withName(name).delete();
            return UpdateControl.noUpdate();
        }

        if (Boolean.TRUE.equals(sandbox.getSpec().getPaused())) {
            Pod existing = client.pods().inNamespace(namespace).withName(name).get();
            boolean podGone = existing == null;
            if (!podGone) {
                client.pods().inNamespace(namespace).withName(name).delete();
            }
            SandboxStatus st = status(sandbox);
            if (podGone && st.getPhase() == SandboxPhase.Paused) {
                return UpdateControl.<Sandbox>noUpdate().rescheduleAfter(POD_SETTLE);
            }
            st.setPhase(SandboxPhase.Paused);
            return UpdateControl.patchStatus(sandbox);
        }

        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        String adjust = OperatorSupport.getAnnotation(sandbox, ApiConstants.ANNOTATION_SANDBOX_ADJUST);

        if (ApiConstants.ADJUST_REBUILD.equals(adjust)) {
            if (pod != null) {
                client.pods().inNamespace(namespace).withName(name).delete();
            }
            clearAdjust(sandbox);
            status(sandbox).setPhase(SandboxPhase.Pending);
            // The pod name stays occupied until the old pod is fully gone; the next
            // scheduled reconcile creates the replacement pod from the updated template.
            return patchedAndPoll(sandbox);
        }

        if (ApiConstants.ADJUST_INPLACE.equals(adjust)) {
            applyInPlace(pod, sandbox);
            return patchedAndPoll(sandbox);
        }

        if (pod == null) {
            createPod(sandbox);
            status(sandbox).setPhase(SandboxPhase.Pending);
            return patchedAndPoll(sandbox);
        }

        if ("Running".equals(pod.getStatus().getPhase())) {
            boolean ready = podReady(pod);
            String podIp = pod.getStatus().getPodIP();
            String nodeName = pod.getSpec().getNodeName();
            SandboxStatus status = status(sandbox);
            boolean alreadyMarked = status.getPhase() == SandboxPhase.Running
                    && Objects.equals(status.getSandboxIp(), podIp)
                    && Objects.equals(status.getNodeName(), nodeName)
                    && statusReadyMatches(status, ready);
            if (alreadyMarked) {
                // Nothing changed since the last write: settle on a slow poll instead of
                // patching status forever (a patch would re-trigger reconciliation).
                return UpdateControl.<Sandbox>noUpdate().rescheduleAfter(POD_SETTLE);
            }
            status.setPhase(SandboxPhase.Running);
            status.setNodeName(nodeName);
            status.setSandboxIp(podIp);
            PodInfo podInfo = new PodInfo();
            podInfo.setAnnotations(pod.getMetadata().getAnnotations());
            podInfo.setLabels(pod.getMetadata().getLabels());
            podInfo.setNodeName(nodeName);
            podInfo.setPodIP(podIp);
            podInfo.setPodUID(pod.getMetadata().getUid());
            status.setPodInfo(podInfo);
            status.setConditions(java.util.List.of(readyCondition(ready)));
            UpdateControl<Sandbox> control = UpdateControl.patchStatus(sandbox);
            return control.rescheduleAfter(POD_SETTLE);
        }

        // Pod still pending/container-creating: come back shortly.
        return UpdateControl.<Sandbox>noUpdate().rescheduleAfter(POD_SETTLE);
    }

    /** In-place image adjustment: patch image on the existing pod (kubelet restarts the container). */
    private void applyInPlace(Pod pod, Sandbox sandbox) {
        String namespace = sandbox.getMetadata().getNamespace();
        String name = sandbox.getMetadata().getName();
        PodTemplateSpec template = OperatorSupport.resolveTemplate(client, sandbox);
        if (template == null || template.getSpec() == null) {
            clearAdjust(sandbox);
            return;
        }
        if (pod == null) {
            createPod(sandbox);
        } else {
            java.util.List<Container> desired = template.getSpec().getContainers();
            client.pods().inNamespace(namespace).withName(name).edit(p -> {
                if (desired != null) {
                    java.util.List<Container> current = p.getSpec().getContainers();
                    for (int i = 0; i < desired.size() && i < current.size(); i++) {
                        String image = desired.get(i).getImage();
                        if (image != null) {
                            current.get(i).setImage(image);
                        }
                    }
                }
                return p;
            });
        }
        clearAdjust(sandbox);
    }

    private void createPod(Sandbox sandbox) {
        String namespace = sandbox.getMetadata().getNamespace();
        String name = sandbox.getMetadata().getName();
        client.pods().inNamespace(namespace)
                .resource(OperatorSupport.generatePodFromSandbox(client, sandbox))
                .create();
    }

    private void clearAdjust(Sandbox sandbox) {
        String namespace = sandbox.getMetadata().getNamespace();
        String name = sandbox.getMetadata().getName();
        client.resources(Sandbox.class).inNamespace(namespace).withName(name).edit(s -> {
            if (s.getMetadata().getAnnotations() != null) {
                s.getMetadata().getAnnotations().remove(ApiConstants.ANNOTATION_SANDBOX_ADJUST);
            }
            return s;
        });
    }

    private static boolean podReady(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getConditions() == null) {
            return false;
        }
        for (PodCondition condition : pod.getStatus().getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return "True".equals(condition.getStatus());
            }
        }
        return false;
    }

    /** Whether the persisted Ready condition already reflects {@code ready}. */
    private static boolean statusReadyMatches(SandboxStatus status, boolean ready) {
        if (status.getConditions() == null) {
            return false;
        }
        for (Condition condition : status.getConditions()) {
            if ("Ready".equals(condition.getType())) {
                return ("True".equals(condition.getStatus())) == ready;
            }
        }
        return false;
    }

    private static UpdateControl<Sandbox> patchedAndPoll(Sandbox sandbox) {
        return UpdateControl.<Sandbox>patchStatus(sandbox).rescheduleAfter(POD_SETTLE);
    }

    private static Condition readyCondition(boolean ready) {
        return new ConditionBuilder()
                .withType("Ready")
                .withStatus(ready ? "True" : "False")
                .withLastTransitionTime(Instant.now().toString())
                .withReason(ready ? "PodReady" : "PodNotReady")
                .withMessage(ready ? "Backing pod is ready" : "Backing pod is not ready")
                .build();
    }

    private static SandboxStatus status(Sandbox sandbox) {
        if (sandbox.getStatus() == null) {
            sandbox.setStatus(new SandboxStatus());
        }
        return sandbox.getStatus();
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
