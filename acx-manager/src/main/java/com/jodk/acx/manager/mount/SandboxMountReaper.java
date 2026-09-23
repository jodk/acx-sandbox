package com.jodk.acx.manager.mount;

import com.jodk.acx.agentruntime.MountResult;
import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.manager.model.ActiveMount;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort teardown of a sandbox's active dynamic mounts before the sandbox is deleted.
 *
 * <p>Deleting a sandbox pod that still carries dynamic mounts leaks the mounts on the worker node:
 * the NFS/bind submounts created by the agent-runtime sidecar under the shared emptyDir are not
 * tracked by kubelet, so they survive the pod. The delete paths ({@code delete sandbox} /
 * {@code delete pool}) call this reaper while the pod is still up so the sidecar can unmount each
 * mount cleanly. The agent-runtime sidecar additionally umounts all tracked mounts on shutdown as
 * a backstop for pod termination that never reaches the manager.
 */
@Component
public class SandboxMountReaper {

    private static final Logger LOG = Logger.getLogger(SandboxMountReaper.class.getName());

    private final KubernetesClient client;
    private final RuntimeMountClient runtime;

    public SandboxMountReaper(KubernetesClient client, RuntimeMountClient runtime) {
        this.client = client;
        this.runtime = runtime;
    }

    /**
     * Umounts every active dynamic mount of the sandbox {@code name} in namespace {@code namespace}.
     * Safe to call when the sandbox or its pod is already gone (no-op). Never throws: deletion must
     * proceed even when a mount is busy and cannot be torn down.
     */
    public void umountAll(String namespace, String name) {
        Sandbox sbx = client.resources(Sandbox.class).inNamespace(namespace).withName(name).get();
        if (sbx == null) {
            return;
        }
        List<ActiveMount> mounts = ActiveMounts.read(sbx);
        if (mounts.isEmpty()) {
            return;
        }
        Pod pod = client.pods().inNamespace(namespace).withName(name).get();
        String currentUid = pod != null && pod.getMetadata() != null ? pod.getMetadata().getUid() : null;
        String currentIp = pod != null && pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
        boolean sidecar = hasSidecar(pod);
        for (ActiveMount mount : mounts) {
            boolean stale = mount.podUid != null && !mount.podUid.equals(currentUid);
            if (stale || currentUid == null || currentIp == null || !sidecar) {
                // The mount belonged to a pod that is already gone, or there is no sidecar to reach:
                // nothing left to tear down from the manager side.
                LOG.log(Level.INFO, "skip umount {0} of sandbox {1}/{2}: pod gone or sidecar absent",
                        new Object[]{mount.mountId, namespace, name});
                continue;
            }
            try {
                MountResult result = runtime.umount(new PodKey(namespace, name, currentUid, currentIp), mount.mountId);
                if (result.isOk() || "not mounted".equals(result.getError())) {
                    LOG.log(Level.INFO, "umounted {0} of sandbox {1}/{2} before delete",
                            new Object[]{mount.mountId, namespace, name});
                } else {
                    LOG.log(Level.WARNING, "umount {0} of sandbox {1}/{2} failed before delete: {3}",
                            new Object[]{mount.mountId, namespace, name, result.getError()});
                }
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "umount " + mount.mountId + " of sandbox "
                        + namespace + "/" + name + " threw", e);
            }
        }
    }

    private static boolean hasSidecar(Pod pod) {
        if (pod == null || pod.getSpec() == null || pod.getSpec().getContainers() == null) {
            return false;
        }
        for (Container container : pod.getSpec().getContainers()) {
            if (AgentRuntimeDefaults.CONTAINER_NAME.equals(container.getName())) {
                return true;
            }
        }
        return false;
    }
}
