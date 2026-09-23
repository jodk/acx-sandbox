package com.jodk.acx.common;

/** Cross-component constants, ported from pkg/sandbox-manager/consts and pkg/proxy. */
public final class SandboxManagerConstants {

    private SandboxManagerConstants() {
    }

    public static final int DEFAULT_CLAIM_WORKERS = 500;
    public static final int DEFAULT_CREATE_QPS = 49;
    public static final int DEFAULT_POOLING_CANDIDATE_COUNTS = 100;
    public static final String DEFAULT_WAIT_READY_TIMEOUT = "60s";
    public static final String DEFAULT_WAIT_CHECKPOINT_TIMEOUT = "60s";

    public static final int EXT_PROC_PORT = 9002;
    public static final int DEFAULT_EXT_PROC_CONCURRENCY = 1000;
    public static final int RUNTIME_PORT = 49983;
    public static final int SYSTEM_PORT = 7789;
    public static final String SHUTDOWN_TIMEOUT = "90s";
    public static final long REQUEST_PEER_TIMEOUT_MS = 100;

    public static final String REFRESH_PATH = "/refresh";
}
