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
import org.kubesmarts.logic.dataindex.elasticsearch.ElasticsearchTaskExecutionStorage;
import org.kubesmarts.logic.dataindex.elasticsearch.TestAttributeFilter;
import org.kubesmarts.logic.dataindex.model.Error;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kie.kogito.persistence.api.query.FilterCondition;
import org.kie.kogito.persistence.api.query.QueryFilterFactory;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for ElasticsearchTaskExecutionStorage.
 *
 * <p>Tests all CRUD operations and query capabilities:
 * <ul>
 *   <li>CREATE - put() stores execution in Elasticsearch
 *   <li>READ - get() retrieves execution correctly
 *   <li>UPDATE - put() with same ID updates execution
 *   <li>DELETE - remove() deletes execution
 *   <li>QUERY - query() returns filtered/sorted results
 *   <li>NULL handling - get() on non-existent ID returns null
 * </ul>
 *
 * <p><b>Test Data Setup:</b>
 * Uses @BeforeEach/@AfterEach to ensure clean state for each test.
 * Creates executions directly via storage API.
 *
 * <p><b>Composite ID Pattern:</b>
 * TaskExecution uses composite IDs in format: {@code instanceId:taskPosition}
 * Example: {@code test-instance-001:/do/0}
 *
 * <p><b>Elasticsearch Dev Services:</b>
 * Quarkus automatically starts Elasticsearch container for tests.
 * No manual setup required.
 */
@QuarkusTest
class ElasticsearchTaskExecutionStorageIT {

    @Inject
    ElasticsearchTaskExecutionStorage storage;

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String TEST_INSTANCE_ID_1 = "test-instance-001";
    private static final String TEST_INSTANCE_ID_2 = "test-instance-002";
    private static final String TEST_INSTANCE_ID_3 = "test-instance-003";

    // Composite IDs: instanceId:taskPosition
    private static final String TEST_ID_1 = TEST_INSTANCE_ID_1 + ":/do/0";
    private static final String TEST_ID_2 = TEST_INSTANCE_ID_1 + ":/do/1";
    private static final String TEST_ID_3 = TEST_INSTANCE_ID_2 + ":/do/0";

    @BeforeEach
    void setUp() throws Exception {
        // Create index if it doesn't exist
        createIndexIfNotExists("test-task-executions");

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
                        .properties("task_name", p -> p.keyword(k -> k))
                        .properties("task_position", p -> p.keyword(k -> k))
                        .properties("status", p -> p.keyword(k -> k))
                        .properties("instance_id", p -> p.keyword(k -> k))
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
            .as("ElasticsearchTaskExecutionStorage should be injected by CDI")
            .isNotNull();
    }

    @Test
    void testPutAndGet() throws Exception {
        // Given: A task execution
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "RUNNING");

        // When: Put the execution
        TaskExecution result = storage.put(TEST_ID_1, execution);

        // Then: Put returns the same execution
        assertThat(result).isSameAs(execution);

        // Wait for Elasticsearch to refresh
        waitForRefresh();

        // When: Get the execution
        TaskExecution retrieved = storage.get(TEST_ID_1);

        // Then: Retrieved execution matches original
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo(TEST_ID_1);
        assertThat(retrieved.getTaskName()).isEqualTo("call-service");
        assertThat(retrieved.getTaskPosition()).isEqualTo("/do/0");
        assertThat(retrieved.getStatus()).isEqualTo("RUNNING");
        assertThat(retrieved.getStart()).isNotNull();
    }

    @Test
    void testGetNonExistent() {
        // When: Get non-existent execution
        TaskExecution result = storage.get("non-existent-id");

        // Then: Returns null
        assertThat(result).isNull();
    }

    @Test
    void testUpdate() throws Exception {
        // Given: An existing task execution
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "RUNNING");
        storage.put(TEST_ID_1, execution);
        waitForRefresh();

        // When: Update the execution
        execution.setStatus("COMPLETED");
        execution.setEnd(ZonedDateTime.now());
        storage.put(TEST_ID_1, execution);
        waitForRefresh();

        // Then: Updated execution is retrieved
        TaskExecution retrieved = storage.get(TEST_ID_1);
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getStatus()).isEqualTo("COMPLETED");
        assertThat(retrieved.getEnd()).isNotNull();
    }

    @Test
    void testRemove() throws Exception {
        // Given: An existing task execution
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "RUNNING");
        storage.put(TEST_ID_1, execution);
        waitForRefresh();

        // When: Remove the execution
        TaskExecution removed = storage.remove(TEST_ID_1);

        // Then: Remove returns the removed execution
        assertThat(removed).isNotNull();
        assertThat(removed.getId()).isEqualTo(TEST_ID_1);

        // Wait for deletion to complete
        waitForRefresh();

        // And: Execution no longer exists
        TaskExecution retrieved = storage.get(TEST_ID_1);
        assertThat(retrieved).isNull();
    }

    @Test
    void testRemoveNonExistent() {
        // When: Remove non-existent execution
        TaskExecution removed = storage.remove("non-existent-id");

        // Then: Returns null
        assertThat(removed).isNull();
    }

    @Test
    void testContainsKey() throws Exception {
        // Given: An existing task execution
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "RUNNING");
        storage.put(TEST_ID_1, execution);
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
        // Given: Multiple task executions
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "task-1", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "task-2", "COMPLETED"));
        waitForRefresh();

        // When: Clear all executions
        storage.clear();
        waitForRefresh();

        // Then: All executions are removed
        assertThat(storage.get(TEST_ID_1)).isNull();
        assertThat(storage.get(TEST_ID_2)).isNull();
    }

    @Test
    void testQueryAll() throws Exception {
        // Given: Multiple task executions
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "task-1", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "task-2", "COMPLETED"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "task-3", "RUNNING"));
        waitForRefresh();

        // When: Query all executions
        List<TaskExecution> results = storage.query().execute();

        // Then: Returns all executions
        assertThat(results).hasSize(3);
    }

    @Test
    void testQueryWithFilterByStatus() throws Exception {
        // Given: Multiple task executions with different statuses
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "task-1", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "task-2", "COMPLETED"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "task-3", "RUNNING"));
        waitForRefresh();

        // When: Query for RUNNING executions
        TestAttributeFilter<String> filter = new TestAttributeFilter<>(
            "status",
            FilterCondition.EQUAL,
            "RUNNING"
        );
        List<TaskExecution> results = storage.query()
            .filter(List.of(filter))
            .execute();

        // Then: Returns only RUNNING executions
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.getStatus().equals("RUNNING"));
    }

    @Test
    void testQueryWithFilterByTaskName() throws Exception {
        // Given: Multiple task executions with different task names
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "emit-event", "COMPLETED"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "call-service", "RUNNING"));
        waitForRefresh();

        // When: Query for "call-service" executions
        TestAttributeFilter<String> filter = new TestAttributeFilter<>(
            "taskName",
            FilterCondition.EQUAL,
            "call-service"
        );
        List<TaskExecution> results = storage.query()
            .filter(List.of(filter))
            .execute();

        // Then: Returns only "call-service" executions
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(e -> e.getTaskName().equals("call-service"));
    }

    @Test
    void testQueryWithSort() throws Exception {
        // Given: Multiple task executions with different task names
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "zebra-task", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "alpha-task", "RUNNING"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "beta-task", "RUNNING"));
        waitForRefresh();

        // When: Query with ascending sort by taskName
        List<TaskExecution> results = storage.query()
            .sort(List.of(QueryFilterFactory.orderBy("taskName", org.kie.kogito.persistence.api.query.SortDirection.ASC)))
            .execute();

        // Then: Returns executions in alphabetical order
        assertThat(results).hasSize(3);
        assertThat(results.get(0).getTaskName()).isEqualTo("alpha-task");
        assertThat(results.get(1).getTaskName()).isEqualTo("beta-task");
        assertThat(results.get(2).getTaskName()).isEqualTo("zebra-task");
    }

    @Test
    void testQueryWithPagination() throws Exception {
        // Given: Multiple task executions
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "task-1", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "task-2", "RUNNING"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "task-3", "RUNNING"));
        waitForRefresh();

        // When: Query first page (limit 2)
        List<TaskExecution> page1 = storage.query()
            .limit(2)
            .execute();

        // Then: Returns 2 executions
        assertThat(page1).hasSize(2);

        // When: Query second page (offset 2, limit 2)
        List<TaskExecution> page2 = storage.query()
            .offset(2)
            .limit(2)
            .execute();

        // Then: Returns remaining execution
        assertThat(page2).hasSize(1);
    }

    @Test
    void testQueryCount() throws Exception {
        // Given: Multiple task executions
        storage.put(TEST_ID_1, createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "task-1", "RUNNING"));
        storage.put(TEST_ID_2, createTestExecution(TEST_ID_2, TEST_INSTANCE_ID_1, "/do/1", "task-2", "COMPLETED"));
        storage.put(TEST_ID_3, createTestExecution(TEST_ID_3, TEST_INSTANCE_ID_2, "/do/0", "task-3", "RUNNING"));
        waitForRefresh();

        // When: Count all executions
        long total = storage.query().count();

        // Then: Returns correct count
        assertThat(total).isEqualTo(3);

        // When: Count RUNNING executions
        TestAttributeFilter<String> filter = new TestAttributeFilter<>(
            "status",
            FilterCondition.EQUAL,
            "RUNNING"
        );
        long runningCount = storage.query()
            .filter(List.of(filter))
            .count();

        // Then: Returns correct filtered count
        assertThat(runningCount).isEqualTo(2);
    }

    @Test
    void testJsonFieldsSerializationDeserialization() throws Exception {
        // Given: A task execution with JSON input/output
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "COMPLETED");

        JsonNode input = objectMapper.readTree("{\"serviceUrl\": \"https://api.example.com\", \"method\": \"POST\"}");
        JsonNode output = objectMapper.readTree("{\"statusCode\": 200, \"responseBody\": {\"success\": true}}");

        execution.setInput(input);
        execution.setOutput(output);

        // When: Put and get the execution
        storage.put(TEST_ID_1, execution);
        waitForRefresh();
        TaskExecution retrieved = storage.get(TEST_ID_1);

        // Then: JSON fields are correctly serialized/deserialized
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getInput()).isNotNull();
        assertThat(retrieved.getInput().get("serviceUrl").asText()).isEqualTo("https://api.example.com");
        assertThat(retrieved.getOutput()).isNotNull();
        assertThat(retrieved.getOutput().get("statusCode").asInt()).isEqualTo(200);
    }

    @Test
    void testErrorFieldSerializationDeserialization() throws Exception {
        // Given: A task execution with error
        TaskExecution execution = createTestExecution(TEST_ID_1, TEST_INSTANCE_ID_1, "/do/0", "call-service", "FAULTED");

        Error error = new Error("communication", "Service unavailable");
        error.setDetail("HTTP 503 - Service temporarily unavailable");
        error.setStatus(503);
        error.setInstance("error-task-001");

        execution.setError(error);
        execution.setEnd(ZonedDateTime.now());

        // When: Put and get the execution
        storage.put(TEST_ID_1, execution);
        waitForRefresh();
        TaskExecution retrieved = storage.get(TEST_ID_1);

        // Then: Error is correctly serialized/deserialized
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getError()).isNotNull();
        assertThat(retrieved.getError().getType()).isEqualTo("communication");
        assertThat(retrieved.getError().getTitle()).isEqualTo("Service unavailable");
        assertThat(retrieved.getError().getDetail()).isEqualTo("HTTP 503 - Service temporarily unavailable");
        assertThat(retrieved.getError().getStatus()).isEqualTo(503);
        assertThat(retrieved.getError().getInstance()).isEqualTo("error-task-001");
    }

    @Test
    void testGetRootType() {
        // When: Get root type
        String rootType = storage.getRootType();

        // Then: Returns TaskExecution class name
        assertThat(rootType).isEqualTo(TaskExecution.class.getCanonicalName());
    }

    @Test
    void testCompositeIdPattern() throws Exception {
        // Given: Task executions with composite IDs following instanceId:taskPosition pattern
        String instanceId = "workflow-abc-123";
        String taskPosition1 = "/do/0";
        String taskPosition2 = "/do/1/then/0";

        String compositeId1 = instanceId + ":" + taskPosition1;
        String compositeId2 = instanceId + ":" + taskPosition2;

        TaskExecution execution1 = createTestExecution(compositeId1, instanceId, taskPosition1, "first-task", "COMPLETED");
        TaskExecution execution2 = createTestExecution(compositeId2, instanceId, taskPosition2, "nested-task", "RUNNING");

        // When: Put executions
        storage.put(compositeId1, execution1);
        storage.put(compositeId2, execution2);
        waitForRefresh();

        // Then: Executions can be retrieved by composite ID
        TaskExecution retrieved1 = storage.get(compositeId1);
        TaskExecution retrieved2 = storage.get(compositeId2);

        assertThat(retrieved1).isNotNull();
        assertThat(retrieved1.getId()).isEqualTo(compositeId1);
        assertThat(retrieved1.getTaskPosition()).isEqualTo(taskPosition1);

        assertThat(retrieved2).isNotNull();
        assertThat(retrieved2.getId()).isEqualTo(compositeId2);
        assertThat(retrieved2.getTaskPosition()).isEqualTo(taskPosition2);
    }

    @Test
    void testMultipleExecutionsPerInstance() throws Exception {
        // Given: Multiple task executions for the same workflow instance
        String instanceId = TEST_INSTANCE_ID_1;

        TaskExecution exec1 = createTestExecution(instanceId + ":/do/0", instanceId, "/do/0", "task-1", "COMPLETED");
        TaskExecution exec2 = createTestExecution(instanceId + ":/do/1", instanceId, "/do/1", "task-2", "RUNNING");
        TaskExecution exec3 = createTestExecution(instanceId + ":/do/2", instanceId, "/do/2", "task-3", "RUNNING");

        storage.put(exec1.getId(), exec1);
        storage.put(exec2.getId(), exec2);
        storage.put(exec3.getId(), exec3);
        waitForRefresh();

        // When: Query for executions by instance ID
        // Note: This would require proper instanceId field filtering in the query implementation
        List<TaskExecution> allResults = storage.query().execute();

        // Then: All executions exist and can be retrieved individually
        assertThat(storage.get(exec1.getId())).isNotNull();
        assertThat(storage.get(exec2.getId())).isNotNull();
        assertThat(storage.get(exec3.getId())).isNotNull();
        assertThat(allResults).hasSizeGreaterThanOrEqualTo(3);
    }

    /**
     * Create a test task execution with minimal required fields.
     */
    private TaskExecution createTestExecution(String id, String instanceId, String taskPosition, String taskName, String status) {
        TaskExecution execution = new TaskExecution();
        execution.setId(id);
        execution.setTaskName(taskName);
        execution.setTaskPosition(taskPosition);
        execution.setStatus(status);
        execution.setStart(ZonedDateTime.now());
        return execution;
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
