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
package org.kubesmarts.logic.dataindex.graphql;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;
import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;

import jakarta.inject.Inject;

/**
 * GraphQL API for Serverless Workflow 1.0.0 execution data.
 *
 * <p>Provides queries for:
 * <ul>
 *   <li>Workflow instances (getWorkflowInstance, getWorkflowInstances)
 *   <li>Task executions (getTaskExecutions)
 * </ul>
 *
 * <p><b>Data Index v1.0.0 is read-only</b> - only query operations are supported.
 */
@GraphQLApi
public class WorkflowInstanceGraphQLApi {

    @Inject
    WorkflowInstanceStorage workflowInstanceStorage;

    /**
     * Get a single workflow instance by ID.
     *
     * @param id Workflow instance ID
     * @return WorkflowInstance or null if not found
     */
    @Query("getWorkflowInstance")
    @Description("Get a single workflow instance by ID. Returns null if not found.")
    public WorkflowInstance getWorkflowInstance(@Name("id") String id) {
        return workflowInstanceStorage.get(id);
    }

    /**
     * Get multiple workflow instances.
     *
     * <p>TODO: Implement filtering, sorting, pagination
     * Currently returns all instances (for initial testing with mocked data).
     *
     * @return List of all workflow instances
     */
    @Query("getWorkflowInstances")
    @Description("Get multiple workflow instances with optional filtering, sorting, and pagination.")
    public List<WorkflowInstance> getWorkflowInstances() {
        // TODO: Implement filter, orderBy, pagination
        // For now, return all instances using query API
        return new ArrayList<>(workflowInstanceStorage.query().execute());
    }

    /**
     * Get task executions for a workflow instance.
     *
     * @param workflowInstanceId Workflow instance ID
     * @return List of task executions
     */
    @Query("getTaskExecutions")
    @Description("Get task executions for a workflow instance.")
    public List<TaskExecution> getTaskExecutions(@Name("workflowInstanceId") String workflowInstanceId) {
        WorkflowInstance instance = workflowInstanceStorage.get(workflowInstanceId);
        if (instance == null) {
            return List.of();
        }
        return instance.getTaskExecutions() != null ? instance.getTaskExecutions() : List.of();
    }
}
