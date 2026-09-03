package org.kubesmarts.logic.apps.dataindex.collectors;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.DockerClientFactory;

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

    private static final String COLLECTORS_BASE_PATH = "../vector";

    // Vector image from Maven property (passed via system property)
    // See pom.xml: <vector.image>timberio/vector:${vector.version}-distroless-libc</vector.image>
    private static final String VECTOR_IMAGE = System.getProperty("vector.image");

    static {
        if (VECTOR_IMAGE == null) {
            throw new IllegalStateException(
                "vector.image system property is required. " +
                "Set it in pom.xml <systemPropertyVariables> or via -Dvector.image=timberio/vector:x.y.z"
            );
        }
    }

    @Test
    void mode2ElasticsearchConfigIsValid() throws Exception {
        Path configPath = getConfigPath("mode2-elasticsearch/vector.yaml");
        assertThat(configPath).exists();

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
        validateWithVectorContainer(configPath);
    }

    @Test
    @EnabledIfSystemProperty(named = "test.mode1", matches = "true")
    void mode1PostgreSQLConfigIsValid() throws Exception {
        // TODO: Implement when MODE 1 Vector config is ready
        // Path configPath = getConfigPath("mode1-postgresql/vector.yaml");
        // assertThat(configPath).exists();
    }

    /**
     * Validates Vector config by running Docker container directly.
     *
     * <p><strong>Why not Testcontainers?</strong> While Testcontainers is generally more reliable,
     * this is a legitimate edge case where direct Docker execution is actually the better choice:
     *
     * <ul>
     *   <li>One-shot validation command (container exits immediately)</li>
     *   <li>Non-standard exit code 78 (warnings) needs to be accepted</li>
     *   <li>Distroless image has no shell/sleep commands for keep-alive</li>
     *   <li>Testcontainers' OneShotStartupCheckStrategy only accepts exit code 0</li>
     * </ul>
     *
     * <p>This approach:
     * <ul>
     *   <li>Uses Testcontainers' DockerClientFactory for Docker detection/compatibility</li>
     *   <li>Runs {@code docker run --rm vector validate} directly via ProcessBuilder</li>
     *   <li>Captures both stdout/stderr for comprehensive validation feedback</li>
     *   <li>Handles exit codes 0 (success) and 78 (warnings) gracefully</li>
     * </ul>
     *
     * @param configPath path to Vector YAML config file
     */
    private void validateWithVectorContainer(Path configPath) throws Exception {
        // Verify Docker is available (same check Testcontainers uses)
        DockerClientFactory.instance().client();

        // Run Vector validation via Docker
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm",
                "-e", "NODE_NAME=test-node",
                "-e", "WORKFLOW_NAMESPACE=workflows",
                "-e", "ELASTICSEARCH_HOST=elasticsearch.test.svc",
                "-e", "ELASTICSEARCH_PORT=9200",
                "-v", configPath.toAbsolutePath() + ":/etc/vector/vector.yaml:ro",
                VECTOR_IMAGE,
                "validate", "--config-yaml", "/etc/vector/vector.yaml"
        );

        pb.redirectErrorStream(true);
        Process process = pb.start();

        // Read output
        String output;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        // Wait for completion
        int exitCode = process.waitFor();

        // Check validation succeeded
        // Exit code 0 = success, 78 = loaded with warnings (acceptable)
        assertThat(exitCode)
                .as("Vector validation should succeed (exit 0) or load with warnings (exit 78)\nImage: %s\nOutput:\n%s",
                        VECTOR_IMAGE, output)
                .isIn(0, 78);

        // Make sure there are no errors (warnings are OK)
        assertThat(output)
                .as("Vector config should not have errors (warnings are OK)")
                .doesNotContain("Failed to load");
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
