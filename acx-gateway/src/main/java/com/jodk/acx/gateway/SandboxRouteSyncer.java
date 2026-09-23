package com.jodk.acx.gateway;

import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.common.route.Route;
import com.jodk.acx.common.route.RouteStore;
import com.jodk.acx.common.sandbox.SandboxUtils;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Feeds the route registry from Sandbox CR events. */
@Component
public class SandboxRouteSyncer {

    private final KubernetesClient client;
    private final RouteStore routeStore;

    public SandboxRouteSyncer(KubernetesClient client, RouteStore routeStore) {
        this.client = client;
        this.routeStore = routeStore;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        client.resources(Sandbox.class).inAnyNamespace().inform(new ResourceEventHandler<Sandbox>() {
            @Override
            public void onAdd(Sandbox sandbox) {
                routeStore.setRoute(toRoute(sandbox));
            }

            @Override
            public void onUpdate(Sandbox oldObj, Sandbox newObj) {
                routeStore.setRoute(toRoute(newObj));
            }

            @Override
            public void onDelete(Sandbox sandbox, boolean deletedFinalStateUnknown) {
                routeStore.deleteRoute(SandboxUtils.getSandboxId(sandbox));
            }
        });
    }

    private static Route toRoute(Sandbox sandbox) {
        Map<String, String> annotations = sandbox.getMetadata().getAnnotations();
        String owner = annotations != null ? annotations.get(ApiConstants.ANNOTATION_OWNER) : null;
        return new Route(
                sandbox.getStatus() != null ? sandbox.getStatus().getSandboxIp() : null,
                SandboxUtils.getSandboxId(sandbox),
                sandbox.getMetadata().getUid(),
                owner,
                SandboxUtils.getSandboxState(sandbox).state(),
                sandbox.getMetadata().getResourceVersion());
    }
}
