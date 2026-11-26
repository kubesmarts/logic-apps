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
import java.util.List;

import static java.sql.Types.INTEGER;
import static java.sql.Types.VARCHAR;

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
        stmt.setString(5, event.workflowApplicationId());
        if (event.status() != null) {
            stmt.setString(6, event.status().name());
        } else {
            stmt.setNull(6, VARCHAR);
        }
        stmt.setObject(7, event.startedAt());
        stmt.setObject(8, event.endedAt());
        stmt.setObject(9, event.lastUpdate());

        // JSON fields
        stmt.setString(10, event.input());
        stmt.setString(11, event.output());

        // Error fields
        if (event.error() != null) {
            stmt.setString(12, event.error().getType());
            stmt.setString(13, event.error().getTitle());
            stmt.setString(14, event.error().getDetail());
            stmt.setObject(15, event.error().getStatus());
            stmt.setString(16, event.error().getInstance());
        } else {
            stmt.setNull(12, VARCHAR);
            stmt.setNull(13, VARCHAR);
            stmt.setNull(14, VARCHAR);
            stmt.setNull(15, INTEGER);
            stmt.setNull(16, VARCHAR);
        }
        stmt.setObject(17, event.eventTimestamp());
    }

}
