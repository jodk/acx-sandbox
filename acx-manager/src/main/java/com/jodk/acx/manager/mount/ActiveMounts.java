package com.jodk.acx.manager.mount;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jodk.acx.api.v1alpha1.ApiConstants;
import com.jodk.acx.api.v1alpha1.Sandbox;
import com.jodk.acx.manager.model.ActiveMount;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Read/encode helpers for the {@code agents.kruise.io/active-mounts} annotation. The annotation
 * stores the active dynamic-mount list as JSON, avoiding a CRD schema change.
 */
public final class ActiveMounts {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ActiveMounts() {
    }

    public static List<ActiveMount> read(Sandbox sbx) {
        if (sbx.getMetadata() == null || sbx.getMetadata().getAnnotations() == null) {
            return new ArrayList<>();
        }
        String json = sbx.getMetadata().getAnnotations().get(ApiConstants.ANNOTATION_ACTIVE_MOUNTS);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<ActiveMount> mounts = MAPPER.readValue(json, new TypeReference<List<ActiveMount>>() {
            });
            return mounts != null ? new ArrayList<>(mounts) : new ArrayList<>();
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    public static String encode(List<ActiveMount> mounts) {
        try {
            return MAPPER.writeValueAsString(mounts);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot encode active mounts", e);
        }
    }
}
