# Data Index Model

Domain model and storage interfaces for Data Index. This module defines the GraphQL API types and storage layer contracts.

## Overview

**Purpose:**
- Define domain model (workflow instances, task executions)
- Provide storage layer interfaces (`Storage` API)
- Separate GraphQL API types from JPA entities

**Does NOT contain:**
- JPA/Hibernate entities (see `data-index-storage-postgresql`)
- Storage implementations (see `data-index-storage-*`)
- GraphQL endpoint code (see `data-index-service`)

## Architecture

```
┌────────────────────────┐
│  GraphQL API Types     │  WorkflowInstance, TaskExecution
│  (exposed via API)     │  WorkflowInstanceStatus, etc.
├────────────────────────┤
│  Storage Interfaces    │  WorkflowInstanceStorage
│  (from Kogito)         │  TaskExecutionStorage
└────────────────────────┘
```

**Key principle:** Domain model is independent of persistence technology (JPA, Elasticsearch, etc.)

## Project Structure

```
data-index-model/
├── src/main/java/.../model/
│   ├── WorkflowInstance.java          # Domain model for workflow execution
│   ├── TaskExecution.java             # Domain model for task execution
│   ├── WorkflowInstanceStatus.java    # Enum: RUNNING, COMPLETED, etc.
│   └── api/
│       ├── WorkflowInstanceStorage.java   # Storage interface
│       └── TaskExecutionStorage.java      # Storage interface
└── pom.xml
```

## Domain Model

### WorkflowInstance

Represents a single workflow execution:

```java
public class WorkflowInstance {
    private String id;              // Workflow instance ID (UUID)
    private String namespace;       // Kubernetes namespace
    private String name;            // Workflow definition name
    private WorkflowInstanceStatus status;  // RUNNING, COMPLETED, etc.
    private ZonedDateTime start;    // Start timestamp
    private ZonedDateTime end;      // End timestamp (null if running)
    private JsonNode input;         // Input data (JSONB)
    private JsonNode output;        // Output data (JSONB)
    private List<TaskExecution> taskExecutions;  // Child tasks
    
    // GraphQL-friendly getters
    @JsonProperty("startDate")
    public ZonedDateTime getStart();
    
    @JsonProperty("endDate")
    public ZonedDateTime getEnd();
    
    // JSON fields exposed as String for GraphQL
    @Ignore  // Hide JsonNode from GraphQL
    public JsonNode getInput();
    
    public String getInputData() {
        return input != null ? input.toString() : null;
    }
}
```

**Key design decisions:**
- `start`/`end` in domain, `startDate`/`endDate` in GraphQL (via `@JsonProperty`)
- `input`/`output` JsonNode hidden from GraphQL, exposed as String via getters
- Child `taskExecutions` loaded lazily via GraphQL (not JPA relationships)

### TaskExecution

Represents a single task execution within a workflow:

```java
public class TaskExecution {
    private String id;              // Task execution ID (event ID)
    private String instanceId;      // Parent workflow instance ID
    private String taskName;        // Task name from definition
    private String taskPosition;    // Task position (e.g., "/do/0")
    private String status;          // RUNNING, COMPLETED, FAULTED
    private ZonedDateTime start;    // Start timestamp
    private ZonedDateTime end;      // End timestamp (null if running)
    private JsonNode input;         // Task input (JSONB)
    private JsonNode output;        // Task output (JSONB)
    
    // GraphQL field name mapping
    @JsonProperty("startDate")
    public ZonedDateTime getStart();
    
    @JsonProperty("endDate")
    public ZonedDateTime getEnd();
    
    // JSON as String
    public String getInputData() {
        return input != null ? input.toString() : null;
    }
}
```

**Important:** `id` is the task execution ID (unique per event), NOT a synthetic database ID.

### WorkflowInstanceStatus

```java
public enum WorkflowInstanceStatus {
    RUNNING,
    COMPLETED,
    FAULTED,
    CANCELLED,
    SUSPENDED,
    RESUMED
}
```

Maps to Serverless Workflow 1.0.0 CloudEvent types:
- `io.serverlessworkflow.workflow.started.v1` → `RUNNING`
- `io.serverlessworkflow.workflow.completed.v1` → `COMPLETED`
- etc.

## Storage API

### Interfaces

Storage interfaces extend `org.kie.kogito.persistence.api.Storage`:

```java
public interface WorkflowInstanceStorage extends Storage<String, WorkflowInstance> {
    // Inherits from Storage:
    // - query()          → Query builder
    // - put(key, value)  → Store instance
    // - get(key)         → Retrieve by ID
    // - remove(key)      → Delete
}
```

**From Kogito persistence-commons-api:**
- `Storage<K, V>` - Generic storage interface
- `Query<V>` - Query builder with filter/sort
- `AttributeFilter` - Field-based filtering
- `AttributeSort` - Field-based sorting

### Usage in GraphQL API

```java
@Query
public List<WorkflowInstance> getWorkflowInstances(
    @DefaultValue("10") int limit
) {
    return workflowInstanceStorage.query()
        .limit(limit)
        .execute();
}

@Query
public WorkflowInstance getWorkflowInstance(String id) {
    return workflowInstanceStorage.get(id);
}
```

Storage implementations (PostgreSQL, Elasticsearch) provide the actual query logic.

## GraphQL Annotations

SmallRye GraphQL annotations are used in domain model:

```java
@Type  // GraphQL object type
public class WorkflowInstance {
    
    @Query  // GraphQL query (in GraphQL API class, not model)
    public WorkflowInstance getWorkflowInstance(String id) { ... }
    
    @DefaultValue("10")  // Default query parameter
    public List<WorkflowInstance> getWorkflowInstances(int limit) { ... }
    
    @JsonProperty("startDate")  // Field name mapping
    public ZonedDateTime getStart() { ... }
    
    @Ignore  // Hide from GraphQL schema
    public JsonNode getInput() { ... }
}
```

**Note:** `@Query` annotations are in `data-index-service` GraphQL API classes, not in model classes.

## JSON Handling

### Why String Instead of JsonNode?

SmallRye GraphQL doesn't have built-in support for `JsonNode` as a GraphQL scalar:

**Option 1: String getter (CURRENT APPROACH)**
```java
@Ignore
public JsonNode getInput() { return input; }

public String getInputData() {
    return input != null ? input.toString() : null;
}
```

**Pros:** Simple, works immediately, no custom scalar configuration
**Cons:** JSON is opaque to GraphQL (no field-level selection)

**Option 2: Custom GraphQL scalar (FUTURE)**
- Define `@Scalar` type for JsonNode
- Requires SmallRye GraphQL configuration
- Industry standard approach

See: [JSON Scalar Analysis](../docs/jsonnode-scalar-analysis.md)

## Dependencies

### Required

```xml
<dependency>
  <groupId>org.kie.kogito</groupId>
  <artifactId>persistence-commons-api</artifactId>
  <version>999-SNAPSHOT</version>
</dependency>
```

**Used for:**
- `org.kie.kogito.persistence.api.Storage`
- `org.kie.kogito.persistence.api.query.*`

### Runtime

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
</dependency>
```

**Used for:** `JsonNode` in domain model

## Field Naming Conventions

### Database → JPA Entity → Domain Model → GraphQL

| Database Column | JPA Entity Field | Domain Model Field | GraphQL Field |
|----------------|-----------------|-------------------|---------------|
| `id` | `id` | `id` | `id` |
| `start` | `start` | `start` | `startDate` |
| `end` | `end` | `end` | `endDate` |
| `input` | `input` | `input` | `inputData` |
| `output` | `output` | `output` | `outputData` |
| `task_execution_id` | `taskExecutionId` | `id` | `id` |

**Key mappings:**
- `@JsonProperty("startDate")` maps `start` → `startDate` in GraphQL
- `@JsonProperty("endDate")` maps `end` → `endDate` in GraphQL
- `getInputData()` exposes `input` as String in GraphQL

**Never use these old names:**
- `inputArgs`, `outputArgs` (now `input`, `output`)
- `enter`, `exit` (now `start`, `end`)
- `triggerTime`, `leaveTime` (now `start`, `end`)

## Testing

This module contains no tests (pure model). Tests are in:

- `data-index-service` - Integration tests for GraphQL API
- `data-index-storage-postgresql` - Integration tests for JPA entities

## Usage

### In Storage Implementation

```java
// data-index-storage-postgresql
@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity {
    @Id
    private String id;
    private ZonedDateTime start;
    // ... JPA-specific annotations
}

// MapStruct mapper
@Mapper
public interface WorkflowInstanceEntityMapper {
    WorkflowInstance toDomain(WorkflowInstanceEntity entity);
    WorkflowInstanceEntity toEntity(WorkflowInstance domain);
}
```

### In GraphQL API

```java
// data-index-service
@GraphQLApi
public class WorkflowInstanceGraphQLApi {
    
    @Inject
    WorkflowInstanceStorage storage;
    
    @Query
    public WorkflowInstance getWorkflowInstance(String id) {
        return storage.get(id);
    }
}
```

## Related Modules

- **data-index-service** - GraphQL endpoint implementation
- **data-index-storage-common** - Abstract storage base classes
- **data-index-storage-postgresql** - PostgreSQL/JPA implementation
- **data-index-storage-elasticsearch** - Elasticsearch implementation (future)

## Contributing

**When adding fields:**

1. Add to domain model class
2. Add corresponding field to JPA entity (storage implementation)
3. Update MapStruct mapper (auto-maps if names match)
4. Update database schema (Flyway migration)
5. Update GraphQL API tests

**Code style:**
- Keep domain model clean of JPA/Hibernate annotations
- Use `@JsonProperty` for GraphQL field name mapping
- Use `@Ignore` to hide fields from GraphQL
- Don't add comments explaining WHAT (only WHY)
