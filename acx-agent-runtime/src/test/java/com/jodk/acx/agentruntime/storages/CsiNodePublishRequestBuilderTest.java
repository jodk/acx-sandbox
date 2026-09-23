package com.jodk.acx.agentruntime.storages;

import io.fabric8.kubernetes.api.model.CSIPersistentVolumeSource;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolume;
import io.fabric8.kubernetes.api.model.PersistentVolumeSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsiNodePublishRequestBuilderTest {

    private static PersistentVolume pv(String name, String volumeHandle, Map<String, String> attrs) {
        PersistentVolume pv = new PersistentVolume();
        pv.setMetadata(new ObjectMetaBuilder().withName(name).build());
        PersistentVolumeSpec spec = new PersistentVolumeSpec();
        CSIPersistentVolumeSource csi = new CSIPersistentVolumeSource();
        csi.setDriver("acx.csi.bind");
        csi.setVolumeHandle(volumeHandle);
        csi.setFsType("bind");
        csi.setVolumeAttributes(attrs);
        spec.setCsi(csi);
        spec.setAccessModes(List.of("ReadWriteMany"));
        pv.setSpec(spec);
        return pv;
    }

    @Test
    void subPathIsMergedOntoVolumeContextPath() {
        PersistentVolume pv = pv("demo-bind", "bind-hdl-1", Map.of("path", "/data/data1"));
        CsiNodePublishRequest request = CsiNodePublishRequestBuilder.build(
                pv, null, "/mnt/envd/volumes/abc", "data", false);
        assertEquals("bind-hdl-1", request.getVolumeId());
        assertEquals("/data/data1/data", request.getVolumeContext().get("path"));
        assertEquals("/mnt/envd/volumes/abc", request.getTargetPath());
    }

    @Test
    void subPathMergesOntoRootWhenContextHasNoPath() {
        PersistentVolume pv = pv("demo-bind", "bind-hdl-1", Map.of());
        CsiNodePublishRequest request = CsiNodePublishRequestBuilder.build(
                pv, null, "/mnt/envd/volumes/abc", "data", false);
        assertEquals("/data", request.getVolumeContext().get("path"));
    }

    @Test
    void volumeIdFallsBackToPvNameWhenHandleMissing() {
        PersistentVolume pv = pv("demo-bind", null, Map.of("path", "/data/data1"));
        CsiNodePublishRequest request = CsiNodePublishRequestBuilder.build(
                pv, null, "/mnt/envd/volumes/abc", null, false);
        assertEquals("demo-bind", request.getVolumeId());
    }

    @Test
    void traversalSubPathIsRejected() {
        PersistentVolume pv = pv("demo-bind", "bind-hdl-1", Map.of("path", "/data/data1"));
        assertThrows(IllegalArgumentException.class,
                () -> CsiNodePublishRequestBuilder.build(pv, null, "/mnt/envd/volumes/abc", "../../etc", false));
    }

    @Test
    void absoluteSubPathHasLeadingSlashStripped() {
        PersistentVolume pv = pv("demo-bind", "bind-hdl-1", Map.of("path", "/data/data1"));
        CsiNodePublishRequest request = CsiNodePublishRequestBuilder.build(
                pv, null, "/mnt/envd/volumes/abc", "/deep/dir", false);
        assertEquals("/data/data1/deep/dir", request.getVolumeContext().get("path"));
    }
}
