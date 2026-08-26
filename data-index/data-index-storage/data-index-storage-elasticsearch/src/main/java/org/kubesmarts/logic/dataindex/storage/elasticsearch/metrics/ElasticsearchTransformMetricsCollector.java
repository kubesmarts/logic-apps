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
package org.kubesmarts.logic.dataindex.storage.elasticsearch.metrics;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Gauge;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.runtime.Startup;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Collects Elasticsearch Transform metrics and exposes them via Micrometer.
 *
 * Polls Transform Stats API on a schedule and updates gauges for:
 * - documents_processed: Total documents processed by transform
 * - documents_indexed: Total documents indexed to destination
 * - lag: Processing lag (processed - indexed)
 * - state: Transform state (0=stopped, 1=started, 2=failed, -1=unknown)
 * - last_checkpoint: Last checkpoint timestamp (epoch millis)
 */
@ApplicationScoped
@Startup
public class ElasticsearchTransformMetricsCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchTransformMetricsCollector.class);

    @Inject
    ElasticsearchClient client;

    @Inject
    MeterRegistry registry;

    @Inject
    RestClient restClient;

    @Inject
    ObjectMapper objectMapper;

    @ConfigProperty(name = "data-index.metrics.transform.enabled", defaultValue = "true")
    boolean metricsEnabled;

    private static final List<String> TRANSFORM_IDS = List.of(
        "workflow-instances-transform",
        "task-executions-transform"
    );

    private final Map<String, AtomicLong> documentsProcessedGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> documentsIndexedGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lagGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> stateGauges = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastCheckpointGauges = new ConcurrentHashMap<>();

    /**
     * Collect metrics for all transforms on schedule.
     *
     * Default: every 30s (configurable via data-index.metrics.transform.poll-interval)
     */
    @Scheduled(every = "{data-index.metrics.transform.poll-interval:30s}")
    void collectTransformMetrics() {
        if (!metricsEnabled) {
            return;
        }

        for (String transformId : TRANSFORM_IDS) {
            try {
                collectMetricsForTransform(transformId);
            } catch (Exception e) {
                LOGGER.warn("Failed to collect metrics for transform '{}': {}", transformId, e.getMessage());
                // Set state to unknown (-1) on error
                gauge("data_index.transform.state", transformId, stateGauges).set(-1);
            }
        }
    }

    private AtomicLong gauge(String metricName, String transformId, Map<String, AtomicLong> cache) {
        return cache.computeIfAbsent(transformId, id -> {
            AtomicLong value = new AtomicLong(0L);
    
            Gauge.builder(metricName, value, AtomicLong::get)
                    .tag("transform", id)
                    .register(registry);
    
            return value;
        });
    }

    private void collectMetricsForTransform(String transformId) throws Exception {
        Request request = new Request("GET", "/_transform/" + transformId + "/_stats");
        Response response = restClient.performRequest(request);
    
        try (InputStream is = response.getEntity().getContent()) {
            JsonNode root = objectMapper.readTree(is);
            JsonNode transforms = root.path("transforms");
    
            if (!transforms.isArray() || transforms.isEmpty()) {
                LOGGER.warn("Transform '{}' not found, skipping metrics", transformId);
                return;
            }
    
            JsonNode transform = transforms.get(0);
            updateMetricsFromJson(transformId, transform);
        }
    }

    private void updateMetricsFromJson(String transformId, JsonNode transform) {
        JsonNode stats = transform.path("stats");
    
        long documentsProcessed = stats.path("documents_processed").asLong(0L);
        long documentsIndexed = stats.path("documents_indexed").asLong(0L);
        long lag = Math.max(0L, documentsProcessed - documentsIndexed);
    
        String state = transform.path("state").asText("unknown");
        int stateValue = mapStateToNumeric(state);
    
        gauge("data_index.transform.documents_processed", transformId, documentsProcessedGauges)
                .set(documentsProcessed);
    
        gauge("data_index.transform.documents_indexed", transformId, documentsIndexedGauges)
                .set(documentsIndexed);
    
        gauge("data_index.transform.lag", transformId, lagGauges)
                .set(lag);
    
        gauge("data_index.transform.state", transformId, stateGauges)
                .set(stateValue);
    
        JsonNode checkpoint = transform.path("checkpointing").path("last").path("timestamp_millis");
        if (!checkpoint.isMissingNode() && !checkpoint.isNull()) {
            gauge("data_index.transform.last_checkpoint", transformId, lastCheckpointGauges)
                    .set(checkpoint.asLong());
        }
    
        LOGGER.debug(
                "Updated metrics for transform '{}': processed={}, indexed={}, lag={}, state={}",
                transformId,
                documentsProcessed,
                documentsIndexed,
                lag,
                state);
    }

    private int mapStateToNumeric(String state) {
        return switch (state.toLowerCase()) {
            case "started", "indexing"  -> 1;
            case "stopped" -> 0;
            case "failed" -> 2;
            default -> -1;  // unknown
        };
    }
}
