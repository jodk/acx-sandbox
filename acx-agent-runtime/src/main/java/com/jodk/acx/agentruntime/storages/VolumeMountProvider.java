package com.jodk.acx.agentruntime.storages;

import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.Secret;

/** Generates a CSI NodePublishVolumeRequest for a dynamic volume mount. */
public interface VolumeMountProvider {

    CsiNodePublishRequest generateCsiNodePublishVolumeRequest(PersistentVolume pv, Secret secret,
                                                              String mountPath, String subPath, boolean readOnly);
}
