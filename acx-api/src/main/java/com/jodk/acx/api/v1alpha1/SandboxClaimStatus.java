package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.List;

/** Observed state of a SandboxClaim. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxClaimStatus {

    @JsonProperty("observedGeneration")
    private Long observedGeneration;

    @JsonProperty("phase")
    private SandboxClaimPhase phase;

    @JsonProperty("message")
    private String message;

    @JsonProperty("claimedReplicas")
    private Integer claimedReplicas;

    @JsonProperty("claimStartTime")
    private String claimStartTime;

    @JsonProperty("completionTime")
    private String completionTime;

    @JsonProperty("conditions")
    private List<Condition> conditions;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public SandboxClaimPhase getPhase() {
        return phase;
    }

    public void setPhase(SandboxClaimPhase phase) {
        this.phase = phase;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getClaimedReplicas() {
        return claimedReplicas;
    }

    public void setClaimedReplicas(Integer claimedReplicas) {
        this.claimedReplicas = claimedReplicas;
    }

    public String getClaimStartTime() {
        return claimStartTime;
    }

    public void setClaimStartTime(String claimStartTime) {
        this.claimStartTime = claimStartTime;
    }

    public String getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
