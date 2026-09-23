package com.jodk.acx.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** SandboxSet is a pool workload keeping a number of unused sandboxes ready. */
@Group(ApiConstants.GROUP)
@Version(ApiConstants.VERSION)
@Kind("SandboxSet")
@Plural("sandboxsets")
@Singular("sandboxset")
public class SandboxSet extends CustomResource<SandboxSetSpec, SandboxSetStatus> implements Namespaced {
}
