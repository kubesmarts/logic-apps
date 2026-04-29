# Task Error Structure Unification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Unify error handling by using a shared `Error` type for both workflow instances and task executions, exposing full error structure in GraphQL API with filtering support.

**Architecture:** Rename domain model classes, add 5 error columns to task_instances table, update PostgreSQL trigger to extract error fields, create GraphQL filters for error querying.

**Tech Stack:** Java 17, PostgreSQL, JPA/Hibernate, MapStruct, SmallRye GraphQL, Quarkus

---

## Task 1: Create Feature Branch

**Files:**
- Git operations only

- [ ] **Step 1: Create feature branch from main**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git checkout main
git pull origin main
git checkout -b feature/unify-error-structure
```

Expected: Switched to a new branch 'feature/unify-error-structure'

- [ ] **Step 2: Verify branch**

```bash
git branch --show-current
```

Expected: feature/unify-error-structure

---

## Task 2: Update Database Schema

**Files:**
- Modify: `data-index/data-index-storage/data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql`

- [ ] **Step 1: Add error columns to task_instances table CREATE statement**

Find the CREATE TABLE task_instances block and add error columns after the output column:

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
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  CONSTRAINT fk_task_instance_workflow FOREIGN KEY (instance_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
);
```

- [ ] **Step 2: Update normalize_task_event() INSERT section**

Find the INSERT INTO task_instances in normalize_task_event() function and add error fields:

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
  error_type,
  error_title,
  error_detail,
  error_status,
  error_instance,
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
  NEW.data->'error'->>'type',
  NEW.data->'error'->>'title',
  NEW.data->'error'->>'detail',
  (NEW.data->'error'->>'status')::integer,
  NEW.data->'error'->>'instance',
  event_timestamp,
  NEW.time,
  NEW.time
);
```

- [ ] **Step 3: Update normalize_task_event() UPDATE section**

Find the UPDATE task_instances in normalize_task_event() function and add error field handling:

```sql
UPDATE task_instances SET
  status = CASE
    WHEN event_timestamp > last_event_time
    THEN NEW.data->>'status'
    ELSE status
  END,
  
  task_name = COALESCE(task_name, NEW.data->>'taskName'),
  task_position = COALESCE(task_position, NEW.data->>'taskPosition'),
  start = COALESCE(start, to_timestamp((NEW.data->>'startTime')::numeric)),
  input = COALESCE(input, NEW.data->'input'),
  
  "end" = COALESCE(to_timestamp((NEW.data->>'endTime')::numeric), "end"),
  output = COALESCE(NEW.data->'output', output),
  
  error_type = COALESCE(NEW.data->'error'->>'type', error_type),
  error_title = COALESCE(NEW.data->'error'->>'title', error_title),
  error_detail = COALESCE(NEW.data->'error'->>'detail', error_detail),
  error_status = COALESCE((NEW.data->'error'->>'status')::integer, error_status),
  error_instance = COALESCE(NEW.data->'error'->>'instance', error_instance),
  
  last_event_time = GREATEST(event_timestamp, last_event_time),
  updated_at = NEW.time

WHERE instance_id = NEW.data->>'instanceId'
  AND task_position = NEW.data->>'taskPosition'
  AND "end" IS NULL;
```

- [ ] **Step 4: Commit database schema changes**

```bash
git add data-index/data-index-storage/data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql
git commit -m "feat(db): add error columns to task_instances table

Add 5 error columns (error_type, error_title, error_detail, error_status,
error_instance) to task_instances table and update normalize_task_event()
trigger to extract error fields from JSONB events.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

Expected: Changes committed successfully

---

## Task 3: Rename WorkflowInstanceError to Error (Domain Model)

**Files:**
- Rename: `data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/WorkflowInstanceError.java` → `Error.java`

- [ ] **Step 1: Rename the file using git mv**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git mv data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/WorkflowInstanceError.java \
       data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/Error.java
```

Expected: File renamed

- [ ] **Step 2: Update class name and javadoc in Error.java**

Change class declaration from `public class WorkflowInstanceError` to `public class Error` and update javadoc:

```java
/**
 * Error information for failed workflow and task executions.
 *
 * <p>Represents a runtime error from Serverless Workflow 1.0.0 execution.
 * Aligns with the <a href="https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#error">SW 1.0.0 Error spec</a>
 *
 * <p>This captures error details from Quarkus Flow structured logging events
 * (workflow.instance.faulted, workflow.task.faulted).
 * 
 * <p>Used by both WorkflowInstance and TaskExecution.
 */
public class Error {
```

Update toString method:

```java
@Override
public String toString() {
    return "Error{" +
            "type='" + type + '\'' +
            ", title='" + title + '\'' +
            ", detail='" + detail + '\'' +
            ", status=" + status +
            ", instance='" + instance + '\'' +
            '}';
}
```

Update equals method:

```java
@Override
public boolean equals(Object o) {
    if (this == o) {
        return true;
    }
    if (o == null || getClass() != o.getClass()) {
        return false;
    }
    Error that = (Error) o;
    return Objects.equals(type, that.type) &&
            Objects.equals(title, that.title) &&
            Objects.equals(detail, that.detail) &&
            Objects.equals(status, that.status) &&
            Objects.equals(instance, that.instance);
}
```

- [ ] **Step 3: Verify compilation**

```bash
cd data-index/data-index-model
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/Error.java
git commit -m "refactor(model): rename WorkflowInstanceError to Error

Rename to generic Error type that can be used by both WorkflowInstance
and TaskExecution domain models.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Update WorkflowInstance to Use Error Type

**Files:**
- Modify: `data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/WorkflowInstance.java`

- [ ] **Step 1: Update import statement**

Change:
```java
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceError;
```

To:
```java
import org.kubesmarts.logic.dataindex.model.Error;
```

- [ ] **Step 2: Update field type**

Change:
```java
/**
 * Error information if instance failed.
 * <p>Source: error object from workflow.instance.faulted event
 */
private WorkflowInstanceError error;
```

To:
```java
/**
 * Error information if instance failed.
 * <p>Source: error object from workflow.instance.faulted event
 */
private Error error;
```

- [ ] **Step 3: Update getter/setter**

Change:
```java
public WorkflowInstanceError getError() {
    return error;
}

public void setError(WorkflowInstanceError error) {
    this.error = error;
}
```

To:
```java
public Error getError() {
    return error;
}

public void setError(Error error) {
    this.error = error;
}
```

- [ ] **Step 4: Verify compilation**

```bash
cd data-index/data-index-model
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/WorkflowInstance.java
git commit -m "refactor(model): update WorkflowInstance to use Error type

Update WorkflowInstance.error field from WorkflowInstanceError to Error.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Update TaskExecution to Use Error Type

**Files:**
- Modify: `data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/TaskExecution.java`

- [ ] **Step 1: Add import for Error**

Add after existing imports:
```java
import org.kubesmarts.logic.dataindex.model.Error;
```

- [ ] **Step 2: Remove errorMessage field and methods**

Remove:
```java
private String errorMessage;

public String getErrorMessage() {
    return errorMessage;
}

public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
}
```

- [ ] **Step 3: Add error field and methods**

Add after the `end` field:
```java
/**
 * Error information if task failed.
 * <p>Source: error object from workflow.task.faulted event
 */
private Error error;

public Error getError() {
    return error;
}

public void setError(Error error) {
    this.error = error;
}
```

- [ ] **Step 4: Update toString method**

Change:
```java
@Override
public String toString() {
    return "TaskExecution{" +
            "id='" + id + '\'' +
            ", taskName='" + taskName + '\'' +
            ", taskPosition='" + taskPosition + '\'' +
            ", status='" + status + '\'' +
            ", start=" + start +
            ", end=" + end +
            ", errorMessage='" + errorMessage + '\'' +
            '}';
}
```

To:
```java
@Override
public String toString() {
    return "TaskExecution{" +
            "id='" + id + '\'' +
            ", taskName='" + taskName + '\'' +
            ", taskPosition='" + taskPosition + '\'' +
            ", status='" + status + '\'' +
            ", start=" + start +
            ", end=" + end +
            ", error=" + error +
            '}';
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-model
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/TaskExecution.java
git commit -m "feat(model): replace TaskExecution.errorMessage with Error object

Remove simple errorMessage string field and add structured Error object
to match WorkflowInstance error handling.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Rename WorkflowInstanceErrorEntity to ErrorEntity

**Files:**
- Rename: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/WorkflowInstanceErrorEntity.java` → `ErrorEntity.java`

- [ ] **Step 1: Rename the file using git mv**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git mv data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/WorkflowInstanceErrorEntity.java \
       data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/ErrorEntity.java
```

Expected: File renamed

- [ ] **Step 2: Update class name and javadoc**

Change:
```java
/**
 * JPA entity for workflow instance error information.
 *
 * <p><b>Design principle:</b> This entity stores data from the error object in
 * workflow.instance.faulted events. It aligns with the Serverless Workflow 1.0.0 Error spec.
 *
 * <p><b>Event source:</b>
 * <ul>
 *   <li>workflow.instance.faulted → error object with type, title, detail, status, instance
 * </ul>
 *
 * <p>Maps to WorkflowInstanceError domain model.
 *
 * <p>Embedded in WorkflowInstanceEntity (not a separate table).
 */
@Embeddable
public class WorkflowInstanceErrorEntity {
```

To:
```java
/**
 * JPA embeddable for error information.
 *
 * <p><b>Design principle:</b> This entity stores data from the error object in
 * workflow and task faulted events. It aligns with the Serverless Workflow 1.0.0 Error spec.
 *
 * <p><b>Event sources:</b>
 * <ul>
 *   <li>workflow.instance.faulted → error object with type, title, detail, status, instance
 *   <li>workflow.task.faulted → error object with type, title, detail, status, instance
 * </ul>
 *
 * <p>Maps to Error domain model.
 *
 * <p>Used by both WorkflowInstanceEntity and TaskInstanceEntity.
 * Maps to error_* columns in workflow_instances and task_instances tables.
 */
@Embeddable
public class ErrorEntity {
```

- [ ] **Step 3: Update toString method**

Change:
```java
@Override
public String toString() {
    return "WorkflowInstanceErrorEntity{" +
            "type='" + type + '\'' +
            ", title='" + title + '\'' +
            ", status=" + status +
            ", instance='" + instance + '\'' +
            '}';
}
```

To:
```java
@Override
public String toString() {
    return "ErrorEntity{" +
            "type='" + type + '\'' +
            ", title='" + title + '\'' +
            ", status=" + status +
            ", instance='" + instance + '\'' +
            '}';
}
```

- [ ] **Step 4: Update equals method**

Change:
```java
@Override
public boolean equals(Object o) {
    if (this == o) {
        return true;
    }
    if (o == null || getClass() != o.getClass()) {
        return false;
    }
    WorkflowInstanceErrorEntity that = (WorkflowInstanceErrorEntity) o;
    return Objects.equals(type, that.type) &&
            Objects.equals(title, that.title) &&
            Objects.equals(detail, that.detail) &&
            Objects.equals(status, that.status) &&
            Objects.equals(instance, that.instance);
}
```

To:
```java
@Override
public boolean equals(Object o) {
    if (this == o) {
        return true;
    }
    if (o == null || getClass() != o.getClass()) {
        return false;
    }
    ErrorEntity that = (ErrorEntity) o;
    return Objects.equals(type, that.type) &&
            Objects.equals(title, that.title) &&
            Objects.equals(detail, that.detail) &&
            Objects.equals(status, that.status) &&
            Objects.equals(instance, that.instance);
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/ErrorEntity.java
git commit -m "refactor(entity): rename WorkflowInstanceErrorEntity to ErrorEntity

Rename to generic ErrorEntity that can be embedded in both
WorkflowInstanceEntity and TaskInstanceEntity.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 7: Update WorkflowInstanceEntity to Use ErrorEntity

**Files:**
- Modify: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/WorkflowInstanceEntity.java`

- [ ] **Step 1: Update import statement**

Change:
```java
import org.kubesmarts.logic.dataindex.storage.entity.WorkflowInstanceErrorEntity;
```

To:
```java
import org.kubesmarts.logic.dataindex.storage.entity.ErrorEntity;
```

- [ ] **Step 2: Update field type**

Change:
```java
/**
 * Error information if instance failed.
 * <p>Source: error object from workflow.instance.faulted event
 */
@Embedded
private WorkflowInstanceErrorEntity error;
```

To:
```java
/**
 * Error information if instance failed.
 * <p>Source: error object from workflow.instance.faulted event
 */
@Embedded
private ErrorEntity error;
```

- [ ] **Step 3: Update getter/setter**

Change:
```java
public WorkflowInstanceErrorEntity getError() {
    return error;
}

public void setError(WorkflowInstanceErrorEntity error) {
    this.error = error;
}
```

To:
```java
public ErrorEntity getError() {
    return error;
}

public void setError(ErrorEntity error) {
    this.error = error;
}
```

- [ ] **Step 4: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/WorkflowInstanceEntity.java
git commit -m "refactor(entity): update WorkflowInstanceEntity to use ErrorEntity

Update error field from WorkflowInstanceErrorEntity to ErrorEntity.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Add Error Field to TaskInstanceEntity

**Files:**
- Modify: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/TaskInstanceEntity.java`

- [ ] **Step 1: Add import for ErrorEntity**

Add after existing imports:
```java
import jakarta.persistence.Embedded;
```

- [ ] **Step 2: Add error field after output field**

Add after the output field declaration:
```java
/**
 * Error information if task failed.
 * <p>Source: error object from workflow.task.faulted event
 * <p>Extracted by trigger from: data->'error'
 * <p>Stored in error_* columns
 */
@Embedded
private ErrorEntity error;
```

- [ ] **Step 3: Add getter/setter after getOutput/setOutput**

Add:
```java
public ErrorEntity getError() {
    return error;
}

public void setError(ErrorEntity error) {
    this.error = error;
}
```

- [ ] **Step 4: Update toString method**

Change:
```java
@Override
public String toString() {
    return "TaskInstanceEntity{" +
            "taskExecutionId='" + taskExecutionId + '\'' +
            ", instanceId='" + instanceId + '\'' +
            ", taskName='" + taskName + '\'' +
            ", taskPosition='" + taskPosition + '\'' +
            ", status='" + status + '\'' +
            ", start=" + start +
            ", end=" + end +
            '}';
}
```

To:
```java
@Override
public String toString() {
    return "TaskInstanceEntity{" +
            "taskExecutionId='" + taskExecutionId + '\'' +
            ", instanceId='" + instanceId + '\'' +
            ", taskName='" + taskName + '\'' +
            ", taskPosition='" + taskPosition + '\'' +
            ", status='" + status + '\'' +
            ", start=" + start +
            ", end=" + end +
            ", error=" + error +
            '}';
}
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/entity/TaskInstanceEntity.java
git commit -m "feat(entity): add error field to TaskInstanceEntity

Add @Embedded ErrorEntity error field to capture task error information
from database error_* columns.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 9: Rename WorkflowInstanceErrorEntityMapper to ErrorEntityMapper

**Files:**
- Rename: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/WorkflowInstanceErrorEntityMapper.java` → `ErrorEntityMapper.java`

- [ ] **Step 1: Rename the file using git mv**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git mv data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/WorkflowInstanceErrorEntityMapper.java \
       data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/ErrorEntityMapper.java
```

Expected: File renamed

- [ ] **Step 2: Update imports**

Change:
```java
import org.kubesmarts.logic.dataindex.storage.entity.WorkflowInstanceErrorEntity;
import org.kubesmarts.logic.dataindex.model.WorkflowInstanceError;
```

To:
```java
import org.kubesmarts.logic.dataindex.storage.entity.ErrorEntity;
import org.kubesmarts.logic.dataindex.model.Error;
```

- [ ] **Step 3: Update interface name and javadoc**

Change:
```java
/**
 * MapStruct mapper for WorkflowInstanceError domain model and WorkflowInstanceErrorEntity JPA embeddable.
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface WorkflowInstanceErrorEntityMapper {
```

To:
```java
/**
 * MapStruct mapper for Error domain model and ErrorEntity JPA embeddable.
 * 
 * <p>Used for both workflow instance and task execution error mapping.
 */
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
public interface ErrorEntityMapper {
```

- [ ] **Step 4: Update method signatures**

Change:
```java
/**
 * Convert JPA embeddable to domain model.
 */
WorkflowInstanceError toModel(WorkflowInstanceErrorEntity entity);

/**
 * Convert domain model to JPA embeddable.
 */
WorkflowInstanceErrorEntity toEntity(WorkflowInstanceError model);
```

To:
```java
/**
 * Convert JPA embeddable to domain model.
 */
Error toModel(ErrorEntity entity);

/**
 * Convert domain model to JPA embeddable.
 */
ErrorEntity toEntity(Error model);
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/ErrorEntityMapper.java
git commit -m "refactor(mapper): rename WorkflowInstanceErrorEntityMapper to ErrorEntityMapper

Rename to generic ErrorEntityMapper for use with both workflow and task errors.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 10: Update WorkflowInstanceEntityMapper to Use ErrorEntityMapper

**Files:**
- Modify: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/WorkflowInstanceEntityMapper.java`

- [ ] **Step 1: Update uses annotation**

Find:
```java
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
```

Change to:
```java
@Mapper(componentModel = "cdi", 
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        uses = ErrorEntityMapper.class)
```

- [ ] **Step 2: Add explicit error mapping to toModel method**

Find the toModel method and ensure it has explicit error mapping:
```java
@Mapping(target = "id", source = "id")
@Mapping(target = "error", source = "error")
WorkflowInstance toModel(WorkflowInstanceEntity entity);
```

- [ ] **Step 3: Add explicit error mapping to toEntity method**

Find the toEntity method and ensure it has explicit error mapping:
```java
@Mapping(target = "id", source = "id")
@Mapping(target = "error", source = "error")
@Mapping(target = "taskExecutions", ignore = true)
@Mapping(target = "createdAt", ignore = true)
@Mapping(target = "updatedAt", ignore = true)
WorkflowInstanceEntity toEntity(WorkflowInstance model);
```

- [ ] **Step 4: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/WorkflowInstanceEntityMapper.java
git commit -m "refactor(mapper): update WorkflowInstanceEntityMapper to use ErrorEntityMapper

Add ErrorEntityMapper to uses clause and explicit error field mappings.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 11: Update TaskInstanceEntityMapper to Use ErrorEntityMapper

**Files:**
- Modify: `data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/TaskInstanceEntityMapper.java`

- [ ] **Step 1: Update uses annotation**

Change:
```java
@Mapper(componentModel = "cdi", injectionStrategy = InjectionStrategy.CONSTRUCTOR)
```

To:
```java
@Mapper(componentModel = "cdi", 
        injectionStrategy = InjectionStrategy.CONSTRUCTOR,
        uses = ErrorEntityMapper.class)
```

- [ ] **Step 2: Add explicit error mapping to toModel method**

Find the toModel method and add error mapping:
```java
@Mapping(target = "id", source = "taskExecutionId")
@Mapping(target = "start", source = "start")
@Mapping(target = "end", source = "end")
@Mapping(target = "input", source = "input")
@Mapping(target = "output", source = "output")
@Mapping(target = "error", source = "error")
TaskExecution toModel(TaskInstanceEntity entity);
```

- [ ] **Step 3: Add explicit error mapping to toEntity method**

Find the toEntity method and add error mapping:
```java
@Mapping(target = "taskExecutionId", source = "id")
@Mapping(target = "error", source = "error")
@Mapping(target = "instanceId", ignore = true)
@Mapping(target = "workflowInstance", ignore = true)
@Mapping(target = "createdAt", ignore = true)
@Mapping(target = "updatedAt", ignore = true)
TaskInstanceEntity toEntity(TaskExecution model);
```

- [ ] **Step 4: Add error mapping to updateEntityFromModel method**

Find the updateEntityFromModel method and ensure error is not ignored:
```java
@Mapping(target = "taskExecutionId", ignore = true)
@Mapping(target = "error", source = "error")
@Mapping(target = "instanceId", ignore = true)
@Mapping(target = "workflowInstance", ignore = true)
@Mapping(target = "createdAt", ignore = true)
@Mapping(target = "updatedAt", ignore = true)
void updateEntityFromModel(TaskExecution model, @MappingTarget TaskInstanceEntity entity);
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-storage/data-index-storage-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/mapper/TaskInstanceEntityMapper.java
git commit -m "feat(mapper): add error field mapping to TaskInstanceEntityMapper

Add ErrorEntityMapper to uses clause and explicit error field mappings
for toModel, toEntity, and updateEntityFromModel methods.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 12: Create IntFilter for Integer Field Filtering

**Files:**
- Create: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/IntFilter.java`

- [ ] **Step 1: Create IntFilter class**

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.graphql.filter;

import java.util.List;

import org.eclipse.microprofile.graphql.Description;

/**
 * Integer field filter for GraphQL queries.
 *
 * <p>Supports equality, comparison, and list inclusion operations.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   errorStatus: { eq: 500 }
 *   errorStatus: { gte: 400, lt: 500 }
 *   errorStatus: { in: [400, 404, 500] }
 * }
 * </pre>
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

    public Integer getEq() {
        return eq;
    }

    public void setEq(Integer eq) {
        this.eq = eq;
    }

    public Integer getGt() {
        return gt;
    }

    public void setGt(Integer gt) {
        this.gt = gt;
    }

    public Integer getGte() {
        return gte;
    }

    public void setGte(Integer gte) {
        this.gte = gte;
    }

    public Integer getLt() {
        return lt;
    }

    public void setLt(Integer lt) {
        this.lt = lt;
    }

    public Integer getLte() {
        return lte;
    }

    public void setLte(Integer lte) {
        this.lte = lte;
    }

    public List<Integer> getIn() {
        return in;
    }

    public void setIn(List<Integer> in) {
        this.in = in;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/IntFilter.java
git commit -m "feat(filter): create IntFilter for integer field filtering

Add IntFilter to support integer field filtering in GraphQL queries
with eq, gt, gte, lt, lte, and in operations.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 13: Create ErrorFilter for Structured Error Filtering

**Files:**
- Create: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/ErrorFilter.java`

- [ ] **Step 1: Create ErrorFilter class**

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.graphql.filter;

import org.eclipse.microprofile.graphql.Description;

/**
 * Error field filter for GraphQL queries.
 *
 * <p>Used by both WorkflowInstanceFilter and TaskExecutionFilter to filter
 * by error fields.
 *
 * <p>Example GraphQL usage:
 * <pre>
 * filter: {
 *   error: {
 *     type: { eq: "communication" }
 *     status: { gte: 500 }
 *     instance: { like: "do/0/*" }
 *   }
 * }
 * </pre>
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

    public StringFilter getType() {
        return type;
    }

    public void setType(StringFilter type) {
        this.type = type;
    }

    public StringFilter getTitle() {
        return title;
    }

    public void setTitle(StringFilter title) {
        this.title = title;
    }

    public StringFilter getDetail() {
        return detail;
    }

    public void setDetail(StringFilter detail) {
        this.detail = detail;
    }

    public IntFilter getStatus() {
        return status;
    }

    public void setStatus(IntFilter status) {
        this.status = status;
    }

    public StringFilter getInstance() {
        return instance;
    }

    public void setInstance(StringFilter instance) {
        this.instance = instance;
    }
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/ErrorFilter.java
git commit -m "feat(filter): create ErrorFilter for structured error filtering

Add ErrorFilter to support nested error field filtering in GraphQL queries
for both workflow instances and task executions.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 14: Update TaskExecutionFilter to Use ErrorFilter

**Files:**
- Modify: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/TaskExecutionFilter.java`

- [ ] **Step 1: Remove errorMessage field and methods**

Remove:
```java
@Description("Filter by error message")
private StringFilter errorMessage;

public StringFilter getErrorMessage() {
    return errorMessage;
}

public void setErrorMessage(StringFilter errorMessage) {
    this.errorMessage = errorMessage;
}
```

- [ ] **Step 2: Add error field and methods**

Add after the exit field:
```java
@Description("Filter by error fields")
private ErrorFilter error;

public ErrorFilter getError() {
    return error;
}

public void setError(ErrorFilter error) {
    this.error = error;
}
```

- [ ] **Step 3: Update javadoc example**

Update the class javadoc example from:
```java
 *     filter: {
 *       taskName: { eq: "callPaymentService" }
 *       enter: { gte: "2026-01-01T00:00:00Z" }
 *       inputArgs: {
 *         eq: { customerId: "customer-123" }
 *       }
 *     }
```

To:
```java
 *     filter: {
 *       taskName: { eq: "callPaymentService" }
 *       enter: { gte: "2026-01-01T00:00:00Z" }
 *       error: {
 *         type: { eq: "communication" }
 *         status: { gte: 500 }
 *       }
 *     }
```

- [ ] **Step 4: Verify compilation**

```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/TaskExecutionFilter.java
git commit -m "feat(filter): replace errorMessage with ErrorFilter in TaskExecutionFilter

Remove simple errorMessage StringFilter and add structured ErrorFilter
to enable nested error field filtering.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 15: Add ErrorFilter to WorkflowInstanceFilter

**Files:**
- Modify: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/WorkflowInstanceFilter.java`

- [ ] **Step 1: Add error field and methods**

Add after the output field:
```java
@Description("Filter by error fields")
private ErrorFilter error;

public ErrorFilter getError() {
    return error;
}

public void setError(ErrorFilter error) {
    this.error = error;
}
```

- [ ] **Step 2: Update javadoc example**

Update the class javadoc example to include error filtering:
```java
 *     filter: {
 *       status: { eq: COMPLETED }
 *       namespace: { eq: "production" }
 *       startTime: { gte: "2026-01-01T00:00:00Z" }
 *       error: {
 *         type: { eq: "communication" }
 *         status: { gte: 500 }
 *       }
 *     }
```

- [ ] **Step 3: Verify compilation**

```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/WorkflowInstanceFilter.java
git commit -m "feat(filter): add ErrorFilter to WorkflowInstanceFilter

Add error field with ErrorFilter to enable error filtering for
workflow instances (was previously missing).

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 16: Update FilterConverter to Handle Error Filtering

**Files:**
- Modify: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/FilterConverter.java`

- [ ] **Step 1: Add addIntFilters method**

Add after the addDateTimeFilters method:
```java
/**
 * Add integer field filters.
 */
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

- [ ] **Step 2: Add addErrorFilters method**

Add after the addIntFilters method:
```java
/**
 * Add error field filters.
 * 
 * <p>Maps nested ErrorFilter fields to database column names:
 * <ul>
 *   <li>error.type → error_type
 *   <li>error.title → error_title
 *   <li>error.detail → error_detail
 *   <li>error.status → error_status
 *   <li>error.instance → error_instance
 * </ul>
 */
private static void addErrorFilters(List<AttributeFilter<?>> result, ErrorFilter filter) {
    if (filter == null) {
        return;
    }

    addStringFilters(result, "error_type", filter.getType());
    addStringFilters(result, "error_title", filter.getTitle());
    addStringFilters(result, "error_detail", filter.getDetail());
    addIntFilters(result, "error_status", filter.getStatus());
    addStringFilters(result, "error_instance", filter.getInstance());
}
```

- [ ] **Step 3: Update convert(WorkflowInstanceFilter) method**

Add error filter handling before the return statement:
```java
// Error filters
addErrorFilters(result, filter.getError());

return result;
```

- [ ] **Step 4: Update convert(TaskExecutionFilter) method**

Remove:
```java
addStringFilters(result, "errorMessage", filter.getErrorMessage());
```

Add:
```java
// Error filters
addErrorFilters(result, filter.getError());
```

- [ ] **Step 5: Verify compilation**

```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/FilterConverter.java
git commit -m "feat(filter): add IntFilter and ErrorFilter conversion to FilterConverter

Add addIntFilters() and addErrorFilters() methods to convert nested
error filter fields to database column names. Update both convert()
methods to handle error filtering.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 17: Update Integration Test - Test Data Setup

**Files:**
- Modify: `data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java`

- [ ] **Step 1: Find setupTestData method and add error test data**

Add after existing test data setup, before the method ends:
```java
// Create workflow instance with error for error structure testing
WorkflowInstanceEntity faultedWf = new WorkflowInstanceEntity();
faultedWf.setId("faulted-wf-123");
faultedWf.setNamespace("org.acme");
faultedWf.setName("petstore");
faultedWf.setVersion("0.0.1");
faultedWf.setStatus(WorkflowInstanceStatus.FAULTED);
faultedWf.setStart(ZonedDateTime.now());
faultedWf.setEnd(ZonedDateTime.now());

ErrorEntity wfError = new ErrorEntity();
wfError.setType("communication");
wfError.setStatus(500);
wfError.setTitle("Internal Server Error");
wfError.setInstance("do/0/findPetByStatus");
wfError.setDetail("{\"code\":500,\"message\":\"There was an error processing your request\"}");
faultedWf.setError(wfError);
em.persist(faultedWf);

// Create task instance with error
TaskInstanceEntity failedTask = new TaskInstanceEntity();
failedTask.setTaskExecutionId("failed-task-456");
failedTask.setInstanceId("faulted-wf-123");
failedTask.setTaskPosition("do/0/findPetByStatus");
failedTask.setTaskName("findPetByStatus");
failedTask.setStatus("FAILED");
failedTask.setStart(ZonedDateTime.now().minusSeconds(10));
failedTask.setEnd(ZonedDateTime.now());

ErrorEntity taskError = new ErrorEntity();
taskError.setType("communication");
taskError.setStatus(500);
taskError.setTitle("Internal Server Error");
taskError.setDetail("{\"code\":500,\"message\":\"API call failed\"}");
taskError.setInstance("do/0/findPetByStatus");
failedTask.setError(taskError);
em.persist(failedTask);
```

- [ ] **Step 2: Verify compilation**

```bash
cd data-index/data-index-integration-tests/data-index-integration-tests-postgresql
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java
git commit -m "test: add error test data to integration test setup

Add faulted workflow instance and failed task instance with error
objects for testing error structure in GraphQL queries.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 18: Add Integration Test for Error Structure

**Files:**
- Modify: `data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java`

- [ ] **Step 1: Write failing test for error structure**

Add test method:
```java
@Test
void testTaskExecutionWithErrorStructure() {
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
        .body(Map.of("query", query))
    .when()
        .post("/graphql")
    .then()
        .statusCode(200)
        .body("data.getWorkflowInstances[0].id", equalTo("faulted-wf-123"))
        .body("data.getWorkflowInstances[0].error.type", equalTo("communication"))
        .body("data.getWorkflowInstances[0].error.status", equalTo(500))
        .body("data.getWorkflowInstances[0].error.title", equalTo("Internal Server Error"))
        .body("data.getWorkflowInstances[0].error.instance", equalTo("do/0/findPetByStatus"))
        .body("data.getWorkflowInstances[0].taskExecutions[0].id", equalTo("failed-task-456"))
        .body("data.getWorkflowInstances[0].taskExecutions[0].error.type", equalTo("communication"))
        .body("data.getWorkflowInstances[0].taskExecutions[0].error.status", equalTo(500))
        .body("data.getWorkflowInstances[0].taskExecutions[0].error.instance", equalTo("do/0/findPetByStatus"));
}
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd data-index/data-index-integration-tests/data-index-integration-tests-postgresql
mvn test -Dtest=WorkflowInstanceGraphQLApiTest#testTaskExecutionWithErrorStructure
```

Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java
git commit -m "test: add integration test for error structure in GraphQL

Verify that both workflow instance and task execution error objects
are correctly exposed in GraphQL API with all error fields.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 19: Add Integration Test for Error Filtering

**Files:**
- Modify: `data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java`

- [ ] **Step 1: Write failing test for error filtering**

Add test method:
```java
@Test
void testErrorFiltering() {
    String query = """
        {
          workflowsByError: getWorkflowInstances(
            filter: {
              error: {
                type: { eq: "communication" }
                status: { gte: 500 }
              }
            }
          ) {
            id
            name
            error { type, status, title }
          }
          
          tasksByError: getTaskExecutions(
            filter: {
              error: {
                status: { eq: 500 }
                instance: { eq: "do/0/findPetByStatus" }
              }
            }
          ) {
            id
            taskPosition
            error { status, instance, type }
          }
        }
        """;
    
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("query", query))
    .when()
        .post("/graphql")
    .then()
        .statusCode(200)
        .body("data.workflowsByError.size()", greaterThan(0))
        .body("data.workflowsByError[0].error.type", equalTo("communication"))
        .body("data.workflowsByError[0].error.status", equalTo(500))
        .body("data.tasksByError.size()", greaterThan(0))
        .body("data.tasksByError[0].error.status", equalTo(500))
        .body("data.tasksByError[0].error.instance", equalTo("do/0/findPetByStatus"));
}
```

- [ ] **Step 2: Run test to verify it passes**

```bash
cd data-index/data-index-integration-tests/data-index-integration-tests-postgresql
mvn test -Dtest=WorkflowInstanceGraphQLApiTest#testErrorFiltering
```

Expected: PASS

- [ ] **Step 3: Run all integration tests**

```bash
cd data-index/data-index-integration-tests/data-index-integration-tests-postgresql
mvn clean test
```

Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java
git commit -m "test: add integration test for error filtering in GraphQL

Verify that ErrorFilter works correctly for both workflow instances
and task executions with nested field filtering.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 20: Update CLAUDE.md Documentation

**Files:**
- Modify: `data-index/CLAUDE.md`

- [ ] **Step 1: Add Error type to field name mapping section**

Find the "Field Names - Critical Mapping" section and add:

```markdown
### Error Handling

**Error structure (unified for workflows and tasks):**
```
Database columns → JPA Entity → GraphQL:
error_type       → ErrorEntity.type     → Error.type
error_title      → ErrorEntity.title    → Error.title
error_detail     → ErrorEntity.detail   → Error.detail
error_status     → ErrorEntity.status   → Error.status
error_instance   → ErrorEntity.instance → Error.instance
```

**Domain model:**
- `Error` - Generic error type used by both WorkflowInstance and TaskExecution
- Previously `WorkflowInstanceError` (renamed for reuse)

**JPA entities:**
- `ErrorEntity` - Embeddable entity used by WorkflowInstanceEntity and TaskInstanceEntity
- Previously `WorkflowInstanceErrorEntity` (renamed for reuse)

**GraphQL filtering:**
- `ErrorFilter` - Nested filter for error fields
- `IntFilter` - Integer field filter (used by error.status)
- Available for both workflow instances and task executions
```

- [ ] **Step 2: Update the "What NOT to Do" section**

Add to the Code section:
```markdown
- Don't use `WorkflowInstanceError` or `WorkflowInstanceErrorEntity` (renamed to `Error` and `ErrorEntity`)
- Don't use `TaskExecution.errorMessage` (replaced with `error: Error`)
```

- [ ] **Step 3: Commit**

```bash
git add data-index/CLAUDE.md
git commit -m "docs: update CLAUDE.md with Error type documentation

Document Error type unification, field name mappings, and GraphQL
error filtering capabilities.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Task 21: Final Verification and Testing

**Files:**
- All modified files

- [ ] **Step 1: Run full build from root**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index
mvn clean install
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Verify GraphQL schema changes**

Start dev mode and check GraphQL schema:
```bash
cd data-index/data-index-service/data-index-service-core
mvn quarkus:dev -Dquarkus.profile=postgresql
```

Open browser to: http://localhost:8080/graphql-ui

Verify schema shows:
- TaskExecution.error (not errorMessage)
- Error type with all 5 fields
- ErrorFilter available in both WorkflowInstanceFilter and TaskExecutionFilter
- IntFilter available

Stop dev mode with Ctrl+C

- [ ] **Step 3: Create summary commit**

```bash
git log --oneline --no-decorate feature/unify-error-structure ^main > /tmp/commits.txt
cat /tmp/commits.txt
```

Expected: List of all commits from this feature

- [ ] **Step 4: Push feature branch**

```bash
git push -u origin feature/unify-error-structure
```

Expected: Branch pushed successfully

---

## Self-Review Checklist

**Spec coverage:**
- ✅ Database schema updated with 5 error columns in task_instances
- ✅ Trigger extracts error fields from JSONB
- ✅ WorkflowInstanceError renamed to Error
- ✅ WorkflowInstance uses Error type
- ✅ TaskExecution uses Error type (removed errorMessage)
- ✅ ErrorEntity created (renamed from WorkflowInstanceErrorEntity)
- ✅ TaskInstanceEntity has embedded ErrorEntity
- ✅ Mappers updated to handle error fields
- ✅ IntFilter created for integer filtering
- ✅ ErrorFilter created for nested error filtering
- ✅ TaskExecutionFilter uses ErrorFilter
- ✅ WorkflowInstanceFilter uses ErrorFilter
- ✅ FilterConverter handles error filtering
- ✅ Integration tests cover error structure
- ✅ Integration tests cover error filtering
- ✅ CLAUDE.md documentation updated

**Type consistency:**
- ✅ Error (domain model) ↔ ErrorEntity (JPA) ↔ ErrorEntityMapper
- ✅ Error used consistently in WorkflowInstance and TaskExecution
- ✅ ErrorEntity embedded consistently in WorkflowInstanceEntity and TaskInstanceEntity
- ✅ ErrorFilter used consistently in both filter classes
- ✅ Database column names match across table and trigger

**No placeholders:**
- ✅ All code blocks are complete
- ✅ All SQL statements are complete
- ✅ All test methods are complete
- ✅ All file paths are absolute and correct

---

## Next Steps

After completing this plan:

1. **Manual testing in KIND:**
   - Deploy a faulting workflow (petstore example)
   - Verify database has error data: `SELECT error_type, error_title FROM task_instances WHERE status='FAILED';`
   - Test GraphQL error queries and filtering
   - Verify trigger idempotency with out-of-order events

2. **Create Pull Request:**
   - Base: `main`
   - Head: `feature/unify-error-structure`
   - Title: "feat: unify error structure for workflows and tasks"
   - Include summary of changes and testing done

3. **Update user-facing documentation** (if needed):
   - GraphQL API examples in data-index-docs
   - Database schema documentation
