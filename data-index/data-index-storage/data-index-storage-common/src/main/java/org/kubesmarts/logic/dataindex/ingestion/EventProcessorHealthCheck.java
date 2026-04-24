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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Liveness;
import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend-agnostic health check for event processor.
 *
 * <p><b>Purpose</b>: Monitors event processor health for Kubernetes liveness/readiness probes.
 *
 * <p><b>Health Criteria</b>:
 * <ul>
 *   <li>Processing lag below threshold (default 60s)
 *   <li>Backlog below threshold (default 1000 events)
 * </ul>
 *
 * <p><b>Storage-Agnostic</b>: Works with any EventProcessor implementation (PostgreSQL, Elasticsearch, etc.).
 *
 * <p><b>Configuration</b>:
 * <pre>
 * data-index.event-processor.lag.threshold.seconds=60
 * data-index.event-processor.backlog.threshold=1000
 * </pre>
 */
@Liveness
@ApplicationScoped
public class EventProcessorHealthCheck implements HealthCheck {

    private static final Logger log = LoggerFactory.getLogger(EventProcessorHealthCheck.class);

    @Inject
    Instance<PollingEventProcessor<?>> eventProcessors;

    @ConfigProperty(name = "data-index.event-processor.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "data-index.event-processor.lag.threshold.seconds", defaultValue = "60")
    long lagThresholdSeconds;

    @ConfigProperty(name = "data-index.event-processor.backlog.threshold", defaultValue = "1000")
    long backlogThreshold;

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("event-processor");

        if (!enabled) {
            return builder.up()
                    .withData("enabled", false)
                    .withData("message", "Event processor is disabled")
                    .build();
        }

        try {
            boolean lagHealthy = true;
            boolean backlogHealthy = true;
            long maxLag = 0;
            long totalBacklog = 0;

            // Check each processor
            for (PollingEventProcessor<?> processor : eventProcessors) {
                try {
                    String processorName = processor.getProcessorName();

                    // Check lag
                    long lag = processor.getOldestUnprocessedAgeSeconds();
                    maxLag = Math.max(maxLag, lag);
                    if (lag >= lagThresholdSeconds) {
                        lagHealthy = false;
                    }

                    // Check backlog
                    long backlog = processor.getBacklog();
                    totalBacklog += backlog;
                    if (backlog >= backlogThreshold) {
                        backlogHealthy = false;
                    }

                    // Add processor-specific data
                    builder.withData(processorName + "LagSeconds", lag);
                    builder.withData(processorName + "Backlog", backlog);
                } catch (Exception e) {
                    log.error("Error checking health for processor '{}'",
                            processor.getProcessorName(), e);
                    return builder.down()
                            .withData("error", "Error checking processor: " + processor.getProcessorName())
                            .withData("errorMessage", e.getMessage())
                            .build();
                }
            }

            // Overall health
            boolean overall = lagHealthy && backlogHealthy;

            return builder
                    .status(overall)
                    .withData("enabled", true)
                    .withData("maxLagSeconds", maxLag)
                    .withData("totalBacklog", totalBacklog)
                    .withData("lagThresholdSeconds", lagThresholdSeconds)
                    .withData("backlogThreshold", backlogThreshold)
                    .withData("lagHealthy", lagHealthy)
                    .withData("backlogHealthy", backlogHealthy)
                    .build();
        } catch (Exception e) {
            log.error("Error checking event processor health", e);
            return builder.down()
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}
