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
 * Kafka mode event processor interface.
 *
 * <p><b>Mode</b>: Kafka + PostgreSQL
 *
 * <p><b>Pattern</b>: Event-driven real-time processing
 *
 * <p><b>Flow</b>:
 * <pre>
 * Quarkus Flow → Logs → FluentBit → Kafka → KafkaEventProcessor → Normalized tables
 * </pre>
 *
 * <p><b>Design</b>:
 * <ul>
 *   <li><b>No event tables</b>: Events come directly from Kafka
 *   <li>Normalized tables: workflow_instances, task_executions
 *   <li>Processor receives events from Kafka, writes directly to normalized tables
 * </ul>
 *
 * <p><b>Key difference from polling mode</b>: Events are consumed in real-time from Kafka,
 * not polled from database tables. No intermediate event table storage.
 *
 * <p><b>Usage</b>: Called by {@code KafkaEventConsumer} when a message arrives from Kafka.
 *
 * @param <E> Domain event type (e.g., WorkflowInstanceEvent, TaskExecutionEvent from Kafka)
 */
public interface KafkaEventProcessor<E> {

    /**
     * Process a single event from Kafka.
     *
     * <p>Implementations should:
     * <ol>
     *   <li>Receive event from Kafka consumer
     *   <li>Find or create entity in normalized table
     *   <li>Merge event data into entity (using COALESCE logic for out-of-order)
     *   <li>Persist entity to database
     *   <li>Record metrics (duration, count, errors)
     * </ol>
     *
     * <p><b>Example</b>:
     * <pre>
     * WorkflowInstanceEntity instance = entityManager.find(event.getInstanceId());
     * if (instance == null) {
     *     instance = new WorkflowInstanceEntity();
     *     instance.setId(event.getInstanceId());
     * }
     * merge(instance, event);
     * entityManager.merge(instance);
     * </pre>
     *
     * <p><b>Out-of-order handling</b>: Same COALESCE logic as polling mode, but applied
     * immediately as events arrive rather than in batches.
     *
     * <p><b>Transaction</b>: This method should be @Transactional. Kafka offset is committed
     * only after successful transaction commit.
     *
     * @param event Event from Kafka to process
     */
    void processEvent(E event);

    /**
     * Get the processor name for metrics/logging.
     *
     * <p><b>Examples</b>: "workflow", "task"
     *
     * @return Processor name
     */
    String getProcessorName();
}
