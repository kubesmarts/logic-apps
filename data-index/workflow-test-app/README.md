# Workflow Test App

Quarkus Flow test application for Data Index integration testing.

## Overview

This is a simple Quarkus Flow application that:

- Defines Java DSL workflows for testing
- Emits structured logging events
- Used for end-to-end integration tests with Data Index

**Purpose:** Test event collection → FluentBit → PostgreSQL → Data Index GraphQL API

## Workflows

### SimpleSetWorkflow

Basic workflow with two sequential set tasks:

```java
@Workflow(id = "simple-set", name = "Simple Set Workflow", version = "1.0")
public class SimpleSetWorkflow implements WorkflowDefinition {
    @Override
    public WorkflowBuilder define() {
        return Workflow.builder()
            .doSet("set-0")
                .set("$.result.value0", "\"test-value-0\"")
            .end()
            .doSet("set-1")
                .set("$.result.value1", "\"test-value-1\"")
            .end();
    }
}
```

**Tests:**
- Basic workflow execution
- Multiple task executions
- Start/completion events

### HelloWorldWorkflow

Simple hello world example:

```java
@Workflow(id = "hello-world", name = "Hello World Workflow", version = "1.0")
public class HelloWorldWorkflow implements WorkflowDefinition {
    @Override
    public WorkflowBuilder define() {
        return Workflow.builder()
            .doSet("greeting")
                .set("$.result.message", "\"Hello, World!\"")
            .end();
    }
}
```

**Tests:**
- Simplest possible workflow
- Single task execution

## REST Endpoint

### WorkflowTestResource

Exposes workflows via REST API:

```java
@Path("/test-workflows")
public class WorkflowTestResource {
    
    @POST
    @Path("/{workflowId}")
    public Response executeWorkflow(
        @PathParam("workflowId") String workflowId,
        Map<String, Object> input
    ) {
        // Execute workflow and return result
    }
}
```

**Usage:**
```bash
# Execute simple-set workflow
curl -X POST http://localhost:8080/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"test": "data"}'

# Execute hello-world workflow
curl -X POST http://localhost:8080/test-workflows/hello-world \
  -H "Content-Type: application/json" \
  -d '{}'
```

## Configuration

### Structured Logging

Configured to emit events in epoch-seconds format (Data Index requirement):

```properties
# Enable structured logging
quarkus.flow.structured-logging.enabled=true

# Timestamp format: epoch-seconds (required for Data Index)
quarkus.flow.structured-logging.timestamp-format=epoch-seconds

# Console handler for structured events
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled=true
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".format=%s%n

# Route structured logging to dedicated handler
quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS_CONSOLE
quarkus.log.category."io.quarkiverse.flow.structuredlogging".use-parent-handlers=false
quarkus.log.category."io.quarkiverse.flow.structuredlogging".level=INFO
```

**Output format:**
```json
{"eventType":"io.serverlessworkflow.workflow.started.v1","instanceId":"abc-123",...,"timestamp":1714291200}
{"eventType":"io.serverlessworkflow.task.started.v1","instanceId":"abc-123",...,"timestamp":1714291201}
```

### Kubernetes Deployment

```properties
# Deploy to workflows namespace (FluentBit requirement)
quarkus.kubernetes.namespace=workflows

# Labels for FluentBit filtering
quarkus.kubernetes.labels."app"=workflow-test-app
quarkus.kubernetes.labels."version"=1.0.0
```

## Building

### Development Mode

```bash
mvn quarkus:dev
```

**Access:** http://localhost:8080

### Production Build

```bash
mvn clean package -DskipTests
```

**Result:** `target/quarkus-app/` (JVM mode)

### Container Image

```bash
mvn package -Dquarkus.container-image.build=true
```

**Result:** `kubesmarts/workflow-test-app:999-SNAPSHOT`

## Deployment

### KIND (Local Testing)

```bash
# From data-index/scripts/kind/
./deploy-workflow-app.sh
```

**What this does:**
1. Builds container image with Jib
2. Loads image to KIND cluster
3. Deploys to `workflows` namespace
4. Exposes via NodePort 30082

### Manual Kubernetes Deployment

```bash
# Build and load image
mvn package -Dquarkus.container-image.build=true
kind load docker-image kubesmarts/workflow-test-app:999-SNAPSHOT

# Deploy
kubectl create namespace workflows
kubectl apply -f target/kubernetes/kubernetes.yml -n workflows
```

## Testing

### Execute Workflow via REST

```bash
# Port-forward to service
kubectl port-forward -n workflows svc/workflow-test-app 8082:8080

# Execute workflow
curl -X POST http://localhost:8082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name": "E2E Test"}'
```

### Verify Events in Data Index

```bash
# Wait for events to propagate (5-10 seconds)
sleep 10

# Query GraphQL API
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(limit: 1) { id name status taskExecutions { taskPosition status } } }"}'
```

## Event Flow

```
Workflow Execution
    ↓
Quarkus Flow Structured Logging
    ↓
stdout (JSON events with epoch-seconds timestamp)
    ↓
Kubernetes captures to /var/log/containers/workflow-test-app-*.log
    ↓
FluentBit DaemonSet tails logs
    ↓
PostgreSQL raw tables (workflow_events_raw, task_events_raw)
    ↓
PostgreSQL triggers normalize to workflow_instances, task_instances
    ↓
Data Index GraphQL API
```

## Dependencies

### Quarkus Flow

```xml
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow</artifactId>
  <version>0.9.0</version>
</dependency>
```

**Provides:**
- Workflow definition API (`WorkflowDefinition`, `WorkflowBuilder`)
- Workflow execution engine
- Structured logging emission

### Quarkus REST

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-rest</artifactId>
</dependency>
```

**Provides:** JAX-RS endpoint for triggering workflows

## Logs

### Application Logs

Standard Quarkus logs:
```
kubectl logs -n workflows -l app=workflow-test-app
```

### Structured Events

Filter for workflow events only:
```bash
kubectl logs -n workflows -l app=workflow-test-app | grep eventType
```

**Example:**
```json
{"eventType":"io.serverlessworkflow.workflow.started.v1","instanceId":"f3c9d8b7-123","name":"simple-set",...}
{"eventType":"io.serverlessworkflow.task.started.v1","instanceId":"f3c9d8b7-123","taskPosition":"/do/0",...}
{"eventType":"io.serverlessworkflow.task.completed.v1","instanceId":"f3c9d8b7-123","taskPosition":"/do/0",...}
```

## Integration with Data Index

### FluentBit Collection

FluentBit DaemonSet collects logs from this app:

```yaml
# fluent-bit.conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_workflows_workflow-test-app-*.log
    Parser            cri
    Tag               kube.*
```

### Namespace Requirement

**IMPORTANT:** Must deploy to `workflows` namespace (or configure FluentBit to watch your namespace).

FluentBit filters by namespace pattern: `/var/log/containers/*_workflows_*.log`

### Event Format Requirement

**IMPORTANT:** Timestamp format MUST be `epoch-seconds` for Data Index compatibility.

```properties
quarkus.flow.structured-logging.timestamp-format=epoch-seconds
```

Other formats (ISO 8601, epoch-millis) will cause parsing errors in Data Index triggers.

## Related Documentation

- [Quarkus Flow Documentation](https://docs.quarkiverse.io/quarkus-flow/dev/)
- [Structured Logging Configuration](https://docs.quarkiverse.io/quarkus-flow/dev/structured-logging.html)
- [Data Index Integration Tests](../data-index-docs/modules/ROOT/pages/deployment/kind-local.adoc)
- [FluentBit Configuration](../data-index-docs/modules/ROOT/pages/deployment/fluentbit-config.adoc)

## Contributing

**When adding test workflows:**

1. Create Java DSL workflow class with `@Workflow` annotation
2. Implement `WorkflowDefinition` interface
3. Add execution endpoint in `WorkflowTestResource` (optional)
4. Test locally with `mvn quarkus:dev`
5. Verify events are emitted: `curl http://localhost:8080/test-workflows/{workflowId} -d '{}'`

**Code style:**
- Keep workflows simple (testing focus, not real-world complexity)
- Use descriptive workflow and task names
- Don't add external HTTP calls (use set tasks for simplicity)
