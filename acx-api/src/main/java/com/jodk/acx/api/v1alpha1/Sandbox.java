package com.jodk.acx.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** Sandbox is a single sandbox instance backed by a Kubernetes Pod. */
@Group(ApiConstants.GROUP)
@Version(ApiConstants.VERSION)
@Kind("Sandbox")
@Plural("sandboxes")
@Singular("sandbox")
public class Sandbox extends CustomResource<SandboxSpec, SandboxStatus> implements Namespaced {
}
