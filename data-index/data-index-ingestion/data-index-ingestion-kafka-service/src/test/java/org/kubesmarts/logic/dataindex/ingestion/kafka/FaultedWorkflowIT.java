package org.kubesmarts.logic.dataindex.ingestion.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.serverlessworkflow.impl.WorkflowStatus;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
public class FaultedWorkflowIT extends BaseWorkflowLifecycleIT {

    @Test
    void shouldSaveFaultedWorkflowCloudEvents() throws Exception {
        publishEventsToKafka("faulted-workflow.json");

        String workflowId = "01KSR2FQCGEFV9B5V6QQ6PTJDK";
        awaitByWorkflowStatus(workflowId, WorkflowStatus.FAULTED.name());


        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT id, status, name, namespace, version, error_type, error_status, error_detail " +
                                 "FROM workflow_instances WHERE id = ?")) {
                stmt.setString(1, workflowId);
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("status")).isEqualTo("FAULTED");
                    assertThat(rs.getString("name")).isEqualTo("faulted-workflow");
                    assertThat(rs.getString("namespace")).isEqualTo("quarkus.flow");
                    assertThat(rs.getString("version")).isEqualTo("0.0.1");

                    // Verify error fields
                    assertThat(rs.getString("error_type")).isEqualTo("https://serverlessworkflow.io/spec/1.0.0/errors/data");
                    assertThat(rs.getInt("error_status")).isEqualTo(422);
                    assertThat(rs.getString("error_detail")).contains("Connection refused");
                }
            }
        });

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            // Verify task is also FAULTED with error
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                         "SELECT task_name, task, status, error_type, error_status, error_detail " +
                                 "FROM task_instances WHERE instance_id = ? AND task = ? AND status = ?")) {
                stmt.setString(1, workflowId);
                stmt.setString(2 , "do/0/http-0");
                stmt.setString(3 , "FAILED");
                try (ResultSet rs = stmt.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("task_name")).isEqualTo("http-0");
                    assertThat(rs.getString("task")).isEqualTo("do/0/http-0");
                    assertThat(rs.getString("status")).isEqualTo("FAILED");

                    // Verify task error fields
                    assertThat(rs.getString("error_type")).isEqualTo("https://serverlessworkflow.io/spec/1.0.0/errors/data");
                    assertThat(rs.getInt("error_status")).isEqualTo(422);
                    assertThat(rs.getString("error_detail")).contains("Connection refused");
                }
            }
        });
    }
}
