# Data Index GraphQL API

**Version:** 1.0.0  
**Endpoint:** `http://localhost:30080/graphql` (KIND cluster)  
**Status:** ✅ Production Ready

## Overview

The Data Index exposes a read-only GraphQL API for querying workflow instances and task executions. Built with SmallRye GraphQL on Quarkus.

## Endpoints

| Endpoint | Purpose | Status |
|----------|---------|--------|
| `/graphql` | GraphQL API | ✅ Working |
| `/graphql-ui` | GraphiQL UI | ⚠️ Not Available (404) |
| `/q/health` | Health Check | ✅ Working |
| `/q/metrics` | Prometheus Metrics | ✅ Working |

**Note:** GraphQL UI is not available in production mode. Use curl, Postman, or online GraphQL clients.

## Quick Start

### Test Connection

```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __schema { queryType { name } } }"}'
```

### Get All Workflows

```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id namespace name version status startDate endDate } }"}'
```

## Schema

### Types

#### WorkflowInstance

```graphql
type WorkflowInstance {
  id: String!
  namespace: String
  name: String
  version: String
  status: WorkflowInstanceStatus!
  startDate: DateTime
  endDate: DateTime
  lastUpdate: DateTime
  error: WorkflowInstanceError
  taskExecutions: [TaskExecution]
}
```

**Field Notes:**
- `id` - Unique instance ID (ULID format)
- `status` - RUNNING | COMPLETED | FAULTED | CANCELLED | SUSPENDED
- `startDate` - Workflow started timestamp (ISO 8601)
- `endDate` - Workflow ended timestamp (null if still running)
- `lastUpdate` - Last status change timestamp
- `error` - Error details (present only if FAULTED)

#### TaskExecution

```graphql
type TaskExecution {
  id: String!
  taskName: String
  taskPosition: String
  status: TaskExecutionStatus!
  startDate: DateTime
  endDate: DateTime
  workflowInstance: WorkflowInstance
}
```

**Field Notes:**
- `id` - Unique task execution ID
- `taskPosition` - Position in workflow (e.g., "do/0/set-0")
- `status` - RUNNING | COMPLETED | FAULTED
- `workflowInstance` - Parent workflow (bidirectional relationship)

#### WorkflowInstanceError

```graphql
type WorkflowInstanceError {
  type: String
  title: String
  detail: String
  status: Int
  instance: String
}
```

**RFC 7807 Problem Details:**
- `type` - Error type URI
- `title` - Human-readable summary
- `detail` - Detailed error description
- `status` - HTTP status code equivalent
- `instance` - URI reference to specific error occurrence

### Queries

#### Get Single Workflow

```graphql
query {
  getWorkflowInstance(id: "01KPZY3F6HPMVHSSXKBKS11NQ2") {
    id
    name
    status
    startDate
    endDate
    taskExecutions {
      taskName
      status
    }
  }
}
```

#### Get All Workflows

```graphql
query {
  getWorkflowInstances {
    id
    namespace
    name
    version
    status
    startDate
    endDate
    lastUpdate
  }
}
```

#### Get Workflows with Filtering (Planned)

```graphql
query {
  getWorkflowInstances(
    filter: {
      namespace: "org.acme"
      status: COMPLETED
    }
    orderBy: { field: START_DATE, direction: DESC }
    limit: 10
    offset: 0
  ) {
    id
    name
    status
  }
}
```

**Note:** Filtering is implemented in the GraphQL schema but may require additional testing.

#### Get Tasks for Workflow

```graphql
query {
  getTaskExecutionsByWorkflowInstance(instanceId: "01KPZY3F6HPMVHSSXKBKS11NQ2") {
    id
    taskName
    taskPosition
    status
    startDate
    endDate
  }
}
```

#### Get Single Task

```graphql
query {
  getTaskExecution(id: "82d04e1f-bc32-3786-9d9c-56630ab0e168") {
    id
    taskName
    status
    workflowInstance {
      id
      name
    }
  }
}
```

#### Get All Tasks

```graphql
query {
  getTaskExecutions {
    id
    taskName
    status
    startDate
  }
}
```

## Using External GraphQL Clients

Since `/graphql-ui` is not available, use these alternatives:

### Option 1: Altair GraphQL Client (Online)

1. Visit https://altair.sirmuel.design/
2. Set endpoint: `http://localhost:30080/graphql`
3. Use the visual query builder with autocomplete

### Option 2: Postman

1. Create new request
2. Set type to GraphQL
3. Enter endpoint: `http://localhost:30080/graphql`
4. Use schema introspection for autocomplete

### Option 3: Insomnia

1. Create GraphQL request
2. Enter endpoint: `http://localhost:30080/graphql`
3. Use built-in schema viewer

### Option 4: curl

```bash
# Store query in variable for readability
QUERY='query {
  getWorkflowInstances {
    id
    namespace
    name
    status
    startDate
  }
}'

curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d "{\"query\":\"$(echo $QUERY | tr '\n' ' ')\"}" \
  | jq '.'
```

## Schema Introspection

### Get All Query Types

```graphql
{
  __schema {
    queryType {
      fields {
        name
        description
      }
    }
  }
}
```

### Get WorkflowInstance Fields

```graphql
{
  __type(name: "WorkflowInstance") {
    fields {
      name
      type {
        name
        kind
      }
      description
    }
  }
}
```

### Get Enums

```graphql
{
  __type(name: "WorkflowInstanceStatus") {
    enumValues {
      name
      description
    }
  }
}
```

## Error Handling

### Common Errors

#### Field Not Found

```json
{
  "errors": [{
    "message": "Validation error (FieldUndefined@[getWorkflowInstances/createdAt]) : Field 'createdAt' in type 'WorkflowInstance' is undefined",
    "locations": [{"line": 1, "column": 51}],
    "extensions": {"classification": "ValidationError"}
  }]
}
```

**Solution:** Use correct field names (check schema with introspection).

#### Invalid ID

```json
{
  "data": {
    "getWorkflowInstance": null
  }
}
```

**Solution:** Returns `null` for non-existent IDs (not an error).

## Performance Considerations

### Pagination

Use `limit` and `offset` for large result sets:

```graphql
query {
  getWorkflowInstances(limit: 100, offset: 0) {
    id
    name
  }
}
```

### Field Selection

Only request fields you need:

```graphql
# ❌ Bad: Over-fetching
query {
  getWorkflowInstances {
    id
    namespace
    name
    version
    status
    startDate
    endDate
    lastUpdate
    error { type title detail status instance }
    taskExecutions { id taskName status startDate endDate }
  }
}

# ✅ Good: Minimal fields
query {
  getWorkflowInstances {
    id
    name
    status
  }
}
```

### N+1 Query Problem

The GraphQL API uses JPA with proper fetch strategies to avoid N+1 queries:

```graphql
# This does NOT cause N+1 - taskExecutions are fetched efficiently
query {
  getWorkflowInstances {
    id
    taskExecutions {
      taskName
    }
  }
}
```

## Testing

### Health Check

```bash
curl http://localhost:30080/q/health | jq '.'
```

Expected response:
```json
{
  "status": "UP",
  "checks": [{
    "name": "Database connections health check",
    "status": "UP",
    "data": {"<default>": "UP"}
  }]
}
```

### Sample Data

To test the API, execute workflows using the test app:

```bash
kubectl port-forward -n workflows svc/workflow-test-app 8082:8080 &

curl -X POST http://localhost:8082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name": "GraphQL Test"}'
```

Then query the Data Index:

```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(limit: 1) { id name status } }"}' \
  | jq '.'
```

## Configuration

GraphQL configuration in `application.properties`:

```properties
# SmallRye GraphQL
quarkus.smallrye-graphql.root-path=/graphql
quarkus.smallrye-graphql.ui.enabled=true
quarkus.smallrye-graphql.ui.root-path=/graphql-ui
quarkus.smallrye-graphql.print-data-fetcher-exception=true
quarkus.smallrye-graphql.log-payload=queryAndVariables
```

## Monitoring

### Request Logging

SmallRye GraphQL logs all requests/responses:

```
SRGQL011005: Payload In [{ getWorkflowInstances { id name } }]
SRGQL011006: Payload Out [{"data":{"getWorkflowInstances":[...]}}]
```

View logs:
```bash
kubectl logs -n data-index deployment/data-index-service -f | grep SRGQL
```

### Metrics

Prometheus metrics available at `/q/metrics`:

```bash
curl http://localhost:30080/q/metrics | grep graphql
```

## Future Enhancements

- [ ] Enable GraphQL UI in production builds
- [ ] Add GraphQL subscriptions (real-time updates)
- [ ] Implement DataLoader for batch loading
- [ ] Add GraphQL query complexity limits
- [ ] Implement field-level security
- [ ] Add GraphQL federation support

## References

- SmallRye GraphQL: https://smallrye.io/smallrye-graphql/
- GraphQL Spec: https://spec.graphql.org/
- Quarkus GraphQL Guide: https://quarkus.io/guides/smallrye-graphql
