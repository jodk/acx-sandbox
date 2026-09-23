package com.jodk.acx.manager.mount;

import com.jodk.acx.agentruntime.BindMountExecutor;
import com.jodk.acx.agentruntime.MountResult;
import com.jodk.acx.agentruntime.TmpfsMountExecutor;
import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;
import com.jodk.acx.agentruntime.storages.CsiNodePublishRequestBuilder;
import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.manager.AdminService;
import com.jodk.acx.manager.model.ActiveMount;
import com.jodk.acx.manager.model.SandboxView;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Control-plane orchestration of dynamic (pod-preserving) CSI mounts.
 *
 * <p>The pod spec is never modified and no {@code adjust} annotation is set, so the operator keeps
 * the running pod. The sidecar creates the mount under the shared mount tree; the app container
 * sees it through HostToContainer propagation.</p>
 */
@Component
public class DynamicMountService {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KubernetesClient client;
    private final RuntimeMountClient runtime;
    private final AdminService admin;

    public DynamicMountService(KubernetesClient client, RuntimeMountClient runtime, AdminService admin) {
        this.client = client;
        this.runtime = runtime;
        this.admin = admin;
    }

    public SandboxView dynamicMount(String sandboxId, Map<String, Object> req) {
        String pvName = str(req, "pvName");
        String sourceKind = str(req, "source");
        if (sourceKind == null || sourceKind.isBlank()) {
            // Backward compatible: a body with only pvName is the classic PV-backed source.
            sourceKind = pvName != null && !pvName.isBlank() ? "pv" : null;
        }
        if (sourceKind == null) {
            throw new IllegalArgumentException("动态挂载来源缺失：请指定 source ∈ {pv, host, tmpfs}（pv 需同时给 pvName）");
        }
        String subPath = str(req, "subPath");
        boolean readOnly = bool(req, "readOnly");
        String name = nameOf(sandboxId);
        String namespace = ns();

        Sandbox sbx = getSandbox(namespace, name);
        Pod pod = runningPodWithSidecar(namespace, name);
        String podUid = pod.getMetadata().getUid();
        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;

        String driver;
        String recordPv = null;
        String recordSource = null;
        String mountIdBase;
        CsiNodePublishRequest request;

        switch (sourceKind) {
            case "pv" -> {
                if (pvName == null || pvName.isBlank()) {
                    throw new IllegalArgumentException("source=pv 需要 pvName");
                }
                PersistentVolume pv = client.persistentVolumes().withName(pvName).get();
                if (pv == null) {
                    throw new IllegalArgumentException("persistent volume not found: " + pvName);
                }
                if (pv.getSpec() == null || pv.getSpec().getCsi() == null) {
                    throw new IllegalArgumentException(
                            "persistent volume " + pvName + " has no csi source; dynamic mounts require a CSI PV");
                }
                driver = resolveDriver(pv.getSpec().getCsi().getDriver());
                if (driver == null) {
                    throw new IllegalArgumentException("unsupported csi driver '"
                            + pv.getSpec().getCsi().getDriver()
                            + "' for dynamic mounts (supported: bind, tmpfs)");
                }
                if (BindMountExecutor.DRIVER.equals(driver)) {
                    requireInjectedHostRoot(sbx, pvName, pv);
                }
                Secret secret = readPublishSecret(pv, namespace);
                request = CsiNodePublishRequestBuilder.build(pv, secret,
                        AgentRuntimeDefaults.MOUNT_ROOT + "/<mountId>", subPath, readOnly);
                recordPv = pvName;
                mountIdBase = pvName;
            }
            case "host" -> {
                // Route A bind of a pool-declared host root, without needing a PV envelope.
                if (pvName != null && !pvName.isBlank()) {
                    throw new IllegalArgumentException("source=host 不能与 pvName 同时给出");
                }
                String hostPath = str(req, "path");
                if (hostPath == null || !hostPath.startsWith("/")) {
                    throw new IllegalArgumentException("source=host 需要宿主绝对路径 path（如 /data/data1）");
                }
                requireHostRootCovered(sbx, "宿主目录 " + hostPath, hostPath);
                driver = BindMountExecutor.DRIVER;
                request = CsiNodePublishRequestBuilder.buildFromContext(
                        "<mountId>", driver, Map.of("path", hostPath), null,
                        AgentRuntimeDefaults.MOUNT_ROOT + "/<mountId>", subPath, readOnly);
                recordSource = hostPath;
                mountIdBase = "host";
            }
            case "tmpfs" -> {
                if (pvName != null && !pvName.isBlank()) {
                    throw new IllegalArgumentException("source=tmpfs 不能与 pvName 同时给出");
                }
                driver = TmpfsMountExecutor.DRIVER;
                String sizeMi = str(req, "sizeMi");
                List<String> flags = null;
                if (sizeMi != null && !sizeMi.isBlank()) {
                    int mi;
                    try {
                        mi = Integer.parseInt(sizeMi.trim());
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("sizeMi 必须是整数（单位 MiB）");
                    }
                    if (mi <= 0 || mi > 1048576) {
                        throw new IllegalArgumentException("sizeMi 须在 1..1048576 之间");
                    }
                    flags = List.of("size=" + mi + "M");
                }
                request = CsiNodePublishRequestBuilder.buildFromContext(
                        "<mountId>", driver, Map.of(), flags,
                        AgentRuntimeDefaults.MOUNT_ROOT + "/<mountId>", null, false);
                recordSource = "tmpfs";
                mountIdBase = "tmpfs";
            }
            default -> throw new IllegalArgumentException(
                    "未知动态挂载来源 source=" + sourceKind + "（支持 pv / host / tmpfs）");
        }

        String mountId = str(req, "mountId");
        if (mountId == null || mountId.isBlank()) {
            mountId = mountIdBase + "-" + randomSuffix();
        }
        String containerPath = AgentRuntimeDefaults.MOUNT_ROOT + "/" + mountId;
        request.setTargetPath(containerPath);
        request.setVolumeId(mountId); // unique per mount instance so the same PV/root can be mounted twice

        MountResult result = runtime.mount(new PodKey(namespace, name, podUid, podIp), driver, request);
        if (!result.isOk()) {
            throw new IllegalStateException("dynamic mount failed: "
                    + (result.getError() != null ? result.getError() : "unknown error"));
        }

        ActiveMount mount = new ActiveMount();
        mount.mountId = mountId;
        String key = mountId; // effectively-final copy for the edit lambda
        mount.driver = driver;
        mount.pvName = recordPv;
        mount.source = recordSource;
        mount.subPath = subPath;
        mount.readOnly = readOnly;
        mount.containerPath = containerPath;
        mount.podUid = podUid;
        mount.mountedAt = Instant.now().toString();

        client.resources(Sandbox.class).inNamespace(namespace).withName(name).edit(s -> {
            List<ActiveMount> mounts = ActiveMounts.read(s);
            mounts.removeIf(m -> key.equals(m.mountId));
            mounts.add(mount);
            annotationsOf(s).put(ApiConstants.ANNOTATION_ACTIVE_MOUNTS, ActiveMounts.encode(mounts));
            return s;
        });
        return admin.sandbox(sandboxId);
    }

    public SandboxView dynamicUmount(String sandboxId, String mountId) {
        if (mountId == null || mountId.isBlank()) {
            throw new IllegalArgumentException("mountId is required");
        }
        String name = nameOf(sandboxId);
        String namespace = ns();
        getSandbox(namespace, name);

        client.resources(Sandbox.class).inNamespace(namespace).withName(name).edit(s -> {
            List<ActiveMount> mounts = ActiveMounts.read(s);
            ActiveMount found = null;
            for (ActiveMount m : mounts) {
                if (mountId.equals(m.mountId)) {
                    found = m;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalArgumentException("active mount not found: " + mountId);
            }
            Pod pod = client.pods().inNamespace(namespace).withName(name).get();
            String currentUid = pod != null && pod.getMetadata() != null ? pod.getMetadata().getUid() : null;
            String currentIp = pod != null && pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
            boolean stale = found.podUid != null && !found.podUid.equals(currentUid);
            if (!stale && currentUid != null && hasSidecar(pod)) {
                MountResult result = runtime.umount(new PodKey(namespace, name, currentUid, currentIp), mountId);
                if (!result.isOk() && !"not mounted".equals(result.getError())) {
                    throw new IllegalStateException("dynamic umount failed: "
                            + (result.getError() != null ? result.getError() : "unknown error"));
                }
            }
            mounts.remove(found);
            annotationsOf(s).put(ApiConstants.ANNOTATION_ACTIVE_MOUNTS, ActiveMounts.encode(mounts));
            return s;
        });
        return admin.sandbox(sandboxId);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Sandbox getSandbox(String namespace, String name) {
        Sandbox sbx = client.resources(Sandbox.class).inNamespace(namespace).withName(name).get();
        if (sbx == null) {
            throw new IllegalArgumentException("sandbox not found: " + name);
        }
        return sbx;
    }

    private Pod runningPodWithSidecar(String namespace, String name) {
        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        if (pod == null || !"Running".equals(pod.getStatus() != null ? pod.getStatus().getPhase() : null)) {
            throw new IllegalArgumentException("sandbox pod is not running");
        }
        if (!hasSidecar(pod)) {
            throw new IllegalArgumentException(
                    "sandbox pod does not run the agent-runtime sidecar; create the pool with agent-runtime enabled");
        }
        return pod;
    }

    private static boolean hasSidecar(Pod pod) {
        if (pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return false;
        }
        for (Container container : pod.getSpec().getContainers()) {
            if (AgentRuntimeDefaults.CONTAINER_NAME.equals(container.getName())) {
                return true;
            }
        }
        return false;
    }

    private Secret readPublishSecret(PersistentVolume pv, String defaultNamespace) {
        if (pv.getSpec().getCsi().getNodePublishSecretRef() == null
                || pv.getSpec().getCsi().getNodePublishSecretRef().getName() == null) {
            return null;
        }
        String name = pv.getSpec().getCsi().getNodePublishSecretRef().getName();
        String namespace = pv.getSpec().getCsi().getNodePublishSecretRef().getNamespace();
        return client.secrets().inNamespace(namespace != null ? namespace : defaultNamespace).withName(name).get();
    }

    /**
     * Route A constraint: a bind dynamic mount can only reach host roots that were injected into the
     * sidecar as hostPath volumes at pod creation (the pool's hostMounts). The sidecar's mount
     * namespace does not contain arbitrary node paths, so binding an undeclared root fails with a
     * cryptic {@code special device does not exist}. Fail fast with an actionable message instead.
     */
    private void requireInjectedHostRoot(Sandbox sbx, String pvName, PersistentVolume pv) {
        Map<String, String> ctx = pv.getSpec().getCsi().getVolumeAttributes();
        String path = ctx != null ? ctx.get("path") : null;
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("bind PV '" + pvName
                    + "' 缺少 volumeAttributes.path（须为宿主绝对路径）");
        }
        requireHostRootCovered(sbx, "bind PV '" + pvName + "' 指向的宿主目录 " + path, path);
    }

    /**
     * Same Route A constraint for a source=host mount: the chosen host path must sit under a root
     * the pool declared in hostMounts. Fails fast before the sidecar produces a cryptic error.
     */
    private void requireHostRootCovered(Sandbox sbx, String what, String path) {
        List<String> roots = HostMounts.of(sbx);
        if (HostMounts.covers(roots, path)) {
            return;
        }
        throw new IllegalArgumentException(what
                + " 不在该沙箱已注入的宿主根之内（当前 hostMounts=" + roots + "）。"
                + "动态 bind 只能挂载预热池 hostMounts 里已声明的宿主根下的路径；"
                + "请在预热池配置的 hostMounts 中加入包含 " + path + " 的宿主根，再新建预热成员后重试。");
    }

    /** Normalises a CSI driver name onto a registered executor driver ({@code bind} / {@code tmpfs}). */
    private static String resolveDriver(String csiDriver) {
        if (csiDriver == null) {
            return null;
        }
        String lower = csiDriver.toLowerCase();
        if (lower.contains("bind")) {
            return BindMountExecutor.DRIVER;
        }
        if (lower.contains("tmpfs")) {
            return TmpfsMountExecutor.DRIVER;
        }
        return null;
    }

    private static Map<String, String> annotationsOf(Sandbox sbx) {
        if (sbx.getMetadata().getAnnotations() == null) {
            sbx.getMetadata().setAnnotations(new HashMap<>());
        }
        return sbx.getMetadata().getAnnotations();
    }

    private static String nameOf(String sandboxId) {
        int idx = sandboxId.indexOf("--");
        return idx >= 0 ? sandboxId.substring(idx + 2) : sandboxId;
    }

    private String ns() {
        return System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
    }

    private static String randomSuffix() {
        char[] buf = new char[6];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = HEX[RANDOM.nextInt(HEX.length)];
        }
        return new String(buf);
    }

    private static String str(Map<String, Object> map, String key) {
        return map.get(key) != null ? String.valueOf(map.get(key)) : null;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == Boolean.TRUE || "true".equals(String.valueOf(value));
    }
}
