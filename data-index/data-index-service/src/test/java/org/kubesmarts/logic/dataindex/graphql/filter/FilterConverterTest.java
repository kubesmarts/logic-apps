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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.kie.kogito.persistence.api.query.AttributeFilter;
import org.kie.kogito.persistence.api.query.FilterCondition;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for FilterConverter.
 *
 * <p>Verifies conversion from GraphQL filter input types to storage API AttributeFilter objects.
 */
class FilterConverterTest {

    @Test
    void testConvertEmptyFilter() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        List<AttributeFilter<?>> result = FilterConverter.convert(filter);
        assertThat(result).isEmpty();
    }

    @Test
    void testConvertNullFilter() {
        List<AttributeFilter<?>> result = FilterConverter.convert((WorkflowInstanceFilter) null);
        assertThat(result).isEmpty();
    }

    @Test
    void testConvertStringFilterEq() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        StringFilter nameFilter = new StringFilter();
        nameFilter.setEq("greeting-workflow");
        filter.setName(nameFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        AttributeFilter<?> attributeFilter = result.get(0);
        assertThat(attributeFilter.getAttribute()).isEqualTo("name");
        assertThat(attributeFilter.getCondition()).isEqualTo(FilterCondition.EQUAL);
        assertThat(attributeFilter.getValue()).isEqualTo("greeting-workflow");
    }

    @Test
    void testConvertStringFilterLike() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        StringFilter namespaceFilter = new StringFilter();
        namespaceFilter.setLike("prod-*");
        filter.setNamespace(namespaceFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAttribute()).isEqualTo("namespace");
        assertThat(result.get(0).getCondition()).isEqualTo(FilterCondition.LIKE);
        assertThat(result.get(0).getValue()).isEqualTo("prod-*");
    }

    @Test
    void testConvertStringFilterIn() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        StringFilter versionFilter = new StringFilter();
        versionFilter.setIn(List.of("1.0", "1.1", "1.2"));
        filter.setVersion(versionFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAttribute()).isEqualTo("version");
        assertThat(result.get(0).getCondition()).isEqualTo(FilterCondition.IN);
        assertThat(result.get(0).getValue()).isEqualTo(List.of("1.0", "1.1", "1.2"));
    }

    @Test
    void testConvertStatusFilter() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        WorkflowInstanceStatusFilter statusFilter = new WorkflowInstanceStatusFilter();
        statusFilter.setEq(WorkflowInstanceStatus.COMPLETED);
        filter.setStatus(statusFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAttribute()).isEqualTo("status");
        assertThat(result.get(0).getCondition()).isEqualTo(FilterCondition.EQUAL);
        assertThat(result.get(0).getValue()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testConvertDateTimeFilterGte() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        DateTimeFilter startTimeFilter = new DateTimeFilter();
        ZonedDateTime timestamp = ZonedDateTime.parse("2026-01-01T00:00:00Z");
        startTimeFilter.setGte(timestamp);
        filter.setStartTime(startTimeFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAttribute()).isEqualTo("startTime");
        assertThat(result.get(0).getCondition()).isEqualTo(FilterCondition.GTE);
        assertThat(result.get(0).getValue()).isEqualTo(timestamp);
    }

    @Test
    void testConvertJsonFilter() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        JsonFilter inputFilter = new JsonFilter();
        JsonFieldFilter field = new JsonFieldFilter();
        field.setKey("customerId");
        field.setValue("customer-123");
        inputFilter.setEq(List.of(field));
        filter.setInput(inputFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(1);
        AttributeFilter<?> attributeFilter = result.get(0);
        assertThat(attributeFilter.getAttribute()).isEqualTo("input.customerId");
        assertThat(attributeFilter.getCondition()).isEqualTo(FilterCondition.EQUAL);
        assertThat(attributeFilter.getValue()).isEqualTo("customer-123");
        assertThat(attributeFilter.isJson()).isTrue();  // Verify JSON flag is set
    }

    @Test
    void testConvertJsonFilterMultipleFields() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();
        JsonFilter outputFilter = new JsonFilter();

        JsonFieldFilter field1 = new JsonFieldFilter();
        field1.setKey("status");
        field1.setValue("approved");

        JsonFieldFilter field2 = new JsonFieldFilter();
        field2.setKey("amount");
        field2.setValue("1000");

        outputFilter.setEq(List.of(field1, field2));
        filter.setOutput(outputFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(2);

        // Find status filter
        AttributeFilter<?> statusFilter = result.stream()
            .filter(f -> f.getAttribute().equals("output.status"))
            .findFirst()
            .orElseThrow();
        assertThat(statusFilter.getValue()).isEqualTo("approved");
        assertThat(statusFilter.isJson()).isTrue();

        // Find amount filter
        AttributeFilter<?> amountFilter = result.stream()
            .filter(f -> f.getAttribute().equals("output.amount"))
            .findFirst()
            .orElseThrow();
        assertThat(amountFilter.getValue()).isEqualTo("1000");
        assertThat(amountFilter.isJson()).isTrue();
    }

    @Test
    void testConvertCombinedFilters() {
        WorkflowInstanceFilter filter = new WorkflowInstanceFilter();

        // Status filter
        WorkflowInstanceStatusFilter statusFilter = new WorkflowInstanceStatusFilter();
        statusFilter.setEq(WorkflowInstanceStatus.COMPLETED);
        filter.setStatus(statusFilter);

        // Namespace filter
        StringFilter namespaceFilter = new StringFilter();
        namespaceFilter.setEq("production");
        filter.setNamespace(namespaceFilter);

        // JSON input filter
        JsonFilter inputFilter = new JsonFilter();
        JsonFieldFilter field = new JsonFieldFilter();
        field.setKey("customerId");
        field.setValue("customer-123");
        inputFilter.setEq(List.of(field));
        filter.setInput(inputFilter);

        List<AttributeFilter<?>> result = FilterConverter.convert(filter);

        assertThat(result).hasSize(3);

        // Verify each filter
        assertThat(result).anyMatch(f ->
            f.getAttribute().equals("status") &&
            f.getValue().equals(WorkflowInstanceStatus.COMPLETED));

        assertThat(result).anyMatch(f ->
            f.getAttribute().equals("namespace") &&
            f.getValue().equals("production"));

        assertThat(result).anyMatch(f ->
            f.getAttribute().equals("input.customerId") &&
            f.getValue().equals("customer-123") &&
            f.isJson());
    }
}
