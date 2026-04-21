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
package org.kubesmarts.logic.dataindex.processor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import org.kubesmarts.logic.dataindex.api.KafkaEventProcessor;
import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity;
import org.kubesmarts.logic.dataindex.entity.WorkflowInstanceErrorEntity;
import org.kubesmarts.logic.dataindex.event.WorkflowInstanceEvent;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
import org.kubesmarts.logic.dataindex.repository.WorkflowInstanceEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * PostgreSQL-specific processor for workflow instance events.
 *
 * <p><b>Dual Mode Support</b>: Implements both {@link PollingEventProcessor} and {@link KafkaEventProcessor}
 *
 * <p><b>Polling Mode</b>:
 * <pre>
 * FluentBit → PostgreSQL event tables → processBatch() → workflow_instances
 * </pre>
 *
 * <p><b>Kafka Mode</b>:
 * <pre>
 * FluentBit → Kafka → processEvent() → workflow_instances
 * </pre>
 *
 * <p><b>Pattern</b>: Materialized View (CQRS read model)
 *
 * <p><b>Out-of-Order Handling</b>: Events can arrive in any order. Merge logic:
 * <ul>
 *   <li>Fields from 'started' event: only set if currently NULL (COALESCE)
 *   <li>Fields from 'completed' event: always update (final state)
 *   <li>Fields from 'faulted' event: always update (final state)
 * </ul>
 */
@ApplicationScoped
public class PostgreSQLWorkflowInstanceEventProcessor
        implements PollingEventProcessor<WorkflowInstanceEvent>, KafkaEventProcessor<WorkflowInstanceEvent> {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLWorkflowInstanceEventProcessor.class);

    @Inject
    EntityManager entityManager;

    @Inject
    WorkflowInstanceEventRepository eventRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void processEvent(WorkflowInstanceEvent event) {
        // Start timing
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            log.debug("Processing single workflow event for instance {}", event.getInstanceId());

            // Find or create workflow instance
            WorkflowInstanceEntity instance = entityManager.find(WorkflowInstanceEntity.class, event.getInstanceId());
            if (instance == null) {
                instance = new WorkflowInstanceEntity();
                instance.setId(event.getInstanceId());
                log.debug("Created new workflow instance: {}", event.getInstanceId());
            }

            // Merge event
            mergeEvent(instance, event);

            // Persist/update instance
            entityManager.merge(instance);

            // Record success metrics
            meterRegistry.counter("event.processor.events.processed.total",
                    Tags.of("processor", "workflow", "status", "success", "mode", "kafka"))
                    .increment();

            log.debug("Processed workflow event for instance {}", event.getInstanceId());
        } catch (Exception e) {
            // Record error metrics
            meterRegistry.counter("event.processor.errors.total",
                    Tags.of("processor", "workflow", "type", e.getClass().getSimpleName(), "mode", "kafka"))
                    .increment();
            log.error("Error processing workflow event for instance {}", event.getInstanceId(), e);
            throw e;
        } finally {
            // Record processing duration
            sample.stop(meterRegistry.timer("event.processor.event.duration",
                    Tags.of("processor", "workflow", "mode", "kafka")));
        }
    }

    @Override
    @Transactional
    public int processBatch(int batchSize) {
        // Start timing batch processing
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Fetch batch of unprocessed events
            List<WorkflowInstanceEvent> events = eventRepository.findUnprocessedEvents(batchSize);

            if (events.isEmpty()) {
                return 0;
            }

            log.debug("Processing {} workflow instance events", events.size());

            // Group events by instance ID (handle multiple events per instance)
            Map<String, List<WorkflowInstanceEvent>> eventsByInstance = events.stream()
                    .collect(Collectors.groupingBy(WorkflowInstanceEvent::getInstanceId));

            // Merge each instance's events
            for (Map.Entry<String, List<WorkflowInstanceEvent>> entry : eventsByInstance.entrySet()) {
                String instanceId = entry.getKey();
                List<WorkflowInstanceEvent> instanceEvents = entry.getValue();

                log.debug("Merging {} events for instance {}", instanceEvents.size(), instanceId);

                // Find or create workflow instance
                WorkflowInstanceEntity instance = entityManager.find(WorkflowInstanceEntity.class, instanceId);
                if (instance == null) {
                    instance = new WorkflowInstanceEntity();
                    instance.setId(instanceId);
                    log.debug("Created new workflow instance: {}", instanceId);
                }

                // Merge all events for this instance
                for (WorkflowInstanceEvent event : instanceEvents) {
                    mergeEvent(instance, event);
                }

                // Persist/update instance
                entityManager.merge(instance);
            }

            // Mark events as processed
            eventRepository.markAsProcessed(events);

            log.info("Processed {} workflow instance events ({} instances)",
                    events.size(), eventsByInstance.size());

            // Record success metrics
            meterRegistry.counter("event.processor.events.processed.total",
                    Tags.of("processor", "workflow", "status", "success", "mode", "polling"))
                    .increment(events.size());

            meterRegistry.gauge("event.processor.batch.size",
                    Tags.of("processor", "workflow", "mode", "polling"), events.size());

            return events.size();
        } catch (Exception e) {
            // Record error metrics
            meterRegistry.counter("event.processor.errors.total",
                    Tags.of("processor", "workflow", "type", e.getClass().getSimpleName(), "mode", "polling"))
                    .increment();
            throw e;
        } finally {
            // Record batch processing duration
            sample.stop(meterRegistry.timer("event.processor.batch.duration",
                    Tags.of("processor", "workflow", "mode", "polling")));
        }
    }

    @Override
    public String getProcessorName() {
        return "workflow";
    }

    @Override
    public long getBacklog() {
        return eventRepository.countUnprocessed();
    }

    @Override
    public long getOldestUnprocessedAgeSeconds() {
        Instant oldest = eventRepository.findOldestUnprocessedEventTime();
        if (oldest == null) {
            return 0;
        }
        return Instant.now().getEpochSecond() - oldest.getEpochSecond();
    }

    /**
     * Merge event into workflow instance.
     *
     * <p><b>Out-of-Order Handling</b>:
     * <ul>
     *   <li>Fields from 'started' → only set if NULL (completed may arrive first)
     *   <li>Fields from 'completed' → always update (final state)
     *   <li>Fields from 'faulted' → always update (final state)
     * </ul>
     *
     * @param instance workflow instance entity to update
     * @param event    event to merge
     */
    private void mergeEvent(WorkflowInstanceEntity instance, WorkflowInstanceEvent event) {
        String eventType = event.getEventType();

        // Fields from 'started' event (only set if NULL - COALESCE logic)
        if ("started".equals(eventType)) {
            if (event.getWorkflowNamespace() != null && instance.getNamespace() == null) {
                instance.setNamespace(event.getWorkflowNamespace());
            }
            if (event.getWorkflowName() != null && instance.getName() == null) {
                instance.setName(event.getWorkflowName());
            }
            if (event.getWorkflowVersion() != null && instance.getVersion() == null) {
                instance.setVersion(event.getWorkflowVersion());
            }
            if (event.getStartTime() != null && instance.getStart() == null) {
                instance.setStart(event.getStartTime());
            }
            if (event.getInputData() != null && instance.getInput() == null) {
                instance.setInput(event.getInputData());
            }
        }

        // Fields from 'completed' event (always update - final state)
        if ("completed".equals(eventType)) {
            if (event.getEndTime() != null) {
                instance.setEnd(event.getEndTime());
            }
            if (event.getOutputData() != null) {
                instance.setOutput(event.getOutputData());
            }
        }

        // Fields from 'faulted' event (always update - final state)
        if ("faulted".equals(eventType)) {
            if (event.getEndTime() != null) {
                instance.setEnd(event.getEndTime());
            }
            if (event.getErrorType() != null) {
                WorkflowInstanceErrorEntity error = new WorkflowInstanceErrorEntity();
                error.setType(event.getErrorType());
                error.setTitle(event.getErrorTitle());
                error.setDetail(event.getErrorDetail());
                error.setStatus(event.getErrorStatus());
                instance.setError(error);
            }
        }

        // Status (COALESCE logic - preserve terminal states)
        // Don't overwrite terminal states (COMPLETED, FAULTED, CANCELLED) with non-terminal states
        if (event.getStatus() != null) {
            try {
                WorkflowInstanceStatus newStatus = WorkflowInstanceStatus.valueOf(event.getStatus());
                WorkflowInstanceStatus currentStatus = instance.getStatus();

                boolean isNewStatusTerminal = newStatus == WorkflowInstanceStatus.COMPLETED
                        || newStatus == WorkflowInstanceStatus.FAULTED
                        || newStatus == WorkflowInstanceStatus.CANCELLED;
                boolean isCurrentStatusTerminal = currentStatus == WorkflowInstanceStatus.COMPLETED
                        || currentStatus == WorkflowInstanceStatus.FAULTED
                        || currentStatus == WorkflowInstanceStatus.CANCELLED;

                // Update status unless we're trying to overwrite a terminal state with a non-terminal state
                if (currentStatus == null || !isCurrentStatusTerminal || isNewStatusTerminal) {
                    instance.setStatus(newStatus);
                } else {
                    log.debug("Preserving terminal status {} over non-terminal {}", currentStatus, newStatus);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid workflow status: {}, skipping", event.getStatus());
            }
        }

        // Last update timestamp
        instance.setLastUpdate(event.getEventTime());
    }
}
