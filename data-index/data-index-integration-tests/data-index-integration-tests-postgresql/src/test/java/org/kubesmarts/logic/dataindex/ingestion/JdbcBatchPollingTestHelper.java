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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

@ApplicationScoped
public class JdbcBatchPollingTestHelper {

    public static final String DLQ_FAIL_PREFIX = "jdbc-batch-dlq-fail-";

    @Inject
    DataSource dataSource;

    public void cleanup() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("DROP TRIGGER IF EXISTS reject_dlq_insert ON workflow_events_dlq").executeUpdate();
            conn.prepareStatement("DROP TRIGGER IF EXISTS reject_dlq_insert ON task_events_dlq").executeUpdate();
            conn.prepareStatement("DROP FUNCTION IF EXISTS reject_dlq_insert()").executeUpdate();
            conn.prepareStatement("DELETE FROM task_instances").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_instances").executeUpdate();
            conn.prepareStatement("DELETE FROM task_events_raw").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_events_raw").executeUpdate();
            conn.prepareStatement("DELETE FROM task_events_dlq").executeUpdate();
            conn.prepareStatement("DELETE FROM workflow_events_dlq").executeUpdate();
        }
    }

    // Simulates a DLQ write failure for rows whose instance_id starts with DLQ_FAIL_PREFIX.
    public void rejectDlqInserts(String dlqTable) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            conn.prepareStatement("""
                    CREATE OR REPLACE FUNCTION reject_dlq_insert() RETURNS trigger AS $$
                    BEGIN
                      IF NEW.instance_id LIKE '%s%%' THEN
                        RAISE EXCEPTION 'simulated DLQ write failure';
                      END IF;
                      RETURN NEW;
                    END;
                    $$ LANGUAGE plpgsql
                    """.formatted(DLQ_FAIL_PREFIX)).executeUpdate();
            conn.prepareStatement("CREATE TRIGGER reject_dlq_insert BEFORE INSERT ON " + dlqTable
                    + " FOR EACH ROW EXECUTE FUNCTION reject_dlq_insert()").executeUpdate();
        }
    }

    public int count(String sql, String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void insertWorkflowEventRaw(String instanceId, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                       String namespace, String name, String version) throws SQLException {
        OffsetDateTime eventTimestamp = startedAt != null ? startedAt : (endedAt != null ? endedAt : OffsetDateTime.now());
        insertWorkflowEventRaw(instanceId, status, startedAt, endedAt, namespace, name, version, eventTimestamp, null);
    }

    public void insertWorkflowEventRaw(String instanceId, String status, OffsetDateTime startedAt, OffsetDateTime endedAt,
                                       String namespace, String name, String version, OffsetDateTime eventTimestamp,
                                       String output) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertWorkflowEventRaw(conn, instanceId, status, startedAt, endedAt, namespace, name, version, eventTimestamp, output);
        }
    }

    public void insertWorkflowEventRaw(Connection conn, String instanceId, String status, OffsetDateTime startedAt,
                                       OffsetDateTime endedAt, String namespace, String name, String version,
                                       OffsetDateTime eventTimestamp, String output) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
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

    public void insertTaskEventRaw(String instanceId, String task, String taskName, String status,
                                   OffsetDateTime startedAt, OffsetDateTime endedAt) throws SQLException {
        insertTaskEventRaw(instanceId, task, taskName, status, startedAt, endedAt, null);
    }

    public void insertTaskEventRaw(String instanceId, String task, String taskName, String status,
                                   OffsetDateTime startedAt, OffsetDateTime endedAt, String input) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            insertTaskEventRaw(conn, instanceId, task, taskName, status, startedAt, endedAt, input);
        }
    }

    public void insertTaskEventRaw(Connection conn, String instanceId, String task, String taskName, String status,
                                   OffsetDateTime startedAt, OffsetDateTime endedAt, String input) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(
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
