package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.Condition;

import java.util.List;

/** Observed state of a SandboxSet. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxSetStatus {

    @JsonProperty("observedGeneration")
    private Long observedGeneration;

    @JsonProperty("replicas")
    private Integer replicas;

    @JsonProperty("availableReplicas")
    private Integer availableReplicas;

    @JsonProperty("updateRevision")
    private String updateRevision;

    @JsonProperty("conditions")
    private List<Condition> conditions;

    @JsonProperty("selector")
    private String selector;

    public Long getObservedGeneration() {
        return observedGeneration;
    }

    public void setObservedGeneration(Long observedGeneration) {
        this.observedGeneration = observedGeneration;
    }

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    public Integer getAvailableReplicas() {
        return availableReplicas;
    }

    public void setAvailableReplicas(Integer availableReplicas) {
        this.availableReplicas = availableReplicas;
    }

    public String getUpdateRevision() {
        return updateRevision;
    }

    public void setUpdateRevision(String updateRevision) {
        this.updateRevision = updateRevision;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    public String getSelector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }
}
