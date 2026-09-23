package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/** Snapshot of the backing pod. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PodInfo {

    @JsonProperty("annotations")
    private Map<String, String> annotations;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("nodeName")
    private String nodeName;

    @JsonProperty("podIP")
    private String podIP;

    @JsonProperty("podUID")
    private String podUID;

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public String getNodeName() {
        return nodeName;
    }

    public void setNodeName(String nodeName) {
        this.nodeName = nodeName;
    }

    public String getPodIP() {
        return podIP;
    }

    public void setPodIP(String podIP) {
        this.podIP = podIP;
    }

    public String getPodUID() {
        return podUID;
    }

    public void setPodUID(String podUID) {
        this.podUID = podUID;
    }
}
