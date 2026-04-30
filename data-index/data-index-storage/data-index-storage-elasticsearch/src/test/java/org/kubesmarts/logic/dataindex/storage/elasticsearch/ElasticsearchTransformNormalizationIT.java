/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.transform.StartTransformRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Elasticsearch Transform normalization of out-of-order events.
 *
 * <p>Tests the workflow-instances-transform's ability to correctly handle events that arrive
 * out of chronological order, using sophisticated aggregation logic:
 * <ul>
 *   <li>Immutable fields (name, version, namespace, input) - FIRST non-null value wins
 *   <li>Terminal fields (output, error) - LAST non-null value wins
 *   <li>Status - Terminal status (COMPLETED, FAULTED, CANCELLED) wins regardless of timestamp
 *   <li>Timestamps - start uses MIN, end uses MAX aggregation
 * </ul>
 *
 * <p><b>Transform Architecture:</b>
 * Raw events → workflow-events-* index → Transform aggregation → workflow-instances index
 *
 * <p><b>Test Strategy:</b>
 * Insert events out of order, wait for Transform to process (runs every 1s), verify normalized data.
 */
@QuarkusTest
class ElasticsearchTransformNormalizationIT {

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String RAW_INDEX = "workflow-events-test";
    private static final String NORMALIZED_INDEX = "workflow-instances";
    private static final String TRANSFORM_ID = "workflow-instances-transform";

    @BeforeEach
    void setUp() throws Exception {
        ensureTransformStarted();
    }

    @Test
    void testImmutableFieldsFirstWins() throws Exception {
        String instanceId = "test-immutable-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        Map<String, Object> laterInput = new HashMap<>();
        laterInput.put("customerId", "later");
        laterInput.put("orderId", "ORDER-999");

        Map<String, Object> firstInput = new HashMap<>();
        firstInput.put("customerId", "first");
        firstInput.put("orderId", "ORDER-001");

        insertWorkflowEvent(instanceId, "workflow.instance.running",
                           baseTime.plusSeconds(10), laterInput, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.started",
                           baseTime, firstInput, null, null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getInput()).isNotNull();
        assertThat(normalized.getInput().get("customerId").asText()).isEqualTo("first");
        assertThat(normalized.getInput().get("orderId").asText()).isEqualTo("ORDER-001");
    }

    @Test
    void testTerminalFieldsLastNonNullWins() throws Exception {
        String instanceId = "test-terminal-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        Map<String, Object> firstOutput = new HashMap<>();
        firstOutput.put("result", "result1");
        firstOutput.put("timestamp", baseTime.plusSeconds(10).toString());

        Map<String, Object> laterOutput = new HashMap<>();
        laterOutput.put("result", "result2");
        laterOutput.put("timestamp", baseTime.plusSeconds(20).toString());

        insertWorkflowEvent(instanceId, "workflow.instance.started", baseTime, null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.completed",
                           baseTime.plusSeconds(10), null, firstOutput, null);

        insertWorkflowEvent(instanceId, "workflow.instance.running",
                           baseTime.plusSeconds(20), null, laterOutput, null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getOutput()).isNotNull();
        assertThat(normalized.getOutput().get("result").asText()).isEqualTo("result2");
    }

    @Test
    void testStatusTerminalPrecedence() throws Exception {
        String instanceId = "test-status-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        insertWorkflowEvent(instanceId, "workflow.instance.started", baseTime, null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.running",
                           baseTime.plusSeconds(5), null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.completed",
                           baseTime.plusSeconds(10), null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.running",
                           baseTime.plusSeconds(15), null, null, null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testTimestampAggregations() throws Exception {
        String instanceId = "test-timestamps-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        Instant t5s = baseTime.plusSeconds(5);
        Instant t10s = baseTime.plusSeconds(10);
        Instant t20s = baseTime.plusSeconds(20);

        insertWorkflowEventWithTimestamps(instanceId, "workflow.instance.started",
                                         t10s, t10s, null);

        insertWorkflowEventWithTimestamps(instanceId, "workflow.instance.running",
                                         t5s, t5s, null);

        insertWorkflowEventWithTimestamps(instanceId, "workflow.instance.completed",
                                         t20s, t10s, t20s);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStart()).isNotNull();
        assertThat(normalized.getEnd()).isNotNull();

        assertThat(normalized.getStart().toInstant().toEpochMilli())
            .isCloseTo(t5s.toEpochMilli(), org.assertj.core.api.Assertions.within(1000L));

        assertThat(normalized.getEnd().toInstant().toEpochMilli())
            .isCloseTo(t20s.toEpochMilli(), org.assertj.core.api.Assertions.within(1000L));
    }

    @Test
    void testErrorFieldLastNonNullWins() throws Exception {
        String instanceId = "test-error-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        Map<String, Object> earlyError = new HashMap<>();
        earlyError.put("type", "validation");
        earlyError.put("title", "Early validation error");
        earlyError.put("status", 400);

        Map<String, Object> laterError = new HashMap<>();
        laterError.put("type", "system");
        laterError.put("title", "System error occurred");
        laterError.put("detail", "Database connection failed");
        laterError.put("status", 500);

        insertWorkflowEvent(instanceId, "workflow.instance.started", baseTime, null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.faulted",
                           baseTime.plusSeconds(5), null, null, earlyError);

        insertWorkflowEvent(instanceId, "workflow.instance.faulted",
                           baseTime.plusSeconds(10), null, null, laterError);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getError()).isNotNull();
        assertThat(normalized.getError().getType()).isEqualTo("system");
        assertThat(normalized.getError().getTitle()).isEqualTo("System error occurred");
        assertThat(normalized.getError().getDetail()).isEqualTo("Database connection failed");
        assertThat(normalized.getError().getStatus()).isEqualTo(500);
    }

    @Test
    void testComplexOutOfOrderScenario() throws Exception {
        String instanceId = "test-complex-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        Map<String, Object> input = new HashMap<>();
        input.put("orderId", "ORDER-123");

        Map<String, Object> output = new HashMap<>();
        output.put("result", "success");

        insertWorkflowEvent(instanceId, "workflow.instance.completed",
                           baseTime.plusSeconds(30), null, output, null);

        insertWorkflowEvent(instanceId, "workflow.instance.running",
                           baseTime.plusSeconds(15), null, null, null);

        insertWorkflowEvent(instanceId, "workflow.instance.started",
                           baseTime, input, null, null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();

        assertThat(normalized.getInput()).isNotNull();
        assertThat(normalized.getInput().get("orderId").asText()).isEqualTo("ORDER-123");

        assertThat(normalized.getOutput()).isNotNull();
        assertThat(normalized.getOutput().get("result").asText()).isEqualTo("success");

        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);

        assertThat(normalized.getStart()).isNotNull();
        assertThat(normalized.getStart().toInstant().toEpochMilli())
            .isCloseTo(baseTime.toEpochMilli(), org.assertj.core.api.Assertions.within(1000L));

        assertThat(normalized.getEnd()).isNotNull();
        assertThat(normalized.getEnd().toInstant().toEpochMilli())
            .isCloseTo(baseTime.plusSeconds(30).toEpochMilli(), org.assertj.core.api.Assertions.within(1000L));
    }

    private void insertWorkflowEvent(String instanceId, String eventType, Instant eventTime,
                                     Map<String, Object> input, Map<String, Object> output,
                                     Map<String, Object> error) throws IOException {
        Map<String, Object> event = new HashMap<>();
        event.put("@timestamp", Instant.now().toString());
        event.put("tag", "quarkus-flow.workflow");
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", eventType);
        event.put("event_time", eventTime.toString());
        event.put("instance_id", instanceId);
        event.put("workflow_name", "test-workflow");
        event.put("workflow_version", "1.0");
        event.put("workflow_namespace", "test");
        event.put("status", extractStatusFromEventType(eventType));

        if (input != null) {
            event.put("input", input);
        }
        if (output != null) {
            event.put("output", output);
        }
        if (error != null) {
            event.put("error", error);
        }

        IndexRequest<Map<String, Object>> request = IndexRequest.of(b -> b
            .index(RAW_INDEX)
            .document(event)
            .refresh(Refresh.True)
        );
        client.index(request);
    }

    private void insertWorkflowEventWithTimestamps(String instanceId, String eventType,
                                                   Instant eventTime, Instant start, Instant end) throws IOException {
        Map<String, Object> event = new HashMap<>();
        event.put("@timestamp", Instant.now().toString());
        event.put("tag", "quarkus-flow.workflow");
        event.put("event_id", UUID.randomUUID().toString());
        event.put("event_type", eventType);
        event.put("event_time", eventTime.toString());
        event.put("instance_id", instanceId);
        event.put("workflow_name", "test-workflow");
        event.put("workflow_version", "1.0");
        event.put("workflow_namespace", "test");
        event.put("status", extractStatusFromEventType(eventType));

        if (start != null) {
            event.put("start", start.toString());
        }
        if (end != null) {
            event.put("end", end.toString());
        }

        IndexRequest<Map<String, Object>> request = IndexRequest.of(b -> b
            .index(RAW_INDEX)
            .document(event)
            .refresh(Refresh.True)
        );
        client.index(request);
    }

    private String extractStatusFromEventType(String eventType) {
        String[] parts = eventType.split("\\.");
        if (parts.length > 0) {
            return parts[parts.length - 1].toUpperCase();
        }
        return "UNKNOWN";
    }

    private void waitForTransform() throws InterruptedException {
        Thread.sleep(3000);
    }

    private void ensureTransformStarted() throws IOException {
        try {
            StartTransformRequest request = StartTransformRequest.of(b -> b
                .transformId(TRANSFORM_ID)
            );
            client.transform().startTransform(request);
        } catch (Exception e) {
        }
    }

    private WorkflowInstance getNormalizedInstance(String id) throws IOException {
        try {
            GetRequest request = GetRequest.of(b -> b
                .index(NORMALIZED_INDEX)
                .id(id)
            );
            GetResponse<Map> response = client.get(request, Map.class);

            if (!response.found() || response.source() == null) {
                return null;
            }

            Map<String, Object> source = response.source();
            WorkflowInstance instance = new WorkflowInstance();
            instance.setId(id);

            if (source.containsKey("name")) {
                instance.setName((String) source.get("name"));
            }
            if (source.containsKey("version")) {
                instance.setVersion((String) source.get("version"));
            }
            if (source.containsKey("namespace")) {
                instance.setNamespace((String) source.get("namespace"));
            }
            if (source.containsKey("status")) {
                instance.setStatus(WorkflowInstanceStatus.valueOf((String) source.get("status")));
            }

            if (source.containsKey("start")) {
                String startStr = (String) source.get("start");
                instance.setStart(ZonedDateTime.parse(startStr));
            }
            if (source.containsKey("end")) {
                String endStr = (String) source.get("end");
                instance.setEnd(ZonedDateTime.parse(endStr));
            }

            if (source.containsKey("input")) {
                JsonNode inputNode = objectMapper.valueToTree(source.get("input"));
                instance.setInput(inputNode);
            }
            if (source.containsKey("output")) {
                JsonNode outputNode = objectMapper.valueToTree(source.get("output"));
                instance.setOutput(outputNode);
            }

            if (source.containsKey("error") && source.get("error") != null) {
                Map<String, Object> errorMap = (Map<String, Object>) source.get("error");
                Error error = new Error(
                    (String) errorMap.get("type"),
                    (String) errorMap.get("title")
                );
                if (errorMap.containsKey("detail")) {
                    error.setDetail((String) errorMap.get("detail"));
                }
                if (errorMap.containsKey("status")) {
                    error.setStatus((Integer) errorMap.get("status"));
                }
                if (errorMap.containsKey("instance")) {
                    error.setInstance((String) errorMap.get("instance"));
                }
                instance.setError(error);
            }

            if (source.containsKey("last_update")) {
                String lastUpdateStr = (String) source.get("last_update");
                instance.setLastUpdate(ZonedDateTime.parse(lastUpdateStr));
            }

            return instance;
        } catch (Exception e) {
            return null;
        }
    }
}
