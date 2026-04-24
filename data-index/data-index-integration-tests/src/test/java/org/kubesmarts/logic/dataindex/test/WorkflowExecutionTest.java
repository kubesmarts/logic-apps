package org.kubesmarts.logic.dataindex.test;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * End-to-end workflow execution tests with mocked HTTP endpoints.
 * <p>
 * Uses WireMock to mock httpbin.org responses, allowing tests to run
 * without external dependencies and with deterministic responses.
 * <p>
 * Tests via REST endpoints (following Quarkus Flow's pattern) instead of
 * directly awaiting CompletableFutures to avoid async completion issues in tests.
 */
@QuarkusTest
@QuarkusTestResource(HttpBinMockServer.class)
class WorkflowExecutionTest {

    @Test
    void shouldExecuteSimpleWorkflowWithoutHttp() {
        // When/Then: invoke simple workflow (no HTTP) via REST endpoint
        given()
                .contentType("application/json")
                .body("{}")
                .when().post("/test-workflows/simple-set")
                .then()
                .statusCode(200)
                .body("greeting", equalTo("Hello from Quarkus Flow!"))
                .body("timestamp", notNullValue());
    }
}
