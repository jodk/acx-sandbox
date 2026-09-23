package com.jodk.acx.agentruntime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Driver-keyed executor lookup. Route-B executors (node-level) register here the same way. */
public final class MountRegistry {

    private final Map<String, MountExecutor> executors = new LinkedHashMap<>();

    public MountRegistry register(MountExecutor executor) {
        if (executor == null || executor.driver() == null || executor.driver().isBlank()) {
            throw new IllegalArgumentException("executor must expose a non-blank driver name");
        }
        executors.put(executor.driver(), executor);
        return this;
    }

    public MountExecutor get(String driver) {
        return executors.get(driver);
    }

    public boolean supports(String driver) {
        return driver != null && executors.containsKey(driver);
    }

    public Set<String> drivers() {
        return executors.keySet();
    }

    public String driverList() {
        return executors.keySet().stream().sorted().collect(Collectors.joining(","));
    }

    public static MountRegistry withDefaults(MountTracker tracker) {
        return new MountRegistry()
                .register(new TmpfsMountExecutor(tracker))
                .register(new BindMountExecutor(tracker));
    }
}
