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

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.kubesmarts.logic.dataindex.api.TaskExecutionStorage;
import org.kubesmarts.logic.dataindex.storage.elasticsearch.config.ElasticsearchConfiguration;
import org.kubesmarts.logic.dataindex.model.TaskExecution;
import org.kie.kogito.persistence.api.StorageServiceCapability;
import org.kie.kogito.persistence.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Elasticsearch storage implementation for TaskExecution domain model.
 *
 * <p>Uses:
 * <ul>
 *   <li>ElasticsearchClient - Java client for Elasticsearch operations (handles JSON internally)
 *   <li>ElasticsearchQuery - Query implementation for filtering/sorting/pagination
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
    private final String indexName;

    @Inject
    public ElasticsearchTaskExecutionStorage(
            ElasticsearchClient client,
            ElasticsearchConfiguration config) {
        this.client = client;
        this.indexName = config.taskExecutionIndex();
    }

    // Default constructor for CDI proxying
    protected ElasticsearchTaskExecutionStorage() {
        this.client = null;
        this.indexName = "task-executions";
    }

    @Override
    public Query<TaskExecution> query() {
        return new ElasticsearchQuery<>(client, indexName, TaskExecution.class);
    }

    /**
     * Find all task executions for a specific workflow instance.
     *
     * In Elasticsearch MODE 2, the id field is a composite key: "workflowInstanceId:taskPosition".
     * We use a prefix query to find all task executions that start with the workflow instance ID.
     *
     * @param workflowInstanceId Workflow instance ID
     * @return List of task executions for this workflow instance
     */
    public List<TaskExecution> findByWorkflowInstanceId(String workflowInstanceId) {
        try {
            SearchRequest request = SearchRequest.of(s -> s
                    .index(indexName)
                    .query(q -> q
                            .prefix(p -> p
                                    .field("id")
                                    .value(workflowInstanceId + ":")))
                    .size(1000)); // Max task executions per workflow

            SearchResponse<TaskExecution> response = client.search(request, TaskExecution.class);

            LOGGER.info("Found {} task executions for workflow instance: {}", response.hits().hits().size(), workflowInstanceId);

            List<TaskExecution> results = response.hits().hits().stream()
                    .map(hit -> {
                        TaskExecution source = hit.source();
                        if (source == null) {
                            LOGGER.warn("Null source for task execution hit with id: {}", hit.id());
                        } else {
                            LOGGER.info("Successfully deserialized task execution: id={}, taskName={}, status={}",
                                source.getId(), source.getTaskName(), source.getStatus());
                        }
                        return source;
                    })
                    .filter(source -> source != null)
                    .collect(Collectors.toList());

            LOGGER.info("Returning {} task executions after filtering nulls", results.size());
            return results;

        } catch (IOException e) {
            LOGGER.error("Failed to find task executions for workflow instance: {}", workflowInstanceId, e);
            return List.of();
        }
    }

    @Override
    public TaskExecution get(String id) {
        try {
            // Search by id field (not Elasticsearch _id, which is auto-generated by transform)
            SearchRequest request = SearchRequest.of(s -> s
                    .index(indexName)
                    .query(q -> q
                            .term(t -> t
                                    .field("id")
                                    .value(id)))
                    .size(1));

            LOGGER.info("Searching for task execution with id: {}", id);
            SearchResponse<TaskExecution> response = client.search(request, TaskExecution.class);
            LOGGER.info("Search response: {} hits found", response.hits().hits().size());

            if (response.hits().hits().isEmpty()) {
                LOGGER.info("No task execution found with id: {}", id);
                return null;
            }

            TaskExecution result = response.hits().hits().get(0).source();
            if (result == null) {
                LOGGER.warn("Task execution found but deserialization returned null for id: {}", id);
            } else {
                LOGGER.info("Successfully retrieved task execution: id={}, taskName={}, task={}",
                        result.getId(), result.getTaskName(), result.getTask());
            }
            return result;

        } catch (Exception e) {
            LOGGER.error("Failed to get task execution: " + id, e);
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
    public Set<StorageServiceCapability> capabilities() {
        return Set.of(StorageServiceCapability.JSON_QUERY);
    }
}
