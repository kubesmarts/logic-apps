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
package org.kubesmarts.logic.dataindex.storage.jpa;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.kubesmarts.logic.dataindex.api.TaskExecutionStorage;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.TaskInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.jpa.mapper.TaskInstanceEntityMapper;
import org.kubesmarts.logic.dataindex.model.TaskExecution;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA storage implementation for TaskExecution domain model.
 *
 * <p>Uses:
 * <ul>
 *   <li>TaskInstanceEntity - JPA entity for persistence (maps to task_instances table)
 *   <li>TaskInstanceEntityMapper - MapStruct mapper for entity/model conversion
 *   <li>AbstractStorage - Base JPA storage with query support
 * </ul>
 */
@ApplicationScoped
public class TaskExecutionJPAStorage extends AbstractStorage<String, TaskInstanceEntity, TaskExecution>
        implements TaskExecutionStorage {

    @Inject
    public TaskExecutionJPAStorage(
            EntityManager em,
            TaskInstanceEntityMapper mapper,
            Instance<JsonPredicateBuilder> jsonPredicateBuilder) {
        super(
                em,
                TaskExecution.class,
                TaskInstanceEntity.class,
                mapper::toModel,
                mapper::toEntity,
                Optional.ofNullable(DependencyInjectionUtils.getInstance(jsonPredicateBuilder)));
    }

    // Default constructor for CDI proxying
    protected TaskExecutionJPAStorage() {
        super();
    }

    @Override
    public List<TaskExecution> findByWorkflowInstanceId(String workflowInstanceId) {
        List<TaskInstanceEntity> entities = em
                .createQuery("SELECT t FROM TaskInstanceEntity t WHERE t.instanceId = :instanceId", TaskInstanceEntity.class)
                .setParameter("instanceId", workflowInstanceId)
                .getResultList();

        return entities.stream()
                .map(mapToModel)
                .collect(Collectors.toList());
    }
}
