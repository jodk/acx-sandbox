package com.jodk.acx.manager;

import com.jodk.acx.common.route.Route;
import com.jodk.acx.common.route.RouteStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Peer route-sync endpoint (POST /refresh). */
@RestController
public class RefreshController {

    private final RouteStore routeStore;

    public RefreshController(RouteStore routeStore) {
        this.routeStore = routeStore;
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(@RequestBody Route route) {
        if (com.jodk.acx.api.v1alpha1.ApiConstants.SANDBOX_STATE_DEAD.equals(route.getState())) {
            routeStore.deleteRoute(route.getId());
        } else {
            routeStore.setRoute(route);
        }
        return ResponseEntity.noContent().build();
    }
}
