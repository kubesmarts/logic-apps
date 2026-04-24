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
package org.kubesmarts.logic.dataindex.json;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Singleton holder for Quarkus-managed ObjectMapper.
 *
 * <p><b>Purpose</b>: Provides access to Quarkus-managed ObjectMapper for components
 * that cannot use CDI injection (e.g., JPA AttributeConverters, GraphQL scalars).
 *
 * <p><b>Usage</b>:
 * <pre>
 * // In CDI beans - prefer direct injection:
 * {@literal @}Inject ObjectMapper objectMapper;
 *
 * // In non-CDI components (JPA converters, etc.):
 * ObjectMapper mapper = ObjectMapperProducer.get();
 * </pre>
 *
 * <p><b>Why not static ObjectMapper?</b>
 * <ul>
 *   <li>Quarkus configures ObjectMapper with custom modules
 *   <li>Centralized configuration in application.properties
 *   <li>Proper lifecycle management
 *   <li>Better testability
 * </ul>
 */
@ApplicationScoped
public class ObjectMapperProducer {

    private static ObjectMapperProducer INSTANCE;

    private final ObjectMapper objectMapper;

    @Inject
    public ObjectMapperProducer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        INSTANCE = this;
    }

    /**
     * Get Quarkus-managed ObjectMapper instance.
     *
     * <p><b>For CDI beans</b>: Prefer {@code @Inject ObjectMapper} instead.
     *
     * <p><b>For non-CDI components</b>: Use this method to get the shared ObjectMapper.
     *
     * @return Quarkus-managed ObjectMapper
     */
    public static ObjectMapper get() {
        if (INSTANCE == null) {
            throw new IllegalStateException("ObjectMapperProducer not initialized - CDI container not started");
        }
        return INSTANCE.objectMapper;
    }

    /**
     * Get ObjectMapper instance (instance method for CDI injection).
     *
     * @return Quarkus-managed ObjectMapper
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }
}
