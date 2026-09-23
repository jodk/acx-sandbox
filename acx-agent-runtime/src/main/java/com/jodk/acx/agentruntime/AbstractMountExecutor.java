package com.jodk.acx.agentruntime;

import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared mount/umount lifecycle for in-pod executors (Route A): ensure target dir, run the mount
 * command, record the outcome in the {@link MountTracker}. Mounting an already-tracked volume is
 * idempotent; {@code umount} removes the tracker entry and best-effort removes the target dir.
 */
public abstract class AbstractMountExecutor implements MountExecutor {

    private static final long COMMAND_TIMEOUT_SECONDS = 30;

    protected final MountTracker tracker;

    protected AbstractMountExecutor(MountTracker tracker) {
        this.tracker = tracker;
    }

    /** File-system type used by {@code mount -t} when the request carries none. */
    protected abstract String defaultFsType();

    /** Source argument placed before the target, e.g. a bound host path or {@code tmpfs}. */
    protected abstract String sourceOf(CsiNodePublishRequest request);

    /** Optional per-driver validation of volume context before assembling the command. */
    protected void validate(CsiNodePublishRequest request) {
    }

    /** Per-driver hook invoked just before the mount command (e.g. create a bind source subdir). */
    protected void prepareSource(CsiNodePublishRequest request) {
    }

    @Override
    public final MountResult mount(MountRequest mountRequest) {
        CsiNodePublishRequest req = mountRequest == null ? null : mountRequest.getRequest();
        if (req == null) {
            return MountResult.failure(null, null, "request is missing");
        }
        String volumeId = req.getVolumeId();
        String target = req.getTargetPath();
        if (volumeId == null || volumeId.isBlank()) {
            return MountResult.failure(volumeId, target, "volumeId is required");
        }
        if (target == null || target.isBlank()) {
            return MountResult.failure(volumeId, target, "targetPath is required");
        }
        synchronized (tracker) {
            if (tracker.isTracked(volumeId)) {
                return MountResult.ok(volumeId, tracker.targetPathOf(volumeId));
            }
        }
        final List<String> command;
        try {
            validate(req);
            command = buildMountCommand(mountRequest);
        } catch (IllegalArgumentException e) {
            return MountResult.failure(volumeId, target, e.getMessage());
        }
        String mkdirError = run(List.of("mkdir", "-p", target));
        if (mkdirError != null) {
            return MountResult.failure(volumeId, target, mkdirError);
        }
        prepareSource(req);
        String error = run(command);
        if (error != null) {
            return MountResult.failure(volumeId, target, error);
        }
        List<String> post = postMountCommand(req, target);
        if (!post.isEmpty()) {
            String postError = run(post);
            if (postError != null) {
                run(List.of("umount", target)); // best-effort cleanup of the half-applied mount
                return MountResult.failure(volumeId, target, postError);
            }
        }
        tracker.track(volumeId, driver(), target);
        tracker.persist();
        return MountResult.ok(volumeId, target);
    }

    @Override
    public final MountResult umount(String volumeId) {
        String target;
        synchronized (tracker) {
            if (!tracker.isTracked(volumeId)) {
                return MountResult.failure(volumeId, null, "not mounted");
            }
            target = tracker.targetPathOf(volumeId);
        }
        String error = run(buildUmountCommand(target));
        if (error != null) {
            // A mount still referenced by the running app container (open file / cwd) returns EBUSY.
            // This is expected during teardown: detach lazily so the mount point disappears from every
            // mount namespace and nothing leaks on the worker node after the pod is gone. A lazy
            // unmount never touches the mounted data (the source stays intact on the node).
            String lazyError = run(List.of("umount", "-l", target));
            if (lazyError != null) {
                return MountResult.failure(volumeId, target, lazyError);
            }
        }
        // best effort: the dir may still be busy (open files) — that is fine, the mount is gone.
        run(List.of("rmdir", target));
        tracker.untrack(volumeId);
        tracker.persist();
        return MountResult.ok(volumeId, target);
    }

    /** Full {@code mount} invocation. Package-private for unit-test assertions. */
    List<String> buildMountCommand(MountRequest mountRequest) {
        CsiNodePublishRequest req = mountRequest.getRequest();
        List<String> command = new ArrayList<>();
        command.add("mount");
        command.add("-t");
        command.add(req.getFsType() != null && !req.getFsType().isBlank() ? req.getFsType() : defaultFsType());
        String options = mountOptions(req);
        if (!options.isEmpty()) {
            command.add("-o");
            command.add(options);
        }
        command.add(sourceOf(req));
        command.add(req.getTargetPath());
        return command;
    }

    List<String> buildUmountCommand(String target) {
        return List.of("umount", target);
    }

    /** Optional follow-up mount step (e.g. {@code remount,bind,ro}) run after the primary mount succeeds. */
    protected List<String> postMountCommand(CsiNodePublishRequest request, String target) {
        return List.of();
    }

    private String mountOptions(CsiNodePublishRequest req) {
        List<String> flags = new ArrayList<>();
        if (req.getMountFlags() != null) {
            flags.addAll(req.getMountFlags());
        }
        if (req.isReadOnly() && !flags.contains("ro")) {
            flags.add("ro");
        }
        return String.join(",", flags);
    }

    static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "command timed out: " + String.join(" ", command);
            }
            if (process.exitValue() == 0) {
                return null;
            }
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return "command failed (" + String.join(" ", command) + ") exit=" + process.exitValue()
                    + (output.isEmpty() ? "" : ": " + output);
        } catch (IOException e) {
            return "cannot execute " + command.get(0) + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted while running " + command.get(0);
        }
    }
}
