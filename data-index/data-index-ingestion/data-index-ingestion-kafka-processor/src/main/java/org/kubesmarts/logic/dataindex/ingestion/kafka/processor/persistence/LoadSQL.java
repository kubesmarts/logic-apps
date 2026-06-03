package org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class LoadSQL {
    private LoadSQL() {}

    public static String load(String path) {
        try (InputStream is = LoadSQL.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IllegalStateException("SQL resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load SQL resource: " + path, e);
        }
    }
}
