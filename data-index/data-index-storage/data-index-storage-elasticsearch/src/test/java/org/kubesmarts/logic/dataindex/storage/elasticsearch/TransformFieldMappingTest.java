package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

/**
 * Test that verifies transform aggregations produce correct field types.
 * 
 * PROBLEM: terms aggregations create bucket structures like {"petstore": 1} 
 * instead of simple string values "petstore".
 * 
 * This causes GraphQL filters to fail because the name field isn't a string.
 */
@QuarkusTest
public class TransformFieldMappingTest {

    @Inject
    ElasticsearchClient client;

    @Inject
    ElasticsearchWorkflowInstanceStorage workflowStorage;

    private String testInstanceId;

    @BeforeEach
    public void setup() {
        testInstanceId = "test-field-mapping-" + UUID.randomUUID();
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
     * Test that reproduces the problem: name filter returns no results because
     * name field is stored as bucket structure {"petstore": 1} instead of string "petstore".
     */
    @Test
    public void testWorkflowNameFilterReturnsResults() throws Exception {
        // Given: a workflow event with name "test-workflow"
        String workflowName = "test-workflow-" + UUID.randomUUID().toString().substring(0, 8);
        long now = Instant.now().toEpochMilli();

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
                        "RUNNING",
                        now,
                        null))));

        // Force refresh to make event visible to transform
        client.indices().refresh(r -> r.index(indexName));

        // Manually trigger transform if needed
        try {
            client.transform().startTransform(s -> s.transformId("workflow-instances-transform"));
        } catch (Exception e) {
            System.out.println("Transform already running or error starting: " + e.getMessage());
        }

        // Wait for transform to process (transforms run every 1s)
        Thread.sleep(5000);

        // Debug: Check if raw event was indexed
        SearchResponse<Map> rawResponse = client.search(s -> s
                .index("workflow-events-*")
                .query(q -> q.matchAll(m -> m)),
                Map.class);
        System.out.println("Raw events count: " + rawResponse.hits().total().value());
        if (!rawResponse.hits().hits().isEmpty()) {
            System.out.println("Sample raw event: " + rawResponse.hits().hits().get(0).source());
        }

        // Debug: Check normalized index
        SearchResponse<Map> allNormalized = client.search(s -> s
                .index("workflow-instances")
                .query(q -> q.matchAll(m -> m)),
                Map.class);
        System.out.println("Normalized workflows count: " + allNormalized.hits().total().value());
        if (!allNormalized.hits().hits().isEmpty()) {
            System.out.println("Sample normalized: " + allNormalized.hits().hits().get(0).source());
        }

        // When: querying workflow-instances index directly by name field
        SearchResponse<Map> response = client.search(s -> s
                .index("workflow-instances")
                .query(q -> q
                        .term(t -> t
                                .field("name.keyword")
                                .value(workflowName))),
                Map.class);

        // Then: should return 1 result (FAILS with current transform because name is {"test-workflow": 1})
        List<Hit<Map>> hits = response.hits().hits();
        assertEquals(1, hits.size(),
                "Expected 1 workflow with name '" + workflowName + "', but got " + hits.size() +
                ". This indicates the transform is producing bucket structures instead of simple strings.");

        Map<String, Object> doc = hits.get(0).source();
        assertEquals(workflowName, doc.get("name"));
        assertEquals("1.0.0", doc.get("version"));
        assertEquals("org.acme", doc.get("namespace"));
    }

    /**
     * Simple DTO for workflow event document
     */
    static class WorkflowEventDoc {
        public String instanceId;
        public String workflowName;
        public String workflowVersion;
        public String workflowNamespace;
        public String eventType;
        public String instanceStatus;
        public Long startTime;
        public Long timestamp;
        public String atTimestamp;  // @timestamp field (Elasticsearch reserved field name)

        public WorkflowEventDoc(String instanceId, String workflowName, String version,
                String namespace, String eventType, String instanceStatus, Long startTime, Long timestamp) {
            this.instanceId = instanceId;
            this.workflowName = workflowName;
            this.workflowVersion = version;
            this.workflowNamespace = namespace;
            this.eventType = eventType;
            this.instanceStatus = instanceStatus;
            this.startTime = startTime;
            this.timestamp = timestamp != null ? timestamp : startTime;
            this.atTimestamp = Instant.now().toString();  // ISO 8601 format
        }

        @com.fasterxml.jackson.annotation.JsonProperty("@timestamp")
        public String getAtTimestamp() {
            return atTimestamp;
        }
    }
}
