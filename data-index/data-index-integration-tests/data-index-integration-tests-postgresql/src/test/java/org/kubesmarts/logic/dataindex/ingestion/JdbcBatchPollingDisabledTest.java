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

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * MODE 3 runs the same image as MODE 1, so its raw tables must be left alone when polling is off.
 */
@QuarkusTest
@TestProfile(JdbcBatchPollingDisabledTest.PollingDisabled.class)
class JdbcBatchPollingDisabledTest {

    public static class PollingDisabled implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("data-index.ingestion.jdbc-batch.enabled", "false");
        }
    }

    @Inject
    DataSource dataSource;

    @AfterEach
    void cleanup() throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("DELETE FROM workflow_events_raw")) {
            stmt.executeUpdate();
        }
    }

    @Test
    void shouldLeaveStagingRowsUntouchedWhenDisabled() throws SQLException {
        String instanceId = "jdbc-batch-disabled-" + UUID.randomUUID();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO workflow_events_raw (instance_id, status, event_timestamp) VALUES (?, 'RUNNING', now())")) {
            stmt.setString(1, instanceId);
            stmt.executeUpdate();
        }

        await().during(Duration.ofSeconds(3))
                .atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(countStagedRows(instanceId)).isEqualTo(1));
    }

    private int countStagedRows(String instanceId) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT count(*) FROM workflow_events_raw WHERE instance_id = ?")) {
            stmt.setString(1, instanceId);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
