package com.jodk.acx.agentruntime;

import com.jodk.acx.agentruntime.storages.CsiNodePublishRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TmpfsMountExecutorTest {

    private static CsiNodePublishRequest tmpfsRequest() {
        CsiNodePublishRequest req = new CsiNodePublishRequest();
        req.setVolumeId("tmpfs-1");
        req.setTargetPath("/mnt/envd/volumes/xyz");
        req.setVolumeContext(Map.of());
        return req;
    }

    @Test
    void commandDefaultsToTmpfsTypeAndSource() {
        TmpfsMountExecutor executor = new TmpfsMountExecutor(new MountTracker(null));
        List<String> cmd = executor.buildMountCommand(new MountRequest("tmpfs", tmpfsRequest()));
        assertEquals(List.of("mount", "-t", "tmpfs", "tmpfs", "/mnt/envd/volumes/xyz"), cmd);
    }

    @Test
    void commandEmitsDashOOnlyWhenFlagsPresent() {
        CsiNodePublishRequest req = tmpfsRequest();
        req.setMountFlags(List.of("size=64m", "mode=0777"));
        TmpfsMountExecutor executor = new TmpfsMountExecutor(new MountTracker(null));
        List<String> cmd = executor.buildMountCommand(new MountRequest("tmpfs", req));
        assertEquals(List.of("mount", "-t", "tmpfs", "-o", "size=64m,mode=0777", "tmpfs", "/mnt/envd/volumes/xyz"), cmd);
    }

    @Test
    void readOnlyAppendsRo() {
        CsiNodePublishRequest req = tmpfsRequest();
        req.setReadOnly(true);
        TmpfsMountExecutor executor = new TmpfsMountExecutor(new MountTracker(null));
        List<String> cmd = executor.buildMountCommand(new MountRequest("tmpfs", req));
        assertTrue(cmd.contains("-o"));
        assertTrue(cmd.contains("ro"));
    }
}
