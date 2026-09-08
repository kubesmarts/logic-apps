# Data Index E2E Tests

End-to-end tests for Data Index Helm deployments. These tests assume a running Kubernetes cluster with Helm-deployed Data Index components.

## Overview

Unlike integration tests (`@QuarkusTest`) that spin up their own environment, E2E tests verify a real Helm deployment:

- **MODE 1**: PostgreSQL + FluentBit + Triggers
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

**MODE 1 (PostgreSQL + FluentBit):**
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
| `e2e.mode` | `mode1` | Deployment mode (mode1, mode2, mode3) |
| `e2e.skip` | `false` | Skip E2E tests entirely |

## Test Structure

```
data-index-e2e-tests/
├── BaseE2ETest.java           # Shared utilities (GraphQL, wait helpers)
├── Mode1PostgresqlTest.java   # MODE 1 specific tests
├── Mode2ElasticsearchTest.java # MODE 2 specific tests
└── Mode3KafkaTest.java        # MODE 3 specific tests
```

Each test class:
- Uses `@EnabledIf` to run only for its mode
- Verifies GraphQL API works
- Tests workflow lifecycle end-to-end
- Validates mode-specific components

## What Tests Verify

### All Modes
- GraphQL schema introspection works
- Can query `workflowInstances` and `taskExecutions`
- Workflow trigger → event processing → GraphQL query works

### MODE 1 Specific
- FluentBit tail → PostgreSQL raw → Trigger → normalized

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
- Verify FluentBit/Vector/Kafka is running

**GraphQL not ready:**
- Increase timeout in `waitForGraphQLReady()`
- Check Data Index pod status
- Verify backend (PostgreSQL/Elasticsearch) is healthy

## Development

Add new test:
1. Create test class extending `BaseE2ETest`
2. Add `@EnabledIf` condition for mode
3. Use helper methods: `executeGraphQL()`, `triggerWorkflow()`, `waitForWorkflowInstance()`

Example:
```java
@EnabledIf("isMode1")
public class MyCustomTest extends BaseE2ETest {
    static boolean isMode1() {
        return "mode1".equalsIgnoreCase(System.getProperty("e2e.mode"));
    }
    
    @Test
    public void testCustomScenario() {
        // Your test here
    }
}
```
