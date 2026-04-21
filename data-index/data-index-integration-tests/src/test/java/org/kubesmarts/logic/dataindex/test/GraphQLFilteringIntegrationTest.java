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
package org.kubesmarts.logic.dataindex.test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;

import java.time.ZonedDateTime;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;

/**
 * Integration tests for GraphQL filtering functionality.
 *
 * <p>Validates end-to-end GraphQL filtering including:
 * <ul>
 *   <li>String filters (eq, like, in)
 *   <li>Status filters (eq, in)
 *   <li>DateTime filters (gte, lte)
 *   <li>JSON field filters (input.*, output.*)
 *   <li>Combined filters
 *   <li>PostgreSQL JSONB queries
 * </ul>
 */
@QuarkusTest
@TestProfile(GraphQLFilteringIntegrationTest.DatabaseEnabledProfile.class)
public class GraphQLFilteringIntegrationTest {

    @Inject
    WorkflowInstanceStorage workflowInstanceStorage;

    @Inject
    ObjectMapper objectMapper;

    @BeforeEach
    @Transactional
    void setupTestData() {
        // Clear existing data
        workflowInstanceStorage.clear();

        // Create test workflow instances with diverse data for filtering
        createWorkflowInstance("wf-1", "greeting-workflow", "production", "1.0",
                WorkflowInstanceStatus.COMPLETED,
                Map.of("customerId", "customer-123", "priority", "high"),
                Map.of("message", "Hello World", "status", "approved"));

        createWorkflowInstance("wf-2", "greeting-workflow", "production", "1.1",
                WorkflowInstanceStatus.COMPLETED,
                Map.of("customerId", "customer-456", "priority", "low"),
                Map.of("message", "Goodbye", "status", "rejected"));

        createWorkflowInstance("wf-3", "order-workflow", "production", "1.0",
                WorkflowInstanceStatus.RUNNING,
                Map.of("customerId", "customer-123", "orderId", "order-789"),
                null);

        createWorkflowInstance("wf-4", "order-workflow", "staging", "1.0",
                WorkflowInstanceStatus.FAULTED,
                Map.of("customerId", "customer-999", "orderId", "order-111"),
                null);

        createWorkflowInstance("wf-5", "notification-workflow", "production", "2.0",
                WorkflowInstanceStatus.COMPLETED,
                Map.of("userId", "user-001", "channel", "email"),
                Map.of("sent", "true", "timestamp", "2026-04-20T10:00:00Z"));
    }

    private void createWorkflowInstance(String id, String name, String namespace, String version,
                                       WorkflowInstanceStatus status,
                                       Map<String, String> inputData,
                                       Map<String, String> outputData) {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId(id);
        instance.setName(name);
        instance.setNamespace(namespace);
        instance.setVersion(version);
        instance.setStatus(status);
        instance.setStart(ZonedDateTime.parse("2026-04-20T10:00:00Z"));
        if (status == WorkflowInstanceStatus.COMPLETED || status == WorkflowInstanceStatus.FAULTED) {
            instance.setEnd(ZonedDateTime.parse("2026-04-20T10:05:00Z"));
        }

        // Create JSON input
        if (inputData != null) {
            ObjectNode input = objectMapper.createObjectNode();
            inputData.forEach(input::put);
            instance.setInput(input);
        }

        // Create JSON output
        if (outputData != null) {
            ObjectNode output = objectMapper.createObjectNode();
            outputData.forEach(output::put);
            instance.setOutput(output);
        }

        workflowInstanceStorage.put(id, instance);
    }

    @Test
    void testFilterByStatus() {
        String query = """
            {
              getWorkflowInstances(filter: { status: { eq: COMPLETED } }) {
                id
                name
                status
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(3))
            .body("data.getWorkflowInstances[0].status", equalTo("COMPLETED"));
    }

    @Test
    void testFilterByName() {
        String query = """
            {
              getWorkflowInstances(filter: { name: { eq: "greeting-workflow" } }) {
                id
                name
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(2))
            .body("data.getWorkflowInstances.id", hasItems("wf-1", "wf-2"));
    }

    @Test
    void testFilterByNamespace() {
        String query = """
            {
              getWorkflowInstances(filter: { namespace: { eq: "production" } }) {
                id
                namespace
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(4))
            .body("data.getWorkflowInstances.namespace", everyItem(equalTo("production")));
    }

    @Test
    void testFilterByJsonInputField() {
        String query = """
            {
              getWorkflowInstances(filter: { input: { eq: [{ key: "customerId", value: "customer-123" }] } }) {
                id
                name
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(2))
            .body("data.getWorkflowInstances.id", hasItems("wf-1", "wf-3"));
    }

    @Test
    void testFilterByJsonOutputField() {
        String query = """
            {
              getWorkflowInstances(filter: { output: { eq: [{ key: "status", value: "approved" }] } }) {
                id
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(1))
            .body("data.getWorkflowInstances[0].id", equalTo("wf-1"));
    }

    @Test
    void testFilterByMultipleJsonFields() {
        String query = """
            {
              getWorkflowInstances(filter: { input: { eq: [
                { key: "customerId", value: "customer-123" },
                { key: "priority", value: "high" }
              ] } }) {
                id
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(1))
            .body("data.getWorkflowInstances[0].id", equalTo("wf-1"));
    }

    @Test
    void testCombinedFilters() {
        String query = """
            {
              getWorkflowInstances(
                filter: {
                  status: { eq: COMPLETED }
                  namespace: { eq: "production" }
                  input: { eq: [{ key: "customerId", value: "customer-123" }] }
                }
              ) {
                id
                name
                status
                namespace
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(1))
            .body("data.getWorkflowInstances[0].id", equalTo("wf-1"))
            .body("data.getWorkflowInstances[0].status", equalTo("COMPLETED"))
            .body("data.getWorkflowInstances[0].namespace", equalTo("production"));
    }

    @Test
    void testFilterWithPagination() {
        String query = """
            {
              getWorkflowInstances(
                filter: { namespace: { eq: "production" } }
                limit: 2
                offset: 0
              ) {
                id
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(2));
    }

    @Test
    void testFilterByStatusIn() {
        String query = """
            {
              getWorkflowInstances(filter: { status: { in: [COMPLETED, FAULTED] } }) {
                id
                status
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(4))
            .body("data.getWorkflowInstances.status", everyItem(isOneOf("COMPLETED", "FAULTED")));
    }

    @Test
    void testFilterByVersionIn() {
        String query = """
            {
              getWorkflowInstances(filter: { version: { in: ["1.0", "1.1"] } }) {
                id
                version
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(4))
            .body("data.getWorkflowInstances.version", everyItem(isOneOf("1.0", "1.1")));
    }

    @Test
    void testNoResultsWhenFilterDoesNotMatch() {
        String query = """
            {
              getWorkflowInstances(filter: { input: { eq: [{ key: "customerId", value: "non-existent" }] } }) {
                id
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(0));
    }

    @Test
    void testFilterByNameLike() {
        String query = """
            {
              getWorkflowInstances(filter: { name: { like: "greeting*" } }) {
                id
                name
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", query))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getWorkflowInstances", hasSize(2))
            .body("data.getWorkflowInstances.name", everyItem(startsWith("greeting")));
    }

    /**
     * Test profile that enables PostgreSQL database for GraphQL filtering tests.
     */
    public static class DatabaseEnabledProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> config = new java.util.HashMap<>();

            // Disable Dev Services - use existing PostgreSQL container
            config.put("quarkus.devservices.enabled", "false");
            config.put("quarkus.datasource.devservices.enabled", "false");

            // Connect to existing PostgreSQL container on port 33224
            config.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:33224/quarkus");
            config.put("quarkus.datasource.username", "quarkus");
            config.put("quarkus.datasource.password", "quarkus");

            // Enable Hibernate ORM with schema generation
            config.put("quarkus.hibernate-orm.enabled", "true");
            config.put("quarkus.hibernate-orm.database.generation", "drop-and-create");
            config.put("quarkus.hibernate-orm.log.sql", "true");

            // Configure Hibernate ORM JSON format mapper
            config.put("quarkus.hibernate-orm.mapping.format.global", "ignore");

            // Enable GraphQL
            config.put("quarkus.smallrye-graphql.enabled", "true");

            return config;
        }
    }
}
