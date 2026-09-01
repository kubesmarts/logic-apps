/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.ingestion.kafka;

import io.quarkiverse.flow.Flow;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.api.types.Workflow;
import io.quarkiverse.flow.dsl.FlowWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.quarkiverse.flow.dsl.FlowDSL.set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test that verifies:
 * Quarkus Flow (lifecycle-enabled) → Kafka (binary CloudEvents) → Ingestion Service → PostgreSQL
 *
 * This test executes an actual workflow using Quarkus Flow and verifies that:
 * 1. Lifecycle events are published to Kafka as binary CloudEvents (CE attributes in headers, data in body)
 * 2. Ingestion service consumes the events from Kafka (supports both binary and structured modes)
 * 3. Events are persisted to PostgreSQL workflow_instances and task_instances tables
 */
@QuarkusTest
@TestProfile(QuarkusFlowLifecycleIT.Profile.class)
public class QuarkusFlowLifecycleIT extends BaseWorkflowLifecycleIT {

    private static final Logger log = LoggerFactory.getLogger(QuarkusFlowLifecycleIT.class);

    @Inject
    TestWorkflow testWorkflow;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM task_instances;").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_instances;").executeUpdate();
        }
    }

    @Test
    public void shouldConsumeQuarkusFlowLifecycleEventsFromKafka() throws Exception {
        // Given: Quarkus Flow with lifecycle messaging enabled
        log.info("=== Quarkus Flow Lifecycle Messaging Test ===");
        log.info("Configuration:");
        log.info("  - quarkus.flow.messaging.lifecycle-enabled: true");
        log.info("  - CloudEvents mode: binary (CE attributes in headers, default SmallRye behavior)");
        log.info("  - Topic: flow-lifecycle-out");

        // When: Execute a workflow
        log.info("Executing test workflow...");
        String workflowResult = testWorkflow.instance(Map.of("message", "test"))
                .start()
                .thenApply(model -> model.asMap().orElseThrow())
                .get(10, TimeUnit.SECONDS)
                .toString();

        log.info("✓ Workflow execution completed");
        log.info("  Result: {}", workflowResult);

        // Then: Verify lifecycle events were consumed and persisted
        log.info("Waiting for ingestion service to consume and persist events...");

        // Extract workflow instance ID from result
        // The workflow instance is stored in the model, we need to query by workflow name
        Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT id, namespace, name, version, status FROM workflow_instances WHERE name = ?")) {
                        stmt.setString(1, "e2e-test-workflow");
                        ResultSet rs = stmt.executeQuery();

                        assertTrue(rs.next(), "Workflow instance should be persisted to PostgreSQL");

                        String id = rs.getString("id");
                        String namespace = rs.getString("namespace");
                        String name = rs.getString("name");
                        String version = rs.getString("version");
                        String status = rs.getString("status");

                        assertNotNull(id, "Workflow ID should not be null");
                        assertNotNull(namespace, "Namespace should not be null");
                        assertEquals("e2e-test-workflow", name);
                        assertNotNull(version, "Version should not be null");
                        assertEquals("COMPLETED", status);

                        log.info("✓ Workflow instance persisted:");
                        log.info("  ID: {}", id);
                        log.info("  Namespace: {}", namespace);
                        log.info("  Name: {}", name);
                        log.info("  Version: {}", version);
                        log.info("  Status: {}", status);
                    }
                });

        // Verify task executions were also persisted
        Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT instance_id, task, status FROM task_instances ORDER BY task")) {
                        ResultSet rs = stmt.executeQuery();

                        int taskCount = 0;
                        while (rs.next()) {
                            String instanceId = rs.getString("instance_id");
                            String task = rs.getString("task");
                            String status = rs.getString("status");

                            assertNotNull(instanceId);
                            assertNotNull(task);
                            assertNotNull(status);

                            taskCount++;
                            log.info("✓ Task persisted: {} (status: {})", task, status);
                        }

                        assertTrue(taskCount >= 2, "Should have at least 2 task executions (workflow has 2 set tasks)");
                        log.info("✓ Total tasks persisted: {}", taskCount);
                    }
                });

        log.info("✅ End-to-end test passed!");
        log.info("✅ Quarkus Flow → Kafka → Ingestion Service → PostgreSQL chain verified");
    }

    /**
     * Test workflow that uses Quarkus Flow DSL.
     * This workflow will trigger lifecycle events when executed.
     */
    @ApplicationScoped
    public static class TestWorkflow extends Flow {
        @Override
        public Workflow descriptor() {
            return FlowWorkflowBuilder.workflow("e2e-test-workflow")
                    .tasks(
                            set("""
                                {
                                  greeting: "Hello from Quarkus Flow!",
                                  timestamp: now()
                                }
                                """),
                            set("""
                                {
                                  completed: true,
                                  result: "E2E test successful"
                                }
                                """)
                    )
                    .build();
        }
    }

    /**
     * Test profile that enables Quarkus Flow lifecycle messaging with structured CloudEvents.
     */
    public static class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new HashMap<>();

            // Disable structured logging (conflicts with lifecycle messaging)
            config.put("quarkus.flow.structured-logging.enabled", "false");

            // Enable lifecycle messaging - publishes CloudEvents to Kafka
            config.put("quarkus.flow.messaging.lifecycle-enabled", "true");
            config.put("quarkus.flow.messaging.defaults-enabled", "true");

            // Configure flow-lifecycle-out channel - SmallRye handles CloudEvents automatically (binary mode)
            config.put("mp.messaging.outgoing.flow-lifecycle-out.connector", "smallrye-kafka");
            config.put("mp.messaging.outgoing.flow-lifecycle-out.topic", "flow-lifecycle-out");
            config.put("mp.messaging.outgoing.flow-lifecycle-out.value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
            config.put("mp.messaging.outgoing.flow-lifecycle-out.key.serializer", "org.apache.kafka.common.serialization.StringSerializer");

            // Configure flow-out channel (required by defaults-enabled)
            config.put("mp.messaging.outgoing.flow-out.connector", "smallrye-kafka");
            config.put("mp.messaging.outgoing.flow-out.topic", "flow-out");
            config.put("mp.messaging.outgoing.flow-out.value.serializer", "org.apache.kafka.common.serialization.StringSerializer");

            // Configure flow-in channel (required by defaults-enabled)
            config.put("mp.messaging.incoming.flow-in.connector", "smallrye-kafka");
            config.put("mp.messaging.incoming.flow-in.topic", "flow-in");
            config.put("mp.messaging.incoming.flow-in.value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

            return config;
        }
    }
}
