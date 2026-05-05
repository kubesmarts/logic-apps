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
package org.kubesmarts.logic.dataindex.api;

import java.util.List;

import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kie.kogito.persistence.api.Storage;

/**
 * Storage interface for TaskExecution domain model.
 *
 * <p>Provides CRUD operations for task executions.
 * Extends Storage&lt;String, TaskExecution&gt; where key is task execution ID.
 *
 * <p>Data Index v1.0.0 is read-only, so implementations should focus on query operations.
 */
public interface TaskExecutionStorage extends Storage<String, TaskExecution> {
    // Inherits:
    // - TaskExecution get(String id)
    // - boolean containsKey(String id)
    // - Storage query operations via StorageQuery interface

    /**
     * Find all task executions for a specific workflow instance.
     *
     * @param workflowInstanceId Workflow instance ID
     * @return List of task executions for this workflow instance, empty list if none found
     */
    List<TaskExecution> findByWorkflowInstanceId(String workflowInstanceId);
}
