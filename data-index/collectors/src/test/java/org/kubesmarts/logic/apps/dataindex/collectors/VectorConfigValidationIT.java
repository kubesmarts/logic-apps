package org.kubesmarts.logic.apps.dataindex.collectors;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Integration tests that validate Vector configuration files using Vector in a container.
 *
 * <p>These tests guarantee that reference configurations are syntactically valid
 * before they're consumed by logic-operator.
 *
 * <p>Uses Testcontainers to run actual Vector validation - same container image
 * that will be deployed in production.
 *
 * <p>Prerequisites:
 * <ul>
 *   <li>Docker running (for Testcontainers)</li>
 * </ul>
 */
class VectorConfigValidationIT {
    private static final Logger LOGGER = LoggerFactory.getLogger(VectorConfigValidationIT.class);

    private static final String COLLECTORS_BASE_PATH = "../vector";

    /** Helm chart keeps byte-identical copies of the collector configs (Files.Get in the ConfigMap template). */
    private static final Path HELM_VECTOR_CONFIG_DIR = Paths.get("../helm/data-index/configs/vector");

    // Vector image from Maven property (passed via system property)
    // See pom.xml: <vector.image>timberio/vector:${vector.version}-distroless-libc</vector.image>
    private static final String VECTOR_IMAGE = System.getProperty("vector.image");

    private static final Map<String, String> MODE1_ENV = Map.of(
            "NODE_NAME", "test-node",
            "WORKFLOW_NAMESPACE", "workflows",
            "POSTGRES_HOST", "postgresql.test.svc",
            "POSTGRES_PORT", "5432",
            "POSTGRES_DB", "dataindex",
            "POSTGRES_USER", "dataindex",
            "POSTGRES_PASSWORD", "test-only");

    private static final Map<String, String> MODE2_ENV = Map.of(
            "NODE_NAME", "test-node",
            "WORKFLOW_NAMESPACE", "workflows",
            "ELASTICSEARCH_HOST", "elasticsearch.test.svc",
            "ELASTICSEARCH_PORT", "9200");

    static {
        if (VECTOR_IMAGE == null) {
            throw new IllegalStateException(
                "vector.image system property is required. " +
                "Set it in pom.xml <systemPropertyVariables> or via -Dvector.image=timberio/vector:x.y.z"
            );
        }
    }

    @Test
    void mode1PostgreSQLConfigIsValid() throws Exception {
        Path configPath = getConfigPath("mode1-postgresql/vector.yaml");

        String configContent = Files.readString(configPath);
        assertThat(configContent)
                .as("Config should define the kubernetes_logs source")
                .contains("sources:")
                .contains("kubernetes_logs:");

        assertThat(configContent)
                .as("Config should define one postgres sink per raw table")
                .contains("postgres_workflow:")
                .contains("postgres_task:")
                .contains("table: workflow_events_raw")
                .contains("table: task_events_raw");

        validateWithVectorContainer(configPath, MODE1_ENV);
    }

    @Test
    void mode2ElasticsearchConfigIsValid() throws Exception {
        Path configPath = getConfigPath("mode2-elasticsearch/vector.yaml");

        // Basic structure validation
        String configContent = Files.readString(configPath);
        assertThat(configContent)
                .as("Config should define sources")
                .contains("sources:")
                .contains("kubernetes_logs:");

        assertThat(configContent)
                .as("Config should define Elasticsearch sinks")
                .contains("sinks:")
                .contains("elasticsearch_workflow:")
                .contains("elasticsearch_task:");

        // Validate with actual Vector container (uses real Vector validation!)
        validateWithVectorContainer(configPath, MODE2_ENV);
    }

    /**
     * The Helm chart ships copies of these configs (rendered into a ConfigMap via
     * {@code .Files.Get}). There is no build-time sync, so this guards against drift.
     */
    @Test
    void helmChartConfigsMatchCollectorSources() throws Exception {
        assertHelmCopyMatches("mode1-postgresql/vector.yaml", "vector-mode1-postgresql.yaml");
        assertHelmCopyMatches("mode2-elasticsearch/vector.yaml", "vector-mode2-elasticsearch.yaml");
    }

    private void assertHelmCopyMatches(String collectorRelativePath, String helmFileName) throws Exception {
        assumeThat(HELM_VECTOR_CONFIG_DIR)
                .as("Helm chart config dir should be reachable from the module root")
                .exists();

        Path source = getConfigPath(collectorRelativePath);
        Path helmCopy = HELM_VECTOR_CONFIG_DIR.resolve(helmFileName);

        assertThat(helmCopy).as("Helm chart should ship a copy at " + helmCopy).exists();
        assertThat(Files.readString(helmCopy))
                .as("Helm copy %s must be byte-identical to collector source %s", helmCopy, source)
                .isEqualTo(Files.readString(source));
    }

    /**
     * Validates Vector config using Testcontainers.
     *
     * <p>Runs Vector's validate command in a container:
     * <ul>
     *   <li>Mounts config file to /etc/vector/vector.yaml</li>
     *   <li>Provides the environment variables the config interpolates</li>
     *   <li>Accepts exit codes 0 (success) or 78 (warnings)</li>
     *   <li>Verifies no "Failed to load" errors in output</li>
     * </ul>
     *
     * @param configPath path to Vector YAML config file
     * @param env        environment variables the config references
     */
    private void validateWithVectorContainer(Path configPath, Map<String, String> env) throws Exception {
        try (GenericContainer<?> vector = new GenericContainer<>(VECTOR_IMAGE)
                .withCopyFileToContainer(
                        MountableFile.forHostPath(configPath),
                        "/etc/vector/vector.yaml"
                )
                .withEnv(env)
                .withCommand("validate", "--config-yaml", "/etc/vector/vector.yaml")
                .waitingFor(Wait.forLogMessage(".*", 1))) {

            vector.start();

            // Get container logs
            String logs = vector.getLogs();
            LOGGER.debug("Vector validation output:\n{}", logs);

            // Get exit code via inspect
            Long exitCode = vector.getDockerClient()
                    .inspectContainerCmd(vector.getContainerId())
                    .exec()
                    .getState()
                    .getExitCodeLong();

            // Check validation succeeded
            // Exit code 0 = success, 78 = loaded with warnings (acceptable)
            assertThat(exitCode)
                    .as("Vector validation should succeed (exit 0) or load with warnings (exit 78)\nImage: %s\nLogs:\n%s",
                            VECTOR_IMAGE, logs)
                    .isIn(0L, 78L);

            // Make sure there are no errors (warnings are OK)
            assertThat(logs)
                    .as("Vector config should not have errors (warnings are OK)")
                    .doesNotContain("Failed to load");
        }
    }

    /**
     * Gets path to Vector config file
     */
    private Path getConfigPath(String relativePath) {
        // Try test resources first (copied by Maven)
        Path resourcePath = Paths.get("target/test-classes/vector-configs", relativePath);

        if (Files.exists(resourcePath)) {
            return resourcePath;
        }

        // Fallback: Direct path from module root
        Path directPath = Paths.get(COLLECTORS_BASE_PATH, relativePath).normalize();
        assumeThat(directPath).as("Config file should exist: " + directPath).exists();

        return directPath;
    }
}
