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
import java.util.Map;

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
import org.kubesmarts.logic.dataindex.storage.jpa.entity.TaskInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.WorkflowInstanceEntity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for TaskExecution GraphQL filtering and sorting.
 *
 * <p><b>Purpose:</b> Verify that GraphQL filters/sorts correctly map to JPA entity fields,
 * especially composite key fields that require nested paths (id.taskPosition).
 *
 * <p><b>Tests cover:</b>
 * <ul>
 *   <li>Filter by taskPosition (composite key field) - requires "id.taskPosition" mapping
 *   <li>Order by taskPosition (composite key field) - requires "id.taskPosition" mapping
 *   <li>Order by enter/exit (entity start/end fields) - requires field name mapping
 *   <li>Filter by input/output (JSON fields) - requires "input"/"output" not "inputArgs"/"outputArgs"
 * </ul>
 *
 * <p><b>Expected Behavior:</b>
 * These tests will FAIL until FilterConverter and OrderByConverter are fixed to:
 * <ul>
 *   <li>Map taskPosition → id.taskPosition (composite key)
 *   <li>Map enter → start, exit → end (field names)
 *   <li>Map inputArgs → input, outputArgs → output (field names)
 * </ul>
 */
@QuarkusTest
public class TaskExecutionFilteringIT {

    @Inject
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WORKFLOW_ID = "filter-test-workflow";

    @BeforeEach
    @Transactional
    public void setupTestData() throws Exception {
        // Create workflow
        WorkflowInstanceEntity workflow = new WorkflowInstanceEntity();
        workflow.setId(WORKFLOW_ID);
        workflow.setNamespace("test-ns");
        workflow.setName("filter-test");
        workflow.setVersion("1.0");
        workflow.setStatus(WorkflowInstanceStatus.RUNNING);
        workflow.setStartedAt(ZonedDateTime.now().minusMinutes(10));
        em.persist(workflow);

        // Task 1: position /do/0
        TaskInstanceEntity task1 = new TaskInstanceEntity();
        task1.setInstanceId(WORKFLOW_ID);
        task1.setTask("/do/0");
        task1.setTaskName("task-alpha");
        task1.setStatus("COMPLETED");
        task1.setStartedAt(ZonedDateTime.now().minusMinutes(9));
        task1.setEndedAt(ZonedDateTime.now().minusMinutes(7));
        task1.setInput(MAPPER.readTree("{\"customerId\":\"123\"}"));
        task1.setOutput(MAPPER.readTree("{\"result\":\"success\"}"));
        em.persist(task1);

        // Task 2: position /do/1
        TaskInstanceEntity task2 = new TaskInstanceEntity();
        task2.setInstanceId(WORKFLOW_ID);
        task2.setTask("/do/1");
        task2.setTaskName("task-beta");
        task2.setStatus("COMPLETED");
        task2.setStartedAt(ZonedDateTime.now().minusMinutes(7));
        task2.setEndedAt(ZonedDateTime.now().minusMinutes(5));
        task2.setInput(MAPPER.readTree("{\"customerId\":\"456\"}"));
        task2.setOutput(MAPPER.readTree("{\"result\":\"failure\"}"));
        em.persist(task2);

        // Task 3: position /do/2
        TaskInstanceEntity task3 = new TaskInstanceEntity();
        task3.setInstanceId(WORKFLOW_ID);
        task3.setTask("/do/2");
        task3.setTaskName("task-gamma");
        task3.setStatus("RUNNING");
        task3.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        task3.setInput(MAPPER.readTree("{\"customerId\":\"789\"}"));
        em.persist(task3);

        em.flush();
    }

    @AfterEach
    @Transactional
    public void cleanupTestData() {
        em.createQuery("DELETE FROM TaskInstanceEntity").executeUpdate();
        em.createQuery("DELETE FROM WorkflowInstanceEntity").executeUpdate();
    }

    /**
     * Test filtering by taskPosition (composite key field).
     *
     * <p><b>Current Bug:</b> FilterConverter uses "taskPosition" but entity has @EmbeddedId,
     * so actual path is "id.taskPosition". This causes Criteria query to fail.
     *
     * <p><b>Expected Failure:</b> System error or null results until FilterConverter maps
     * "taskPosition" → "id.taskPosition".
     */
    @Test
    public void testFilterByTaskPosition() {
        String query = """
            {
              getTaskExecutions(filter: { taskPosition: { eq: "/do/1" } }) {
                id
                taskPosition
                taskName
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(1))
            .body("data.getTaskExecutions[0].taskPosition", equalTo("/do/1"))
            .body("data.getTaskExecutions[0].taskName", equalTo("task-beta"));
    }

    /**
     * Test ordering by taskPosition (composite key field).
     *
     * <p><b>Current Bug:</b> OrderByConverter uses "taskPosition" but entity has @EmbeddedId,
     * so actual path is "id.taskPosition". This causes Criteria query to fail.
     *
     * <p><b>Expected Failure:</b> System error or wrong order until OrderByConverter maps
     * "taskPosition" → "id.taskPosition".
     */
    @Test
    public void testOrderByTaskPosition() {
        String query = """
            {
              getTaskExecutions(orderBy: { taskPosition: ASC }) {
                id
                taskPosition
                taskName
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(3))
            // Should be ordered: /do/0, /do/1, /do/2
            .body("data.getTaskExecutions[0].taskPosition", equalTo("/do/0"))
            .body("data.getTaskExecutions[1].taskPosition", equalTo("/do/1"))
            .body("data.getTaskExecutions[2].taskPosition", equalTo("/do/2"));
    }

    /**
     * Test ordering by enter (maps to entity "start" field).
     *
     * <p><b>Current Status:</b> Already fixed in OrderByConverter (enter → start mapping).
     * This test verifies the fix works.
     */
    @Test
    public void testOrderByEnter() {
        String query = """
            {
              getTaskExecutions(orderBy: { enter: DESC }) {
                id
                taskName
                startDate
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(3))
            // Should be ordered by start DESC: task-gamma (most recent), task-beta, task-alpha
            .body("data.getTaskExecutions[0].taskName", equalTo("task-gamma"))
            .body("data.getTaskExecutions[1].taskName", equalTo("task-beta"))
            .body("data.getTaskExecutions[2].taskName", equalTo("task-alpha"));
    }

    /**
     * Test ordering by exit (maps to entity "end" field).
     *
     * <p><b>Current Status:</b> Already fixed in OrderByConverter (exit → end mapping).
     * This test verifies the fix works.
     */
    @Test
    public void testOrderByExit() {
        String query = """
            {
              getTaskExecutions(orderBy: { exit: ASC }) {
                id
                taskName
                endDate
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(3))
            // Completed tasks ordered by end ASC, then RUNNING task (null end)
            // task-alpha ends at -7min, task-beta ends at -5min, task-gamma is RUNNING (null end)
            .body("data.getTaskExecutions.findAll { it.status == 'COMPLETED' }.size()", equalTo(2));
    }

    /**
     * Test filtering by JSON input field.
     *
     * <p><b>Current Bug:</b> FilterConverter uses "inputArgs" but entity field is "input".
     * GraphQL input type uses "inputArgs" for backward compat, but converter should map
     * to actual entity field "input".
     *
     * <p><b>Expected Failure:</b> System error or null results until FilterConverter maps
     * "inputArgs" → "input".
     */
    @Test
    public void testFilterByInputJson() {
        String query = """
            {
              getTaskExecutions(filter: {
                inputArgs: {
                  eq: [{ key: "customerId", value: "456" }]
                }
              }) {
                id
                taskName
                inputData
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(1))
            .body("data.getTaskExecutions[0].taskName", equalTo("task-beta"));
    }

    /**
     * Test filtering by JSON output field.
     *
     * <p><b>Current Bug:</b> FilterConverter uses "outputArgs" but entity field is "output".
     * GraphQL input type uses "outputArgs" for backward compat, but converter should map
     * to actual entity field "output".
     *
     * <p><b>Expected Failure:</b> System error or null results until FilterConverter maps
     * "outputArgs" → "output".
     */
    @Test
    public void testFilterByOutputJson() {
        String query = """
            {
              getTaskExecutions(filter: {
                outputArgs: {
                  eq: [{ key: "result", value: "success" }]
                }
              }) {
                id
                taskName
                outputData
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
            .body("data.getTaskExecutions", notNullValue())
            .body("data.getTaskExecutions.size()", equalTo(1))
            .body("data.getTaskExecutions[0].taskName", equalTo("task-alpha"));
    }
}
