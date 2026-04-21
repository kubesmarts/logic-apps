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
package org.kubesmarts.logic.dataindex.ingestion;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.quarkus.scheduler.Scheduled;

/**
 * Backend-agnostic metrics for event processor monitoring.
 *
 * <p><b>Purpose</b>: Provides Prometheus-compatible metrics for event processor health and performance.
 *
 * <p><b>Metrics</b>:
 * <ul>
 *   <li>event.processor.lag.seconds{processor="name"} - Age of oldest unprocessed event
 *   <li>event.processor.backlog.total{processor="name"} - Number of unprocessed events
 *   <li>event.processor.oldest.unprocessed.age.seconds{processor="name"} - Age of oldest unprocessed event
 * </ul>
 *
 * <p><b>Update Frequency</b>: Gauges updated every 1 minute.
 *
 * <p><b>Storage-Agnostic</b>: Works with any EventProcessor implementation (PostgreSQL, Elasticsearch, etc.).
 */
@ApplicationScoped
public class EventProcessorMetrics {

    private static final Logger log = LoggerFactory.getLogger(EventProcessorMetrics.class);

    @Inject
    Instance<PollingEventProcessor<?>> eventProcessors;

    @Inject
    MeterRegistry meterRegistry;

    @ConfigProperty(name = "data-index.event-processor.enabled", defaultValue = "true")
    boolean enabled;

    // Atomic gauges for thread-safe updates (per processor)
    private final Map<String, AtomicLong> backlogGauges = new HashMap<>();
    private final Map<String, AtomicLong> lagGauges = new HashMap<>();
    private final Map<String, AtomicLong> oldestAgeGauges = new HashMap<>();

    /**
     * Initialize gauges on startup.
     */
    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent event) {
        log.info("Initializing event processor metrics");

        // Register gauges for each processor
        for (PollingEventProcessor<?> processor : eventProcessors) {
            String processorName = processor.getProcessorName();

            // Create atomic gauges
            AtomicLong backlog = new AtomicLong(0);
            AtomicLong lag = new AtomicLong(0);
            AtomicLong oldestAge = new AtomicLong(0);

            // Store in maps
            backlogGauges.put(processorName, backlog);
            lagGauges.put(processorName, lag);
            oldestAgeGauges.put(processorName, oldestAge);

            // Register with Micrometer
            meterRegistry.gauge("event.processor.backlog.total",
                    Tags.of("processor", processorName),
                    backlog);

            meterRegistry.gauge("event.processor.lag.seconds",
                    Tags.of("processor", processorName),
                    lag);

            meterRegistry.gauge("event.processor.oldest.unprocessed.age.seconds",
                    Tags.of("processor", processorName),
                    oldestAge);

            log.info("Registered metrics for processor '{}'", processorName);
        }
    }

    /**
     * Update metrics every minute.
     */
    @Scheduled(every = "1m")
    public void updateMetrics() {
        if (!enabled) {
            return;
        }

        for (PollingEventProcessor<?> processor : eventProcessors) {
            try {
                String processorName = processor.getProcessorName();

                // Update backlog
                long backlog = processor.getBacklog();
                backlogGauges.get(processorName).set(backlog);

                // Update oldest unprocessed age
                long oldestAge = processor.getOldestUnprocessedAgeSeconds();
                oldestAgeGauges.get(processorName).set(oldestAge);
                lagGauges.get(processorName).set(oldestAge); // Lag = oldest age

                log.trace("Updated metrics for processor '{}': backlog={}, lag={}s",
                        processorName, backlog, oldestAge);
            } catch (Exception e) {
                log.error("Error updating metrics for processor '{}'",
                        processor.getProcessorName(), e);
            }
        }
    }
}
