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

@QuarkusTest
class JdbcBatchPollingTest {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcBatchPollingTestHelper helper;

    @AfterEach
    void cleanup() throws SQLException {
        helper.cleanup();
    }

    @Test
    void shouldNormalizeWorkflowEventAndDeleteStagingRow() throws SQLException {
        String instanceId = "jdbc-batch-wf-" + UUID.randomUUID();
        helper.insertWorkflowEventRaw(instanceId, "RUNNING", OffsetDateTime.now(), null, "test-namespace", "test-workflow", "1.0");

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
        helper.insertTaskEventRaw(instanceId, "/do/0", "step1", "RUNNING", OffsetDateTime.now(), null);

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

        helper.insertWorkflowEventRaw(badId, "NOT_A_REAL_STATUS", now, null, "ns", "wf", "1.0");
        helper.insertWorkflowEventRaw(goodId, "RUNNING", now, null, "ns", "wf", "1.0");

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
    void shouldStoreDatabaseErrorInWorkflowDlqFailureReason() throws SQLException {
        String badId = "jdbc-batch-db-error-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        // Jackson accepts \u0000 but jsonb rejects it, so the failure comes from the UPSERT.
        helper.insertWorkflowEventRaw(badId, "COMPLETED", null, now, "ns", "wf", "1.0", now, "{\"a\":\"\\u0000\"}");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT failure_reason FROM workflow_events_dlq WHERE instance_id = ?")) {
                        stmt.setString(1, badId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("failure_reason"))
                                .contains("unsupported Unicode escape sequence")
                                .doesNotContain("INSERT INTO");
                    }
                });
    }

    @Test
    void shouldStoreDatabaseErrorInTaskDlqFailureReason() throws SQLException {
        String badInstanceId = "jdbc-batch-task-db-error-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();

        helper.insertTaskEventRaw(badInstanceId, "/do/0", "step1", "RUNNING", now, null, "{\"a\":\"\\u0000\"}");

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    try (Connection conn = dataSource.getConnection();
                         PreparedStatement stmt = conn.prepareStatement(
                                 "SELECT failure_reason FROM task_events_dlq WHERE instance_id = ?")) {
                        stmt.setString(1, badInstanceId);
                        ResultSet rs = stmt.executeQuery();
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getString("failure_reason"))
                                .contains("unsupported Unicode escape sequence")
                                .doesNotContain("INSERT INTO");
                    }
                });
    }

    @Test
    void shouldHandleTaskEventRacingConcurrentWorkflowBatchForSameInstance() throws SQLException {

        String instanceId = "jdbc-batch-race-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();
        OffsetDateTime t1 = t0.plusSeconds(1);

        helper.insertTaskEventRaw(instanceId, "/do/0", "step1", "COMPLETED", t0, t1);
        helper.insertWorkflowEventRaw(instanceId, "RUNNING", t0, null, "race-ns", "race-wf", "1.0", t0, null);
        helper.insertWorkflowEventRaw(instanceId, "COMPLETED", null, t1, null, null, null, t1, "{\"r\":1}");

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
        helper.insertTaskEventRaw(badInstanceId, "/do/0", "step1", "RUNNING", now, null, "{not-valid-json");
        helper.insertTaskEventRaw(goodInstanceId, "/do/0", "step1", "RUNNING", now, null, null);

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
    void shouldKeepWorkflowEventStagedWhenDlqWriteFailsWithoutBlockingBatch() throws SQLException {
        String stuckId = JdbcBatchPollingTestHelper.DLQ_FAIL_PREFIX + UUID.randomUUID();
        String dlqId = "jdbc-batch-dlq-ok-" + UUID.randomUUID();
        String goodId = "jdbc-batch-dlq-good-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        helper.rejectDlqInserts("workflow_events_dlq");

        // One transaction so all three rows land in the same poll batch, stuck row first.
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            helper.insertWorkflowEventRaw(conn, stuckId, "NOT_A_REAL_STATUS", now, null, "ns", "wf", "1.0", now, null);
            helper.insertWorkflowEventRaw(conn, dlqId, "NOT_A_REAL_STATUS", now, null, "ns", "wf", "1.0", now, null);
            helper.insertWorkflowEventRaw(conn, goodId, "RUNNING", now, null, "ns", "wf", "1.0", now, null);
            conn.commit();
        }

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(helper.count("SELECT COUNT(*) FROM workflow_instances WHERE id = ?", goodId)).isEqualTo(1);
                    assertThat(helper.count("SELECT COUNT(*) FROM workflow_events_raw WHERE instance_id = ?", goodId)).isZero();
                    assertThat(helper.count("SELECT COUNT(*) FROM workflow_events_dlq WHERE instance_id = ?", dlqId)).isEqualTo(1);
                    assertThat(helper.count("SELECT COUNT(*) FROM workflow_events_raw WHERE instance_id = ?", dlqId)).isZero();
                });

        assertThat(helper.count("SELECT COUNT(*) FROM workflow_events_raw WHERE instance_id = ?", stuckId)).isEqualTo(1);
        assertThat(helper.count("SELECT COUNT(*) FROM workflow_events_dlq WHERE instance_id = ?", stuckId)).isZero();
    }

    @Test
    void shouldKeepTaskEventStagedWhenDlqWriteFailsWithoutBlockingBatch() throws SQLException {
        String stuckId = JdbcBatchPollingTestHelper.DLQ_FAIL_PREFIX + UUID.randomUUID();
        String dlqId = "jdbc-batch-task-dlq-ok-" + UUID.randomUUID();
        String goodId = "jdbc-batch-task-dlq-good-" + UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        helper.rejectDlqInserts("task_events_dlq");

        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            helper.insertTaskEventRaw(conn, stuckId, "/do/0", "step1", "RUNNING", now, null, "{not-valid-json");
            helper.insertTaskEventRaw(conn, dlqId, "/do/0", "step1", "RUNNING", now, null, "{not-valid-json");
            helper.insertTaskEventRaw(conn, goodId, "/do/0", "step1", "RUNNING", now, null, null);
            conn.commit();
        }

        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(helper.count("SELECT COUNT(*) FROM task_instances WHERE instance_id = ?", goodId)).isEqualTo(1);
                    assertThat(helper.count("SELECT COUNT(*) FROM task_events_raw WHERE instance_id = ?", goodId)).isZero();
                    assertThat(helper.count("SELECT COUNT(*) FROM task_events_dlq WHERE instance_id = ?", dlqId)).isEqualTo(1);
                    assertThat(helper.count("SELECT COUNT(*) FROM task_events_raw WHERE instance_id = ?", dlqId)).isZero();
                });

        assertThat(helper.count("SELECT COUNT(*) FROM task_events_raw WHERE instance_id = ?", stuckId)).isEqualTo(1);
        assertThat(helper.count("SELECT COUNT(*) FROM task_events_dlq WHERE instance_id = ?", stuckId)).isZero();
    }

    @Test
    void shouldMergeRepeatedWorkflowEventsIdempotently() throws SQLException {
        String instanceId = "jdbc-batch-merge-" + UUID.randomUUID();
        OffsetDateTime t0 = OffsetDateTime.now();

        // workflow.started-shaped event: immutable fields + RUNNING, no terminal data yet.
        helper.insertWorkflowEventRaw(instanceId, "RUNNING", t0, null, "merge-ns", "merge-wf", "2.0", t0, null);

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
        helper.insertWorkflowEventRaw(instanceId, "COMPLETED", null, t1, null, null, null, t1, "{\"ok\":true}");

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
}
