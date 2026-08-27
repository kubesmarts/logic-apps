package org.kubesmarts.logic.dataindex.ingestion.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public abstract class BaseWorkflowLifecycleIT {

    final Logger log = LoggerFactory.getLogger(BaseWorkflowLifecycleIT.class);

    @Inject
    protected DataSource dataSource;

    @Inject
    protected ObjectMapper mapper;

    @ConfigProperty(name = "kafka.bootstrap.servers")
    protected String kafkaBootstrapServers;

    protected KafkaProducer<String, String> producer;
    protected KafkaConsumer<String, String> dlqConsumer;

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

    protected void publishEventsToKafka(String jsonFileName) throws Exception {
        String array = readCloudEvents(jsonFileName);
        JsonNode events = mapper.readTree(array);

        for (JsonNode event : events) {
            String eventJson = mapper.writeValueAsString(event);
            producer.send(new ProducerRecord<>("flow-lifecycle-out", null, eventJson)).get();
        }
        producer.flush();
    }

    protected void awaitByWorkflowStatus(String instanceId, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("status")).isEqualTo(status);
                        }
                    }
                });
    }

    protected void awaitWorkflowWithName(String instanceId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT name FROM workflow_instances WHERE id = ? AND name IS NOT NULL")) {
                        stmt.setString(1, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                });
    }

    protected void awaitByWorkflow(String instanceId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT 1 FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                });
    }

    protected void awaitByTaskNameAndInstanceId(String taskName, String instanceId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT 1 FROM task_instances WHERE task_name = ? AND instance_id = ?")) {
                        stmt.setString(1, taskName);
                        stmt.setString(2, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                });
    }

    protected void awaitByTaskPositionAndInstanceId(String taskPosition, String instanceId) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT 1 FROM task_instances WHERE task = ? AND instance_id = ?")) {
                        stmt.setString(1, taskPosition);
                        stmt.setString(2, instanceId);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                        }
                    }
                });
    }

    protected void awaitTaskStatus(String taskName, String status) {
        Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM task_instances WHERE task_name = ? AND status = ?")) {
                        stmt.setString(1, taskName);
                        stmt.setString(2, status);
                        try (ResultSet rs = stmt.executeQuery()) {
                            assertThat(rs.next()).isTrue();
                            assertThat(rs.getString("status")).isEqualTo(status);
                        }
                    }
                });
    }

    protected List<ConsumerRecord<String, String>> pollDLQ(Duration timeout) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        long endTime = System.currentTimeMillis() + timeout.toMillis();
        int consecutiveEmptyPolls = 0;

        while (System.currentTimeMillis() < endTime) {
            var polled = dlqConsumer.poll(Duration.ofMillis(500));
            if (polled.isEmpty()) {
                consecutiveEmptyPolls++;
                if (consecutiveEmptyPolls >= 3 && !records.isEmpty()) {
                    break;
                }
            } else {
                consecutiveEmptyPolls = 0;
                polled.forEach(records::add);
            }
        }

        return records;
    }

    protected void drainDLQ() {
        long endTime = System.currentTimeMillis() + Duration.ofSeconds(2).toMillis();
        int drained = 0;

        while (System.currentTimeMillis() < endTime) {
            var polled = dlqConsumer.poll(Duration.ofMillis(500));
            drained += polled.count();
            if (polled.isEmpty()) {
                break;
            }
        }

        if (drained > 0) {
            log.debug("Drained {} old messages from DLQ", drained);
        }
    }

    protected Map<String, Object> createWorkflowDefinition() {
        return Map.of(
                "namespace", "default",
                "name", "test-workflow",
                "version", "1.0.0"
        );
    }

    protected String getEventTypeSuffix(String status) {
        return switch (status) {
            case "RUNNING", "STARTED" -> "started.v1";
            case "COMPLETED" -> "completed.v1";
            case "FAULTED" -> "faulted.v1";
            case "CANCELLED" -> "cancelled.v1";
            default -> status.toLowerCase() + ".v1";
        };
    }

    protected void publishStatusChanged(String instanceId, String status) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("name", instanceId);
        data.put("definition", createWorkflowDefinition());
        data.put("status", status);
        data.put("updatedAt", OffsetDateTime.now().toString());

        var event = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.workflow.status-changed.v1",
                "source", "test",
                "id", UUID.randomUUID().toString(),
                "time", Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", data);

        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>("flow-lifecycle-out", null, json)).get();
        producer.flush();
    }

    protected void publishWorkflowEvent(String instanceId, String status,
                                      ZonedDateTime startTime, ZonedDateTime endTime, String inputJson, String outputJson,
                                      Map<String, Object> error) throws Exception {
        publishWorkflowEvent(instanceId, status, startTime, endTime, inputJson, outputJson, error, null);
    }

    protected void publishWorkflowEvent(String instanceId, String status,
                                        ZonedDateTime startTime, ZonedDateTime endTime, String inputJson, String outputJson,
                                        Map<String, Object> error, OffsetDateTime cloudEventTime) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("name", instanceId);
        data.put("definition", createWorkflowDefinition());
        data.put("status", status);

        if ("RUNNING".equals(status) || "STARTED".equals(status)) {
            if (startTime != null) data.put("startedAt", startTime);
            if (inputJson != null) data.put("input", mapper.readTree(inputJson));
        } else if ("COMPLETED".equals(status)) {
            if (endTime != null) data.put("completedAt", endTime);
            if (outputJson != null) data.put("output", mapper.readTree(outputJson));
        } else if ("FAULTED".equals(status)) {
            if (endTime != null) data.put("faultedAt", endTime);
            if (error != null) data.put("error", error);
        } else if ("CANCELLED".equals(status)) {
            if (endTime != null) data.put("cancelledAt", endTime);
        }

        if (error != null && !"FAULTED".equals(status)) data.put("error", error);

        var event = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.workflow." + getEventTypeSuffix(status),
                "source", "test",
                "id", UUID.randomUUID().toString(),
                "time", cloudEventTime != null ? cloudEventTime.toInstant().toString() : Instant.now().toString(),
                "datacontenttype", "application/json",
                "data", data);

        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>("flow-lifecycle-out", instanceId, json)).get();
        producer.flush();
    }

    protected void publishTaskEvent(String task, String workflow, String status,
                                  ZonedDateTime startTime, ZonedDateTime endTime) throws Exception {
        publishTaskEvent(task, workflow, status, startTime, endTime, null);
    }

    protected void publishTaskEvent(String task, String workflow, String status,
                                    ZonedDateTime startTime, ZonedDateTime endTime, Instant cloudEventTime) throws Exception {
        publishTaskEvent(task, workflow, status, startTime, endTime, cloudEventTime, null, null);

    }

    protected void publishTaskEvent(String task, String workflow, String status,
                                  ZonedDateTime startTime, ZonedDateTime endTime, Instant cloudEventTime, String input, String output) throws Exception {
        var data = new HashMap<String, Object>();
        data.put("workflow", workflow);
        data.put("task", task);
        data.put("definition", createWorkflowDefinition());
        data.put("status", status);

        if ("RUNNING".equals(status) || "STARTED".equals(status)) {
            if (startTime != null) data.put("startedAt", startTime);
            if (input != null) data.put("input", input);
        } else if ("COMPLETED".equals(status)) {
            if (endTime != null) data.put("completedAt", endTime);
            if (output != null) data.put("output", output);
        } else if ("FAULTED".equals(status)) {
            if (endTime != null) data.put("faultedAt", endTime);
        } else if ("CANCELLED".equals(status)) {
            if (endTime != null) data.put("cancelledAt", endTime);
        }

        var event = Map.of(
                "specversion", "1.0",
                "type", "io.serverlessworkflow.task." + getEventTypeSuffix(status),
                "source", "test",
                "id", UUID.randomUUID().toString(),
                "time", cloudEventTime != null ? cloudEventTime : Instant.now(),
                "datacontenttype", "application/json",
                "data", data);

        String json = mapper.writeValueAsString(event);
        producer.send(new ProducerRecord<>("flow-lifecycle-out", null, json)).get();
        producer.flush();
    }

    protected static String readCloudEvents(String filename) throws IOException {
        URL resource = Thread.currentThread().getContextClassLoader()
                .getResource(filename);
        return Files.readString(Paths.get(resource.getPath()), StandardCharsets.UTF_8);
    }
}
