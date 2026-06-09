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
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence.WorkflowPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.Objects;

@Unremovable
@ApplicationScoped
public class WorkflowEventProcessor implements EventProcessor<WorkflowInstance> {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventProcessor.class);

    final WorkflowPersistence workflowPersistence;

    @Inject
    public WorkflowEventProcessor(WorkflowPersistence workflowPersistence) {
        this.workflowPersistence = workflowPersistence;
    }

    public void process(final WorkflowInstance event) {
        try {
            this.workflowPersistence.persist(Objects.requireNonNull(event, "event cannot be null"));
            log.debug("Successfully processed the workflow event with ID: {}", event.getId());
        } catch (SQLException e) {
            log.error("Error while processing the workflow event: {}", event, e);
            throw new ProcessEventFailedException("Failed to process the workflow event with instance ID: " + event.getId(), e);
        }
    }
}

