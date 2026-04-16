# Quarkus Flow Integration Testing Plan

**Date**: 2026-04-16  
**Status**: 🔨 In Progress  
**Goal**: Verify end-to-end Data Index pipeline with real Quarkus Flow workflows

---

## Overview

Test the complete pipeline with real workflow execution:

```
Quarkus Flow Workflow
    ↓ (emits structured JSON logs)
FluentBit
    ↓ (parses & routes to PostgreSQL)
PostgreSQL Triggers
    ↓ (merges events into final tables)
Data Index GraphQL API
    ↓ (queries via JPA)
Client
```

---

## Prerequisites

✅ **Already Complete**:
- Data Index GraphQL API operational
- PostgreSQL schema with triggers deployed
- FluentBit parsing tested with sample events
- Test data queries working

🔨 **Needed**:
- Quarkus Flow example workflow application
- Structured logging configuration
- FluentBit → PostgreSQL integration
- End-to-end test scenarios

---

## Test Application Options

### Option 1: Use Existing Quarkus Flow Example
**Pros**:
- Already built and tested
- Real-world scenario
- Multiple examples available (petstore-openapi, micrometer-prometheus, etc.)

**Cons**:
- May have unnecessary complexity
- Dependencies on external services

### Option 2: Create Minimal Test Workflow
**Pros**:
- Minimal dependencies
- Focused on testing Data Index integration
- Easy to understand and debug

**Cons**:
- Need to create from scratch

**Decision**: Start with **Option 2** - Create minimal test workflow

---

## Minimal Test Workflow Spec

**File**: `test-workflow/hello-world.sw.yaml`

```yaml
document:
  dsl: 1.0.0-alpha1
  namespace: test
  name: hello-world
  version: 1.0.0
do:
  - sayHello:
      call: http
      with:
        method: get
        endpoint: https://httpbin.org/get
  - processResponse:
      set:
        message: "Hello, ${ .response.url }"
```

**Why this workflow**:
- ✅ Simple HTTP call (no external dependencies needed with httpbin.org)
- ✅ Generates 2 task events (sayHello, processResponse)
- ✅ Has input/output data
- ✅ Can test success scenario
- ✅ Can test failure scenario (by using invalid endpoint)

---

## Test Scenarios

### Scenario 1: Successful Workflow Execution ✅

**Workflow**: hello-world.sw.yaml (valid httpbin.org endpoint)

**Expected Events**:
1. `workflow.instance.started` - with input
2. `workflow.task.started` - sayHello
3. `workflow.task.completed` - sayHello with output
4. `workflow.task.started` - processResponse
5. `workflow.task.completed` - processResponse with output
6. `workflow.instance.completed` - with output

**GraphQL Verification**:
```graphql
query {
  getWorkflowInstance(id: "<instanceId>") {
    id
    namespace
    name
    status  # Should be COMPLETED
    startDate
    endDate
    input
    output
    taskExecutions {
      id
      taskName
      taskPosition
      triggerTime
      leaveTime
    }
  }
}
```

**Expected Result**: 1 workflow, 2 tasks, all COMPLETED

---

### Scenario 2: Failed Workflow Execution ❌

**Workflow**: hello-world-fail.sw.yaml (invalid endpoint)

**Expected Events**:
1. `workflow.instance.started`
2. `workflow.task.started` - sayHello
3. `workflow.task.faulted` - sayHello with error
4. `workflow.instance.faulted` - with error details

**GraphQL Verification**:
```graphql
query {
  getWorkflowInstance(id: "<instanceId>") {
    id
    status  # Should be FAULTED
    error {
      type
      title
      detail
      status
    }
    taskExecutions {
      id
      taskName
      errorMessage
    }
  }
}
```

**Expected Result**: 1 workflow FAULTED, 1 task with error

---

### Scenario 3: Out-of-Order Events ⚠️

**Purpose**: Verify PostgreSQL triggers handle events arriving out of order

**Test**: Delay `workflow.instance.started` event, send `completed` first

**Expected Behavior**:
- Trigger creates row with `completed` data
- When `started` arrives, trigger fills in missing fields (namespace, name, input)
- GraphQL query returns complete data

---

## Implementation Steps

### Step 1: Create Quarkus Flow Test Application

**Directory**: `/data-index/test-workflow/`

```
test-workflow/
├── pom.xml                    # Quarkus Flow dependencies
├── src/main/resources/
│   ├── application.properties # Structured logging config
│   └── workflows/
│       ├── hello-world.sw.yaml
│       └── hello-world-fail.sw.yaml
└── README.md
```

**Key Configuration**:
```properties
# Enable structured logging
quarkus.log.handler.structured.enable=true
quarkus.log.handler.structured.format=json
quarkus.log.handler.structured.include-fields=eventType,timestamp,instanceId,workflowNamespace,workflowName,workflowVersion,status,input,output,error

# Log to file for FluentBit
quarkus.log.file.enable=true
quarkus.log.file.path=/var/log/quarkus-flow/workflow-events.log
quarkus.log.file.format=%d{yyyy-MM-dd HH:mm:ss} %m%n
```

---

### Step 2: Configure FluentBit → PostgreSQL

**File**: `/data-index/fluent-bit/fluent-bit-postgresql.conf`

```conf
[SERVICE]
    Flush        1
    Daemon       Off
    Log_Level    info

[INPUT]
    Name              tail
    Path              /var/log/quarkus-flow/workflow-events.log
    Parser            json
    Tag               workflow
    Read_from_Head    true
    Refresh_Interval  1

[FILTER]
    Name    parser
    Match   workflow.*
    Key_Name log
    Parser  json

[FILTER]
    Name    lua
    Match   workflow.*
    Script  flatten-event.lua
    Call    flatten_event

[OUTPUT]
    Name            pgsql
    Match           workflow.instance.*
    Host            localhost
    Port            5432
    User            postgres
    Password        postgres
    Database        dataindex
    Table           workflow_instance_events
    Timestamp_Key   timestamp
    Async           Off

[OUTPUT]
    Name            pgsql
    Match           workflow.task.*
    Host            localhost
    Port            5432
    User            postgres
    Password        postgres
    Database        dataindex
    Table           task_execution_events
    Timestamp_Key   timestamp
    Async           Off
```

---

### Step 3: Docker Compose Setup

**File**: `/data-index/docker-compose-integration-test.yml`

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: dataindex
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - ./scripts/schema.sql:/docker-entrypoint-initdb.d/01-schema.sql
      - ./scripts/triggers.sql:/docker-entrypoint-initdb.d/02-triggers.sql

  fluent-bit:
    image: fluent/fluent-bit:latest
    volumes:
      - ./fluent-bit/fluent-bit-postgresql.conf:/fluent-bit/etc/fluent-bit.conf
      - ./fluent-bit/parsers.conf:/fluent-bit/etc/parsers.conf
      - ./fluent-bit/flatten-event.lua:/fluent-bit/etc/flatten-event.lua
      - /tmp/quarkus-flow-logs:/var/log/quarkus-flow
    depends_on:
      - postgres

  data-index:
    image: org.kie.kogito/data-index-service:999-SNAPSHOT
    ports:
      - "8080:8080"
    environment:
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://postgres:5432/dataindex
      QUARKUS_DATASOURCE_USERNAME: postgres
      QUARKUS_DATASOURCE_PASSWORD: postgres
    depends_on:
      - postgres
```

---

### Step 4: Test Execution

```bash
# 1. Start infrastructure
cd /data-index
docker-compose -f docker-compose-integration-test.yml up -d

# 2. Build test workflow application
cd test-workflow
mvn clean package -DskipTests

# 3. Run workflow (configured to log to /tmp/quarkus-flow-logs)
mvn quarkus:dev

# 4. Trigger workflow execution
curl -X POST http://localhost:8081/hello-world \
  -H "Content-Type: application/json" \
  -d '{"name": "World"}'

# 5. Wait for events to flow through pipeline (~2-3 seconds)

# 6. Query Data Index
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status startDate endDate } }"}' \
  | python3 -m json.tool
```

---

## Success Criteria

✅ **Workflow Execution**:
- Quarkus Flow workflow executes successfully
- Structured JSON logs written to `/tmp/quarkus-flow-logs/workflow-events.log`

✅ **Event Pipeline**:
- FluentBit parses events and writes to PostgreSQL staging tables
- PostgreSQL triggers merge events into final tables
- No errors in FluentBit logs

✅ **Data Index GraphQL**:
- `getWorkflowInstances` returns executed workflow
- All fields populated correctly (id, namespace, name, status, dates)
- `taskExecutions` includes both tasks
- Input/output data preserved

✅ **Error Handling**:
- Failed workflow shows `status: FAULTED`
- Error details captured (type, title, detail)
- Task error messages preserved

✅ **Out-of-Order Events**:
- Completed event arriving before started still produces correct final state
- No data loss or corruption

---

## Debugging

### Check FluentBit parsing
```bash
docker-compose -f docker-compose-integration-test.yml logs fluent-bit
```

### Check PostgreSQL staging tables
```bash
docker-compose -f docker-compose-integration-test.yml exec postgres \
  psql -U postgres -d dataindex -c "SELECT * FROM workflow_instance_events;"
```

### Check PostgreSQL final tables
```bash
docker-compose -f docker-compose-integration-test.yml exec postgres \
  psql -U postgres -d dataindex -c "SELECT id, name, status, start_date, end_date FROM workflow_instances;"
```

### Check Data Index logs
```bash
docker-compose -f docker-compose-integration-test.yml logs data-index
```

---

## Next Steps

After successful integration testing:

1. ✅ Verify complete pipeline works end-to-end
2. **Performance Testing** - Multiple concurrent workflows
3. **GraphQL Enhancements** - Add filtering, sorting, pagination
4. **Production Deployment** - Kubernetes setup
5. **v0.8 Compatibility** (optional) - Add adapters if needed

---

**See Also**:
- [Current State](current-state.md) - What's done, what's next
- [Architecture](architecture.md) - Complete system design
- [GraphQL Testing](graphql-testing.md) - Testing the GraphQL API
- [Quarkus Flow Events](quarkus-flow-events.md) - Event structure reference
