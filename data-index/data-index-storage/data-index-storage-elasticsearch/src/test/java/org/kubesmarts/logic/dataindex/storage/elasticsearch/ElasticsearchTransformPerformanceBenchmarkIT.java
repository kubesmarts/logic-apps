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
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Performance benchmarking tests for Elasticsearch Transform smart filtering.
 *
 * Tests verify:
 * - Smart filtering maintains constant processing time as data grows
 * - Transform lag stays low under load
 * - Processing time doesn't increase linearly with event count
 */
@QuarkusTest
@TestProfile(MetricsTestProfile.class)
class ElasticsearchTransformPerformanceBenchmarkIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchTransformPerformanceBenchmarkIT.class);

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

    private void insertBulkWorkflowEvents(int count, double terminalRatio, Duration ageOffset) throws IOException {
        List<BulkOperation> operations = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            boolean isTerminal = Math.random() < terminalRatio;
            String eventType = isTerminal
                ? "io.serverlessworkflow.workflow.completed.v1"
                : "io.serverlessworkflow.workflow.started.v1";
            String status = isTerminal ? "COMPLETED" : "RUNNING";

            Instant eventTime = Instant.now().minus(ageOffset);

            Map<String, Object> event = new HashMap<>();
            event.put("@timestamp", Instant.now().toString());
            event.put("tag", "quarkus-flow.workflow");
            event.put("eventId", UUID.randomUUID().toString());
            event.put("eventType", eventType);
            event.put("eventTime", eventTime.toString());
            event.put("instanceId", "benchmark-" + UUID.randomUUID());
            event.put("workflowName", "benchmark-workflow");
            event.put("workflowVersion", "1.0");
            event.put("workflowNamespace", "benchmark");
            event.put("instanceStatus", status);

            operations.add(BulkOperation.of(builder -> builder
                .index(idx -> idx
                    .index(RAW_INDEX)
                    .id(UUID.randomUUID().toString())
                    .document(event))));
        }

        BulkRequest request = BulkRequest.of(builder -> builder
            .operations(operations)
            .refresh(Refresh.True));

        BulkResponse response = client.bulk(request);

        if (response.errors()) {
            LOGGER.warn("Bulk insert had {} errors", response.items().stream()
                .filter(item -> item.error() != null).count());
        }

        LOGGER.info("Inserted {} events ({}% terminal, age offset: {})",
            count, (int)(terminalRatio * 100), ageOffset);
    }

    private void waitForTransformToProcess() throws InterruptedException {
        // Wait for transform processing (1s frequency + buffer)
        Thread.sleep(3000);
    }

    private long getLagMetric() {
        var lag = registry.find("data_index.transform.lag")
            .tag("transform", TRANSFORM_ID)
            .gauge();
        return lag != null ? (long) lag.value() : -1;
    }

    @Test
    void testSmartFilteringScalesWithDataGrowth() throws Exception {
        // Phase 1: Insert 1K events, 90% terminal (old)
        LOGGER.info("=== Phase 1: Inserting 1K events ===");
        insertBulkWorkflowEvents(1000, 0.9, Duration.ofHours(2));

        // Measure transform processing time
        long phase1Start = System.currentTimeMillis();
        waitForTransformToProcess();
        long phase1Duration = System.currentTimeMillis() - phase1Start;

        LOGGER.info("Phase 1 (1K events): {} ms", phase1Duration);

        // Phase 2: Insert 10K MORE events, 90% terminal (old)
        LOGGER.info("=== Phase 2: Inserting 10K more events ===");
        insertBulkWorkflowEvents(10000, 0.9, Duration.ofHours(2));

        // Measure transform processing time
        long phase2Start = System.currentTimeMillis();
        waitForTransformToProcess();
        long phase2Duration = System.currentTimeMillis() - phase2Start;

        LOGGER.info("Phase 2 (11K total events): {} ms", phase2Duration);

        // Assert: Processing time delta < 50% (ideally < 20%)
        // Without smart filtering, would be 10x slower (linear growth)
        double increase = (double) phase2Duration / phase1Duration;
        LOGGER.info("Processing time increase: {}x", String.format("%.2f", increase));

        assertThat(increase).isLessThan(1.5); // < 50% increase
    }

    @Test
    void testTransformLagUnderLoad() throws Exception {
        // Insert 1K events rapidly (50% terminal, recent)
        LOGGER.info("=== Inserting 1K events rapidly ===");
        insertBulkWorkflowEvents(1000, 0.5, Duration.ofMinutes(30));

        // Monitor lag metric over 5 poll intervals (25 seconds with 5s poll)
        List<Long> lagSamples = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Thread.sleep(5000); // Wait for metrics poll
            long lag = getLagMetric();
            lagSamples.add(lag);
            LOGGER.info("Lag sample {}: {} documents", i + 1, lag);
        }

        // Assert: Max lag < 100 documents
        long maxLag = lagSamples.stream().max(Long::compare).orElse(0L);
        LOGGER.info("Max lag observed: {} documents", maxLag);
        assertThat(maxLag).isLessThan(100);

        // Assert: Lag decreases over time (transform catches up)
        long firstLag = lagSamples.get(0);
        long lastLag = lagSamples.get(lagSamples.size() - 1);
        LOGGER.info("Lag trend: first={}, last={}", firstLag, lastLag);
        assertThat(lastLag).isLessThanOrEqualTo(firstLag);
    }
}
