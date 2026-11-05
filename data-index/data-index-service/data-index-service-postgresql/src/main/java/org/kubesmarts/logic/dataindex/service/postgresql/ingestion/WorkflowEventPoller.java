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
import org.kubesmarts.logic.dataindex.ingestion.persistence.WorkflowEventProcessor;
import org.kubesmarts.logic.dataindex.ingestion.persistence.jdbc.LoadSQL;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
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

@ApplicationScoped
public class WorkflowEventPoller {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventPoller.class);

    private String selectBatchSql;
    private String deleteBatchSql;
    private String deleteSingleSql;
    private String insertDlqSql;

    final DataSource dataSource;
    final WorkflowEventProcessor workflowEventProcessor;
    final ObjectMapper objectMapper;
    final MeterRegistry meterRegistry;

    private Counter processedCounter;
    private Counter dlqCounter;
    private Counter dlqWriteFailedCounter;

    @ConfigProperty(name = "data-index.ingestion.jdbc-batch.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "data-index.ingestion.jdbc-batch.batch-size", defaultValue = "1000")
    int batchSize;

    @Inject
    public WorkflowEventPoller(DataSource dataSource, WorkflowEventProcessor workflowEventProcessor, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.dataSource = dataSource;
        this.workflowEventProcessor = workflowEventProcessor;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {
        selectBatchSql = LoadSQL.load("/sql/workflow-events-select-batch.sql");
        deleteBatchSql = LoadSQL.load("/sql/workflow-events-delete-batch.sql");
        deleteSingleSql = LoadSQL.load("/sql/workflow-events-delete-single.sql");
        insertDlqSql = LoadSQL.load("/sql/workflow-events-insert-dlq.sql");
        processedCounter = meterRegistry.counter("data_index.jdbc_batch.events.processed", "type", "workflow");
        dlqCounter = meterRegistry.counter("data_index.jdbc_batch.events.dlq", "type", "workflow");
        dlqWriteFailedCounter = meterRegistry.counter("data_index.jdbc_batch.dlq.write.failed", "type", "workflow");
    }

    @Scheduled(every = "{data-index.ingestion.jdbc-batch.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!enabled) {
            return;
        }
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<WorkflowEventRow> rows = selectBatch(conn);
                if (!rows.isEmpty()) {
                    processBatch(conn, rows);
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                log.error("Workflow event poll failed; staging rows remain for retry on the next poll", e);
            }
        } catch (SQLException e) {
            log.error("Failed to obtain a connection for the workflow event poll", e);
        }
    }

    private void processBatch(Connection conn, List<WorkflowEventRow> rows) throws SQLException {
        try {
            List<WorkflowInstance> events = rows.stream()
                    .map(row -> WorkflowEventRowMapper.toWorkflowInstance(row, objectMapper))
                    .toList();
            workflowEventProcessor.processBatch(events);
            deleteBatch(conn, rows);
            processedCounter.increment(rows.size());
        } catch (Exception batchFailure) {
            log.warn("Workflow batch of {} events failed; falling back to per-record processing", rows.size(), batchFailure);
            processPerRecordWithDlqFallback(conn, rows);
        }
    }

    private void processPerRecordWithDlqFallback(Connection conn, List<WorkflowEventRow> rows) throws SQLException {
        for (WorkflowEventRow row : rows) {
            try {
                WorkflowInstance event = WorkflowEventRowMapper.toWorkflowInstance(row, objectMapper);
                workflowEventProcessor.process(event);
                deleteSingle(conn, row.id());
                processedCounter.increment();
            } catch (Exception singleFailure) {
                log.error("Workflow event id={} instanceId={} failed; moving to DLQ", row.id(), row.instanceId(), singleFailure);
                try {
                    moveToDlq(conn, row, describeFailure(singleFailure));
                    dlqCounter.increment();
                } catch (Exception dlqFailure) {
                    // Leave the row in place, it's retried next poll.
                    log.error("Workflow event id={} instanceId={} could not be moved to DLQ; it remains staged for retry",
                            row.id(), row.instanceId(), dlqFailure);
                    dlqWriteFailedCounter.increment();
                }
            }
        }
    }

    private List<WorkflowEventRow> selectBatch(Connection conn) throws SQLException {
        List<WorkflowEventRow> rows = new ArrayList<>();
        try (PreparedStatement stmt = conn.prepareStatement(selectBatchSql)) {
            stmt.setInt(1, batchSize);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new WorkflowEventRow(
                            rs.getLong("id"),
                            rs.getString("instance_id"),
                            rs.getString("namespace"),
                            rs.getString("name"),
                            rs.getString("version"),
                            rs.getString("status"),
                            rs.getObject("started_at", OffsetDateTime.class),
                            rs.getObject("ended_at", OffsetDateTime.class),
                            rs.getObject("last_update", OffsetDateTime.class),
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

    private void deleteBatch(Connection conn, List<WorkflowEventRow> rows) throws SQLException {
        Long[] ids = rows.stream().map(WorkflowEventRow::id).toArray(Long[]::new);
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

    private void moveToDlq(Connection conn, WorkflowEventRow row, String failureReason) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(insertDlqSql)) {
            stmt.setString(1, row.instanceId());
            stmt.setString(2, row.namespace());
            stmt.setString(3, row.name());
            stmt.setString(4, row.version());
            stmt.setString(5, row.status());
            stmt.setObject(6, row.startedAt());
            stmt.setObject(7, row.endedAt());
            stmt.setObject(8, row.lastUpdate());
            stmt.setString(9, row.input());
            stmt.setString(10, row.output());
            stmt.setString(11, row.errorType());
            stmt.setString(12, row.errorTitle());
            stmt.setString(13, row.errorDetail());
            if (row.errorStatus() != null) {
                stmt.setInt(14, row.errorStatus());
            } else {
                stmt.setNull(14, Types.INTEGER);
            }
            stmt.setString(15, row.errorInstance());
            stmt.setObject(16, row.eventTimestamp());
            stmt.setString(17, failureReason);
            stmt.executeUpdate();
        }
        deleteSingle(conn, row.id());
    }

    private static String describeFailure(Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : "no message";
        return e.getClass().getSimpleName() + ": " + message;
    }
}
