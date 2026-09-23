package com.jodk.acx.api.v1alpha1;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** SandboxTemplate is a reusable pod/PVC template referenced by Sandbox or SandboxSet. */
@Group(ApiConstants.GROUP)
@Version(ApiConstants.VERSION)
@Kind("SandboxTemplate")
@Plural("sandboxtemplates")
@Singular("sandboxtemplate")
public class SandboxTemplate extends CustomResource<SandboxTemplateSpec, Void> implements Namespaced {
}
