package com.jodk.acx.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.RuntimeConfig;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxSet;
import com.jodk.acx.api.v1alpha1.SandboxSetSpec;
import com.jodk.acx.common.sandbox.SandboxUtils;
import com.jodk.acx.manager.model.PoolView;
import com.jodk.acx.manager.model.SandboxView;
import com.jodk.acx.manager.mount.ActiveMounts;
import com.jodk.acx.manager.mount.HostMounts;
import com.jodk.acx.manager.mount.SandboxMountReaper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Management-plane operations over the CRDs: warm-pool (SandboxSet) CRUD, starting an
 * application pod from a pool (claim + template override), dynamic mounts and in-place
 * image adjustment. Writes intent onto the Sandbox/SandboxSet resources; the operator
 * reconcilers turn it into pod actions.
 */
@Component
public class AdminService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final KubernetesClient client;
    private final SandboxMountReaper reaper;

    public AdminService(KubernetesClient client, SandboxMountReaper reaper) {
        this.client = client;
        this.reaper = reaper;
    }

    // ---------------------------------------------------------------------
    // Warm pool (SandboxSet) CRUD
    // ---------------------------------------------------------------------

    public List<PoolView> pools() {
        List<Sandbox> all = listSandboxesRaw();
        Map<String, List<Sandbox>> byPool = new HashMap<>();
        Map<String, List<Sandbox>> byTemplate = new HashMap<>();
        for (Sandbox sbx : all) {
            Map<String, String> labels = labelsOf(sbx);
            String pool = labels.get(ApiConstants.LABEL_SANDBOX_POOL);
            String template = labels.get(ApiConstants.LABEL_SANDBOX_TEMPLATE);
            if (pool != null) {
                byPool.computeIfAbsent(pool, k -> new ArrayList<>()).add(sbx);
            }
            if (template != null) {
                byTemplate.computeIfAbsent(template, k -> new ArrayList<>()).add(sbx);
            }
        }

        List<PoolView> views = new ArrayList<>();
        for (SandboxSet set : client.resources(SandboxSet.class).inNamespace(ns()).list().getItems()) {
            String name = set.getMetadata().getName();
            PoolView v = new PoolView();
            v.name = name;
            v.namespace = set.getMetadata().getNamespace();
            v.replicas = set.getSpec() != null && set.getSpec().getReplicas() != null ? set.getSpec().getReplicas() : 0;
            v.image = imageOf(set.getSpec() != null ? set.getSpec().getTemplate() : null);
            v.agentRuntime = hasAgentRuntime(set.getSpec() != null ? set.getSpec().getRuntimes() : null);
            v.hostMounts = hostMountsOf(set);
            v.dynamicRoots = dynamicRootsOf(set);

            int available = 0;
            int creating = 0;
            for (Sandbox member : byPool.getOrDefault(name, List.of())) {
                if (Boolean.TRUE.equals(member.getMetadata().getLabels() != null
                        && member.getMetadata().getLabels().get(ApiConstants.LABEL_SANDBOX_IS_CLAIMED) != null
                        && member.getMetadata().getLabels().get(ApiConstants.LABEL_SANDBOX_IS_CLAIMED).equals(ApiConstants.TRUE))) {
                    continue;
                }
                if (SandboxUtils.isSandboxReady(member)) {
                    available++;
                } else {
                    creating++;
                }
            }
            int launched = 0;
            for (Sandbox sbx : byTemplate.getOrDefault(name, List.of())) {
                if (isClaimed(sbx)) {
                    launched++;
                }
            }
            v.available = available;
            v.creating = creating;
            v.claimed = launched;
            views.add(v);
        }
        return views;
    }

    public PoolView createPool(Map<String, Object> req) {
        String image = str(req, "image");
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image is required");
        }
        String name = str(req, "name");
        if (name == null || name.isBlank()) {
            name = "pool-" + Long.toHexString(System.nanoTime());
        }
        int replicas = integer(req.get("replicas"), 1);
        if (client.resources(SandboxSet.class).inNamespace(ns()).withName(name).get() != null) {
            throw new IllegalStateException("pool already exists: " + name);
        }

        SandboxSet set = new SandboxSet();
        set.getMetadata().setName(name);
        set.getMetadata().setNamespace(ns());
        SandboxSetSpec spec = new SandboxSetSpec();
        spec.setReplicas(replicas);
        spec.setRuntimes(parseRuntimes(req));
        materializeMountSources(mounts(req.get("mounts")));
        spec.setTemplate(buildTemplate(req));
        set.setSpec(spec);
        List<String> hostMounts = stringList(req.get("hostMounts"));
        List<String> dynamicRoots = stringList(req.get("dynamicRoots"));
        if (!hostMounts.isEmpty() || !dynamicRoots.isEmpty()) {
            if (set.getMetadata().getAnnotations() == null) {
                set.getMetadata().setAnnotations(new HashMap<>());
            }
            try {
                if (!hostMounts.isEmpty()) {
                    set.getMetadata().getAnnotations().put(ApiConstants.ANNOTATION_HOST_MOUNTS,
                            JSON.writeValueAsString(hostMounts));
                }
                if (!dynamicRoots.isEmpty()) {
                    set.getMetadata().getAnnotations().put(ApiConstants.ANNOTATION_DYNAMIC_ROOTS,
                            JSON.writeValueAsString(dynamicRoots));
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException("cannot encode hostMounts/dynamicRoots: " + e.getMessage());
            }
        }
        client.resources(SandboxSet.class).inNamespace(ns()).resource(set).create();
        return pool(name);
    }

    public PoolView pool(String name) {
        return pools().stream().filter(p -> name.equals(p.name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("pool not found: " + name));
    }

    public void scalePool(String name, int replicas) {
        client.resources(SandboxSet.class).inNamespace(ns()).withName(name)
                .edit(s -> {
                    if (s.getSpec() == null) {
                        s.setSpec(new SandboxSetSpec());
                    }
                    s.getSpec().setReplicas(replicas);
                    return s;
                });
    }

    public void deletePool(String name) {
        String namespace = ns();
        // Tear down dynamic mounts of live pool members first: deleting the member pods while their
        // mounts are still active leaks NFS/bind mounts on the worker node (kubelet does not track
        // submounts created inside the shared emptyDir by the agent-runtime sidecar).
        for (Sandbox member : poolMembers(namespace, name)) {
            reaper.umountAll(namespace, member.getMetadata().getName());
        }
        client.resources(SandboxSet.class).inNamespace(namespace).withName(name).delete();
    }

    /** Idle (not claimed) member sandboxes of a pool — the ones owned by the SandboxSet. */
    private List<Sandbox> poolMembers(String namespace, String pool) {
        KubernetesResourceList<Sandbox> list = client.resources(Sandbox.class).inNamespace(namespace)
                .withLabel(ApiConstants.LABEL_SANDBOX_POOL, pool)
                .withLabel(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE)
                .list();
        return list != null && list.getItems() != null ? list.getItems() : List.of();
    }

    /**
     * Lists CSI-backed PersistentVolumes so the UI can offer them as dynamic-mount candidates
     * (driver names containing {@code bind} / {@code tmpfs}).
     */
    public List<Map<String, Object>> csiPvs() {
        List<Map<String, Object>> out = new ArrayList<>();
        KubernetesResourceList<PersistentVolume> all = client.persistentVolumes().list();
        if (all == null || all.getItems() == null) {
            return out;
        }
        for (PersistentVolume pv : all.getItems()) {
            if (pv.getSpec() == null || pv.getSpec().getCsi() == null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", pv.getMetadata().getName());
            m.put("driver", pv.getSpec().getCsi().getDriver());
            Map<String, String> attrs = pv.getSpec().getCsi().getVolumeAttributes();
            m.put("path", attrs != null ? attrs.get("path") : null);
            m.put("volumeHandle", pv.getSpec().getCsi().getVolumeHandle());
            out.add(m);
        }
        return out;
    }

    // ---------------------------------------------------------------------
    // Sandboxes
    // ---------------------------------------------------------------------

    public List<SandboxView> sandboxes() {
        List<SandboxView> out = new ArrayList<>();
        for (Sandbox sbx : listSandboxesRaw()) {
            out.add(toView(sbx));
        }
        return out;
    }

    public SandboxView sandbox(String sandboxId) {
        String name = nameOf(sandboxId);
        Sandbox sbx = client.resources(Sandbox.class).inNamespace(ns()).withName(name).get();
        if (sbx == null) {
            throw new IllegalArgumentException("sandbox not found: " + sandboxId);
        }
        return toView(sbx);
    }

    /**
     * Start an application pod from a warm pool: claim one idle sandbox, detach it from the
     * pool, overlay image / mounts / env / command onto its pod template and tell the operator
     * how to apply the change (in-place when only the image changes, rebuild otherwise).
     */
    public SandboxView startFromPool(String pool, Map<String, Object> req, String owner) {
        KubernetesResourceList<Sandbox> idle = client.resources(Sandbox.class).inNamespace(ns())
                .withLabel(ApiConstants.LABEL_SANDBOX_POOL, pool)
                .withLabel(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE)
                .list();
        if (idle.getItems().isEmpty()) {
            throw new IllegalStateException("warm pool '" + pool + "' has no idle sandbox right now");
        }
        Sandbox picked = idle.getItems().get(0);

        String desiredImage = str(req, "image");
        List<MountSpec> mounts = mounts(req.get("mounts"));
        materializeMountSources(mounts);
        Map<String, String> env = stringMap(req.get("env"));

        String[] holder = new String[1];
        String[] action = new String[1];

        Sandbox updated = client.resources(Sandbox.class).inNamespace(ns())
                .withName(picked.getMetadata().getName())
                .edit(s -> {
                    labelsOf(s).put(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.TRUE);
                    labelsOf(s).remove(ApiConstants.LABEL_SANDBOX_POOL);
                    if (s.getMetadata().getOwnerReferences() != null) {
                        s.getMetadata().getOwnerReferences().removeIf(AdminService::isPoolOwner);
                    }
                    annotationsOf(s).put(ApiConstants.ANNOTATION_OWNER, owner != null ? owner : "anonymous");
                    annotationsOf(s).put(ApiConstants.ANNOTATION_CLAIM_TIME, Instant.now().toString());

                    PodTemplateSpec current = templateOf(s);
                    String currentImage = imageOf(current);
                    PodTemplateSpec desired = applyTemplateOverrides(current, desiredImage, mounts, env, req);
                    s.getSpec().setTemplate(desired);
                    holder[0] = imageOf(desired);

                    boolean wantsMounts = mounts != null && !mounts.isEmpty();
                    boolean imageChanged = desiredImage != null && !desiredImage.equals(currentImage);
                    if (wantsMounts) {
                        action[0] = ApiConstants.ADJUST_REBUILD;
                    } else if (imageChanged) {
                        action[0] = ApiConstants.ADJUST_INPLACE;
                    } else {
                        action[0] = null;
                    }
                    if (action[0] != null) {
                        annotationsOf(s).put(ApiConstants.ANNOTATION_SANDBOX_ADJUST, action[0]);
                    }
                    return s;
                });

        return toView(updated);
    }

    /** Add custom mounts to a running sandbox. Kubernetes forbids adding mounts in place, so the pod is rebuilt. */
    public SandboxView addMounts(String sandboxId, Map<String, Object> req, String owner) {
        List<MountSpec> mounts = mounts(req.get("mounts"));
        if (mounts == null || mounts.isEmpty()) {
            throw new IllegalArgumentException("mounts is required");
        }
        materializeMountSources(mounts);
        String name = nameOf(sandboxId);
        Sandbox updated = client.resources(Sandbox.class).inNamespace(ns()).withName(name)
                .edit(s -> {
                    if (annotationsOf(s).get(ApiConstants.ANNOTATION_OWNER) == null && owner != null) {
                        annotationsOf(s).put(ApiConstants.ANNOTATION_OWNER, owner);
                    }
                    PodTemplateSpec template = templateOf(s);
                    ensureContainer(template);
                    PodSpec podSpec = template.getSpec();
                    List<String> existingNames = new ArrayList<>();
                    if (podSpec.getVolumes() != null) {
                        for (Volume vol : podSpec.getVolumes()) {
                            existingNames.add(vol.getName());
                        }
                    } else {
                        podSpec.setVolumes(new ArrayList<>());
                    }
                    Container container = podSpec.getContainers().get(0);
                    if (container.getVolumeMounts() == null) {
                        container.setVolumeMounts(new ArrayList<>());
                    }
                    for (MountSpec m : mounts) {
                        if (!existingNames.contains(m.name)) {
                            podSpec.getVolumes().add(toVolume(m));
                            existingNames.add(m.name);
                        }
                        container.getVolumeMounts().add(toVolumeMount(m));
                    }
                    s.getSpec().setTemplate(template);
                    annotationsOf(s).put(ApiConstants.ANNOTATION_SANDBOX_ADJUST, ApiConstants.ADJUST_REBUILD);
                    return s;
                });
        return toView(updated);
    }

    /** In-place adjustment: swap the image of a running sandbox pod without recreating the pod. */
    public SandboxView inplaceImage(String sandboxId, String image, String owner) {
        if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image is required");
        }
        String name = nameOf(sandboxId);
        Sandbox updated = client.resources(Sandbox.class).inNamespace(ns()).withName(name)
                .edit(s -> {
                    if (annotationsOf(s).get(ApiConstants.ANNOTATION_OWNER) == null && owner != null) {
                        annotationsOf(s).put(ApiConstants.ANNOTATION_OWNER, owner);
                    }
                    PodTemplateSpec template = templateOf(s);
                    ensureContainer(template);
                    template.getSpec().getContainers().get(0).setImage(image);
                    s.getSpec().setTemplate(template);
                    annotationsOf(s).put(ApiConstants.ANNOTATION_SANDBOX_ADJUST, ApiConstants.ADJUST_INPLACE);
                    return s;
                });
        return toView(updated);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private List<Sandbox> listSandboxesRaw() {
        KubernetesResourceList<Sandbox> all = client.resources(Sandbox.class).inNamespace(ns()).list();
        return all != null && all.getItems() != null ? all.getItems() : List.of();
    }

    private SandboxView toView(Sandbox sbx) {
        SandboxView v = new SandboxView();
        v.sandboxID = SandboxUtils.getSandboxId(sbx);
        v.name = sbx.getMetadata().getName();
        v.namespace = sbx.getMetadata().getNamespace();
        Map<String, String> labels = labelsOf(sbx);
        v.pool = labels.get(ApiConstants.LABEL_SANDBOX_POOL);
        v.claimed = isClaimed(sbx);
        var state = SandboxUtils.getSandboxState(sbx);
        v.state = state.state();
        v.reason = state.reason();
        v.phase = sbx.getStatus() != null && sbx.getStatus().getPhase() != null ? sbx.getStatus().getPhase().name() : null;
        v.paused = sbx.getSpec() != null && Boolean.TRUE.equals(sbx.getSpec().getPaused());
        v.createdAt = sbx.getMetadata().getCreationTimestamp();
        v.image = imageOf(sbx.getSpec() != null ? sbx.getSpec().getTemplate() : null);
        v.adjust = sbx.getMetadata().getAnnotations() != null ? sbx.getMetadata().getAnnotations().get(ApiConstants.ANNOTATION_SANDBOX_ADJUST) : null;
        v.owner = sbx.getMetadata().getAnnotations() != null ? sbx.getMetadata().getAnnotations().get(ApiConstants.ANNOTATION_OWNER) : null;
        v.shutdownTime = sbx.getSpec() != null ? sbx.getSpec().getShutdownTime() : null;
        if (sbx.getStatus() != null) {
            v.nodeName = sbx.getStatus().getNodeName();
            v.podIP = sbx.getStatus().getSandboxIp();
            if (sbx.getStatus().getPodInfo() != null) {
                if (v.podIP == null) {
                    v.podIP = sbx.getStatus().getPodInfo().getPodIP();
                }
                v.nodeName = sbx.getStatus().getPodInfo().getNodeName();
            }
        }
        v.mounts = mountSummary(sbx.getSpec() != null ? sbx.getSpec().getTemplate() : null);
        v.agentRuntime = hasAgentRuntime(sbx.getSpec() != null ? sbx.getSpec().getRuntimes() : null);
        v.hostMounts = HostMounts.of(sbx);
        v.dynamicRoots = HostMounts.of(sbx, ApiConstants.ANNOTATION_DYNAMIC_ROOTS);
        v.activeMounts = ActiveMounts.read(sbx);
        return v;
    }

    /** Decodes the {@code agents.kruise.io/host-mounts} annotation of a SandboxSet (JSON array). */
    private static List<String> hostMountsOf(SandboxSet set) {
        return HostMounts.of(set.getMetadata(), ApiConstants.ANNOTATION_HOST_MOUNTS);
    }

    /** Decodes the {@code agents.kruise.io/dynamic-roots} annotation of a SandboxSet (JSON array). */
    private static List<String> dynamicRootsOf(SandboxSet set) {
        return HostMounts.of(set.getMetadata(), ApiConstants.ANNOTATION_DYNAMIC_ROOTS);
    }

    private static boolean hasAgentRuntime(List<RuntimeConfig> runtimes) {
        if (runtimes == null) {
            return false;
        }
        for (RuntimeConfig runtime : runtimes) {
            if (runtime != null && ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME.equals(runtime.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Reads {@code runtimes} (list of names or {@code {name}} objects) and the boolean
     * {@code agentRuntime} convenience flag into the SandboxSet runtime list.
     */
    private static List<RuntimeConfig> parseRuntimes(Map<String, Object> req) {
        List<RuntimeConfig> out = new ArrayList<>();
        Object raw = req.get("runtimes");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                String name = null;
                if (item instanceof Map<?, ?> map) {
                    Object value = map.get("name");
                    if (value != null) {
                        name = String.valueOf(value);
                    }
                } else if (item != null) {
                    name = String.valueOf(item);
                }
                if (name != null && !name.isBlank()) {
                    out.add(new RuntimeConfig(name));
                }
            }
        }
        if (Boolean.TRUE.equals(req.get("agentRuntime"))) {
            out.add(new RuntimeConfig(ApiConstants.RUNTIME_CONFIG_FOR_INJECT_AGENT_RUNTIME));
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> mountSummary(PodTemplateSpec template) {
        List<String> out = new ArrayList<>();
        if (template == null || template.getSpec() == null || template.getSpec().getContainers() == null
                || template.getSpec().getContainers().isEmpty()) {
            return out;
        }
        List<VolumeMount> mounts = template.getSpec().getContainers().get(0).getVolumeMounts();
        if (mounts != null) {
            for (VolumeMount m : mounts) {
                out.add(m.getMountPath());
            }
        }
        return out;
    }

    private static PodTemplateSpec applyTemplateOverrides(PodTemplateSpec base, String image,
                                                          List<MountSpec> mounts, Map<String, String> env,
                                                          Map<String, Object> req) {
        PodTemplateSpec template = base != null ? base : new PodTemplateSpec();
        if (template.getSpec() == null) {
            template.setSpec(new PodSpec());
        }
        PodSpec podSpec = template.getSpec();
        if (podSpec.getContainers() == null || podSpec.getContainers().isEmpty()) {
            Container container = new Container();
            container.setName("sandbox");
            podSpec.setContainers(new ArrayList<>(List.of(container)));
        }
        Container container = podSpec.getContainers().get(0);
        if (container.getName() == null || container.getName().isBlank()) {
            container.setName("sandbox");
        }
        if (image != null && !image.isBlank()) {
            container.setImage(image);
        }
        String command = str(req, "command");
        if (command != null) {
            container.setCommand(List.of("/bin/sh", "-c"));
            container.setArgs(List.of(command));
        }
        if (mounts != null && !mounts.isEmpty()) {
            container.setVolumeMounts(new ArrayList<>());
            for (MountSpec m : mounts) {
                container.getVolumeMounts().add(toVolumeMount(m));
            }
            podSpec.setVolumes(new ArrayList<>());
            for (MountSpec m : mounts) {
                podSpec.getVolumes().add(toVolume(m));
            }
        }
        if (env != null && !env.isEmpty()) {
            if (container.getEnv() == null) {
                container.setEnv(new ArrayList<>());
            }
            for (Map.Entry<String, String> e : env.entrySet()) {
                container.getEnv().add(new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build());
            }
        }
        String cpu = str(req, "cpu");
        String memory = str(req, "memory");
        if (cpu != null || memory != null) {
            ResourceRequirementsBuilder builder = new ResourceRequirementsBuilder();
            if (cpu != null) {
                Quantity q = Quantity.parse(cpu);
                builder.addToLimits("cpu", q);
                builder.addToRequests("cpu", q);
            }
            if (memory != null) {
                Quantity q = Quantity.parse(memory);
                builder.addToLimits("memory", q);
                builder.addToRequests("memory", q);
            }
            container.setResources(builder.build());
        }
        return template;
    }

    private static PodTemplateSpec buildTemplate(Map<String, Object> req) {
        PodTemplateSpec template = new PodTemplateSpec();
        template.setSpec(new PodSpec());
        Container container = new Container();
        container.setName("sandbox");
        container.setImage(str(req, "image"));
        String command = str(req, "command");
        if (command != null) {
            container.setCommand(List.of("/bin/sh", "-c"));
            container.setArgs(List.of(command));
        }
        List<MountSpec> mounts = mounts(req.get("mounts"));
        if (mounts != null && !mounts.isEmpty()) {
            container.setVolumeMounts(new ArrayList<>());
            List<Volume> volumes = new ArrayList<>();
            for (MountSpec m : mounts) {
                container.getVolumeMounts().add(toVolumeMount(m));
                volumes.add(toVolume(m));
            }
            template.getSpec().setVolumes(volumes);
        }
        Map<String, String> env = stringMap(req.get("env"));
        if (!env.isEmpty()) {
            List<EnvVar> envVars = new ArrayList<>();
            for (Map.Entry<String, String> e : env.entrySet()) {
                envVars.add(new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build());
            }
            container.setEnv(envVars);
        }
        String cpu = str(req, "cpu");
        String memory = str(req, "memory");
        if (cpu != null || memory != null) {
            ResourceRequirementsBuilder builder = new ResourceRequirementsBuilder();
            if (cpu != null) {
                Quantity q = Quantity.parse(cpu);
                builder.addToLimits("cpu", q);
                builder.addToRequests("cpu", q);
            }
            if (memory != null) {
                Quantity q = Quantity.parse(memory);
                builder.addToLimits("memory", q);
                builder.addToRequests("memory", q);
            }
            container.setResources(builder.build());
        }
        template.getSpec().setContainers(new ArrayList<>(List.of(container)));
        return template;
    }

    private static void ensureContainer(PodTemplateSpec template) {
        if (template.getSpec() == null) {
            template.setSpec(new PodSpec());
        }
        if (template.getSpec().getContainers() == null || template.getSpec().getContainers().isEmpty()) {
            Container container = new Container();
            container.setName("sandbox");
            template.getSpec().setContainers(new ArrayList<>(List.of(container)));
        }
    }

    private static PodTemplateSpec templateOf(Sandbox sbx) {
        if (sbx.getSpec() == null) {
            sbx.setSpec(new com.jodk.acx.api.v1alpha1.SandboxSpec());
        }
        if (sbx.getSpec().getTemplate() == null) {
            sbx.getSpec().setTemplate(new PodTemplateSpec());
        }
        return sbx.getSpec().getTemplate();
    }

    private static Map<String, String> labelsOf(Sandbox sbx) {
        if (sbx.getMetadata().getLabels() == null) {
            sbx.getMetadata().setLabels(new HashMap<>());
        }
        return sbx.getMetadata().getLabels();
    }

    private static Map<String, String> annotationsOf(Sandbox sbx) {
        if (sbx.getMetadata().getAnnotations() == null) {
            sbx.getMetadata().setAnnotations(new HashMap<>());
        }
        return sbx.getMetadata().getAnnotations();
    }

    private static boolean isClaimed(Sandbox sbx) {
        String claimed = sbx.getMetadata().getLabels() != null
                ? sbx.getMetadata().getLabels().get(ApiConstants.LABEL_SANDBOX_IS_CLAIMED) : null;
        return ApiConstants.TRUE.equals(claimed);
    }

    private static boolean isPoolOwner(OwnerReference ref) {
        return "SandboxSet".equals(ref.getKind()) && "agents.kruise.io/v1alpha1".equals(ref.getApiVersion());
    }

    private static String imageOf(PodTemplateSpec template) {
        if (template != null && template.getSpec() != null && template.getSpec().getContainers() != null
                && !template.getSpec().getContainers().isEmpty()) {
            return template.getSpec().getContainers().get(0).getImage();
        }
        return null;
    }

    private static String nameOf(String sandboxId) {
        int idx = sandboxId.indexOf("--");
        return idx >= 0 ? sandboxId.substring(idx + 2) : sandboxId;
    }

    private String ns() {
        return System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
    }

    // ----- mount / request parsing -----

    public static class MountSpec {
        public String name;
        public String mountPath;
        public String subPath;
        public boolean readOnly;
        /** emptyDir | configMap | pvc | hostPath */
        public String type;
        public String configMap;
        /** inline data for auto-creating the ConfigMap when {@code configMap} is not given */
        public Map<String, String> data;
        public String pvcName;
        public String hostPath;
    }

    /**
     * Make mount sources exist before the pod is scheduled: a configMap mount may carry inline
     * {@code data}, in which case the ConfigMap is created (named after the mount) in the
     * sandbox namespace.
     */
    private void materializeMountSources(List<MountSpec> mounts) {
        if (mounts == null) {
            return;
        }
        for (MountSpec m : mounts) {
            if ("configMap".equals(m.type)) {
                if (m.configMap == null && (m.data == null || m.data.isEmpty())) {
                    throw new IllegalArgumentException(
                            "configMap mount '" + m.name + "' needs a configMap name or inline data");
                }
                if (m.configMap == null) {
                    m.configMap = m.name;
                }
                if (m.data != null && !m.data.isEmpty()) {
                    String cmName = m.configMap;
                    boolean exists = client.configMaps().inNamespace(ns()).withName(cmName).get() != null;
                    if (!exists) {
                        client.configMaps().inNamespace(ns()).resource(
                                new io.fabric8.kubernetes.api.model.ConfigMapBuilder()
                                        .withNewMetadata().withName(cmName).withNamespace(ns()).endMetadata()
                                        .withData(m.data)
                                        .build())
                                .create();
                    }
                }
            } else if ("pvc".equals(m.type) && (m.pvcName == null || m.pvcName.isBlank())) {
                throw new IllegalArgumentException("pvc mount '" + m.name + "' requires pvcName");
            } else if ("hostPath".equals(m.type) && (m.hostPath == null || m.hostPath.isBlank())) {
                throw new IllegalArgumentException("hostPath mount '" + m.name + "' requires hostPath");
            }
        }
    }

    private static List<MountSpec> mounts(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<MountSpec> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            MountSpec m = new MountSpec();
            m.name = str(map, "name");
            m.mountPath = str(map, "mountPath");
            m.subPath = str(map, "subPath");
            m.type = str(map, "type");
            m.configMap = str(map, "configMap");
            m.pvcName = str(map, "pvcName");
            m.hostPath = str(map, "hostPath");
            Object data = map.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                m.data = stringMap(dataMap);
            }
            Object ro = map.get("readOnly");
            m.readOnly = ro == Boolean.TRUE || "true".equals(String.valueOf(ro));
            if (m.name == null || m.mountPath == null) {
                throw new IllegalArgumentException("each mount requires name and mountPath");
            }
            if (m.type == null) {
                m.type = "emptyDir";
            }
            out.add(m);
        }
        return out;
    }

    private static Volume toVolume(MountSpec m) {
        VolumeBuilder b = new VolumeBuilder().withName(m.name);
        switch (m.type) {
            case "configMap" -> b.withNewConfigMap().withName(m.configMap).endConfigMap();
            case "pvc" -> b.withNewPersistentVolumeClaim().withClaimName(m.pvcName).endPersistentVolumeClaim();
            case "hostPath" -> b.withNewHostPath().withPath(m.hostPath).endHostPath();
            default -> b.withNewEmptyDir().endEmptyDir();
        }
        return b.build();
    }

    private static VolumeMount toVolumeMount(MountSpec m) {
        VolumeMountBuilder b = new VolumeMountBuilder()
                .withName(m.name)
                .withMountPath(m.mountPath)
                .withReadOnly(m.readOnly);
        if (m.subPath != null) {
            b.withSubPath(m.subPath);
        }
        return b.build();
    }

    private static String str(Object map, String key) {
        if (map instanceof Map<?, ?> m && m.get(key) != null) {
            return String.valueOf(m.get(key));
        }
        return null;
    }

    private static Map<String, String> stringMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (e.getValue() != null) {
                    out.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    private static int integer(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }
}
