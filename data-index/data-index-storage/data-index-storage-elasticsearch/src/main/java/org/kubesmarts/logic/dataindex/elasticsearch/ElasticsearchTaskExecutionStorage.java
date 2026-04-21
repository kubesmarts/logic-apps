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
package org.kubesmarts.logic.dataindex.elasticsearch;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

import org.kubesmarts.logic.dataindex.api.TaskExecutionStorage;
import org.kubesmarts.logic.dataindex.elasticsearch.config.ElasticsearchConfiguration;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kie.kogito.persistence.api.StorageServiceCapability;
import org.kie.kogito.persistence.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Elasticsearch storage implementation for TaskExecution domain model.
 *
 * <p>Uses:
 * <ul>
 *   <li>ElasticsearchClient - Java client for Elasticsearch operations
 *   <li>ElasticsearchQuery - Query implementation for filtering/sorting/pagination
 *   <li>ObjectMapper - JSON serialization for document storage
 * </ul>
 *
 * <p><b>Index Structure</b>:
 * <ul>
 *   <li>Index name: "task-executions"
 *   <li>Document ID: task execution ID
 *   <li>Document source: TaskExecution JSON
 *   <li>Flattened fields: input, output (for queryability)
 * </ul>
 *
 * <p><b>Read-Only Mode</b>:
 * Data Index v1.0.0 is read-only. Write operations (put, remove, clear) should only be used
 * by event processors or administrative tools.
 */
@ApplicationScoped
public class ElasticsearchTaskExecutionStorage implements TaskExecutionStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchTaskExecutionStorage.class);

    private final ElasticsearchClient client;
    private final ObjectMapper objectMapper;
    private final String indexName;

    @Inject
    public ElasticsearchTaskExecutionStorage(
            ElasticsearchClient client,
            ObjectMapper objectMapper,
            ElasticsearchConfiguration config) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.indexName = config.taskExecutionIndex();
    }

    // Default constructor for CDI proxying
    protected ElasticsearchTaskExecutionStorage() {
        this.client = null;
        this.objectMapper = null;
        this.indexName = "task-executions";
    }

    @Override
    public Query<TaskExecution> query() {
        return new ElasticsearchQuery<>(client, indexName, TaskExecution.class);
    }

    @Override
    public TaskExecution get(String id) {
        try {
            GetRequest request = GetRequest.of(r -> r
                    .index(indexName)
                    .id(id));

            GetResponse<TaskExecution> response = client.get(request, TaskExecution.class);

            if (response.found()) {
                return response.source();
            } else {
                return null;
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to get task execution: " + id, e);
        }
    }

    @Override
    public TaskExecution put(String id, TaskExecution value) {
        try {
            IndexRequest<TaskExecution> request = IndexRequest.of(r -> r
                    .index(indexName)
                    .id(id)
                    .document(value));

            IndexResponse response = client.index(request);

            LOGGER.debug("Indexed task execution {} with result: {}", id, response.result());
            return value;

        } catch (IOException e) {
            throw new RuntimeException("Failed to put task execution: " + id, e);
        }
    }

    @Override
    public TaskExecution remove(String id) {
        try {
            // First get the document before deleting
            TaskExecution existing = get(id);
            if (existing == null) {
                return null;
            }

            DeleteRequest request = DeleteRequest.of(r -> r
                    .index(indexName)
                    .id(id));

            DeleteResponse response = client.delete(request);

            LOGGER.debug("Deleted task execution {} with result: {}", id, response.result());
            return existing;

        } catch (IOException e) {
            throw new RuntimeException("Failed to remove task execution: " + id, e);
        }
    }

    @Override
    public boolean containsKey(String id) {
        try {
            return client.exists(e -> e
                    .index(indexName)
                    .id(id))
                    .value();

        } catch (IOException e) {
            throw new RuntimeException("Failed to check existence of task execution: " + id, e);
        }
    }

    @Override
    public void clear() {
        try {
            client.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m)));

            LOGGER.info("Cleared all task executions from index: {}", indexName);

        } catch (IOException e) {
            throw new RuntimeException("Failed to clear task executions", e);
        }
    }

    @Override
    public Map<String, TaskExecution> entries() {
        throw new UnsupportedOperationException("We should not iterate over all entries");
    }

    @Override
    public String getRootType() {
        return TaskExecution.class.getCanonicalName();
    }

    @Override
    public Multi<TaskExecution> objectCreatedListener() {
        // Reactive listeners not implemented in v1.0.0
        return Multi.createFrom().empty();
    }

    @Override
    public Multi<TaskExecution> objectUpdatedListener() {
        // Reactive listeners not implemented in v1.0.0
        return Multi.createFrom().empty();
    }

    @Override
    public Multi<String> objectRemovedListener() {
        // Reactive listeners not implemented in v1.0.0
        return Multi.createFrom().empty();
    }

    @Override
    public Set<StorageServiceCapability> capabilities() {
        return Set.of(StorageServiceCapability.JSON_QUERY);
    }
}
