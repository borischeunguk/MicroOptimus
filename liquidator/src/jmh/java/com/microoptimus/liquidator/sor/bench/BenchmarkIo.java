package com.microoptimus.liquidator.sor.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

final class BenchmarkIo {
    private BenchmarkIo() {
    }

    static void writeReport(String fileName, String json) {
        Path path = Paths.get(System.getProperty("user.dir"), "perf-reports", fileName);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("failed to write benchmark report: " + e.getMessage());
        }
    }

    static String jsonLine(String key, Object value, boolean trailingComma) {
        String suffix = trailingComma ? ",\n" : "\n";
        if (value instanceof String) {
            return "  \"" + key + "\": \"" + value + "\"" + suffix;
        }
        return "  \"" + key + "\": " + value + suffix;
    }
}

