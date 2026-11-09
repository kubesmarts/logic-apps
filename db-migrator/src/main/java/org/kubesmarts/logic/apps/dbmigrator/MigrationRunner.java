package org.kubesmarts.logic.apps.dbmigrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.runtime.QuarkusApplication;

@Dependent
public class MigrationRunner implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(MigrationRunner.class);



    @Inject
    @FlywayDataSource("flow-runtime")
    Flyway runtimeFlyway;

    @Inject
    @FlywayDataSource("flow-quartz")
    Flyway quartzFlyway;

    @Inject
    @FlywayDataSource("data-index")
    Flyway dataIndexFlyway;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    LogicAppsMigratorConfig config;

    @Override
    public int run(String... args) throws Exception {

        List<StreamResult> succeeded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (MigrationStreams stream : config.include()) {
            try {
                succeeded.add(migrate(stream));
            } catch (Exception e) {
                LOG.errorv(e, "Stream {0} failed to migrate", stream);
                errors.add(stream + ": " + e.getMessage());
            }
        }

        String error = errors.isEmpty() ? null : String.join("; ", errors);
        writeReport(new MigrationReport(succeeded, error));
        return errors.isEmpty() ? 0 : 1;
    }

    private StreamResult migrate(MigrationStreams stream) {
        Flyway flyway = switch (stream) {
            case RUNTIME -> runtimeFlyway;
            case QUARTZ -> quartzFlyway;
            case DATA_INDEX -> dataIndexFlyway;
        };
        MigrateResult result = flyway.migrate();
        LOG.infov("Stream {0}: applied {1} migration(s), now at version {2}", stream, result.migrationsExecuted,
                result.targetSchemaVersion);
        return new StreamResult(stream, result.targetSchemaVersion, result.migrationsExecuted);
    }

    private void writeReport(MigrationReport report) throws Exception {
        String json = objectMapper.writeValueAsString(report);
        LOG.info(json);
        Files.writeString(Path.of(config.terminationLogPath()), json);
    }
}
