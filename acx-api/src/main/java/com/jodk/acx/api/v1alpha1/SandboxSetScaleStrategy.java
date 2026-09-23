package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.fabric8.kubernetes.api.model.IntOrString;

/** Scale strategy for SandboxSet. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxSetScaleStrategy {

    @JsonProperty("maxUnavailable")
    private IntOrString maxUnavailable;

    public IntOrString getMaxUnavailable() {
        return maxUnavailable;
    }

    public void setMaxUnavailable(IntOrString maxUnavailable) {
        this.maxUnavailable = maxUnavailable;
    }
}
