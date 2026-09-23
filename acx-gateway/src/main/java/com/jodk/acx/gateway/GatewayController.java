package com.jodk.acx.gateway;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.common.route.Route;
import com.jodk.acx.common.route.RouteStore;
import com.jodk.acx.common.routing.HostRouter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Host → sandbox routing (Java equivalent of the Go Envoy filter logic). */
@RestController
public class GatewayController {

    private final RouteStore routeStore;

    public GatewayController(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    @GetMapping("/health")
    public String health() {
        return "OK";
    }

    @GetMapping("/resolve")
    public ResponseEntity<Map<String, String>> resolve(@RequestParam String host) {
        HostRouter.HostInfo hostInfo = HostRouter.extractHostInfo(host);
        if (hostInfo == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid host format"));
        }
        return routeStore.loadRoute(hostInfo.sandboxId())
                .map(route -> resolveResponse(route, hostInfo.port()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "sandbox_not_found", "sandboxID", hostInfo.sandboxId())));
    }

    @GetMapping("/routes")
    public List<Route> routes() {
        return routeStore.listRoutes();
    }

    private ResponseEntity<Map<String, String>> resolveResponse(Route route, String port) {
        if (!ApiConstants.SANDBOX_STATE_RUNNING.equals(route.getState())) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "sandbox_not_running", "sandboxID", route.getId()));
        }
        return ResponseEntity.ok(Map.of(
                "sandboxID", route.getId(),
                "ip", route.getIp() == null ? "" : route.getIp(),
                "port", port));
    }
}
