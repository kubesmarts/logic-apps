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
import org.kubesmarts.logic.dataindex.api.TaskExecutionStorage;
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
 *   <li>Task executions (getTaskExecution, getTaskExecutions, getTaskExecutionsByWorkflowInstance)
 * </ul>
 *
 * <p><b>Data Index v1.0.0 is read-only</b> - only query operations are supported.
 */
@GraphQLApi
public class WorkflowInstanceGraphQLApi {

    @Inject
    WorkflowInstanceStorage workflowInstanceStorage;

    @Inject
    TaskExecutionStorage taskExecutionStorage;

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
     * @param filter Optional filter criteria
     * @param orderBy Optional sort order
     * @param limit Maximum number of results
     * @param offset Number of results to skip
     * @return List of workflow instances matching criteria
     */
    @Query("getWorkflowInstances")
    @Description("Get multiple workflow instances with optional filtering, sorting, and pagination.")
    public List<WorkflowInstance> getWorkflowInstances(
            @Name("filter") org.kubesmarts.logic.dataindex.graphql.filter.WorkflowInstanceFilter filter,
            @Name("orderBy") org.kubesmarts.logic.dataindex.graphql.filter.WorkflowInstanceOrderBy orderBy,
            @Name("limit") Integer limit,
            @Name("offset") Integer offset) {

        org.kie.kogito.persistence.api.query.Query<WorkflowInstance> query = workflowInstanceStorage.query();

        // Apply filter
        if (filter != null) {
            query.filter(org.kubesmarts.logic.dataindex.graphql.filter.FilterConverter.convert(filter));
        }

        // Apply ordering
        if (orderBy != null) {
            query.sort(org.kubesmarts.logic.dataindex.graphql.filter.OrderByConverter.convert(orderBy));
        }

        // Apply pagination
        if (limit != null) {
            query.limit(limit);
        }
        if (offset != null) {
            query.offset(offset);
        }

        return query.execute();
    }

    /**
     * Get a single task execution by ID.
     *
     * @param id Task execution ID
     * @return TaskExecution or null if not found
     */
    @Query("getTaskExecution")
    @Description("Get a single task execution by ID. Returns null if not found.")
    public TaskExecution getTaskExecution(@Name("id") String id) {
        return taskExecutionStorage.get(id);
    }

    /**
     * Get multiple task executions with filtering, sorting, and pagination.
     *
     * @param filter Optional filter criteria
     * @param orderBy Optional sort order
     * @param limit Maximum number of results
     * @param offset Number of results to skip
     * @return List of task executions matching criteria
     */
    @Query("getTaskExecutions")
    @Description("Get multiple task executions with optional filtering, sorting, and pagination.")
    public List<TaskExecution> getTaskExecutions(
            @Name("filter") org.kubesmarts.logic.dataindex.graphql.filter.TaskExecutionFilter filter,
            @Name("orderBy") org.kubesmarts.logic.dataindex.graphql.filter.TaskExecutionOrderBy orderBy,
            @Name("limit") Integer limit,
            @Name("offset") Integer offset) {

        org.kie.kogito.persistence.api.query.Query<TaskExecution> query = taskExecutionStorage.query();

        // Apply filter
        if (filter != null) {
            query.filter(org.kubesmarts.logic.dataindex.graphql.filter.FilterConverter.convert(filter));
        }

        // Apply ordering
        if (orderBy != null) {
            query.sort(org.kubesmarts.logic.dataindex.graphql.filter.OrderByConverter.convert(orderBy));
        }

        // Apply pagination
        if (limit != null) {
            query.limit(limit);
        }
        if (offset != null) {
            query.offset(offset);
        }

        return query.execute();
    }

    /**
     * Get task executions for a workflow instance (via aggregate navigation).
     *
     * @param workflowInstanceId Workflow instance ID
     * @return List of task executions for this workflow instance
     */
    @Query("getTaskExecutionsByWorkflowInstance")
    @Description("Get task executions for a specific workflow instance.")
    public List<TaskExecution> getTaskExecutionsByWorkflowInstance(@Name("workflowInstanceId") String workflowInstanceId) {
        WorkflowInstance instance = workflowInstanceStorage.get(workflowInstanceId);
        if (instance == null) {
            return List.of();
        }
        return instance.getTaskExecutions() != null ? instance.getTaskExecutions() : List.of();
    }
}
