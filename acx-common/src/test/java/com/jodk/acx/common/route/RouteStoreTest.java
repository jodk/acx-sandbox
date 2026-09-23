package com.jodk.acx.common.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteStoreTest {

    private static Route route(String id, String rv, String state) {
        return new Route("10.0.0.1", id, "uid-" + id, "user", state, rv);
    }

    @Test
    void firstWriteWins() {
        RouteStore store = new RouteStore();
        store.setRoute(route("a", "1", "running"));
        assertEquals("1", store.loadRoute("a").orElseThrow().getResourceVersion());
        assertEquals(1, store.size());
    }

    @Test
    void newerReplacesOlder() {
        RouteStore store = new RouteStore();
        store.setRoute(route("a", "1", "running"));
        store.setRoute(route("a", "2", "paused"));
        assertEquals("paused", store.loadRoute("a").orElseThrow().getState());
    }

    @Test
    void olderIsSkipped() {
        RouteStore store = new RouteStore();
        store.setRoute(route("a", "2", "running"));
        store.setRoute(route("a", "1", "paused"));
        assertEquals("running", store.loadRoute("a").orElseThrow().getState());
    }

    @Test
    void deleteRemoves() {
        RouteStore store = new RouteStore();
        store.setRoute(route("a", "1", "running"));
        store.deleteRoute("a");
        assertFalse(store.loadRoute("a").isPresent());
        assertTrue(store.listRoutes().isEmpty());
    }
}
