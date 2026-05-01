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
import co.elastic.clients.elasticsearch._types.ErrorResponse;
import co.elastic.clients.elasticsearch.ilm.ElasticsearchIlmClient;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleRequest;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleResponse;
import co.elastic.clients.elasticsearch.ilm.PutLifecycleRequest;
import co.elastic.clients.elasticsearch.ilm.PutLifecycleResponse;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.GetIndexTemplateResponse;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateRequest;
import co.elastic.clients.elasticsearch.indices.PutIndexTemplateResponse;
import co.elastic.clients.elasticsearch.transform.ElasticsearchTransformClient;
import co.elastic.clients.elasticsearch.transform.GetTransformRequest;
import co.elastic.clients.elasticsearch.transform.GetTransformResponse;
import co.elastic.clients.elasticsearch.transform.PutTransformRequest;
import co.elastic.clients.elasticsearch.transform.PutTransformResponse;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElasticsearchSchemaInitializerTest {

    @Mock
    private ElasticsearchClient client;

    @Mock
    private ElasticsearchIlmClient ilmClient;

    @Mock
    private ElasticsearchIndicesClient indicesClient;

    @Mock
    private ElasticsearchTransformClient transformClient;

    @InjectMocks
    private ElasticsearchSchemaInitializer initializer;

    @BeforeEach
    void setUp() {
        when(client.ilm()).thenReturn(ilmClient);
        when(client.indices()).thenReturn(indicesClient);
        when(client.transform()).thenReturn(transformClient);
    }

    @Test
    void shouldApplyIlmPoliciesIndexTemplatesAndTransformsWhenEnabled() throws IOException {
        initializer.schemaInitEnabled = true;

        ErrorResponse errorResponse = ErrorResponse.of(builder -> builder
                .error(error -> error.reason("Not Found").type("not_found"))
                .status(404));
        ElasticsearchException notFoundException = new ElasticsearchException("Not Found", errorResponse);

        when(ilmClient.getLifecycle(any(GetLifecycleRequest.class)))
                .thenThrow(notFoundException);
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class)))
                .thenThrow(notFoundException);
        when(transformClient.getTransform(any(GetTransformRequest.class)))
                .thenThrow(notFoundException);

        when(ilmClient.putLifecycle(any(PutLifecycleRequest.class))).thenReturn(mock(PutLifecycleResponse.class));
        when(indicesClient.putIndexTemplate(any(PutIndexTemplateRequest.class))).thenReturn(mock(PutIndexTemplateResponse.class));
        when(transformClient.putTransform(any(PutTransformRequest.class))).thenReturn(mock(PutTransformResponse.class));

        initializer.onStart(mock(StartupEvent.class));

        verify(ilmClient, times(1)).putLifecycle(any(PutLifecycleRequest.class));
        verify(indicesClient, times(2)).putIndexTemplate(any(PutIndexTemplateRequest.class));
        verify(transformClient, times(1)).putTransform(any(PutTransformRequest.class));
    }

    @Test
    void shouldSkipInitializationWhenDisabled() {
        initializer.schemaInitEnabled = false;

        initializer.onStart(mock(StartupEvent.class));

        verifyNoInteractions(ilmClient);
        verifyNoInteractions(indicesClient);
        verifyNoInteractions(transformClient);
    }

    @Test
    void shouldHandleIdempotentOperationWhenResourceAlreadyExists() throws IOException {
        initializer.schemaInitEnabled = true;

        when(ilmClient.getLifecycle(any(GetLifecycleRequest.class)))
                .thenReturn(mock(GetLifecycleResponse.class));
        when(indicesClient.getIndexTemplate(any(GetIndexTemplateRequest.class)))
                .thenReturn(mock(GetIndexTemplateResponse.class));
        when(transformClient.getTransform(any(GetTransformRequest.class)))
                .thenReturn(mock(GetTransformResponse.class));

        initializer.onStart(mock(StartupEvent.class));

        verify(ilmClient, never()).putLifecycle(any(PutLifecycleRequest.class));
        verify(indicesClient, never()).putIndexTemplate(any(PutIndexTemplateRequest.class));
        verify(transformClient, never()).putTransform(any(PutTransformRequest.class));
    }

    @Test
    void shouldThrowExceptionWhenResourceFileNotFound() throws IOException {
        initializer.schemaInitEnabled = true;
        initializer.ilmPolicyResources = new String[]{"/nonexistent.json"};

        ErrorResponse errorResponse = ErrorResponse.of(builder -> builder
                .error(error -> error.reason("Not Found").type("not_found"))
                .status(404));
        ElasticsearchException notFoundException = new ElasticsearchException("Not Found", errorResponse);

        when(ilmClient.getLifecycle(any(GetLifecycleRequest.class)))
                .thenThrow(notFoundException);

        assertThatThrownBy(() -> initializer.onStart(mock(StartupEvent.class)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initialize Elasticsearch schema")
                .hasCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldThrowExceptionWhenElasticsearchApiError() throws IOException {
        initializer.schemaInitEnabled = true;

        when(ilmClient.getLifecycle(any(GetLifecycleRequest.class)))
                .thenThrow(new IOException("Connection failed"));

        assertThatThrownBy(() -> initializer.onStart(mock(StartupEvent.class)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to initialize Elasticsearch schema")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void shouldSkipInitializationWhenUniversalFlagIsTrue() {
        initializer.skipInitSchema = true;
        initializer.schemaInitEnabled = true;

        initializer.onStart(mock(StartupEvent.class));

        verifyNoInteractions(ilmClient);
        verifyNoInteractions(indicesClient);
        verifyNoInteractions(transformClient);
    }

    @Test
    void shouldSkipInitializationWhenBothFlagsAreTrue() {
        initializer.skipInitSchema = true;
        initializer.schemaInitEnabled = false;

        initializer.onStart(mock(StartupEvent.class));

        verifyNoInteractions(ilmClient);
        verifyNoInteractions(indicesClient);
        verifyNoInteractions(transformClient);
    }
}
