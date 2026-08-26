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
package org.kubesmarts.logic.dataindex.storage.mapper;

import org.kubesmarts.logic.dataindex.storage.entity.TaskInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.entity.WorkflowInstanceEntity;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.mapstruct.AfterMapping;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for WorkflowInstance domain model and WorkflowInstanceEntity JPA entity.
 *
 * <p>Maps between:
 * <ul>
 *   <li>WorkflowInstance (domain model) - used by GraphQL API
 *   <li>WorkflowInstanceEntity (JPA entity) - persisted in PostgreSQL
 * </ul>
 */
@Mapper(componentModel = "cdi",
        uses = { ErrorEntityMapper.class, TaskInstanceEntityMapper.class },
        injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface WorkflowInstanceEntityMapper {

    /**
     * Convert JPA entity to domain model.
     * Used when reading from database to return via GraphQL API.
     */
    WorkflowInstance toModel(WorkflowInstanceEntity entity);

    /**
     * Convert domain model to JPA entity.
     * Used when writing to database (though Data Index v1.0.0 is read-only, this may be used for tests).
     */
    @Mapping(source = "taskExecutions", target = "taskExecutions")
    WorkflowInstanceEntity toEntity(WorkflowInstance model);

    /**
     * Update existing entity from model.
     * Useful for merge operations.
     */
    @Mapping(source = "taskExecutions", target = "taskExecutions")
    void updateEntityFromModel(WorkflowInstance model, @MappingTarget WorkflowInstanceEntity entity);

    /**
     * Set bidirectional relationship after mapping.
     * TaskInstanceEntity.workflowInstance must reference the parent WorkflowInstanceEntity.
     */
    @AfterMapping
    default void setTaskWorkflowReferences(@MappingTarget WorkflowInstanceEntity entity) {
        if (entity.getTaskExecutions() != null) {
            for (TaskInstanceEntity task : entity.getTaskExecutions()) {
                task.setWorkflowInstance(entity);
            }
        }
    }
}
