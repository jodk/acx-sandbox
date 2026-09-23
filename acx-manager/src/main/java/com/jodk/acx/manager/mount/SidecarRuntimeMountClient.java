package com.jodk.acx.manager.mount;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jodk.acx.agentruntime.MountRequest;
import com.jodk.acx.agentruntime.MountResult;
import com.jodk.acx.agentruntime.UmountRequest;
import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;
import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Route-A delivery of mount/umount requests to the sandbox pod's agent-runtime sidecar.
 *
 * <p>Two transports, tried in order:
 * <ol>
 *   <li><b>Direct pod-network HTTP</b> to {@code <podIP>:49983} — fast and avoids the apiserver
 *       upgrade path; works whenever the manager can route to the pod network.</li>
 *   <li><b>apiserver-mediated port-forward</b> (fabric8 {@code portForward}) — fallback that works
 *       even when the manager pod and the sandbox pod sit on isolated pod networks.</li>
 * </ol>
 * The wire contract ({@code POST /v1/mount} / {@code /v1/umount}) is identical in both cases and
 * would be served unchanged by a Route-B node-level agent.
 */
@Component
public class SidecarRuntimeMountClient implements RuntimeMountClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration DIRECT_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final KubernetesClient client;
    private final HttpClient http;

    public SidecarRuntimeMountClient(KubernetesClient client) {
        this.client = client;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public MountResult mount(PodKey pod, String driver, CsiNodePublishRequest request) {
        return post(pod, "/v1/mount", new MountRequest(driver, request));
    }

    @Override
    public MountResult umount(PodKey pod, String volumeId) {
        return post(pod, "/v1/umount", new UmountRequest(volumeId));
    }

    private MountResult post(PodKey pod, String path, Object body) {
        final byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(body);
        } catch (IOException e) {
            return MountResult.failure(null, null, "cannot encode request: " + e.getMessage());
        }

        String podIp = pod.podIp();
        if (podIp != null && !podIp.isBlank()) {
            MountResult direct = httpPost("http://" + podIp + ":" + AgentRuntimeDefaults.PORT + path,
                    payload, DIRECT_TIMEOUT);
            if (direct != null) {
                return direct; // direct transport answered (success or a real agent-runtime error)
            }
            // fall through to the apiserver-mediated port-forward
        }

        IOException lastError = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalPortForward forward = null;
            try {
                forward = client.pods().inNamespace(pod.namespace())
                        .withName(pod.name())
                        .portForward(AgentRuntimeDefaults.PORT, 0);
                MountResult result = httpPost("http://127.0.0.1:" + forward.getLocalPort() + path,
                        payload, HTTP_TIMEOUT);
                if (result != null) {
                    return result;
                }
                lastError = new IOException("port-forward produced no HTTP response");
            } catch (RuntimeException e) {
                lastError = new IOException(e);
            } finally {
                if (forward != null) {
                    try {
                        forward.close();
                    } catch (IOException ignored) {
                        // best-effort close
                    }
                }
            }
            sleep(200L * (attempt + 1));
        }
        return MountResult.failure(null, null,
                "cannot reach agent-runtime on " + pod.name() + ": "
                        + (lastError != null ? lastError.getMessage() : "unknown error"));
    }

    /** Returns a parsed result for any HTTP response; {@code null} when the transport itself fails. */
    private MountResult httpPost(String url, byte[] payload, Duration timeout) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return MountResult.failure(null, null,
                        "agent-runtime http " + response.statusCode() + ": " + response.body());
            }
            MountResult result = MAPPER.readValue(response.body(), MountResult.class);
            return result != null ? result
                    : MountResult.failure(null, null, "agent-runtime returned an empty response");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
