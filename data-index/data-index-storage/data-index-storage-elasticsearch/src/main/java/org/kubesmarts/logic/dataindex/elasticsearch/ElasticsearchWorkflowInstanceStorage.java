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

import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
import org.kubesmarts.logic.dataindex.elasticsearch.config.ElasticsearchConfiguration;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;
import org.kie.kogito.persistence.api.StorageServiceCapability;
import org.kie.kogito.persistence.api.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.GetRequest;
import co.elastic.clients.elasticsearch.core.GetResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.IndexResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Elasticsearch storage implementation for WorkflowInstance domain model.
 *
 * <p>Uses:
 * <ul>
 *   <li>ElasticsearchClient - Java client for Elasticsearch operations (handles JSON internally)
 *   <li>ElasticsearchQuery - Query implementation for filtering/sorting/pagination
 * </ul>
 *
 * <p><b>Index Structure</b>:
 * <ul>
 *   <li>Index name: "workflow-instances"
 *   <li>Document ID: workflow instance ID
 *   <li>Document source: WorkflowInstance JSON
 *   <li>Flattened fields: input, output (for queryability)
 * </ul>
 *
 * <p><b>Read-Only Mode</b>:
 * Data Index v1.0.0 is read-only. Write operations (put, remove, clear) should only be used
 * by event processors or administrative tools.
 */
@ApplicationScoped
public class ElasticsearchWorkflowInstanceStorage implements WorkflowInstanceStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchWorkflowInstanceStorage.class);

    private final ElasticsearchClient client;
    private final String indexName;

    @Inject
    public ElasticsearchWorkflowInstanceStorage(
            ElasticsearchClient client,
            ElasticsearchConfiguration config) {
        this.client = client;
        this.indexName = config.workflowInstanceIndex();
    }

    // Default constructor for CDI proxying
    protected ElasticsearchWorkflowInstanceStorage() {
        this.client = null;
        this.indexName = "workflow-instances";
    }

    @Override
    public Query<WorkflowInstance> query() {
        return new ElasticsearchQuery<>(client, indexName, WorkflowInstance.class);
    }

    @Override
    public WorkflowInstance get(String id) {
        try {
            GetRequest request = GetRequest.of(r -> r
                    .index(indexName)
                    .id(id));

            GetResponse<WorkflowInstance> response = client.get(request, WorkflowInstance.class);

            if (response.found()) {
                return response.source();
            } else {
                return null;
            }

        } catch (IOException e) {
            throw new RuntimeException("Failed to get workflow instance: " + id, e);
        }
    }

    @Override
    public WorkflowInstance put(String id, WorkflowInstance value) {
        try {
            IndexRequest<WorkflowInstance> request = IndexRequest.of(r -> r
                    .index(indexName)
                    .id(id)
                    .document(value));

            IndexResponse response = client.index(request);

            LOGGER.debug("Indexed workflow instance {} with result: {}", id, response.result());
            return value;

        } catch (IOException e) {
            throw new RuntimeException("Failed to put workflow instance: " + id, e);
        }
    }

    @Override
    public WorkflowInstance remove(String id) {
        try {
            // First get the document before deleting
            WorkflowInstance existing = get(id);
            if (existing == null) {
                return null;
            }

            DeleteRequest request = DeleteRequest.of(r -> r
                    .index(indexName)
                    .id(id));

            DeleteResponse response = client.delete(request);

            LOGGER.debug("Deleted workflow instance {} with result: {}", id, response.result());
            return existing;

        } catch (IOException e) {
            throw new RuntimeException("Failed to remove workflow instance: " + id, e);
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
            throw new RuntimeException("Failed to check existence of workflow instance: " + id, e);
        }
    }

    @Override
    public void clear() {
        try {
            client.deleteByQuery(d -> d
                    .index(indexName)
                    .query(q -> q.matchAll(m -> m))
                    .conflicts(co.elastic.clients.elasticsearch._types.Conflicts.Proceed));

            LOGGER.info("Cleared all workflow instances from index: {}", indexName);

        } catch (IOException e) {
            throw new RuntimeException("Failed to clear workflow instances", e);
        }
    }

    @Override
    public Map<String, WorkflowInstance> entries() {
        throw new UnsupportedOperationException("We should not iterate over all entries");
    }

    @Override
    public String getRootType() {
        return WorkflowInstance.class.getCanonicalName();
    }

    @Override
    public Multi<WorkflowInstance> objectCreatedListener() {
        // Reactive listeners not implemented in v1.0.0
        return Multi.createFrom().empty();
    }

    @Override
    public Multi<WorkflowInstance> objectUpdatedListener() {
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
