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
package org.kubesmarts.logic.dataindex.storage;

import java.util.Optional;

import org.kubesmarts.logic.dataindex.storage.AbstractStorage;
import org.kubesmarts.logic.dataindex.storage.JsonPredicateBuilder;
import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.storage.entity.WorkflowInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.mapper.WorkflowInstanceEntityMapper;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA storage implementation for WorkflowInstance domain model.
 *
 * <p>Uses:
 * <ul>
 *   <li>WorkflowInstanceEntity - JPA entity for persistence
 *   <li>WorkflowInstanceEntityMapper - MapStruct mapper for entity/model conversion
 *   <li>AbstractStorage - Base JPA storage with query support
 * </ul>
 */
@ApplicationScoped
public class WorkflowInstanceJPAStorage extends AbstractStorage<String, WorkflowInstanceEntity, WorkflowInstance>
        implements WorkflowInstanceStorage {

    @Inject
    public WorkflowInstanceJPAStorage(
            EntityManager em,
            WorkflowInstanceEntityMapper mapper,
            Instance<JsonPredicateBuilder> jsonPredicateBuilder) {
        super(
                em,
                WorkflowInstance.class,
                WorkflowInstanceEntity.class,
                mapper::toModel,
                mapper::toEntity,
                WorkflowInstanceEntity::getId,
                Optional.ofNullable(DependencyInjectionUtils.getInstance(jsonPredicateBuilder)));
    }

    // Default constructor for CDI proxying
    protected WorkflowInstanceJPAStorage() {
        super();
    }
}
