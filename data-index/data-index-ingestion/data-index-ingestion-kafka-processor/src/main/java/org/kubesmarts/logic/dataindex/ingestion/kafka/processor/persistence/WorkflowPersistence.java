package org.kubesmarts.logic.dataindex.ingestion.kafka.processor.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.arc.Unremovable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.ZonedDateTime;
import java.util.Optional;

@Unremovable
@ApplicationScoped
public class WorkflowPersistence {

    private static final Logger log = LoggerFactory.getLogger(WorkflowPersistence.class);

    private String insertWorkflowUpsert;

    final DataSource dataSource;
    final ObjectMapper objectMapper;

    @Inject
    public WorkflowPersistence(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        insertWorkflowUpsert = LoadSQL.load("/sql/workflow-instance-upsert.sql");
    }

    public void persist(WorkflowInstance event) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(insertWorkflowUpsert)) {

            conn.setAutoCommit(false);

            try {
                setWorkflowParameters(stmt, event);
                stmt.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private void setWorkflowParameters(PreparedStatement stmt, WorkflowInstance event) throws SQLException {
        stmt.setString(1, event.getId());
        stmt.setString(2, event.getNamespace());
        stmt.setString(3, event.getName());
        stmt.setString(4, event.getVersion());
        if (event.getStatus() != null) {
            stmt.setString(5, event.getStatus().name());
        } else {
            stmt.setNull(5, Types.VARCHAR);
        }
        stmt.setObject(6, Optional.ofNullable(event.getStart()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
        stmt.setObject(7, Optional.ofNullable(event.getEnd()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
        stmt.setObject(8, Optional.ofNullable(event.getLastUpdate()).map(ZonedDateTime::toOffsetDateTime).orElse(null));

        // JSON fields
        stmt.setString(9, toJsonString(event.getInput()));
        stmt.setString(10, toJsonString(event.getOutput()));

        // Error fields
        if (event.getError() != null) {
            stmt.setString(11, event.getError().getType());
            stmt.setString(12, event.getError().getTitle());
            stmt.setString(13, event.getError().getDetail());
            stmt.setObject(14, event.getError().getStatus());
            stmt.setString(15, event.getError().getInstance());
        } else {
            stmt.setNull(11, Types.VARCHAR);
            stmt.setNull(12, Types.VARCHAR);
            stmt.setNull(13, Types.VARCHAR);
            stmt.setNull(14, Types.INTEGER);
            stmt.setNull(15, Types.VARCHAR);
        }
        stmt.setObject(16, Optional.ofNullable(event.getEventTimestamp()).map(ZonedDateTime::toOffsetDateTime).orElse(null));
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