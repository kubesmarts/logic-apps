package org.kubesmarts.logic.apps.dbmigrator.it;

import java.util.Map;

import org.testcontainers.containers.PostgreSQLContainer;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;

public class PostgresTestResource implements QuarkusTestResourceLifecycleManager {

    private PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        return Map.ofEntries(
                Map.entry("quarkus.datasource.jdbc.url", postgres.getJdbcUrl()),
                Map.entry("quarkus.datasource.username", postgres.getUsername()),
                Map.entry("quarkus.datasource.password", postgres.getPassword()),
                Map.entry("quarkus.datasource.flow-runtime.jdbc.url", postgres.getJdbcUrl()),
                Map.entry("quarkus.datasource.flow-runtime.username", postgres.getUsername()),
                Map.entry("quarkus.datasource.flow-runtime.password", postgres.getPassword()),
                Map.entry("quarkus.datasource.flow-quartz.jdbc.url", postgres.getJdbcUrl()),
                Map.entry("quarkus.datasource.flow-quartz.username", postgres.getUsername()),
                Map.entry("quarkus.datasource.flow-quartz.password", postgres.getPassword()),
                Map.entry("quarkus.datasource.data-index.jdbc.url", postgres.getJdbcUrl()),
                Map.entry("quarkus.datasource.data-index.username", postgres.getUsername()),
                Map.entry("quarkus.datasource.data-index.password", postgres.getPassword()));
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
        }
    }
}
