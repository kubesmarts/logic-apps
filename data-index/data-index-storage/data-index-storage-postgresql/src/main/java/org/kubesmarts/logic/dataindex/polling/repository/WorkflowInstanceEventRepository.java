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
package org.kubesmarts.logic.dataindex.polling.repository;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import org.kubesmarts.logic.dataindex.api.EventRepository;
import org.kubesmarts.logic.dataindex.polling.event.WorkflowInstanceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL repository for workflow instance events.
 *
 * <p>Provides CRUD operations on workflow_instance_events table using JPA.
 */
@ApplicationScoped
public class WorkflowInstanceEventRepository implements EventRepository<WorkflowInstanceEvent> {

    private static final Logger log = LoggerFactory.getLogger(WorkflowInstanceEventRepository.class);

    @Inject
    EntityManager entityManager;

    @Override
    public List<WorkflowInstanceEvent> findUnprocessedEvents(int limit) {
        return entityManager
                .createQuery(
                        "SELECT e FROM WorkflowInstanceEvent e " +
                                "WHERE e.processed = false " +
                                "ORDER BY e.eventTime ASC",
                        WorkflowInstanceEvent.class)
                .setMaxResults(limit)
                .getResultList();
    }

    @Override
    public void markAsProcessed(List<WorkflowInstanceEvent> events) {
        ZonedDateTime now = ZonedDateTime.now();
        for (WorkflowInstanceEvent event : events) {
            event.setProcessed(true);
            event.setProcessedAt(now);
        }
        // EntityManager tracks changes automatically (no need to call merge for each)
    }

    @Override
    public long countUnprocessed() {
        try {
            return (Long) entityManager.createQuery(
                    "SELECT COUNT(e) FROM WorkflowInstanceEvent e WHERE e.processed = false")
                    .getSingleResult();
        } catch (Exception e) {
            log.debug("Error counting unprocessed workflow events", e);
            return 0;
        }
    }

    @Override
    public Instant findOldestUnprocessedEventTime() {
        try {
            ZonedDateTime result = (ZonedDateTime) entityManager.createQuery(
                    "SELECT MIN(e.eventTime) FROM WorkflowInstanceEvent e WHERE e.processed = false")
                    .getSingleResult();
            return result != null ? result.toInstant() : null;
        } catch (Exception e) {
            log.debug("No unprocessed workflow events found", e);
            return null;
        }
    }

    @Override
    public int deleteOlderThan(Instant cutoffTime) {
        try {
            ZonedDateTime cutoff = ZonedDateTime.ofInstant(cutoffTime, java.time.ZoneId.systemDefault());
            return entityManager
                    .createQuery(
                            "DELETE FROM WorkflowInstanceEvent e " +
                                    "WHERE e.processed = true " +
                                    "AND e.processedAt < :cutoff")
                    .setParameter("cutoff", cutoff)
                    .executeUpdate();
        } catch (Exception e) {
            log.error("Error deleting old workflow events", e);
            return 0;
        }
    }
}
