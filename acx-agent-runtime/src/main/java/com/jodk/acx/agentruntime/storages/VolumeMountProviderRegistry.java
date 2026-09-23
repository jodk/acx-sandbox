package com.jodk.acx.agentruntime.storages;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Registry of CSI mount providers keyed by CSI driver name. */
public class VolumeMountProviderRegistry {

    private final Map<String, VolumeMountProvider> providers = new ConcurrentHashMap<>();
    private final VolumeMountProvider defaultProvider = new MountProvider();

    public void register(String driver, VolumeMountProvider provider) {
        providers.put(driver, provider);
    }

    public VolumeMountProvider get(String driver) {
        return providers.getOrDefault(driver, defaultProvider);
    }
}
