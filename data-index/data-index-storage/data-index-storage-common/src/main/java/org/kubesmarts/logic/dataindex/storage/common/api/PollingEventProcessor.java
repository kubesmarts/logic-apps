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
package org.kubesmarts.logic.dataindex.api;

/**
 * Polling mode event processor interface.
 *
 * <p><b>Mode</b>: Polling + PostgreSQL
 *
 * <p><b>Pattern</b>: Event tables as poor man's message queue
 *
 * <p><b>Flow</b>:
 * <pre>
 * Quarkus Flow → Logs → FluentBit → PostgreSQL event tables
 *                                          ↓ (polling every 5s)
 *                                    PollingEventProcessor
 *                                          ↓
 *                                  Normalized tables
 * </pre>
 *
 * <p><b>Design</b>:
 * <ul>
 *   <li>Event tables: workflow_instance_events, task_execution_events (append-only)
 *   <li>Normalized tables: workflow_instances, task_executions
 *   <li>Processor polls event tables, merges into normalized tables, marks as processed
 * </ul>
 *
 * <p><b>Usage</b>: Called by {@code EventProcessorScheduler} every N seconds.
 *
 * @param <E> Event table entity type (e.g., WorkflowInstanceEvent, TaskExecutionEvent)
 */
public interface PollingEventProcessor<E> {

    /**
     * Process a batch of unprocessed events from event tables.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Fetch unprocessed events (up to batchSize) from event table
     *   <li>Group events by entity ID (handle multiple events per instance)
     *   <li>Merge events into normalized table (using COALESCE logic for out-of-order)
     *   <li>Mark events as processed in event table
     *   <li>Record metrics (duration, count, errors)
     * </ol>
     *
     * <p><b>Example</b>:
     * <pre>
     * List&lt;WorkflowInstanceEvent&gt; events = eventRepository.findUnprocessedEvents(batchSize);
     * for (WorkflowInstanceEvent event : events) {
     *     WorkflowInstanceEntity instance = merge(event);
     *     entityManager.merge(instance);
     * }
     * eventRepository.markAsProcessed(events);
     * </pre>
     *
     * @param batchSize Maximum number of events to process in this batch
     * @return Number of events processed
     */
    int processBatch(int batchSize);

    /**
     * Get the processor name for metrics/logging.
     *
     * <p><b>Examples</b>: "workflow", "task"
     *
     * @return Processor name
     */
    String getProcessorName();

    /**
     * Get current backlog (number of unprocessed events in event table).
     *
     * <p>Used for health checks and monitoring.
     *
     * @return Number of unprocessed events waiting to be processed
     */
    long getBacklog();

    /**
     * Get age of oldest unprocessed event in seconds.
     *
     * <p>Used for lag monitoring and health checks.
     *
     * @return Age in seconds, or 0 if no unprocessed events
     */
    long getOldestUnprocessedAgeSeconds();
}
