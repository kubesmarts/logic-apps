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
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.mapping.Property;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleRequest;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleResponse;
import co.elastic.clients.elasticsearch.ilm.IlmPolicy;
import co.elastic.clients.elasticsearch.ilm.Phase;
import co.elastic.clients.elasticsearch.ilm.get_lifecycle.Lifecycle;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.IndexTemplate;
import co.elastic.clients.elasticsearch.indices.get_index_template.IndexTemplateItem;
import co.elastic.clients.elasticsearch.transform.GetTransformRequest;
import co.elastic.clients.elasticsearch.transform.GetTransformResponse;
import co.elastic.clients.elasticsearch.transform.get_transform.TransformSummary;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(ElasticsearchSchemaTestProfile.class)
class ElasticsearchSchemaInitializationIT {

    @Inject
    ElasticsearchClient client;

    @Test
    void testIlmPolicyApplied() throws IOException {
        GetLifecycleRequest request = GetLifecycleRequest.of(b -> b.name("data-index-events-retention"));
        GetLifecycleResponse response = client.ilm().getLifecycle(request);

        Lifecycle lifecycle = response.get("data-index-events-retention");
        assertThat(lifecycle).isNotNull();

        IlmPolicy policy = lifecycle.policy();
        assertThat(policy).isNotNull();
        assertThat(policy.phases()).isNotNull();

        Phase hotPhase = policy.phases().hot();
        assertThat(hotPhase).isNotNull();
        assertThat(hotPhase.actions()).isNotNull();

        Phase deletePhase = policy.phases().delete();
        assertThat(deletePhase).isNotNull();
        assertThat(deletePhase.minAge()).isNotNull();
        Time minAge = deletePhase.minAge();
        assertThat(minAge.time()).isEqualTo("7d");
        assertThat(deletePhase.actions()).isNotNull();
    }

    @Test
    void testWorkflowEventsIndexTemplateApplied() throws IOException {
        GetIndexTemplateRequest request = GetIndexTemplateRequest.of(b -> b.name("workflow-events"));
        GetIndexTemplateResponse response = client.indices().getIndexTemplate(request);

        assertThat(response.indexTemplates()).isNotEmpty();
        assertThat(response.indexTemplates()).hasSize(1);

        IndexTemplateItem templateItem = response.indexTemplates().get(0);
        IndexTemplate template = templateItem.indexTemplate();
        assertThat(template).isNotNull();

        assertThat(template.indexPatterns()).contains("workflow-events-*");

        assertThat(template.template()).isNotNull();
        assertThat(template.template().settings()).isNotNull();

        assertThat(template.template().mappings()).isNotNull();
        assertThat(template.template().mappings().properties()).isNotNull();

        Map<String, Property> properties = template.template().mappings().properties();

        assertThat(properties).containsKey("@timestamp");
        assertThat(properties.get("@timestamp").date()).isNotNull();

        assertThat(properties).containsKey("tag");
        assertThat(properties.get("tag").keyword()).isNotNull();

        assertThat(properties).containsKey("event_id");
        assertThat(properties.get("event_id").keyword()).isNotNull();

        assertThat(properties).containsKey("event_type");
        assertThat(properties.get("event_type").keyword()).isNotNull();

        assertThat(properties).containsKey("event_time");
        assertThat(properties.get("event_time").date()).isNotNull();

        assertThat(properties).containsKey("instance_id");
        assertThat(properties.get("instance_id").keyword()).isNotNull();

        assertThat(properties).containsKey("workflow_name");
        assertThat(properties.get("workflow_name").keyword()).isNotNull();

        assertThat(properties).containsKey("workflow_version");
        assertThat(properties.get("workflow_version").keyword()).isNotNull();

        assertThat(properties).containsKey("workflow_namespace");
        assertThat(properties.get("workflow_namespace").keyword()).isNotNull();

        assertThat(properties).containsKey("status");
        assertThat(properties.get("status").keyword()).isNotNull();

        assertThat(properties).containsKey("start");
        assertThat(properties.get("start").date()).isNotNull();

        assertThat(properties).containsKey("end");
        assertThat(properties.get("end").date()).isNotNull();

        assertThat(properties).containsKey("input");
        assertThat(properties.get("input").flattened()).isNotNull();

        assertThat(properties).containsKey("output");
        assertThat(properties.get("output").flattened()).isNotNull();

        assertThat(properties).containsKey("error");
        assertThat(properties.get("error").object()).isNotNull();
        var errorProps = properties.get("error").object().properties();
        assertThat(errorProps).containsKey("type");
        assertThat(errorProps.get("type").keyword()).isNotNull();
        assertThat(errorProps).containsKey("title");
        assertThat(errorProps.get("title").text()).isNotNull();
        assertThat(errorProps).containsKey("detail");
        assertThat(errorProps.get("detail").text()).isNotNull();
        assertThat(errorProps).containsKey("status");
        assertThat(errorProps.get("status").integer()).isNotNull();
        assertThat(errorProps).containsKey("instance");
        assertThat(errorProps.get("instance").keyword()).isNotNull();
    }

    @Test
    void testWorkflowInstancesIndexTemplateApplied() throws IOException {
        GetIndexTemplateRequest request = GetIndexTemplateRequest.of(b -> b.name("workflow-instances"));
        GetIndexTemplateResponse response = client.indices().getIndexTemplate(request);

        assertThat(response.indexTemplates()).isNotEmpty();
        assertThat(response.indexTemplates()).hasSize(1);

        IndexTemplateItem templateItem = response.indexTemplates().get(0);
        IndexTemplate template = templateItem.indexTemplate();
        assertThat(template).isNotNull();

        assertThat(template.indexPatterns()).contains("workflow-instances");

        assertThat(template.template()).isNotNull();
        assertThat(template.template().settings()).isNotNull();

        assertThat(template.template().mappings()).isNotNull();
        assertThat(template.template().mappings().properties()).isNotNull();

        Map<String, Property> properties = template.template().mappings().properties();

        assertThat(properties).containsKey("id");
        assertThat(properties.get("id").keyword()).isNotNull();

        assertThat(properties).containsKey("name");
        assertThat(properties.get("name").keyword()).isNotNull();

        assertThat(properties).containsKey("version");
        assertThat(properties.get("version").keyword()).isNotNull();

        assertThat(properties).containsKey("namespace");
        assertThat(properties.get("namespace").keyword()).isNotNull();

        assertThat(properties).containsKey("status");
        assertThat(properties.get("status").keyword()).isNotNull();

        assertThat(properties).containsKey("start");
        assertThat(properties.get("start").date()).isNotNull();

        assertThat(properties).containsKey("end");
        assertThat(properties.get("end").date()).isNotNull();

        assertThat(properties).containsKey("input");
        assertThat(properties.get("input").flattened()).isNotNull();

        assertThat(properties).containsKey("output");
        assertThat(properties.get("output").flattened()).isNotNull();

        assertThat(properties).containsKey("error");
        assertThat(properties.get("error").object()).isNotNull();
        var errorProps = properties.get("error").object().properties();
        assertThat(errorProps).containsKey("type");
        assertThat(errorProps.get("type").keyword()).isNotNull();
        assertThat(errorProps).containsKey("title");
        assertThat(errorProps.get("title").text()).isNotNull();
        assertThat(errorProps).containsKey("detail");
        assertThat(errorProps.get("detail").text()).isNotNull();
        assertThat(errorProps).containsKey("status");
        assertThat(errorProps.get("status").integer()).isNotNull();
        assertThat(errorProps).containsKey("instance");
        assertThat(errorProps.get("instance").keyword()).isNotNull();

        assertThat(properties).containsKey("last_update");
        assertThat(properties.get("last_update").date()).isNotNull();
    }

    @Test
    void testTransformApplied() throws IOException {
        GetTransformRequest request = GetTransformRequest.of(b -> b.transformId("workflow-instances-transform"));
        GetTransformResponse response = client.transform().getTransform(request);

        assertThat(response.transforms()).isNotEmpty();
        assertThat(response.transforms()).hasSize(1);

        TransformSummary transform = response.transforms().get(0);
        assertThat(transform).isNotNull();
        assertThat(transform.id()).isEqualTo("workflow-instances-transform");

        assertThat(transform.source()).isNotNull();
        assertThat(transform.source().index()).contains("workflow-events-*");
        assertThat(transform.source().query()).isNotNull();

        assertThat(transform.dest()).isNotNull();
        assertThat(transform.dest().index()).isEqualTo("workflow-instances");

        assertThat(transform.frequency()).isNotNull();
        assertThat(transform.frequency().time()).isEqualTo("1s");

        assertThat(transform.sync()).isNotNull();
        assertThat(transform.sync().time()).isNotNull();
        assertThat(transform.sync().time().field()).isEqualTo("event_time");
        assertThat(transform.sync().time().delay()).isNotNull();
        assertThat(transform.sync().time().delay().time()).isEqualTo("5m");

        assertThat(transform.pivot()).isNotNull();
        assertThat(transform.pivot().groupBy()).isNotNull();
        assertThat(transform.pivot().groupBy()).containsKey("id");
        assertThat(transform.pivot().aggregations()).isNotNull();
        assertThat(transform.pivot().aggregations()).containsKey("name");
        assertThat(transform.pivot().aggregations()).containsKey("version");
        assertThat(transform.pivot().aggregations()).containsKey("namespace");
        assertThat(transform.pivot().aggregations()).containsKey("status");
        assertThat(transform.pivot().aggregations()).containsKey("start");
        assertThat(transform.pivot().aggregations()).containsKey("end");
        assertThat(transform.pivot().aggregations()).containsKey("input");
        assertThat(transform.pivot().aggregations()).containsKey("output");
        assertThat(transform.pivot().aggregations()).containsKey("error");
        assertThat(transform.pivot().aggregations()).containsKey("last_update");
    }

    @Test
    void testIdempotency() throws IOException {
        GetLifecycleRequest ilmRequest = GetLifecycleRequest.of(b -> b.name("data-index-events-retention"));
        GetLifecycleResponse ilmResponse1 = client.ilm().getLifecycle(ilmRequest);
        assertThat(ilmResponse1.get("data-index-events-retention")).isNotNull();

        GetIndexTemplateRequest eventsTemplateRequest = GetIndexTemplateRequest.of(b -> b.name("workflow-events"));
        GetIndexTemplateResponse eventsResponse1 = client.indices().getIndexTemplate(eventsTemplateRequest);
        assertThat(eventsResponse1.indexTemplates()).hasSize(1);

        GetIndexTemplateRequest instancesTemplateRequest = GetIndexTemplateRequest.of(b -> b.name("workflow-instances"));
        GetIndexTemplateResponse instancesResponse1 = client.indices().getIndexTemplate(instancesTemplateRequest);
        assertThat(instancesResponse1.indexTemplates()).hasSize(1);

        GetTransformRequest transformRequest = GetTransformRequest.of(b -> b.transformId("workflow-instances-transform"));
        GetTransformResponse transformResponse1 = client.transform().getTransform(transformRequest);
        assertThat(transformResponse1.transforms()).hasSize(1);

        GetLifecycleResponse ilmResponse2 = client.ilm().getLifecycle(ilmRequest);
        assertThat(ilmResponse2.get("data-index-events-retention")).isNotNull();

        GetIndexTemplateResponse eventsResponse2 = client.indices().getIndexTemplate(eventsTemplateRequest);
        assertThat(eventsResponse2.indexTemplates()).hasSize(1);

        GetIndexTemplateResponse instancesResponse2 = client.indices().getIndexTemplate(instancesTemplateRequest);
        assertThat(instancesResponse2.indexTemplates()).hasSize(1);

        GetTransformResponse transformResponse2 = client.transform().getTransform(transformRequest);
        assertThat(transformResponse2.transforms()).hasSize(1);
    }
}
