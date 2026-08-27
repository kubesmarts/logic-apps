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
package org.kubesmarts.logic.dataindex.storage;

import java.time.ZonedDateTime;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.api.TaskExecutionStorage;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.TaskInstanceEntity;
import org.kubesmarts.logic.dataindex.storage.jpa.entity.WorkflowInstanceEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TaskExecutionStorage implementation.
 *
 * <p>Tests the PostgreSQL storage layer directly, focusing on:
 * <ul>
 *   <li>Composite key query logic (@EmbeddedId JPQL paths)
 *   <li>Derived ID parsing ("instanceId:taskPosition" format)
 *   <li>Invalid ID format handling
 *   <li>Query by workflow instance ID
 *   <li>Edge cases not covered by GraphQL tests
 * </ul>
 */
@QuarkusTest
public class TaskExecutionStorageIT {

    @Inject
    TaskExecutionStorage taskExecutionStorage;

    @Inject
    EntityManager em;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEST_WORKFLOW_ID = "test-workflow-123";
    private static final String TEST_TASK_POSITION_1 = "/do/0";
    private static final String TEST_TASK_POSITION_2 = "/do/1";

    @BeforeEach
    @Transactional
    public void setupTestData() throws Exception {
        // Create workflow first (FK constraint)
        WorkflowInstanceEntity workflow = new WorkflowInstanceEntity();
        workflow.setId(TEST_WORKFLOW_ID);
        workflow.setNamespace("test-ns");
        workflow.setName("test-workflow");
        workflow.setVersion("1.0");
        workflow.setStatus(WorkflowInstanceStatus.RUNNING);
        workflow.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        em.persist(workflow);

        // Create task 1 using convenience setters
        TaskInstanceEntity task1 = new TaskInstanceEntity();
        task1.setInstanceId(TEST_WORKFLOW_ID);
        task1.setTask(TEST_TASK_POSITION_1);
        task1.setTaskName("task1");
        task1.setStatus("COMPLETED");
        task1.setStartedAt(ZonedDateTime.now().minusMinutes(5));
        task1.setEndedAt(ZonedDateTime.now().minusMinutes(3));
        task1.setInput(MAPPER.readTree("{\"input\":\"data1\"}"));
        task1.setOutput(MAPPER.readTree("{\"output\":\"result1\"}"));
        em.persist(task1);

        // Create task 2 using convenience setters
        TaskInstanceEntity task2 = new TaskInstanceEntity();
        task2.setInstanceId(TEST_WORKFLOW_ID);
        task2.setTask(TEST_TASK_POSITION_2);
        task2.setTaskName("task2");
        task2.setStatus("RUNNING");
        task2.setStartedAt(ZonedDateTime.now().minusMinutes(3));
        task2.setInput(MAPPER.readTree("{\"input\":\"data2\"}"));
        em.persist(task2);

        em.flush();
    }

    @AfterEach
    @Transactional
    public void cleanupTestData() {
        em.createQuery("DELETE FROM TaskInstanceEntity").executeUpdate();
        em.createQuery("DELETE FROM WorkflowInstanceEntity").executeUpdate();
    }

    /**
     * Test get() with derived ID format.
     * Verifies JPQL query uses correct @EmbeddedId paths (t.id.instanceId, t.id.taskPosition).
     */
    @Test
    public void testGetByDerivedId() {
        String derivedId = TEST_WORKFLOW_ID + ":" + TEST_TASK_POSITION_1;

        TaskExecution task = taskExecutionStorage.get(derivedId);

        assertNotNull(task, "Task should be found");
        assertEquals(derivedId, task.getId(), "ID should match derived format");
        assertEquals(TEST_WORKFLOW_ID, task.getInstanceId(), "Instance ID should match");
        assertEquals(TEST_TASK_POSITION_1, task.getTask(), "Task should match");
        assertEquals("task1", task.getTaskName(), "Task name should match");
        assertEquals("COMPLETED", task.getStatus(), "Status should match");
    }

    /**
     * Test get() with non-existent ID.
     */
    @Test
    public void testGetByDerivedIdNotFound() {
        String derivedId = TEST_WORKFLOW_ID + ":/nonexistent/task";

        TaskExecution task = taskExecutionStorage.get(derivedId);

        assertNull(task, "Non-existent task should return null");
    }

    /**
     * Test get() with null ID.
     */
    @Test
    public void testGetWithNullId() {
        TaskExecution task = taskExecutionStorage.get(null);

        assertNull(task, "Null ID should return null");
    }

    /**
     * Test get() with empty ID.
     */
    @Test
    public void testGetWithEmptyId() {
        TaskExecution task = taskExecutionStorage.get("");

        assertNull(task, "Empty ID should return null");
    }

    /**
     * Test get() with invalid ID format (no colon separator).
     */
    @Test
    public void testGetWithInvalidIdFormatNoSeparator() {
        String invalidId = "invalid-id-without-separator";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> taskExecutionStorage.get(invalidId),
            "Should throw IllegalArgumentException for missing separator"
        );

        assertTrue(exception.getMessage().contains("Invalid task execution ID format"),
            "Error message should mention invalid format");
        assertTrue(exception.getMessage().contains("instanceId:taskPosition"),
            "Error message should show expected format");
    }

    /**
     * Test get() with invalid ID format (separator at start).
     */
    @Test
    public void testGetWithInvalidIdFormatSeparatorAtStart() {
        String invalidId = ":taskPosition";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> taskExecutionStorage.get(invalidId),
            "Should throw IllegalArgumentException for separator at start"
        );

        assertTrue(exception.getMessage().contains("Invalid task execution ID format"));
    }

    /**
     * Test get() with invalid ID format (separator at end).
     */
    @Test
    public void testGetWithInvalidIdFormatSeparatorAtEnd() {
        String invalidId = "instanceId:";

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> taskExecutionStorage.get(invalidId),
            "Should throw IllegalArgumentException for separator at end"
        );

        assertTrue(exception.getMessage().contains("Invalid task execution ID format"));
    }

    /**
     * Test findByWorkflowInstanceId().
     * Verifies JPQL query uses correct @EmbeddedId path (t.id.instanceId).
     */
    @Test
    public void testFindByWorkflowInstanceId() {
        List<TaskExecution> tasks = taskExecutionStorage.findByWorkflowInstanceId(TEST_WORKFLOW_ID);

        assertNotNull(tasks, "Result should not be null");
        assertEquals(2, tasks.size(), "Should find 2 tasks");

        // Verify both tasks belong to correct workflow
        tasks.forEach(task -> {
            assertEquals(TEST_WORKFLOW_ID, task.getInstanceId(),
                "All tasks should belong to test workflow");
            assertNotNull(task.getId(), "Task ID should be derived");
            assertTrue(task.getId().startsWith(TEST_WORKFLOW_ID + ":"),
                "Derived ID should start with workflow ID");
        });

        // Verify task names
        assertTrue(tasks.stream().anyMatch(t -> "task1".equals(t.getTaskName())),
            "Should contain task1");
        assertTrue(tasks.stream().anyMatch(t -> "task2".equals(t.getTaskName())),
            "Should contain task2");
    }

    /**
     * Test findByWorkflowInstanceId() with non-existent workflow.
     */
    @Test
    public void testFindByWorkflowInstanceIdNotFound() {
        List<TaskExecution> tasks = taskExecutionStorage.findByWorkflowInstanceId("nonexistent-workflow");

        assertNotNull(tasks, "Result should not be null");
        assertTrue(tasks.isEmpty(), "Should return empty list for non-existent workflow");
    }

    /**
     * Test derived ID format consistency.
     * Verifies that IDs returned from queries match the expected format.
     */
    @Test
    public void testDerivedIdFormatConsistency() {
        String expectedId1 = TEST_WORKFLOW_ID + ":" + TEST_TASK_POSITION_1;
        String expectedId2 = TEST_WORKFLOW_ID + ":" + TEST_TASK_POSITION_2;

        // Get via direct get()
        TaskExecution task1 = taskExecutionStorage.get(expectedId1);
        assertEquals(expectedId1, task1.getId(), "Direct get() should return correct derived ID");

        // Get via findByWorkflowInstanceId()
        List<TaskExecution> tasks = taskExecutionStorage.findByWorkflowInstanceId(TEST_WORKFLOW_ID);
        assertTrue(tasks.stream().anyMatch(t -> expectedId1.equals(t.getId())),
            "findByWorkflowInstanceId() should return task with correct derived ID");
        assertTrue(tasks.stream().anyMatch(t -> expectedId2.equals(t.getId())),
            "findByWorkflowInstanceId() should return task with correct derived ID");
    }

    /**
     * Test composite key uniqueness.
     * Verifies that (instanceId, taskPosition) uniquely identifies tasks.
     */
    @Test
    @Transactional
    public void testCompositeKeyUniqueness() throws Exception {
        // Same task position in different workflow should work
        String anotherWorkflowId = "another-workflow-456";

        WorkflowInstanceEntity workflow2 = new WorkflowInstanceEntity();
        workflow2.setId(anotherWorkflowId);
        workflow2.setNamespace("test-ns");
        workflow2.setName("another-workflow");
        workflow2.setVersion("1.0");
        workflow2.setStatus(WorkflowInstanceStatus.RUNNING);
        workflow2.setStartedAt(ZonedDateTime.now());
        em.persist(workflow2);

        TaskInstanceEntity task3 = new TaskInstanceEntity();
        task3.setInstanceId(anotherWorkflowId);
        task3.setTask(TEST_TASK_POSITION_1); // Same position as task1, different workflow
        task3.setTaskName("task3");
        task3.setStatus("RUNNING");
        task3.setStartedAt(ZonedDateTime.now());
        em.persist(task3);
        em.flush();

        // Should be able to retrieve both tasks with same position but different workflows
        String derivedId1 = TEST_WORKFLOW_ID + ":" + TEST_TASK_POSITION_1;
        String derivedId2 = anotherWorkflowId + ":" + TEST_TASK_POSITION_1;

        TaskExecution foundTask1 = taskExecutionStorage.get(derivedId1);
        TaskExecution foundTask2 = taskExecutionStorage.get(derivedId2);

        assertNotNull(foundTask1, "Task 1 should be found");
        assertNotNull(foundTask2, "Task 2 should be found");
        assertNotEquals(foundTask1.getId(), foundTask2.getId(), "IDs should be different");
        assertEquals("task1", foundTask1.getTaskName(), "Should get correct task from workflow 1");
        assertEquals("task3", foundTask2.getTaskName(), "Should get correct task from workflow 2");
    }
}
