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

import org.kubesmarts.logic.dataindex.entity.WorkflowInstanceErrorEntity;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceError;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;

/**
 * MapStruct mapper for WorkflowInstanceError domain model and WorkflowInstanceErrorEntity JPA embeddable.
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface WorkflowInstanceErrorEntityMapper {

    /**
     * Convert JPA embeddable to domain model.
     */
    WorkflowInstanceError toModel(WorkflowInstanceErrorEntity entity);

    /**
     * Convert domain model to JPA embeddable.
     */
    WorkflowInstanceErrorEntity toEntity(WorkflowInstanceError model);
}
