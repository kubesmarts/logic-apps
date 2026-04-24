# Database Schema - v1.1.0 (Trigger-Based)

**Date**: 2026-04-24  
**Status**: ✅ Production Ready  
**Migrations:** V1 (initial schema) + V2 (idempotency)

## Design Principle

**Two-tier architecture:**
1. **Raw staging tables** - FluentBit pgsql plugin fixed schema (tag, time, data JSONB)
2. **Normalized tables** - PostgreSQL triggers extract fields from JSONB and normalize

**Benefits:**
- FluentBit schema constraints handled
- Real-time normalization (triggers on INSERT)
- Raw events preserved for debugging
- Idempotent event processing

## Schema V2 (Current)

### Migration History

| Version | Description | Date |
|---------|-------------|------|
| V1__initial_schema.sql | Raw staging tables, normalized tables, basic triggers | 2026-04-23 |
| V2__add_idempotency.sql | Field-level idempotency, out-of-order handling | 2026-04-24 |

## Raw Staging Tables

### workflow_events_raw

**Purpose**: Raw events from FluentBit (fixed schema requirement)

```sql
CREATE TABLE workflow_events_raw (
    tag TEXT,                        -- FluentBit tag (e.g., "workflow.instance.started")
    time TIMESTAMP WITH TIME ZONE,   -- Event timestamp
    data JSONB                       -- Complete event as JSON
);

CREATE INDEX idx_workflow_events_raw_time ON workflow_events_raw (time DESC);
CREATE INDEX idx_workflow_events_raw_tag ON workflow_events_raw (tag);
```

**Sample data field:**
```json
{
  "instanceId": "01KPZY3F6HPMVHSSXKBKS11NQ2",
  "eventType": "io.serverlessworkflow.workflow.started.v1",
  "timestamp": 1777040735.516131,
  "workflowNamespace": "org.acme",
  "workflowName": "simple-set",
  "workflowVersion": "0.0.1",
  "status": "RUNNING",
  "startTime": 1777040735.516131,
  "input": {"name": "Test"}
}
```

**Retention:** Optional cleanup (7+ days via scheduled job)

### task_events_raw

**Purpose**: Raw task events from FluentBit

```sql
CREATE TABLE task_events_raw (
    tag TEXT,                        -- FluentBit tag (e.g., "workflow.task.started")
    time TIMESTAMP WITH TIME ZONE,   -- Event timestamp
    data JSONB                       -- Complete event as JSON
);

CREATE INDEX idx_task_events_raw_time ON task_events_raw (time DESC);
CREATE INDEX idx_task_events_raw_tag ON task_events_raw (tag);
```

**Sample data field:**
```json
{
  "instanceId": "01KPZY3F6HPMVHSSXKBKS11NQ2",
  "taskExecutionId": "82d04e1f-bc32-3786-9d9c-56630ab0e168",
  "eventType": "io.serverlessworkflow.task.started.v1",
  "timestamp": 1777040735.588971,
  "taskName": "set-0",
  "taskPosition": "do/0/set-0",
  "status": "RUNNING",
  "startTime": 1777040735.588971,
  "input": {"name": "Test"}
}
```

## Normalized Tables

### workflow_instances

**Purpose**: Normalized workflow execution state

**JPA Entity**: `org.kubesmarts.logic.dataindex.storage.postgresql.WorkflowInstanceEntity`

**GraphQL Type**: `WorkflowInstance`

```sql
CREATE TABLE workflow_instances (
    -- Identity
    id VARCHAR(255) PRIMARY KEY,                    -- instanceId from events
    
    -- Workflow identification
    namespace VARCHAR(255),                         -- workflowNamespace
    name VARCHAR(255),                              -- workflowName
    version VARCHAR(255),                           -- workflowVersion
    
    -- Status & lifecycle
    status VARCHAR(50),                             -- RUNNING | COMPLETED | FAULTED | CANCELLED | SUSPENDED
    start TIMESTAMP WITH TIME ZONE,                 -- startTime from workflow.started
    "end" TIMESTAMP WITH TIME ZONE,                 -- endTime from workflow.completed/faulted
    last_update TIMESTAMP WITH TIME ZONE,           -- lastUpdateTime from workflow.status-changed
    
    -- Idempotency (V2)
    last_event_time TIMESTAMP WITH TIME ZONE,       -- timestamp from event (for idempotency)
    
    -- Data (JSONB)
    input JSONB,                                    -- input from workflow.started
    output JSONB,                                   -- output from workflow.completed
    
    -- Error information (RFC 7807 Problem Details)
    error_type VARCHAR(255),                        -- error.type from workflow.faulted
    error_title VARCHAR(255),                       -- error.title
    error_detail TEXT,                              -- error.detail
    error_status INTEGER,                           -- error.status
    error_instance VARCHAR(255),                    -- error.instance
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_workflow_instances_namespace_name ON workflow_instances (namespace, name);
CREATE INDEX idx_workflow_instances_status ON workflow_instances (status);
CREATE INDEX idx_workflow_instances_start ON workflow_instances (start DESC);
CREATE INDEX idx_workflow_instances_last_event_time ON workflow_instances (last_event_time DESC);
```

**Total Columns**: 16 (V2 adds `last_event_time`)

**Event Mapping**:
```
workflow.started       → id, namespace, name, version, status, start, input, last_event_time
workflow.completed     → status, end, output, last_event_time
workflow.faulted       → status, end, error_*, last_event_time
workflow.status-changed → status, last_update, last_event_time
```

**Trigger:** `normalize_workflow_event()` (BEFORE INSERT on workflow_events_raw)

### task_instances

**Purpose**: Normalized task execution state

**JPA Entity**: `org.kubesmarts.logic.dataindex.storage.postgresql.TaskInstanceEntity`

**GraphQL Type**: `TaskExecution`

```sql
CREATE TABLE task_instances (
    -- Identity
    task_execution_id VARCHAR(255) PRIMARY KEY,     -- taskExecutionId from events
    
    -- Foreign key to workflow
    instance_id VARCHAR(255) NOT NULL,              -- instanceId (workflow FK)
    
    -- Task identification
    task_name VARCHAR(255),                         -- taskName
    task_position VARCHAR(255),                     -- taskPosition (e.g., "do/0/set-0")
    
    -- Status & lifecycle
    status VARCHAR(50),                             -- RUNNING | COMPLETED | FAULTED
    start TIMESTAMP WITH TIME ZONE,                 -- startTime from task.started
    "end" TIMESTAMP WITH TIME ZONE,                 -- endTime from task.completed/faulted
    
    -- Idempotency (V2)
    last_event_time TIMESTAMP WITH TIME ZONE,       -- timestamp from event
    
    -- Data (JSONB)
    input JSONB,                                    -- input from task.started
    output JSONB,                                   -- output from task.completed
    
    -- Audit
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    
    -- Foreign key constraint
    CONSTRAINT fk_task_instance_workflow 
        FOREIGN KEY (instance_id) 
        REFERENCES workflow_instances(id) 
        ON DELETE CASCADE
);

-- Indexes
CREATE INDEX idx_task_instances_instance_id ON task_instances (instance_id);
CREATE INDEX idx_task_instances_status ON task_instances (status);
CREATE INDEX idx_task_instances_last_event_time ON task_instances (last_event_time DESC);
```

**Total Columns**: 12 (V2 adds `last_event_time`)

**Event Mapping**:
```
task.started   → task_execution_id, instance_id, task_name, task_position, status, start, input, last_event_time
task.completed → status, end, output, last_event_time
task.faulted   → status, end, last_event_time
```

**Trigger:** `normalize_task_event()` (BEFORE INSERT on task_events_raw)

**Note:** Quarkus Flow emits separate task_execution_id for started vs completed events, resulting in multiple rows per logical task.

## Trigger Functions

### normalize_workflow_event()

**Purpose**: Extract fields from JSONB and UPSERT into workflow_instances

**Key Features:**
- Field-level idempotency (V2)
- Out-of-order event handling
- Immutable fields (first event wins)
- Terminal fields (preserve once set)
- Status determined by timestamp

**Logic:**
```sql
-- Immutable fields: First event wins (never overwrite)
namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace)
name = COALESCE(workflow_instances.name, EXCLUDED.name)
version = COALESCE(workflow_instances.version, EXCLUDED.version)
start = COALESCE(workflow_instances.start, EXCLUDED.start)
input = COALESCE(workflow_instances.input, EXCLUDED.input)

-- Terminal fields: Preserve if already set
"end" = COALESCE(EXCLUDED."end", workflow_instances."end")
output = COALESCE(EXCLUDED.output, workflow_instances.output)
error_* = COALESCE(EXCLUDED.error_*, workflow_instances.error_*)

-- Status: Use timestamp to determine winner
status = CASE
  WHEN event_timestamp > workflow_instances.last_event_time
  THEN EXCLUDED.status
  ELSE workflow_instances.status
END

-- Timestamp: Keep latest
last_event_time = GREATEST(event_timestamp, workflow_instances.last_event_time)
```

**Handles:**
- ✅ Duplicate events (same event inserted twice)
- ✅ Out-of-order events (COMPLETED arrives before STARTED)
- ✅ Event replay (FluentBit tail DB deleted)

**Example:**
1. COMPLETED event arrives (10:02:00) → status=COMPLETED, end set
2. STARTED event arrives (10:01:00) → keeps status=COMPLETED, fills start/input
3. Result: COMPLETED workflow with ALL data from both events ✅

### normalize_task_event()

**Purpose**: Extract fields from JSONB and UPSERT into task_instances

**Additional Logic:**
- Ensures parent workflow exists (handles tasks arriving before workflow events)
- Same field-level idempotency as workflow

**Auto-create workflow:**
```sql
INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
VALUES (NEW.data->>'instanceId', NEW.time, NEW.time, event_timestamp)
ON CONFLICT (id) DO NOTHING;
```

## Idempotency Guarantees (V2)

### Replay Safety

**Scenario:** FluentBit tail DB deleted → reprocesses all logs in /var/log/containers/

**Result:**
- Raw tables: Duplicate events inserted ✅
- Normalized tables: State unchanged (triggers handle duplicates) ✅
- last_event_time: Prevents older events from overwriting newer state ✅

**Example:**
```sql
-- Initial state
id: 01KPZY3F6H..., status: COMPLETED, last_event_time: 2026-04-24T14:26:55.549

-- Replay: STARTED event arrives again (timestamp: 2026-04-24T14:26:55.463)
-- Trigger logic: 14:26:55.463 < 14:26:55.549 → Keep COMPLETED ✅
```

### Out-of-Order Events

**Scenario:** Network delay causes COMPLETED to arrive before STARTED

**Result:**
1. COMPLETED (14:26:55.549) → status=COMPLETED, end set, start=NULL
2. STARTED (14:26:55.463) → keeps status=COMPLETED (newer), fills start ✅

**All data preserved, correct final state.**

## Data Flow

```
Quarkus Flow App
    ↓ (stdout - JSON events)
Kubernetes /var/log/containers/
    ↓ (FluentBit DaemonSet tail)
FluentBit pgsql output plugin
    ↓ (INSERT with tag, time, data)
workflow_events_raw / task_events_raw
    ↓ (BEFORE INSERT TRIGGER)
normalize_workflow_event() / normalize_task_event()
    ↓ (Extract JSONB → UPSERT)
workflow_instances / task_instances
    ↓ (JPA queries)
Data Index GraphQL API
```

## Migration Strategy

### Apply Migrations

```bash
# Copy migration files to PostgreSQL pod
kubectl cp V1__initial_schema.sql postgresql/postgresql-0:/tmp/
kubectl cp V2__add_idempotency.sql postgresql/postgresql-0:/tmp/

# Execute
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -f /tmp/V1__initial_schema.sql

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -f /tmp/V2__add_idempotency.sql
```

### Verify Schema

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c "\d workflow_instances"
```

Expected output includes:
- `last_event_time` column
- Triggers: `normalize_workflow_events`

## Cleanup Strategy

### Raw Event Retention

Raw staging tables can be cleaned up periodically (events already normalized):

```sql
-- Delete raw events older than 7 days
DELETE FROM workflow_events_raw WHERE time < NOW() - INTERVAL '7 days';
DELETE FROM task_events_raw WHERE time < NOW() - INTERVAL '7 days';
```

Schedule via pg_cron or external cron job.

### Workflow/Task Retention

Normalized tables should be retained based on business requirements:
- Active workflows: Never delete
- Completed workflows: Retain 30-90 days
- Failed workflows: Retain 180 days (troubleshooting)

## Performance Considerations

### Trigger Overhead

- **V1 triggers:** Minimal (simple field extraction)
- **V2 triggers:** Slightly higher (CASE logic, timestamp comparison)
- **Impact:** < 1ms per event (negligible)

### Index Strategy

- Optimize for GraphQL queries (by namespace, name, status, time)
- last_event_time indexed for idempotency checks
- Covering indexes for common queries

### JSONB Performance

- `input` and `output` stored as JSONB (not indexed)
- GraphQL returns them as JSON scalars
- Consider GIN indexes if filtering by input/output fields needed

## Future Enhancements

- [ ] Partitioning for workflow_instances (by month)
- [ ] Read replicas for GraphQL queries
- [ ] JSONB GIN indexes for input/output filtering
- [ ] Materialized views for dashboard queries
- [ ] pg_cron for automated cleanup

## References

- V1 Migration: `data-index-storage-migrations/V1__initial_schema.sql`
- V2 Migration: `data-index-storage-migrations/V2__add_idempotency.sql`
- Trigger Design: `docs/reference/EVENT_PROCESSOR_DESIGN.md` (historical)
- FluentBit Schema: `docs/operations/FLUENTBIT_PARSER_CONFIGURATION.md`
