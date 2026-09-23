package com.jodk.acx.agentruntime;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Result of a mount/umount attempt, serialized back to the manager over HTTP. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MountResult {

    private boolean ok;
    private String volumeId;
    private String targetPath;
    private String error;

    public MountResult() {
    }

    private MountResult(boolean ok, String volumeId, String targetPath, String error) {
        this.ok = ok;
        this.volumeId = volumeId;
        this.targetPath = targetPath;
        this.error = error;
    }

    public static MountResult ok(String volumeId, String targetPath) {
        return new MountResult(true, volumeId, targetPath, null);
    }

    public static MountResult failure(String volumeId, String targetPath, String error) {
        return new MountResult(false, volumeId, targetPath, error);
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

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

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
