package org.kubesmarts.logic.apps.dbmigrator.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import org.kubesmarts.logic.apps.dbmigrator.MigrationRunner;

@QuarkusTest
@QuarkusTestResource(PostgresTestResource.class)
class MigrationRunnerIT {

    @Inject
    MigrationRunner runner;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "db-migrator.termination-log-path")
    String terminationLogPath;

    @Test
    void all_streams_migrate_and_report_success() throws Exception {
        int exitCode = runner.run();

        assertThat(exitCode).isZero();
        String report = Files.readString(Path.of(terminationLogPath));
        assertThat(report)
                .contains("\"name\":\"RUNTIME\"")
                .contains("\"name\":\"QUARTZ\"")
                .contains("\"name\":\"DATA_INDEX\"")
                .doesNotContain("\"error\"");

        assertTableExists("workflow_instance_entity"); // runtime
        assertTableExists("qrtz_job_details"); // quartz
        assertTableExists("workflow_instances"); // data-index
    }

    private void assertTableExists(String tableName) throws Exception {
        try (Connection connection = dataSource.getConnection();
                ResultSet tables = connection.getMetaData().getTables(null, "public", tableName, null)) {
            assertThat(tables.next()).as("table %s should exist", tableName).isTrue();
        }
    }
}
