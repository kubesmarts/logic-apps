# Data Index Integration Testing

**Date**: 2026-04-17  
**Status**: ✅ Fully Operational - JPA Schema Generation Approach

---

## Overview

The Data Index integration tests validate the complete data pipeline from workflow execution to database persistence, using JPA-generated schema instead of manual SQL scripts or FluentBit in tests.

**Test Architecture**:
```
Quarkus Flow Workflow Execution
    ↓ (emits structured JSON logs)
Quarkus Flow Structured Logging
    ↓ (writes to target/quarkus-flow-events.log)
EventLogParser
    ↓ (parses JSON events)
Domain Models (WorkflowInstance, TaskExecution)
    ↓ (via MapStruct)
JPA Entities
    ↓ (via Hibernate ORM)
PostgreSQL Database
    ↓ (via JPA query)
GraphQL API
```

**Key Principle**: Integration tests use JPA schema generation (`quarkus.hibernate-orm.database.generation=drop-and-create`), NOT manual SQL scripts. This ensures the schema is always in sync with the JPA entities.

---

## Module: data-index-integration-tests

**Location**: `/data-index/data-index-integration-tests`

### Test Infrastructure

**Test Classes** (3 total):
```
├── DataIndexIntegrationTest.java      # End-to-end pipeline test (JPA persistence)
├── WorkflowTest.java                  # CDI bean injection tests
└── WorkflowExecutionTest.java         # Workflow execution tests (REST endpoints)
```

**Support Classes** (3 total):
```
├── EventLogParser.java                # Parses structured JSON logs → domain models
├── HttpBinMockServer.java             # WireMock server for HTTP task testing
└── WorkflowTestResource.java          # JAX-RS endpoints for workflow execution
```

**Test Workflows** (3 total):
```
├── simple-set.sw.yaml                 # No HTTP, just set operations
├── test-http-success.sw.yaml          # Successful HTTP call (httpbin.org mock)
└── test-http-failure.sw.yaml          # Failed HTTP call (500 error mock)
```

---

## Test Approach

### JPA Schema Generation

**Configuration** (DatabaseEnabledProfile in DataIndexIntegrationTest):
```java
public static class DatabaseEnabledProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> config = new java.util.HashMap<>();
        
        // Disable Dev Services - use existing PostgreSQL container
        config.put("quarkus.devservices.enabled", "false");
        
        // Connect to existing PostgreSQL container on port 33224
        config.put("quarkus.datasource.jdbc.url", "jdbc:postgresql://localhost:33224/quarkus");
        config.put("quarkus.datasource.username", "quarkus");
        
        // Enable Hibernate ORM with schema generation
        config.put("quarkus.hibernate-orm.enabled", "true");
        config.put("quarkus.hibernate-orm.database.generation", "drop-and-create");
        
        // Keep structured logging enabled
        config.put("quarkus.flow.structured-logging.enabled", "true");
        
        return config;
    }
}
```

**Why JPA Schema Generation**:
- ✅ Schema always matches JPA entities (no drift)
- ✅ No manual SQL scripts to maintain
- ✅ Automatic DDL for JSONB columns, foreign keys, constraints
- ✅ Escapes PostgreSQL reserved keywords ("start", "end")

---

## EventLogParser

**Purpose**: Parse Quarkus Flow structured logging JSON events into domain models.

**Key Logic**:

### Event Type Ordering (Critical)
```java
// CRITICAL: Check for task events FIRST
// Both workflow and task events contain ".workflow." in the eventType
// (e.g., "io.serverlessworkflow.task.started.v1")
if (eventType.contains(".task.")) {
    updateTaskExecution(tasks, event, eventType);
} else if (eventType.contains(".workflow.")) {
    updateWorkflowInstance(instance, event, eventType);
}
```

### Task Event Merging by Position
```java
// Tasks are identified by position (JSONPointer), NOT execution ID
// The SDK generates different taskExecutionId for started vs completed events
TaskExecution task = tasks.stream()
        .filter(t -> taskPosition.equals(t.getTaskPosition()))
        .findFirst()
        .orElse(null);

if (task == null) {
    task = new TaskExecution();
    task.setId(taskExecutionId); // Use first seen ID
    tasks.add(task);
}
```

**Why Position-Based Merging**:
- ✅ Task position is stable (e.g., "do/0", "do/1")
- ✅ taskExecutionId changes between started/completed events
- ✅ Prevents duplicate task executions

### Out-of-Order Event Handling
```java
// Events can arrive in any order
// Use computeIfAbsent to create instance on first event
WorkflowInstance instance = instances.computeIfAbsent(instanceId, id -> {
    WorkflowInstance wi = new WorkflowInstance();
    wi.setId(id);
    return wi;
});

// Update instance with event data (fields can be filled incrementally)
updateWorkflowInstance(instance, event, eventType);
```

---

## Test Scenarios

### 1. End-to-End Pipeline Test

**Test**: `DataIndexIntegrationTest.shouldIngestWorkflowEventsIntoDatabase()`

**Steps**:
1. Execute `test-http-success` workflow
2. Parse events from `target/quarkus-flow-events.log`
3. Convert events to `WorkflowInstance` domain model
4. Persist to PostgreSQL via `WorkflowInstanceStorage`
5. Query back from database
6. Verify all fields match

**Validates**:
- ✅ Workflow execution generates events
- ✅ EventLogParser correctly parses events
- ✅ Domain models map to JPA entities (via MapStruct)
- ✅ Bidirectional JPA relationships persist correctly
- ✅ JSONB input/output columns store data
- ✅ Task executions persist with foreign key

---

### 2. Multiple Workflow Instances

**Test**: `DataIndexIntegrationTest.shouldHandleMultipleWorkflowInstances()`

**Validates**:
- ✅ Multiple concurrent workflow instances
- ✅ Each instance has unique ID
- ✅ Events don't cross-contaminate

---

### 3. Workflow Input/Output Persistence

**Test**: `DataIndexIntegrationTest.shouldPersistWorkflowInputAndOutput()`

**Validates**:
- ✅ Workflow input data stored as JSONB
- ✅ Workflow output data stored as JSONB
- ✅ JsonNode → JSONB conversion works (via JsonBinaryConverter)

---

## Test Workflows

### simple-set.sw.yaml
```yaml
document:
  dsl: 1.0.0-alpha1
  namespace: test
  name: simple-set
  version: 1.0.0
do:
  - setGreeting:
      set:
        greeting: "Hello from Quarkus Flow!"
        timestamp: ${ now() }
```

**Purpose**: Basic workflow with no HTTP calls (tests core engine)

---

### test-http-success.sw.yaml
```yaml
document:
  dsl: 1.0.0-alpha1
  namespace: test
  name: test-http-success
  version: 1.0.0
do:
  - fetchData:
      call: http
      with:
        method: get
        endpoint: http://localhost:28080/get
  - extractMessage:
      set:
        message: "Received slide: ${ .slideshow.title }"
        author: ${ .slideshow.author }
```

**Purpose**: Tests HTTP task execution with successful response

**Mock Response** (HttpBinMockServer):
```json
{
  "slideshow": {
    "title": "Sample Slide Show",
    "author": "Yours Truly"
  }
}
```

---

### test-http-failure.sw.yaml
```yaml
document:
  dsl: 1.0.0-alpha1
  namespace: test
  name: test-http-failure
  version: 1.0.0
do:
  - failingCall:
      call: http
      with:
        method: get
        endpoint: http://localhost:28080/status/500
```

**Purpose**: Tests workflow error handling (HTTP 500 response)

**Mock Response**: HTTP 500 Internal Server Error

---

## Database Setup

### External PostgreSQL Container

**Why Not Testcontainers**:
- ❌ Testcontainers PostgreSQL timeout issues (60s wait strategy fails)
- ✅ External container is faster and more reliable

**Setup**:
```bash
# Start PostgreSQL container on port 33224
docker run -d \
  --name data-index-postgres \
  -e POSTGRES_USER=quarkus \
  -e POSTGRES_PASSWORD=quarkus \
  -e POSTGRES_DB=quarkus \
  -p 33224:5432 \
  postgres:15-alpine
```

**Connection String**: `jdbc:postgresql://localhost:33224/quarkus`

---

## MapStruct Bidirectional Relationship Fix

**Problem**: Foreign key constraint violation when persisting `WorkflowInstanceEntity` with `TaskExecutionEntity` children.

**Root Cause**: `TaskExecutionEntity.workflowInstance` was null (bidirectional relationship not set).

**Solution**: `@AfterMapping` in `WorkflowInstanceEntityMapper`:
```java
@AfterMapping
default void setTaskWorkflowReferences(@MappingTarget WorkflowInstanceEntity entity) {
    if (entity.getTaskExecutions() != null) {
        for (TaskExecutionEntity task : entity.getTaskExecutions()) {
            task.setWorkflowInstance(entity);
        }
    }
}
```

**Why**:
- ✅ MapStruct doesn't automatically set parent references
- ✅ JPA CASCADE requires both sides of relationship to be set
- ✅ @AfterMapping runs after entity mapping completes

---

## Running the Tests

### Prerequisites
1. Start PostgreSQL container on port 33224
2. Ensure `quarkus-flow` is in local Maven repo

### Run All Tests
```bash
cd /data-index/data-index-integration-tests
mvn clean test
```

### Run Specific Test
```bash
mvn test -Dtest=DataIndexIntegrationTest
mvn test -Dtest=WorkflowTest
mvn test -Dtest=WorkflowExecutionTest
```

### Test Output
```
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running org.kubesmarts.logic.dataindex.test.DataIndexIntegrationTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

---

## Known Issues & Solutions

### 1. PostgreSQL Reserved Keywords
**Error**: `syntax error at or near "end"`

**Solution**: Escape column names in JPA entities:
```java
@Column(name = "\"start\"")
private ZonedDateTime start;

@Column(name = "\"end\"")
private ZonedDateTime end;
```

---

### 2. Task Events Not Parsing
**Error**: Parser filtering out all task events

**Solution**: Check for `.task.` BEFORE `.workflow.` (both contain "workflow")

---

### 3. Duplicate Task Executions
**Error**: 4 tasks instead of 2 (started/completed creating separate tasks)

**Solution**: Merge by `taskPosition` (JSONPointer), not `taskExecutionId`

---

### 4. Foreign Key Constraint Violation
**Error**: `null value in column "workflow_instance_id" violates not-null constraint`

**Solution**: Add `@AfterMapping` to set bidirectional references in MapStruct mapper

---

## FluentBit vs Integration Tests

**Integration Tests** (This Approach):
- ✅ JPA schema generation (no manual SQL)
- ✅ EventLogParser reads log files directly
- ✅ Fast, deterministic, no external dependencies
- ✅ Test-only (not for production)

**FluentBit + Triggers** (Production Approach):
- ✅ Scalable event pipeline
- ✅ Handles retries, buffering, backpressure
- ✅ Pluggable outputs (PostgreSQL, Elasticsearch, etc.)
- ✅ Production-ready
- ❌ More complex (FluentBit config, triggers, staging tables)
- ❌ Not needed for automated tests

**Decision**: Use JPA approach for tests, FluentBit for production/manual testing.

---

## Future Enhancements

### 1. GraphQL Integration Tests
Add tests that execute workflows and query via GraphQL API:
```java
@Test
void shouldQueryWorkflowViaGraphQL() {
    // Execute workflow
    instance.start().get(5, TimeUnit.SECONDS);
    
    // Query via GraphQL
    String query = "{ getWorkflowInstance(id: \"" + instanceId + "\") { name status } }";
    Response response = given()
        .contentType(ContentType.JSON)
        .body(Map.of("query", query))
        .post("/graphql");
    
    assertThat(response.jsonPath().getString("data.getWorkflowInstance.status"))
        .isEqualTo("COMPLETED");
}
```

---

### 2. Error Scenario Tests
Add more failure tests:
- Network timeouts
- Invalid workflow definitions
- Task retry scenarios
- Compensation flows

---

### 3. Performance Tests
Test high-volume scenarios:
- 100+ concurrent workflows
- Large JSONB payloads (>1MB)
- Complex workflows (50+ tasks)

---

## References

- **JPA Entities**: [WorkflowInstanceEntity.java](../data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/jpa/WorkflowInstanceEntity.java)
- **MapStruct Mappers**: [WorkflowInstanceEntityMapper.java](../data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/mapper/WorkflowInstanceEntityMapper.java)
- **EventLogParser**: [EventLogParser.java](../data-index-integration-tests/src/test/java/org/kubesmarts/logic/dataindex/test/EventLogParser.java)
- **Integration Tests**: [DataIndexIntegrationTest.java](../data-index-integration-tests/src/test/java/org/kubesmarts/logic/dataindex/test/DataIndexIntegrationTest.java)
- **Database Schema**: [database-schema.md](database-schema.md)
- **Domain Model**: [domain-model-design.md](domain-model-design.md)
