package org.kubesmarts.logic.dataindex.ingestion.kafka;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class SuspendedWorkflowIT extends BaseWorkflowLifecycleIT {

    @Test
    void shouldSaveSuspendedWorkflowCloudEvents() throws Exception {
        publishEventsToKafka("suspended-workflow.json");

        String workflowId = "01KSR74SRH4D8A4ZGQ4Q2A56VE";
        awaitByWorkflowStatus(workflowId, "SUSPENDED");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, status, name, namespace, version " +
                             "FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, workflowId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("SUSPENDED");
                assertThat(rs.getString("name")).isEqualTo("SwitchLoopWait");
                assertThat(rs.getString("namespace")).isEqualTo("example");
                assertThat(rs.getString("version")).isEqualTo("0.1.0");
            }
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT COUNT(*) as task_count FROM task_instances WHERE instance_id = ?")) {
            stmt.setString(1, workflowId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("task_count")).isGreaterThan(0);
            }
        }
    }
}
