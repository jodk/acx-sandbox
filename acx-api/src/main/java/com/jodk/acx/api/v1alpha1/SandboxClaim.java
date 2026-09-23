package com.jodk.acx.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** SandboxClaim claims a batch of sandboxes from a SandboxSet pool. */
@Group(ApiConstants.GROUP)
@Version(ApiConstants.VERSION)
@Kind("SandboxClaim")
@Plural("sandboxclaims")
@Singular("sandboxclaim")
public class SandboxClaim extends CustomResource<SandboxClaimSpec, SandboxClaimStatus> implements Namespaced {
}
