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
package org.kubesmarts.logic.dataindex.metrics;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Backend-agnostic REST endpoint for event processor metrics.
 *
 * <p><b>Purpose</b>: Provides metrics for custom dashboards and monitoring tools.
 *
 * <p><b>Storage-Agnostic</b>: Works with any EventProcessor implementation (PostgreSQL, Elasticsearch, etc.).
 *
 * <p><b>Endpoint</b>: GET /event-processor/metrics
 *
 * <p><b>Response Example</b>:
 * <pre>
 * {
 *   "processors": {
 *     "workflow": {
 *       "processorName": "workflow",
 *       "backlog": 0,
 *       "oldestUnprocessedAgeSeconds": 0
 *     },
 *     "task": {
 *       "processorName": "task",
 *       "backlog": 5,
 *       "oldestUnprocessedAgeSeconds": 12
 *     }
 *   },
 *   "totalBacklog": 5,
 *   "maxLagSeconds": 12
 * }
 * </pre>
 *
 * <p><b>Use Cases</b>:
 * <ul>
 *   <li>Custom Grafana dashboards
 *   <li>Alert rule configuration
 *   <li>Operational visibility
 *   <li>Capacity planning
 * </ul>
 */
@Path("/event-processor/metrics")
@Produces(MediaType.APPLICATION_JSON)
public class EventProcessorMetricsResource {

    private static final Logger log = LoggerFactory.getLogger(EventProcessorMetricsResource.class);

    @Inject
    Instance<PollingEventProcessor<?>> eventProcessors;

    /**
     * Get comprehensive event processor metrics.
     *
     * @return Event processor metrics response
     */
    @GET
    public EventProcessorMetricsResponse getMetrics() {
        log.debug("Fetching event processor metrics");

        EventProcessorMetricsResponse response = new EventProcessorMetricsResponse();
        long totalBacklog = 0;
        long maxLag = 0;

        // Collect metrics from all processors
        for (PollingEventProcessor<?> processor : eventProcessors) {
            try {
                String processorName = processor.getProcessorName();
                long backlog = processor.getBacklog();
                long lag = processor.getOldestUnprocessedAgeSeconds();

                // Create metrics object
                EventMetrics metrics = new EventMetrics(processorName, backlog, lag);
                response.addProcessor(processorName, metrics);

                // Update aggregates
                totalBacklog += backlog;
                maxLag = Math.max(maxLag, lag);

            } catch (Exception e) {
                log.error("Error getting metrics for processor '{}'",
                        processor.getProcessorName(), e);
            }
        }

        response.setTotalBacklog(totalBacklog);
        response.setMaxLagSeconds(maxLag);

        return response;
    }
}
