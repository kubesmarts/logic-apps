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
package org.kubesmarts.logic.dataindex.processor.config;

import java.time.Duration;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

/**
 * Event Processor configuration.
 *
 * <p>Configuration prefix: {@code data-index.event-processor}
 *
 * <p><b>Three Deployment Modes:</b>
 *
 * <p><b>Mode 1: Polling + PostgreSQL (Simple)</b>
 * <pre>
 * Quarkus Flow → Logs → FluentBit → PostgreSQL event tables
 *                                          ↓ (polling every 5s)
 *                                    Event Processor
 *                                          ↓
 *                                  Normalized tables → Data Index GraphQL
 * </pre>
 * <ul>
 *   <li>Use case: &lt; 10K workflows/day, simple deployments</li>
 *   <li>Event tables: workflow_instance_events, task_execution_events</li>
 *   <li>Normalized tables: workflow_instances, task_executions</li>
 *   <li>Event processor: Polls event tables, merges into normalized tables</li>
 * </ul>
 *
 * <p><b>Mode 2: Elasticsearch Only (Search/Analytics)</b>
 * <pre>
 * Quarkus Flow → Logs → FluentBit → ES raw event indices
 *                                          ↓ (ES Transform, automatic, ~1s)
 *                                    ES normalized indices → Data Index GraphQL
 * </pre>
 * <ul>
 *   <li>Use case: Full-text search, analytics, high read throughput</li>
 *   <li>No event processor code on our side: ES Transform handles event processing</li>
 *   <li>Raw event indices: workflow-events, task-events (from FluentBit)</li>
 *   <li>Normalized indices: workflow-instances, task-executions (created by ES Transform)</li>
 *   <li>ES Transform: Handles out-of-order events, task correlation, COALESCE logic via Painless scripts</li>
 *   <li>Latency: ~1s (ES Transform frequency)</li>
 * </ul>
 *
 * <p><b>Mode 3: Kafka + PostgreSQL (Scale)</b>
 * <pre>
 * Quarkus Flow → Logs → FluentBit → Kafka → Event Processor → Normalized tables → Data Index GraphQL
 * </pre>
 * <ul>
 *   <li>Use case: &gt; 10K workflows/day, real-time, need relational/ACID</li>
 *   <li>No event tables: Event processor writes directly to workflow_instances, task_executions</li>
 *   <li>Event processor: Consumes from Kafka, writes to normalized tables in real-time</li>
 * </ul>
 *
 * <p><b>Configuration Examples:</b>
 *
 * <p><b>Mode 1: Polling + PostgreSQL</b>
 * <pre>
 * data-index.event-processor.mode=polling
 * data-index.event-processor.enabled=true
 * data-index.event-processor.interval=5s
 * data-index.event-processor.batch-size=100
 * data-index.storage.backend=postgresql
 * </pre>
 *
 * <p><b>Mode 2: Elasticsearch Only</b>
 * <pre>
 * # No event processor code on our side - ES Transform handles it
 * data-index.event-processor.enabled=false
 * data-index.storage.backend=elasticsearch
 * quarkus.elasticsearch.hosts=localhost:9200
 *
 * # ES Transform automatically processes:
 * # workflow-events → workflow-instances (normalized)
 * # task-events → task-executions (normalized)
 * </pre>
 *
 * <p><b>Mode 3: Kafka + PostgreSQL</b>
 * <pre>
 * data-index.event-processor.mode=kafka
 * data-index.storage.backend=postgresql
 * kafka.bootstrap.servers=localhost:9092
 * </pre>
 *
 * @see Mode
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "data-index.event-processor")
public interface EventProcessorConfiguration {

    /**
     * Processing mode.
     *
     * <p><b>Values:</b>
     * <ul>
     *   <li>{@code POLLING} - EventProcessorScheduler polls PostgreSQL event tables
     *   <li>{@code KAFKA} - KafkaEventConsumer consumes events from Kafka topics in real-time
     * </ul>
     *
     * <p><b>Default:</b> {@code POLLING}
     *
     * @return processing mode
     */
    @WithDefault("polling")
    Mode mode();

    /**
     * Enable event processing.
     *
     * <p>When {@code false}, the event processor is disabled completely.
     *
     * <p><b>Default:</b> {@code true}
     *
     * @return true if event processing is enabled
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Polling interval for batch processing.
     *
     * <p>Only applies when {@link #mode()} is {@link Mode#POLLING}.
     *
     * <p><b>Default:</b> {@code 5s}
     *
     * @return polling interval
     */
    @WithDefault("5s")
    Duration interval();

    /**
     * Maximum number of events to process in a single batch.
     *
     * <p>Only applies when {@link #mode()} is {@link Mode#POLLING}.
     *
     * <p><b>Default:</b> {@code 100}
     *
     * @return batch size
     */
    @WithName("batch-size")
    @WithDefault("100")
    int batchSize();

    /**
     * Retention period for processed events (in days).
     *
     * <p>Events older than this will be deleted by the cleanup job.
     *
     * <p><b>Default:</b> {@code 30}
     *
     * @return retention period in days
     */
    @WithName("retention-days")
    @WithDefault("30")
    int retentionDays();

    /**
     * Lag threshold configuration.
     *
     * @return lag threshold configuration
     */
    LagConfiguration lag();

    /**
     * Backlog threshold configuration.
     *
     * @return backlog threshold configuration
     */
    BacklogConfiguration backlog();

    /**
     * Slow processing threshold configuration.
     *
     * @return slow processing threshold configuration
     */
    @WithName("slow-processing")
    SlowProcessingConfiguration slowProcessing();

    /**
     * Processing mode enumeration.
     */
    enum Mode {
        /**
         * Polling mode: EventProcessorScheduler polls PostgreSQL event tables.
         *
         * <p><b>Use case:</b> Simple deployments, &lt; 10K workflows/day, PostgreSQL only
         */
        POLLING,

        /**
         * Kafka mode: KafkaEventConsumer consumes events from Kafka topics in real-time.
         *
         * <p><b>Use case:</b> Scale, &gt; 10K workflows/day, works with PostgreSQL or Elasticsearch
         */
        KAFKA
    }

    /**
     * Lag threshold configuration.
     *
     * <p>Configuration prefix: {@code data-index.event-processor.lag.threshold}
     */
    interface LagConfiguration {

        /**
         * Maximum acceptable lag in seconds.
         *
         * <p>Health check will fail if oldest unprocessed event is older than this.
         *
         * <p><b>Default:</b> {@code 60}
         *
         * @return threshold in seconds
         */
        @WithName("threshold.seconds")
        @WithDefault("60")
        long seconds();
    }

    /**
     * Backlog threshold configuration.
     *
     * <p>Configuration prefix: {@code data-index.event-processor.backlog}
     */
    interface BacklogConfiguration {

        /**
         * Maximum acceptable backlog count.
         *
         * <p>Health check will fail if number of unprocessed events exceeds this.
         *
         * <p><b>Default:</b> {@code 1000}
         *
         * @return threshold count
         */
        @WithDefault("1000")
        long threshold();
    }

    /**
     * Slow processing threshold configuration.
     *
     * <p>Configuration prefix: {@code data-index.event-processor.slow-processing}
     */
    interface SlowProcessingConfiguration {

        /**
         * Slow processing threshold in milliseconds.
         *
         * <p>Processing times exceeding this will be logged as warnings.
         *
         * <p><b>Default:</b> {@code 1000}
         *
         * @return threshold in milliseconds
         */
        @WithName("threshold.ms")
        @WithDefault("1000")
        long ms();
    }
}
