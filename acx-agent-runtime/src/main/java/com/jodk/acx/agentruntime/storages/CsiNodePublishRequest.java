package com.jodk.acx.agentruntime.storages;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * JSON representation of a CSI NodePublishVolumeRequest. The real in-sandbox CLI consumes a
 * protobuf-encoded request; this POJO captures the same fields for a Java-only encoding.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CsiNodePublishRequest {

    private String volumeId;
    private String targetPath;
    private String fsType;
    private boolean readOnly;
    private String accessMode;
    private List<String> mountFlags;
    private Map<String, String> volumeContext;
    private Map<String, String> publishContext;
    private Map<String, String> secrets;

    public String getVolumeId() {
        return volumeId;
    }

    public void setVolumeId(String volumeId) {
        this.volumeId = volumeId;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public String getFsType() {
        return fsType;
    }

    public void setFsType(String fsType) {
        this.fsType = fsType;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public void setReadOnly(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public String getAccessMode() {
        return accessMode;
    }

    public void setAccessMode(String accessMode) {
        this.accessMode = accessMode;
    }

    public List<String> getMountFlags() {
        return mountFlags;
    }

    public void setMountFlags(List<String> mountFlags) {
        this.mountFlags = mountFlags;
    }

    public Map<String, String> getVolumeContext() {
        return volumeContext;
    }

    public void setVolumeContext(Map<String, String> volumeContext) {
        this.volumeContext = volumeContext;
    }

    public Map<String, String> getPublishContext() {
        return publishContext;
    }

    public void setPublishContext(Map<String, String> publishContext) {
        this.publishContext = publishContext;
    }

    public Map<String, String> getSecrets() {
        return secrets;
    }

    public void setSecrets(Map<String, String> secrets) {
        this.secrets = secrets;
    }
}
