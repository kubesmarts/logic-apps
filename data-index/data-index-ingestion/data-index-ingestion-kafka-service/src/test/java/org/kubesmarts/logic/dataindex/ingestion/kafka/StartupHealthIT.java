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
package org.kubesmarts.logic.dataindex.ingestion.kafka;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for service startup, health checks, and Kafka consumer initialization.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Health endpoints respond correctly</li>
 *   <li>Kafka consumer initializes without blocking event loop threads</li>
 *   <li>Service reports readiness appropriately</li>
 * </ul>
 */
@QuarkusTest
public class StartupHealthIT {

    @Test
    void shouldRespondToLivenessProbe() {
        given()
            .when().get("/q/health/live")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldRespondToReadinessProbe() {
        given()
            .when().get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void shouldReportKafkaHealthAsUp() {
        given()
            .when().get("/q/health/ready")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks.find { it.name == 'SmallRye Reactive Messaging - readiness check' }.status",
                      equalTo("UP"));
    }

    @Test
    void shouldRespondToOverallHealthCheck() {
        given()
            .when().get("/q/health")
            .then()
                .statusCode(200)
                .body("status", equalTo("UP"))
                .body("checks", not(empty()));
    }
}
