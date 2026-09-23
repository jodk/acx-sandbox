package com.jodk.acx.common.peers;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Peer discovery backed by the Kubernetes API (replaces hashicorp/memberlist).
 * Lists pods matching {@code labelSelector} in {@code namespace} and returns their pod IPs.
 */
public class K8sPeers implements Peers {

    private final KubernetesClient client;
    private final String namespace;
    private final Map<String, String> selector;
    private final String nodeName;

    public K8sPeers(KubernetesClient client, String namespace, String labelSelector, String nodeName) {
        this.client = client;
        this.namespace = namespace;
        this.selector = parseSelector(labelSelector);
        this.nodeName = nodeName;
    }

    @Override
    public List<Peer> getPeers() {
        PodList pods = client.pods().inNamespace(namespace).withLabels(selector).list();
        List<Peer> peers = new ArrayList<>();
        for (Pod pod : pods.getItems()) {
            String name = pod.getMetadata().getName();
            if (name != null && name.equals(nodeName)) {
                continue;
            }
            String ip = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
            if (ip != null && !ip.isEmpty() && !"127.0.0.1".equals(ip)) {
                peers.add(new Peer(ip, name));
            }
        }
        return peers;
    }

    private static Map<String, String> parseSelector(String labelSelector) {
        Map<String, String> result = new LinkedHashMap<>();
        if (labelSelector == null || labelSelector.isBlank()) {
            return result;
        }
        for (String part : labelSelector.split(",")) {
            String trimmed = part.trim();
            int idx = trimmed.indexOf('=');
            if (idx > 0) {
                result.put(trimmed.substring(0, idx), trimmed.substring(idx + 1));
            }
        }
        return result;
    }
}
