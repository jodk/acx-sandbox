package com.jodk.acx.agentruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jodk.acx.api.v1alpha1.AgentRuntimeDefaults;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point of the injected {@code agent-runtime} sidecar. Exposes the dynamic-mount control
 * surface on {@code 0.0.0.0:49983}: {@code GET /health}, {@code POST /v1/mount}, {@code POST
 * /v1/umount}. The manager reaches it over a Kubernetes port-forward; the same contract would be
 * served by a Route-B node-level agent without control-plane changes.
 */
public final class AgentRuntimeMain {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE = "application/json; charset=utf-8";

    private final int port;
    private final MountRegistry registry;
    private final MountTracker tracker;
    private final Path shareDir;

    AgentRuntimeMain(int port, MountRegistry registry, MountTracker tracker, Path shareDir) {
        this.port = port;
        this.registry = registry;
        this.tracker = tracker;
        this.shareDir = shareDir;
    }

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(
                System.getenv().getOrDefault("ACX_AGENT_PORT", String.valueOf(AgentRuntimeDefaults.PORT)));
        String shareDirValue = System.getenv().getOrDefault("ACX_SHARE_DIR", AgentRuntimeDefaults.SHARE_MOUNT_PATH);
        Path shareDir = Path.of(shareDirValue);
        Files.createDirectories(shareDir);
        MountTracker tracker = MountTracker.in(shareDir).load();
        MountRegistry registry = MountRegistry.withDefaults(tracker);
        AgentRuntimeMain runtime = new AgentRuntimeMain(port, registry, tracker, shareDir);
        runtime.start();
        runtime.installShutdownUmountHook();
        while (true) {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.createContext("/health", this::handleHealth);
        server.createContext("/v1/mount", this::handleMount);
        server.createContext("/v1/umount", this::handleUmount);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(pool);
        server.start();
        System.out.println("[agent-runtime] sidecar listening on 0.0.0.0:" + port
                + " (drivers=" + registry.driverList() + ", shareDir=" + shareDir + ")");
    }

    /**
     * Backstop for pod termination that never reaches the manager delete paths (operator scale-down,
     * node eviction, sandbox CR removed by a third party): a JVM shutdown hook that best-effort
     * umounts every tracked dynamic mount. Without this, the submounts created under the shared
     * emptyDir are not tracked by kubelet and would leak on the worker node after the pod is gone.
     * Each umount is best-effort — a busy mount (files still open in the app container) may
     * fail and is reported but never retried here.
     */
    void installShutdownUmountHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::umountAllOnShutdown, "agent-runtime-umount-all"));
    }

    private void umountAllOnShutdown() {
        List<MountTracker.TrackedMount> tracked = tracker.snapshot();
        if (tracked.isEmpty()) {
            return;
        }
        System.out.println("[agent-runtime] shutdown: umounting " + tracked.size() + " tracked mount(s)");
        // Last mounted first: children before parents keeps the shared tree consistent.
        for (int i = tracked.size() - 1; i >= 0; i--) {
            MountTracker.TrackedMount mount = tracked.get(i);
            MountExecutor executor = mount.getDriver() == null ? null : registry.get(mount.getDriver());
            if (executor == null) {
                continue;
            }
            try {
                MountResult result = executor.umount(mount.getVolumeId());
                if (result.isOk()) {
                    System.out.println("[agent-runtime]   umounted " + mount.getVolumeId());
                } else {
                    System.err.println("[agent-runtime]   umount " + mount.getVolumeId() + " failed: " + result.getError());
                }
            } catch (RuntimeException e) {
                System.err.println("[agent-runtime]   umount " + mount.getVolumeId() + " error: " + e.getMessage());
            }
        }
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            write(exchange, 405, errorNode("method not allowed"));
            return;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("status", "ok");
        ArrayNode drivers = node.putArray("drivers");
        registry.drivers().stream().sorted().forEach(drivers::add);
        write(exchange, 200, node);
    }

    private void handleMount(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, errorNode("method not allowed"));
            return;
        }
        MountResult result;
        try {
            MountRequest mountRequest = MAPPER.readValue(exchange.getRequestBody(), MountRequest.class);
            String driver = mountRequest.getDriver();
            MountExecutor executor = driver == null ? null : registry.get(driver);
            if (executor == null) {
                result = MountResult.failure(null, null,
                        "unsupported driver '" + driver + "' (known: " + registry.driverList() + ")");
            } else {
                result = executor.mount(mountRequest);
            }
        } catch (IOException e) {
            result = MountResult.failure(null, null, "cannot parse mount request: " + e.getMessage());
        } catch (RuntimeException e) {
            result = MountResult.failure(null, null, "mount failed: " + e.getMessage());
        }
        write(exchange, 200, MAPPER.valueToTree(result));
    }

    private void handleUmount(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            write(exchange, 405, errorNode("method not allowed"));
            return;
        }
        MountResult result;
        try {
            UmountRequest umountRequest = MAPPER.readValue(exchange.getRequestBody(), UmountRequest.class);
            String volumeId = umountRequest.getVolumeId();
            String driver = volumeId == null ? null : tracker.driverOf(volumeId);
            MountExecutor executor = driver == null ? null : registry.get(driver);
            if (executor == null) {
                result = MountResult.failure(volumeId, null, "not mounted");
            } else {
                result = executor.umount(volumeId);
            }
        } catch (IOException e) {
            result = MountResult.failure(null, null, "cannot parse umount request: " + e.getMessage());
        } catch (RuntimeException e) {
            result = MountResult.failure(null, null, "umount failed: " + e.getMessage());
        }
        write(exchange, 200, MAPPER.valueToTree(result));
    }

    private static JsonNode errorNode(String message) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("ok", false);
        node.put("error", message);
        return node;
    }

    private static void write(HttpExchange exchange, int code, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
        exchange.getResponseHeaders().set("Content-Length", String.valueOf(bytes.length));
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    static byte[] toJson(Object value) throws IOException {
        return MAPPER.writeValueAsBytes(value);
    }
}
