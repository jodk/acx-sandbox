package com.jodk.acx.manager.mount;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Decode helpers for JSON-array string annotations carried by a pool (SandboxSet) and copied onto
 * each member Sandbox — e.g. {@code agents.kruise.io/host-mounts} (node host roots injected into the
 * agent-runtime sidecar as hostPath volumes, the only dirs a Route A bind mount can reach) and
 * {@code agents.kruise.io/dynamic-roots} (extra app-container paths where the shared mount tree is
 * surfaced so dynamic mounts appear at familiar container paths).
 */
public final class HostMounts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HostMounts() {
    }

    /** Decodes the {@code host-mounts} annotation of a Sandbox (JSON array of absolute paths). */
    public static List<String> of(Sandbox sbx) {
        return of(sbx, ApiConstants.ANNOTATION_HOST_MOUNTS);
    }

    /** Decodes a specific JSON-array annotation of a Sandbox (e.g. {@code dynamic-roots}). */
    public static List<String> of(Sandbox sbx, String annotationKey) {
        return sbx == null ? List.of() : of(sbx.getMetadata(), annotationKey);
    }

    /** Decodes a specific JSON-array annotation of any owning object (SandboxSet/Sandbox meta). */
    public static List<String> of(ObjectMeta meta, String annotationKey) {
        if (meta == null || meta.getAnnotations() == null) {
            return List.of();
        }
        return decode(meta.getAnnotations().get(annotationKey));
    }

    /** Decodes the annotation of a raw annotation value. */
    public static List<String> ofRaw(String raw) {
        return decode(raw);
    }

    private static List<String> decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = MAPPER.readValue(raw, new TypeReference<List<String>>() {
            });
            return parsed != null ? new ArrayList<>(parsed) : new ArrayList<>();
        } catch (Exception e) {
            List<String> fallback = new ArrayList<>();
            for (String part : raw.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    fallback.add(p);
                }
            }
            return fallback;
        }
    }

    /** Whether {@code path} equals a declared root or lives strictly beneath one. */
    public static boolean covers(List<String> roots, String path) {
        if (roots == null || path == null) {
            return false;
        }
        for (String root : roots) {
            if (root != null && (path.equals(root) || path.startsWith(root + "/"))) {
                return true;
            }
        }
        return false;
    }
}
