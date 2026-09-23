package com.jodk.acx.api.v1alpha1;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Inplace update options applied while claiming a sandbox. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SandboxClaimInplaceUpdateOptions {

    @JsonProperty("image")
    private String image;

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }
}
