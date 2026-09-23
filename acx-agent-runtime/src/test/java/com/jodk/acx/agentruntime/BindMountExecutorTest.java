package com.jodk.acx.agentruntime;

import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BindMountExecutorTest {

    private static final String HOST_ROOT = "/gridview-niesl113-10033113";

    private static CsiNodePublishRequest bindRequest() {
        CsiNodePublishRequest req = new CsiNodePublishRequest();
        req.setVolumeId("nfs-bind-demo");
        req.setTargetPath("/mnt/envd/volumes/abc");
        req.setVolumeContext(Map.of("path", HOST_ROOT));
        return req;
    }

    @Test
    void commandIsBindMountWithoutFstypeOrSourceColon() {
        BindMountExecutor executor = new BindMountExecutor(new MountTracker(null));
        List<String> cmd = executor.buildMountCommand(new MountRequest("bind", bindRequest()));
        assertEquals(List.of("mount", "--bind", HOST_ROOT, "/mnt/envd/volumes/abc"), cmd);
    }

    @Test
    void readOnlyAddsRemountStep() {
        CsiNodePublishRequest req = bindRequest();
        req.setReadOnly(true);
        BindMountExecutor executor = new BindMountExecutor(new MountTracker(null));
        assertEquals(List.of("mount", "-o", "remount,bind,ro", "/mnt/envd/volumes/abc"),
                executor.postMountCommand(req, "/mnt/envd/volumes/abc"));
    }

    @Test
    void writableHasNoRemountStep() {
        BindMountExecutor executor = new BindMountExecutor(new MountTracker(null));
        assertTrue(executor.postMountCommand(bindRequest(), "/mnt/envd/volumes/abc").isEmpty());
    }

    @Test
    void missingSourceFailsWithoutRunningMount() {
        CsiNodePublishRequest req = bindRequest();
        req.setVolumeContext(Map.of());
        BindMountExecutor executor = new BindMountExecutor(new MountTracker(null));
        MountResult result = executor.mount(new MountRequest("bind", req));
        assertFalse(result.isOk());
        assertNotNull(result.getError());
    }

    @Test
    void relativeSourceRejected() {
        CsiNodePublishRequest req = bindRequest();
        req.setVolumeContext(Map.of("path", "relative/path"));
        BindMountExecutor executor = new BindMountExecutor(new MountTracker(null));
        MountResult result = executor.mount(new MountRequest("bind", req));
        assertFalse(result.isOk());
        assertNotNull(result.getError());
    }

    @Test
    void registryServesBindDriverByDefault() {
        BindMountExecutor executor = (BindMountExecutor) MountRegistry.withDefaults(new MountTracker(null)).get("bind");
        assertNotNull(executor);
        assertEquals("bind", executor.driver());
    }
}
