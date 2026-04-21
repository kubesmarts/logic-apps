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
package org.kubesmarts.logic.dataindex.processor.kafka;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.kubesmarts.logic.dataindex.api.KafkaEventProcessor;
import org.kubesmarts.logic.dataindex.processor.config.EventProcessorConfiguration;

/**
 * Kafka event consumer for Mode 3 (Kafka + PostgreSQL).
 *
 * <p><b>Deployment Mode: Mode 3 - Kafka + PostgreSQL</b>
 * <pre>
 * Quarkus Flow → Logs → FluentBit → Kafka → KafkaEventConsumer → Normalized tables → Data Index GraphQL
 * </pre>
 *
 * <p><b>Responsibility:</b>
 * <ul>
 *   <li>Consume events from Kafka topics in real-time</li>
 *   <li>Invoke all registered {@link KafkaEventProcessor} beans</li>
 *   <li>Process events individually (not batched)</li>
 * </ul>
 *
 * <p><b>Configuration:</b>
 * <pre>
 * data-index.event-processor.mode=kafka
 * kafka.bootstrap.servers=localhost:9092
 * mp.messaging.incoming.workflow-events.topic=workflow-events
 * mp.messaging.incoming.workflow-events.connector=smallrye-kafka
 * mp.messaging.incoming.task-events.topic=task-events
 * mp.messaging.incoming.task-events.connector=smallrye-kafka
 * </pre>
 *
 * <p><b>Topics:</b>
 * <ul>
 *   <li>{@code workflow-events} - Workflow instance events (STARTED, COMPLETED, ERROR)</li>
 *   <li>{@code task-events} - Task execution events (STARTED, COMPLETED, SKIPPED, ABORTED)</li>
 * </ul>
 *
 * <p><b>Event Processors:</b>
 * <ul>
 *   <li>{@code KafkaWorkflowInstanceEventProcessor} - Processes workflow events from Kafka</li>
 *   <li>{@code KafkaTaskExecutionEventProcessor} - Processes task events from Kafka</li>
 * </ul>
 *
 * @see EventProcessorConfiguration
 * @see KafkaEventProcessor
 */
@ApplicationScoped
public class KafkaEventConsumer {

    private static final Logger LOG = Logger.getLogger(KafkaEventConsumer.class);

    private final EventProcessorConfiguration config;
    private final Instance<KafkaEventProcessor<String>> eventProcessors;

    @Inject
    public KafkaEventConsumer(
            EventProcessorConfiguration config,
            Instance<KafkaEventProcessor<String>> eventProcessors) {
        this.config = config;
        this.eventProcessors = eventProcessors;
    }

    /**
     * Consume workflow instance events from Kafka.
     *
     * <p><b>Topic:</b> {@code workflow-events}
     *
     * <p><b>Event Format:</b>
     * <pre>{@code
     * {
     *   "id": "uuid",
     *   "instanceId": "workflow-instance-id",
     *   "definitionId": "workflow-definition-id",
     *   "version": "1.0.0",
     *   "state": "STARTED|COMPLETED|ERROR",
     *   "timestamp": "2026-04-21T12:00:00Z",
     *   "data": { ... }
     * }
     * }</pre>
     *
     * @param event workflow event JSON
     */
    @Incoming("workflow-events")
    public void onWorkflowEvent(String event) {
        if (config.mode() != EventProcessorConfiguration.Mode.KAFKA) {
            LOG.tracef("Skipping Kafka event - mode is %s", config.mode());
            return;
        }

        if (!config.enabled()) {
            LOG.trace("Skipping Kafka event - event processing is disabled");
            return;
        }

        LOG.tracef("Received workflow event from Kafka: %s", event);

        long startTime = System.currentTimeMillis();

        for (KafkaEventProcessor<String> processor : eventProcessors) {
            try {
                if ("workflow".equals(processor.getProcessorName())) {
                    processor.processEvent(event);
                }
            } catch (Exception e) {
                LOG.errorf(e, "Error processing workflow event with %s", processor.getClass().getSimpleName());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (duration > config.slowProcessing().ms()) {
            LOG.warnf("Slow Kafka event processing: %d ms (threshold: %d ms)",
                    duration, config.slowProcessing().ms());
        }
    }

    /**
     * Consume task execution events from Kafka.
     *
     * <p><b>Topic:</b> {@code task-events}
     *
     * <p><b>Event Format:</b>
     * <pre>{@code
     * {
     *   "id": "uuid",
     *   "taskId": "task-id",
     *   "workflowInstanceId": "workflow-instance-id",
     *   "taskName": "task-name",
     *   "state": "STARTED|COMPLETED|SKIPPED|ABORTED",
     *   "timestamp": "2026-04-21T12:00:00Z",
     *   "input": { ... },
     *   "output": { ... }
     * }
     * }</pre>
     *
     * @param event task event JSON
     */
    @Incoming("task-events")
    public void onTaskEvent(String event) {
        if (config.mode() != EventProcessorConfiguration.Mode.KAFKA) {
            LOG.tracef("Skipping Kafka event - mode is %s", config.mode());
            return;
        }

        if (!config.enabled()) {
            LOG.trace("Skipping Kafka event - event processing is disabled");
            return;
        }

        LOG.tracef("Received task event from Kafka: %s", event);

        long startTime = System.currentTimeMillis();

        for (KafkaEventProcessor<String> processor : eventProcessors) {
            try {
                if ("task".equals(processor.getProcessorName())) {
                    processor.processEvent(event);
                }
            } catch (Exception e) {
                LOG.errorf(e, "Error processing task event with %s", processor.getClass().getSimpleName());
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (duration > config.slowProcessing().ms()) {
            LOG.warnf("Slow Kafka event processing: %d ms (threshold: %d ms)",
                    duration, config.slowProcessing().ms());
        }
    }
}
