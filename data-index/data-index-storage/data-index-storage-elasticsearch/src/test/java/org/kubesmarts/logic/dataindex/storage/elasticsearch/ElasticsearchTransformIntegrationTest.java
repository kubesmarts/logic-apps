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
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.elasticsearch.ElasticsearchTaskExecutionStorage;
import org.kubesmarts.logic.dataindex.elasticsearch.ElasticsearchWorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Elasticsearch Transform-based data retrieval.
 *
 * <p>Tests scenarios specific to MODE 2 (Elasticsearch Transform-based normalization):
 * <ul>
 *   <li>Bucket format deserialization (e.g., {"taskName": {"set-0": 1}})
 *   <li>get() methods searching by domain id field (not Elasticsearch _id)
 *   <li>findByWorkflowInstanceId() prefix queries
 *   <li>Handling of transform-aggregated documents
 * </ul>
 *
 * <p><b>Transform Aggregation Format:</b>
 * Elasticsearch transforms use terms aggregations which return bucket format:
 * <pre>
 * {
 *   "id": "wf-123",
 *   "name": {"greeting": 1},           ← Bucket format (key: count)
 *   "status": {"RUNNING": 2},           ← Bucket format (latest status wins by ordering)
 *   "taskName": {"call-api": 1}         ← Bucket format
 * }
 * </pre>
 *
 * <p><b>Custom Deserializers:</b>
 * BucketStringDeserializer and BucketEnumDeserializer extract values from bucket format.
 *
 * <p><b>Document IDs:</b>
 * Transform-created documents have auto-generated _id, but store workflow/task ID in "id" field.
 * The get() methods must search by "id" field, not _id.
 */
@QuarkusTest
class ElasticsearchTransformIntegrationTest {

    @Inject
    ElasticsearchWorkflowInstanceStorage workflowStorage;

    @Inject
    ElasticsearchTaskExecutionStorage taskStorage;

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String WORKFLOW_INDEX = "test-workflow-instances";
    private static final String TASK_INDEX = "test-task-executions";

    @BeforeEach
    void setUp() throws Exception {
        createIndices();
        cleanIndices();
    }

    @AfterEach
    void tearDown() throws Exception {
        cleanIndices();
    }

    private void createIndices() throws Exception {
        createIndexIfNotExists(WORKFLOW_INDEX);
        createIndexIfNotExists(TASK_INDEX);
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
                    ));
            }
        } catch (Exception e) {
            // Ignore if index already exists
        }
    }

    private void cleanIndices() throws Exception {
        try {
            workflowStorage.clear();
            taskStorage.clear();
            waitForRefresh();
        } catch (Exception e) {
            // Ignore clear errors
        }
    }

    private void waitForRefresh() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Bucket Format Deserialization Tests ====================

    @Test
    void testGetWorkflowInstanceWithBucketFormatFields() throws Exception {
        // Given: A workflow document in transform bucket format
        // Simulates what Elasticsearch transform creates with terms aggregations
        Map<String, Object> bucketDoc = Map.of(
            "id", "wf-bucket-001",
            "name", Map.of("simple-set", 1),              // Bucket format: {"value": count}
            "namespace", Map.of("org.acme", 1),
            "version", Map.of("1.0.0", 1),
            "status", Map.of("RUNNING", 2),                // Status bucket (count = 2 events)
            "last_update", "2026-05-05T14:00:00Z"
        );

        // When: Index the document directly (simulating transform output)
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(WORKFLOW_INDEX)
            .document(bucketDoc));

        client.index(indexReq);
        waitForRefresh();

        // When: Get workflow by ID
        WorkflowInstance retrieved = workflowStorage.get("wf-bucket-001");

        // Then: Bucket values are correctly deserialized to plain strings
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-bucket-001");
        assertThat(retrieved.getName()).isEqualTo("simple-set");        // Extracted from bucket
        assertThat(retrieved.getNamespace()).isEqualTo("org.acme");     // Extracted from bucket
        assertThat(retrieved.getVersion()).isEqualTo("1.0.0");          // Extracted from bucket
        assertThat(retrieved.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);  // Extracted from bucket
    }

    @Test
    void testGetTaskExecutionWithBucketFormatFields() throws Exception {
        // Given: A task execution document in transform bucket format
        Map<String, Object> bucketDoc = Map.of(
            "id", "wf-001:do/0/set-0",
            "taskName", Map.of("set-0", 2),                // Bucket format
            "taskPosition", Map.of("do/0/set-0", 2),       // Bucket format
            "status", Map.of("RUNNING", 1),                // Bucket format
            "last_update", "2026-05-05T14:00:00Z"
        );

        // When: Index the document
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(TASK_INDEX)
            .document(bucketDoc));

        client.index(indexReq);
        waitForRefresh();

        // When: Get task execution by ID
        TaskExecution retrieved = taskStorage.get("wf-001:do/0/set-0");

        // Then: Bucket values are correctly deserialized
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-001:do/0/set-0");
        assertThat(retrieved.getTaskName()).isEqualTo("set-0");           // Extracted from bucket
        assertThat(retrieved.getTaskPosition()).isEqualTo("do/0/set-0");  // Extracted from bucket
        assertThat(retrieved.getStatus()).isEqualTo("RUNNING");           // Extracted from bucket
    }

    @Test
    void testGetWorkflowInstanceWithMultipleBucketValues() throws Exception {
        // Given: Document with multiple bucket values (transform hasn't fully aggregated)
        // This can happen during intermediate transform processing
        Map<String, Object> bucketDoc = Map.of(
            "id", "wf-multi-001",
            "name", Map.of(
                "workflow-v1", 1,
                "workflow-v2", 1  // Multiple values - should take first alphabetically
            ),
            "status", Map.of(
                "RUNNING", 1,
                "COMPLETED", 1
            )
        );

        // When: Index and retrieve
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(WORKFLOW_INDEX)
            .document(bucketDoc));

        client.index(indexReq);
        waitForRefresh();

        WorkflowInstance retrieved = workflowStorage.get("wf-multi-001");

        // Then: Deserializer extracts first value
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-multi-001");
        assertThat(retrieved.getName()).isNotNull();  // Some value extracted
    }

    // ==================== Search by Domain ID (not _id) Tests ====================

    @Test
    void testGetWorkflowInstanceByDomainId() throws Exception {
        // Given: Workflow document with auto-generated _id (like transform creates)
        // The domain ID is stored in "id" field, _id is auto-generated
        Map<String, Object> doc = Map.of(
            "id", "wf-domain-123",
            "name", "greeting",
            "status", "RUNNING"
        );

        // When: Index without specifying _id (Elasticsearch auto-generates it)
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(WORKFLOW_INDEX)
            .document(doc)
            // No .id() specified - Elasticsearch will auto-generate _id
        );

        client.index(indexReq);
        waitForRefresh();

        // When: Get by domain ID (not Elasticsearch _id)
        WorkflowInstance retrieved = workflowStorage.get("wf-domain-123");

        // Then: Found via search on "id" field
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-domain-123");
        assertThat(retrieved.getName()).isEqualTo("greeting");
    }

    @Test
    void testGetTaskExecutionByDomainId() throws Exception {
        // Given: Task execution with auto-generated _id
        Map<String, Object> doc = Map.of(
            "id", "wf-001:do/0/task-1",
            "taskName", "task-1",
            "taskPosition", "do/0/task-1",
            "status", "COMPLETED"
        );

        // When: Index without _id
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(TASK_INDEX)
            .document(doc));

        client.index(indexReq);
        waitForRefresh();

        // When: Get by composite domain ID
        TaskExecution retrieved = taskStorage.get("wf-001:do/0/task-1");

        // Then: Found via search on "id" field
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-001:do/0/task-1");
    }

    @Test
    void testGetNonExistentWorkflowInstanceReturnsNull() {
        // When: Get workflow that doesn't exist
        WorkflowInstance result = workflowStorage.get("non-existent-id");

        // Then: Returns null
        assertThat(result).isNull();
    }

    @Test
    void testGetNonExistentTaskExecutionReturnsNull() {
        // When: Get task execution that doesn't exist
        TaskExecution result = taskStorage.get("non-existent-id");

        // Then: Returns null
        assertThat(result).isNull();
    }

    // ==================== findByWorkflowInstanceId Tests ====================

    @Test
    void testFindTaskExecutionsByWorkflowInstanceId() throws Exception {
        // Given: Multiple task executions for workflow "wf-001"
        String workflowId = "wf-001";

        Map<String, Object> task1 = Map.of(
            "id", workflowId + ":do/0/set-0",
            "taskName", Map.of("set-0", 1),
            "taskPosition", Map.of("do/0/set-0", 1),
            "status", Map.of("COMPLETED", 1)
        );

        Map<String, Object> task2 = Map.of(
            "id", workflowId + ":do/1/set-1",
            "taskName", Map.of("set-1", 1),
            "taskPosition", Map.of("do/1/set-1", 1),
            "status", Map.of("RUNNING", 1)
        );

        // And: Task execution for different workflow
        Map<String, Object> task3 = Map.of(
            "id", "wf-002:do/0/set-0",
            "taskName", Map.of("set-0", 1),
            "taskPosition", Map.of("do/0/set-0", 1),
            "status", Map.of("RUNNING", 1)
        );

        // When: Index all task executions
        client.index(IndexRequest.of(i -> i.index(TASK_INDEX).document(task1)));
        client.index(IndexRequest.of(i -> i.index(TASK_INDEX).document(task2)));
        client.index(IndexRequest.of(i -> i.index(TASK_INDEX).document(task3)));
        waitForRefresh();

        // When: Find task executions by workflow instance ID
        List<TaskExecution> results = taskStorage.findByWorkflowInstanceId(workflowId);

        // Then: Returns only task executions for wf-001
        assertThat(results).hasSize(2);
        assertThat(results).allMatch(t -> t.getId().startsWith(workflowId + ":"));
        assertThat(results).extracting(TaskExecution::getTaskName)
            .containsExactlyInAnyOrder("set-0", "set-1");
    }

    @Test
    void testFindTaskExecutionsByWorkflowInstanceIdWithNoResults() throws Exception {
        // Given: Task executions for different workflows
        Map<String, Object> task1 = Map.of(
            "id", "wf-001:do/0/set-0",
            "taskName", "set-0",
            "status", "RUNNING"
        );

        client.index(IndexRequest.of(i -> i.index(TASK_INDEX).document(task1)));
        waitForRefresh();

        // When: Find task executions for non-existent workflow
        List<TaskExecution> results = taskStorage.findByWorkflowInstanceId("wf-999");

        // Then: Returns empty list
        assertThat(results).isEmpty();
    }

    @Test
    void testFindTaskExecutionsByWorkflowInstanceIdWithManyTasks() throws Exception {
        // Given: Many task executions for same workflow
        String workflowId = "wf-large-001";

        for (int i = 0; i < 50; i++) {
            Map<String, Object> task = Map.of(
                "id", workflowId + ":do/" + i + "/task-" + i,
                "taskName", "task-" + i,
                "taskPosition", "do/" + i + "/task-" + i,
                "status", "COMPLETED"
            );
            client.index(IndexRequest.of(r -> r.index(TASK_INDEX).document(task)));
        }

        waitForRefresh();

        // When: Find all task executions
        List<TaskExecution> results = taskStorage.findByWorkflowInstanceId(workflowId);

        // Then: Returns all 50 task executions
        assertThat(results).hasSize(50);
        assertThat(results).allMatch(t -> t.getId().startsWith(workflowId + ":"));
    }

    @Test
    void testFindTaskExecutionsByWorkflowInstanceIdWithSpecialCharacters() throws Exception {
        // Given: Workflow ID with special characters that need escaping
        String workflowId = "wf:special-chars/test\\example";

        Map<String, Object> task1 = Map.of(
            "id", workflowId + ":do/0/set-0",
            "taskName", "set-0",
            "taskPosition", "do/0/set-0",
            "status", "RUNNING"
        );

        client.index(IndexRequest.of(i -> i.index(TASK_INDEX).document(task1)));
        waitForRefresh();

        // When: Find task executions
        List<TaskExecution> results = taskStorage.findByWorkflowInstanceId(workflowId);

        // Then: Correctly finds tasks despite special characters
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(workflowId + ":do/0/set-0");
    }

    // ==================== Null Field Handling Tests ====================

    @Test
    void testGetWorkflowInstanceWithNullBucketFields() throws Exception {
        // Given: Document with null bucket values (no events for certain fields)
        Map<String, Object> doc = Map.of(
            "id", "wf-null-001",
            "name", Map.of("greeting", 1),
            "status", Map.of("RUNNING", 1)
            // namespace and version are null/missing
        );

        // When: Index and retrieve
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(WORKFLOW_INDEX)
            .document(doc));

        client.index(indexReq);
        waitForRefresh();

        WorkflowInstance retrieved = workflowStorage.get("wf-null-001");

        // Then: Non-null fields are deserialized, null fields are null
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-null-001");
        assertThat(retrieved.getName()).isEqualTo("greeting");
        assertThat(retrieved.getNamespace()).isNull();
        assertThat(retrieved.getVersion()).isNull();
    }

    @Test
    void testGetTaskExecutionWithNullFields() throws Exception {
        // Given: Task execution with minimal fields
        Map<String, Object> doc = Map.of(
            "id", "wf-001:do/0/task-minimal",
            "taskName", Map.of("minimal-task", 1),
            "status", Map.of("RUNNING", 1)
            // taskPosition, start, end are null
        );

        // When: Index and retrieve
        IndexRequest<Map<String, Object>> indexReq = IndexRequest.of(i -> i
            .index(TASK_INDEX)
            .document(doc));

        client.index(indexReq);
        waitForRefresh();

        TaskExecution retrieved = taskStorage.get("wf-001:do/0/task-minimal");

        // Then: Available fields are populated
        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getId()).isEqualTo("wf-001:do/0/task-minimal");
        assertThat(retrieved.getTaskName()).isEqualTo("minimal-task");
        assertThat(retrieved.getTaskPosition()).isNull();
        assertThat(retrieved.getStart()).isNull();
    }
}
