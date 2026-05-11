package com.microoptimus.javamvp.common;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class BenchmarkSupport {
    private BenchmarkSupport() {}

    public static final class LatencyRecorder {
        private long[] values;
        private int size;

        public LatencyRecorder(int capacity) {
            this.values = new long[Math.max(16, capacity)];
            this.size = 0;
        }

        public void record(long valueNs) {
            if (size == values.length) {
                values = Arrays.copyOf(values, values.length * 2);
            }
            values[size++] = valueNs;
        }

        public long quantile(double q) {
            long[] sorted = Arrays.copyOf(values, size);
            Arrays.sort(sorted);
            int idx = (int) Math.min(sorted.length - 1, Math.floor(q * (sorted.length - 1)));
            return sorted[idx];
        }

        public int size() {
            return size;
        }
    }

    public static void writeJson(Path file, String json) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
    }
}

