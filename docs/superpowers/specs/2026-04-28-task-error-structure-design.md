# Task Error Structure Unification Design

**Date:** 2026-04-28  
**Status:** Approved  
**Author:** Claude Sonnet 4.5

## Problem Statement

Currently, the Data Index has inconsistent error handling between workflow instances and task executions:

- **WorkflowInstance** has a structured `error` object with fields: `type`, `title`, `detail`, `status`, `instance`
- **TaskExecution** has only a simple `errorMessage` string field
- Quarkus Flow emits the **same error structure** for both workflow and task faulted events
- The database trigger `normalize_task_event()` does not extract error fields, resulting in NULL error data

**Example from production logs:**
```json
{
  "taskExecutions": [{
    "status": "FAILED",
    "errorMessage": null  // Should be structured error object
  }]
}
```

The error object exists in the log events but is not being captured or exposed.

## Goals

1. Unify error handling across workflow instances and task executions
2. Use a single, reusable `Error` type for both
3. Capture full error structure from Quarkus Flow events in the database
4. Expose structured errors via GraphQL API
5. Enable error filtering in GraphQL queries (by type, status, instance, etc.)
6. Maintain consistency with Serverless Workflow 1.0.0 Error spec

## Non-Goals

- Changing how errors are logged by Quarkus Flow
- Backward compatibility (unreleased version)
- Error aggregation/statistics
- Complex error filtering (e.g., full-text search on error.detail)

## Scope

### What Changes

1. **Domain Model:**
   - Rename `WorkflowInstanceError` → `Error` (generic, reusable)
   - Update `WorkflowInstance.error` to use new `Error` type
   - Update `TaskExecution`: replace `errorMessage: String` with `error: Error`

2. **Database Schema:**
   - Add 5 error columns to `task_instances` table
   - Update `normalize_task_event()` trigger to extract error fields from JSONB

3. **JPA Entities:**
   - Rename `WorkflowInstanceErrorEntity` → `ErrorEntity` (reusable)
   - Update `TaskInstanceEntity` to add `@Embedded ErrorEntity error`
   - Update mappers to handle error field

4. **GraphQL API:**
   - Schema change: `TaskExecution.errorMessage` → `TaskExecution.error`
   - Breaking change, but acceptable (unreleased version)

5. **GraphQL Filters:**
   - Create `IntFilter` for integer field filtering
   - Create `ErrorFilter` for structured error filtering
   - Update `TaskExecutionFilter`: replace `errorMessage` with `error: ErrorFilter`
   - Update `WorkflowInstanceFilter`: add `error: ErrorFilter` (currently missing!)
   - Update `FilterConverter` to handle nested error filters

6. **Documentation:**
   - Update GraphQL API docs with new TaskExecution.error structure
   - Update database schema docs with task_instances error columns
   - Update example queries

7. **Git Workflow:**
   - Create feature branch `feature/unify-error-structure` from `main`
   - All work on this branch
   - Merge after review and testing

### What Stays the Same

- FluentBit configuration (no changes)
- Event ingestion flow (still trigger-based)
- Workflow error handling (just renaming the class)
- Data flow architecture

## Architecture

### Data Flow (Enhanced)

```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON with error object)
                      ↓ (FluentBit tail)
              task_events_raw (data JSONB contains error)
                      ↓ (normalize_task_event trigger - NOW extracts error fields)
              task_instances (NEW: error_type, error_title, error_detail, error_status, error_instance)
                      ↓ (JPA/Hibernate with @Embedded ErrorEntity)
              TaskExecution domain model (error: Error object)
                      ↓ (GraphQL auto-generated schema)
              Client sees error { type, title, detail, status, instance }
```

### Layers Affected

#### 1. Domain Model Layer (`data-index-model/`)

**Rename:** `WorkflowInstanceError.java` → `Error.java`

```java
package org.kubesmarts.logic.dataindex.model;

/**
 * Error information for failed workflow and task executions.
 * 
 * Aligns with SW 1.0.0 Error spec.
 * Applies to both WorkflowInstance and TaskExecution.
 */
public class Error {
    private String type;      // e.g., "communication", "timeout", "business"
    private String title;     // Short summary
    private String detail;    // Detailed message/stack trace
    private Integer status;   // HTTP status code
    private String instance;  // Error instance identifier (e.g., task position)
    
    // ... getters/setters, equals, hashCode, toString
}
```

**Update:** `WorkflowInstance.java`

```java
// Change field type
private Error error;  // Was: WorkflowInstanceError error

public Error getError() { return error; }
public void setError(Error error) { this.error = error; }
```

**Update:** `TaskExecution.java`

```java
// REMOVE:
private String errorMessage;
public String getErrorMessage() { return errorMessage; }
public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

// ADD:
private Error error;
public Error getError() { return error; }
public void setError(Error error) { this.error = error; }
```

#### 2. Storage Layer (`data-index-storage/`)

**Database Schema Changes (V1__initial_schema.sql):**

Add columns to `task_instances` table definition:

```sql
CREATE TABLE IF NOT EXISTS task_instances (
  task_execution_id VARCHAR(255) PRIMARY KEY,
  instance_id VARCHAR(255) NOT NULL,
  task_name VARCHAR(255),
  task_position VARCHAR(255),
  status VARCHAR(50),
  start TIMESTAMP WITH TIME ZONE,
  "end" TIMESTAMP WITH TIME ZONE,
  last_event_time TIMESTAMP WITH TIME ZONE,
  input JSONB,
  output JSONB,
  -- NEW: Error columns (same structure as workflow_instances)
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  -- End new columns
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  CONSTRAINT fk_task_instance_workflow FOREIGN KEY (instance_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
);
```

**Update `normalize_task_event()` trigger function:**

Add error field extraction in INSERT:

```sql
INSERT INTO task_instances (
  task_execution_id,
  instance_id,
  task_name,
  task_position,
  status,
  start,
  "end",
  input,
  output,
  error_type,          -- NEW
  error_title,         -- NEW
  error_detail,        -- NEW
  error_status,        -- NEW
  error_instance,      -- NEW
  last_event_time,
  created_at,
  updated_at
) VALUES (
  NEW.data->>'taskExecutionId',
  NEW.data->>'instanceId',
  NEW.data->>'taskName',
  NEW.data->>'taskPosition',
  NEW.data->>'status',
  to_timestamp((NEW.data->>'startTime')::numeric),
  to_timestamp((NEW.data->>'endTime')::numeric),
  NEW.data->'input',
  NEW.data->'output',
  NEW.data->'error'->>'type',                        -- NEW
  NEW.data->'error'->>'title',                       -- NEW
  NEW.data->'error'->>'detail',                      -- NEW
  (NEW.data->'error'->>'status')::integer,          -- NEW
  NEW.data->'error'->>'instance',                   -- NEW
  event_timestamp,
  NEW.time,
  NEW.time
);
```

Add error field handling in UPDATE:

```sql
UPDATE task_instances SET
  status = CASE
    WHEN event_timestamp > last_event_time
    THEN NEW.data->>'status'
    ELSE status
  END,
  
  -- Immutable fields
  task_name = COALESCE(task_name, NEW.data->>'taskName'),
  task_position = COALESCE(task_position, NEW.data->>'taskPosition'),
  start = COALESCE(start, to_timestamp((NEW.data->>'startTime')::numeric)),
  input = COALESCE(input, NEW.data->'input'),
  
  -- Terminal fields
  "end" = COALESCE(to_timestamp((NEW.data->>'endTime')::numeric), "end"),
  output = COALESCE(NEW.data->'output', output),
  
  -- NEW: Error fields (terminal - once set, preserve)
  error_type = COALESCE(NEW.data->'error'->>'type', error_type),
  error_title = COALESCE(NEW.data->'error'->>'title', error_title),
  error_detail = COALESCE(NEW.data->'error'->>'detail', error_detail),
  error_status = COALESCE((NEW.data->'error'->>'status')::integer, error_status),
  error_instance = COALESCE(NEW.data->'error'->>'instance', error_instance),
  
  -- Timestamp tracking
  last_event_time = GREATEST(event_timestamp, last_event_time),
  updated_at = NEW.time

WHERE instance_id = NEW.data->>'instanceId'
  AND task_position = NEW.data->>'taskPosition'
  AND "end" IS NULL;
```

**JPA Entity Changes:**

Rename `WorkflowInstanceErrorEntity` → `ErrorEntity`:

```java
package org.kubesmarts.logic.dataindex.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * JPA embeddable for error information.
 * 
 * Used by both WorkflowInstanceEntity and TaskInstanceEntity.
 * Maps to error_* columns in workflow_instances and task_instances tables.
 */
@Embeddable
public class ErrorEntity {
    
    @Column(name = "error_type")
    private String type;
    
    @Column(name = "error_title")
    private String title;
    
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String detail;
    
    @Column(name = "error_status")
    private Integer status;
    
    @Column(name = "error_instance")
    private String instance;
    
    // ... getters/setters, equals, hashCode, toString
}
```

Update `WorkflowInstanceEntity.java`:

```java
@Embedded
private ErrorEntity error;  // Was: WorkflowInstanceErrorEntity error
```

Update `TaskInstanceEntity.java`:

```java
// ADD:
@Embedded
private ErrorEntity error;

public ErrorEntity getError() { return error; }
public void setError(ErrorEntity error) { this.error = error; }
```

**Mapper Changes:**

Rename `WorkflowInstanceErrorEntityMapper` → `ErrorEntityMapper`:

```java
package org.kubesmarts.logic.dataindex.storage.mapper;

import org.kubesmarts.logic.dataindex.storage.entity.ErrorEntity;
import org.kubesmarts.logic.dataindex.model.Error;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;

@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface ErrorEntityMapper {
    
    Error toModel(ErrorEntity entity);
    ErrorEntity toEntity(Error model);
}
```

Update `TaskInstanceEntityMapper.java`:

```java
@Mapper(componentModel = "cdi", 
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        uses = ErrorEntityMapper.class)  // Add this
public interface TaskInstanceEntityMapper {
    
    @Mapping(target = "id", source = "taskExecutionId")
    @Mapping(target = "error", source = "error")  // Add explicit mapping
    TaskExecution toModel(TaskInstanceEntity entity);
    
    @Mapping(target = "taskExecutionId", source = "id")
    @Mapping(target = "error", source = "error")  // Add explicit mapping
    @Mapping(target = "instanceId", ignore = true)
    @Mapping(target = "workflowInstance", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    TaskInstanceEntity toEntity(TaskExecution model);
}
```

#### 3. Service Layer (`data-index-service/`)

**GraphQL API:** No code changes needed - SmallRye GraphQL auto-generates schema from domain model.

**Schema Change (auto-generated):**

Before:
```graphql
type TaskExecution {
  id: String!
  taskPosition: String
  status: String
  startDate: String
  endDate: String
  errorMessage: String  # Simple string
}
```

After:
```graphql
type TaskExecution {
  id: String!
  taskPosition: String
  status: String
  startDate: String
  endDate: String
  error: Error  # Structured object
}

type Error {
  type: String
  title: String
  detail: String
  status: Int
  instance: String
}
```

#### 4. Integration Tests

**Test Data Setup:**

```java
@BeforeEach
@Transactional
void setupTestData() {
    // Create workflow instance with error
    WorkflowInstanceEntity wf = new WorkflowInstanceEntity();
    wf.setId("faulted-wf-123");
    wf.setNamespace("org.acme");
    wf.setName("petstore");
    wf.setVersion("0.0.1");
    wf.setStatus(WorkflowInstanceStatus.FAULTED);
    
    ErrorEntity wfError = new ErrorEntity();
    wfError.setType("communication");
    wfError.setStatus(500);
    wfError.setTitle("Internal Server Error");
    wfError.setInstance("do/0/findPetByStatus");
    wfError.setDetail("{\"code\":500,\"message\":\"There was an error...\"}");
    wf.setError(wfError);
    em.persist(wf);
    
    // Create task instance with error
    TaskInstanceEntity task = new TaskInstanceEntity();
    task.setTaskExecutionId("failed-task-456");
    task.setInstanceId("faulted-wf-123");
    task.setTaskPosition("do/0/findPetByStatus");
    task.setStatus("FAILED");
    
    ErrorEntity taskError = new ErrorEntity();
    taskError.setType("communication");
    taskError.setStatus(500);
    taskError.setTitle("Internal Server Error");
    taskError.setDetail("{\"code\":500,\"message\":\"API call failed\"}");
    taskError.setInstance("do/0/findPetByStatus");
    task.setError(taskError);
    em.persist(task);
}
```

**GraphQL Query Test:**

```java
@Test
void testTaskExecutionWithError() {
    String query = """
        {
          getWorkflowInstances(limit:10, filter:{name: {eq: "petstore"}}) {
            id
            name
            status
            error {
              instance
              status
              title
              type
              detail
            }
            taskExecutions {
              id
              taskPosition
              status
              error {
                instance
                status
                title
                type
                detail
              }
            }
          }
        }
        """;
    
    given()
        .contentType(ContentType.JSON)
        .body("{\"query\":\"" + query + "\"}")
    .when()
        .post("/graphql")
    .then()
        .statusCode(200)
        .body("data.getWorkflowInstances[0].error.type", equalTo("communication"))
        .body("data.getWorkflowInstances[0].error.status", equalTo(500))
        .body("data.getWorkflowInstances[0].taskExecutions[0].error.type", equalTo("communication"))
        .body("data.getWorkflowInstances[0].taskExecutions[0].error.status", equalTo(500));
}

@Test
void testErrorFiltering() {
    String query = """
        {
          getWorkflowInstances(
            filter: {
              error: {
                type: { eq: "communication" }
                status: { gte: 500 }
              }
            }
          ) {
            id
            error { type, status }
          }
          
          getTaskExecutions(
            filter: {
              error: {
                status: { eq: 500 }
              }
            }
          ) {
            id
            error { status, instance }
          }
        }
        """;
    
    given()
        .contentType(ContentType.JSON)
        .body("{\"query\":\"" + query + "\"}")
    .when()
        .post("/graphql")
    .then()
        .statusCode(200)
        .body("data.getWorkflowInstances[0].error.type", equalTo("communication"))
        .body("data.getTaskExecutions[0].error.status", equalTo(500));
}
```

#### 5. GraphQL Filter Updates

**Create new filter classes:**

**IntFilter.java:**
```java
package org.kubesmarts.logic.dataindex.graphql.filter;

import java.util.List;
import org.eclipse.microprofile.graphql.Description;

/**
 * Integer field filter for GraphQL queries.
 */
public class IntFilter {
    @Description("Equal to value")
    private Integer eq;
    
    @Description("Greater than")
    private Integer gt;
    
    @Description("Greater than or equal")
    private Integer gte;
    
    @Description("Less than")
    private Integer lt;
    
    @Description("Less than or equal")
    private Integer lte;
    
    @Description("In list of values")
    private List<Integer> in;
    
    // ... getters/setters
}
```

**ErrorFilter.java:**
```java
package org.kubesmarts.logic.dataindex.graphql.filter;

import org.eclipse.microprofile.graphql.Description;

/**
 * Error field filter for GraphQL queries.
 * Used by both WorkflowInstanceFilter and TaskExecutionFilter.
 */
public class ErrorFilter {
    @Description("Filter by error type")
    private StringFilter type;
    
    @Description("Filter by error title")
    private StringFilter title;
    
    @Description("Filter by error detail")
    private StringFilter detail;
    
    @Description("Filter by error status code")
    private IntFilter status;
    
    @Description("Filter by error instance")
    private StringFilter instance;
    
    // ... getters/setters
}
```

**Update TaskExecutionFilter.java:**
```java
// REMOVE:
@Description("Filter by error message")
private StringFilter errorMessage;

// ADD:
@Description("Filter by error fields")
private ErrorFilter error;

public ErrorFilter getError() { return error; }
public void setError(ErrorFilter error) { this.error = error; }
```

**Update WorkflowInstanceFilter.java:**
```java
// ADD (currently missing error filtering!):
@Description("Filter by error fields")
private ErrorFilter error;

public ErrorFilter getError() { return error; }
public void setError(ErrorFilter error) { this.error = error; }
```

**Update FilterConverter.java:**

Add IntFilter conversion method:
```java
private static void addIntFilters(List<AttributeFilter<?>> result, String fieldName, IntFilter filter) {
    if (filter == null) {
        return;
    }
    
    if (filter.getEq() != null) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.EQUAL, filter.getEq()));
    }
    if (filter.getGt() != null) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.GT, filter.getGt()));
    }
    if (filter.getGte() != null) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.GTE, filter.getGte()));
    }
    if (filter.getLt() != null) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.LT, filter.getLt()));
    }
    if (filter.getLte() != null) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.LTE, filter.getLte()));
    }
    if (filter.getIn() != null && !filter.getIn().isEmpty()) {
        result.add(new DataIndexAttributeFilter<>(fieldName, FilterCondition.IN, filter.getIn()));
    }
}
```

Add ErrorFilter conversion method:
```java
private static void addErrorFilters(List<AttributeFilter<?>> result, ErrorFilter filter) {
    if (filter == null) {
        return;
    }
    
    // Map nested fields to database column names
    addStringFilters(result, "error_type", filter.getType());
    addStringFilters(result, "error_title", filter.getTitle());
    addStringFilters(result, "error_detail", filter.getDetail());
    addIntFilters(result, "error_status", filter.getStatus());
    addStringFilters(result, "error_instance", filter.getInstance());
}
```

Update convert methods:
```java
// In convert(WorkflowInstanceFilter filter):
// ADD:
addErrorFilters(result, filter.getError());

// In convert(TaskExecutionFilter filter):
// REMOVE:
addStringFilters(result, "errorMessage", filter.getErrorMessage());

// ADD:
addErrorFilters(result, filter.getError());
```

**GraphQL Usage Example:**
```graphql
query {
  getWorkflowInstances(
    filter: {
      status: { eq: FAULTED }
      error: {
        type: { eq: "communication" }
        status: { gte: 500 }
      }
    }
  ) {
    id
    status
    error { type, status, title }
  }
  
  getTaskExecutions(
    filter: {
      error: {
        type: { eq: "communication" }
        instance: { like: "do/0/*" }
      }
    }
  ) {
    id
    taskPosition
    error { type, status, instance }
  }
}
```

#### 6. Documentation Updates

**Files to update:**

1. `data-index-docs/modules/ROOT/pages/graphql-api.adoc`
   - Update TaskExecution type documentation
   - Show new error structure in examples
   - Remove errorMessage references
   - Document ErrorFilter usage

2. `data-index-docs/modules/ROOT/pages/developers/database-schema.adoc`
   - Document task_instances error columns
   - Update table schema diagram

3. Any example queries in documentation showing taskExecutions

## Implementation Plan

### Step-by-step Order

1. **Create feature branch:** `feature/unify-error-structure` from `main`

2. **Database Schema (V1__initial_schema.sql):**
   - Add 5 error columns to `task_instances` table definition
   - Update `normalize_task_event()` trigger function INSERT section
   - Update `normalize_task_event()` trigger function UPDATE section
   - Test trigger logic with sample JSONB data

3. **Domain Model Layer:**
   - Rename `WorkflowInstanceError.java` → `Error.java`
   - Update all references in `WorkflowInstance.java`
   - Update `TaskExecution.java`: remove `errorMessage`, add `error: Error`

4. **Storage/Entity Layer:**
   - Rename `WorkflowInstanceErrorEntity.java` → `ErrorEntity.java`
   - Update `WorkflowInstanceEntity.java` to use `ErrorEntity`
   - Update `TaskInstanceEntity.java` to add `@Embedded ErrorEntity error`
   - Rename `WorkflowInstanceErrorEntityMapper.java` → `ErrorEntityMapper.java`
   - Update `WorkflowInstanceEntityMapper.java` to use `ErrorEntityMapper`
   - Update `TaskInstanceEntityMapper.java` to map error field

5. **GraphQL Filter Layer:**
   - Create `IntFilter.java` (for integer field filtering)
   - Create `ErrorFilter.java` (nested filter for error fields)
   - Update `TaskExecutionFilter.java`: remove `errorMessage`, add `error: ErrorFilter`
   - Update `WorkflowInstanceFilter.java`: add `error: ErrorFilter`
   - Update `FilterConverter.java`: add `addIntFilters()` and `addErrorFilters()` methods
   - Update both `convert()` methods to handle error filtering

6. **Integration Tests:**
   - Update test data setup methods to include error objects for task executions
   - Update GraphQL query strings from `errorMessage` to `error { type, title, ... }`
   - Add new test cases for error structure
   - Add test cases for error filtering (both workflow and task)
   - Verify error data flows correctly through the stack

7. **Documentation:**
   - Update GraphQL API docs with new TaskExecution.error structure
   - Update database schema docs with task_instances error columns
   - Update any example queries showing task executions
   - Update CLAUDE.md to document the Error type and field name mapping

8. **Manual Testing (KIND deployment):**
   - Deploy a workflow that will fault (e.g., petstore with bad endpoint)
   - Verify error appears in database
   - Query via GraphQL and verify error structure matches workflow error structure

## Testing Strategy

### Automated Tests

**Integration Test Coverage:**
- ✅ TaskExecution with error object persists correctly
- ✅ TaskExecution without error (NULL) handles gracefully
- ✅ GraphQL query returns error structure
- ✅ Mapper converts ErrorEntity ↔ Error correctly
- ✅ Workflow error still works after renaming
- ✅ ErrorFilter works for workflow instances
- ✅ ErrorFilter works for task executions
- ✅ IntFilter handles numeric comparisons (eq, gt, gte, lt, lte, in)
- ✅ Filter by error.type, error.status, error.instance

### Manual Testing

**KIND Deployment:**
1. Deploy faulting workflow (e.g., petstore with invalid endpoint)
2. Check database: `SELECT error_type, error_title, error_instance FROM task_instances WHERE status='FAILED';`
3. Query GraphQL API with error fields
4. Verify error structure matches between workflow and task
5. Verify out-of-order events handled correctly (idempotency)

### Verification Checklist

- ✅ Database schema has 5 new error columns in task_instances
- ✅ Trigger extracts error fields from task events
- ✅ JPA entity maps error columns correctly
- ✅ Mapper converts ErrorEntity ↔ Error domain model
- ✅ GraphQL schema exposes TaskExecution.error
- ✅ IntFilter and ErrorFilter classes created
- ✅ WorkflowInstanceFilter and TaskExecutionFilter updated with error filtering
- ✅ FilterConverter handles nested error filters
- ✅ Integration tests pass with new error structure
- ✅ Error filtering works in GraphQL queries
- ✅ Manual test shows error in GraphQL response
- ✅ Documentation updated
- ✅ CLAUDE.md updated with field name guidance

## Constraints & Assumptions

**Constraints:**
- Must maintain consistency with Serverless Workflow 1.0.0 Error spec
- No new migration files (unreleased version, modify V1__)
- Keep existing trigger-based architecture
- Maintain COALESCE idempotency pattern

**Assumptions:**
- Quarkus Flow always emits same error structure for workflow and task events
- Error is terminal (once set, doesn't change)
- FluentBit correctly parses nested error objects from JSONB
- No existing production deployments to migrate

## Risks & Mitigation

| Risk | Impact | Mitigation |
|------|--------|------------|
| Breaking GraphQL API change | High | Acceptable - unreleased version |
| Trigger complexity increase | Medium | Follow existing COALESCE patterns, test thoroughly |
| Mapper configuration issues | Low | Use explicit mappings, integration tests |
| Documentation drift | Medium | Update docs as part of implementation, not after |

## Success Criteria

1. ✅ TaskExecution has structured `error` field matching WorkflowInstance
2. ✅ Database stores all 5 error fields for task executions
3. ✅ GraphQL query returns error structure for both workflow and task
4. ✅ Error filtering works via GraphQL (ErrorFilter for both workflow and task)
5. ✅ Integration tests pass
6. ✅ Manual KIND test shows correct error data and filtering
7. ✅ Documentation updated and accurate
8. ✅ No regression in workflow error handling

## Future Enhancements (Out of Scope)

- Error aggregation/statistics (e.g., count by error type)
- Error retry metadata
- Error classification improvements
- Full-text search on error.detail field
