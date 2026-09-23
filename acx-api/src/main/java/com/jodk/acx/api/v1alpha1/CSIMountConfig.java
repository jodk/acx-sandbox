package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Dynamic CSI volume mount request. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSIMountConfig {

    @JsonProperty("mountID")
    private String mountID;

    @JsonProperty("pvName")
    private String pvName;

    @JsonProperty("mountPath")
    private String mountPath;

    @JsonProperty("subPath")
    private String subPath;

    @JsonProperty("readOnly")
    private Boolean readOnly;

    public String getMountID() {
        return mountID;
    }

    public void setMountID(String mountID) {
        this.mountID = mountID;
    }

    public String getPvName() {
        return pvName;
    }

    public void setPvName(String pvName) {
        this.pvName = pvName;
    }

    public String getMountPath() {
        return mountPath;
    }

    public void setMountPath(String mountPath) {
        this.mountPath = mountPath;
    }

    public String getSubPath() {
        return subPath;
    }

    public void setSubPath(String subPath) {
        this.subPath = subPath;
    }

    public Boolean getReadOnly() {
        return readOnly;
    }

    public void setReadOnly(Boolean readOnly) {
        this.readOnly = readOnly;
    }
}
