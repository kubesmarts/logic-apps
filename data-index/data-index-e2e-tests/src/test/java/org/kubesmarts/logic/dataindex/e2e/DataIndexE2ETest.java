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
package org.kubesmarts.logic.dataindex.e2e;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for Data Index - runs for all modes (MODE 1, MODE 2, MODE 3).
 *
 * Mode is selected via system property: -De2e.mode=mode1|mode2|mode3
 *
 * The same tests run for all modes, verifying:
 * - GraphQL schema
 * - Workflow queries
 * - Task queries
 * - Full lifecycle (trigger → ingestion → storage → GraphQL)
 *
 * Each mode has different ingestion paths but same GraphQL API:
 * - MODE 1: FluentBit → PostgreSQL triggers → GraphQL
 * - MODE 2: Vector → Elasticsearch transforms → GraphQL
 * - MODE 3: Kafka → Ingestion Service → PostgreSQL → GraphQL
 */
public class DataIndexE2ETest {

    protected static final Logger log = LoggerFactory.getLogger(DataIndexE2ETest.class);

    protected static String graphqlUrl;
    protected static String workflowUrl;
    protected static String mode;

    @BeforeAll
    public static void setupE2E() {
        // Read configuration from system properties (set by Maven)
        graphqlUrl = System.getProperty("e2e.graphql.url", "http://localhost:30080/graphql");
        workflowUrl = System.getProperty("e2e.workflow.url", "http://localhost:30082");
        mode = System.getProperty("e2e.mode", "mode1");

        log.info("E2E Test Configuration:");
        log.info("  GraphQL URL: {}", graphqlUrl);
        log.info("  Workflow URL: {}", workflowUrl);
        log.info("  Mode: {}", mode);

        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @BeforeEach
    public void setup() {
        waitForGraphQLReady();
    }

    // ========================================================================
    // E2E Tests (common for all modes)
    // ========================================================================

    @Test
    public void testGraphQLSchemaIntrospection() {
        log.info("Testing GraphQL schema introspection...");

        Response response = executeGraphQL("{ __schema { queryType { name } } }");

        response.then()
                .statusCode(200);

        String queryTypeName = response.jsonPath().getString("data.__schema.queryType.name");
        assertThat(queryTypeName).isEqualTo("Query");

        log.info("✓ GraphQL schema introspection successful");
    }

    @Test
    public void testQueryWorkflowInstances() {
        log.info("Testing getWorkflowInstances query...");

        String query = """
                {
                  getWorkflowInstances {
                    id
                    name
                    version
                    status
                  }
                }
                """;

        Response response = executeGraphQL(query);

        response.then()
                .statusCode(200);

        assertThat(response.jsonPath().getList("data.getWorkflowInstances"))
                .isNotNull();

        log.info("✓ getWorkflowInstances query successful");
    }

    @Test
    public void testQueryTaskExecutions() {
        log.info("Testing getTaskExecutions query...");

        String query = """
                {
                  getTaskExecutions {
                    id
                    task
                    status
                  }
                }
                """;

        Response response = executeGraphQL(query);

        response.then()
                .statusCode(200);

        assertThat(response.jsonPath().getList("data.getTaskExecutions"))
                .isNotNull();

        log.info("✓ getTaskExecutions query successful");
    }

    @Test
    public void testWorkflowLifecycle() {
        log.info("Testing full workflow lifecycle...");

        // Trigger workflow
        String instanceId = triggerWorkflow("hello-world");

        // Wait for event to be processed (mode-specific timing)
        waitForWorkflowInstance(instanceId);

        // Verify workflow instance data
        String query = String.format("""
                {
                  getWorkflowInstance(id: "%s") {
                    id
                    name
                    status
                    startedAt
                  }
                }
                """, instanceId);

        Response response = executeGraphQL(query);

        response.then()
                .statusCode(200);

        assertThat(response.jsonPath().getString("data.getWorkflowInstance.id"))
                .isEqualTo(instanceId);
        assertThat(response.jsonPath().getString("data.getWorkflowInstance.name"))
                .isNotEmpty();
        assertThat(response.jsonPath().getString("data.getWorkflowInstance.status"))
                .isIn("CREATED", "RUNNING", "COMPLETED");

        log.info("✓ Full workflow lifecycle verified");
    }

    // ========================================================================
    // Helper Methods
    // ========================================================================

    /**
     * Execute GraphQL query and return response.
     */
    protected Response executeGraphQL(String query) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of("query", query))
                .when()
                .post(graphqlUrl);
    }

    /**
     * Execute GraphQL query with variables.
     */
    protected Response executeGraphQL(String query, Map<String, Object> variables) {
        return given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "query", query,
                        "variables", variables
                ))
                .when()
                .post(graphqlUrl);
    }

    /**
     * Wait for GraphQL API to be available.
     */
    protected void waitForGraphQLReady() {
        log.info("Waiting for GraphQL API to be ready...");
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    try {
                        Response response = executeGraphQL("{ __schema { queryType { name } } }");
                        return response.statusCode() == 200;
                    } catch (Exception e) {
                        log.debug("GraphQL not ready yet: {}", e.getMessage());
                        return false;
                    }
                });
        log.info("GraphQL API is ready");
    }

    /**
     * Trigger a workflow execution via workflow test app.
     */
    protected String triggerWorkflow(String workflowName) {
        log.info("Triggering workflow: {}", workflowName);

        // Workflow test app endpoints: /test-workflows/{workflow-name}
        Response response = given()
                .contentType(ContentType.JSON)
                .body(Map.of("message", "test"))
                .when()
                .post(workflowUrl + "/test-workflows/" + workflowName);

        response.then().statusCode(200);

        // Extract workflow instance ID from response
        String instanceId = response.jsonPath().getString("id");
        if (instanceId == null) {
            // Try alternative field names
            instanceId = response.jsonPath().getString("instance.id");
        }

        log.info("Workflow triggered: {} (instanceId: {})", workflowName, instanceId);
        return instanceId;
    }

    /**
     * Wait for workflow instance to appear in GraphQL API.
     * MODE 2 needs extra time for Elasticsearch transform delay (1s frequency + buffer).
     */
    protected void waitForWorkflowInstance(String instanceId) {
        log.info("Waiting for workflow instance {} to appear...", instanceId);

        // All modes use same 30s timeout (sufficient for MODE 2 transforms)
        await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(2, TimeUnit.SECONDS)
                .until(() -> {
                    String query = String.format(
                            "{ getWorkflowInstance(id: \"%s\") { id name status } }",
                            instanceId
                    );
                    Response response = executeGraphQL(query);
                    if (response.statusCode() != 200) {
                        return false;
                    }
                    Object getWorkflowInstance = response.jsonPath().get("data.getWorkflowInstance");
                    return getWorkflowInstance != null;
                });
        log.info("Workflow instance {} found", instanceId);
    }
}
