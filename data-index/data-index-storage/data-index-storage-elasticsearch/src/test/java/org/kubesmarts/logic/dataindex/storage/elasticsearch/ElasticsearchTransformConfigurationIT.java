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
package org.kubesmarts.logic.dataindex.storage.elasticsearch;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for transform configuration.
 *
 * Verifies that configured time window and ILM retention are correctly
 * applied to transforms and ILM policies.
 */
@QuarkusTest
@TestProfile(CustomTimeWindowProfile.class)
class ElasticsearchTransformConfigurationIT {

    @Inject
    ElasticsearchClient client;

    @Test
    void testCustomTimeWindowApplied() throws IOException {
        var response = client.transform()
            .getTransform(r -> r.transformId("workflow-instances-transform"));

        var transform = response.transforms().get(0);
        String sourceQuery = transform.source().toString();

        // Verify query uses configured time window (30m from test profile)
        assertThat(sourceQuery).contains("now-30m");
        assertThat(sourceQuery).doesNotContain("now-1h");
    }

    @Test
    void testBothTransformsUseSameWindow() throws IOException {
        // Check workflow transform
        var workflowResponse = client.transform()
            .getTransform(r -> r.transformId("workflow-instances-transform"));
        String workflowQuery = workflowResponse.transforms().get(0).source().toString();

        // Check task transform
        var taskResponse = client.transform()
            .getTransform(r -> r.transformId("task-executions-transform"));
        String taskQuery = taskResponse.transforms().get(0).source().toString();

        // Both should use same time window
        assertThat(workflowQuery).contains("now-30m");
        assertThat(taskQuery).contains("now-30m");
    }

    @Test
    void testIlmRetentionConfigured() throws IOException {
        var response = client.ilm()
            .getLifecycle(r -> r.name("data-index-events-retention"));

        var policy = response.get("data-index-events-retention");
        String minAge = policy.policy().phases().delete().minAge().time();

        // Verify ILM uses configured retention (30d from test profile)
        assertThat(minAge).isEqualTo("30d");
    }
}
