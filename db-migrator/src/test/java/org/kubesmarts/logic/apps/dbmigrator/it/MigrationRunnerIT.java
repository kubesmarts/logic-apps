package org.kubesmarts.logic.apps.dbmigrator.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

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

    @ConfigProperty(name = "db-migrator.termination-log-path")
    String terminationLogPath;

    @Test
    void all_streams_migrate_and_report_success() throws Exception {
        int exitCode = runner.run();

        assertThat(exitCode).isZero();
        String report = Files.readString(Path.of(terminationLogPath));
        assertThat(report)
                .contains("\"name\":\"runtime\"")
                .contains("\"name\":\"quartz\"")
                .contains("\"name\":\"data-index\"")
                .doesNotContain("\"error\"");
    }
}
