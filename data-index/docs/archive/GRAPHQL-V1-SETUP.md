# Data Index v1.0.0 - GraphQL API Setup

**Date**: 2026-04-16  
**Status**: ✅ Storage Layer Complete, GraphQL Schema Defined, Test Data Available

---

## What's Been Built

### 1. Storage Layer ✅

**MapStruct Mappers** (Entity ↔ Domain Model):
- `WorkflowInstanceEntityMapper` - Maps between WorkflowInstanceEntity and WorkflowInstance
- `TaskExecutionEntityMapper` - Maps between TaskExecutionEntity and TaskExecution
- `WorkflowInstanceErrorEntityMapper` - Maps error embeddable

**JPA Storage Implementations**:
- `WorkflowInstanceJPAStorage` - JPA storage for workflow instances
- `TaskExecutionJPAStorage` - JPA storage for task executions

**Storage Interfaces**:
- `WorkflowInstanceStorage` - Storage interface for WorkflowInstance
- `TaskExecutionStorage` - Storage interface for TaskExecution

**Location**:
- Mappers: `data-index-storage/data-index-storage-jpa-common/src/main/java/org/kubesmarts/logic/dataindex/jpa/mapper/`
- Storage: `data-index-storage/data-index-storage-jpa-common/src/main/java/org/kubesmarts/logic/dataindex/jpa/storage/`
- Interfaces: `data-index-storage/data-index-storage-api/src/main/java/org/kubesmarts/logic/dataindex/storage/`

### 2. GraphQL Schema ✅

**Schema File**: `data-index-graphql/src/main/resources/META-INF/workflow-v1.graphql`

**Types Defined**:
- `WorkflowInstance` - Complete workflow instance with all v1.0.0 fields
- `TaskExecution` - Task execution with position, times, input/output
- `WorkflowInstanceError` - SW 1.0.0 error spec
- `WorkflowInstanceStatus` - Enum (RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED)

**Queries Defined**:
```graphql
type Query {
    getWorkflowInstance(id: String!): WorkflowInstance
    getWorkflowInstances(
        where: WorkflowInstanceFilter
        orderBy: WorkflowInstanceOrderBy
        pagination: Pagination
    ): [WorkflowInstance!]!
    getTaskExecutions(workflowInstanceId: String!): [TaskExecution!]!
}
```

**Filter/Sort/Pagination** (defined, not yet implemented):
- `WorkflowInstanceFilter` - Filter by id, namespace, name, version, status, dates
- `WorkflowInstanceOrderBy` - Sort by startDate, endDate, lastUpdate
- `Pagination` - limit, offset

### 3. GraphQL API Class ✅

**File**: `data-index-service/data-index-service-common/src/main/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApi.java`

**Methods**:
- `getWorkflowInstance(id)` - Get single instance by ID
- `getWorkflowInstances()` - Get all instances (TODO: add filter/sort/pagination)
- `getTaskExecutions(workflowInstanceId)` - Get tasks for an instance

**Note**: Uses SmallRye GraphQL annotations (@GraphQLApi, @Query). May need adaptation to work with existing vert.x GraphQL infrastructure.

### 4. Test Data ✅

**File**: `scripts/test-data-v1.sql`

**Test Scenarios**:

1. **Successful Workflow** (`wf-success-001`):
   - Status: COMPLETED
   - 3 tasks: validateOrder, processPayment, sendConfirmation
   - All tasks successful with input/output

2. **Failed Workflow** (`wf-failed-002`):
   - Status: FAULTED
   - 2 tasks: validateOrder (success), processPayment (failed)
   - Error: Payment Service Unavailable (503)
   - Task error: Connection timeout

3. **Running Workflow** (`wf-running-003`):
   - Status: RUNNING
   - 2 tasks: fetchInventory (completed), updateDatabase (in progress - no exit time)

4. **Cancelled Workflow** (`wf-cancelled-004`):
   - Status: CANCELLED
   - No tasks (cancelled before execution)

**Load Test Data**:
```bash
cd fluent-bit
docker-compose -f docker-compose-triggers.yml up -d
cd ..
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
    psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql
```

---

## How to Test

### Option 1: Query Database Directly

```bash
# Start PostgreSQL
cd fluent-bit
docker-compose -f docker-compose-triggers.yml up -d

# Load test data
cd ..
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
    psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql

# Query workflow instances
docker-compose -f fluent-bit/docker-compose-triggers.yml exec postgres \
    psql -U postgres -d dataindex -c \
    "SELECT id, namespace, name, status, start FROM workflow_instances;"

# Query with task executions (JOIN)
docker-compose -f fluent-bit/docker-compose-triggers.yml exec postgres \
    psql -U postgres -d dataindex -c \
    "SELECT w.id as workflow_id, w.name, w.status, t.task_name, t.task_position, t.error_message
     FROM workflow_instances w
     LEFT JOIN task_executions t ON w.id = t.workflow_instance_id
     ORDER BY w.start, t.enter;"
```

### Option 2: Test Storage Layer (Java Test)

Create a test in `data-index-storage-postgresql`:

```java
@QuarkusTest
public class WorkflowInstanceStorageTest {

    @Inject
    WorkflowInstanceStorage storage;

    @Test
    @Transactional
    public void testGetWorkflowInstance() {
        // Load test data first via SQL
        // Then query via storage
        WorkflowInstance instance = storage.get("wf-success-001");
        
        assertNotNull(instance);
        assertEquals("order-processing", instance.getName());
        assertEquals(WorkflowInstanceStatus.COMPLETED, instance.getStatus());
        assertEquals(3, instance.getTaskExecutions().size());
    }
}
```

### Option 3: Test GraphQL API (Integration Test)

Once Data Index service is running:

```bash
# Start Data Index service with PostgreSQL
mvn quarkus:dev -pl data-index-quarkus/data-index-service-postgresql

# GraphQL endpoint will be available at:
# http://localhost:8080/graphql

# Example GraphQL query:
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ getWorkflowInstance(id: \"wf-success-001\") { id name status taskExecutions { taskName taskPosition } } }"
  }'
```

**GraphQL UI** (if enabled):
- http://localhost:8080/graphql-ui

---

## What Works Right Now

✅ **Storage Layer**:
- Entities extend AbstractEntity
- MapStruct mappers compile successfully
- JPA storage classes created and compiled

✅ **Database Schema**:
- Tables created (workflow_instances, task_executions)
- Test data loads successfully
- 4 workflows, 7 tasks in database

✅ **GraphQL Schema**:
- Schema file defined with complete v1.0.0 types
- Queries defined (getWorkflowInstance, getWorkflowInstances, getTaskExecutions)

✅ **Test Data**:
- Comprehensive test scenarios (success, failure, running, cancelled)
- SQL script loads without errors

---

## What's Next (TODO)

### 1. Integrate GraphQL API with Existing Infrastructure

**Current State**: Created `WorkflowInstanceGraphQLApi` with SmallRye GraphQL annotations

**Problem**: Existing data-index uses low-level graphql-java API (DataFetchers), not SmallRye

**Options**:
1. **Adapt to existing pattern** - Create data fetchers using graphql-java API
2. **Add SmallRye GraphQL** - Add quarkus-smallrye-graphql extension to enable annotations
3. **REST endpoint** - Create simple REST API for testing, add GraphQL later

**Recommendation**: Add quarkus-smallrye-graphql dependency to data-index-service-postgresql pom.xml

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-graphql</artifactId>
</dependency>
```

### 2. Wire Up Storage Beans in PostgreSQL Service

**Current State**: Storage classes created but not yet wired into data-index-service-postgresql

**Need**:
- Ensure WorkflowInstanceJPAStorage and TaskExecutionJPAStorage are discovered by CDI
- Verify EntityManager injection works
- Test storage beans in integration test

### 3. Test End-to-End

**Steps**:
1. Start data-index-service-postgresql in dev mode
2. Load test data (scripts/test-data-v1.sql)
3. Access GraphQL UI (http://localhost:8080/graphql-ui)
4. Run queries:
   ```graphql
   query GetAllWorkflows {
       getWorkflowInstances {
           id
           name
           status
           startDate
           endDate
       }
   }
   
   query GetWorkflowWithTasks {
       getWorkflowInstance(id: "wf-success-001") {
           id
           name
           status
           taskExecutions {
               taskName
               taskPosition
               triggerTime
               leaveTime
               errorMessage
           }
       }
   }
   ```

### 4. Implement Filtering, Sorting, Pagination

**Current State**: Filter/Sort/Pagination types defined in schema, not implemented

**Need**:
- Implement WorkflowInstanceFilter in GraphQL API
- Map filters to JPA Criteria API queries
- Implement sorting with ORDER BY
- Implement pagination with LIMIT/OFFSET

---

## Architecture Verification

### Data Flow (Read Path)

```
GraphQL Query
    ↓
WorkflowInstanceGraphQLApi (resolvers)
    ↓
WorkflowInstanceJPAStorage (storage layer)
    ↓
EntityManager.find() / JPQL query
    ↓
WorkflowInstanceEntity (JPA entity)
    ↓
WorkflowInstanceEntityMapper.toModel()
    ↓
WorkflowInstance (domain model)
    ↓
GraphQL Response (JSON)
```

### Database Tables

```
workflow_instances (final table - query target)
    ← populated by FluentBit + triggers
    ← can also populate with test-data-v1.sql for testing
    ↓
WorkflowInstanceJPAStorage reads
    ↓
Returns to GraphQL API
```

---

## Quick Start Commands

```bash
# 1. Start PostgreSQL + deploy schema
cd fluent-bit
docker-compose -f docker-compose-triggers.yml up -d
cd ..

# 2. Load test data
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
    psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql

# 3. Verify data loaded
docker-compose -f fluent-bit/docker-compose-triggers.yml exec postgres \
    psql -U postgres -d dataindex -c \
    "SELECT id, name, status FROM workflow_instances;"

# 4. Build data-index
mvn clean compile -DskipTests

# 5. (TODO) Start Data Index service
mvn quarkus:dev -pl data-index-quarkus/data-index-service-postgresql

# 6. (TODO) Open GraphQL UI
# http://localhost:8080/graphql-ui
```

---

## Files Created

### Storage Layer
```
data-index-storage/
├── data-index-storage-api/
│   └── src/main/java/org/kubesmarts/logic/dataindex/storage/
│       ├── WorkflowInstanceStorage.java
│       └── TaskExecutionStorage.java
└── data-index-storage-jpa-common/
    └── src/main/java/org/kubesmarts/logic/dataindex/jpa/
        ├── mapper/
        │   ├── WorkflowInstanceEntityMapper.java
        │   ├── TaskExecutionEntityMapper.java
        │   └── WorkflowInstanceErrorEntityMapper.java
        └── storage/
            ├── WorkflowInstanceJPAStorage.java
            └── TaskExecutionJPAStorage.java
```

### GraphQL Layer
```
data-index-graphql/
└── src/main/resources/META-INF/
    └── workflow-v1.graphql

data-index-service/
└── data-index-service-common/
    └── src/main/java/org/kubesmarts/logic/dataindex/graphql/
        └── WorkflowInstanceGraphQLApi.java
```

### Test Data
```
scripts/
└── test-data-v1.sql
```

### Documentation
```
GRAPHQL-V1-SETUP.md (this file)
```

---

## Compatibility with Existing v0.8 API

**Approach**: Dual API support

1. **v1.0.0 API** (new):
   - Endpoint: `/graphql` (or `/v1/graphql`)
   - Schema: workflow-v1.graphql
   - Domain: WorkflowInstance, TaskExecution (SW 1.0.0)

2. **v0.8 API** (legacy):
   - Endpoint: `/v0.8/graphql`
   - Schema: existing protobuf-based schema
   - Domain: ProcessInstance, UserTaskInstance (Kogito legacy)

**Migration Strategy** (for later):
- Create adapter layer: ProcessInstance ↔ WorkflowInstance
- Add deprecation warnings to v0.8 API
- Provide migration guide for clients

---

## Next Immediate Action

**Recommended**:

1. Add SmallRye GraphQL dependency to data-index-service-postgresql
2. Start service in dev mode
3. Verify GraphQL UI loads
4. Test queries against mocked data

**Command**:
```bash
# Add to data-index-quarkus/data-index-service-postgresql/pom.xml:
# <dependency>
#     <groupId>io.quarkus</groupId>
#     <artifactId>quarkus-smallrye-graphql</artifactId>
# </dependency>

mvn quarkus:dev -pl data-index-quarkus/data-index-service-postgresql
```

---

**Report Generated**: 2026-04-16  
**Author**: Claude Code (Sonnet 4.5)
