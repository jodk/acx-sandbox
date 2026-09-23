package com.jodk.acx.agentruntime;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;

/**
 * Wire payload of {@code POST /v1/mount}. The {@code driver} selects the executor that interprets
 * the CSI request (today {@code bind} / {@code tmpfs}); a future Route-B node-level agent would
 * serve the exact same contract without touching the control plane.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MountRequest {

    private String driver;
    private CsiNodePublishRequest request;

    public MountRequest() {
    }

    public MountRequest(String driver, CsiNodePublishRequest request) {
        this.driver = driver;
        this.request = request;
    }

    public String getDriver() {
        return driver;
    }

    public void setDriver(String driver) {
        this.driver = driver;
    }

    public CsiNodePublishRequest getRequest() {
        return request;
    }

    public void setRequest(CsiNodePublishRequest request) {
        this.request = request;
    }
}
