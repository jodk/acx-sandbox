package com.jodk.acx.agentruntime.storages;

import io.fabric8.kubernetes.api.model.CSIPersistentVolumeSource;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.Secret;

import io.fabric8.kubernetes.api.model.ObjectMeta;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Builds a CSI NodePublishVolumeRequest from a PV + Secret. */
public final class CsiNodePublishRequestBuilder {

    private CsiNodePublishRequestBuilder() {
    }

    public static CsiNodePublishRequest build(PersistentVolume pv, Secret secret,
                                              String mountPath, String subPath, boolean readOnly) {
        CsiNodePublishRequest request = new CsiNodePublishRequest();
        CSIPersistentVolumeSource csi = pv.getSpec() != null ? pv.getSpec().getCsi() : null;
        ObjectMeta meta = pv.getMetadata();

        String volumeId = csi != null ? csi.getVolumeHandle() : null;
        request.setVolumeId(volumeId != null && !volumeId.isBlank() ? volumeId
                : (meta != null ? meta.getName() : null));
        request.setTargetPath(mountPath);
        request.setFsType(csi != null ? csi.getFsType() : null);
        request.setReadOnly(readOnly || Storages.isPureReadOnly(pv.getSpec() != null ? pv.getSpec().getAccessModes() : null));
        request.setAccessMode(primaryAccessMode(pv));
        request.setMountFlags(pv.getSpec() != null ? pv.getSpec().getMountOptions() : null);
        request.setVolumeContext(volumeContextWithSubPath(csi, subPath));
        if (secret != null) {
            request.setSecrets(decodeSecret(secret));
        }
        return request;
    }

    /**
     * Builds a publish request from raw volume attributes instead of a PV — used for dynamic mounts
     * whose source is not a pre-created PersistentVolume (a pool host root bound directly or an
     * anonymous tmpfs).
     *
     * @param volumeId      unique instance id; the manager substitutes its own mountId afterwards
     * @param fsType        executor file-system type ({@code bind} / {@code tmpfs})
     * @param volumeContext driver-specific attributes (e.g. {@code path})
     * @param mountFlags    optional {@code -o} flags (e.g. {@code size=256m} for tmpfs)
     * @param mountPath     absolute target inside the shared mount root
     * @param subPath       optional sub-directory merged onto {@code volumeContext["path"]}
     * @param readOnly      whether to remount read-only after publishing
     */
    public static CsiNodePublishRequest buildFromContext(String volumeId, String fsType,
                                                         Map<String, String> volumeContext, List<String> mountFlags,
                                                         String mountPath, String subPath, boolean readOnly) {
        CsiNodePublishRequest request = new CsiNodePublishRequest();
        request.setVolumeId(volumeId);
        request.setTargetPath(mountPath);
        request.setFsType(fsType);
        request.setReadOnly(readOnly);
        request.setMountFlags(mountFlags);
        request.setVolumeContext(volumeContextWithSubPath(volumeContext, subPath));
        return request;
    }

    /**
     * Copies {@code volumeAttributes} and, when a subPath is requested, joins it onto the
     * {@code path} attribute (default {@code "/"}) — mirroring the upstream merge-and-validate
     * semantics so a mount can address a sub-directory of the exported volume.
     */
    private static Map<String, String> volumeContextWithSubPath(CSIPersistentVolumeSource csi, String subPath) {
        Map<String, String> ctx = new HashMap<>();
        if (csi != null && csi.getVolumeAttributes() != null) {
            ctx.putAll(csi.getVolumeAttributes());
        }
        return volumeContextWithSubPath(ctx, subPath);
    }

    private static Map<String, String> volumeContextWithSubPath(Map<String, String> attrs, String subPath) {
        Map<String, String> ctx = new HashMap<>();
        if (attrs != null) {
            ctx.putAll(attrs);
        }
        if (subPath != null && !subPath.isBlank()) {
            String base = ctx.getOrDefault("path", "/");
            ctx.put("path", mergeAndValidatePaths(base, subPath));
        }
        return ctx;
    }

    public static String mergeAndValidatePaths(String basePath, String subPath) {
        if (basePath == null || !basePath.startsWith("/")) {
            throw new IllegalArgumentException("base path must be an absolute path starting with /, got: " + basePath);
        }
        String clean = subPath;
        if (clean.startsWith("/")) {
            clean = clean.substring(1);
        }
        if (clean.isEmpty() || clean.equals(".") || clean.equals("..")) {
            throw new IllegalArgumentException("sub path cannot be . or ..");
        }
        if (clean.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("sub path contains null byte");
        }
        String normalized = java.nio.file.Path.of(clean).normalize().toString();
        if (normalized.equals("..") || normalized.startsWith("../") || normalized.startsWith("..\\")) {
            throw new IllegalArgumentException("sub path must not traverse to parent directory, got: " + subPath);
        }
        String merged = java.nio.file.Path.of(basePath, normalized).normalize().toString();
        String basePrefix = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        if (!merged.startsWith(basePrefix + "/") && !merged.equals(basePrefix)) {
            throw new IllegalArgumentException("merged path " + merged + " is not within base path " + basePath);
        }
        return merged;
    }

    private static String primaryAccessMode(PersistentVolume pv) {
        List<String> modes = pv.getSpec() != null ? pv.getSpec().getAccessModes() : null;
        return modes != null && !modes.isEmpty() ? modes.get(0) : null;
    }

    private static Map<String, String> decodeSecret(Secret secret) {
        Map<String, String> result = new HashMap<>();
        if (secret.getData() != null) {
            secret.getData().forEach((k, v) -> result.put(k, new String(Base64.getDecoder().decode(v))));
        }
        if (secret.getStringData() != null) {
            result.putAll(secret.getStringData());
        }
        return result;
    }
}
