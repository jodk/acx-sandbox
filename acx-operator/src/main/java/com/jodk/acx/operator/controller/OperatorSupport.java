package com.jodk.acx.operator.controller;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxSet;
import com.jodk.acx.api.v1alpha1.SandboxSpec;
import com.jodk.acx.api.v1alpha1.SandboxTemplate;
import com.jodk.acx.api.v1alpha1.SandboxTemplateRef;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared helpers for the operator reconcilers. */
final class OperatorSupport {

    static final String SANDBOX_FINALIZER = "agents.kruise.io/sandbox";

    private OperatorSupport() {
    }

    static OwnerReference controllerOwner(String apiVersion, String kind, String name, String uid) {
        return new OwnerReferenceBuilder()
                .withApiVersion(apiVersion)
                .withKind(kind)
                .withName(name)
                .withUid(uid)
                .withController(true)
                .build();
    }

    static Sandbox newSandboxFromSandboxSet(KubernetesClient client, SandboxSet set) {
        Sandbox sbx = new Sandbox();
        if (sbx.getSpec() == null) {
            sbx.setSpec(new SandboxSpec());
        }
        sbx.getMetadata().setGenerateName(set.getMetadata().getName() + "-");
        sbx.getMetadata().setNamespace(set.getMetadata().getNamespace());
        sbx.getMetadata().setLabels(new HashMap<>());
        sbx.getMetadata().getLabels().put(ApiConstants.LABEL_SANDBOX_POOL, set.getMetadata().getName());
        sbx.getMetadata().getLabels().put(ApiConstants.LABEL_SANDBOX_TEMPLATE, set.getMetadata().getName());
        sbx.getMetadata().getLabels().put(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE);
        sbx.getMetadata().setOwnerReferences(List.of(
                controllerOwner("agents.kruise.io/v1alpha1", "SandboxSet",
                        set.getMetadata().getName(), set.getMetadata().getUid())));

        sbx.getSpec().setTemplateRef(set.getSpec().getTemplateRef());
        sbx.getSpec().setTemplate(set.getSpec().getTemplate());
        sbx.getSpec().setVolumeClaimTemplates(set.getSpec().getVolumeClaimTemplates());
        sbx.getSpec().setRuntimes(set.getSpec().getRuntimes());
        sbx.getSpec().setPersistentContents(set.getSpec().getPersistentContents());
        if (set.getMetadata().getAnnotations() != null) {
            Map<String, String> annotations = set.getMetadata().getAnnotations();
            String hostMounts = annotations.get(ApiConstants.ANNOTATION_HOST_MOUNTS);
            if (hostMounts != null) {
                annotations(sbx).put(ApiConstants.ANNOTATION_HOST_MOUNTS, hostMounts);
            }
            String dynamicRoots = annotations.get(ApiConstants.ANNOTATION_DYNAMIC_ROOTS);
            if (dynamicRoots != null) {
                annotations(sbx).put(ApiConstants.ANNOTATION_DYNAMIC_ROOTS, dynamicRoots);
            }
        }
        return sbx;
    }

    static PodTemplateSpec resolveTemplate(KubernetesClient client, Sandbox sbx) {
        SandboxTemplateRef ref = sbx.getSpec().getTemplateRef();
        if (ref != null) {
            SandboxTemplate template = client.resources(SandboxTemplate.class)
                    .inNamespace(sbx.getMetadata().getNamespace())
                    .withName(ref.getName())
                    .get();
            if (template != null) {
                return template.getSpec().getTemplate();
            }
        }
        return sbx.getSpec().getTemplate();
    }

    static Pod generatePodFromSandbox(KubernetesClient client, Sandbox sbx) {
        PodTemplateSpec template = resolveTemplate(client, sbx);
        Map<String, String> labels = new HashMap<>();
        Map<String, String> annotations = new HashMap<>();
        if (template != null && template.getMetadata() != null) {
            if (template.getMetadata().getLabels() != null) {
                labels.putAll(template.getMetadata().getLabels());
            }
            if (template.getMetadata().getAnnotations() != null) {
                annotations.putAll(template.getMetadata().getAnnotations());
            }
        }

        PodSpec spec = template != null ? template.getSpec() : null;
        spec = SidecarInjector.maybeInject(sbx, spec, System.getenv("AGENT_RUNTIME_IMAGE"));

        return new PodBuilder()
                .withNewMetadata()
                .withName(sbx.getMetadata().getName())
                .withNamespace(sbx.getMetadata().getNamespace())
                .withLabels(labels)
                .withAnnotations(annotations)
                .addToOwnerReferences(controllerOwner("agents.kruise.io/v1alpha1", "Sandbox",
                        sbx.getMetadata().getName(), sbx.getMetadata().getUid()))
                .endMetadata()
                .withSpec(spec)
                .build();
    }

    /** Lazily-initialised annotations map of a Sandbox. */
    static Map<String, String> annotations(Sandbox sbx) {
        if (sbx.getMetadata().getAnnotations() == null) {
            sbx.getMetadata().setAnnotations(new HashMap<>());
        }
        return sbx.getMetadata().getAnnotations();
    }

    static String getAnnotation(Sandbox sbx, String key) {
        return sbx.getMetadata().getAnnotations() != null ? sbx.getMetadata().getAnnotations().get(key) : null;
    }

    /** Removes the controller owner-reference to a SandboxSet, decoupling a claimed sandbox from its pool. */
    static void detachFromPool(Sandbox sbx) {
        if (sbx.getMetadata().getOwnerReferences() != null) {
            sbx.getMetadata().getOwnerReferences().removeIf(ref ->
                    "SandboxSet".equals(ref.getKind()) && "agents.kruise.io/v1alpha1".equals(ref.getApiVersion()));
        }
        if (sbx.getMetadata().getLabels() != null) {
            sbx.getMetadata().getLabels().remove(ApiConstants.LABEL_SANDBOX_POOL);
        }
    }
}
