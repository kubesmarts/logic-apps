package org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.util.Objects;
import java.util.stream.Collectors;

public class LoadSQL {
    private LoadSQL() {
    }

    public static String load(String path) {
        try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            BufferedReader buff = new BufferedReader(new InputStreamReader(Objects.requireNonNull(stream, "stream from path '" + path + "' is null")));
            return buff.lines().collect(Collectors.joining("\n"));
        } catch (IOException | NullPointerException e) {
            throw new IllegalStateException("Failed to load SQL resource: " + path, e);
        }
    }
}
