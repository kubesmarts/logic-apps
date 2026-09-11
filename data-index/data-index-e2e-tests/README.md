# Data Index E2E Tests

End-to-end tests for Data Index Helm deployments. These tests assume a running Kubernetes cluster with Helm-deployed Data Index components.

## Overview

Unlike integration tests (`@QuarkusTest`) that spin up their own environment, E2E tests verify a real Helm deployment:

- **MODE 1**: PostgreSQL + Vector + Triggers
- **MODE 2**: Elasticsearch + Vector + Transforms  
- **MODE 3**: Kafka + Ingestion Service + PostgreSQL

## Prerequisites

1. **KIND cluster** with Data Index Helm chart deployed
2. **Port forwarding** for services (or use NodePort):
   ```bash
   kubectl port-forward -n default svc/data-index 30080:8080
   kubectl port-forward -n workflows svc/workflow-test-app 30082:8080
   ```

## Running Tests

### Quick Start (MODE 1)

```bash
# 1. Deploy MODE 1 with Helm
cd data-index/helm/data-index
helm install data-index . -f values-mode1.yaml

# 2. Wait for pods ready
kubectl wait --for=condition=ready pod -l app=data-index --timeout=120s

# 3. Run E2E tests
cd ../../data-index-e2e-tests
mvn test -De2e.mode=mode1
```

### Mode-Specific Tests

**MODE 1 (PostgreSQL + Vector):**
```bash
mvn test -De2e.mode=mode1 \
  -De2e.graphql.url=http://localhost:30080/graphql \
  -De2e.workflow.url=http://localhost:30082
```

**MODE 2 (Elasticsearch + Vector):**
```bash
mvn test -De2e.mode=mode2 \
  -De2e.graphql.url=http://localhost:30080/graphql \
  -De2e.workflow.url=http://localhost:30082
```

**MODE 3 (Kafka + Ingestion):**
```bash
mvn test -De2e.mode=mode3 \
  -De2e.graphql.url=http://localhost:30080/graphql \
  -De2e.workflow.url=http://localhost:30082
```

### Using NodePort Services

If Helm chart is deployed with `NodePort` services:

```bash
# MODE 1 with NodePort
mvn test -De2e.mode=mode1 \
  -De2e.graphql.url=http://localhost:30080/graphql \
  -De2e.workflow.url=http://localhost:30082
```

No port-forwarding needed!

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `e2e.graphql.url` | `http://localhost:30080/graphql` | Data Index GraphQL API URL |
| `e2e.workflow.url` | `http://localhost:30082` | Workflow Test App URL |
| `e2e.mode` | *(required)* | Deployment mode (mode1, mode2, mode3) |
| `e2e.skip` | `true` | Skip E2E tests (must set to `false` to run) |

## Test Structure

```
data-index-e2e-tests/
└── DataIndexE2ETest.java      # All E2E tests (runs for all modes)
```

The test class:
- Uses `@EnabledIfSystemProperty` to run only when `e2e.mode` is set
- Contains 4 tests that run for all modes:
  - `testGraphQLSchemaIntrospection` - Verify GraphQL schema
  - `testQueryWorkflowInstances` - Query workflow instances
  - `testQueryTaskExecutions` - Query task executions  
  - `testWorkflowLifecycle` - Full workflow trigger → GraphQL query
- Mode-specific behavior (e.g., longer timeout for MODE 2 transforms)

## What Tests Verify

### All Modes
- GraphQL schema introspection works
- Can query `workflowInstances` and `taskExecutions`
- Workflow trigger → event processing → GraphQL query works

### MODE 1 Specific
- Vector kubernetes_logs → postgres sink → PostgreSQL raw → Trigger → normalized

### MODE 2 Specific  
- Vector tail → Elasticsearch raw → Transform → normalized
- Transform processing delay (1s frequency)

### MODE 3 Specific
- Kafka CloudEvents → Ingestion Service → PostgreSQL

## CI/CD Integration

```yaml
# Example GitHub Actions workflow
- name: Run MODE 1 E2E Tests
  run: |
    # Deploy with Helm
    helm install data-index ./helm/data-index -f values-mode1.yaml
    
    # Wait for ready
    kubectl wait --for=condition=ready pod -l app=data-index --timeout=120s
    
    # Run tests
    cd data-index-e2e-tests
    mvn test -De2e.mode=mode1
```

## Troubleshooting

**Connection refused:**
- Verify port-forwarding is active
- Check NodePort configuration matches test URLs

**Tests timeout waiting for workflow:**
- Check workflow test app logs: `kubectl logs -l app=workflow-test-app`
- Check data index logs: `kubectl logs -l app=data-index`
- Verify Vector/Kafka is running

**GraphQL not ready:**
- Increase timeout in `waitForGraphQLReady()`
- Check Data Index pod status
- Verify backend (PostgreSQL/Elasticsearch) is healthy

## Development

Add new test to `DataIndexE2ETest.java`:

```java
@Test
public void testMyNewFeature() {
    log.info("Testing my new feature...");
    
    // Use helper methods
    String instanceId = triggerWorkflow("my-workflow");
    waitForWorkflowInstance(instanceId);
    
    String query = "{ getWorkflowInstance(id: \"" + instanceId + "\") { ... } }";
    Response response = executeGraphQL(query);
    
    // Assertions
    response.then().statusCode(200);
    assertThat(response.jsonPath().getString("data.getWorkflowInstance.id"))
        .isEqualTo(instanceId);
    
    log.info("✓ My new feature test successful");
}
```

The test will automatically run for all modes (MODE 1, MODE 2, MODE 3).
