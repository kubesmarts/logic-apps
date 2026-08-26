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

import org.kubesmarts.logic.dataindex.storage.entity.ErrorEntity;
import org.kubesmarts.logic.dataindex.model.Error;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;

/**
 * Maps between Error (domain model) and ErrorEntity (JPA entity).
 * <p>Used by both WorkflowInstanceEntityMapper and TaskInstanceEntityMapper.
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface ErrorEntityMapper {

    /**
     * Convert JPA embeddable to domain model.
     */
    Error toModel(ErrorEntity entity);

    /**
     * Convert domain model to JPA embeddable.
     */
    ErrorEntity toEntity(Error model);
}
