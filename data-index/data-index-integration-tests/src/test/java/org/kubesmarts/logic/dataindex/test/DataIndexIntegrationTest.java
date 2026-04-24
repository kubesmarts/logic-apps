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

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceStatus;

import io.quarkiverse.flow.Flow;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.serverlessworkflow.impl.WorkflowModel;
import io.smallrye.common.annotation.Identifier;

/**
 * End-to-end integration test for Data Index.
 *
 * <p>This test validates the complete data flow:
 * <ol>
 *   <li>Quarkus Flow executes workflow
 *   <li>Structured logging writes events to quarkus-flow-events.log
 *   <li>EventLogParser reads and parses JSON events
 *   <li>Events are converted to domain models (WorkflowInstance, TaskExecution)
 *   <li>Domain models are persisted to PostgreSQL via JPA
 *   <li>Data can be queried back from the database
 * </ol>
 *
 * <p>Note: This test uses JPA-generated schema (drop-and-create),
 * NOT manual SQL scripts. Schema is derived from JPA entities.
 */
@QuarkusTest
@TestProfile(DataIndexIntegrationTest.DatabaseEnabledProfile.class)
@QuarkusTestResource(HttpBinMockServer.class)
public class DataIndexIntegrationTest {

    private static final Path LOG_FILE = Path.of("target/quarkus-flow-events.log");

    @Inject
    @Identifier("test.TestHttpSuccess")
    Flow testHttpSuccess;

    @Inject
    WorkflowInstanceStorage workflowInstanceStorage;

    private EventLogParser eventLogParser;

    @BeforeEach
    void setup() {
        eventLogParser = new EventLogParser();
    }

    @Test
    @Transactional
    void shouldIngestWorkflowEventsIntoDatabase() throws Exception {
        // Given: workflow executes successfully
        io.serverlessworkflow.impl.WorkflowInstance instance = testHttpSuccess.instance();
        WorkflowModel result = instance.start().get(5, TimeUnit.SECONDS);

        String instanceId = instance.id();
        assertThat(result.asMap()).isPresent();

        // When: events are parsed from log file
        Map<String, WorkflowInstance> instances = eventLogParser.parseWorkflowInstances(LOG_FILE);

        // Then: workflow instance is present in parsed events
        assertThat(instances).containsKey(instanceId);
        WorkflowInstance workflowInstance = instances.get(instanceId);

        // And: instance has correct metadata from events
        assertThat(workflowInstance.getId()).isEqualTo(instanceId);
        assertThat(workflowInstance.getNamespace()).isEqualTo("test");
        assertThat(workflowInstance.getName()).isEqualTo("test-http-success");
        assertThat(workflowInstance.getVersion()).isEqualTo("1.0.0");
        assertThat(workflowInstance.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(workflowInstance.getStart()).isNotNull();
        assertThat(workflowInstance.getEnd()).isNotNull();

        // And: instance has input data
        assertThat(workflowInstance.getInput()).isNotNull();

        // And: instance has output data
        assertThat(workflowInstance.getOutput()).isNotNull();
        assertThat(workflowInstance.getOutput().get("message")).isNotNull();

        // And: instance has task executions
        assertThat(workflowInstance.getTaskExecutions())
                .as("Task executions should be parsed from events")
                .isNotEmpty()
                .anySatisfy(task -> {
                    assertThat(task.getId()).isNotNull();
                    assertThat(task.getTaskName()).isNotBlank();
                    assertThat(task.getTaskPosition()).startsWith("do/");
                    assertThat(task.getStart()).isNotNull();
                    assertThat(task.getEnd()).isNotNull();
                });

        // When: instance is persisted to database
        workflowInstanceStorage.put(instanceId, workflowInstance);

        // Then: instance can be queried back
        WorkflowInstance persisted = workflowInstanceStorage.get(instanceId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getId()).isEqualTo(instanceId);
        assertThat(persisted.getName()).isEqualTo("test-http-success");
        assertThat(persisted.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);

        // And: task executions are persisted with the instance
        assertThat(persisted.getTaskExecutions())
                .isNotEmpty()
                .hasSameSizeAs(workflowInstance.getTaskExecutions());
    }

    @Test
    @Transactional
    void shouldHandleMultipleWorkflowInstances() throws Exception {
        // Given: multiple workflow instances execute
        io.serverlessworkflow.impl.WorkflowInstance instance1 = testHttpSuccess.instance();
        io.serverlessworkflow.impl.WorkflowInstance instance2 = testHttpSuccess.instance();
        io.serverlessworkflow.impl.WorkflowInstance instance3 = testHttpSuccess.instance();

        instance1.start().get(5, TimeUnit.SECONDS);
        instance2.start().get(5, TimeUnit.SECONDS);
        instance3.start().get(5, TimeUnit.SECONDS);

        // When: events are parsed
        Map<String, WorkflowInstance> instances = eventLogParser.parseWorkflowInstances(LOG_FILE);

        // Then: all three instances are present
        assertThat(instances)
                .containsKeys(instance1.id(), instance2.id(), instance3.id());

        // When: all instances are persisted
        instances.forEach((id, wi) -> workflowInstanceStorage.put(id, wi));

        // Then: all instances can be queried back
        assertThat(workflowInstanceStorage.get(instance1.id())).isNotNull();
        assertThat(workflowInstanceStorage.get(instance2.id())).isNotNull();
        assertThat(workflowInstanceStorage.get(instance3.id())).isNotNull();

        // And: instances are independent (different IDs)
        assertThat(instance1.id()).isNotEqualTo(instance2.id());
        assertThat(instance2.id()).isNotEqualTo(instance3.id());
    }

    @Test
    @Transactional
    void shouldPersistWorkflowInputAndOutput() throws Exception {
        // Given: workflow with specific input
        Map<String, Object> input = Map.of("testKey", "testValue");
        io.serverlessworkflow.impl.WorkflowInstance instance = testHttpSuccess.instance(input);
        instance.start().get(5, TimeUnit.SECONDS);

        // When: events are parsed and persisted
        Map<String, WorkflowInstance> instances = eventLogParser.parseWorkflowInstances(LOG_FILE);
        WorkflowInstance workflowInstance = instances.get(instance.id());
        workflowInstanceStorage.put(instance.id(), workflowInstance);

        // Then: input is persisted in database
        WorkflowInstance persisted = workflowInstanceStorage.get(instance.id());
        assertThat(persisted.getInput()).isNotNull();
        assertThat(persisted.getInput().get("testKey").asText()).isEqualTo("testValue");

        // And: output is persisted
        assertThat(persisted.getOutput()).isNotNull();
        assertThat(persisted.getOutput().get("message")).isNotNull();
    }

    /**
     * Test profile that enables PostgreSQL database for integration tests.
     * Uses existing PostgreSQL container to avoid Testcontainers timeout issues.
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
            config.put("quarkus.hibernate-orm.log.sql", "false");

            // Keep structured logging enabled
            config.put("quarkus.flow.structured-logging.enabled", "true");
            config.put("quarkus.flow.structured-logging.events", "*");
            config.put("quarkus.flow.structured-logging.include-workflow-payloads", "true");

            return config;
        }
    }
}
