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
package org.kubesmarts.logic.dataindex.processor.polling;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.kubesmarts.logic.dataindex.processor.config.EventProcessorConfiguration;

import io.quarkus.scheduler.Scheduled;

/**
 * Event processor scheduler for Mode 1 (Polling + PostgreSQL).
 *
 * <p><b>Deployment Mode: Mode 1 - Polling + PostgreSQL</b>
 * <pre>
 * Quarkus Flow → Logs → FluentBit → PostgreSQL event tables
 *                                          ↓ (polling every 5s)
 *                                    EventProcessorScheduler
 *                                          ↓
 *                                  Normalized tables → Data Index GraphQL
 * </pre>
 *
 * <p><b>Responsibility:</b>
 * <ul>
 *   <li>Poll event tables periodically (default: every 5s)</li>
 *   <li>Invoke all registered {@link PollingEventProcessor} beans</li>
 *   <li>Process events in batches (default: 100 events)</li>
 * </ul>
 *
 * <p><b>Configuration:</b>
 * <pre>
 * data-index.event-processor.mode=polling
 * data-index.event-processor.enabled=true
 * data-index.event-processor.interval=5s
 * data-index.event-processor.batch-size=100
 * </pre>
 *
 * <p><b>Event Processors:</b>
 * <ul>
 *   <li>{@code PostgreSQLWorkflowInstanceEventProcessor} - Processes workflow_instance_events</li>
 *   <li>{@code PostgreSQLTaskExecutionEventProcessor} - Processes task_execution_events</li>
 * </ul>
 *
 * @see EventProcessorConfiguration
 * @see PollingEventProcessor
 */
@ApplicationScoped
public class EventProcessorScheduler {

    private static final Logger LOG = Logger.getLogger(EventProcessorScheduler.class);

    private final EventProcessorConfiguration config;
    private final Instance<PollingEventProcessor<?>> eventProcessors;

    @Inject
    public EventProcessorScheduler(
            EventProcessorConfiguration config,
            Instance<PollingEventProcessor<?>> eventProcessors) {
        this.config = config;
        this.eventProcessors = eventProcessors;
    }

    /**
     * Poll event tables and process events.
     *
     * <p>Scheduled execution controlled by:
     * <ul>
     *   <li>{@code data-index.event-processor.interval} - Polling interval (default: 5s)</li>
     *   <li>{@code data-index.event-processor.enabled} - Enable/disable processing</li>
     * </ul>
     *
     * <p><b>Metrics:</b>
     * <ul>
     *   <li>{@code event_processor_events_processed_total} - Total events processed</li>
     *   <li>{@code event_processor_processing_duration_seconds} - Processing duration</li>
     *   <li>{@code event_processor_lag_seconds} - Age of oldest unprocessed event</li>
     *   <li>{@code event_processor_backlog_count} - Number of unprocessed events</li>
     * </ul>
     */
    @Scheduled(
            identity = "event-processor-polling",
            every = "{data-index.event-processor.interval}",
            skipExecutionIf = ProcessingDisabledPredicate.class)
    public void processEvents() {
        if (config.mode() != EventProcessorConfiguration.Mode.POLLING) {
            LOG.tracef("Skipping polling - mode is %s", config.mode());
            return;
        }

        LOG.tracef("Starting scheduled event processing (interval=%s, batch-size=%d)",
                config.interval(), config.batchSize());

        long totalProcessed = 0;
        long startTime = System.currentTimeMillis();

        for (PollingEventProcessor<?> processor : eventProcessors) {
            try {
                int processed = processor.processBatch(config.batchSize());
                totalProcessed += processed;

                if (processed > 0) {
                    LOG.debugf("Processed %d events with %s", processed, processor.getClass().getSimpleName());
                }
            } catch (Exception e) {
                LOG.errorf(e, "Error processing events with %s", processor.getClass().getSimpleName());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (totalProcessed > 0) {
            LOG.infof("Processed %d events in %d ms", totalProcessed, duration);
        }

        if (duration > config.slowProcessing().ms()) {
            LOG.warnf("Slow processing detected: %d ms (threshold: %d ms)",
                    duration, config.slowProcessing().ms());
        }
    }

    /**
     * Predicate to skip execution when event processing is disabled.
     *
     * <p>Controlled by: {@code data-index.event-processor.enabled}
     */
    public static class ProcessingDisabledPredicate implements Scheduled.SkipPredicate {

        @Inject
        EventProcessorConfiguration config;

        @Override
        public boolean test(io.quarkus.scheduler.ScheduledExecution execution) {
            return !config.enabled();
        }
    }
}
