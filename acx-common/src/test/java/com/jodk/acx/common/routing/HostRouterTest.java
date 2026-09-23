package com.jodk.acx.common.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostRouterTest {

    @Test
    void extractsPortAndSandboxId() {
        HostRouter.HostInfo info = HostRouter.extractHostInfo("3000-ns--name.example.com");
        assertEquals("ns--name", info.sandboxId());
        assertEquals("3000", info.port());
    }

    @Test
    void emptyOrInvalidReturnsNull() {
        assertNull(HostRouter.extractHostInfo(""));
        assertNull(HostRouter.extractHostInfo(null));
        assertNull(HostRouter.extractHostInfo("no-dash.example.com"));
    }
}
