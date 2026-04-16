# Database Schema - v1.0.0 (Event-Driven)

**Date**: 2026-04-15  
**Status**: ✅ Aligned with Domain Model

## Design Principle

Every table and column maps directly to Quarkus Flow structured logging events.

## Tables

### 1. workflow_instances

**Purpose**: Store workflow instance executions

**JPA Entity**: `org.kubesmarts.logic.dataindex.jpa.WorkflowInstanceEntity`

**Domain Model**: `org.kubesmarts.logic.dataindex.model.WorkflowInstance`

```sql
CREATE TABLE workflow_instances (
    -- Identity
    id VARCHAR(255) PRIMARY KEY,
    
    -- Workflow identification (from events)
    namespace VARCHAR(255),          -- workflowNamespace
    name VARCHAR(255),                -- workflowName
    version VARCHAR(255),             -- workflowVersion
    
    -- Status & lifecycle
    status VARCHAR(50),               -- RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
    start TIMESTAMP WITH TIME ZONE,   -- startTime from workflow.instance.started
    "end" TIMESTAMP WITH TIME ZONE,   -- endTime from workflow.instance.completed/faulted
    last_update TIMESTAMP WITH TIME ZONE,  -- lastUpdateTime from workflow.instance.status.changed
    
    -- Data (JSONB)
    input JSONB,                      -- input from workflow.instance.started
    output JSONB,                     -- output from workflow.instance.completed
    
    -- Error information (embedded)
    error_type VARCHAR(255),          -- error.type from workflow.instance.faulted
    error_title VARCHAR(255),         -- error.title
    error_detail TEXT,                -- error.detail
    error_status INTEGER,             -- error.status
    error_instance VARCHAR(255)       -- error.instance
);

-- Indexes
CREATE INDEX idx_workflow_instances_namespace_name ON workflow_instances(namespace, name);
CREATE INDEX idx_workflow_instances_status ON workflow_instances(status);
CREATE INDEX idx_workflow_instances_start ON workflow_instances(start DESC);
```

**Total Columns**: 14

**Event Mapping**:
```
workflow.instance.started → id, namespace, name, version, status, start, input
workflow.instance.completed → status, end, output
workflow.instance.faulted → status, end, error_type, error_title, error_detail, error_status, error_instance
workflow.instance.status.changed → status, last_update
```

### 2. task_executions

**Purpose**: Store task execution instances

**JPA Entity**: `org.kubesmarts.logic.dataindex.jpa.TaskExecutionEntity`

**Domain Model**: `org.kubesmarts.logic.dataindex.model.TaskExecution`

```sql
CREATE TABLE task_executions (
    -- Identity
    id VARCHAR(255) PRIMARY KEY,
    
    -- Foreign key to workflow instance
    workflow_instance_id VARCHAR(255) NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    
    -- Task identification
    task_name VARCHAR(255),           -- taskName from workflow.task.started
    task_position VARCHAR(255),       -- taskPosition (JSONPointer: "/do/0")
    
    -- Lifecycle
    enter TIMESTAMP WITH TIME ZONE,   -- startTime from workflow.task.started
    exit TIMESTAMP WITH TIME ZONE,    -- endTime from workflow.task.completed/faulted
    
    -- Error
    error_message TEXT,               -- error.title from workflow.task.faulted
    
    -- Data (JSONB)
    input_args JSONB,                 -- input from workflow.task.started
    output_args JSONB                 -- output from workflow.task.completed
);

-- Indexes
CREATE INDEX idx_task_executions_workflow_instance ON task_executions(workflow_instance_id);
CREATE INDEX idx_task_executions_position ON task_executions(task_position);
CREATE INDEX idx_task_executions_enter ON task_executions(enter DESC);
```

**Total Columns**: 9

**Event Mapping**:
```
workflow.task.started → id, workflow_instance_id, task_name, task_position, enter, input_args
workflow.task.completed → exit, output_args
workflow.task.faulted → exit, error_message
```

## Schema Summary

| Table | Columns | Purpose | Event Sources |
|-------|---------|---------|---------------|
| `workflow_instances` | 14 | Workflow executions | workflow.instance.* |
| `task_executions` | 9 | Task executions | workflow.task.* |

**Total Tables**: 2  
**Total Columns**: 23

## Field-by-Field Event Mapping

### workflow_instances

| Column | Type | Source Event | JSON Path |
|--------|------|--------------|-----------|
| id | VARCHAR(255) | workflow.instance.started | instanceId |
| namespace | VARCHAR(255) | workflow.instance.started | workflowNamespace |
| name | VARCHAR(255) | workflow.instance.started | workflowName |
| version | VARCHAR(255) | workflow.instance.started | workflowVersion |
| status | VARCHAR(50) | workflow.instance.* | status |
| start | TIMESTAMP | workflow.instance.started | startTime |
| end | TIMESTAMP | workflow.instance.completed/faulted | endTime |
| last_update | TIMESTAMP | workflow.instance.status.changed | lastUpdateTime |
| input | JSONB | workflow.instance.started | input |
| output | JSONB | workflow.instance.completed | output |
| error_type | VARCHAR(255) | workflow.instance.faulted | error.type |
| error_title | VARCHAR(255) | workflow.instance.faulted | error.title |
| error_detail | TEXT | workflow.instance.faulted | error.detail |
| error_status | INTEGER | workflow.instance.faulted | error.status |
| error_instance | VARCHAR(255) | workflow.instance.faulted | error.instance |

### task_executions

| Column | Type | Source Event | JSON Path |
|--------|------|--------------|-----------|
| id | VARCHAR(255) | workflow.task.started | taskExecutionId |
| workflow_instance_id | VARCHAR(255) | workflow.task.* | instanceId (FK) |
| task_name | VARCHAR(255) | workflow.task.started | taskName |
| task_position | VARCHAR(255) | workflow.task.started | taskPosition |
| enter | TIMESTAMP | workflow.task.started | startTime |
| exit | TIMESTAMP | workflow.task.completed/faulted | endTime |
| error_message | TEXT | workflow.task.faulted | error.title |
| input_args | JSONB | workflow.task.started | input |
| output_args | JSONB | workflow.task.completed | output |

## Key Design Features

### ✅ Separate Input/Output
- `input` and `output` are separate JSONB columns
- Matches event structure exactly
- Better queryability (can filter by input OR output)

### ✅ Embedded Error
- Error fields embedded in `workflow_instances` table
- No separate error table (error is part of instance lifecycle)
- Aligns with SW 1.0.0 Error spec

### ✅ Task Position as JSONPointer
- `task_position` stores JSONPointer (e.g., "/do/0", "/fork/branches/0/do/1")
- This is the SW 1.0.0 way to identify tasks
- Critical for correlating executions to workflow definition

### ✅ Enum as String
- `status` stored as VARCHAR (RUNNING, COMPLETED, etc.)
- Not ordinal integers (clearer, future-proof)

### ✅ Cascade Delete
- Deleting a workflow instance deletes all its task executions
- ON DELETE CASCADE on foreign key

## Data Ingestion Flow

```
Quarkus Flow Runtime
    ↓
Structured JSON Logs
    ↓
FluentBit (parses JSON)
    ↓
PostgreSQL (UPSERT into tables)
    ↓
JPA Entities (read via Hibernate)
    ↓
Domain Models (via MapStruct)
    ↓
GraphQL API
```

## Example Event → Row Mapping

### workflow.instance.started Event
```json
{
  "eventType": "io.serverlessworkflow.workflow.started.v1",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0",
  "status": "RUNNING",
  "startTime": "2026-04-15T15:30:00Z",
  "input": { "orderId": "12345" }
}
```

### Becomes INSERT/UPSERT
```sql
INSERT INTO workflow_instances (
    id, namespace, name, version, status, start, input
) VALUES (
    'uuid-1234',
    'default',
    'order-processing',
    '1.0.0',
    'RUNNING',
    '2026-04-15 15:30:00+00',
    '{"orderId": "12345"}'::jsonb
);
```

### workflow.instance.completed Event
```json
{
  "eventType": "io.serverlessworkflow.workflow.completed.v1",
  "instanceId": "uuid-1234",
  "status": "COMPLETED",
  "endTime": "2026-04-15T15:30:30Z",
  "output": { "result": "success" }
}
```

### Becomes UPDATE
```sql
UPDATE workflow_instances
SET 
    status = 'COMPLETED',
    "end" = '2026-04-15 15:30:30+00',
    output = '{"result": "success"}'::jsonb
WHERE id = 'uuid-1234';
```

## Next Steps

1. **Create Liquibase/Flyway migration** to generate schema
2. **Configure FluentBit** to parse Quarkus Flow logs → PostgreSQL
3. **Test with real workflows** - verify event ingestion
4. **Create MapStruct mappers** - Entity ↔ Domain model
5. **Generate GraphQL schema** from domain model

---

**Schema Status**: ✅ Fully aligned with domain model and Quarkus Flow events
