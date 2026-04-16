# GraphQL API Testing Guide

**Date**: 2026-04-16  
**Status**: ✅ GraphQL API Operational

---

## Prerequisites

✅ **Required**:
- PostgreSQL running on port 5432
- Test data loaded (4 workflows, 7 tasks)
- Data Index service built

✅ **Already Configured**:
- SmallRye GraphQL dependency
- Application properties
- Storage layer with CDI
- MapStruct mappers

---

## Quick Start

### 1. Load Test Data

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index

# Start PostgreSQL
cd fluent-bit
docker-compose -f docker-compose-triggers.yml up -d
cd ..

# Load test data
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
  psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql
```

### 2. Start Data Index Service

```bash
# Start in dev mode
mvn quarkus:dev -pl data-index-service
```

**Expected output**:
```
__  ____  __  _____   ___  __ ____  ______ 
 --/ __ \/ / / / _ | / _ \/ //_/ / / / __/ 
 -/ /_/ / /_/ / __ |/ , _/ ,< / /_/ /\ \   
--\___\_\____/_/ |_/_/|_/_/|_|\____/___/   
INFO  [io.quarkus] data-index-service 999-SNAPSHOT on JVM started in 2.3s
INFO  [io.quarkus] Profile dev activated. Live Coding activated.
INFO  [io.quarkus] Installed features: [agroal, cdi, hibernate-orm, jdbc-postgresql, smallrye-graphql, ...]
```

### 3. Access GraphQL UI

Open your browser:
```
http://localhost:8080/graphql-ui
```

**GraphQL Playground** will load with schema explorer and query editor.

---

## Test Queries

### Query 1: Get All Workflow Instances

```graphql
query GetAllWorkflows {
  getWorkflowInstances {
    id
    namespace
    name
    status
    startDate
    endDate
  }
}
```

**Expected**: 4 workflows (COMPLETED, FAULTED, RUNNING, CANCELLED)

---

### Query 2: Get Single Workflow by ID

```graphql
query GetWorkflow {
  getWorkflowInstance(id: "wf-success-001") {
    id
    namespace
    name
    status
    startDate
    endDate
  }
}
```

**Expected**: Single workflow with all fields populated

---

### Query 3: Get Workflow with Task Executions

```graphql
query GetWorkflowWithTasks {
  getWorkflowInstance(id: "wf-success-001") {
    id
    name
    status
    taskExecutions {
      id
      taskName
      taskPosition
      triggerTime
      leaveTime
      errorMessage
    }
  }
}
```

**Expected**: Workflow with 3 task executions (validateOrder, processPayment, sendConfirmation)

---

### Query 4: Get Failed Workflow with Error

```graphql
query GetFailedWorkflow {
  getWorkflowInstance(id: "wf-failed-002") {
    id
    name
    status
    error {
      type
      title
      detail
    }
  }
}
```

**Expected**: Workflow with status FAULTED and error details

---

### Query 5: Get Task Executions for Workflow

```graphql
query GetTasksForWorkflow {
  getTaskExecutions(workflowInstanceId: "wf-success-001") {
    id
    taskName
    taskPosition
    triggerTime
    leaveTime
  }
}
```

**Expected**: 3 task executions

---

## Alternative: Test with cURL

If GraphQL UI doesn't load, test with cURL:

### Get All Workflows
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status startDate endDate } }"}' \
  | python3 -m json.tool
```

### Get Single Workflow
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstance(id: \"wf-success-001\") { id name status } }"}' \
  | python3 -m json.tool
```

### Get Workflow with Error
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstance(id: \"wf-failed-002\") { id name status error { type title detail } } }"}' \
  | python3 -m json.tool
```

---

## Schema Introspection

### Check Available Queries

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

**Expected**:
- `getWorkflowInstance`
- `getWorkflowInstances`
- `getTaskExecutions`

### Check WorkflowInstance Type

```graphql
{
  __type(name: "WorkflowInstance") {
    fields {
      name
      type {
        name
        kind
      }
    }
  }
}
```

### Get Full Schema

```bash
curl http://localhost:8080/graphql/schema.graphql
```

---

## Troubleshooting

### Issue: Port 8080 already in use

**Solution**: Change port in application.properties or stop conflicting process
```properties
quarkus.http.port=8081
```

### Issue: PostgreSQL connection refused

**Check PostgreSQL is running**:
```bash
docker-compose -f fluent-bit/docker-compose-triggers.yml ps
```

**Restart if needed**:
```bash
docker-compose -f fluent-bit/docker-compose-triggers.yml restart postgres
```

### Issue: No data returned

**Verify test data exists**:
```bash
docker-compose -f fluent-bit/docker-compose-triggers.yml exec postgres \
  psql -U postgres -d dataindex -c "SELECT COUNT(*) FROM workflow_instances;"
```

**Expected**: `count: 4`

**Reload test data if needed**:
```bash
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
  psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql
```

### Issue: CDI injection errors

**Check logs for**:
```
Unsatisfied dependencies for type WorkflowInstanceStorage
```

**Solution**: Verify Jandex indexing is present
```bash
ls -la data-index-storage-postgresql/target/classes/META-INF/jandex.idx
```

### Issue: GraphQL schema empty

**Check logs for**:
```
No GraphQL operations found
```

**Solution**: Verify Jandex indexing in data-index-model
```bash
ls -la data-index-model/target/classes/META-INF/jandex.idx
```

---

## Health Check

Verify service is healthy:
```bash
curl http://localhost:8080/q/health
```

**Expected**:
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "Database connections health check",
      "status": "UP"
    }
  ]
}
```

---

## Success Criteria

✅ **GraphQL UI loads** at http://localhost:8080/graphql-ui  
✅ **Schema explorer** shows WorkflowInstance, TaskExecution types  
✅ **getWorkflowInstances** returns 4 workflow instances  
✅ **getWorkflowInstance** returns workflow with all fields  
✅ **Nested query** returns workflow with task executions  
✅ **Error query** returns failed workflow with error details  
✅ **getTaskExecutions** returns task executions for workflow  

---

## Next Steps

Once all queries work:

1. ✅ GraphQL API is functional with test data
2. **Real Workflow Testing** - Test with actual Quarkus Flow events
3. **Implement Filtering** - Add where, orderBy, pagination
4. **Performance Optimization** - DataLoader for n+1 queries
5. **Production Testing** - Load testing, monitoring

---

**See Also**:
- [Architecture](architecture.md) - Complete system architecture
- [Database Schema](database-schema.md) - Schema and event mappings
- [FluentBit Configuration](fluentbit-configuration.md) - Event ingestion setup
