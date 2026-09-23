package com.jodk.acx.operator;

import com.jodk.acx.operator.controller.SandboxClaimReconciler;
import com.jodk.acx.operator.controller.SandboxReconciler;
import com.jodk.acx.operator.controller.SandboxSetReconciler;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;

/** Entry point for the agent-sandbox-controller (operator). */
public final class OperatorApplication {

    private OperatorApplication() {
    }

    public static void main(String[] args) {
        try (KubernetesClient client = new KubernetesClientBuilder().build()) {
            Operator operator = new Operator(overrider -> overrider.withKubernetesClient(client));
            operator.register(new SandboxReconciler(client));
            operator.register(new SandboxSetReconciler(client));
            operator.register(new SandboxClaimReconciler(client));
            operator.start();
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
