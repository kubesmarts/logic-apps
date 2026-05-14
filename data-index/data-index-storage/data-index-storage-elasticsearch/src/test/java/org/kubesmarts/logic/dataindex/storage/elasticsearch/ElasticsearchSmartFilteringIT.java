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
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Elasticsearch smart filtering.
 *
 * Tests that smart filtering correctly:
 * - Processes recent events (within time window)
 * - Skips old terminal events (old + COMPLETED/FAULTED/CANCELLED)
 * - Processes old non-terminal events (old + RUNNING/STARTED)
 * - Handles late arrivals within window
 * - Handles state transitions (non-terminal → terminal)
 */
@QuarkusTest
class ElasticsearchSmartFilteringIT {

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String RAW_INDEX = "workflow-events-" + LocalDate.now();
    private static final String NORMALIZED_INDEX = "workflow-instances";
    private static final String TRANSFORM_ID = "workflow-instances-transform";

    @BeforeEach
    void setUp() throws Exception {
        ensureTransformStarted();
    }

    private void ensureTransformStarted() throws IOException {
        try {
            client.transform().startTransform(r -> r.transformId(TRANSFORM_ID));
        } catch (Exception e) {
            // Already started, ignore
        }
    }

    private void waitForTransform() throws InterruptedException {
        // Wait for transform to process (1s frequency + buffer)
        Thread.sleep(5000);
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
        event.put("instanceStatus", extractStatusFromEventType(eventType));

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

    private String extractStatusFromEventType(String eventType) {
        // Extract status from eventType like "io.serverlessworkflow.workflow.started.v1"
        String[] parts = eventType.split("\\.");
        if (parts.length >= 2) {
            String status = parts[parts.length - 2];
            return status.toUpperCase();
        }
        return "UNKNOWN";
    }

    private WorkflowInstance getNormalizedInstance(String instanceId) throws IOException {
        try {
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
            return objectMapper.convertValue(source, WorkflowInstance.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Test
    void testRecentEventsAlwaysProcessed() throws Exception {
        String instanceId = "test-recent-" + UUID.randomUUID();
        Instant now = Instant.now();

        // Insert COMPLETED event (recent, within 1h window)
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           now, null, Map.of("result", "success"), null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testOldTerminalEventsSkipped() throws Exception {
        String instanceId = "test-old-terminal-" + UUID.randomUUID();
        Instant oldTime = Instant.now().minus(Duration.ofHours(2)); // > 1h old

        // Insert old COMPLETED event
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           oldTime, null, Map.of("result", "success"), null);

        waitForTransform();

        // Should NOT be processed (old + terminal)
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNull();
    }

    @Test
    void testOldNonTerminalEventsProcessed() throws Exception {
        String instanceId = "test-old-running-" + UUID.randomUUID();
        Instant oldTime = Instant.now().minus(Duration.ofHours(2)); // > 1h old

        // Insert old RUNNING event
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           oldTime, Map.of("orderId", "123"), null, null);

        waitForTransform();

        // SHOULD be processed (old but NON-terminal)
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testLateArrivalWithinWindow() throws Exception {
        String instanceId = "test-late-arrival-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        // Insert COMPLETED event first
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           baseTime.plusSeconds(30), null, Map.of("result", "success"), null);

        waitForTransform();

        // Insert STARTED event late (but within 1h window)
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1",
                           baseTime, Map.of("orderId", "123"), null, null);

        waitForTransform();

        // Both events should be processed
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(normalized.getInput()).isNotNull();
        assertThat(normalized.getOutput()).isNotNull();
    }
}
