package com.jodk.acx.agentruntime.storages;

import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.Secret;

/** Default CSI mount provider. */
public class MountProvider implements VolumeMountProvider {

    @Override
    public CsiNodePublishRequest generateCsiNodePublishVolumeRequest(PersistentVolume pv, Secret secret,
                                                                     String mountPath, String subPath, boolean readOnly) {
        return CsiNodePublishRequestBuilder.build(pv, secret, mountPath, subPath, readOnly);
    }
}
