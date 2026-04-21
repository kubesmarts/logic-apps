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

import java.time.Instant;
import java.util.List;

/**
 * Backend-agnostic event repository interface.
 *
 * <p>Provides CRUD operations for event tables (PostgreSQL) or event indices (Elasticsearch).
 *
 * @param <E> Event type (e.g., WorkflowInstanceEvent, TaskExecutionEvent)
 */
public interface EventRepository<E> {

    /**
     * Find unprocessed events (up to limit).
     *
     * @param limit Maximum number of events to return
     * @return List of unprocessed events, ordered by event time (oldest first)
     */
    List<E> findUnprocessedEvents(int limit);

    /**
     * Mark events as processed.
     *
     * @param events Events to mark as processed
     */
    void markAsProcessed(List<E> events);

    /**
     * Count unprocessed events.
     *
     * @return Number of unprocessed events
     */
    long countUnprocessed();

    /**
     * Find oldest unprocessed event time.
     *
     * @return Event time of oldest unprocessed event, or null if none
     */
    Instant findOldestUnprocessedEventTime();

    /**
     * Delete events older than the given cutoff time.
     *
     * @param cutoffTime Delete events with event_time < cutoffTime
     * @return Number of events deleted
     */
    int deleteOlderThan(Instant cutoffTime);
}
