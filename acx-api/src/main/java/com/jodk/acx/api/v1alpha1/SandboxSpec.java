package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;

import java.util.List;

/** Desired state of a Sandbox. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxSpec {

    @JsonProperty("paused")
    private Boolean paused;

    @JsonProperty("persistentContents")
    private List<String> persistentContents;

    @JsonProperty("shutdownTime")
    private String shutdownTime;

    @JsonProperty("runtimes")
    private List<RuntimeConfig> runtimes;

    @JsonProperty("pauseTime")
    private String pauseTime;

    // EmbeddedSandboxTemplate (inlined)
    @JsonProperty("templateRef")
    private SandboxTemplateRef templateRef;

    @JsonProperty("template")
    private PodTemplateSpec template;

    @JsonProperty("volumeClaimTemplates")
    private List<PersistentVolumeClaim> volumeClaimTemplates;

    public Boolean getPaused() {
        return paused;
    }

    public void setPaused(Boolean paused) {
        this.paused = paused;
    }

    public List<String> getPersistentContents() {
        return persistentContents;
    }

    public void setPersistentContents(List<String> persistentContents) {
        this.persistentContents = persistentContents;
    }

    public String getShutdownTime() {
        return shutdownTime;
    }

    public void setShutdownTime(String shutdownTime) {
        this.shutdownTime = shutdownTime;
    }

    public List<RuntimeConfig> getRuntimes() {
        return runtimes;
    }

    public void setRuntimes(List<RuntimeConfig> runtimes) {
        this.runtimes = runtimes;
    }

    public String getPauseTime() {
        return pauseTime;
    }

    public void setPauseTime(String pauseTime) {
        this.pauseTime = pauseTime;
    }

    public SandboxTemplateRef getTemplateRef() {
        return templateRef;
    }

    public void setTemplateRef(SandboxTemplateRef templateRef) {
        this.templateRef = templateRef;
    }

    public PodTemplateSpec getTemplate() {
        return template;
    }

    public void setTemplate(PodTemplateSpec template) {
        this.template = template;
    }

    public List<PersistentVolumeClaim> getVolumeClaimTemplates() {
        return volumeClaimTemplates;
    }

    public void setVolumeClaimTemplates(List<PersistentVolumeClaim> volumeClaimTemplates) {
        this.volumeClaimTemplates = volumeClaimTemplates;
    }
}
