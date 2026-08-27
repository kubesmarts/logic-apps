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
import io.serverlessworkflow.impl.WorkflowStatus;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
public class KafkaIngestionIT extends BaseWorkflowLifecycleIT {

    @BeforeEach
    void setUp() {
        var producerProps = new Properties();
        producerProps.put("bootstrap.servers", kafkaBootstrapServers);
        producerProps.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producerProps.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        producer = new KafkaProducer<>(producerProps);

        var consumerProps = new Properties();
        consumerProps.put("bootstrap.servers", kafkaBootstrapServers);
        consumerProps.put("group.id", "dlq-test-consumer-" + UUID.randomUUID());
        consumerProps.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("auto.offset.reset", "earliest");
        consumerProps.put("enable.auto.commit", "true");
        dlqConsumer = new KafkaConsumer<>(consumerProps);
        dlqConsumer.subscribe(singletonList("data-index-events-dlq"));

        // Drain any existing DLQ messages from previous tests
        drainDLQ();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (producer != null) {
            producer.close();
        }
        if (dlqConsumer != null) {
            dlqConsumer.close();
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM task_instances;").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_instances;").executeUpdate();
        }
    }

    @Test
    void shouldNormalizeWorkflowStartedEvent() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);

        awaitByWorkflow(instanceId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, status, name, namespace, version FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("RUNNING");
                assertThat(rs.getString("name")).isEqualTo("test-workflow");
                assertThat(rs.getString("namespace")).isEqualTo("default");
            }
        }
    }

    @Test
    void shouldNormalizeWorkflowCompletedEvent() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime endTime = startTime.plusSeconds(10);

        publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);
        awaitByWorkflow(instanceId);

        publishWorkflowEvent(instanceId, "COMPLETED", null, endTime,
                null, "{\"result\":\"ok\"}", null);
        awaitByWorkflowStatus(instanceId, "COMPLETED");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT status, \"endedAt\", output FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                assertThat(rs.getTimestamp("endedAt")).isNotNull();
                assertThat(rs.getString("output")).contains("result");
            }
        }
    }

    @Test
    void shouldPreserveImmutableFieldsOnUpdate() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null,
                "{\"original\":true}", null, null);

        awaitByWorkflow(instanceId);

        var completedTime = startTime.plusSeconds(5);
        publishWorkflowEvent(instanceId, WorkflowStatus.COMPLETED.name(), completedTime, completedTime,
                "{\"overwrite\":true}", null, null);

        publishStatusChanged(instanceId, WorkflowStatus.COMPLETED.name());

        awaitByWorkflowStatus(instanceId, "COMPLETED");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT input, \"startedAt\" FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                // Immutable: first value wins
                assertThat(rs.getString("input")).contains("original");
            }
        }
    }

    @Test
    void shouldHandleTaskBeforeWorkflow() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        String taskName = "callHttp";
        String task = "do/0/" + taskName;
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // Publish task event BEFORE workflow event
        publishTaskEvent(task, instanceId, "RUNNING", startTime, null);
        awaitByTaskNameAndInstanceId(taskName, instanceId);

        // Placeholder workflow should have been created
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).as("Placeholder workflow should exist").isTrue();
            }
        }

        // Now send the actual workflow event
        publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);
        awaitWorkflowWithName(instanceId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT name FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("name")).isEqualTo("test-workflow");
            }
        }
    }

    @Test
    void shouldNormalizeTaskLifecycle() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        String taskName = "doSomething";
        String task = "do/0/" + taskName;
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);
        awaitByWorkflow(instanceId);

        publishTaskEvent(task, instanceId, "RUNNING", startTime, null);
        awaitByTaskNameAndInstanceId(taskName, instanceId);

        var endTime = startTime.plusSeconds(5);
        publishTaskEvent(task, instanceId, "COMPLETED", null, endTime);
        awaitTaskStatus(taskName, "COMPLETED");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT status, task_name, \"endedAt\" FROM task_instances WHERE task_name = ? AND status = ?")) {
            stmt.setString(1, taskName);
            stmt.setString(2, "COMPLETED");
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                assertThat(rs.getString("task_name")).isEqualTo(taskName);
                assertThat(rs.getTimestamp("endedAt")).isNotNull();
            }
        }
    }

    @Test
    void shouldHandleWorkflowWithError() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        Map<String, Object> error = Map.of(
                "type", "RuntimeException",
                "title", "Workflow failed",
                "detail", "NullPointerException at line 42",
                "status", 500);

        publishWorkflowEvent(instanceId, "FAULTED", startTime, startTime.plusSeconds(1),
                null, null, error);
        awaitByWorkflow(instanceId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT status, error_type, error_title, error_detail, error_status FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("FAULTED");
                assertThat(rs.getString("error_type")).isEqualTo("RuntimeException");
                assertThat(rs.getString("error_title")).isEqualTo("Workflow failed");
                assertThat(rs.getString("error_detail")).contains("NullPointerException");
                assertThat(rs.getInt("error_status")).isEqualTo(500);
            }
        }
    }

    @Test
    void shouldHandleOutOfOrderWorkflowEvents() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);
        var endTime = startTime.plusSeconds(10);

        // Send COMPLETED event BEFORE RUNNING event
        publishWorkflowEvent(instanceId, "COMPLETED", null, endTime,
                null, "{\"result\":\"ok\"}", null, endTime.toOffsetDateTime());
        awaitByWorkflow(instanceId);

        // Now send the RUNNING event with earlier timestamp
        publishWorkflowEvent(instanceId, "RUNNING", startTime, null,
                "{\"input\":\"data\"}", null, null, startTime.toOffsetDateTime());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT status, input, output, \"startedAt\" FROM workflow_instances WHERE id = ?")) {
                stmt.setString(1, instanceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Status should remain COMPLETED (latest timestamp wins for status)
                    assertThat(rs.getString("status")).isEqualTo(WorkflowStatus.COMPLETED.name());
                    // Immutable field: input should be set from the first event that provides a non-null input
                    assertThat(rs.getString("input")).contains("data");
                    // Terminal field: output should be preserved
                    assertThat(rs.getString("output")).contains("result");
                }
            }
        });

    }

    @Test
    void shouldUseTimestampToDetermineStatusWinner() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var t1 = ZonedDateTime.now(ZoneOffset.UTC);
        var t2 = t1.plusSeconds(5);
        var t3 = t2.plusSeconds(5);

        // Send events in order: RUNNING -> COMPLETED -> late RUNNING
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), t1, null, null, null, null, t1.toOffsetDateTime());
        awaitByWorkflow(instanceId);

        publishWorkflowEvent(instanceId, WorkflowStatus.COMPLETED.name(), null, t3, null, "{\"result\":\"ok\"}", null, t3.toOffsetDateTime());
        awaitByWorkflowStatus(instanceId, WorkflowStatus.COMPLETED.name());

        // Send late RUNNING event with timestamp between t1 and t3
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), null, t2, null, null, null, t2.toOffsetDateTime());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT status FROM workflow_instances WHERE id = ?")) {
                stmt.setString(1, instanceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Should remain COMPLETED (latest timestamp wins)
                    assertThat(rs.getString("status")).isEqualTo(WorkflowStatus.COMPLETED.name());
                }
            }
        });
    }

    @Test
    void shouldHandleIdempotentWorkflowEventReplay() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // Send the same event twice
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null,
                "{\"input\":\"original\"}", null, null, startTime.toOffsetDateTime());
        awaitByWorkflow(instanceId);

        // Replay the exact same event
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null,
                "{\"input\":\"original\"}", null, null, startTime.toOffsetDateTime());

        await()
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as cnt, input FROM workflow_instances WHERE id = ? GROUP BY input")) {
                        stmt.setString(1, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            // Should only have one row
                            assertThat(rs.getInt("cnt")).isEqualTo(1);
                            assertThat(rs.getString("input")).contains("original");
                        }
                    }
                });
    }

    @Test
    void shouldHandleIdempotentTaskEventReplay() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        String taskName = "set-0";
        String task = "do/0/" + taskName;
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null, null, null, null);
        awaitByWorkflow(instanceId);

        Instant cloudEventTime = OffsetDateTime.now().toInstant();

        // Send TASK_STARTED event
        publishTaskEvent(task, instanceId, WorkflowStatus.RUNNING.name(), startTime.plus(4, ChronoUnit.MILLIS), null, cloudEventTime);
        awaitByTaskNameAndInstanceId(taskName, instanceId);

        // Replay the exact same event
        publishTaskEvent(task, instanceId, WorkflowStatus.RUNNING.name(), startTime.plus(4, ChronoUnit.MILLIS), null, cloudEventTime);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM task_instances WHERE task_name = ? AND instance_id = ?")) {
                stmt.setString(1, taskName);
                stmt.setString(2, instanceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Should only have one row
                    assertThat(rs.getInt(1)).isEqualTo(1);
                }
            }
        });
    }

    @Test
    void shouldNotOverwriteImmutableFieldsOnReplay() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // Send initial event with input
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null,
                "{\"original\":\"value\"}", null, null);
        awaitByWorkflow(instanceId);

        // Try to replay with different input (simulating corrupted replay)
        publishWorkflowEvent(instanceId, WorkflowStatus.RUNNING.name(), startTime, null,
                "{\"modified\":\"value\"}", null, null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT input FROM workflow_instances WHERE id = ?")) {
                stmt.setString(1, instanceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Input should remain original (immutable field)
                    assertThat(rs.getString("input")).contains("original");
                    assertThat(rs.getString("input")).doesNotContain("modified");
                }
            }
        });
    }

    @Test
    void shouldAcceptTerminalFieldUpdatesOnReplay() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);
        var endTime = startTime.plusSeconds(5);

        // Send COMPLETED event without output
        publishWorkflowEvent(instanceId, "COMPLETED", startTime, endTime,
                null, null, null);
        awaitByWorkflow(instanceId);

        // Replay with output (late-arriving data)
        publishWorkflowEvent(instanceId, "COMPLETED", startTime, endTime,
                null, "{\"result\":\"ok\"}", null);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT output FROM workflow_instances WHERE id = ?")) {
                stmt.setString(1, instanceId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    // Terminal field (output) should accept non-null value
                    assertThat(rs.getString("output")).contains("result");
                }
            }
        });
    }

    @Test
    void shouldHandleRepeatedPlaceholderWorkflowCreation() throws Exception {
        String instanceId = "wf-" + UUID.randomUUID();
        String taskName = "set-0";
        String task1 = "do/0/" + taskName;
        String task2 = "do/1/" + taskName;
        var startTime = ZonedDateTime.now(ZoneOffset.UTC);

        // Send two different tasks for same non-existent workflow
        publishTaskEvent(task1, instanceId, "RUNNING", startTime, null);
        publishTaskEvent(task2, instanceId, "RUNNING", startTime, null);
        awaitByTaskPositionAndInstanceId(task1, instanceId);
        awaitByTaskPositionAndInstanceId(task2, instanceId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                // Should only have one placeholder workflow (idempotent)
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM task_instances WHERE instance_id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                // Should have both tasks
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    @Test
    void shouldSendInvalidJsonEventToDLQ() throws Exception {
        String invalidJson = "{invalid-json-not-properly-formatted";

        producer.send(new ProducerRecord<>("flow-lifecycle-out", "invalid-key", invalidJson)).get();
        producer.flush();

        var dlqRecords = pollDLQ(Duration.ofSeconds(10));

        assertThat(dlqRecords).isNotEmpty();
        assertThat(dlqRecords.get(0).value()).contains("invalid-json");
    }

    @Test
    void shouldIgnoreEventWithMissingRequiredFields() throws Exception {
        var event = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.workflow.running",
                "source", "test",
                "id", UUID.randomUUID().toString(),
                "time", Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", Map.of());

        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>("flow-lifecycle-out", "missing-fields", json)).get();
        producer.flush();

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT COUNT(*) FROM workflow_instances")) {
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isZero();
                }
            }
        });
    }

    @Test
    void shouldIgnoreEventWithUnknownType() throws Exception {
        var data = Map.of(
                "instanceId", "unknown-123",
                "status", "RUNNING");

        var event = Map.of(
                "specversion", "1.0",
                "type", "unknown.event.type",
                "source", "test",
                "id", UUID.randomUUID().toString(),
                "time", Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", data);

        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>("flow-lifecycle-out", "unknown-type", json)).get();
        producer.flush();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, "unknown-123");
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }

        var dlqRecords = pollDLQ(Duration.ofSeconds(3));
        assertThat(dlqRecords).as("Unknown event type should not be ignored.").isNotEmpty();
    }

    @Test
    void shouldNotIgnoreEmptyMessages() throws Exception {
        producer.send(new ProducerRecord<>("flow-lifecycle-out", "empty-key", "")).get();
        producer.flush();
        var dlqRecords = pollDLQ(Duration.ofSeconds(3));
        assertThat(dlqRecords).as("Empty messages should not be ignored.").isNotEmpty();
    }

    @Test
    void shouldProcessValidEventAfterDLQEvent() throws Exception {
        String invalidJson = "{invalid-json";
        producer.send(new ProducerRecord<>("flow-lifecycle-out", "invalid", invalidJson)).get();

        String validInstanceId = "wf-" + UUID.randomUUID();
        publishWorkflowEvent(validInstanceId, "RUNNING", ZonedDateTime.now(ZoneOffset.UTC), null, null, null, null);
        producer.flush();

        awaitByWorkflow(validInstanceId);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT status FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, validInstanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("RUNNING");
            }
        }

        var dlqRecords = pollDLQ(Duration.ofSeconds(5));
        assertThat(dlqRecords).isNotEmpty();
    }

    @Test
    void shouldReiveMessagesInDlqWhenSendingMalformadEvents() throws Exception {
        for (int i = 0; i < 5; i++) {
            String invalidJson = "{\"invalid-event-" + i + "\":";
            producer.send(new ProducerRecord<>("flow-lifecycle-out", "invalid-" + i, invalidJson)).get();
        }
        producer.flush();

        var dlqRecords = pollDLQ(Duration.ofSeconds(15));

        assertThat(dlqRecords.size()).isGreaterThanOrEqualTo(5);
    }
}
