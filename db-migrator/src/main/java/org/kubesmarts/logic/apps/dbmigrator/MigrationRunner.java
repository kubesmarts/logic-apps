package org.kubesmarts.logic.apps.dbmigrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.context.Dependent;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.flyway.FlywayDataSource;
import io.quarkus.runtime.QuarkusApplication;

/**
 * Applies whichever schema streams are named in LOGIC_DB_MIGRATOR_INCLUDE (see
 * logic-operator's MigrationIncludeEnvVar), then reports the outcome as JSON on the
 * container's termination message (see MigrationReport) so the operator can harvest it.
 * Each stream has its own Flyway history via a dedicated named datasource (see
 * application.properties), isolating it from the others even though all three target the
 * same physical database.
 */
@Dependent
public class MigrationRunner implements QuarkusApplication {

    private static final Logger LOG = Logger.getLogger(MigrationRunner.class);

    private static final String RUNTIME_STREAM = "runtime";
    private static final String QUARTZ_STREAM = "quartz";
    private static final String DATA_INDEX_STREAM = "data-index";
    private static final Set<String> KNOWN_STREAMS = Set.of(RUNTIME_STREAM, QUARTZ_STREAM, DATA_INDEX_STREAM);

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

    @ConfigProperty(name = "logic.db-migrator.include")
    List<String> include;

    @ConfigProperty(name = "db-migrator.termination-log-path")
    String terminationLogPath;

    @Override
    public int run(String... args) throws Exception {
        for (String stream : include) {
            if (!KNOWN_STREAMS.contains(stream)) {
                throw new IllegalArgumentException(
                        "Unknown LOGIC_DB_MIGRATOR_INCLUDE entry '" + stream + "', expected one of " + KNOWN_STREAMS);
            }
        }

        List<StreamResult> succeeded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (String stream : include) {
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

    private StreamResult migrate(String stream) {
        Flyway flyway = switch (stream) {
            case RUNTIME_STREAM -> runtimeFlyway;
            case QUARTZ_STREAM -> quartzFlyway;
            case DATA_INDEX_STREAM -> dataIndexFlyway;
            default -> throw new IllegalStateException("Unhandled known stream '" + stream + "'");
        };
        MigrateResult result = flyway.migrate();
        LOG.infov("Stream {0}: applied {1} migration(s), now at version {2}", stream, result.migrationsExecuted,
                result.targetSchemaVersion);
        return new StreamResult(stream, result.targetSchemaVersion, result.migrationsExecuted);
    }

    private void writeReport(MigrationReport report) throws Exception {
        String json = objectMapper.writeValueAsString(report);
        LOG.info(json);
        Files.writeString(Path.of(terminationLogPath), json);
    }
}
