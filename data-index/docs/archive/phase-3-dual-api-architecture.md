# Phase 3: Dual API Architecture - v0.8 and v1.0.0

**Date**: 2026-04-14  
**Status**: 📋 PLANNING

## Overview

Data Index will support TWO GraphQL APIs simultaneously:
- **v1.0.0 API** (default) - Serverless Workflow 1.0.0 terminology
- **v0.8 API** (legacy) - BPMN/Kogito terminology (deprecated)

Both APIs query the same PostgreSQL database but use different:
- Model classes
- GraphQL schemas
- HTTP endpoints

## Architecture

### Endpoint Structure

```
HTTP Routes:
├── /graphql              → v1.0.0 API (default, recommended)
├── /v1.0.0/graphql       → v1.0.0 API (explicit version)
└── /v0.8/graphql         → v0.8 API (legacy, deprecated)
```

### Model Packages

```
Storage API Module:
├── org.kubesmarts.logic.dataindex.model.v1.*   (NEW - v1.0.0)
│   ├── Workflow
│   ├── WorkflowInstance
│   ├── WorkflowState
│   ├── WorkflowStateExecution
│   ├── Job
│   └── WorkflowInstanceStatus
│
└── org.kie.kogito.index.model.*                (KEEP - v0.8)
    ├── ProcessDefinition
    ├── ProcessInstance
    ├── Node
    ├── NodeInstance
    └── Job
```

### GraphQL Schema Comparison

#### v1.0.0 Schema (Serverless Workflow Terminology)

```graphql
type Query {
    Workflows(where: WorkflowArgument, ...): [Workflow]
    WorkflowInstances(where: WorkflowInstanceArgument, ...): [WorkflowInstance]
    WorkflowStates(where: WorkflowStateArgument, ...): [WorkflowState]
    Jobs(where: JobArgument, ...): [Job]
}

type WorkflowInstance {
    id: String!
    workflowId: String!
    workflowName: String
    version: String
    status: WorkflowInstanceStatus!
    variables: JSON
    stateExecutions: [WorkflowStateExecution!]!
    start: DateTime
    end: DateTime
    businessKey: String
    parentInstanceId: String
    rootInstanceId: String
    error: WorkflowInstanceError
}

enum WorkflowInstanceStatus {
    PENDING
    ACTIVE
    COMPLETED
    ABORTED
    SUSPENDED
    ERROR
}
```

#### v0.8 Schema (BPMN/Kogito Terminology)

```graphql
type Query {
    ProcessDefinitions(where: ProcessDefinitionArgument, ...): [ProcessDefinition]
    ProcessInstances(where: ProcessInstanceArgument, ...): [ProcessInstance]
    UserTaskInstances(...): [UserTaskInstance]  # Returns empty (BPMN legacy)
    Jobs(where: JobArgument, ...): [Job]
}

type ProcessInstance {
    id: String!
    processId: String!
    processName: String
    version: String
    state: Int!  # Enum ordinal for backward compatibility
    variables: JSON
    nodes: [NodeInstance!]!
    milestones: [Milestone!]  # Returns empty (BPMN legacy)
    start: DateTime
    end: DateTime
    businessKey: String
    parentProcessInstanceId: String
    rootProcessInstanceId: String
    error: ProcessInstanceError
}
```

### Field Name Mapping

| v0.8 (Legacy) | v1.0.0 (New) | Notes |
|---------------|--------------|-------|
| processId | workflowId | Same semantic meaning |
| processName | workflowName | Same semantic meaning |
| state (Int) | status (Enum) | v0.8 uses ordinal, v1.0.0 uses enum name |
| nodes | stateExecutions | BPMN → SW terminology |
| nodeId | stateId | BPMN → SW terminology |
| parentProcessInstanceId | parentInstanceId | Shorter name |
| rootProcessInstanceId | rootInstanceId | Shorter name |

## Data Flow

### v1.0.0 Request Flow

```
Client → /graphql
    ↓
GraphQLSchemaManagerV1 (org.kubesmarts.logic.dataindex.graphql.v1)
    ↓
WorkflowInstanceStorage (org.kubesmarts.logic.dataindex.storage.v1)
    ↓
WorkflowInstanceEntityStorage (JPA)
    ↓
WorkflowInstanceEntity → WorkflowInstance (MapStruct)
    ↓
GraphQL Response (v1.0.0 schema)
```

### v0.8 Request Flow (Facade)

```
Client → /v0.8/graphql
    ↓
GraphQLSchemaManagerV0 (org.kie.kogito.index.graphql)
    ↓
ProcessInstanceStorageFacade (adapter)
    ↓
WorkflowInstanceStorage (v1.0.0 internal)
    ↓
WorkflowInstanceEntityStorage (JPA)
    ↓
WorkflowInstanceEntity → WorkflowInstance
    ↓
WorkflowInstance → ProcessInstance (MapStruct adapter) ⭐
    ↓
GraphQL Response (v0.8 schema)
```

## Storage Layer Strategy

### Single Source of Truth: v1.0.0

**JPA Entities use v1.0.0 terminology:**
```java
@Entity
@Table(name = "processes")  // Keep table name for compatibility
public class WorkflowInstanceEntity extends AbstractEntity {
    @Id
    private String id;
    
    @Column(name = "process_id")  // Keep column name for DB compatibility
    private String workflowId;
    
    @Column(name = "process_name")
    private String workflowName;
    
    @Convert(converter = JsonBinaryConverter.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode variables;
    
    // ...
}
```

**MapStruct Mappers:**
```java
// v1.0.0 mapper (primary)
@Mapper
interface WorkflowInstanceEntityMapper {
    WorkflowInstance mapToModel(WorkflowInstanceEntity entity);
    WorkflowInstanceEntity mapToEntity(WorkflowInstance model);
}

// v0.8 adapter mapper (facade)
@Mapper
interface ProcessInstanceAdapter {
    ProcessInstance adaptFromV1(WorkflowInstance v1Model);
    WorkflowInstance adaptToV1(ProcessInstance v0Model);
    
    @Mapping(source = "workflowId", target = "processId")
    @Mapping(source = "workflowName", target = "processName")
    @Mapping(source = "status", target = "state", qualifiedByName = "statusToOrdinal")
    @Mapping(source = "stateExecutions", target = "nodes")
    ProcessInstance map(WorkflowInstance source);
    
    @Named("statusToOrdinal")
    default Integer statusToOrdinal(WorkflowInstanceStatus status) {
        return status.ordinal();
    }
}
```

## Implementation Plan

### Step 1: Create v1.0.0 Model Package ✅

**Location**: `dataindex-storage-api/src/main/java/org/kubesmarts/logic/dataindex/model/v1/`

**Classes to create:**
1. `Workflow.java` (was ProcessDefinition)
2. `WorkflowInstance.java` (was ProcessInstance)
3. `WorkflowInstanceMeta.java` (base metadata)
4. `WorkflowInstanceStatus.java` (enum)
5. `WorkflowState.java` (was Node)
6. `WorkflowStateExecution.java` (was NodeInstance)
7. `WorkflowInstanceError.java` (was ProcessInstanceError)
8. `Job.java` (shared or separate v1)

### Step 2: Update JPA Entities to Use v1.0.0

**Location**: `dataindex-storage-jpa-common/.../jpa/model/`

**Changes:**
- Rename `ProcessInstanceEntity` → `WorkflowInstanceEntity`
- Update field names in entity classes
- Keep `@Column(name="process_id")` for DB compatibility
- Update MapStruct mappers to use v1.0.0 models

### Step 3: Create v0.8 Adapter Layer

**Location**: `dataindex-graphql/src/main/java/org/kie/kogito/index/graphql/adapter/`

**Classes to create:**
1. `ProcessInstanceAdapter.java` - MapStruct: v1 ↔ v0.8
2. `ProcessDefinitionAdapter.java` - MapStruct: v1 ↔ v0.8
3. `NodeInstanceAdapter.java` - MapStruct: v1 ↔ v0.8
4. `ProcessInstanceStorageFacade.java` - Wraps v1 storage, returns v0.8 models

### Step 4: Create Dual GraphQL Endpoints

**Location**: `dataindex-service-common/.../service/endpoint/`

**Classes to create:**
1. `GraphQLEndpointV1.java`
   - Route: `/graphql` and `/v1.0.0/graphql`
   - Uses: `GraphQLSchemaManagerV1`
   - Models: `org.kubesmarts.logic.dataindex.model.v1.*`

2. `GraphQLEndpointV0.java`
   - Route: `/v0.8/graphql`
   - Uses: `GraphQLSchemaManagerV0` (existing)
   - Models: `org.kie.kogito.index.model.*` (via adapters)

3. `VertxRouterSetup.java` (update)
   - Register both endpoints
   - Add deprecation headers for v0.8

### Step 5: Update Quarkus Application

**Location**: `dataindex-service-postgresql/`

**Changes:**
- CDI beans for both GraphQL schema managers
- Configuration for v0.8 deprecation warnings
- Health check endpoints for both versions

### Step 6: Testing

**Test both APIs:**
```bash
# v1.0.0 API (default)
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ WorkflowInstances { id workflowId status } }"}'

# v1.0.0 API (explicit)
curl -X POST http://localhost:8080/v1.0.0/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ WorkflowInstances { id workflowId status } }"}'

# v0.8 API (legacy)
curl -X POST http://localhost:8080/v0.8/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ ProcessInstances { id processId state } }"}'
```

## Benefits

### ✅ Clean Separation
- v0.8 and v1.0.0 models don't interfere
- No naming conflicts or aliases
- Clear package ownership

### ✅ Gradual Migration
- Clients can migrate at their own pace
- Both APIs work simultaneously
- Easy to test migration in parallel

### ✅ Deprecation Path
- v0.8 endpoint returns deprecation warnings
- Metrics track v0.8 usage
- Can sunset v0.8 when usage drops to zero

### ✅ Code Clarity
- v1.0.0 code uses modern terminology
- Adapters are isolated in facade layer
- No v0.8 concepts leak into v1.0.0

## Migration Timeline

### Phase 3 (Current - 2026-Q2)
- ✅ Create v1.0.0 model package
- ✅ Update JPA entities to v1.0.0
- ✅ Create v0.8 adapter layer
- ✅ Implement dual endpoints
- ✅ Test both APIs

### Phase 4 (2026-Q3)
- Deploy dual API to production
- Monitor v0.8 usage metrics
- Migrate internal clients to v1.0.0
- Communicate deprecation to external clients

### Phase 5 (2026-Q4)
- Sunset v0.8 endpoint (if usage < 1%)
- Remove v0.8 models and adapters
- Clean up codebase
- Single v1.0.0 API remains

## Database Compatibility

**PostgreSQL schema remains unchanged:**
```sql
-- Table names stay as-is for backward compatibility
CREATE TABLE processes (...);           -- WorkflowInstance data
CREATE TABLE definitions (...);         -- Workflow definition data
CREATE TABLE jobs (...);                -- Job data

-- Column names stay as-is
process_id    → workflowId (in Java model)
process_name  → workflowName (in Java model)
```

**Both APIs query the same tables:**
- v1.0.0: WorkflowInstanceEntity (process_id column → workflowId field)
- v0.8: Adapter maps workflowId → processId for compatibility

## Configuration

```yaml
# application.properties
kubesmarts.dataindex.graphql.v1.enabled=true
kubesmarts.dataindex.graphql.v1.path=/graphql

kubesmarts.dataindex.graphql.v0.enabled=true
kubesmarts.dataindex.graphql.v0.path=/v0.8/graphql
kubesmarts.dataindex.graphql.v0.deprecated=true
kubesmarts.dataindex.graphql.v0.sunset-date=2026-12-31
```

## API Documentation

### v1.0.0 API
**Endpoint**: `GET /graphql/schema` (GraphQL introspection)
**Swagger**: `/v1.0.0/api-docs`
**Status**: ✅ Active, recommended

### v0.8 API
**Endpoint**: `GET /v0.8/graphql/schema`
**Swagger**: `/v0.8/api-docs`
**Status**: ⚠️ Deprecated, sunset planned for 2026-12-31

---

**Next Steps**: Create v1.0.0 model classes in `org.kubesmarts.logic.dataindex.model.v1.*`
