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
package org.kubesmarts.logic.dataindex.ingestion;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the MODE 1 JDBC batch normalization path: insert typed rows into the
 * staging tables (as Vector does), let the workflow/task event
 * pollers pick them up, and assert the normalized tables are populated and the staging rows
 * are deleted.
 */
@QuarkusTest
class JdbcBatchPollingTest {

    @Inject
    DataSource dataSource;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DELETE FROM task_instances").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_instances").executeUpdate();
            conn.prepareStatement("DELETE FROM task_events_raw").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_events_raw").executeUpdate();
            conn.prepareStatement("DELETE FROM task_events_dlq").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_events_dlq").executeUpdate();
        }
    }

    @Test
    void shouldNormalizeWorkflowEventAndDeleteStagingRow() throws SQLException {
        String instanceId = "jdbc-batch-wf-" + UUID.randomUUID();
        insertWorkflowEventRaw(instanceId, "RUNNING", OffsetDateTime.now(), null, "test-namespace", "test-workflow", "1.0");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                    }
                });

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT COUNT(*) AS count FROM workflow_events_raw WHERE instance_id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt("count")).isZero();
                    }
                });
    }

    @Test
    void shouldNormalizeTaskEventArrivingBeforeItsWorkflow() throws SQLException {
        String instanceId = "jdbc-batch-task-first-" + UUID.randomUUID();
        insertTaskEventRaw(instanceId, "/do/0", "step1", "RUNNING", OffsetDateTime.now(), null);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM task_instances WHERE instance_id = ? AND task = ?")) {
                        stmt.setString(1, instanceId);
                        stmt.setString(2, "/do/0");
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                    }
                });

        // The FK-violation self-heal must have created a placeholder workflow row.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT id FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
        }
    }

    @Test
    void shouldMoveMalformedWorkflowEventToDlqWithoutBlockingOthers() throws SQLException {
        String badId = "jdbc-batch-bad-" + UUID.randomUUID();
        String goodId = "jdbc-batch-good-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        insertWorkflowEventRaw(badId, "NOT_A_REAL_STATUS", now, null, "ns", "wf", "1.0");
        insertWorkflowEventRaw(goodId, "RUNNING", now, null, "ns", "wf", "1.0");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, goodId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                    }
                });

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT failure_reason FROM workflow_events_dlq WHERE instance_id = ?")) {
                        stmt.setString(1, badId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("failure_reason")).isNotBlank();
                    }
                });

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) AS count FROM workflow_events_raw WHERE instance_id = ?")) {
            stmt.setString(1, badId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("count")).isZero();
        }
    }

    @Test
    void shouldHandleTaskEventRacingConcurrentWorkflowBatchForSameInstance() throws SQLException {
        // Ports PR #76's concurrency spot-check: a task event for an instance races a batch
        // of workflow events for that SAME instance, each normalized by an independently
        // scheduled poller (WorkflowEventPoller / TaskEventPoller) on its own connection -
        // the exact shape of race that used to produce "duplicate key value violates unique
        // constraint workflow_instances_pkey" before PR #76's self-targeting UPSERT fix.
        String instanceId = "jdbc-batch-race-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();
        OffsetDateTime t1 = t0.plusSeconds(1);

        insertTaskEventRaw(instanceId, "/do/0", "step1", "COMPLETED", t0, t1);
        insertWorkflowEventRaw(instanceId, "RUNNING", t0, null, "race-ns", "race-wf", "1.0", t0, null);
        insertWorkflowEventRaw(instanceId, "COMPLETED", null, t1, null, null, null, t1, "{\"r\":1}");

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT name, status, output FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                        assertThat(rs.getString("name")).isEqualTo("race-wf");
                        assertThat(rs.getString("output")).contains("\"r\"").contains("1");
                    }
                });

        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM task_instances WHERE instance_id = ? AND task = ?")) {
                        stmt.setString(1, instanceId);
                        stmt.setString(2, "/do/0");
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                    }
                });
    }

    @Test
    void shouldMoveMalformedTaskEventToDlqWithoutBlockingOthers() throws SQLException {
        String badInstanceId = "jdbc-batch-task-bad-" + UUID.randomUUID();
        String goodInstanceId = "jdbc-batch-task-good-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // Malformed input fails at TaskEventRowMapper's JSON parsing step, not at the
        // staging INSERT (input is a plain TEXT column, unvalidated there) - the same
        // "invalid JSON payload" failure mode MODE 3's own BatchProcessingIT exercises.
        insertTaskEventRaw(badInstanceId, "/do/0", "step1", "RUNNING", now, null, "{not-valid-json");
        insertTaskEventRaw(goodInstanceId, "/do/0", "step1", "RUNNING", now, null, null);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM task_instances WHERE instance_id = ? AND task = ?")) {
                        stmt.setString(1, goodInstanceId);
                        stmt.setString(2, "/do/0");
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                    }
                });

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT failure_reason FROM task_events_dlq WHERE instance_id = ?")) {
                        stmt.setString(1, badInstanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("failure_reason")).isNotBlank();
                    }
                });

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) AS count FROM task_events_raw WHERE instance_id = ?")) {
            stmt.setString(1, badInstanceId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("count")).isZero();
        }
    }

    @Test
    void shouldMergeRepeatedWorkflowEventsIdempotently() throws SQLException {
        String instanceId = "jdbc-batch-merge-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();

        // workflow.started-shaped event: immutable fields + RUNNING, no terminal data yet.
        insertWorkflowEventRaw(instanceId, "RUNNING", t0, null, "merge-ns", "merge-wf", "2.0", t0, null);

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT status FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("status")).isEqualTo("RUNNING");
                    }
                });

        // workflow.completed-shaped event: terminal data only, as real Quarkus Flow events
        // do - no namespace/name/version repeated on this one.
        OffsetDateTime t1 = t0.plusSeconds(5);
        insertWorkflowEventRaw(instanceId, "COMPLETED", null, t1, null, null, null, t1, "{\"ok\":true}");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT namespace, name, version, status, output FROM workflow_instances WHERE id = ?")) {
                        stmt.setString(1, instanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        // Immutable fields: first event wins, never overwritten by the second.
                        assertThat(rs.getString("namespace")).isEqualTo("merge-ns");
                        assertThat(rs.getString("name")).isEqualTo("merge-wf");
                        assertThat(rs.getString("version")).isEqualTo("2.0");
                        // Terminal fields: latest event wins.
                        assertThat(rs.getString("status")).isEqualTo("COMPLETED");
                        assertThat(rs.getString("output")).contains("ok").contains("true");
                    }
                });

        // Exactly one normalized row for this instance - the second event updated it
        // in place, it did not insert a duplicate.
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) AS count FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, instanceId);
            ResultSet rs = stmt.executeQuery();
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt("count")).isEqualTo(1);
        }
    }

    private void insertWorkflowEventRaw(String instanceId, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                         String namespace, String name, String version) throws SQLException {
        OffsetDateTime eventTimestamp = startedAt != null ? startedAt : (endedAt != null ? endedAt : OffsetDateTime.now());
        insertWorkflowEventRaw(instanceId, status, startedAt, endedAt, namespace, name, version, eventTimestamp, null);
    }

    private void insertWorkflowEventRaw(String instanceId, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                         String namespace, String name, String version, OffsetDateTime eventTimestamp,
                                         String output) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO workflow_events_raw (instance_id, namespace, name, version, status, started_at, ended_at, output, event_timestamp) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, instanceId);
            stmt.setString(2, namespace);
            stmt.setString(3, name);
            stmt.setString(4, version);
            stmt.setString(5, status);
            stmt.setObject(6, startedAt);
            stmt.setObject(7, endedAt);
            stmt.setString(8, output);
            stmt.setObject(9, eventTimestamp);
            stmt.executeUpdate();
        }
    }

    private void insertTaskEventRaw(String instanceId, String task, String taskName, String status,
                                     OffsetDateTime startedAt, OffsetDateTime endedAt) throws SQLException {
        insertTaskEventRaw(instanceId, task, taskName, status, startedAt, endedAt, null);
    }

    private void insertTaskEventRaw(String instanceId, String task, String taskName, String status,
                                     OffsetDateTime startedAt, OffsetDateTime endedAt, String input) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO task_events_raw (instance_id, task, task_name, status, started_at, ended_at, input, event_timestamp) " +
                             "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, instanceId);
            stmt.setString(2, task);
            stmt.setString(3, taskName);
            stmt.setString(4, status);
            stmt.setObject(5, startedAt);
            stmt.setObject(6, endedAt);
            stmt.setString(7, input);
            stmt.setObject(8, startedAt != null ? startedAt : OffsetDateTime.now());
            stmt.executeUpdate();
        }
    }
}
