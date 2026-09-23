package com.jodk.acx.common.route;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe route table with optimistic (resource-version) CAS semantics.
 * Ported from pkg/proxy/routes.go SetRoute/LoadRoute/ListRoutes/DeleteRoute.
 */
public class RouteStore {

    private final ConcurrentHashMap<String, Route> routes = new ConcurrentHashMap<>();

    public void setRoute(Route route) {
        Route existing = routes.putIfAbsent(route.getId(), route);
        if (existing == null) {
            return; // first write, success directly
        }
        while (true) {
            Route current = routes.get(route.getId());
            if (!ResourceVersions.isResourceVersionNewer(current.getResourceVersion(), route.getResourceVersion())) {
                return; // new version is not newer, skip write
            }
            if (routes.replace(route.getId(), current, route)) {
                return;
            }
            // CAS failed, another writer intervened; retry
        }
    }

    public Optional<Route> loadRoute(String id) {
        return Optional.ofNullable(routes.get(id));
    }

    public List<Route> listRoutes() {
        return new ArrayList<>(routes.values());
    }

    public void deleteRoute(String id) {
        routes.remove(id);
    }

    public int size() {
        return routes.size();
    }
}
