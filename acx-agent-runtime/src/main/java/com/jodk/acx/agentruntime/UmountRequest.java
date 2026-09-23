package com.jodk.acx.agentruntime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Wire payload of {@code POST /v1/umount}. Only the {@code volumeId} recorded at mount time is needed. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UmountRequest {

    private String volumeId;

    public UmountRequest() {
    }

    public UmountRequest(String volumeId) {
        this.volumeId = volumeId;
    }

    public String getVolumeId() {
        return volumeId;
    }

    public void setVolumeId(String volumeId) {
        this.volumeId = volumeId;
    }
}
