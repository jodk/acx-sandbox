package com.jodk.acx.operator.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.RuntimeConfig;
import com.jodk.acx.api.v1alpha1.Sandbox;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Route-A sidecar injection, applied when a Sandbox is materialised into a backing Pod.
 *
 * <p>The injection model is:
 * <ul>
 *   <li>a privileged {@code agent-runtime} container sharing an emptyDir with the app container;</li>
 *   <li>the sidecar's share mount is {@code Bidirectional} (only privileged containers may request it),
 *       the app container's share mount is {@code HostToContainer};</li>
 *   <li>dynamic mounts are created by the sidecar under {@code /mnt/envd/volumes} and propagate into the
 *       running app container — the Pod spec never changes afterwards.</li>
 * </ul>
 * Every mutation happens on a deep copy of the template PodSpec so the Sandbox/Template CR is untouched.
 */
final class SidecarInjector {

    private static final String HOST_TO_CONTAINER = "HostToContainer";
    private static final String BIDIRECTIONAL = "Bidirectional";

    private SidecarInjector() {
    }

    /** Whether the sandbox requests the agent-runtime sidecar via {@code spec.runtimes}. */
    static boolean enabled(Sandbox sbx) {
        if (sbx == null || sbx.getSpec() == null || sbx.getSpec().getRuntimes() == null) {
            return false;
        }
        for (RuntimeConfig runtime : sbx.getSpec().getRuntimes()) {
            if (runtime != null && ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME.equals(runtime.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deep-copies {@code spec} and injects the sidecar when enabled; otherwise returns the caller's
     * object untouched (no copy needed). {@code configuredImage} comes from the operator env
     * {@code AGENT_RUNTIME_IMAGE}; falls back to {@link AgentRuntimeDefaults#DEFAULT_IMAGE}.
     */
    static PodSpec maybeInject(Sandbox sbx, PodSpec spec, String configuredImage) {
        if (spec == null || !enabled(sbx)) {
            return spec;
        }
        PodSpec copy = SERIALIZATION.clone(spec);
        inject(copy, configuredImage, hostMountsOf(sbx), dynamicRootsOf(sbx));
        return copy;
    }

    private static final KubernetesSerialization SERIALIZATION = new KubernetesSerialization();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static void inject(PodSpec spec, String configuredImage, List<String> hostRoots,
                               List<String> dynamicRoots) {
        ensureShareVolume(spec);
        ensureHostVolumes(spec, hostRoots);
        ensureRuntimeContainer(spec, configuredImage);
        ensureHostMountsOnSidecar(spec, hostRoots);
        ensureAppContainerSeesMounts(spec, dynamicRoots);
    }

    /**
     * Node host roots (from the {@code agents.kruise.io/host-mounts} annotation, JSON array) that are
     * exposed to the agent-runtime sidecar as hostPath volumes so the bind executor can dynamically
     * {@code mount --bind} sub-directories of an already-mounted host path (e.g. an existing NFS
     * export on the worker node) into the shared mount tree.
     */
    static List<String> hostMountsOf(Sandbox sbx) {
        if (sbx.getMetadata() == null || sbx.getMetadata().getAnnotations() == null) {
            return List.of();
        }
        String raw = sbx.getMetadata().getAnnotations().get(ApiConstants.ANNOTATION_HOST_MOUNTS);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (IOException e) {
            // tolerate a plain comma-separated annotation value
            return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    /**
     * Extra app-container paths (from the {@code agents.kruise.io/dynamic-roots} annotation, JSON
     * array) where the shared dynamic-mount volume is additionally surfaced with HostToContainer
     * propagation, so dynamic mounts appear at familiar container paths without a pod rebuild.
     */
    static List<String> dynamicRootsOf(Sandbox sbx) {
        if (sbx.getMetadata() == null || sbx.getMetadata().getAnnotations() == null) {
            return List.of();
        }
        String raw = sbx.getMetadata().getAnnotations().get(ApiConstants.ANNOTATION_DYNAMIC_ROOTS);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
            if (parsed == null) {
                return List.of();
            }
            return parsed.stream().filter(s -> s != null && !s.isBlank()).toList();
        } catch (IOException e) {
            // tolerate a plain comma-separated annotation value
            return java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
    }

    private static void ensureShareVolume(PodSpec spec) {
        List<Volume> volumes = spec.getVolumes() != null ? spec.getVolumes() : new ArrayList<>();
        spec.setVolumes(volumes);
        for (Volume volume : volumes) {
            if (AgentRuntimeDefaults.SHARE_VOLUME_NAME.equals(volume.getName())) {
                return;
            }
        }
        volumes.add(new VolumeBuilder()
                .withName(AgentRuntimeDefaults.SHARE_VOLUME_NAME)
                .withNewEmptyDir()
                .endEmptyDir()
                .build());
    }

    /** Adds a hostPath volume per host root ({@code acx-hostmount-<i>}) so the sidecar can bind it. */
    private static void ensureHostVolumes(PodSpec spec, List<String> hostRoots) {
        if (hostRoots == null || hostRoots.isEmpty()) {
            return;
        }
        List<Volume> volumes = spec.getVolumes() != null ? spec.getVolumes() : new ArrayList<>();
        spec.setVolumes(volumes);
        for (int i = 0; i < hostRoots.size(); i++) {
            String root = hostRoots.get(i);
            if (root == null || root.isBlank()) {
                continue;
            }
            String name = AgentRuntimeDefaults.HOST_VOLUME_PREFIX + i;
            boolean present = false;
            for (Volume volume : volumes) {
                if (name.equals(volume.getName())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                volumes.add(new VolumeBuilder()
                        .withName(name)
                        .withNewHostPath().withPath(root).endHostPath()
                        .build());
            }
        }
    }

    /** Mounts each injected host root into the sidecar container at the same absolute path. */
    private static void ensureHostMountsOnSidecar(PodSpec spec, List<String> hostRoots) {
        if (hostRoots == null || hostRoots.isEmpty()) {
            return;
        }
        Container sidecar = containerByName(spec, AgentRuntimeDefaults.CONTAINER_NAME);
        if (sidecar == null) {
            return;
        }
        List<VolumeMount> mounts = sidecar.getVolumeMounts() != null ? sidecar.getVolumeMounts() : new ArrayList<>();
        sidecar.setVolumeMounts(mounts);
        for (int i = 0; i < hostRoots.size(); i++) {
            String root = hostRoots.get(i);
            if (root == null || root.isBlank()) {
                continue;
            }
            String name = AgentRuntimeDefaults.HOST_VOLUME_PREFIX + i;
            boolean present = false;
            for (VolumeMount mount : mounts) {
                if (name.equals(mount.getName())) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                mounts.add(new VolumeMountBuilder()
                        .withName(name)
                        .withMountPath(root)
                        .build());
            }
        }
    }

    private static Container containerByName(PodSpec spec, String name) {
        if (spec.getContainers() == null) {
            return null;
        }
        for (Container container : spec.getContainers()) {
            if (name.equals(container.getName())) {
                return container;
            }
        }
        return null;
    }

    private static void ensureRuntimeContainer(PodSpec spec, String configuredImage) {
        List<Container> containers = spec.getContainers() != null ? spec.getContainers() : new ArrayList<>();
        spec.setContainers(containers);
        for (Container container : containers) {
            if (AgentRuntimeDefaults.CONTAINER_NAME.equals(container.getName())) {
                return; // a template-provided agent-runtime container wins; do not duplicate
            }
        }
        String image = configuredImage != null && !configuredImage.isBlank()
                ? configuredImage
                : AgentRuntimeDefaults.DEFAULT_IMAGE;
        containers.add(new ContainerBuilder()
                .withName(AgentRuntimeDefaults.CONTAINER_NAME)
                .withImage(image)
                .withNewSecurityContext()
                .withPrivileged(true)
                .endSecurityContext()
                .withVolumeMounts(new VolumeMountBuilder()
                        .withName(AgentRuntimeDefaults.SHARE_VOLUME_NAME)
                        .withMountPath(AgentRuntimeDefaults.SHARE_MOUNT_PATH)
                        .withMountPropagation(BIDIRECTIONAL)
                        .build())
                .build());
    }

    private static void ensureAppContainerSeesMounts(PodSpec spec, List<String> dynamicRoots) {
        List<Container> containers = spec.getContainers();
        if (containers == null || containers.isEmpty()) {
            return;
        }
        Container app = containers.get(0);
        if (AgentRuntimeDefaults.CONTAINER_NAME.equals(app.getName())) {
            return; // no app container to attach to
        }
        List<String> roots = usableDynamicRoots(dynamicRoots);
        if (roots.isEmpty()) {
            // No custom root configured: the classic Route A layout — the shared volume is surfaced
            // at the canonical /mnt/envd mount point, so mounts appear at /mnt/envd/volumes/<mountId>.
            ensureEnv(app, AgentRuntimeDefaults.ENV_MOUNT_ROOT, AgentRuntimeDefaults.MOUNT_ROOT);
            ensureShareMount(app, HOST_TO_CONTAINER);
            return;
        }
        // Custom roots configured: surface the shared volume ONLY at those roots (not also at the
        // default /mnt/envd), so each dynamic mount appears exactly once at <root>/volumes/<mountId>.
        for (String root : roots) {
            ensureShareMountAt(app, root, HOST_TO_CONTAINER);
        }
        ensureEnv(app, AgentRuntimeDefaults.ENV_MOUNT_ROOT, roots.get(0) + "/volumes");
    }

    /** Filters dynamicRoots to absolute, non-{@code /mnt/envd}, de-duplicated mount points. */
    private static List<String> usableDynamicRoots(List<String> dynamicRoots) {
        if (dynamicRoots == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String root : dynamicRoots) {
            if (root == null || root.isBlank()) {
                continue;
            }
            String trimmed = root.trim();
            if (trimmed.startsWith("/") && !AgentRuntimeDefaults.SHARE_MOUNT_PATH.equals(trimmed)
                    && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static void ensureEnv(Container container, String name, String value) {
        List<EnvVar> env = container.getEnv() != null ? container.getEnv() : new ArrayList<>();
        container.setEnv(env);
        for (EnvVar var : env) {
            if (name.equals(var.getName())) {
                return;
            }
        }
        env.add(new EnvVarBuilder().withName(name).withValue(value).build());
    }

    private static void ensureShareMount(Container container, String propagation) {
        List<VolumeMount> mounts = container.getVolumeMounts() != null ? container.getVolumeMounts() : new ArrayList<>();
        container.setVolumeMounts(mounts);
        for (VolumeMount mount : mounts) {
            if (AgentRuntimeDefaults.SHARE_VOLUME_NAME.equals(mount.getName())) {
                return;
            }
        }
        mounts.add(new VolumeMountBuilder()
                .withName(AgentRuntimeDefaults.SHARE_VOLUME_NAME)
                .withMountPath(AgentRuntimeDefaults.SHARE_MOUNT_PATH)
                .withMountPropagation(propagation)
                .build());
    }

    /**
     * Surfaces the shared dynamic-mount volume at an absolute path inside the app container
     * (e.g. {@code /data}). A submount the sidecar creates under the volume root
     * ({@code /mnt/envd/volumes/<mountId>}) propagates to this mount point at the relative path
     * {@code volumes/<mountId>} — so the app can read dynamic mounts at a path it prefers, still
     * without any pod rebuild. When {@code dynamicRoots} is configured these roots are the only
     * places mounts appear (the default {@code /mnt/envd} mount is skipped, see
     * {@link #ensureAppContainerSeesMounts}); otherwise {@code /mnt/envd} remains the single default.
     * Skipped when the path is already taken by another mount.
     */
    private static void ensureShareMountAt(Container container, String path, String propagation) {
        List<VolumeMount> mounts = container.getVolumeMounts() != null ? container.getVolumeMounts() : new ArrayList<>();
        container.setVolumeMounts(mounts);
        for (VolumeMount mount : mounts) {
            if (path.equals(mount.getMountPath())) {
                return; // already mounted (same volume or another) — do not duplicate/conflict
            }
        }
        mounts.add(new VolumeMountBuilder()
                .withName(AgentRuntimeDefaults.SHARE_VOLUME_NAME)
                .withMountPath(path)
                .withMountPropagation(propagation)
                .build());
    }
}
