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
package org.kubesmarts.logic.dataindex.elasticsearch.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Elasticsearch storage configuration.
 *
 * <p>Configuration prefix: {@code data-index.elasticsearch}
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * @Inject
 * ElasticsearchConfiguration config;
 *
 * String workflowIndex = config.workflowInstanceIndex();
 * }</pre>
 *
 * <p><b>Configuration example:</b>
 * <pre>
 * # Elasticsearch connection (managed by Quarkus)
 * quarkus.elasticsearch.hosts=localhost:9200
 *
 * # Index names
 * data-index.elasticsearch.workflow-instance-index=workflow-instances
 * data-index.elasticsearch.task-execution-index=task-executions
 *
 * # Index refresh behavior
 * data-index.elasticsearch.refresh-policy=wait_for
 * </pre>
 *
 * <p><b>Note:</b> Elasticsearch connection settings (hosts, credentials, TLS) are configured
 * via Quarkus Elasticsearch extension properties ({@code quarkus.elasticsearch.*}).
 *
 * @see <a href="https://quarkus.io/guides/elasticsearch">Quarkus Elasticsearch Guide</a>
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "data-index.elasticsearch")
public interface ElasticsearchConfiguration {

    /**
     * Workflow instance index name.
     *
     * <p><b>Default:</b> {@code workflow-instances}
     *
     * @return index name for workflow instances
     */
    @WithDefault("workflow-instances")
    String workflowInstanceIndex();

    /**
     * Task execution index name.
     *
     * <p><b>Default:</b> {@code task-executions}
     *
     * @return index name for task executions
     */
    @WithDefault("task-executions")
    String taskExecutionIndex();

    /**
     * Index refresh policy.
     *
     * <p><b>Values:</b>
     * <ul>
     *   <li>{@code true} - Immediate refresh (slower, good for tests)
     *   <li>{@code false} - Background refresh (faster, eventual consistency)
     *   <li>{@code wait_for} - Wait for refresh (balanced, recommended for production)
     * </ul>
     *
     * <p><b>Default:</b> {@code wait_for}
     *
     * @return refresh policy
     */
    @WithDefault("wait_for")
    String refreshPolicy();

    /**
     * Number of primary shards for indices.
     *
     * <p><b>Recommendation:</b>
     * <ul>
     *   <li>Small datasets (&lt; 10GB): 1 shard
     *   <li>Medium datasets (10-100GB): 3-5 shards
     *   <li>Large datasets (&gt; 100GB): 5-10 shards
     * </ul>
     *
     * <p><b>Default:</b> 3
     *
     * @return number of primary shards
     */
    @WithDefault("3")
    int numberOfShards();

    /**
     * Number of replica shards for indices.
     *
     * <p><b>Recommendation:</b>
     * <ul>
     *   <li>Development: 0 replicas
     *   <li>Production: 1-2 replicas (for high availability)
     * </ul>
     *
     * <p><b>Default:</b> 1
     *
     * @return number of replica shards
     */
    @WithDefault("1")
    int numberOfReplicas();

    /**
     * Whether to create indices automatically if they don't exist.
     *
     * <p><b>Default:</b> true
     *
     * @return true to auto-create indices
     */
    @WithDefault("true")
    boolean autoCreateIndices();
}
