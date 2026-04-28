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
package org.kubesmarts.logic.dataindex.graphql.filter;

import java.util.ArrayList;
import java.util.List;

import org.kie.kogito.persistence.api.query.AttributeSort;
import org.kie.kogito.persistence.api.query.SortDirection;

/**
 * Converts GraphQL orderBy input types to storage API AttributeSort objects.
 *
 * <p>Handles conversion for WorkflowInstance and TaskExecution ordering.
 *
 * <p><b>Field Mapping</b>:
 * <ul>
 *   <li>GraphQL field names map to entity field names
 *   <li>startTime → start (entity uses "start" column)
 *   <li>endTime → end (entity uses "end" column)
 *   <li>enter → enter (TaskExecution entity)
 *   <li>exit → exit (TaskExecution entity)
 * </ul>
 */
public class OrderByConverter {

    /**
     * Convert WorkflowInstanceOrderBy to AttributeSort list.
     *
     * @param orderBy GraphQL orderBy input
     * @return List of AttributeSort objects for storage Query API
     */
    public static List<AttributeSort> convert(WorkflowInstanceOrderBy orderBy) {
        List<AttributeSort> result = new ArrayList<>();

        if (orderBy == null) {
            return result;
        }

        if (orderBy.getId() != null) {
            result.add(new DataIndexAttributeSort("id", toSortDirection(orderBy.getId())));
        }
        if (orderBy.getName() != null) {
            result.add(new DataIndexAttributeSort("name", toSortDirection(orderBy.getName())));
        }
        if (orderBy.getNamespace() != null) {
            result.add(new DataIndexAttributeSort("namespace", toSortDirection(orderBy.getNamespace())));
        }
        if (orderBy.getVersion() != null) {
            result.add(new DataIndexAttributeSort("version", toSortDirection(orderBy.getVersion())));
        }
        if (orderBy.getStatus() != null) {
            result.add(new DataIndexAttributeSort("status", toSortDirection(orderBy.getStatus())));
        }
        if (orderBy.getStartTime() != null) {
            result.add(new DataIndexAttributeSort("start", toSortDirection(orderBy.getStartTime())));
        }
        if (orderBy.getEndTime() != null) {
            result.add(new DataIndexAttributeSort("end", toSortDirection(orderBy.getEndTime())));
        }
        if (orderBy.getLastUpdate() != null) {
            result.add(new DataIndexAttributeSort("lastUpdate", toSortDirection(orderBy.getLastUpdate())));
        }

        return result;
    }

    /**
     * Convert TaskExecutionOrderBy to AttributeSort list.
     *
     * @param orderBy GraphQL orderBy input
     * @return List of AttributeSort objects for storage Query API
     */
    public static List<AttributeSort> convert(TaskExecutionOrderBy orderBy) {
        List<AttributeSort> result = new ArrayList<>();

        if (orderBy == null) {
            return result;
        }

        if (orderBy.getId() != null) {
            result.add(new DataIndexAttributeSort("id", toSortDirection(orderBy.getId())));
        }
        if (orderBy.getTaskName() != null) {
            result.add(new DataIndexAttributeSort("taskName", toSortDirection(orderBy.getTaskName())));
        }
        if (orderBy.getTaskPosition() != null) {
            result.add(new DataIndexAttributeSort("taskPosition", toSortDirection(orderBy.getTaskPosition())));
        }
        if (orderBy.getEnter() != null) {
            result.add(new DataIndexAttributeSort("enter", toSortDirection(orderBy.getEnter())));
        }
        if (orderBy.getExit() != null) {
            result.add(new DataIndexAttributeSort("exit", toSortDirection(orderBy.getExit())));
        }

        return result;
    }

    /**
     * Convert GraphQL OrderBy enum to storage SortDirection enum.
     */
    private static SortDirection toSortDirection(OrderBy orderBy) {
        return orderBy == OrderBy.ASC ? SortDirection.ASC : SortDirection.DESC;
    }
}
