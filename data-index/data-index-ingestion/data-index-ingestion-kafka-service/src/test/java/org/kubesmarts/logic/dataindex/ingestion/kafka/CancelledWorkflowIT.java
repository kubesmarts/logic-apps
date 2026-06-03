package org.kubesmarts.logic.dataindex.ingestion.kafka;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class CancelledWorkflowIT extends BaseWorkflowLifecycleIT {

    @Test
    void shouldSaveCancelledWorkflowCloudEvents() throws Exception {
        publishEventsToKafka("cancelled-workflow.json");

        String workflowId = "01KSR5FER167JC2SN81K0K2N0S";
        awaitByWorkflowStatus(workflowId, "CANCELLED");

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, status, name, namespace, version " +
                             "FROM workflow_instances WHERE id = ?")) {
            stmt.setString(1, workflowId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("status")).isEqualTo("CANCELLED");
                assertThat(rs.getString("name")).isEqualTo("SwitchLoopWait");
                assertThat(rs.getString("namespace")).isEqualTo("example");
                assertThat(rs.getString("version")).isEqualTo("0.1.0");
            }
        }

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT task_name, task_position, status " +
                             "FROM task_instances WHERE instance_id = ? AND status = 'CANCELLED'")) {
            stmt.setString(1, workflowId);
            try (ResultSet rs = stmt.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("task_name")).isEqualTo("waitABit");
                assertThat(rs.getString("task_position")).isEqualTo("do/2/waitABit");
                assertThat(rs.getString("status")).isEqualTo("CANCELLED");
            }
        }
    }
}
