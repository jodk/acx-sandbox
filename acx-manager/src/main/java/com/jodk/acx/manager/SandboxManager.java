package com.jodk.acx.manager;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.api.v1alpha1.SandboxClaim;
import com.jodk.acx.api.v1alpha1.SandboxClaimSpec;
import com.jodk.acx.common.SandboxManagerConstants;
import com.jodk.acx.common.peers.K8sPeers;
import com.jodk.acx.common.peers.Peers;
import com.jodk.acx.common.route.Route;
import com.jodk.acx.common.route.RouteStore;
import com.jodk.acx.common.sandbox.SandboxUtils;
import com.jodk.acx.manager.model.SandboxDto;
import com.jodk.acx.manager.mount.SandboxMountReaper;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Core sandbox manager: claim/pick, route table, peer sync, lifecycle. */
@Component
public class SandboxManager {

    private final KubernetesClient client;
    private final RouteStore routeStore;
    private final SandboxMountReaper reaper;
    private final ObjectMapperHolder mapper = new ObjectMapperHolder();
    private Peers peers;

    public SandboxManager(KubernetesClient client, RouteStore routeStore, SandboxMountReaper reaper) {
        this.client = client;
        this.routeStore = routeStore;
        this.reaper = reaper;
        this.peers = buildPeers();
    }

    public SandboxDto claimSandbox(String templateName, String user, Integer timeoutSeconds, Map<String, String> metadata) {
        String namespace = System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
        KubernetesResourceList<Sandbox> available = client.resources(Sandbox.class).inNamespace(namespace)
                .withLabel(ApiConstants.LABEL_SANDBOX_TEMPLATE, templateName)
                .withLabel(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.FALSE)
                .list();

        Sandbox picked = available.getItems().stream().findFirst().orElse(null);
        if (picked == null) {
            throw new IllegalStateException("no available sandbox for template " + templateName);
        }

        Sandbox claimed = client.resources(Sandbox.class).inNamespace(namespace)
                .withName(picked.getMetadata().getName())
                .edit(s -> {
                    if (s.getMetadata().getLabels() == null) {
                        s.getMetadata().setLabels(new HashMap<>());
                    }
                    s.getMetadata().getLabels().put(ApiConstants.LABEL_SANDBOX_IS_CLAIMED, ApiConstants.TRUE);
                    // Claimed sandboxes leave the warm pool: the pool controller must neither
                    // count them as available nor garbage-collect them as drift.
                    s.getMetadata().getLabels().remove(ApiConstants.LABEL_SANDBOX_POOL);
                    if (s.getMetadata().getOwnerReferences() != null) {
                        s.getMetadata().getOwnerReferences().removeIf(SandboxManager::isPoolOwner);
                    }
                    Map<String, String> annotations = s.getMetadata().getAnnotations();
                    if (annotations == null) {
                        annotations = new HashMap<>();
                        s.getMetadata().setAnnotations(annotations);
                    }
                    annotations.put(ApiConstants.ANNOTATION_OWNER, user);
                    annotations.put(ApiConstants.ANNOTATION_CLAIM_TIME, java.time.Instant.now().toString());
                    if (metadata != null) {
                        annotations.putAll(metadata);
                    }
                    return s;
                });

        syncRoute(claimed);
        return toDto(claimed, templateName, user);
    }

    public List<SandboxDto> listSandboxes(String user) {
        String namespace = System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
        List<SandboxDto> result = new ArrayList<>();
        KubernetesResourceList<Sandbox> all = client.resources(Sandbox.class).inNamespace(namespace).list();
        for (Sandbox sandbox : all.getItems()) {
            Map<String, String> annotations = sandbox.getMetadata().getAnnotations();
            if (annotations != null && user.equals(annotations.get(ApiConstants.ANNOTATION_OWNER))) {
                result.add(toDto(sandbox, templateOf(sandbox), user));
            }
        }
        return result;
    }

    public SandboxDto getSandbox(String user, String sandboxId) {
        String namespace = System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
        String name = nameOf(sandboxId);
        Sandbox sandbox = client.resources(Sandbox.class).inNamespace(namespace).withName(name).get();
        if (sandbox == null) {
            throw new IllegalArgumentException("sandbox not found: " + sandboxId);
        }
        return toDto(sandbox, templateOf(sandbox), user);
    }

    public void deleteSandbox(String user, String sandboxId) {
        String namespace = System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
        String name = nameOf(sandboxId);
        // Tear down dynamic mounts while the pod is still up: deleting the pod with active mounts
        // leaks the NFS/bind submounts on the worker node.
        reaper.umountAll(namespace, name);
        client.resources(Sandbox.class).inNamespace(namespace).withName(name).delete();
        routeStore.deleteRoute(sandboxId);
    }

    public void pauseSandbox(String sandboxId) {
        mutateSandbox(sandboxId, s -> s.getSpec().setPaused(true));
    }

    public void resumeSandbox(String sandboxId) {
        mutateSandbox(sandboxId, s -> s.getSpec().setPaused(false));
    }

    public void setTimeout(String sandboxId, int timeoutSeconds) {
        mutateSandbox(sandboxId, s -> s.getSpec().setShutdownTime(
                java.time.Instant.now().plusSeconds(timeoutSeconds).toString()));
    }

    public String getOwnerOfSandbox(String sandboxId) {
        return routeStore.loadRoute(sandboxId).map(Route::getOwner).orElse(null);
    }

    public List<Route> listRoutes() {
        return routeStore.listRoutes();
    }

    public void syncRoute(Sandbox sandbox) {
        Route route = toRoute(sandbox);
        routeStore.setRoute(route);
        fanOutRefresh(route);
    }

    private void mutateSandbox(String sandboxId, java.util.function.Consumer<Sandbox> mutator) {
        String namespace = System.getenv().getOrDefault("SANDBOX_NAMESPACE", "default");
        String name = nameOf(sandboxId);
        Sandbox updated = client.resources(Sandbox.class).inNamespace(namespace).withName(name)
                .edit(s -> {
                    mutator.accept(s);
                    return s;
                });
        syncRoute(updated);
    }

    private Route toRoute(Sandbox sandbox) {
        Map<String, String> annotations = sandbox.getMetadata().getAnnotations();
        String owner = annotations != null ? annotations.get(ApiConstants.ANNOTATION_OWNER) : null;
        return new Route(
                sandbox.getStatus() != null ? sandbox.getStatus().getSandboxIp() : null,
                SandboxUtils.getSandboxId(sandbox),
                sandbox.getMetadata().getUid(),
                owner,
                SandboxUtils.getSandboxState(sandbox).state(),
                sandbox.getMetadata().getResourceVersion());
    }

    private SandboxDto toDto(Sandbox sandbox, String templateId, String user) {
        SandboxDto dto = new SandboxDto();
        dto.setSandboxID(SandboxUtils.getSandboxId(sandbox));
        dto.setTemplateID(templateId);
        dto.setClientID(user);
        dto.setState(SandboxUtils.getSandboxState(sandbox).state());
        dto.setStartedAt(sandbox.getMetadata().getCreationTimestamp());
        dto.setEndAt(sandbox.getSpec().getShutdownTime());
        dto.setMetadata(sandbox.getMetadata().getAnnotations());
        return dto;
    }

    private String templateOf(Sandbox sandbox) {
        Map<String, String> labels = sandbox.getMetadata().getLabels();
        return labels != null ? labels.get(ApiConstants.LABEL_SANDBOX_TEMPLATE) : null;
    }

    private static String nameOf(String sandboxId) {
        int idx = sandboxId.indexOf("--");
        return idx >= 0 ? sandboxId.substring(idx + 2) : sandboxId;
    }

    private static boolean isPoolOwner(OwnerReference ref) {
        return "SandboxSet".equals(ref.getKind()) && "agents.kruise.io/v1alpha1".equals(ref.getApiVersion());
    }

    private Peers buildPeers() {
        String namespace = System.getenv().getOrDefault("PEER_NAMESPACE", System.getenv().getOrDefault("SYSTEM_NAMESPACE", "sandbox-system"));
        String selector = System.getenv().getOrDefault("PEER_LABEL_SELECTOR", "app=sandbox-manager");
        String nodeName = System.getenv().getOrDefault("HOSTNAME", System.getenv().getOrDefault("POD_NAME", ""));
        return new K8sPeers(client, namespace, selector, nodeName);
    }

    private void fanOutRefresh(Route route) {
        if (peers == null) {
            return;
        }
        HttpClient http = HttpClient.newHttpClient();
        for (var peer : peers.getPeers()) {
            try {
                String body = mapper.writeValueAsString(route);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + peer.ip() + ":" + SandboxManagerConstants.SYSTEM_PORT + SandboxManagerConstants.REFRESH_PATH))
                        .timeout(Duration.ofMillis(SandboxManagerConstants.REQUEST_PEER_TIMEOUT_MS))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                http.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception ignored) {
                // peer unreachable; skip
            }
        }
    }

    private static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper delegate = new com.fasterxml.jackson.databind.ObjectMapper();

        String writeValueAsString(Object value) throws Exception {
            return delegate.writeValueAsString(value);
        }
    }
}
