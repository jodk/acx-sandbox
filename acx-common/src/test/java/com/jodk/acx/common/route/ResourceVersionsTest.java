package com.jodk.acx.common.route;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceVersionsTest {

    @Test
    void emptyOldIsAlwaysNewer() {
        assertTrue(ResourceVersions.isResourceVersionNewer("", "1"));
        assertTrue(ResourceVersions.isResourceVersionNewer(null, "1"));
    }

    @Test
    void numericOrdering() {
        assertTrue(ResourceVersions.isResourceVersionNewer("1", "2"));
        assertTrue(ResourceVersions.isResourceVersionNewer("1", "1"));
        assertFalse(ResourceVersions.isResourceVersionNewer("2", "1"));
    }

    @Test
    void nonNumericFallback() {
        assertTrue(ResourceVersions.isResourceVersionNewer("abc", "1"));
        assertFalse(ResourceVersions.isResourceVersionNewer("1", "abc"));
    }
}
