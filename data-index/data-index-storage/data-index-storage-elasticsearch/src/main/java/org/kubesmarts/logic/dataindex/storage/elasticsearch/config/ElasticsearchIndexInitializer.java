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

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Initializes Elasticsearch indices on application startup.
 *
 * <p>Creates indices with proper mappings if they don't exist:
 * <ul>
 *   <li>workflow-instances - with flattened input/output fields
 *   <li>task-executions - with flattened input/output fields
 * </ul>
 *
 * <p><b>Auto-creation behavior</b>:
 * - Controlled by {@code data-index.elasticsearch.auto-create-indices} (default: true)
 * - In production, consider creating indices manually with proper settings
 */
@ApplicationScoped
public class ElasticsearchIndexInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchIndexInitializer.class);

    @Inject
    ElasticsearchClient client;

    @Inject
    ElasticsearchConfiguration config;

    @Inject
    ObjectMapper objectMapper;

    @Startup
    void onStart() {
        if (!config.autoCreateIndices()) {
            LOGGER.info("Auto-create indices is disabled. Skipping index initialization.");
            return;
        }

        LOGGER.info("Initializing Elasticsearch indices...");

        try {
            createIndexIfNotExists(
                    config.workflowInstanceIndex(),
                    "/elasticsearch/workflow-instances-mapping.json");

            createIndexIfNotExists(
                    config.taskExecutionIndex(),
                    "/elasticsearch/task-executions-mapping.json");

            LOGGER.info("Elasticsearch indices initialized successfully");

        } catch (Exception e) {
            LOGGER.error("Failed to initialize Elasticsearch indices", e);
            throw new RuntimeException("Elasticsearch index initialization failed", e);
        }
    }

    private void createIndexIfNotExists(String indexName, String mappingResource) throws IOException {
        // Check if index exists
        boolean exists = client.indices().exists(ExistsRequest.of(r -> r.index(indexName))).value();

        if (exists) {
            LOGGER.debug("Index {} already exists, skipping creation", indexName);
            return;
        }

        LOGGER.info("Creating index: {}", indexName);

        // Load mapping from resources
        try (InputStream is = getClass().getResourceAsStream(mappingResource)) {
            if (is == null) {
                throw new IOException("Mapping resource not found: " + mappingResource);
            }

            // Create index with mapping
            client.indices().create(CreateIndexRequest.of(r -> r
                    .index(indexName)
                    .withJson(is)));

            LOGGER.info("Index {} created successfully", indexName);
        }
    }
}
