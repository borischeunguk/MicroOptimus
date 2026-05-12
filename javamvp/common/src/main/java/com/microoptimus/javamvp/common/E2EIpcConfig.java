package com.microoptimus.javamvp.common;

/** Shared IPC configuration for coordinator/algo/sor multi-process E2E benchmark. */
public final class E2EIpcConfig {
    public static final String CHANNEL = "aeron:ipc";
    public static final int STREAM_COORD_TO_ALGO = 1101;
    public static final int STREAM_ALGO_TO_SOR = 1102;
    public static final int STREAM_SOR_TO_COORD = 1103;
    public static final int STREAM_COORD_TO_SVC_CONTROL = 1191;
    public static final int STREAM_SVC_TO_COORD_CONTROL = 1192;

    public static final int SERVICE_COORDINATOR = 0;
    public static final int SERVICE_ALGO = 1;
    public static final int SERVICE_SOR = 2;

    public static final int CONTROL_READY = 1;
    public static final int CONTROL_START = 2;
    public static final int CONTROL_STOP = 3;

    public static final long DEFAULT_TIMEOUT_NS = 5_000_000L;
    public static final int DEFAULT_FRAGMENT_LIMIT = 10;

    private E2EIpcConfig() {
    }
}
