# Data Index Storage Migrations

**Single source of truth for all Data Index database schemas.**

## Purpose

Flyway migration scripts for the Data Index PostgreSQL storage backend:

- **Normalized Tables**: `workflow_instances`, `task_instances` (direct insert target for the MODE 1 Vector `postgres` sink)
- **Real-time processing**: `BEFORE INSERT` triggers on these same two tables normalize events as they arrive

## Architecture

```
Quarkus Flow (quarkus-flow 0.9.0+)
    ↓ (structured JSON events with epoch timestamps → stdout)
Vector DaemonSet (postgres sink)
    ├─→ workflow_instances (raw_event JSONB column only)
    └─→ task_instances (raw_event JSONB column only)
    ↓ (BEFORE INSERT triggers, self-targeting)
PostgreSQL Triggers
    ├─→ Extract fields from raw_event JSONB
    ├─→ Handle out-of-order events (COALESCE/GREATEST)
    ├─→ UPDATE existing row + RETURN NULL, or fill in NEW + RETURN NEW
    ↓ (GraphQL queries)
Data Index GraphQL API
```

## Migration Files

### V1__initial_schema.sql

Original schema for Data Index v1.0.0: creates `workflow_instances` and `task_instances`
(without `raw_event`), plus the now-removed raw staging tables and their triggers.

### V2__direct_normalized_inserts.sql

- Adds `raw_event JSONB` to `workflow_instances` and `task_instances`.
- Drops `workflow_events_raw`, `task_events_raw`, and their triggers/functions.
- Adds `normalize_workflow_instance()` / `normalize_task_instance()`, `BEFORE INSERT` on
  `workflow_instances` / `task_instances` respectively — self-targeting `INSERT ... ON CONFLICT
  DO UPDATE`, guarded by `pg_trigger_depth()` to avoid recursion.

## Field Mappings (raw_event JSONB → Normalized Tables)

### Workflow Events (raw_event JSONB → workflow_instances)

`normalize_workflow_instance()` extracts:

| JSONB Path (raw_event->>) | workflow_instances Column | Type | Conversion |
|----------------------------|----------------------------|------|------------|
| `instanceId` | `id` | VARCHAR(255) | Direct |
| `workflowNamespace` | `namespace` | VARCHAR(255) | Direct |
| `workflowName` | `name` | VARCHAR(255) | Direct |
| `workflowVersion` | `version` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `started_at` | TIMESTAMPTZ | `to_timestamp(::numeric)` |
| `endTime` | `ended_at` | TIMESTAMPTZ | `to_timestamp(::numeric)` |
| `lastUpdateTime` | `last_update` | TIMESTAMPTZ | `to_timestamp(::numeric)` |
| `timestamp` | `last_event_time` | TIMESTAMPTZ | `to_timestamp(::numeric)` (drives idempotency) |
| `input` | `input` | JSONB | Direct (`->`) |
| `output` | `output` | JSONB | Direct (`->`) |
| `error->>'type'` | `error_type` | VARCHAR(255) | Nested |
| `error->>'title'` | `error_title` | VARCHAR(255) | Nested |
| `error->>'detail'` | `error_detail` | TEXT | Nested |
| `error->>'status'` | `error_status` | INTEGER | Nested + cast |
| `error->>'instance'` | `error_instance` | VARCHAR(255) | Nested |

**Auto-populated:** `created_at`/`updated_at` (`now()`, set by the trigger).

### Task Events (raw_event JSONB → task_instances)

`normalize_task_instance()` extracts:

| JSONB Path (raw_event->>) | task_instances Column | Type | Conversion |
|----------------------------|------------------------|------|------------|
| `instanceId` | `instance_id` | VARCHAR(255) | Direct (PK part 1, FK to workflow_instances) |
| `taskPosition` | `task` | VARCHAR(255) | Direct (PK part 2, JSON Pointer e.g. `/do/1/initialize`) |
| `taskName` | `task_name` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `started_at` | TIMESTAMPTZ | `to_timestamp(::numeric)` |
| `endTime` | `ended_at` | TIMESTAMPTZ | `to_timestamp(::numeric)` |
| `timestamp` | `last_event_time` | TIMESTAMPTZ | `to_timestamp(::numeric)` (drives idempotency) |
| `input` | `input` | JSONB | Direct (`->`) |
| `output` | `output` | JSONB | Direct (`->`) |
| `error->>'type'` | `error_type` | VARCHAR(255) | Nested |
| `error->>'title'` | `error_title` | VARCHAR(255) | Nested |
| `error->>'detail'` | `error_detail` | TEXT | Nested |
| `error->>'status'` | `error_status` | INTEGER | Nested + cast |
| `error->>'instance'` | `error_instance` | VARCHAR(255) | Nested |

**Auto-populated:** `created_at`/`updated_at` (`now()`, set by the trigger).

**Note:** no `task_execution_id` column — `TaskExecution.id` is derived as `instanceId + ":" + task`.

**Out-of-Order Handling:** the task trigger backfills a placeholder `workflow_instances` row
(`raw_event` left `NULL`) if the parent doesn't exist yet, before processing the task event.

## Usage

### Local Development

Apply migrations manually:

```bash
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex -f /path/to/V1__initial_schema.sql
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex -f /path/to/V2__direct_normalized_inserts.sql
```

### Kubernetes Operator

The Data Index operator uses Flyway to manage migrations automatically for PostgreSQL storage.

**Upgrade safety:**
- `V2__` is additive on top of an already-provisioned `V1__` schema, including existing rows.
- Foreign keys use `ON DELETE CASCADE`.
- Triggers are idempotent regardless of how many times the same event is (re)inserted.

## Trigger-Based Normalization

No Event Processor needed — PostgreSQL triggers handle normalization directly.

### How It Works

1. Vector's `postgres` sink inserts into `workflow_instances`/`task_instances` with only `raw_event` set.
2. The `BEFORE INSERT` trigger fires on that same table (`pg_trigger_depth()` guard: a nested call from step 4 passes through unchanged).
3. Extracts fields from `raw_event` (`NULL` `raw_event` also passes through unchanged — placeholders, JPA-seeded test rows).
4. Runs its own `INSERT ... ON CONFLICT (id) DO UPDATE` against the same table (COALESCE/GREATEST rules).
5. `RETURN NULL`s to cancel the original pending insert — step 4 already applied the event.

`ON CONFLICT` (not a manual `UPDATE`-then-`INSERT`) is required for atomicity: Vector's
`postgres_workflow`/`postgres_task` sinks write concurrently, and a check-then-act pattern is race-prone.

### Advantages

- **Real-time**: no polling or batch delays
- **Out-of-order handling**: atomic `ON CONFLICT DO UPDATE` with COALESCE/GREATEST, safe under concurrency
- **Idempotent**: same event can be reinserted safely
- **Simpler**: two tables instead of four
- **Debugging**: most recent raw event kept per row via `raw_event`

### Example

```sql
-- Vector inserts:
INSERT INTO workflow_instances (raw_event)
VALUES ('{"instanceId":"01KPY...","workflowName":"simple-set","status":"RUNNING",...}'::jsonb);

-- Trigger extracts fields and upserts workflow_instances in place, then cancels this insert.
```

## Notes

- `raw_event` holds only the most recent event per row (not full history).
- Timestamps: epoch seconds from Quarkus Flow, converted via `to_timestamp()`.
- Normalized columns are indexed for GraphQL queries.
- Deleting a workflow instance cascades to its tasks.