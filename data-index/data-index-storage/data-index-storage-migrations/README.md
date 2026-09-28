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
Vector DaemonSet
    ↓ (VRL extracts + types each field)
    ├─→ workflow_instances (typed columns, from_event = true)
    └─→ task_instances (typed columns, from_event = true)
    ↓ (BEFORE INSERT triggers, self-targeting)
PostgreSQL Triggers
    ├─→ Handle out-of-order events (COALESCE/GREATEST)
    ├─→ UPDATE existing row + RETURN NULL, or fill in NEW + RETURN NEW
    ↓ (GraphQL queries)
Data Index GraphQL API
```

## Migration Files

### V1__initial_schema.sql

Creates `workflow_instances` and `task_instances`, plus `normalize_workflow_instance()` /
`normalize_task_instance()` — `BEFORE INSERT` triggers on those same tables, self-targeting
`INSERT ... ON CONFLICT DO UPDATE`, guarded by `pg_trigger_depth()` to avoid recursion.

## Row Shape (Vector VRL → Normalized Tables)

Vector's VRL transforms (`build_workflow_row`/`build_task_row` in
`data-index/collectors/vector/mode1-postgresql/vector.yaml`) extract and type each field
from the Quarkus Flow event before sending it — Vector's `postgres` sink maps JSON keys to
columns via `jsonb_populate_recordset`, so the row Vector sends already has one key per
column. The trigger reads `NEW.<column>` directly; it does not parse JSON.

### Workflow Events → workflow_instances

| Event field | workflow_instances Column | Type | Conversion (in VRL) |
|-------------|----------------------------|------|----------------------|
| `instanceId` | `id` | VARCHAR(255) | Direct |
| `workflowNamespace` | `namespace` | VARCHAR(255) | Direct |
| `workflowName` | `name` | VARCHAR(255) | Direct |
| `workflowVersion` | `version` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `started_at` | TIMESTAMPTZ | `from_unix_timestamp()` |
| `endTime` | `ended_at` | TIMESTAMPTZ | `from_unix_timestamp()` |
| `lastUpdateTime` | `last_update` | TIMESTAMPTZ | `from_unix_timestamp()` |
| `timestamp` | `last_event_time` | TIMESTAMPTZ | `from_unix_timestamp()` (drives idempotency) |
| `input` | `input` | JSONB | Direct |
| `output` | `output` | JSONB | Direct |
| `error.type` | `error_type` | VARCHAR(255) | Flattened |
| `error.title` | `error_title` | VARCHAR(255) | Flattened |
| `error.detail` | `error_detail` | TEXT | Flattened |
| `error.status` | `error_status` | INTEGER | Flattened |
| `error.instance` | `error_instance` | VARCHAR(255) | Flattened |
| *(set by VRL)* | `from_event` | BOOLEAN | Always `true` for Vector rows |

**Auto-populated:** `created_at`/`updated_at` (`now()`, set by the trigger).

### Task Events → task_instances

| Event field | task_instances Column | Type | Conversion (in VRL) |
|-------------|-------------------------|------|-----------------------|
| `instanceId` | `instance_id` | VARCHAR(255) | Direct (PK part 1, FK to workflow_instances) |
| `taskPosition` | `task` | VARCHAR(255) | Direct (PK part 2, JSON Pointer e.g. `/do/1/initialize`) |
| `taskName` | `task_name` | VARCHAR(255) | Direct |
| `status` | `status` | VARCHAR(50) | Direct |
| `startTime` | `started_at` | TIMESTAMPTZ | `from_unix_timestamp()` |
| `endTime` | `ended_at` | TIMESTAMPTZ | `from_unix_timestamp()` |
| `timestamp` | `last_event_time` | TIMESTAMPTZ | `from_unix_timestamp()` (drives idempotency) |
| `input` | `input` | JSONB | Direct |
| `output` | `output` | JSONB | Direct |
| `error.type` | `error_type` | VARCHAR(255) | Flattened |
| `error.title` | `error_title` | VARCHAR(255) | Flattened |
| `error.detail` | `error_detail` | TEXT | Flattened |
| `error.status` | `error_status` | INTEGER | Flattened |
| `error.instance` | `error_instance` | VARCHAR(255) | Flattened |
| *(set by VRL)* | `from_event` | BOOLEAN | Always `true` for Vector rows |

**Auto-populated:** `created_at`/`updated_at` (`now()`, set by the trigger).

**Note:** no `task_execution_id` column — `TaskExecution.id` is derived as `instanceId + ":" + task`.

**Out-of-Order Handling:** the task trigger backfills a placeholder `workflow_instances` row
(`from_event` left at its default `false`) if the parent doesn't exist yet, before processing
the task event.

## Usage

### Local Development

Apply the migration manually:

```bash
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex -f /path/to/V1__initial_schema.sql
```

### Kubernetes Operator

The Data Index operator uses Flyway to manage migrations automatically for PostgreSQL storage.

**Idempotency:** Triggers are idempotent regardless of how many times the same event is
(re)inserted, and foreign keys use `ON DELETE CASCADE`.

## Trigger-Based Normalization

No Event Processor needed — PostgreSQL triggers handle normalization directly.

### How It Works

1. Vector's VRL builds a row with typed columns (`from_event = true`) and the `postgres` sink inserts it into `workflow_instances`/`task_instances`.
2. The `BEFORE INSERT` trigger fires on that same table (`pg_trigger_depth()` guard: a nested call from step 4 passes through unchanged).
3. If `from_event` is `false` (a direct writer like a JPA test fixture, or the task trigger's placeholder backfill), the trigger passes the row through unchanged — no merge.
4. Otherwise it runs its own `INSERT ... ON CONFLICT (id) DO UPDATE` against the same table (COALESCE/GREATEST rules).
5. `RETURN NULL`s to cancel the original pending insert — step 4 already applied the event.

`ON CONFLICT` (not a manual `UPDATE`-then-`INSERT`) is required for atomicity: Vector's
`postgres_workflow`/`postgres_task` sinks write concurrently, and a check-then-act pattern is race-prone.

`from_event` exists because `RETURN NULL` makes Postgres report 0 rows affected for the
*original* statement even when the nested insert in step 4 succeeds — Hibernate's
optimistic-lock row-count check would otherwise fail on every `em.persist()`, not just on
actual conflicts. JPA entities never need to set it; it defaults to `false`.

### Advantages

- **Real-time**: no polling or batch delays
- **Out-of-order handling**: atomic `ON CONFLICT DO UPDATE` with COALESCE/GREATEST, safe under concurrency
- **Idempotent**: same event can be reinserted safely
- **Simpler**: two tables, one migration, no raw JSON parsing in PL/pgSQL

### Example

```sql
-- Vector inserts (typed columns, from_event always true):
INSERT INTO workflow_instances (id, name, status, from_event, ...)
VALUES ('01KPY...', 'simple-set', 'RUNNING', true, ...);

-- Trigger merges into the existing row (or inserts fresh) and cancels this insert.
```

## Notes

- Timestamps: epoch seconds from Quarkus Flow, converted to `TIMESTAMPTZ` by Vector's VRL (`from_unix_timestamp()`).
- Normalized columns are indexed for GraphQL queries.
- Deleting a workflow instance cascades to its tasks.
