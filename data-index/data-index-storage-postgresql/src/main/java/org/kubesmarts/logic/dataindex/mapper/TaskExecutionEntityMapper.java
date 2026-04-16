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
package org.kubesmarts.logic.dataindex.mapper;

import org.kubesmarts.logic.dataindex.jpa.TaskExecutionEntity;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * MapStruct mapper for TaskExecution domain model and TaskExecutionEntity JPA entity.
 *
 * <p>Maps between:
 * <ul>
 *   <li>TaskExecution (domain model) - used by GraphQL API
 *   <li>TaskExecutionEntity (JPA entity) - persisted in PostgreSQL
 * </ul>
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface TaskExecutionEntityMapper {

    /**
     * Convert JPA entity to domain model.
     * Used when reading from database to return via GraphQL API.
     */
    @Mapping(target = "enter", source = "enter")
    @Mapping(target = "exit", source = "exit")
    TaskExecution toModel(TaskExecutionEntity entity);

    /**
     * Convert domain model to JPA entity.
     * Used when writing to database (though Data Index v1.0.0 is read-only, this may be used for tests).
     */
    @Mapping(target = "workflowInstance", ignore = true) // Will be set by relationship
    TaskExecutionEntity toEntity(TaskExecution model);

    /**
     * Update existing entity from model.
     * Useful for merge operations.
     */
    @Mapping(target = "workflowInstance", ignore = true)
    void updateEntityFromModel(TaskExecution model, @MappingTarget TaskExecutionEntity entity);
}
