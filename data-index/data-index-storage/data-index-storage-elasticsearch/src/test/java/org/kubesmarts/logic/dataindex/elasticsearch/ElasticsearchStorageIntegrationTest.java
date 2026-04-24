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
package org.kubesmarts.logic.dataindex.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.kie.kogito.persistence.api.query.FilterCondition.EQUAL;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Integration tests for Elasticsearch storage implementation.
 *
 * <p>Tests:
 * <ul>
 *   <li>CRUD operations (get, put, remove, containsKey)
 *   <li>Querying with filters (including JSON field filters)
 *   <li>Pagination (limit, offset)
 *   <li>Count queries
 * </ul>
 *
 * <p>Uses Testcontainers Elasticsearch for isolated testing.
 */
@QuarkusTest
public class ElasticsearchStorageIntegrationTest {

    @Inject
    ElasticsearchWorkflowInstanceStorage workflowStorage;

    @Inject
    ElasticsearchTaskExecutionStorage taskStorage;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    co.elastic.clients.elasticsearch.ElasticsearchClient client;

    @BeforeEach
    public void setUp() throws Exception {
        // Create indices manually for tests
        createIndexIfNotExists("test-workflow-instances");
        createIndexIfNotExists("test-task-executions");

        // Clear indices before each test
        try {
            workflowStorage.clear();
            taskStorage.clear();
            waitForRefresh();
        } catch (Exception e) {
            // Ignore clear errors on first run
        }
    }

    @AfterEach
    public void tearDown() {
        try {
            workflowStorage.clear();
            taskStorage.clear();
        } catch (Exception e) {
            // Ignore errors during teardown
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
                        .properties("taskName", p -> p.keyword(k -> k))
                        .properties("taskPosition", p -> p.keyword(k -> k))
                        .properties("input", p -> p.flattened(f -> f))
                        .properties("output", p -> p.flattened(f -> f))
                        .properties("inputArgs", p -> p.flattened(f -> f))
                        .properties("outputArgs", p -> p.flattened(f -> f))
                    ));
            }
        } catch (Exception e) {
            // Ignore if index already exists
        }
    }

    // ==================== WorkflowInstance CRUD Tests ====================

    @Test
    public void testWorkflowInstancePutAndGet() {
        // Given
        WorkflowInstance instance = createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.RUNNING);

        // When
        workflowStorage.put("wf-1", instance);
        waitForRefresh();

        WorkflowInstance retrieved = workflowStorage.get("wf-1");

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-1");
        assertThat(retrieved.getName()).isEqualTo("greeting");
        assertThat(retrieved.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    public void testWorkflowInstanceContainsKey() {
        // Given
        WorkflowInstance instance = createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.RUNNING);
        workflowStorage.put("wf-1", instance);
        waitForRefresh();

        // When/Then
        assertThat(workflowStorage.containsKey("wf-1")).isTrue();
        assertThat(workflowStorage.containsKey("non-existent")).isFalse();
    }

    @Test
    public void testWorkflowInstanceRemove() {
        // Given
        WorkflowInstance instance = createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.RUNNING);
        workflowStorage.put("wf-1", instance);
        waitForRefresh();

        // When
        WorkflowInstance removed = workflowStorage.remove("wf-1");
        waitForRefresh();

        // Then
        assertThat(removed).isNotNull();
        assertThat(removed.getId()).isEqualTo("wf-1");
        assertThat(workflowStorage.containsKey("wf-1")).isFalse();
    }

    // ==================== WorkflowInstance Query Tests ====================

    @Test
    public void testQueryByStatus() {
        // Given
        workflowStorage.put("wf-1", createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.COMPLETED));
        workflowStorage.put("wf-2", createWorkflowInstance("wf-2", "greeting", WorkflowInstanceStatus.RUNNING));
        workflowStorage.put("wf-3", createWorkflowInstance("wf-3", "greeting", WorkflowInstanceStatus.COMPLETED));
        waitForRefresh();

        // When
        List<WorkflowInstance> results = workflowStorage.query()
                .filter(List.of(new TestAttributeFilter<>("status", EQUAL, WorkflowInstanceStatus.COMPLETED)))
                .execute();

        // Then
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(w -> w.getStatus() == WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    public void testQueryByName() {
        // Given
        workflowStorage.put("wf-1", createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.RUNNING));
        workflowStorage.put("wf-2", createWorkflowInstance("wf-2", "approval", WorkflowInstanceStatus.RUNNING));
        waitForRefresh();

        // When
        List<WorkflowInstance> results = workflowStorage.query()
                .filter(List.of(new TestAttributeFilter<>("name", EQUAL, "greeting")))
                .execute();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getName()).isEqualTo("greeting");
    }

    @Test
    public void testQueryByJsonInputField() throws Exception {
        // Given
        WorkflowInstance wf1 = createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.RUNNING);
        wf1.setInput(objectMapper.readTree("{\"customerId\": \"customer-123\"}"));

        WorkflowInstance wf2 = createWorkflowInstance("wf-2", "greeting", WorkflowInstanceStatus.RUNNING);
        wf2.setInput(objectMapper.readTree("{\"customerId\": \"customer-456\"}"));

        workflowStorage.put("wf-1", wf1);
        workflowStorage.put("wf-2", wf2);
        waitForRefresh();

        // When
        TestAttributeFilter<String> filter = new TestAttributeFilter<>("input.customerId", EQUAL, "customer-123");
        filter.setJson(true);

        List<WorkflowInstance> results = workflowStorage.query()
                .filter(List.of(filter))
                .execute();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("wf-1");
    }

    @Test
    public void testQueryByJsonOutputField() throws Exception {
        // Given
        WorkflowInstance wf1 = createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.COMPLETED);
        wf1.setOutput(objectMapper.readTree("{\"status\": \"approved\"}"));

        WorkflowInstance wf2 = createWorkflowInstance("wf-2", "greeting", WorkflowInstanceStatus.COMPLETED);
        wf2.setOutput(objectMapper.readTree("{\"status\": \"rejected\"}"));

        workflowStorage.put("wf-1", wf1);
        workflowStorage.put("wf-2", wf2);
        waitForRefresh();

        // When
        TestAttributeFilter<String> filter = new TestAttributeFilter<>("output.status", EQUAL, "approved");
        filter.setJson(true);

        List<WorkflowInstance> results = workflowStorage.query()
                .filter(List.of(filter))
                .execute();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("wf-1");
    }

    @Test
    public void testQueryWithPagination() {
        // Given
        for (int i = 0; i < 10; i++) {
            workflowStorage.put("wf-" + i, createWorkflowInstance("wf-" + i, "greeting", WorkflowInstanceStatus.RUNNING));
        }
        waitForRefresh();

        // When
        List<WorkflowInstance> page1 = workflowStorage.query()
                .limit(5)
                .offset(0)
                .execute();

        List<WorkflowInstance> page2 = workflowStorage.query()
                .limit(5)
                .offset(5)
                .execute();

        // Then
        assertThat(page1).hasSize(5);
        assertThat(page2).hasSize(5);
    }

    @Test
    public void testCount() {
        // Given
        workflowStorage.put("wf-1", createWorkflowInstance("wf-1", "greeting", WorkflowInstanceStatus.COMPLETED));
        workflowStorage.put("wf-2", createWorkflowInstance("wf-2", "greeting", WorkflowInstanceStatus.RUNNING));
        workflowStorage.put("wf-3", createWorkflowInstance("wf-3", "greeting", WorkflowInstanceStatus.COMPLETED));
        waitForRefresh();

        // When
        long totalCount = workflowStorage.query().count();
        long completedCount = workflowStorage.query()
                .filter(List.of(new TestAttributeFilter<>("status", EQUAL, WorkflowInstanceStatus.COMPLETED)))
                .count();

        // Then
        assertThat(totalCount).isEqualTo(3);
        assertThat(completedCount).isEqualTo(2);
    }

    // ==================== TaskExecution CRUD Tests ====================

    @Test
    public void testTaskExecutionPutAndGet() {
        // Given
        TaskExecution task = createTaskExecution("task-1", "greetTask", "/do/0");

        // When
        taskStorage.put("task-1", task);
        waitForRefresh();

        TaskExecution retrieved = taskStorage.get("task-1");

        // Then
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("task-1");
        assertThat(retrieved.getTaskName()).isEqualTo("greetTask");
        assertThat(retrieved.getTaskPosition()).isEqualTo("/do/0");
    }

    @Test
    public void testQueryTasksByName() {
        // Given
        taskStorage.put("task-1", createTaskExecution("task-1", "task1", "/do/0"));
        taskStorage.put("task-2", createTaskExecution("task-2", "task2", "/do/1"));
        taskStorage.put("task-3", createTaskExecution("task-3", "task1", "/do/2"));
        waitForRefresh();

        // When
        List<TaskExecution> results = taskStorage.query()
                .filter(List.of(new TestAttributeFilter<>("taskName", EQUAL, "task1")))
                .execute();

        // Then
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(t -> t.getTaskName().equals("task1"));
    }

    @Test
    public void testQueryTasksByJsonOutputField() throws Exception {
        // Given
        TaskExecution task1 = createTaskExecution("task-1", "approvalTask", "/do/0");
        task1.setOutput(objectMapper.readTree("{\"decision\": \"approved\"}"));

        TaskExecution task2 = createTaskExecution("task-2", "approvalTask", "/do/1");
        task2.setOutput(objectMapper.readTree("{\"decision\": \"rejected\"}"));

        taskStorage.put("task-1", task1);
        taskStorage.put("task-2", task2);
        waitForRefresh();

        // When
        TestAttributeFilter<String> filter = new TestAttributeFilter<>("output.decision", EQUAL, "approved");
        filter.setJson(true);

        List<TaskExecution> results = taskStorage.query()
                .filter(List.of(filter))
                .execute();

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("task-1");
    }

    // ==================== Helper Methods ====================

    private WorkflowInstance createWorkflowInstance(String id, String name, WorkflowInstanceStatus status) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setName(name);
        instance.setNamespace("default");
        instance.setVersion("1.0.0");
        instance.setStatus(status);
        return instance;
    }

    private TaskExecution createTaskExecution(String id, String taskName, String taskPosition) {
        TaskExecution task = new TaskExecution();
        task.setId(id);
        task.setTaskName(taskName);
        task.setTaskPosition(taskPosition);
        task.setStart(ZonedDateTime.now());
        return task;
    }

    private void waitForRefresh() {
        try {
            // Wait for ES to refresh indices
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
