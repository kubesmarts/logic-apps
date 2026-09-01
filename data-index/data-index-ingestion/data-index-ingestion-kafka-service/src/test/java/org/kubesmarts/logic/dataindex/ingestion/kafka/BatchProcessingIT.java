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
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for batch processing in MODE 3 Kafka ingestion.
 *
 * <p>These tests verify that the production batch processing code path works correctly:
 * <ul>
 *   <li>Multiple events in a single Kafka poll batch</li>
 *   <li>DB write chunking when batch exceeds db-batch-size</li>
 *   <li>Mixed workflow + task events in same batch</li>
 *   <li>Partial batch failures (some valid, some invalid)</li>
 *   <li>Concurrent workflows in same batch</li>
 * </ul>
 *
 * <p><b>Configuration:</b> Tests run with {@code batch=true} (production mode).
 */
@QuarkusTest
public class BatchProcessingIT extends BaseWorkflowLifecycleIT {

    private static final Logger log = LoggerFactory.getLogger(BatchProcessingIT.class);

    private KafkaConsumer<String, String> dlqConsumer;

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
    void shouldProcessMultipleWorkflowsInSingleBatch() throws Exception {
        log.info("=== Test: Multiple Workflows in Single Batch ===");

        // Publish 50 workflow events rapidly (should be consumed in one or few batches)
        int workflowCount = 50;
        List<String> workflowIds = new ArrayList<>();
        ZonedDateTime baseTime = ZonedDateTime.now(ZoneOffset.UTC);

        log.info("Publishing {} workflow events rapidly...", workflowCount);
        for (int i = 0; i < workflowCount; i++) {
            String instanceId = "batch-wf-" + i + "-" + UUID.randomUUID();
            workflowIds.add(instanceId);

            var data = Map.of(
                    "name", instanceId,
                    "definition", createWorkflowDefinition(),
                    "status", "RUNNING",
                    "startedAt", baseTime.plusSeconds(i));

            var event = Map.of(
                    "specversion", "1.0",
                    "type", "io.serverlessworkflow.workflow.started.v1",
                    "source", "batch-test",
                    "id", UUID.randomUUID().toString(),
                    "time", Instant.now().toString(),
                    "datacontenttype", "application/json",
                    "data", data);

            String json = mapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId, json));
        }

        producer.flush();
        log.info("✓ Published {} events", workflowCount);

        // Verify all workflows were persisted
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM workflow_instances WHERE id LIKE 'batch-wf-%'")) {
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        assertThat(count).isEqualTo(workflowCount);
                        log.info("✓ All {} workflows persisted", count);
                    }
                });

        log.info("✅ Batch processing test passed");
    }

    @Test
    void shouldChunkLargeBatchForDBWrites() throws Exception {
        log.info("=== Test: Large Batch DB Chunking ===");

        // Publish 2500 events (> db-batch-size=1000, should trigger chunking)
        int eventCount = 2500;
        ZonedDateTime baseTime = ZonedDateTime.now(ZoneOffset.UTC);

        log.info("Publishing {} workflow events (exceeds db-batch-size=1000)...", eventCount);
        for (int i = 0; i < eventCount; i++) {
            String instanceId = "chunk-wf-" + i;

            var data = Map.of(
                    "name", instanceId,
                    "definition", createWorkflowDefinition(),
                    "status", "RUNNING",
                    "startedAt", baseTime.plusSeconds(i));

            var event = Map.of(
                    "specversion", "1.0",
                    "type", "io.serverlessworkflow.workflow.started.v1",
                    "source", "chunk-test",
                    "id", UUID.randomUUID().toString(),
                    "time", Instant.now().toString(),
                    "datacontenttype", "application/json",
                    "data", data);

            String json = mapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId, json));
        }

        producer.flush();
        log.info("✓ Published {} events", eventCount);

        // Verify all workflows were persisted (chunking logic worked)
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM workflow_instances WHERE id LIKE 'chunk-wf-%'")) {
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        log.info("Persisted: {}/{}", count, eventCount);
                        assertThat(count).isEqualTo(eventCount);
                    }
                });

        log.info("✅ Large batch chunking test passed");
    }

    @Test
    void shouldHandleMixedWorkflowAndTaskEventsInBatch() throws Exception {
        log.info("=== Test: Mixed Workflow + Task Events in Batch ===");

        String instanceId = "mixed-wf-" + UUID.randomUUID();
        ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);
        String taskName = "callHttp";
        String task = "do/0/" + taskName;

        log.info("Publishing workflow started + task started in rapid succession...");

        // Publish workflow started
        var workflowData = Map.of(
                "name", instanceId,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime);

        var workflowEvent = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.workflow.started.v1",
                "source", "mixed-test",
                "id", UUID.randomUUID().toString(),
                "time", Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", workflowData);

        producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId,
                mapper.writeValueAsString(workflowEvent))).get();

        // Publish task started immediately after
        var taskData = Map.of(
                "workflow", instanceId,
                "task", task,
                "definition", createWorkflowDefinition(),
                "status", "RUNNING",
                "startedAt", startTime.plusSeconds(1));

        var taskEvent = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.task.started.v1",
                "source", "mixed-test",
                "id", UUID.randomUUID().toString(),
                "time", Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", taskData);

        producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId,
                mapper.writeValueAsString(taskEvent))).get();

        producer.flush();
        log.info("✓ Published workflow + task events");

        // Verify both persisted
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection()) {
                        // Check workflow
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "SELECT id FROM workflow_instances WHERE id = ?")) {
                            stmt.setString(1, instanceId);
                            ResultSet rs = stmt.executeQuery();
                            assertThat(rs.next()).isTrue();
                        }

                        // Check task
                        try (PreparedStatement stmt = conn.prepareStatement(
                                "SELECT task FROM task_instances WHERE instance_id = ? AND task = ?")) {
                            stmt.setString(1, instanceId);
                            stmt.setString(2, task);
                            ResultSet rs = stmt.executeQuery();
                            assertThat(rs.next()).isTrue();
                        }
                    }
                });

        log.info("✅ Mixed workflow + task batch test passed");
    }

    @Test
    void shouldHandlePartialBatchFailure() throws Exception {
        log.info("=== Test: Partial Batch Failure (Valid + Invalid Events) ===");

        // Publish 10 valid events + 1 invalid event in rapid succession
        List<String> validIds = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String instanceId = "partial-valid-" + i;
            validIds.add(instanceId);

            var data = Map.of(
                    "name", instanceId,
                    "definition", createWorkflowDefinition(),
                    "status", "RUNNING",
                    "startedAt", ZonedDateTime.now(ZoneOffset.UTC));

            var event = Map.of(
                    "specversion", "1.0",
                    "type", "io.serverlessworkflow.workflow.started.v1",
                    "source", "partial-test",
                    "id", UUID.randomUUID().toString(),
                    "time", Instant.now().toString(),
                    "datacontenttype", "application/json",
                    "data", data);

            producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId,
                    mapper.writeValueAsString(event))).get();
        }

        // Publish invalid JSON event
        String invalidJson = "{invalid-json-not-closed";
        producer.send(new ProducerRecord<>("flow-lifecycle-out", "invalid-key", invalidJson)).get();

        producer.flush();
        log.info("✓ Published 10 valid + 1 invalid events");

        // Verify valid events persisted
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM workflow_instances WHERE id LIKE 'partial-valid-%'")) {
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        assertThat(count).isEqualTo(10);
                        log.info("✓ {} valid events persisted", count);
                    }
                });

        // Verify invalid event went to DLQ
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    ConsumerRecords<String, String> records = dlqConsumer.poll(Duration.ofMillis(1000));
                    boolean foundInvalid = false;
                    for (ConsumerRecord<String, String> record : records) {
                        if (record.value().contains("invalid-json-not-closed")) {
                            foundInvalid = true;
                            log.info("✓ Invalid event found in DLQ");
                            break;
                        }
                    }
                    assertThat(foundInvalid).isTrue();
                });

        log.info("✅ Partial batch failure test passed");
    }

    @Test
    void shouldHandleMultipleConcurrentWorkflowsInBatch() throws Exception {
        log.info("=== Test: Multiple Concurrent Workflows in Same Batch ===");

        // Create 3 workflows with multiple events each
        int workflowCount = 3;
        int eventsPerWorkflow = 5; // started, 3 tasks, completed
        List<String> workflowIds = new ArrayList<>();

        for (int w = 0; w < workflowCount; w++) {
            String instanceId = "concurrent-wf-" + w + "-" + UUID.randomUUID();
            workflowIds.add(instanceId);
            ZonedDateTime startTime = ZonedDateTime.now(ZoneOffset.UTC);

            // Workflow started
            publishWorkflowEvent(instanceId, "RUNNING", startTime, null, null, null, null);

            // 3 tasks
            for (int t = 0; t < 3; t++) {
                String task = "do/" + t + "/task-" + t;
                publishTaskEvent(task, instanceId, "RUNNING",
                        startTime.plusSeconds(t + 1), null);
                publishTaskEvent(task, instanceId, "COMPLETED",
                        startTime.plusSeconds(t + 1), startTime.plusSeconds(t + 2));
            }

            // Workflow completed
            publishWorkflowEvent(instanceId, "COMPLETED", null,
                    startTime.plusSeconds(10), null, null, null);
        }

        producer.flush();
        log.info("✓ Published {} workflows with {} events each", workflowCount, eventsPerWorkflow);

        // Verify all workflows persisted with COMPLETED status
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM workflow_instances " +
                                         "WHERE id LIKE 'concurrent-wf-%' AND status = 'COMPLETED'")) {
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        assertThat(count).isEqualTo(workflowCount);
                        log.info("✓ {} workflows completed", count);
                    }
                });

        // Verify all tasks persisted
        int expectedTasks = workflowCount * 3;
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) as count FROM task_instances " +
                                         "WHERE instance_id LIKE 'concurrent-wf-%'")) {
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        int count = rs.getInt("count");
                        assertThat(count).isEqualTo(expectedTasks);
                        log.info("✓ {} tasks persisted", count);
                    }
                });

        log.info("✅ Concurrent workflows batch test passed");
    }

    protected void drainDLQ() {
        ConsumerRecords<String, String> records;
        do {
            records = dlqConsumer.poll(Duration.ofMillis(500));
        } while (!records.isEmpty());
    }
}
