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

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.kie.kogito.persistence.api.query.AttributeFilter;
import org.kie.kogito.persistence.api.query.FilterCondition;

/**
 * Converts GraphQL filter input types to storage API AttributeFilter objects.
 *
 * <p>Handles conversion for:
 * <ul>
 *   <li>String filters (eq, like, in)
 *   <li>DateTime filters (eq, gt, gte, lt, lte)
 *   <li>Enum filters (eq, in)
 *   <li>JSON filters (eq with nested paths)
 * </ul>
 *
 * <p><b>JSON Filter Handling</b>:
 * <ul>
 *   <li>Converts nested field paths to dot-notation: "input.customerId"
 *   <li>Marks filters as JSON so JsonPredicateBuilder handles them
 *   <li>PostgreSQL: Uses JSONB operators (->>, @>)
 *   <li>Elasticsearch: Uses flattened field queries
 * </ul>
 */
public class FilterConverter {

    /**
     * Convert WorkflowInstanceFilter to AttributeFilter list.
     *
     * @param filter GraphQL filter input
     * @return List of AttributeFilter objects for storage Query API
     */
    public static List<AttributeFilter<?>> convert(WorkflowInstanceFilter filter) {
        List<AttributeFilter<?>> result = new ArrayList<>();

        if (filter == null) {
            return result;
        }

        // String filters
        addStringFilters(result, "id", filter.getId());
        addStringFilters(result, "name", filter.getName());
        addStringFilters(result, "namespace", filter.getNamespace());
        addStringFilters(result, "version", filter.getVersion());

        // Status filter
        if (filter.getStatus() != null) {
            WorkflowInstanceStatusFilter statusFilter = filter.getStatus();
            if (statusFilter.getEq() != null) {
                result.add(new DataIndexAttributeFilter<>("status", FilterCondition.EQUAL, statusFilter.getEq()));
            }
            if (statusFilter.getIn() != null && !statusFilter.getIn().isEmpty()) {
                result.add(new DataIndexAttributeFilter<>("status", FilterCondition.IN, statusFilter.getIn()));
            }
        }

        // DateTime filters
        addDateTimeFilters(result, "startTime", filter.getStartTime());
        addDateTimeFilters(result, "endTime", filter.getEndTime());

        // JSON filters
        addJsonFilters(result, "input", filter.getInput());
        addJsonFilters(result, "output", filter.getOutput());

        return result;
    }

    /**
     * Add string field filters.
     */
    private static void addStringFilters(List<AttributeFilter<?>> result, String fieldName, StringFilter filter) {
        if (filter == null) {
            return;
        }

        if (filter.getEq() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.EQUAL, filter.getEq()));
        }
        if (filter.getLike() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.LIKE, filter.getLike()));
        }
        if (filter.getIn() != null && !filter.getIn().isEmpty()) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.IN, filter.getIn()));
        }
    }

    /**
     * Add DateTime field filters.
     */
    private static void addDateTimeFilters(List<AttributeFilter<?>> result, String fieldName, DateTimeFilter filter) {
        if (filter == null) {
            return;
        }

        if (filter.getEq() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.EQUAL, filter.getEq()));
        }
        if (filter.getGt() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.GT, filter.getGt()));
        }
        if (filter.getGte() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.GTE, filter.getGte()));
        }
        if (filter.getLt() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.LT, filter.getLt()));
        }
        if (filter.getLte() != null) {
            result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.LTE, filter.getLte()));
        }
    }

    /**
     * Add JSON field filters.
     *
     * <p>Converts nested paths to dot-notation for storage layer:
     * <ul>
     *   <li>GraphQL: input: {eq: [{key: "customerId", value: "123"}]}
     *   <li>Storage: attribute="input.customerId", value="123", json=true
     * </ul>
     *
     * <p>Marks filters as JSON so JsonPredicateBuilder handles them with JSONB operators.
     */
    private static void addJsonFilters(List<AttributeFilter<?>> result, String fieldName, JsonFilter filter) {
        if (filter == null || filter.getEq() == null) {
            return;
        }

        for (JsonFieldFilter entry : filter.getEq()) {
            String attributePath = fieldName + "." + entry.getKey();
            DataIndexAttributeFilter<String> jsonFilter = new DataIndexAttributeFilter<>(attributePath, FilterCondition.EQUAL, entry.getValue());
            jsonFilter.setJson(true);  // Mark as JSON filter for JsonPredicateBuilder
            result.add(jsonFilter);
        }
    }
}
