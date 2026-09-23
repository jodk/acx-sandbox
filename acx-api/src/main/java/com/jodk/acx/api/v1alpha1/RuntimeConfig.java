package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Runtime configuration for a sandbox object. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuntimeConfig {

    @JsonProperty("name")
    private String name;

    public RuntimeConfig() {
    }

    public RuntimeConfig(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
