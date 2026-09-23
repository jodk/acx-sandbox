package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;

import java.util.List;

/** Desired state of a SandboxTemplate. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxTemplateSpec {

    @JsonProperty("template")
    private PodTemplateSpec template;

    @JsonProperty("volumeClaimTemplates")
    private List<PersistentVolumeClaim> volumeClaimTemplates;

    @JsonProperty("persistentContents")
    private List<String> persistentContents;

    @JsonProperty("runtimes")
    private List<RuntimeConfig> runtimes;

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
}
