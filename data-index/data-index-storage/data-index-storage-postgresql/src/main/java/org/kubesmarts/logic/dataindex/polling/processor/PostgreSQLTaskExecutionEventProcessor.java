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
package org.kubesmarts.logic.dataindex.polling.processor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import org.kubesmarts.logic.dataindex.api.KafkaEventProcessor;
import org.kubesmarts.logic.dataindex.api.PollingEventProcessor;
import org.kubesmarts.logic.dataindex.storage.entity.TaskExecutionEntity;
import org.kubesmarts.logic.dataindex.storage.entity.WorkflowInstanceEntity;
import org.kubesmarts.logic.dataindex.polling.event.TaskExecutionEvent;
import org.kubesmarts.logic.dataindex.polling.repository.TaskExecutionEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/**
 * PostgreSQL-specific processor for task execution events.
 *
 * <p><b>Dual Mode Support</b>: Implements both {@link PollingEventProcessor} and {@link KafkaEventProcessor}
 *
 * <p><b>Polling Mode</b>:
 * <pre>
 * FluentBit → PostgreSQL event tables → processBatch() → task_executions
 * </pre>
 *
 * <p><b>Kafka Mode</b>:
 * <pre>
 * FluentBit → Kafka → processEvent() → task_executions
 * </pre>
 *
 * <p><b>CRITICAL</b>: Tasks are merged by taskPosition, NOT taskExecutionId!
 * The Quarkus Flow SDK generates different taskExecutionId for started vs completed events.
 *
 * <p><b>Task Position Examples</b>:
 * <ul>
 *   <li>"do/0" - First task in do sequence
 *   <li>"do/1" - Second task in do sequence
 *   <li>"fork/branches/0/do/0" - First task in first fork branch
 * </ul>
 */
@ApplicationScoped
public class PostgreSQLTaskExecutionEventProcessor
        implements PollingEventProcessor<TaskExecutionEvent>, KafkaEventProcessor<TaskExecutionEvent> {

    private static final Logger log = LoggerFactory.getLogger(PostgreSQLTaskExecutionEventProcessor.class);

    @Inject
    EntityManager entityManager;

    @Inject
    TaskExecutionEventRepository eventRepository;

    @Inject
    MeterRegistry meterRegistry;

    @Override
    @Transactional
    public void processEvent(TaskExecutionEvent event) {
        // Start timing
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            log.debug("Processing single task event for instance {} at position {}",
                    event.getInstanceId(), event.getTaskPosition());

            String instanceId = event.getInstanceId();
            String taskPosition = event.getTaskPosition();

            // Find or create task execution (by position!)
            TaskExecutionEntity task = findTaskByPosition(instanceId, taskPosition);
            if (task == null) {
                task = new TaskExecutionEntity();
                task.setId(event.getTaskExecutionId());
                task.setTaskPosition(taskPosition);

                // Set parent relationship
                WorkflowInstanceEntity instance = entityManager.find(WorkflowInstanceEntity.class, instanceId);
                if (instance != null) {
                    task.setWorkflowInstance(instance);
                } else {
                    log.warn("Workflow instance {} not found for task {}, skipping",
                            instanceId, taskPosition);
                    return; // Skip this event
                }

                log.debug("Created new task execution: {} at position {}", task.getId(), taskPosition);
            }

            // Merge event
            mergeEvent(task, event);

            // Persist/update task
            entityManager.merge(task);

            // Record success metrics
            meterRegistry.counter("event.processor.events.processed.total",
                    Tags.of("processor", "task", "status", "success", "mode", "kafka"))
                    .increment();

            log.debug("Processed task event for instance {} at position {}", instanceId, taskPosition);
        } catch (Exception e) {
            // Record error metrics
            meterRegistry.counter("event.processor.errors.total",
                    Tags.of("processor", "task", "type", e.getClass().getSimpleName(), "mode", "kafka"))
                    .increment();
            log.error("Error processing task event for instance {} at position {}",
                    event.getInstanceId(), event.getTaskPosition(), e);
            throw e;
        } finally {
            // Record processing duration
            sample.stop(meterRegistry.timer("event.processor.event.duration",
                    Tags.of("processor", "task", "mode", "kafka")));
        }
    }

    @Override
    @Transactional
    public int processBatch(int batchSize) {
        // Start timing batch processing
        Timer.Sample sample = Timer.start(meterRegistry);

        try {
            // Fetch batch of unprocessed events
            List<TaskExecutionEvent> events = eventRepository.findUnprocessedEvents(batchSize);

            if (events.isEmpty()) {
                return 0;
            }

            log.debug("Processing {} task execution events", events.size());

            // Group by (instanceId, taskPosition) - CRITICAL for correct merging!
            Map<String, List<TaskExecutionEvent>> eventsByTask = events.stream()
                    .collect(Collectors.groupingBy(e -> e.getInstanceId() + ":" + e.getTaskPosition()));

            // Merge each task's events
            for (Map.Entry<String, List<TaskExecutionEvent>> entry : eventsByTask.entrySet()) {
                List<TaskExecutionEvent> taskEvents = entry.getValue();

                String instanceId = taskEvents.get(0).getInstanceId();
                String taskPosition = taskEvents.get(0).getTaskPosition();

                log.debug("Merging {} events for task {} at position {}",
                        taskEvents.size(), taskEvents.get(0).getTaskName(), taskPosition);

                // Find or create task execution (by position!)
                TaskExecutionEntity task = findTaskByPosition(instanceId, taskPosition);
                if (task == null) {
                    task = new TaskExecutionEntity();
                    task.setId(taskEvents.get(0).getTaskExecutionId()); // Use first seen ID
                    task.setTaskPosition(taskPosition);

                    // Set parent relationship (CRITICAL for CASCADE persistence)
                    WorkflowInstanceEntity instance = entityManager.find(WorkflowInstanceEntity.class, instanceId);
                    if (instance != null) {
                        task.setWorkflowInstance(instance);
                    } else {
                        log.warn("Workflow instance {} not found for task {}, skipping",
                                instanceId, taskPosition);
                        continue; // Skip this task - parent doesn't exist yet
                    }

                    log.debug("Created new task execution: {} at position {}", task.getId(), taskPosition);
                }

                // Merge all events for this task
                for (TaskExecutionEvent event : taskEvents) {
                    mergeEvent(task, event);
                }

                // Persist/update task
                entityManager.merge(task);
            }

            // Mark events as processed
            eventRepository.markAsProcessed(events);

            log.info("Processed {} task execution events ({} tasks)",
                    events.size(), eventsByTask.size());

            // Record success metrics
            meterRegistry.counter("event.processor.events.processed.total",
                    Tags.of("processor", "task", "status", "success", "mode", "polling"))
                    .increment(events.size());

            meterRegistry.gauge("event.processor.batch.size",
                    Tags.of("processor", "task", "mode", "polling"), events.size());

            return events.size();
        } catch (Exception e) {
            // Record error metrics
            meterRegistry.counter("event.processor.errors.total",
                    Tags.of("processor", "task", "type", e.getClass().getSimpleName(), "mode", "polling"))
                    .increment();
            throw e;
        } finally {
            // Record batch processing duration
            sample.stop(meterRegistry.timer("event.processor.batch.duration",
                    Tags.of("processor", "task", "mode", "polling")));
        }
    }

    @Override
    public String getProcessorName() {
        return "task";
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
     * Find task execution by position (NOT by execution ID).
     *
     * @param instanceId   workflow instance ID
     * @param taskPosition task position (JSONPointer)
     * @return task entity or null if not found
     */
    private TaskExecutionEntity findTaskByPosition(String instanceId, String taskPosition) {
        try {
            return entityManager
                    .createQuery(
                            "SELECT t FROM TaskExecutionEntity t " +
                                    "WHERE t.workflowInstance.id = :instanceId " +
                                    "AND t.taskPosition = :position",
                            TaskExecutionEntity.class)
                    .setParameter("instanceId", instanceId)
                    .setParameter("position", taskPosition)
                    .getSingleResult();
        } catch (NoResultException e) {
            return null;
        }
    }

    /**
     * Merge event into task execution.
     *
     * @param task  task execution entity to update
     * @param event event to merge
     */
    private void mergeEvent(TaskExecutionEntity task, TaskExecutionEvent event) {
        String eventType = event.getEventType();

        // Fields from 'started' event (only set if NULL)
        if ("started".equals(eventType)) {
            if (event.getTaskName() != null && task.getTaskName() == null) {
                task.setTaskName(event.getTaskName());
            }
            if (event.getStartTime() != null && task.getEnter() == null) {
                task.setEnter(event.getStartTime());
            }
            if (event.getInputArgs() != null && task.getInputArgs() == null) {
                task.setInputArgs(event.getInputArgs());
            }
        }

        // Fields from 'completed' event (always update)
        if ("completed".equals(eventType)) {
            if (event.getEndTime() != null) {
                task.setExit(event.getEndTime());
            }
            // Set position/name if not already set (defensive)
            if (event.getTaskPosition() != null && task.getTaskPosition() == null) {
                task.setTaskPosition(event.getTaskPosition());
            }
            if (event.getTaskName() != null && task.getTaskName() == null) {
                task.setTaskName(event.getTaskName());
            }
            if (event.getOutputArgs() != null) {
                task.setOutputArgs(event.getOutputArgs());
            }
        }

        // Fields from 'faulted' event (always update)
        if ("faulted".equals(eventType)) {
            if (event.getEndTime() != null) {
                task.setExit(event.getEndTime());
            }
            if (event.getErrorMessage() != null) {
                task.setErrorMessage(event.getErrorMessage());
            }
        }
    }
}
