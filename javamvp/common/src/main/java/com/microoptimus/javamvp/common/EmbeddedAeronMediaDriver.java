package com.microoptimus.javamvp.common;

import io.aeron.driver.MediaDriver;
import io.aeron.driver.ThreadingMode;

/** Coordinator-owned embedded MediaDriver for cross-process E2E benchmark runs. */
public final class EmbeddedAeronMediaDriver implements AutoCloseable {
    private final MediaDriver mediaDriver;

    private EmbeddedAeronMediaDriver(MediaDriver mediaDriver) {
        this.mediaDriver = mediaDriver;
    }

    public static EmbeddedAeronMediaDriver launch(String aeronDir) {
        MediaDriver driver = MediaDriver.launch(new MediaDriver.Context()
            .aeronDirectoryName(aeronDir)
            .threadingMode(ThreadingMode.DEDICATED)
            .dirDeleteOnStart(true)
            .dirDeleteOnShutdown(true));
        return new EmbeddedAeronMediaDriver(driver);
    }

    @Override
    public void close() {
        mediaDriver.close();
    }
}
