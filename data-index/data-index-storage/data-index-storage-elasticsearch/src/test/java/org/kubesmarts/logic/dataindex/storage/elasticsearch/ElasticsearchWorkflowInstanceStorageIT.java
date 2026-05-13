/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.elasticsearch.ElasticsearchWorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
import org.kie.kogito.persistence.api.query.FilterCondition;
import org.kie.kogito.persistence.api.query.QueryFilterFactory;
import org.kubesmarts.logic.dataindex.elasticsearch.TestAttributeFilter;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ElasticsearchWorkflowInstanceStorage.
 *
 * <p>Tests all CRUD operations and query capabilities:
 * <ul>
 *   <li>CREATE - put() stores instance in Elasticsearch
 *   <li>READ - get() retrieves instance correctly
 *   <li>UPDATE - put() with same ID updates instance
 *   <li>DELETE - remove() deletes instance
 *   <li>QUERY - query() returns filtered/sorted results
 *   <li>NULL handling - get() on non-existent ID returns null
 * </ul>
 *
 * <p><b>Test Data Setup:</b>
 * Uses @BeforeEach/@AfterEach to ensure clean state for each test.
 * Creates instances directly via storage API.
 *
 * <p><b>Elasticsearch Dev Services:</b>
 * Quarkus automatically starts Elasticsearch container for tests.
 * No manual setup required.
 */
@QuarkusTest
class ElasticsearchWorkflowInstanceStorageIT {

    @Inject
    ElasticsearchWorkflowInstanceStorage storage;

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String TEST_ID_1 = "test-workflow-001";
    private static final String TEST_ID_2 = "test-workflow-002";
    private static final String TEST_ID_3 = "test-workflow-003";

    @BeforeEach
    void setUp() throws Exception {
        // Create index if it doesn't exist
        createIndexIfNotExists("test-workflow-instances");

        // Clean up any existing test data
        try {
            storage.clear();
            waitForRefresh();
        } catch (Exception e) {
            // Ignore clear errors - index might not exist or be empty
        }
    }

    private void createIndexIfNotExists(String indexName) throws Exception {
        try {
            boolean exists = client.indices().exists(r -> r.index(indexName)).value();
            if (!exists) {
                client.indices().create(r -> r
                    .index(indexName)
                    .settings(s -> s
                        .numberOfShards("1")
                        .numberOfReplicas("0")
                        .refreshInterval(t -> t.time("1s")))
                    .mappings(m -> m
                        .properties("id", p -> p.keyword(k -> k))
                        .properties("name", p -> p.keyword(k -> k))
                        .properties("namespace", p -> p.keyword(k -> k))
                        .properties("version", p -> p.keyword(k -> k))
                        .properties("status", p -> p.keyword(k -> k))
                        .properties("input", p -> p.flattened(f -> f))
                        .properties("output", p -> p.flattened(f -> f))
                    ));
            }
        } catch (Exception e) {
            // Ignore if index already exists
        }
    }

    @AfterEach
    void tearDown() {
        // Clean up test data after each test
        try {
            storage.clear();
        } catch (Exception e) {
            // Ignore errors during teardown
        }
    }

    @Test
    void testStorageIsInjected() {
        assertThat(storage)
            .as("ElasticsearchWorkflowInstanceStorage should be injected by CDI")
            .isNotNull();
    }

    @Test
    void testPutAndGet() throws Exception {
        // Given: A workflow instance
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "order-workflow", WorkflowInstanceStatus.RUNNING);

        // When: Put the instance
        WorkflowInstance result = storage.put(TEST_ID_1, instance);

        // Then: Put returns the same instance
        assertThat(result).isSameAs(instance);

        // Wait for Elasticsearch to refresh
        waitForRefresh();

        // When: Get the instance
        WorkflowInstance retrieved = storage.get(TEST_ID_1);

        // Then: Retrieved instance matches original
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(TEST_ID_1);
        assertThat(retrieved.getName()).isEqualTo("order-workflow");
        assertThat(retrieved.getNamespace()).isEqualTo("test-namespace");
        assertThat(retrieved.getVersion()).isEqualTo("1.0.0");
        assertThat(retrieved.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(retrieved.getStart()).isNotNull();
    }

    @Test
    void testGetNonExistent() {
        // When: Get non-existent instance
        WorkflowInstance result = storage.get("non-existent-id");

        // Then: Returns null
        assertThat(result).isNull();
    }

    @Test
    void testUpdate() throws Exception {
        // Given: An existing workflow instance
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "order-workflow", WorkflowInstanceStatus.RUNNING);
        storage.put(TEST_ID_1, instance);
        waitForRefresh();

        // When: Update the instance
        instance.setStatus(WorkflowInstanceStatus.COMPLETED);
        instance.setEnd(ZonedDateTime.now());
        storage.put(TEST_ID_1, instance);
        waitForRefresh();

        // Then: Updated instance is retrieved
        WorkflowInstance retrieved = storage.get(TEST_ID_1);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(retrieved.getEnd()).isNotNull();
    }

    @Test
    void testRemove() throws Exception {
        // Given: An existing workflow instance
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "order-workflow", WorkflowInstanceStatus.RUNNING);
        storage.put(TEST_ID_1, instance);
        waitForRefresh();

        // When: Remove the instance
        WorkflowInstance removed = storage.remove(TEST_ID_1);

        // Then: Remove returns the removed instance
        assertThat(removed).isNotNull();
        assertThat(removed.getId()).isEqualTo(TEST_ID_1);

        // Wait for deletion to complete
        waitForRefresh();

        // And: Instance no longer exists
        WorkflowInstance retrieved = storage.get(TEST_ID_1);
        assertThat(retrieved).isNull();
    }

    @Test
    void testRemoveNonExistent() {
        // When: Remove non-existent instance
        WorkflowInstance removed = storage.remove("non-existent-id");

        // Then: Returns null
        assertThat(removed).isNull();
    }

    @Test
    void testContainsKey() throws Exception {
        // Given: An existing workflow instance
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "order-workflow", WorkflowInstanceStatus.RUNNING);
        storage.put(TEST_ID_1, instance);
        waitForRefresh();

        // When: Check if key exists
        boolean exists = storage.containsKey(TEST_ID_1);

        // Then: Returns true
        assertThat(exists).isTrue();

        // When: Check non-existent key
        boolean notExists = storage.containsKey("non-existent-id");

        // Then: Returns false
        assertThat(notExists).isFalse();
    }

    @Test
    void testClear() throws Exception {
        // Given: Multiple workflow instances
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "workflow-1", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "workflow-2", WorkflowInstanceStatus.COMPLETED));
        waitForRefresh();

        // When: Clear all instances
        storage.clear();
        waitForRefresh();

        // Then: All instances are removed
        assertThat(storage.get(TEST_ID_1)).isNull();
        assertThat(storage.get(TEST_ID_2)).isNull();
    }

    @Test
    void testQueryAll() throws Exception {
        // Given: Multiple workflow instances
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "workflow-1", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "workflow-2", WorkflowInstanceStatus.COMPLETED));
        storage.put(TEST_ID_3, createTestInstance(TEST_ID_3, "workflow-3", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When: Query all instances
        List<WorkflowInstance> results = storage.query().execute();

        // Then: Returns all instances
        assertThat(results).hasSize(3);
    }

    @Test
    void testQueryWithFilter() throws Exception {
        // Given: Multiple workflow instances with different statuses
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "workflow-1", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "workflow-2", WorkflowInstanceStatus.COMPLETED));
        storage.put(TEST_ID_3, createTestInstance(TEST_ID_3, "workflow-3", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When: Query for RUNNING instances
        TestAttributeFilter<WorkflowInstanceStatus> filter = new TestAttributeFilter<>(
            "status",
            FilterCondition.EQUAL,
            WorkflowInstanceStatus.RUNNING
        );
        List<WorkflowInstance> results = storage.query()
            .filter(List.of(filter))
            .execute();

        // Then: Returns only RUNNING instances
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(i -> i.getStatus() == WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testQueryWithSort() throws Exception {
        // Given: Multiple workflow instances with different names
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "zebra-workflow", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "alpha-workflow", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_3, createTestInstance(TEST_ID_3, "beta-workflow", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When: Query with ascending sort by name
        List<WorkflowInstance> results = storage.query()
            .sort(List.of(QueryFilterFactory.orderBy("name", org.kie.kogito.persistence.api.query.SortDirection.ASC)))
            .execute();

        // Then: Returns instances in alphabetical order
        assertThat(results).hasSize(3);
        assertThat(results.get(0).getName()).isEqualTo("alpha-workflow");
        assertThat(results.get(1).getName()).isEqualTo("beta-workflow");
        assertThat(results.get(2).getName()).isEqualTo("zebra-workflow");
    }

    @Test
    void testQueryWithPagination() throws Exception {
        // Given: Multiple workflow instances
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "workflow-1", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "workflow-2", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_3, createTestInstance(TEST_ID_3, "workflow-3", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When: Query first page (limit 2)
        List<WorkflowInstance> page1 = storage.query()
            .limit(2)
            .execute();

        // Then: Returns 2 instances
        assertThat(page1).hasSize(2);

        // When: Query second page (offset 2, limit 2)
        List<WorkflowInstance> page2 = storage.query()
            .offset(2)
            .limit(2)
            .execute();

        // Then: Returns remaining instance
        assertThat(page2).hasSize(1);
    }

    @Test
    void testQueryCount() throws Exception {
        // Given: Multiple workflow instances
        storage.put(TEST_ID_1, createTestInstance(TEST_ID_1, "workflow-1", WorkflowInstanceStatus.RUNNING));
        storage.put(TEST_ID_2, createTestInstance(TEST_ID_2, "workflow-2", WorkflowInstanceStatus.COMPLETED));
        storage.put(TEST_ID_3, createTestInstance(TEST_ID_3, "workflow-3", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When: Count all instances
        long total = storage.query().count();

        // Then: Returns correct count
        assertThat(total).isEqualTo(3);

        // When: Count RUNNING instances
        TestAttributeFilter<WorkflowInstanceStatus> filter = new TestAttributeFilter<>(
            "status",
            FilterCondition.EQUAL,
            WorkflowInstanceStatus.RUNNING
        );
        long runningCount = storage.query()
            .filter(List.of(filter))
            .count();

        // Then: Returns correct filtered count
        assertThat(runningCount).isEqualTo(2);
    }

    @Test
    void testJsonFieldsSerializationDeserialization() throws Exception {
        // Given: A workflow instance with JSON input/output
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "order-workflow", WorkflowInstanceStatus.COMPLETED);

        JsonNode input = objectMapper.readTree("{\"orderId\": \"12345\", \"customerId\": \"C-001\"}");
        JsonNode output = objectMapper.readTree("{\"status\": \"success\", \"orderNumber\": \"ORD-12345\"}");

        instance.setInput(input);
        instance.setOutput(output);

        // When: Put and get the instance
        storage.put(TEST_ID_1, instance);
        waitForRefresh();
        WorkflowInstance retrieved = storage.get(TEST_ID_1);

        // Then: JSON fields are correctly serialized/deserialized
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getInput()).isNotNull();
        assertThat(retrieved.getInput().get("orderId").asText()).isEqualTo("12345");
        assertThat(retrieved.getOutput()).isNotNull();
        assertThat(retrieved.getOutput().get("status").asText()).isEqualTo("success");
    }

    @Test
    void testErrorFieldSerializationDeserialization() throws Exception {
        // Given: A workflow instance with error
        WorkflowInstance instance = createTestInstance(TEST_ID_1, "failed-workflow", WorkflowInstanceStatus.FAULTED);

        Error error = new Error("system", "Database connection failed");
        error.setDetail("Connection timeout after 30 seconds");
        error.setStatus(500);
        error.setInstance("error-001");

        instance.setError(error);
        instance.setEnd(ZonedDateTime.now());

        // When: Put and get the instance
        storage.put(TEST_ID_1, instance);
        waitForRefresh();
        WorkflowInstance retrieved = storage.get(TEST_ID_1);

        // Then: Error is correctly serialized/deserialized
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getError()).isNotNull();
        assertThat(retrieved.getError().getType()).isEqualTo("system");
        assertThat(retrieved.getError().getTitle()).isEqualTo("Database connection failed");
        assertThat(retrieved.getError().getDetail()).isEqualTo("Connection timeout after 30 seconds");
        assertThat(retrieved.getError().getStatus()).isEqualTo(500);
        assertThat(retrieved.getError().getInstance()).isEqualTo("error-001");
    }

    @Test
    void testGetRootType() {
        // When: Get root type
        String rootType = storage.getRootType();

        // Then: Returns WorkflowInstance class name
        assertThat(rootType).isEqualTo(WorkflowInstance.class.getCanonicalName());
    }

    /**
     * Create a test workflow instance with minimal required fields.
     */
    private WorkflowInstance createTestInstance(String id, String name, WorkflowInstanceStatus status) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setName(name);
        instance.setNamespace("test-namespace");
        instance.setVersion("1.0.0");
        instance.setStatus(status);
        instance.setStart(ZonedDateTime.now());
        instance.setLastUpdate(ZonedDateTime.now());
        return instance;
    }

    /**
     * Wait for Elasticsearch to refresh indices.
     */
    private void waitForRefresh() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
