package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;

import java.util.List;

/** Desired state of a SandboxSet (a pool of unused sandboxes). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxSetSpec {

    @JsonProperty("replicas")
    private Integer replicas;

    @JsonProperty("persistentContents")
    private List<String> persistentContents;

    @JsonProperty("runtimes")
    private List<RuntimeConfig> runtimes;

    // EmbeddedSandboxTemplate (inlined)
    @JsonProperty("templateRef")
    private SandboxTemplateRef templateRef;

    @JsonProperty("template")
    private PodTemplateSpec template;

    @JsonProperty("volumeClaimTemplates")
    private List<PersistentVolumeClaim> volumeClaimTemplates;

    @JsonProperty("scaleStrategy")
    private SandboxSetScaleStrategy scaleStrategy;

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    public List<String> getPersistentContents() {
        return persistentContents;
    }

    public void setPersistentContents(List<String> persistentContents) {
        this.persistentContents = persistentContents;
    }

    public List<RuntimeConfig> getRuntimes() {
        return runtimes;
    }

    public void setRuntimes(List<RuntimeConfig> runtimes) {
        this.runtimes = runtimes;
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

    public SandboxSetScaleStrategy getScaleStrategy() {
        return scaleStrategy;
    }

    public void setScaleStrategy(SandboxSetScaleStrategy scaleStrategy) {
        this.scaleStrategy = scaleStrategy;
    }
}
