package com.jodk.acx.manager.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** E2B-compatible sandbox response. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxDto {

    private String sandboxID;
    private String templateID;
    private String clientID;
    private String startedAt;
    private String endAt;
    private String domain;
    private long cpuCount;
    private long memoryMB;
    private long diskSizeMB;
    private String state;
    private Map<String, String> metadata;

    public String getSandboxID() {
        return sandboxID;
    }

    public void setSandboxID(String sandboxID) {
        this.sandboxID = sandboxID;
    }

    public String getTemplateID() {
        return templateID;
    }

    public void setTemplateID(String templateID) {
        this.templateID = templateID;
    }

    public String getClientID() {
        return clientID;
    }

    public void setClientID(String clientID) {
        this.clientID = clientID;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getEndAt() {
        return endAt;
    }

    public void setEndAt(String endAt) {
        this.endAt = endAt;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public long getCpuCount() {
        return cpuCount;
    }

    public void setCpuCount(long cpuCount) {
        this.cpuCount = cpuCount;
    }

    public long getMemoryMB() {
        return memoryMB;
    }

    public void setMemoryMB(long memoryMB) {
        this.memoryMB = memoryMB;
    }

    public long getDiskSizeMB() {
        return diskSizeMB;
    }

    public void setDiskSizeMB(long diskSizeMB) {
        this.diskSizeMB = diskSizeMB;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Map<String, String> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, String> metadata) {
        this.metadata = metadata;
    }
}
