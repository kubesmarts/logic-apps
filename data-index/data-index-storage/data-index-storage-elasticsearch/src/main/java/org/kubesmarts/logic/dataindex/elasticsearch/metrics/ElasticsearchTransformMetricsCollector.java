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
package org.kubesmarts.logic.dataindex.elasticsearch.metrics;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.transform.GetTransformStatsRequest;
import co.elastic.clients.elasticsearch.transform.GetTransformStatsResponse;
import co.elastic.clients.elasticsearch.transform.get_transform_stats.TransformStats;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
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

    @ConfigProperty(name = "data-index.metrics.transform.enabled", defaultValue = "true")
    boolean metricsEnabled;

    private static final List<String> TRANSFORM_IDS = List.of(
        "workflow-instances-transform",
        "task-executions-transform"
    );

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
                registry.gauge("data_index.transform.state",
                    Tags.of("transform", transformId), -1);
            }
        }
    }

    private void collectMetricsForTransform(String transformId) throws Exception {
        GetTransformStatsRequest request = GetTransformStatsRequest.of(builder ->
            builder.transformId(transformId));

        GetTransformStatsResponse response = client.transform().getTransformStats(request);

        if (response.transforms().isEmpty()) {
            LOGGER.warn("Transform '{}' not found, skipping metrics", transformId);
            return;
        }

        TransformStats stats = response.transforms().get(0);
        updateMetrics(transformId, stats);
    }

    private void updateMetrics(String transformId, TransformStats stats) {
        Tags tags = Tags.of("transform", transformId);

        // Documents processed
        registry.gauge("data_index.transform.documents_processed", tags,
            stats.stats().documentsProcessed());

        // Documents indexed
        registry.gauge("data_index.transform.documents_indexed", tags,
            stats.stats().documentsIndexed());

        // Lag (processed - indexed)
        long lag = stats.stats().documentsProcessed() - stats.stats().documentsIndexed();
        registry.gauge("data_index.transform.lag", tags, lag);

        // State (0=stopped, 1=started, 2=failed, -1=unknown)
        int stateValue = mapStateToNumeric(stats.state());
        registry.gauge("data_index.transform.state", tags, stateValue);

        // Last checkpoint timestamp (if available)
        if (stats.checkpointing() != null && stats.checkpointing().last() != null) {
            long checkpoint = stats.checkpointing().last().timestampMillis();
            registry.gauge("data_index.transform.last_checkpoint", tags, checkpoint);
        }

        LOGGER.debug("Updated metrics for transform '{}': processed={}, indexed={}, lag={}, state={}",
            transformId, stats.stats().documentsProcessed(), stats.stats().documentsIndexed(),
            lag, stats.state());
    }

    private int mapStateToNumeric(String state) {
        return switch (state.toLowerCase()) {
            case "started" -> 1;
            case "stopped" -> 0;
            case "failed" -> 2;
            default -> -1;  // unknown
        };
    }
}
