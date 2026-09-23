package com.jodk.acx.gateway;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.common.route.Route;
import com.jodk.acx.common.route.RouteStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Peer route-sync endpoint. */
@RestController
public class PeerRefreshController {

    private final RouteStore routeStore;

    public PeerRefreshController(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@RequestBody Route route) {
        if (ApiConstants.SANDBOX_STATE_DEAD.equals(route.getState())) {
            routeStore.deleteRoute(route.getId());
        } else {
            routeStore.setRoute(route);
        }
        return ResponseEntity.noContent().build();
    }
}
