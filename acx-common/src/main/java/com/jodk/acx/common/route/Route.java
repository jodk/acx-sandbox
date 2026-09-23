package com.jodk.acx.common.route;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Internal sandbox routing rule (wire format of the {@code /refresh} peer sync). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Route {

    @JsonProperty("ip")
    private String ip;

    @JsonProperty("id")
    private String id;

    @JsonProperty("uid")
    private String uid;

    @JsonProperty("owner")
    private String owner;

    @JsonProperty("state")
    private String state;

    @JsonProperty("resourceVersion")
    private String resourceVersion;

    public Route() {
    }

    public Route(String ip, String id, String uid, String owner, String state, String resourceVersion) {
        this.ip = ip;
        this.id = id;
        this.uid = uid;
        this.owner = owner;
        this.state = state;
        this.resourceVersion = resourceVersion;
    }

    public String getIp() {
        return ip;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getResourceVersion() {
        return resourceVersion;
    }

    public void setResourceVersion(String resourceVersion) {
        this.resourceVersion = resourceVersion;
    }
}
