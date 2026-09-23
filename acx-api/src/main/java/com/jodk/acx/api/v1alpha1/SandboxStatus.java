package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.List;

/** Observed state of a Sandbox. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxStatus {

    @JsonProperty("observedGeneration")
    private Long observedGeneration;

    @JsonProperty("phase")
    private SandboxPhase phase;

    @JsonProperty("message")
    private String message;

    @JsonProperty("conditions")
    private List<Condition> conditions;

    @JsonProperty("podInfo")
    private PodInfo podInfo;

    @JsonProperty("nodeName")
    private String nodeName;

    @JsonProperty("sandboxIp")
    private String sandboxIp;

    @JsonProperty("updateRevision")
    private String updateRevision;

    @JsonProperty("restoreFromCheckpoint")
    private String restoreFromCheckpoint;

    @JsonProperty("restoreSnapshotPath")
    private String restoreSnapshotPath;

    @JsonProperty("restoreCompletionTime")
    private String restoreCompletionTime;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public SandboxPhase getPhase() {
        return phase;
    }

    public void setPhase(SandboxPhase phase) {
        this.phase = phase;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public PodInfo getPodInfo() {
        return podInfo;
    }

    public void setPodInfo(PodInfo podInfo) {
        this.podInfo = podInfo;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getSandboxIp() {
        return sandboxIp;
    }

    public void setSandboxIp(String sandboxIp) {
        this.sandboxIp = sandboxIp;
    }

    public String getUpdateRevision() {
        return updateRevision;
    }

    public void setUpdateRevision(String updateRevision) {
        this.updateRevision = updateRevision;
    }

    public String getRestoreFromCheckpoint() {
        return restoreFromCheckpoint;
    }

    public void setRestoreFromCheckpoint(String restoreFromCheckpoint) {
        this.restoreFromCheckpoint = restoreFromCheckpoint;
    }

    public String getRestoreSnapshotPath() {
        return restoreSnapshotPath;
    }

    public void setRestoreSnapshotPath(String restoreSnapshotPath) {
        this.restoreSnapshotPath = restoreSnapshotPath;
    }

    public String getRestoreCompletionTime() {
        return restoreCompletionTime;
    }

    public void setRestoreCompletionTime(String restoreCompletionTime) {
        this.restoreCompletionTime = restoreCompletionTime;
    }
}
