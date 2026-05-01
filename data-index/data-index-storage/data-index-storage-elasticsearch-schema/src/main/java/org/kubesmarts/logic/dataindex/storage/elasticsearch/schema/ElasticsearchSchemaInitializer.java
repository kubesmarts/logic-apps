/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.kubesmarts.logic.dataindex.storage.elasticsearch.schema;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleRequest;
import co.elastic.clients.elasticsearch.ilm.PutLifecycleRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.transform.GetTransformRequest;
import co.elastic.clients.elasticsearch.transform.PutTransformRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.runtime.Startup;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
@Startup
public class ElasticsearchSchemaInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchSchemaInitializer.class);

    @Inject
    ElasticsearchClient client;

    @ConfigProperty(name = "data-index.storage.skip-init-schema", defaultValue = "false")
    boolean skipInitSchema;

    @ConfigProperty(name = "data-index.elasticsearch.schema.init.enabled", defaultValue = "true")
    boolean schemaInitEnabled;

    String[] ilmPolicyResources = new String[]{
            "/elasticsearch/ilm/data-index-events-retention.json"
    };

    private final Map<String, String> indexTemplateResources = new HashMap<>() {{
        put("workflow-events", "/elasticsearch/index-templates/workflow-events.json");
        put("workflow-instances", "/elasticsearch/index-templates/workflow-instances.json");
    }};

    private final Map<String, String> transformResources = new HashMap<>() {{
        put("workflow-instances-transform", "/elasticsearch/transforms/workflow-instances-transform.json");
    }};

    private final ObjectMapper objectMapper = new ObjectMapper();

    void onStart(@Observes StartupEvent event) {
        if (skipInitSchema) {
            LOGGER.info("Elasticsearch schema initialization disabled (universal flag: data-index.storage.skip-init-schema=true)");
            return;
        }

        if (!schemaInitEnabled) {
            LOGGER.info("Elasticsearch schema initialization disabled (backend-specific flag: data-index.elasticsearch.schema.init.enabled=false)");
            return;
        }

        LOGGER.info("Initializing Elasticsearch schema...");

        try {
            applyIlmPolicies();
            applyIndexTemplates();
            applyTransforms();
            LOGGER.info("Elasticsearch schema initialization complete");
        } catch (Exception e) {
            LOGGER.error("Elasticsearch schema initialization failed", e);
            throw new RuntimeException("Failed to initialize Elasticsearch schema", e);
        }
    }

    private void applyIlmPolicies() throws IOException {
        for (String resourcePath : ilmPolicyResources) {
            String policyName = extractResourceName(resourcePath);
            applyIlmPolicy(policyName, resourcePath);
        }
    }

    private void applyIndexTemplates() throws IOException {
        for (Map.Entry<String, String> entry : indexTemplateResources.entrySet()) {
            applyIndexTemplate(entry.getKey(), entry.getValue());
        }
    }

    private void applyTransforms() throws IOException {
        for (Map.Entry<String, String> entry : transformResources.entrySet()) {
            applyTransform(entry.getKey(), entry.getValue());
        }
    }

    private void applyIlmPolicy(String name, String resourcePath) throws IOException {
        if (ilmPolicyExists(name)) {
            LOGGER.info("ILM policy '{}' already exists, skipping", name);
            return;
        }

        LOGGER.info("Applying ILM policy '{}'...", name);
        String json = loadResourceAsString(resourcePath);
        JsonNode rootNode = objectMapper.readTree(json);
        JsonNode policyNode = rootNode.get("policy");

        if (policyNode == null) {
            throw new IllegalArgumentException("Invalid ILM policy JSON: missing 'policy' field in " + resourcePath);
        }

        try (InputStream is = new ByteArrayInputStream(policyNode.toString().getBytes(StandardCharsets.UTF_8))) {
            PutLifecycleRequest request = PutLifecycleRequest.of(builder -> builder
                    .name(name)
                    .policy(p -> p.withJson(is)));

            client.ilm().putLifecycle(request);
            LOGGER.info("ILM policy '{}' applied successfully", name);
        }
    }

    private void applyIndexTemplate(String name, String resourcePath) throws IOException {
        if (indexTemplateExists(name)) {
            LOGGER.info("Index template '{}' already exists, skipping", name);
            return;
        }

        LOGGER.info("Applying index template '{}'...", name);
        String json = loadResourceAsString(resourcePath);

        try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            PutIndexTemplateRequest request = PutIndexTemplateRequest.of(builder -> builder
                    .name(name)
                    .withJson(is));

            client.indices().putIndexTemplate(request);
            LOGGER.info("Index template '{}' applied successfully", name);
        }
    }

    private void applyTransform(String name, String resourcePath) throws IOException {
        if (transformExists(name)) {
            LOGGER.info("Transform '{}' already exists, skipping", name);
            return;
        }

        LOGGER.info("Applying transform '{}'...", name);
        String json = loadResourceAsString(resourcePath);

        try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            PutTransformRequest request = PutTransformRequest.of(builder -> builder
                    .transformId(name)
                    .withJson(is));

            client.transform().putTransform(request);
            LOGGER.info("Transform '{}' applied successfully", name);
        }
    }

    private boolean ilmPolicyExists(String name) {
        try {
            GetLifecycleRequest request = GetLifecycleRequest.of(builder -> builder.name(name));
            client.ilm().getLifecycle(request);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check if ILM policy exists: " + name, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to check if ILM policy exists: " + name, e);
        }
    }

    private boolean indexTemplateExists(String name) {
        try {
            GetIndexTemplateRequest request = GetIndexTemplateRequest.of(builder -> builder.name(name));
            client.indices().getIndexTemplate(request);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check if index template exists: " + name, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to check if index template exists: " + name, e);
        }
    }

    private boolean transformExists(String name) {
        try {
            GetTransformRequest request = GetTransformRequest.of(builder -> builder.transformId(name));
            client.transform().getTransform(request);
            return true;
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                return false;
            }
            throw new RuntimeException("Failed to check if transform exists: " + name, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to check if transform exists: " + name, e);
        }
    }

    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalArgumentException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes());
        }
    }

    private String extractResourceName(String resourcePath) {
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return fileName.substring(0, fileName.lastIndexOf('.'));
    }
}
