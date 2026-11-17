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
package org.kubesmarts.logic.dataindex.service.postgresql.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kubesmarts.logic.dataindex.ingestion.persistence.TaskExecutionProcessor;
import org.kubesmarts.logic.dataindex.ingestion.persistence.jdbc.LoadSQL;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Polls {@code task_events_raw} on a schedule, independently of {@link WorkflowEventPoller} -
 * mirroring Vector's two independent sinks (postgres_workflow / postgres_task) from PR #76.
 * A task row that arrives before its workflow self-heals via {@code TaskPersistence}'s
 * existing placeholder-insert fallback - no special handling needed here for that case.
 */
@ApplicationScoped
public class TaskEventPoller {

    private static final Logger log = LoggerFactory.getLogger(TaskEventPoller.class);

    private String selectBatchSql;
    private String deleteBatchSql;
    private String deleteSingleSql;
    private String insertDlqSql;

    final DataSource dataSource;
    final TaskExecutionProcessor taskExecutionProcessor;
    final ObjectMapper objectMapper;
    final MeterRegistry meterRegistry;

    private Counter processedCounter;
    private Counter dlqCounter;
    private Counter dlqWriteFailedCounter;

    @ConfigProperty(name = "data-index.ingestion.jdbc-batch.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "data-index.ingestion.jdbc-batch.batch-size", defaultValue = "10000")
    int batchSize;

    @Inject
    public TaskEventPoller(DataSource dataSource, TaskExecutionProcessor taskExecutionProcessor, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.taskExecutionProcessor = taskExecutionProcessor;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        selectBatchSql = LoadSQL.load("/sql/task-events-select-batch.sql");
        deleteBatchSql = LoadSQL.load("/sql/task-events-delete-batch.sql");
        deleteSingleSql = LoadSQL.load("/sql/task-events-delete-single.sql");
        insertDlqSql = LoadSQL.load("/sql/task-events-insert-dlq.sql");
        processedCounter = meterRegistry.counter("data_index.jdbc_batch.events.processed", "type", "task");
        dlqCounter = meterRegistry.counter("data_index.jdbc_batch.events.dlq", "type", "task");
        dlqWriteFailedCounter = meterRegistry.counter("data_index.jdbc_batch.dlq.write.failed", "type", "task");
    }

    @Scheduled(every = "{data-index.ingestion.jdbc-batch.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!enabled) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<TaskEventRow> rows = selectBatch(conn);
                if (!rows.isEmpty()) {
                    processBatch(conn, rows);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                log.error("Task event poll failed; staging rows remain for retry on the next poll", e);
            }
        } catch (SQLException e) {
            log.error("Failed to obtain a connection for the task event poll", e);
        }
    }

    private void processBatch(Connection conn, List<TaskEventRow> rows) {
        try {
            List<TaskExecution> events = rows.stream()
                    .map(row -> TaskEventRowMapper.toTaskExecution(row, objectMapper))
                    .toList();
            taskExecutionProcessor.processBatch(events);
        } catch (Exception batchFailure) {
            log.warn("Task batch of {} events failed; falling back to per-record processing", rows.size(), batchFailure);
            processPerRecordWithDlqFallback(conn, rows);
            return;
        }
        deleteNormalized(conn, rows);
    }

    private void processPerRecordWithDlqFallback(Connection conn, List<TaskEventRow> rows) {
        List<TaskEventRow> normalized = new ArrayList<>();
        for (TaskEventRow row : rows) {
            try {
                TaskExecution event = TaskEventRowMapper.toTaskExecution(row, objectMapper);
                taskExecutionProcessor.process(event);
                normalized.add(row);
            } catch (Exception singleFailure) {
                log.error("Task event id={} instanceId={} task={} failed; moving to DLQ",
                        row.id(), row.instanceId(), row.task(), singleFailure);
                try {
                    SavepointHelper.run(conn, () -> moveToDlq(conn, row, FailureReason.describe(singleFailure)));
                    dlqCounter.increment();
                } catch (SQLException dlqFailure) {
                    // Leave the row in place, it's retried next poll.
                    log.error("Task event id={} instanceId={} task={} could not be moved to DLQ; it remains staged for retry",
                            row.id(), row.instanceId(), row.task(), dlqFailure);
                    dlqWriteFailedCounter.increment();
                }
            }
        }
        // One delete for all normalized rows keeps savepoints (PostgreSQL subtransactions) below the 64 cached per transaction.
        deleteNormalized(conn, normalized);
    }

    private void deleteNormalized(Connection conn, List<TaskEventRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            SavepointHelper.run(conn, () -> deleteBatch(conn, rows));
            processedCounter.increment(rows.size());
        } catch (SQLException deleteFailure) {
            // Normalization is idempotent, so replaying these rows next poll is safe.
            log.error("{} task events were normalized but could not be deleted; they will be replayed", rows.size(), deleteFailure);
        }
    }

    private List<TaskEventRow> selectBatch(Connection conn) throws SQLException {
        List<TaskEventRow> rows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(selectBatchSql)) {
            stmt.setInt(1, batchSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new TaskEventRow(
                            rs.getLong("id"),
                            rs.getString("instance_id"),
                            rs.getString("task_name"),
                            rs.getString("task"),
                            rs.getString("status"),
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("ended_at", OffsetDateTime.class),
                            rs.getString("input"),
                            rs.getString("output"),
                            rs.getString("error_type"),
                            rs.getString("error_title"),
                            rs.getString("error_detail"),
                            rs.getObject("error_status", Integer.class),
                            rs.getString("error_instance"),
                            rs.getObject("event_timestamp", OffsetDateTime.class)));
                }
            }
        }
        return rows;
    }

    private void deleteBatch(Connection conn, List<TaskEventRow> rows) throws SQLException {
        Long[] ids = rows.stream().map(TaskEventRow::id).toArray(Long[]::new);
        try (PreparedStatement stmt = conn.prepareStatement(deleteBatchSql)) {
            Array sqlArray = conn.createArrayOf("bigint", ids);
            stmt.setArray(1, sqlArray);
            stmt.executeUpdate();
        }
    }

    private void deleteSingle(Connection conn, long id) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(deleteSingleSql)) {
            stmt.setLong(1, id);
            stmt.executeUpdate();
        }
    }

    private void moveToDlq(Connection conn, TaskEventRow row, String failureReason) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(insertDlqSql)) {
            stmt.setString(1, row.instanceId());
            stmt.setString(2, row.taskName());
            stmt.setString(3, row.task());
            stmt.setString(4, row.status());
            stmt.setObject(5, row.startedAt());
            stmt.setObject(6, row.endedAt());
            stmt.setString(7, row.input());
            stmt.setString(8, row.output());
            stmt.setString(9, row.errorType());
            stmt.setString(10, row.errorTitle());
            stmt.setString(11, row.errorDetail());
            if (row.errorStatus() != null) {
                stmt.setInt(12, row.errorStatus());
            } else {
                stmt.setNull(12, Types.INTEGER);
            }
            stmt.setString(13, row.errorInstance());
            stmt.setObject(14, row.eventTimestamp());
            stmt.setString(15, failureReason);
            stmt.executeUpdate();
        }
        deleteSingle(conn, row.id());
    }
}
