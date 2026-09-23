package com.jodk.acx.operator.controller;

import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.RuntimeConfig;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxSpec;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SidecarInjectorTest {

    private static final String SIDECAR_IMAGE = "image.ac.com:5000/acx/acx-agent-runtime:test";

    private static PodSpec nginxSpec() {
        Container nginx = new ContainerBuilder()
                .withName("sandbox")
                .withImage("image.ac.com:5000/acx/sbx-nginx:1.27-alpine")
                .build();
        return new PodSpecBuilder().withContainers(nginx).build();
    }

    private static Sandbox sandboxWithRuntimes(String... names) {
        Sandbox sbx = new Sandbox();
        sbx.setSpec(new SandboxSpec());
        sbx.getSpec().setRuntimes(List.of());
        sbx.getSpec().setRuntimes(java.util.stream.Stream.of(names).map(RuntimeConfig::new).toList());
        return sbx;
    }

    @Test
    void injectsSidecarAndMountsWithoutMutatingTemplateSpec() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        PodSpec original = nginxSpec();
        PodSpec injected = SidecarInjector.maybeInject(sbx, original, SIDECAR_IMAGE);

        assertNotSame(original, injected, "injection must operate on a deep copy");

        // original template spec stays untouched
        assertEquals(1, original.getContainers().size());
        assertEquals("sandbox", original.getContainers().get(0).getName());
        assertTrue(original.getContainers().get(0).getEnv() == null || original.getContainers().get(0).getEnv().isEmpty());
        assertTrue(original.getContainers().get(0).getVolumeMounts() == null
                || original.getContainers().get(0).getVolumeMounts().isEmpty());
        assertTrue(original.getVolumes() == null || original.getVolumes().isEmpty());

        assertEquals(2, injected.getContainers().size());
        Container app = injected.getContainers().get(0);
        Container runtime = injected.getContainers().get(1);

        // shared emptyDir volume
        assertEquals("agent-runtime-share", injected.getVolumes().get(0).getName());

        // privileged sidecar with Bidirectional propagation on the shared volume
        assertEquals("agent-runtime", runtime.getName());
        assertEquals(SIDECAR_IMAGE, runtime.getImage());
        assertEquals(Boolean.TRUE, runtime.getSecurityContext().getPrivileged());
        VolumeMount runtimeMount = runtime.getVolumeMounts().get(0);
        assertEquals("agent-runtime-share", runtimeMount.getName());
        assertEquals("/mnt/envd", runtimeMount.getMountPath());
        assertEquals("Bidirectional", runtimeMount.getMountPropagation());

        // app container gets the mount-root env + HostToContainer propagation
        EnvVar mountRoot = app.getEnv().stream()
                .filter(e -> e.getName().equals("ACX_MOUNT_ROOT"))
                .findFirst().orElseThrow();
        assertEquals("/mnt/envd/volumes", mountRoot.getValue());
        VolumeMount appMount = app.getVolumeMounts().stream()
                .filter(m -> m.getName().equals("agent-runtime-share"))
                .findFirst().orElseThrow();
        assertEquals("/mnt/envd", appMount.getMountPath());
        assertEquals("HostToContainer", appMount.getMountPropagation());
    }

    @Test
    void disabledReturnsOriginalObjectUntouched() {
        Sandbox sbx = sandboxWithRuntimes("csi");
        PodSpec original = nginxSpec();
        PodSpec result = SidecarInjector.maybeInject(sbx, original, SIDECAR_IMAGE);
        assertSame(original, result);
        assertEquals(1, result.getContainers().size());
        assertTrue(result.getContainers().get(0).getVolumeMounts() == null
                || result.getContainers().get(0).getVolumeMounts().isEmpty());
    }

    @Test
    void imageDefaultsWhenEnvMissing() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        PodSpec injected = SidecarInjector.maybeInject(sbx, nginxSpec(), null);
        assertEquals(AgentRuntimeDefaults.DEFAULT_IMAGE, injected.getContainers().get(1).getImage());
    }

    @Test
    void injectionIsIdempotent() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        PodSpec first = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);
        PodSpec second = SidecarInjector.maybeInject(sbx, first, SIDECAR_IMAGE);
        assertEquals(2, second.getContainers().size(), "re-injection must not duplicate the sidecar");
        assertEquals(1, second.getVolumes().size());
    }

    @Test
    void hostMountsBecomeSidecarOnlyHostPathVolume() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        annotationsOf(sbx).put(ApiConstants.ANNOTATION_HOST_MOUNTS, "[\"/gridview-niesl113-10033113\"]");
        PodSpec injected = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);

        // hostPath volume carrying the node-mounted root
        Volume hostVolume = injected.getVolumes().stream()
                .filter(v -> v.getName().equals(AgentRuntimeDefaults.HOST_VOLUME_PREFIX + "0"))
                .findFirst().orElseThrow();
        assertEquals("/gridview-niesl113-10033113", hostVolume.getHostPath().getPath());

        // mounted into the sidecar at the same absolute path
        Container sidecar = injected.getContainers().stream()
                .filter(c -> c.getName().equals(AgentRuntimeDefaults.CONTAINER_NAME))
                .findFirst().orElseThrow();
        VolumeMount sidecarMount = sidecar.getVolumeMounts().stream()
                .filter(m -> m.getName().equals(AgentRuntimeDefaults.HOST_VOLUME_PREFIX + "0"))
                .findFirst().orElseThrow();
        assertEquals("/gridview-niesl113-10033113", sidecarMount.getMountPath());

        // the app container must not see the raw host root — only dynamic bind mounts
        Container app = injected.getContainers().get(0);
        assertTrue(app.getVolumeMounts().stream()
                .noneMatch(m -> m.getName().startsWith(AgentRuntimeDefaults.HOST_VOLUME_PREFIX)));
    }

    @Test
    void hostMountInjectionIsIdempotent() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        annotationsOf(sbx).put(ApiConstants.ANNOTATION_HOST_MOUNTS, "[\"/gridview-niesl113-10033113\"]");
        PodSpec first = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);
        PodSpec second = SidecarInjector.maybeInject(sbx, first, SIDECAR_IMAGE);
        assertEquals(1, second.getVolumes().stream()
                .filter(v -> v.getName().equals(AgentRuntimeDefaults.HOST_VOLUME_PREFIX + "0")).count());
        Container sidecar = second.getContainers().stream()
                .filter(c -> c.getName().equals(AgentRuntimeDefaults.CONTAINER_NAME))
                .findFirst().orElseThrow();
        assertEquals(1, sidecar.getVolumeMounts().stream()
                .filter(m -> m.getName().equals(AgentRuntimeDefaults.HOST_VOLUME_PREFIX + "0")).count());
    }

    @Test
    void dynamicRootsBecomeOnlyShareMountsOnAppContainer() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        annotationsOf(sbx).put(ApiConstants.ANNOTATION_DYNAMIC_ROOTS, "[\"/data\",\"/opt/shared\"]");
        PodSpec injected = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);

        Container app = injected.getContainers().get(0);
        List<VolumeMount> appMounts = app.getVolumeMounts();
        // With dynamicRoots configured the default /mnt/envd mount is dropped — only the roots remain,
        // so a dynamic mount is not duplicated under the default shared-tree path.
        assertEquals(2, appMounts.size());
        for (String path : List.of("/data", "/opt/shared")) {
            VolumeMount m = appMounts.stream().filter(x -> path.equals(x.getMountPath()))
                    .findFirst().orElseThrow(() -> new AssertionError("missing mount at " + path));
            assertEquals("agent-runtime-share", m.getName());
            assertEquals("HostToContainer", m.getMountPropagation());
        }
        assertTrue(appMounts.stream().noneMatch(m -> "/mnt/envd".equals(m.getMountPath())),
                "default /mnt/envd share mount must be skipped when custom roots are configured");
        // mount-root env points at the first custom root's volumes base, not the (absent) /mnt/envd
        EnvVar mountRoot = app.getEnv().stream()
                .filter(e -> e.getName().equals("ACX_MOUNT_ROOT"))
                .findFirst().orElseThrow();
        assertEquals("/data/volumes", mountRoot.getValue());
        // sidecar is untouched by dynamicRoots (roots are app-container-only)
        Container sidecar = injected.getContainers().get(1);
        assertEquals(1, sidecar.getVolumeMounts().size());
        assertEquals("/mnt/envd", sidecar.getVolumeMounts().get(0).getMountPath());
    }

    @Test
    void dynamicRootsReinjectionDoesNotDuplicateMounts() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        annotationsOf(sbx).put(ApiConstants.ANNOTATION_DYNAMIC_ROOTS, "[\"/data\"]");
        PodSpec first = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);
        PodSpec second = SidecarInjector.maybeInject(sbx, first, SIDECAR_IMAGE);

        Container app = second.getContainers().get(0);
        assertEquals(1, app.getVolumeMounts().stream().filter(m -> "/data".equals(m.getMountPath())).count());
        assertEquals(0, app.getVolumeMounts().stream().filter(m -> "/mnt/envd".equals(m.getMountPath())).count(),
                "custom-root mode must never add the default /mnt/envd mount");
        assertEquals(2, second.getContainers().size(), "re-injection must not duplicate the sidecar");
    }

    @Test
    void dynamicRootsEqualsSharePathIsIgnored() {
        Sandbox sbx = sandboxWithRuntimes(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME);
        // /mnt/envd and non-absolute entries are unusable as dynamic roots — no usable root means the
        // classic default layout applies: exactly one canonical /mnt/envd share mount.
        annotationsOf(sbx).put(ApiConstants.ANNOTATION_DYNAMIC_ROOTS, "[\"/mnt/envd\",\"relative/no\",\"\"]");
        PodSpec injected = SidecarInjector.maybeInject(sbx, nginxSpec(), SIDECAR_IMAGE);
        Container app = injected.getContainers().get(0);
        assertEquals(1, app.getVolumeMounts().stream().filter(m -> "/mnt/envd".equals(m.getMountPath())).count(),
                "only the canonical /mnt/envd share mount expected");
    }

    private static Map<String, String> annotationsOf(Sandbox sbx) {
        if (sbx.getMetadata().getAnnotations() == null) {
            sbx.getMetadata().setAnnotations(new HashMap<>());
        }
        return sbx.getMetadata().getAnnotations();
    }
}
