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
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for TaskExecution domain model and TaskInstanceEntity JPA entity.
 *
 * <p>Maps between:
 * <ul>
 *   <li>TaskExecution (domain model) - used by GraphQL API
 *   <li>TaskInstanceEntity (JPA entity) - persisted in PostgreSQL via triggers
 * </ul>
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface TaskInstanceEntityMapper {

    /**
     * Convert JPA entity to domain model.
     * Used when reading from database to return via GraphQL API.
     */
    @Mapping(target = "id", source = "taskExecutionId")
    @Mapping(target = "start", source = "start")
    @Mapping(target = "end", source = "end")
    @Mapping(target = "input", source = "input")
    @Mapping(target = "output", source = "output")
    TaskExecution toModel(TaskInstanceEntity entity);

    /**
     * Convert domain model to JPA entity.
     * Used when writing to database (though Data Index v1.0.0 is read-only, this may be used for tests).
     */
    @Mapping(target = "taskExecutionId", source = "id")
    @Mapping(target = "instanceId", ignore = true) // Will be set from relationship
    @Mapping(target = "workflowInstance", ignore = true) // Will be set by relationship
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    TaskInstanceEntity toEntity(TaskExecution model);

    /**
     * Update existing entity from model.
     * Useful for merge operations.
     */
    @Mapping(target = "taskExecutionId", ignore = true) // Primary key, don't update
    @Mapping(target = "instanceId", ignore = true)
    @Mapping(target = "workflowInstance", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    void updateEntityFromModel(TaskExecution model, @MappingTarget TaskInstanceEntity entity);
}
