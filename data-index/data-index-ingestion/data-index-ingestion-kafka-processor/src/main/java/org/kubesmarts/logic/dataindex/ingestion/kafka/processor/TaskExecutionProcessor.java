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
package org.kubesmarts.logic.dataindex.ingestion.kafka.processor;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence.TaskPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Objects;

@Unremovable
@ApplicationScoped
public class TaskExecutionProcessor implements EventProcessor<TaskExecution> {

    private static final Logger log = LoggerFactory.getLogger(TaskExecutionProcessor.class);

    final TaskPersistence taskPersistence;

    @Inject
    public TaskExecutionProcessor(TaskPersistence taskPersistence) {
        this.taskPersistence = taskPersistence;
    }

    @Override
    public void process(TaskExecution event) {
        Objects.requireNonNull(event, "event cannot be null");
        log.debug("Processing task: {}", event);
        event.setId(generateTaskExecutionId(event));
        try {
            this.taskPersistence.persist(event);
            log.debug("Successfully processed the task event with ID: {}", event.getInstanceId());
        } catch (SQLException e) {
            log.error("Error while processing the task event: {}", event, e);
            throw new ProcessEventFailedException("Failed to process the task event with instance ID: " + event.getInstanceId(), e);
        }
    }

    private String generateTaskExecutionId(TaskExecution taskExecutionEvent) {
        // Generate deterministic ID based on instance's ID + task position
        return taskExecutionEvent.getInstanceId() +
                ":" + taskExecutionEvent.getTaskPosition();
    }
}

