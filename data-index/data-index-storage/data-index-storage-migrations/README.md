# Data Index Storage Migrations

**Single source of truth for all Data Index database schemas.**

## Purpose

This module contains Flyway migration scripts for Data Index MODE 1 (PostgreSQL Trigger-based Normalization) architecture:

- **Raw Staging Tables**: `workflow_events_raw`, `task_events_raw` (FluentBit writes here - fixed schema: tag, time, data)
- **Normalized Tables**: `workflow_instances`, `task_instances` (PostgreSQL triggers normalize here)
- **No Event Processor needed**: Real-time normalization via PostgreSQL BEFORE INSERT triggers

## Architecture

```
Quarkus Flow (quarkus-flow 0.9.0+)
    ↓ (structured JSON events with epoch timestamps)
    ↓ (writes to /tmp/quarkus-flow-events.log)
FluentBit DaemonSet
    ├─→ workflow_events_raw (tag TEXT, time TIMESTAMP, data JSONB)
    └─→ task_events_raw (tag TEXT, time TIMESTAMP, data JSONB)
    ↓ (BEFORE INSERT triggers)
PostgreSQL Triggers
    ├─→ Extract fields from data JSONB
    ├─→ Handle out-of-order events
    ├─→ Upsert workflow_instances
    └─→ Upsert task_instances
    ↓ (GraphQL queries)
Data Index GraphQL API
```

## Migration Files

### V1__initial_schema.sql

Initial schema for Data Index v1.0.0 with trigger-based normalization:

**Raw Staging Tables** (FluentBit pgsql plugin fixed schema):
- `workflow_events_raw`: Stores tag (TEXT), time (TIMESTAMP), data (JSONB)
- `task_events_raw`: Stores tag (TEXT), time (TIMESTAMP), data (JSONB)

**Normalized Tables** (Populated via triggers):
- `workflow_instances`: Extracted workflow state with individual columns
- `task_instances`: Extracted task state (with FK to workflow_instances)

**Trigger Functions**:
- `normalize_workflow_event()`: Extracts fields from JSONB and UPSERTs into workflow_instances
- `normalize_task_event()`: Extracts fields from JSONB and UPSERTs into task_instances

**Advantages**:
- No Event Processor needed - triggers handle normalization in real-time
- Automatic out-of-order event handling via UPSERT with COALESCE
- Raw events preserved in staging tables for debugging/replay
- Simpler architecture - fewer moving parts

## FluentBit Configuration

FluentBit pgsql output plugin uses a **fixed schema**:
- `tag TEXT` - The FluentBit tag (workflow.instance.started, etc.)
- `time TIMESTAMP WITH TIME ZONE` - Event timestamp  
- `data JSONB` - Complete event as JSON

This is why we use **raw staging tables** that match this structure, then use **triggers** to normalize.

## Field Mappings (JSONB → Normalized Tables)

### Workflow Events (data JSONB → workflow_instances)

Trigger function extracts:

| JSONB Path (data->>) | workflow_instances Column | Type | Conversion |
|----------------------|---------------------------|------|------------|
| `instanceId` | `id` | VARCHAR(255) | Direct |
| `workflowNamespace` | `namespace` | VARCHAR(255) | Direct |
| `workflowName` | `name` | VARCHAR(255) | Direct |
| `workflowVersion` | `version` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `start` | TIMESTAMP | `to_timestamp(::numeric)` |
| `endTime` | `end` | TIMESTAMP | `to_timestamp(::numeric)` |
| `lastUpdateTime` | `last_update` | TIMESTAMP | `to_timestamp(::numeric)` |
| `input` | `input` | JSONB | Direct (->)  |
| `output` | `output` | JSONB | Direct (->) |
| `error->>'type'` | `error_type` | VARCHAR(255) | Nested |
| `error->>'title'` | `error_title` | VARCHAR(255) | Nested |
| `error->>'detail'` | `error_detail` | TEXT | Nested |
| `error->>'status'` | `error_status` | INTEGER | Nested + cast |
| `error->>'instance'` | `error_instance` | VARCHAR(255) | Nested |

**Auto-populated:**
- `created_at` (from trigger: NEW.time)
- `updated_at` (from trigger: NEW.time)

### Task Events (data JSONB → task_instances)

Trigger function extracts:

| JSONB Path (data->>) | task_instances Column | Type | Conversion |
|----------------------|-----------------------|------|------------|
| `instanceId` | `instance_id` | VARCHAR(255) | Direct |
| `taskExecutionId` | `task_execution_id` | VARCHAR(255) | Direct |
| `taskName` | `task_name` | VARCHAR(255) | Direct |
| `taskPosition` | `task_position` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `start` | TIMESTAMP | `to_timestamp(::numeric)` |
| `endTime` | `end` | TIMESTAMP | `to_timestamp(::numeric)` |
| `input` | `input` | JSONB | Direct (->) |
| `output` | `output` | JSONB | Direct (->) |

**Auto-populated:**
- `created_at` (from trigger: NEW.time)
- `updated_at` (from trigger: NEW.time)

**Note:** Task payloads are included when `quarkus.flow.structured-logging.include-task-payloads=true`

**Out-of-Order Handling:** The task trigger first ensures the workflow instance exists (creates placeholder if needed) before inserting the task.

## Usage

### Local Development

Apply migrations manually:

```bash
# Connect to PostgreSQL
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex

# Apply migration
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex -f /path/to/V1__initial_schema.sql
```

### Kubernetes Operator

The Data Index operator will use Flyway to manage migrations automatically when users choose PostgreSQL storage.

**Operator behavior:**
1. Detects PostgreSQL storage backend
2. Runs Flyway migrations on startup
3. Compares schema version with migration files
4. Applies pending migrations

**Upgrade safety:**
- Migrations are idempotent (`CREATE TABLE IF NOT EXISTS`)
- Foreign keys use `ON DELETE CASCADE` for data integrity
- Indexes created with `IF NOT EXISTS` to prevent errors

## Trigger-Based Normalization

PostgreSQL triggers handle all normalization automatically - **no Event Processor needed!**

### How It Works

1. **FluentBit INSERT** → `workflow_events_raw` or `task_events_raw`
2. **BEFORE INSERT trigger fires** → Extracts fields from JSONB `data` column
3. **UPSERT normalized table** → `workflow_instances` or `task_instances`
4. **Return NEW** → Raw event is also stored in staging table

### Advantages

- **Real-time normalization**: No polling or batch delays
- **Out-of-order handling**: UPSERT with COALESCE handles events arriving in any order
- **Idempotent**: Same event can be inserted multiple times safely
- **Simpler architecture**: Fewer services to deploy and monitor
- **Debugging**: Raw events preserved in staging tables

### Example Workflow Event Processing

```sql
-- FluentBit inserts (via pgsql output plugin):
INSERT INTO workflow_events_raw (tag, time, data) 
VALUES ('workflow.instance.started', '2026-04-23 22:04:49+00', '{"instanceId":"01KPY...","workflowName":"simple-set",...}');

-- Trigger automatically executes:
INSERT INTO workflow_instances (id, namespace, name, version, status, start, ...)
VALUES ('01KPY...', 'org.acme', 'simple-set', '0.0.1', 'RUNNING', '2026-04-23 22:04:49+00', ...)
ON CONFLICT (id) DO UPDATE SET 
  status = EXCLUDED.status,
  "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
  ...;
```

### Retention Policy

Raw staging tables can be cleaned up periodically:

```sql
-- Delete raw events older than 7 days
DELETE FROM workflow_events_raw WHERE time < NOW() - INTERVAL '7 days';
DELETE FROM task_events_raw WHERE time < NOW() - INTERVAL '7 days';
```

This can be scheduled via PostgreSQL `pg_cron` extension or external cron job.

## Notes

- **FluentBit pgsql plugin**: Uses fixed schema (tag TEXT, time TIMESTAMP, data JSONB) - cannot be customized
- **Timestamps**: Epoch seconds from Quarkus Flow 0.9.0+ converted via `to_timestamp()` in triggers
- **JSONB Storage**: Complete events stored as JSONB in `data` column, triggers extract to columns
- **Raw Events**: Preserved in `*_raw` tables for debugging and potential replay
- **JSONB Performance**: Normalized columns indexed for fast GraphQL queries
- **Cascade Deletes**: Deleting a workflow instance cascades to its tasks
- **Idempotent**: All CREATE statements use `IF NOT EXISTS`, triggers use UPSERT with COALESCE
- **Out-of-Order**: Triggers handle events arriving in any order via UPSERT logic
- **No Event Processor**: PostgreSQL triggers replace the need for a separate Event Processor service
