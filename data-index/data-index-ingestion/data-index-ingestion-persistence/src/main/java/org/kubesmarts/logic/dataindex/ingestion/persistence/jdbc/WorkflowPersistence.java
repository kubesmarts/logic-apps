package org.kubesmarts.logic.dataindex.ingestion.persistence.jdbc;

import io.quarkus.arc.Unremovable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.kubesmarts.logic.dataindex.ingestion.persistence.WorkflowInstance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

@Unremovable
@ApplicationScoped
public class WorkflowPersistence {

    private String insertWorkflowUpsert;

    final DataSource dataSource;

    @Inject
    public WorkflowPersistence(DataSource dataSource) {
        this.dataSource = dataSource;
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

    public void persistBatch(List<WorkflowInstance> events) throws SQLException {
        if (events == null || events.isEmpty()) {
            return;
        }
    
        try (Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement(insertWorkflowUpsert)) {
    
            conn.setAutoCommit(false);
    
            try {
                for (WorkflowInstance event : events) {
                    setWorkflowParameters(stmt, event);
                    stmt.addBatch();
                }
    
                stmt.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        }
   }

    private void setWorkflowParameters(PreparedStatement stmt, WorkflowInstance event) throws SQLException {
        stmt.setString(1, event.id());
        stmt.setString(2, event.namespace());
        stmt.setString(3, event.name());
        stmt.setString(4, event.version());
        if (event.status() != null) {
            stmt.setString(5, event.status().name());
        } else {
            stmt.setNull(5, Types.VARCHAR);
        }
        stmt.setObject(6, event.startedAt());
        stmt.setObject(7, event.endedAt());
        stmt.setObject(8, event.lastUpdate());

        // JSON fields
        stmt.setString(9, event.input());
        stmt.setString(10, event.output());

        // Error fields
        if (event.error() != null) {
            stmt.setString(11, event.error().getType());
            stmt.setString(12, event.error().getTitle());
            stmt.setString(13, event.error().getDetail());
            stmt.setObject(14, event.error().getStatus());
            stmt.setString(15, event.error().getInstance());
        } else {
            stmt.setNull(11, Types.VARCHAR);
            stmt.setNull(12, Types.VARCHAR);
            stmt.setNull(13, Types.VARCHAR);
            stmt.setNull(14, Types.INTEGER);
            stmt.setNull(15, Types.VARCHAR);
        }
        stmt.setObject(16, event.eventTimestamp());
    }

}
