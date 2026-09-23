package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** Desired state of a SandboxClaim (batch claim of sandboxes from a pool). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxClaimSpec {

    @JsonProperty("templateName")
    private String templateName;

    @JsonProperty("replicas")
    private Integer replicas;

    @JsonProperty("shutdownTime")
    private String shutdownTime;

    @JsonProperty("claimTimeout")
    private String claimTimeout;

    @JsonProperty("ttlAfterCompleted")
    private String ttlAfterCompleted;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("annotations")
    private Map<String, String> annotations;

    @JsonProperty("envVars")
    private Map<String, String> envVars;

    @JsonProperty("inplaceUpdate")
    private SandboxClaimInplaceUpdateOptions inplaceUpdate;

    @JsonProperty("dynamicVolumesMount")
    private List<CSIMountConfig> dynamicVolumesMount;

    @JsonProperty("runtimes")
    private List<RuntimeConfig> runtimes;

    @JsonProperty("reserveFailedSandbox")
    private Boolean reserveFailedSandbox;

    @JsonProperty("createOnNoStock")
    private Boolean createOnNoStock;

    @JsonProperty("waitReadyTimeout")
    private String waitReadyTimeout;

    @JsonProperty("skipInitRuntime")
    private Boolean skipInitRuntime;

    public String getTemplateName() {
        return templateName;
    }

    public void setTemplateName(String templateName) {
        this.templateName = templateName;
    }

    public Integer getReplicas() {
        return replicas;
    }

    public void setReplicas(Integer replicas) {
        this.replicas = replicas;
    }

    public String getShutdownTime() {
        return shutdownTime;
    }

    public void setShutdownTime(String shutdownTime) {
        this.shutdownTime = shutdownTime;
    }

    public String getClaimTimeout() {
        return claimTimeout;
    }

    public void setClaimTimeout(String claimTimeout) {
        this.claimTimeout = claimTimeout;
    }

    public String getTtlAfterCompleted() {
        return ttlAfterCompleted;
    }

    public void setTtlAfterCompleted(String ttlAfterCompleted) {
        this.ttlAfterCompleted = ttlAfterCompleted;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }

    public Map<String, String> getEnvVars() {
        return envVars;
    }

    public void setEnvVars(Map<String, String> envVars) {
        this.envVars = envVars;
    }

    public SandboxClaimInplaceUpdateOptions getInplaceUpdate() {
        return inplaceUpdate;
    }

    public void setInplaceUpdate(SandboxClaimInplaceUpdateOptions inplaceUpdate) {
        this.inplaceUpdate = inplaceUpdate;
    }

    public List<CSIMountConfig> getDynamicVolumesMount() {
        return dynamicVolumesMount;
    }

    public void setDynamicVolumesMount(List<CSIMountConfig> dynamicVolumesMount) {
        this.dynamicVolumesMount = dynamicVolumesMount;
    }

    public List<RuntimeConfig> getRuntimes() {
        return runtimes;
    }

    public void setRuntimes(List<RuntimeConfig> runtimes) {
        this.runtimes = runtimes;
    }

    public Boolean getReserveFailedSandbox() {
        return reserveFailedSandbox;
    }

    public void setReserveFailedSandbox(Boolean reserveFailedSandbox) {
        this.reserveFailedSandbox = reserveFailedSandbox;
    }

    public Boolean getCreateOnNoStock() {
        return createOnNoStock;
    }

    public void setCreateOnNoStock(Boolean createOnNoStock) {
        this.createOnNoStock = createOnNoStock;
    }

    public String getWaitReadyTimeout() {
        return waitReadyTimeout;
    }

    public void setWaitReadyTimeout(String waitReadyTimeout) {
        this.waitReadyTimeout = waitReadyTimeout;
    }

    public Boolean getSkipInitRuntime() {
        return skipInitRuntime;
    }

    public void setSkipInitRuntime(Boolean skipInitRuntime) {
        this.skipInitRuntime = skipInitRuntime;
    }
}
