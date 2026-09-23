package com.jodk.acx.agentruntime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MountTrackerTest {

    @TempDir
    Path tempDir;

    @Test
    void trackUntrackAndLookup() {
        MountTracker tracker = MountTracker.in(tempDir);
        tracker.track("vol", "tmpfs", "/mnt/x");
        assertTrue(tracker.isTracked("vol"));
        assertEquals("tmpfs", tracker.driverOf("vol"));
        assertEquals("/mnt/x", tracker.targetPathOf("vol"));
        assertEquals(1, tracker.size());

        tracker.untrack("vol");
        assertFalse(tracker.isTracked("vol"));
        assertEquals(0, tracker.size());
    }

    @Test
    void persistsAndReloadsAcrossInstances() {
        MountTracker first = MountTracker.in(tempDir);
        first.track("vol-bind", "bind", "/mnt/envd/volumes/a");
        first.track("vol-tmpfs", "tmpfs", "/mnt/envd/volumes/b");
        first.persist();

        MountTracker second = MountTracker.in(tempDir).load();
        assertTrue(second.isTracked("vol-bind"));
        assertEquals("bind", second.driverOf("vol-bind"));
        assertEquals("/mnt/envd/volumes/a", second.targetPathOf("vol-bind"));
        assertEquals("tmpfs", second.driverOf("vol-tmpfs"));
        assertEquals("/mnt/envd/volumes/b", second.targetPathOf("vol-tmpfs"));
        assertEquals(2, second.size());
    }

    @Test
    void missingStateFileLoadsEmpty() {
        MountTracker tracker = MountTracker.in(tempDir).load();
        assertEquals(0, tracker.size());
    }
}
