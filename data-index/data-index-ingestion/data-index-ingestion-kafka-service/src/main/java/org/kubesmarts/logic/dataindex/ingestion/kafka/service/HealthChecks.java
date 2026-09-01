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
package org.kubesmarts.logic.dataindex.ingestion.kafka.service;

import io.quarkus.arc.Unremovable;
import io.smallrye.health.api.Wellness;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;

/**
 * Health checks for Kafka Ingestion Service.
 *
 * Note: Quarkus automatically provides datasource and Kafka consumer health checks.
 * These are lightweight checks that don't perform blocking I/O on the event loop.
 */
@Unremovable
@ApplicationScoped
public class HealthChecks {

    @Liveness
    public HealthCheck livenessCheck() {
        return () -> HealthCheckResponse
                .named("Kafka Ingestion Service - liveness")
                .up()
                .build();
    }

    @Readiness
    public HealthCheck readinessCheck() {
        return () -> HealthCheckResponse
                .named("Kafka Ingestion Service - readiness")
                .up()
                .build();
    }

    @Wellness
    public HealthCheck wellnessCheck() {
        return readinessCheck();
    }
}

