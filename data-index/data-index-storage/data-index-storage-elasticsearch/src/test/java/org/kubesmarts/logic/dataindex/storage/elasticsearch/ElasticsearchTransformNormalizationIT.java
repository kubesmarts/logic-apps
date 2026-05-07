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

    // Use date-based index name to match production pattern (workflow-events-YYYY.MM.DD)
    private static final String RAW_INDEX = "workflow-events-" + java.time.LocalDate.now().toString();
    private static final String NORMALIZED_INDEX = "workflow-instances";
    private static final String TRANSFORM_ID = "workflow-instances-transform";

    @BeforeEach
    void setUp() throws Exception {
        // Delete existing transform to force recreation with latest config
        try {
            client.transform().stopTransform(s -> s.transformId(TRANSFORM_ID).force(true).waitForCompletion(true));
            client.transform().deleteTransform(d -> d.transformId(TRANSFORM_ID).force(true));
            System.out.println("Deleted existing transform: " + TRANSFORM_ID);
        } catch (Exception e) {
            System.out.println("No existing transform to delete (or delete failed): " + e.getMessage());
        }

        // Wait a bit for deletion to complete
        Thread.sleep(1000);

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

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           baseTime.plusSeconds(10), laterInput, null, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1",
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

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1", baseTime, null, null, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           baseTime.plusSeconds(10), null, firstOutput, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
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

        System.out.println("=== Test: testStatusTerminalPrecedence ===");
        System.out.println("Instance ID: " + instanceId);
        System.out.println("Base time: " + baseTime);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1", baseTime, null, null, null);
        System.out.println("Inserted: workflow.started");

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           baseTime.plusSeconds(5), null, null, null);
        System.out.println("Inserted: workflow.running (5s)");

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           baseTime.plusSeconds(10), null, null, null);
        System.out.println("Inserted: workflow.completed (10s)");

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           baseTime.plusSeconds(15), null, null, null);
        System.out.println("Inserted: workflow.running (15s)");

        System.out.println("Waiting for transform to process events...");

        // Force refresh the raw index to make sure events are visible
        try {
            client.indices().refresh(r -> r.index(RAW_INDEX));
            System.out.println("Refreshed raw index");

            // Check raw events to verify they have status field
            var rawEventsResponse = client.search(s -> s
                .index(RAW_INDEX)
                .query(q -> q
                    .term(t -> t
                        .field("instanceId.keyword")
                        .value(instanceId)))
                .size(10), Map.class);
            System.out.println("Raw events for this instance: " + rawEventsResponse.hits().total().value());
            rawEventsResponse.hits().hits().forEach(hit -> {
                Map<String, Object> source = hit.source();
                System.out.println("  Event status: " + source.get("status") + ", eventType: " + source.get("eventType"));
            });
        } catch (Exception e) {
            System.out.println("Failed to refresh/check raw events: " + e.getMessage());
        }

        // Check transform stats before waiting
        try {
            var statsResponse = client.transform().getTransformStats(s -> s.transformId(TRANSFORM_ID));
            var stats = statsResponse.transforms().get(0);
            System.out.println("Transform state: " + stats.state());
            System.out.println("Documents processed: " + stats.stats().documentsProcessed());
            System.out.println("Documents indexed: " + stats.stats().documentsIndexed());
        } catch (Exception e) {
            System.out.println("Failed to get transform stats: " + e.getMessage());
        }

        waitForTransform();

        // Check transform stats after waiting
        try {
            var statsResponse = client.transform().getTransformStats(s -> s.transformId(TRANSFORM_ID));
            var stats = statsResponse.transforms().get(0);
            System.out.println("After wait - Documents processed: " + stats.stats().documentsProcessed());
            System.out.println("After wait - Documents indexed: " + stats.stats().documentsIndexed());
        } catch (Exception e) {
            System.out.println("Failed to get transform stats: " + e.getMessage());
        }

        System.out.println("Fetching normalized instance...");

        // First, check what documents are in the normalized index
        Map<String, Object> rawSource = null;
        try {
            client.indices().refresh(r -> r.index(NORMALIZED_INDEX));
            var allDocsResponse = client.search(s -> s
                .index(NORMALIZED_INDEX)
                .query(q -> q.matchAll(m -> m)), Map.class);
            System.out.println("Normalized index total docs: " + allDocsResponse.hits().total().value());
            allDocsResponse.hits().hits().forEach(hit -> {
                Map<String, Object> source = hit.source();
                System.out.println("  Doc ID: " + hit.id());
                System.out.println("    Full source: " + source);
                System.out.println("    status field value: " + source.get("status"));
                System.out.println("    status field class: " + (source.get("status") != null ? source.get("status").getClass() : "null"));
            });
        } catch (Exception e) {
            System.out.println("Error checking normalized index: " + e.getMessage());
        }

        WorkflowInstance normalized = getNormalizedInstance(instanceId);

        if (normalized == null) {
            System.out.println("ERROR: Normalized instance is NULL for ID: " + instanceId);
            // Check if raw events exist
            checkRawEvents(instanceId);
        } else {
            System.out.println("SUCCESS: Found normalized instance");
            System.out.println("Deserialized - ID: " + normalized.getId());
            System.out.println("Deserialized - Status: " + normalized.getStatus());
            System.out.println("Deserialized - Name: " + normalized.getName());
            System.out.println("Deserialized - Version: " + normalized.getVersion());
            System.out.println("Deserialized - Namespace: " + normalized.getNamespace());
        }

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

        insertWorkflowEventWithTimestamps(instanceId, "io.serverlessworkflow.workflow.started.v1",
                                         t10s, t10s, null);

        insertWorkflowEventWithTimestamps(instanceId, "io.serverlessworkflow.workflow.running.v1",
                                         t5s, t5s, null);

        insertWorkflowEventWithTimestamps(instanceId, "io.serverlessworkflow.workflow.completed.v1",
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

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1", baseTime, null, null, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.faulted.v1",
                           baseTime.plusSeconds(5), null, null, earlyError);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.faulted.v1",
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

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           baseTime.plusSeconds(30), null, output, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           baseTime.plusSeconds(15), null, null, null);

        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1",
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
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("eventTime", eventTime.toString());
        event.put("instanceId", instanceId);
        event.put("workflowName", "test-workflow");
        event.put("workflowVersion", "1.0");
        event.put("workflowNamespace", "test");
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
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", eventType);
        event.put("eventTime", eventTime.toString());
        event.put("instanceId", instanceId);
        event.put("workflowName", "test-workflow");
        event.put("workflowVersion", "1.0");
        event.put("workflowNamespace", "test");
        event.put("status", extractStatusFromEventType(eventType));

        if (start != null) {
            event.put("startTime", start.getEpochSecond());
        }
        if (end != null) {
            event.put("endTime", end.getEpochSecond());
        }

        IndexRequest<Map<String, Object>> request = IndexRequest.of(b -> b
            .index(RAW_INDEX)
            .document(event)
            .refresh(Refresh.True)
        );
        client.index(request);
    }

    private String extractStatusFromEventType(String eventType) {
        // Extract status from eventType like "io.serverlessworkflow.workflow.started.v1"
        // Result should be "STARTED"
        String[] parts = eventType.split("\\.");
        if (parts.length >= 2) {
            // Get second-to-last part (before .v1)
            String status = parts[parts.length - 2];
            return status.toUpperCase();
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

    private void checkRawEvents(String instanceId) {
        try {
            co.elastic.clients.elasticsearch.core.SearchRequest searchRequest =
                co.elastic.clients.elasticsearch.core.SearchRequest.of(b -> b
                    .index(RAW_INDEX)
                    .query(q -> q
                        .term(t -> t
                            .field("instanceId.keyword")
                            .value(instanceId)))
                );

            var response = client.search(searchRequest, Map.class);
            System.out.println("Raw events found: " + response.hits().total().value());
            response.hits().hits().forEach(hit -> {
                System.out.println("  Event: " + hit.source());
            });
        } catch (Exception e) {
            System.out.println("Error checking raw events: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private WorkflowInstance getNormalizedInstance(String instanceId) throws IOException {
        try {
            // Note: Transform-generated documents don't use instanceId as document ID
            // They use a composite ID, so we need to search by the "id" field in the source
            // The "id" field from transform is the group key, might be stored as text or nested
            var searchRequest = co.elastic.clients.elasticsearch.core.SearchRequest.of(b -> b
                .index(NORMALIZED_INDEX)
                .query(q -> q
                    .match(m -> m
                        .field("id")
                        .query(instanceId)))
                .size(1)
            );

            var searchResponse = client.search(searchRequest, Map.class);

            if (searchResponse.hits().total().value() == 0) {
                return null;
            }

            Map<String, Object> source = searchResponse.hits().hits().get(0).source();

            // Use ObjectMapper to deserialize, which will invoke our custom deserializers
            WorkflowInstance instance = objectMapper.convertValue(source, WorkflowInstance.class);

            // Ensure ID is set from the source field
            if (instance.getId() == null || instance.getId().isEmpty()) {
                Object idValue = source.get("id");
                if (idValue instanceof Map) {
                    // Extract first key from bucket (e.g., {instanceId=1} -> "instanceId")
                    Map<String, Object> idBucket = (Map<String, Object>) idValue;
                    instance.setId(idBucket.keySet().iterator().next());
                } else if (idValue instanceof String) {
                    instance.setId((String) idValue);
                } else {
                    instance.setId(instanceId);
                }
            }

            if (source.containsKey("startDate")) {
                Object startValue = source.get("startDate");
                if (startValue instanceof Number) {
                    long epochMillis = ((Number) startValue).longValue();
                    instance.setStart(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
                } else if (startValue instanceof String) {
                    instance.setStart(ZonedDateTime.parse((String) startValue));
                }
            }
            if (source.containsKey("endDate")) {
                Object endValue = source.get("endDate");
                if (endValue instanceof Number) {
                    long epochMillis = ((Number) endValue).longValue();
                    instance.setEnd(ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneOffset.UTC));
                } else if (endValue instanceof String) {
                    instance.setEnd(ZonedDateTime.parse((String) endValue));
                }
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

            if (source.containsKey("lastUpdate")) {
                String lastUpdateStr = (String) source.get("lastUpdate");
                instance.setLastUpdate(ZonedDateTime.parse(lastUpdateStr));
            }

            return instance;
        } catch (Exception e) {
            return null;
        }
    }
}
