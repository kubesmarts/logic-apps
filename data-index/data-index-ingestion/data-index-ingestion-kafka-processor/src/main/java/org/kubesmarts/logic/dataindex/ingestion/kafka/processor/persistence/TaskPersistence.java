package org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.ZonedDateTime;
import java.util.Optional;

@Unremovable
@ApplicationScoped
public class TaskPersistence {

    private static final Logger log = LoggerFactory.getLogger(TaskPersistence.class);
    private static final String INVALID_FOREIGN_KEY = "23503";

    private String insertTaskUpsert;
    private String insertPlaceholderWorkflow;

    final DataSource dataSource;
    final ObjectMapper objectMapper;

    @Inject
    public TaskPersistence(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        insertTaskUpsert = LoadSQL.load("/sql/task-instance-upsert.sql");
        insertPlaceholderWorkflow = LoadSQL.load("/sql/task-placeholder-workflow-insert.sql");
    }

    public void persist(TaskExecution event) throws SQLException {
        try (Connection conn = this.dataSource.getConnection()) {
            conn.setAutoCommit(false);
            Savepoint sp = conn.setSavepoint("before_task_insert");
            try {
                // Try to insert directly
                tryInsertTask(event, conn);
                conn.commit();
            } catch (SQLException e) {
                if (INVALID_FOREIGN_KEY.equals(e.getSQLState())) {
                    conn.rollback(sp);
                    tryCreatePlaceholder(event, conn);
                    tryInsertTask(event, conn);
                    conn.commit();
                } else {
                    conn.rollback();
                    throw e;
                }
            }
        }
    }

    private void tryInsertTask(TaskExecution event, Connection conn) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(insertTaskUpsert)) {
            setTaskParameters(stmt, event);
            stmt.executeUpdate();
        }
    }

    private void tryCreatePlaceholder(TaskExecution event, Connection conn) throws SQLException {
        // Create placeholder workflow and retry
        log.debug("Task arrived before workflow. Creating placeholder for instance: {}",
                event.getInstanceId());
        try (PreparedStatement placeholderStmt = conn.prepareStatement(insertPlaceholderWorkflow)) {
            placeholderStmt.setString(1, event.getInstanceId());
            placeholderStmt.setObject(2, Optional.ofNullable(event.getEventTimestamp()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
            placeholderStmt.executeUpdate();
        }
    }

    private void setTaskParameters(PreparedStatement stmt, TaskExecution event) throws SQLException {

        stmt.setString(1, event.getId());
        stmt.setString(2, event.getInstanceId());
        stmt.setString(3, event.getTaskName());
        stmt.setString(4, event.getTaskPosition());
        stmt.setString(5, event.getStatus());
        stmt.setObject(6, Optional.ofNullable(event.getStart()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
        stmt.setObject(7, Optional.ofNullable(event.getEnd()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
        stmt.setString(8, toJsonString(event.getInput()));
        stmt.setString(9, toJsonString(event.getOutput()));


        // Error fields
        if (event.getError() != null) {
            stmt.setString(10, event.getError().getType());
            stmt.setString(11, event.getError().getTitle());
            stmt.setString(12, event.getError().getDetail());
            stmt.setObject(13, event.getError().getStatus());
            stmt.setString(14, event.getError().getInstance());
        } else {
            stmt.setNull(10, java.sql.Types.VARCHAR);
            stmt.setNull(11, java.sql.Types.VARCHAR);
            stmt.setNull(12, java.sql.Types.VARCHAR);
            stmt.setNull(13, java.sql.Types.INTEGER);
            stmt.setNull(14, java.sql.Types.VARCHAR);
        }

        stmt.setObject(15, Optional.ofNullable(event.getEventTimestamp()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
    }

    private String toJsonString(JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize JSON node, returning null", e);
            return null;
        }
    }

}
