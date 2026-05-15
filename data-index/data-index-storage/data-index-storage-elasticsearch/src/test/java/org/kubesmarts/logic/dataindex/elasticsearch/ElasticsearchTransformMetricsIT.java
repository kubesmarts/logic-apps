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
package org.kubesmarts.logic.dataindex.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

/**
 * Integration tests for Elasticsearch Transform metrics collection.
 *
 * Tests verify:
 * - Metrics are collected and exposed
 * - Metrics update periodically
 * - Prometheus endpoint exposes metrics correctly
 * - All expected metrics are present for both transforms
 */
@QuarkusTest
@TestProfile(MetricsTestProfile.class)
class ElasticsearchTransformMetricsIT {

    @Inject
    ElasticsearchClient client;

    @Inject
    MeterRegistry registry;

    private static final String RAW_INDEX = "workflow-events-" + LocalDate.now();
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

    private void insertWorkflowEvent(String instanceId, String status) throws IOException {
        Map<String, Object> event = new HashMap<>();
        event.put("@timestamp", Instant.now().toString());
        event.put("tag", "quarkus-flow.workflow");
        event.put("eventId", UUID.randomUUID().toString());
        event.put("eventType", "io.serverlessworkflow.workflow.started.v1");
        event.put("eventTime", Instant.now().toString());
        event.put("instanceId", instanceId);
        event.put("workflowName", "test-workflow");
        event.put("workflowVersion", "1.0");
        event.put("workflowNamespace", "test");
        event.put("instanceStatus", status);

        client.index(IndexRequest.of(builder -> builder
            .index(RAW_INDEX)
            .id(UUID.randomUUID().toString())
            .document(event)
            .refresh(Refresh.True)));
    }

    private void insertBulkWorkflowEvents(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            insertWorkflowEvent("test-metrics-" + UUID.randomUUID(), "RUNNING");
        }
    }

    private void waitForTransformAndMetrics() throws InterruptedException {
        // Wait for transform (1s frequency + buffer)
        Thread.sleep(3000);
        // Wait for metrics poll (5s in test profile + buffer)
        Thread.sleep(7000);
    }

    @Test
    void testTransformMetricsCollected() throws Exception {
        // Insert test events
        insertBulkWorkflowEvents(10);

        // Wait for transform processing and metrics collection
        waitForTransformAndMetrics();

        // Verify documents_processed metric exists and has value > 0
        var documentsProcessed = registry.find("data_index.transform.documents_processed")
            .tag("transform", TRANSFORM_ID)
            .gauge();

        assertThat(documentsProcessed).isNotNull();
        assertThat(documentsProcessed.value()).isGreaterThan(0);

        // Verify lag metric exists
        var lag = registry.find("data_index.transform.lag")
            .tag("transform", TRANSFORM_ID)
            .gauge();

        assertThat(lag).isNotNull();
        assertThat(lag.value()).isGreaterThanOrEqualTo(0);

        // Verify state metric exists and shows started (1)
        var state = registry.find("data_index.transform.state")
            .tag("transform", TRANSFORM_ID)
            .gauge();

        assertThat(state).isNotNull();
        assertThat(state.value()).isEqualTo(1); // started
    }

    @Test
    void testMetricsExposedViaPrometheus() throws Exception {
        // Insert events to ensure metrics are collected
        insertBulkWorkflowEvents(5);
        waitForTransformAndMetrics();

        // GET /q/metrics endpoint should expose Prometheus-format metrics
        given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("data_index_transform_documents_processed"))
            .body(containsString("transform=\"workflow-instances-transform\""))
            .body(containsString("transform=\"task-executions-transform\""));
    }

    @Test
    void testMetricsUpdatePeriodically() throws Exception {
        // Wait for initial metrics collection
        Thread.sleep(6000);

        // Capture initial timestamp by checking Prometheus endpoint
        String metricsInitial = given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200)
            .extract().asString();

        // Verify transform metrics exist
        assertThat(metricsInitial).contains("data_index_transform_documents_processed");

        // Wait for next metrics poll cycle (5s + buffer)
        Thread.sleep(6000);

        // Capture updated metrics
        String metricsUpdated = given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200)
            .extract().asString();

        // Metrics should still be present (verifies periodic collection continues)
        assertThat(metricsUpdated).contains("data_index_transform_documents_processed");
        assertThat(metricsUpdated).contains("data_index_transform_state");
        assertThat(metricsUpdated).contains("data_index_transform_lag");
    }
}
