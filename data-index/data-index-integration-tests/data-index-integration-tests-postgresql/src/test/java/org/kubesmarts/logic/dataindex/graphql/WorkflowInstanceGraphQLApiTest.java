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
package org.kubesmarts.logic.dataindex.graphql;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.ErrorEntity;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.TaskInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.WorkflowInstanceEntity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Integration tests for GraphQL API.
 *
 * <p>Tests the complete GraphQL API stack:
 * <ul>
 *   <li>SmallRye GraphQL endpoint
 *   <li>WorkflowInstanceGraphQLApi
 *   <li>JPA storage layer
 *   <li>PostgreSQL database with triggers
 * </ul>
 *
 * <p>These tests validate that:
 * <ul>
 *   <li>GraphQL queries execute successfully
 *   <li>WorkflowInstance and TaskExecution relationships work
 *   <li>All fields are mapped correctly
 *   <li>Filtering, sorting, and pagination work
 * </ul>
 */
@QuarkusTest
public class WorkflowInstanceGraphQLApiTest {

    @Inject
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_WORKFLOW_ID_1 = "test-workflow-instance-1";
    private static final String TEST_WORKFLOW_ID_2 = "test-workflow-instance-2";

    /**
     * Set up test data before each test.
     * Creates workflow instances and task executions in the database.
     */
    @BeforeEach
    @Transactional
    public void setupTestData() throws Exception {
        // Create test workflow instance 1 with tasks
        WorkflowInstanceEntity workflow1 = new WorkflowInstanceEntity();
        workflow1.setId(TEST_WORKFLOW_ID_1);
        workflow1.setNamespace("test-namespace");
        workflow1.setName("test-workflow");
        workflow1.setVersion("1.0.0");
        workflow1.setStatus(WorkflowInstanceStatus.COMPLETED);
        workflow1.setStartedAt(ZonedDateTime.now().minusMinutes(10));
        workflow1.setEndedAt(ZonedDateTime.now());

        JsonNode inputJson = MAPPER.readTree("{\"name\":\"John\",\"age\":30}");
        JsonNode outputJson = MAPPER.readTree("{\"result\":\"success\",\"processed\":true}");
        workflow1.setInput(inputJson);
        workflow1.setOutput(outputJson);

        // Create task executions for workflow 1
        List<TaskInstanceEntity> tasks1 = new ArrayList<>();

        TaskInstanceEntity task1 = new TaskInstanceEntity();
        task1.setInstanceId(TEST_WORKFLOW_ID_1);
        task1.setTaskName("validateInput");
        task1.setTask("/do/0");
        task1.setStatus("COMPLETED");
        task1.setStartedAt(ZonedDateTime.now().minusMinutes(10));
        task1.setEndedAt(ZonedDateTime.now().minusMinutes(9));
        task1.setInput(MAPPER.readTree("{\"input\":\"validate\"}"));
        task1.setOutput(MAPPER.readTree("{\"valid\":true}"));
        task1.setWorkflowInstance(workflow1);
        tasks1.add(task1);

        TaskInstanceEntity task2 = new TaskInstanceEntity();
        task2.setInstanceId(TEST_WORKFLOW_ID_1);
        task2.setTaskName("processData");
        task2.setTask("/do/1");
        task2.setStatus("COMPLETED");
        task2.setStartedAt(ZonedDateTime.now().minusMinutes(9));
        task2.setEndedAt(ZonedDateTime.now().minusMinutes(5));
        task2.setInput(MAPPER.readTree("{\"data\":\"process\"}"));
        task2.setOutput(MAPPER.readTree("{\"processed\":true}"));
        task2.setWorkflowInstance(workflow1);
        tasks1.add(task2);

        // Persist workflow first
        em.persist(workflow1);

        // Persist task instances separately (composite ID entities can't cascade)
        em.persist(task1);
        em.persist(task2);

        workflow1.setTaskExecutions(tasks1);

        // Create test workflow instance 2 with error
        WorkflowInstanceEntity workflow2 = new WorkflowInstanceEntity();
        workflow2.setId(TEST_WORKFLOW_ID_2);
        workflow2.setNamespace("test-namespace");
        workflow2.setName("test-workflow-failed");
        workflow2.setVersion("1.0.0");
        workflow2.setStatus(WorkflowInstanceStatus.FAULTED);
        workflow2.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        workflow2.setEndedAt(ZonedDateTime.now());
        workflow2.setInput(MAPPER.readTree("{\"name\":\"Jane\"}"));

        ErrorEntity wfError = new ErrorEntity();
        wfError.setType("communication");
        wfError.setStatus(500);
        wfError.setTitle("Internal Server Error");
        wfError.setInstance("/do/0/failingTask");
        wfError.setDetail("{\"code\":500,\"message\":\"There was an error processing your request\"}");
        workflow2.setError(wfError);

        List<TaskInstanceEntity> tasks2 = new ArrayList<>();

        TaskInstanceEntity task3 = new TaskInstanceEntity();
        task3.setInstanceId(TEST_WORKFLOW_ID_2);
        task3.setTaskName("failingTask");
        task3.setTask("/do/0");
        task3.setStatus("FAULTED");
        task3.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        task3.setEndedAt(ZonedDateTime.now());
        task3.setInput(MAPPER.readTree("{\"action\":\"fail\"}"));

        ErrorEntity taskError = new ErrorEntity();
        taskError.setType("communication");
        taskError.setStatus(500);
        taskError.setTitle("Internal Server Error");
        taskError.setDetail("{\"code\":500,\"message\":\"API call failed\"}");
        taskError.setInstance("/do/0/failingTask");
        task3.setError(taskError);

        task3.setWorkflowInstance(workflow2);
        tasks2.add(task3);

        // Persist workflow first
        em.persist(workflow2);

        // Persist task instance separately (composite ID entities can't cascade)
        em.persist(task3);

        workflow2.setTaskExecutions(tasks2);

        em.flush();
    }

    /**
     * Clean up test data after each test.
     */
    @AfterEach
    @Transactional
    public void cleanupTestData() {
        em.createQuery("DELETE FROM TaskInstanceEntity").executeUpdate();
        em.createQuery("DELETE FROM WorkflowInstanceEntity").executeUpdate();
    }

    /**
     * Test basic workflow instance query.
     * Validates that getWorkflowInstances returns data with correct fields.
     */
    @Test
    public void testGetWorkflowInstances() {
        String query = """
                {
                  getWorkflowInstances(limit: 10) {
                    id
                    namespace
                    name
                    status
                    startedAt
                    endedAt
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstances", notNullValue())
                .body("data.getWorkflowInstances[0].id", notNullValue())
                .body("data.getWorkflowInstances[0].namespace", notNullValue())
                .body("data.getWorkflowInstances[0].name", notNullValue())
                .body("data.getWorkflowInstances[0].status", notNullValue());
    }

    /**
     * Test workflow instance with task executions (relationship).
     * Validates that taskExecutions are loaded via @OneToMany relationship.
     */
    @Test
    public void testGetWorkflowInstancesWithTasks() {
        String query = """
                {
                  getWorkflowInstances(limit: 5) {
                    id
                    name
                    namespace
                    status
                    taskExecutions {
                      id
                      taskName
                      task
                      status
                      startedAt
                      endedAt
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstances", notNullValue())
                .body("data.getWorkflowInstances[0].taskExecutions", notNullValue());
    }

    /**
     * Test single workflow instance by ID.
     * Validates getWorkflowInstance(id) query.
     */
    @Test
    public void testGetWorkflowInstanceById() {
        String byIdQuery = """
                {
                  getWorkflowInstance(id: "%s") {
                    id
                    name
                    namespace
                    status
                    taskExecutions {
                      id
                      taskName
                      task
                      status
                    }
                  }
                }
                """.formatted(TEST_WORKFLOW_ID_1);

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + byIdQuery.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstance", notNullValue())
                .body("data.getWorkflowInstance.id", equalTo(TEST_WORKFLOW_ID_1))
                .body("data.getWorkflowInstance.name", equalTo("test-workflow"))
                .body("data.getWorkflowInstance.taskExecutions.size()", is(2));
    }

    /**
     * Test task executions query.
     * Validates that TaskExecution entities have all required fields.
     */
    @Test
    public void testGetTaskExecutions() {
        String query = """
                {
                  getTaskExecutions(limit: 10) {
                    id
                    taskName
                    task
                    status
                    startedAt
                    endedAt
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getTaskExecutions", notNullValue());
    }

    /**
     * Test task executions by workflow instance.
     * Validates getTaskExecutionsByWorkflowInstance query.
     */
    @Test
    public void testGetTaskExecutionsByWorkflowInstance() {
        String tasksQuery = """
                {
                  getTaskExecutionsByWorkflowInstance(workflowInstanceId: "%s") {
                    id
                    taskName
                    task
                    status
                  }
                }
                """.formatted(TEST_WORKFLOW_ID_1);

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + tasksQuery.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getTaskExecutionsByWorkflowInstance", notNullValue())
                .body("data.getTaskExecutionsByWorkflowInstance.size()", is(2))
                .body("data.getTaskExecutionsByWorkflowInstance[0].taskName", notNullValue())
                .body("data.getTaskExecutionsByWorkflowInstance[0].status", notNullValue());
    }

    /**
     * Test that input/output JSON fields are exposed correctly.
     * Validates that JSON scalar works for workflow and task input/output.
     */
    @Test
    public void testInputOutputJsonFields() {
        String query = """
                {
                  getWorkflowInstance(id: "%s") {
                    id
                    inputData
                    outputData
                    taskExecutions {
                      id
                      inputData
                      outputData
                    }
                  }
                }
                """.formatted(TEST_WORKFLOW_ID_1);

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstance.inputData", notNullValue())
                .body("data.getWorkflowInstance.outputData", notNullValue())
                .body("data.getWorkflowInstance.taskExecutions[0].inputData", notNullValue())
                .body("data.getWorkflowInstance.taskExecutions[0].outputData", notNullValue());
    }

    /**
     * Test GraphQL schema introspection.
     * Validates that the schema is accessible.
     */
    @Test
    public void testGraphQLSchemaIntrospection() {
        String introspectionQuery = """
                {
                  __schema {
                    types {
                      name
                    }
                  }
                }
                """;

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + introspectionQuery.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.__schema.types", notNullValue())
                .body("data.__schema.types.name", hasItems("WorkflowInstance", "TaskExecution"));
    }

    /**
     * Test workflow instance with error structure.
     * Validates that Error object is correctly exposed in GraphQL API with all fields.
     */
    @Test
    public void testWorkflowInstanceWithErrorStructure() {
        String query = """
                {
                  getWorkflowInstance(id: "%s") {
                    id
                    name
                    status
                    error {
                      type
                      title
                      detail
                      status
                      instance
                    }
                  }
                }
                """.formatted(TEST_WORKFLOW_ID_2);

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstance.id", equalTo(TEST_WORKFLOW_ID_2))
                .body("data.getWorkflowInstance.error.type", equalTo("communication"))
                .body("data.getWorkflowInstance.error.status", equalTo(500))
                .body("data.getWorkflowInstance.error.title", equalTo("Internal Server Error"))
                .body("data.getWorkflowInstance.error.instance", equalTo("/do/0/failingTask"))
                .body("data.getWorkflowInstance.error.detail", equalTo("{\"code\":500,\"message\":\"There was an error processing your request\"}"));
    }

    /**
     * Test task execution with error structure.
     * Validates that Error object is correctly exposed in TaskExecution GraphQL API.
     */
    @Test
    public void testTaskExecutionWithErrorStructure() {
        String query = """
                {
                  getWorkflowInstance(id: "%s") {
                    id
                    name
                    status
                    taskExecutions {
                      id
                      task
                      status
                      error {
                        type
                        title
                        detail
                        status
                        instance
                      }
                    }
                  }
                }
                """.formatted(TEST_WORKFLOW_ID_2);

        given()
                .contentType(ContentType.JSON)
                .body("{\"query\": \"" + query.replace("\n", " ").replace("\"", "\\\"") + "\"}")
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.getWorkflowInstance.taskExecutions[0].id", equalTo(TEST_WORKFLOW_ID_2 + ":/do/0"))
                .body("data.getWorkflowInstance.taskExecutions[0].error.type", equalTo("communication"))
                .body("data.getWorkflowInstance.taskExecutions[0].error.status", equalTo(500))
                .body("data.getWorkflowInstance.taskExecutions[0].error.title", equalTo("Internal Server Error"))
                .body("data.getWorkflowInstance.taskExecutions[0].error.instance", equalTo("/do/0/failingTask"))
                .body("data.getWorkflowInstance.taskExecutions[0].error.detail", equalTo("{\"code\":500,\"message\":\"API call failed\"}"));
    }

    /**
     * Test task execution ordering by enter/exit (mapped to startedAt/endedAt entity fields).
     * Validates OrderByConverter correctly maps GraphQL enter→startedAt and exit→endedAt.
     */
    @Test
    public void testTaskExecutionOrdering() {
        // Test ordering by enter (maps to startedAt field)
        String queryByEnter = """
            {
              getTaskExecutions(limit: 10, orderBy: { enter: ASC }) {
                id
                taskName
                startedAt
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", queryByEnter))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", greaterThan(0));

        // Test ordering by exit (maps to endedAt field)
        String queryByExit = """
            {
              getTaskExecutions(limit: 10, orderBy: { exit: DESC }) {
                id
                taskName
                endedAt
              }
            }
            """;

        given()
            .contentType(ContentType.JSON)
            .body(Map.of("query", queryByExit))
        .when()
            .post("/graphql")
        .then()
            .statusCode(200)
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", greaterThan(0));
    }

    /**
     * Test error filtering in GraphQL queries.
     * Validates that ErrorFilter works correctly for both workflow instances and task executions.
     */
    @Test
    void testErrorFiltering() {
        String query = """
            {
              workflowsByError: getWorkflowInstances(
                filter: {
                  error: {
                    type: { eq: "communication" }
                    status: { gte: 500 }
                  }
                }
              ) {
                id
                name
                error { type, status, title }
              }

              tasksByError: getTaskExecutions(
                filter: {
                  error: {
                    status: { eq: 500 }
                    instance: { eq: "/do/0/failingTask" }
                  }
                }
              ) {
                id
                task
                error { status, instance, type }
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
            .body("data.workflowsByError.size()", greaterThan(0))
            .body("data.workflowsByError[0].error.type", equalTo("communication"))
            .body("data.workflowsByError[0].error.status", equalTo(500))
            .body("data.tasksByError.size()", greaterThan(0))
            .body("data.tasksByError[0].error.status", equalTo(500))
            .body("data.tasksByError[0].error.instance", equalTo("/do/0/failingTask"));
    }
}
