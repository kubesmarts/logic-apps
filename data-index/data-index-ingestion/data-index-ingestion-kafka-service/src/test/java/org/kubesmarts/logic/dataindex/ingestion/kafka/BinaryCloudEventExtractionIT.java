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

import io.quarkus.test.junit.QuarkusTest;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for binary CloudEvents mode (CE attributes in Kafka headers).
 *
 * <p>Tests verify manual CloudEvent reconstruction from {@code ce_*} headers:
 * <ul>
 *   <li>Correct extraction from binary mode headers</li>
 *   <li>Workflow and task events with binary CloudEvents</li>
 *   <li>Error handling for missing required headers</li>
 *   <li>Error handling for malformed timestamp headers</li>
 * </ul>
 *
 * <p><b>Note:</b> Quarkus Flow default mode is binary (CE attributes in headers, data in body).
 */
@QuarkusTest
public class BinaryCloudEventExtractionIT extends BaseWorkflowLifecycleIT {

    private static final Logger log = LoggerFactory.getLogger(BinaryCloudEventExtractionIT.class);

    @BeforeEach
    void setUp() {
        var producerProps = new Properties();
        producerProps.put("bootstrap.servers", kafkaBootstrapServers);
        producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(producerProps);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (producer != null) {
            producer.close();
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM task_instances;").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_instances;").executeUpdate();
        }
    }

    @Test
    void shouldExtractWorkflowEventFromBinaryHeaders() throws Exception {
        log.info("=== Test: Binary CloudEvent Workflow Extraction ===");

        String instanceId = "binary-wf-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);
        Instant eventTime = Instant.now();

        // Create workflow started event data (JSON payload, no CloudEvent envelope)
        var data = Map.of(
                "name", instanceId,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime);

        String dataJson = mapper.writeValueAsString(data);

        // Create ProducerRecord with CloudEvent attributes in headers (binary mode)
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "flow-lifecycle-out",
                instanceId,
                dataJson  // Just the data payload, not full CloudEvent envelope
        );

        // Add CloudEvent headers (ce_* prefix for binary mode)
        record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_type",
                "io.serverlessworkflow.workflow.started.v1".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_source", "binary-test".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_time", eventTime.toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("content-type", "application/json".getBytes(StandardCharsets.UTF_8)));

        log.info("Publishing binary CloudEvent with ce_* headers...");
        producer.send(record).get();
        producer.flush();
        log.info("✓ Published binary CloudEvent");

        // Verify workflow was persisted (CloudEvent successfully extracted from headers)
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT id, status, name FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("id")).isEqualTo(instanceId);
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                        log.info("✓ Workflow persisted from binary CloudEvent");
                    }
                });

        log.info("✅ Binary CloudEvent workflow extraction test passed");
    }

    @Test
    void shouldExtractTaskEventFromBinaryHeaders() throws Exception {
        log.info("=== Test: Binary CloudEvent Task Extraction ===");

        String instanceId = "binary-task-wf-" + UUID.randomUUID();
        String taskName = "callHttp";
        String task = "do/0/" + taskName;
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // First, create workflow
        publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);
        awaitByWorkflow(instanceId);

        // Create task started event data (JSON payload only)
        var taskData = Map.of(
                "workflow", instanceId,
                "task", task,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime.plusSeconds(1));

        String dataJson = mapper.writeValueAsString(taskData);

        // Create ProducerRecord with CloudEvent headers
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "flow-lifecycle-out",
                instanceId,
                dataJson
        );

        record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_type",
                "io.serverlessworkflow.task.started.v1".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_source", "binary-test".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_time", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        log.info("Publishing binary CloudEvent for task...");
        producer.send(record).get();
        producer.flush();
        log.info("✓ Published binary task CloudEvent");

        // Verify task was persisted
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT task, status FROM task_instances WHERE instance_id = ? AND task = ?")) {
                        stmt.setString(1, instanceId);
                        stmt.setString(2, task);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("task")).isEqualTo(task);
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                        log.info("✓ Task persisted from binary CloudEvent");
                    }
                });

        log.info("✅ Binary CloudEvent task extraction test passed");
    }

    @Test
    void shouldHandleMissingRequiredHeaders() throws Exception {
        log.info("=== Test: Missing Required Headers (Error Handling) ===");

        String instanceId = "missing-headers-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        var data = Map.of(
                "name", instanceId,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime);

        String dataJson = mapper.writeValueAsString(data);

        // Create record with INCOMPLETE headers (missing ce_type - required)
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "flow-lifecycle-out",
                instanceId,
                dataJson
        );

        // Add some headers but NOT ce_type (required)
        record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_source", "binary-test".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        // Missing: ce_type, ce_time

        log.info("Publishing binary CloudEvent with missing ce_type header...");
        producer.send(record).get();
        producer.flush();

        // Wait a bit to ensure consumer had a chance to process
        Thread.sleep(5000);

        // Verify workflow was NOT persisted (validation should have failed)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) as count FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            int count = rs.getInt("count");
            assertThat(count).isZero();
            log.info("✓ Workflow NOT persisted (missing headers rejected as expected)");
        }

        log.info("✅ Missing headers error handling test passed");
    }

    @Test
    void shouldHandleMalformedTimestampHeader() throws Exception {
        log.info("=== Test: Malformed Timestamp Header (Error Handling) ===");

        String instanceId = "malformed-time-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        var data = Map.of(
                "name", instanceId,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime);

        String dataJson = mapper.writeValueAsString(data);

        ProducerRecord<String, String> record = new ProducerRecord<>(
                "flow-lifecycle-out",
                instanceId,
                dataJson
        );

        // Add headers with INVALID timestamp format
        record.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_type",
                "io.serverlessworkflow.workflow.started.v1".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_source", "binary-test".getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("ce_time",
                "not-a-valid-timestamp".getBytes(StandardCharsets.UTF_8)));  // ← Invalid

        log.info("Publishing binary CloudEvent with malformed ce_time header...");
        producer.send(record).get();
        producer.flush();

        // Wait a bit to ensure consumer had a chance to process
        Thread.sleep(5000);

        // Verify workflow was NOT persisted (timestamp parsing should have failed)
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) as count FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            int count = rs.getInt("count");
            assertThat(count).isZero();
            log.info("✓ Workflow NOT persisted (malformed timestamp rejected as expected)");
        }

        log.info("✅ Malformed timestamp error handling test passed");
    }

    @Test
    void shouldHandleBinaryAndStructuredMixInBatch() throws Exception {
        log.info("=== Test: Mixed Binary + Structured CloudEvents in Same Batch ===");

        String binaryId = "binary-mixed-" + UUID.randomUUID();
        String structuredId = "structured-mixed-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // 1. Publish BINARY CloudEvent
        var binaryData = Map.of(
                "name", binaryId,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime);

        String binaryDataJson = mapper.writeValueAsString(binaryData);
        ProducerRecord<String, String> binaryRecord = new ProducerRecord<>(
                "flow-lifecycle-out", binaryId, binaryDataJson);

        binaryRecord.headers().add(new RecordHeader("ce_specversion", "1.0".getBytes(StandardCharsets.UTF_8)));
        binaryRecord.headers().add(new RecordHeader("ce_type",
                "io.serverlessworkflow.workflow.started.v1".getBytes(StandardCharsets.UTF_8)));
        binaryRecord.headers().add(new RecordHeader("ce_source", "binary-test".getBytes(StandardCharsets.UTF_8)));
        binaryRecord.headers().add(new RecordHeader("ce_id", UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)));
        binaryRecord.headers().add(new RecordHeader("ce_time", Instant.now().toString().getBytes(StandardCharsets.UTF_8)));

        producer.send(binaryRecord).get();

        // 2. Publish STRUCTURED CloudEvent (full JSON envelope)
        publishWorkflowEvent(structuredId, "RUNNING", startTime, null, null, null, null);

        producer.flush();
        log.info("✓ Published 1 binary + 1 structured CloudEvent");

        // Verify both persisted correctly (auto-detection worked)
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM workflow_instances WHERE id IN (?, ?)")) {
                        stmt.setString(1, binaryId);
                        stmt.setString(2, structuredId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        assertThat(count).isEqualTo(2);
                        log.info("✓ Both binary and structured CloudEvents persisted");
                    }
                });

        log.info("✅ Mixed binary + structured test passed");
    }
}
