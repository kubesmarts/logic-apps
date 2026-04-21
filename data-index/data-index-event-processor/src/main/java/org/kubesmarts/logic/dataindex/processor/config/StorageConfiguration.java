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
package org.kubesmarts.logic.dataindex.processor.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Storage backend configuration.
 *
 * <p>Configuration prefix: {@code data-index.storage}
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * @Inject
 * StorageConfiguration config;
 *
 * if (config.backend() == StorageConfiguration.Backend.POSTGRESQL) {
 *     // PostgreSQL storage active
 * }
 * }</pre>
 *
 * <p><b>Configuration example:</b>
 * <pre>
 * # Storage backend: postgresql | elasticsearch
 * data-index.storage.backend=postgresql
 * </pre>
 *
 * @see Backend
 */
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "data-index.storage")
public interface StorageConfiguration {

    /**
     * Storage backend.
     *
     * <p><b>Values:</b>
     * <ul>
     *   <li>{@code POSTGRESQL} - PostgreSQL with JPA/Hibernate
     *   <li>{@code ELASTICSEARCH} - Elasticsearch with REST client
     * </ul>
     *
     * <p><b>Default:</b> {@code POSTGRESQL}
     *
     * @return storage backend
     */
    @WithDefault("postgresql")
    Backend backend();

    /**
     * Storage backend enumeration.
     */
    enum Backend {
        /**
         * PostgreSQL storage with JPA/Hibernate.
         *
         * <p><b>Use case:</b> Relational data, ACID transactions, complex queries
         */
        POSTGRESQL,

        /**
         * Elasticsearch storage with REST client.
         *
         * <p><b>Use case:</b> Full-text search, analytics, log aggregation, high throughput
         */
        ELASTICSEARCH
    }
}
