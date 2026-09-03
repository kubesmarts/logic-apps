package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.transform.PutTransformRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that verifies transform aggregations produce correct field types.
 * <p>
 * PROBLEM: terms aggregations create bucket structures like {"petstore": 1}
 * instead of simple string values "petstore".
 * <p>
 * This causes GraphQL filters to fail because the name field isn't a string.
 */
@QuarkusTest
public class TransformFieldMappingTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(TransformFieldMappingTest.class);

    @Inject
    ElasticsearchClient client;

    private String testInstanceId;

    @BeforeEach
    public void setup() throws IOException, InterruptedException {
        testInstanceId = "test-field-mapping-" + UUID.randomUUID();

        // IMPORTANT: Delete and recreate transforms to test latest transform definitions
        // The schema initializer only runs once at startup, so we manually apply transforms here
        deleteAndRecreateTransform("workflow-instances-transform");
        deleteAndRecreateTransform("task-executions-transform");

        // Wait for transforms to be ready
        Thread.sleep(2000);
    }

    /**
     * Deletes an existing transform and recreates it from the JSON definition file.
     * This ensures we're testing the actual transform logic from the resource files.
     */
    private void deleteAndRecreateTransform(String transformId) throws IOException {
        // Stop and delete existing transform
        try {
            client.transform().stopTransform(s -> s
                    .transformId(transformId)
                    .force(true)
                    .waitForCompletion(false)
                    .timeout(t -> t.time("5s")));
            Thread.sleep(500);
            client.transform().deleteTransform(d -> d.transformId(transformId));
            LOGGER.info("Deleted existing transform: {}", transformId);
        } catch (Exception e) {
            LOGGER.debug("Transform {} doesn't exist, will create new", transformId);
        }

        // Load transform definition from resource file
        String resourcePath = "/elasticsearch/transforms/" + transformId + ".json";
        String transformJson = new String(
                getClass().getResourceAsStream(resourcePath).readAllBytes(),
                StandardCharsets.UTF_8);

        // Replace placeholders (same logic as ElasticsearchSchemaInitializer)
        transformJson = transformJson.replace("{TIME_WINDOW}", "1h");

        // Create transform
        try (InputStream is = new ByteArrayInputStream(transformJson.getBytes(StandardCharsets.UTF_8))) {
            PutTransformRequest request = PutTransformRequest.of(builder -> builder
                    .transformId(transformId)
                    .withJson(is));
            client.transform().putTransform(request);
            LOGGER.info("Created transform: {}", transformId);
        }

        // Start transform
        client.transform().startTransform(r -> r.transformId(transformId));
        LOGGER.info("Started transform: {}", transformId);
    }

    @AfterEach
    public void cleanup() throws IOException {
        // Clean up test data
        try {
            client.deleteByQuery(d -> d
                    .index("workflow-events-*")
                    .query(q -> q.match(m -> m.field("instanceId").query(testInstanceId))));
            client.deleteByQuery(d -> d
                    .index("workflow-instances")
                    .query(q -> q.match(m -> m.field("id").query(testInstanceId))));
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    /**
     * Test that verifies transform produces correct field types and values.
     * <p>
     * Tests ALL critical fields:
     * - name, version, namespace (string values, not buckets)
     * - status (aggregated correctly from status field, not instanceStatus)
     * - startedAt (timestamp in milliseconds, not microseconds - year 2026 not 58644)
     */
    @Test
    public void testWorkflowTransformProducesCorrectFields() throws Exception {
        // Given: a workflow event with all fields
        String workflowName = "test-workflow-" + UUID.randomUUID().toString().substring(0, 8);
        long nowMillis = Instant.now().toEpochMilli();

        String indexName = "workflow-events-" + Instant.now().toString().substring(0, 10);
        client.index(IndexRequest.of(i -> i
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(new WorkflowEventDoc(
                        testInstanceId,
                        workflowName,
                        "1.0.0",
                        "org.acme",
                        "io.serverlessworkflow.workflow.started.v1",
                        "RUNNING",  // status field (NOT instanceStatus)
                        nowMillis,
                        nowMillis))));

        // Force refresh to make event visible to transform
        client.indices().refresh(r -> r.index(indexName));

        // Manually trigger transform if needed (usually already running)
        try {
            client.transform().startTransform(s -> s.transformId("workflow-instances-transform"));
        } catch (Exception e) {
            // Transform already running - this is fine
        }

        // Wait for transform to process (transforms run every 1s)
        Thread.sleep(5000);

        // Debug: Check raw event was indexed correctly
        SearchResponse<Map> rawCheck = client.search(s -> s
                        .index(indexName)
                        .query(q -> q.term(t -> t.field("instanceId.keyword").value(testInstanceId))),
                Map.class);
        LOGGER.debug("Raw event check - found: {}", rawCheck.hits().total().value());
        if (!rawCheck.hits().hits().isEmpty()) {
            Map<String, Object> rawDoc = rawCheck.hits().hits().get(0).source();
            LOGGER.debug("Raw event status field: {}", rawDoc.get("status"));
            LOGGER.debug("Full raw event: {}", rawDoc);
        }

        // When: querying workflow-instances index by ID
        SearchResponse<Map> response = client.search(s -> s
                        .index("workflow-instances")
                        .query(q -> q
                                .term(t -> t
                                        .field("id")
                                        .value(testInstanceId))),
                Map.class);

        // Debug: Show what transform produced
        LOGGER.debug("Normalized workflows found: {}", response.hits().total().value());
        if (!response.hits().hits().isEmpty()) {
            LOGGER.debug("Normalized document: {}", response.hits().hits().get(0).source());
        }

        // Then: verify all critical fields
        List<Hit<Map>> hits = response.hits().hits();
        assertEquals(1, hits.size(), "Expected 1 workflow with ID '" + testInstanceId + "'");

        Map<String, Object> doc = hits.get(0).source();

        // Verify string fields (not bucket structures)
        assertEquals(workflowName, doc.get("name"), "name field should be string value");
        assertEquals("1.0.0", doc.get("version"), "version field should be string value");
        assertEquals("org.acme", doc.get("namespace"), "namespace field should be string value");

        // Verify status field (bug fix: was null because transform looked for instanceStatus)
        assertEquals("RUNNING", doc.get("status"), "status field should be populated from status field");

        // Verify timestamps are in milliseconds (bug fix: was microseconds, year 58644)
        assertNotNull(doc.get("startedAt"), "startedAt should not be null");
        long startedAt = ((Number) doc.get("startedAt")).longValue();

        // Verify timestamp is reasonable (year 2020-2030, not year 58644)
        long year2020 = 1577836800000L; // 2020-01-01
        long year2030 = 1893456000000L; // 2030-01-01
        assertTrue(startedAt >= year2020 && startedAt <= year2030,
                "startedAt should be in milliseconds (year 2020-2030), but got: " + startedAt +
                        " which would be year " + Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneId.systemDefault()).getYear());
    }

    /**
     * Test that task transform produces correct fields, especially the task JSON Pointer.
     */
    @Test
    public void testTaskTransformProducesCorrectFields() throws Exception {
        // Given: a task event with all fields
        String taskName = "set-order-status";
        String taskPosition = "do/0/set-0";
        long nowMillis = Instant.now().toEpochMilli();

        String indexName = "task-events-" + Instant.now().toString().substring(0, 10);
        client.index(IndexRequest.of(i -> i
                .index(indexName)
                .id(UUID.randomUUID().toString())
                .document(new TaskEventDoc(
                        testInstanceId,
                        taskPosition,
                        taskName,
                        "io.serverlessworkflow.task.started.v1",
                        "RUNNING",
                        nowMillis,
                        nowMillis))));

        // Force refresh
        client.indices().refresh(r -> r.index(indexName));

        // Wait for transform
        Thread.sleep(5000);

        // When: querying task-executions index by composite ID
        String expectedTaskId = testInstanceId + ":" + taskPosition;
        SearchResponse<Map> response = client.search(s -> s
                        .index("task-executions")
                        .query(q -> q
                                .term(t -> t
                                        .field("id")
                                        .value(expectedTaskId))),
                Map.class);

        // Then: verify all critical fields
        List<Hit<Map>> hits = response.hits().hits();
        assertEquals(1, hits.size(), "Expected 1 task with ID '" + expectedTaskId + "'");

        Map<String, Object> doc = hits.get(0).source();

        // Verify task field (JSON Pointer) - bug fix: was null
        String expectedTaskPointer = "/" + taskPosition;
        assertEquals(expectedTaskPointer, doc.get("task"), "task field should be JSON Pointer");

        // Verify taskName and status
        assertEquals(taskName, doc.get("taskName"), "taskName should match");
        assertEquals("RUNNING", doc.get("status"), "status field should be populated");

        // Verify timestamps
        assertNotNull(doc.get("startedAt"), "startedAt should not be null");
        long startedAt = ((Number) doc.get("startedAt")).longValue();
        long year2020 = 1577836800000L;
        long year2030 = 1893456000000L;
        assertTrue(startedAt >= year2020 && startedAt <= year2030,
                "startedAt should be in milliseconds, got: " + startedAt);
    }

    /**
     * DTO for task event document.
     */
    static class TaskEventDoc {
        public String instanceId;
        public String taskPosition;
        public String taskName;
        public String eventType;
        public String status;
        public Long startTime;
        public Long timestamp;
        public String atTimestamp;

        public TaskEventDoc(String instanceId, String taskPosition, String taskName,
                            String eventType, String status, Long startTime, Long timestamp) {
            this.instanceId = instanceId;
            this.taskPosition = taskPosition;
            this.taskName = taskName;
            this.eventType = eventType;
            this.status = status;
            this.startTime = startTime;
            this.timestamp = timestamp != null ? timestamp : startTime;
            this.atTimestamp = Instant.now().toString();
        }

        @JsonProperty("@timestamp")
        public String getAtTimestamp() {
            return atTimestamp;
        }
    }

    /**
     * DTO for workflow event document.
     * <p>
     * Field names match what Quarkus Flow emits (and what Vector passes through):
     * - status (NOT instanceStatus) - the workflow status
     * - startTime - epoch milliseconds when workflow started
     * - timestamp - epoch milliseconds of the event
     * - @timestamp - ISO 8601 timestamp added by Vector for ES Transform smart filtering
     */
    static class WorkflowEventDoc {
        public String instanceId;
        public String workflowName;
        public String workflowVersion;
        public String workflowNamespace;
        public String eventType;
        public String status;  // IMPORTANT: "status" not "instanceStatus" (matches Quarkus Flow)
        public Long startTime;  // epoch milliseconds
        public Long timestamp;  // epoch milliseconds
        public String atTimestamp;  // @timestamp field added by Vector

        public WorkflowEventDoc(String instanceId, String workflowName, String version,
                                String namespace, String eventType, String status, Long startTime, Long timestamp) {
            this.instanceId = instanceId;
            this.workflowName = workflowName;
            this.workflowVersion = version;
            this.workflowNamespace = namespace;
            this.eventType = eventType;
            this.status = status;
            this.startTime = startTime;
            this.timestamp = timestamp != null ? timestamp : startTime;
            this.atTimestamp = Instant.now().toString();  // ISO 8601 format
        }

        @JsonProperty("@timestamp")
        public String getAtTimestamp() {
            return atTimestamp;
        }
    }
}
