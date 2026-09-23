package com.jodk.acx.agentruntime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process table of active dynamic mounts, persisted as JSON on the shared volume so an
 * agent-runtime process restart (pod kept) can still answer {@code umount} for previous mounts.
 * Re-serves as the idempotency store: mounting an already-known {@code volumeId} is a no-op.
 */
public class MountTracker {

    public static final String FILE_NAME = ".acx-mounts.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path stateFile;
    private final ConcurrentMap<String, TrackedMount> mounts = new ConcurrentHashMap<>();

    public MountTracker(Path stateFile) {
        this.stateFile = stateFile;
    }

    public static MountTracker in(Path dir) {
        return new MountTracker(dir.resolve(FILE_NAME));
    }

    public synchronized MountTracker load() {
        if (stateFile == null || !Files.exists(stateFile)) {
            return this;
        }
        try {
            List<TrackedMount> stored = MAPPER.readValue(stateFile.toFile(), new TypeReference<List<TrackedMount>>() {
            });
            for (TrackedMount m : stored) {
                if (m.getVolumeId() != null) {
                    mounts.put(m.getVolumeId(), m);
                }
            }
        } catch (IOException e) {
            System.err.println("[agent-runtime] ignoring unreadable mount state " + stateFile + ": " + e.getMessage());
        }
        return this;
    }

    public synchronized void track(String volumeId, String driver, String targetPath) {
        if (volumeId == null || volumeId.isBlank() || targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("volumeId and targetPath are required to track a mount");
        }
        mounts.put(volumeId, new TrackedMount(volumeId, driver, targetPath));
    }

    public synchronized void untrack(String volumeId) {
        mounts.remove(volumeId);
    }

    public synchronized boolean isTracked(String volumeId) {
        return volumeId != null && mounts.containsKey(volumeId);
    }

    public synchronized String targetPathOf(String volumeId) {
        TrackedMount m = mounts.get(volumeId);
        return m == null ? null : m.getTargetPath();
    }

    public synchronized String driverOf(String volumeId) {
        TrackedMount m = mounts.get(volumeId);
        return m == null ? null : m.getDriver();
    }

    public synchronized int size() {
        return mounts.size();
    }

    public synchronized List<TrackedMount> snapshot() {
        return new ArrayList<>(mounts.values());
    }

    public synchronized void persist() {
        try {
            if (stateFile != null) {
                if (stateFile.getParent() != null) {
                    Files.createDirectories(stateFile.getParent());
                }
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), new ArrayList<>(mounts.values()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot persist mount state to " + stateFile, e);
        }
    }

    public Path stateFile() {
        return stateFile;
    }

    /** One persisted entry. Getters/setters are required for Jackson round-trip. */
    public static class TrackedMount {
        private String volumeId;
        private String driver;
        private String targetPath;

        public TrackedMount() {
        }

        public TrackedMount(String volumeId, String driver, String targetPath) {
            this.volumeId = volumeId;
            this.driver = driver;
            this.targetPath = targetPath;
        }

        public String getVolumeId() {
            return volumeId;
        }

        public void setVolumeId(String volumeId) {
            this.volumeId = volumeId;
        }

        public String getDriver() {
            return driver;
        }

        public void setDriver(String driver) {
            this.driver = driver;
        }

        public String getTargetPath() {
            return targetPath;
        }

        public void setTargetPath(String targetPath) {
            this.targetPath = targetPath;
        }
    }
}
