# Data Index Storage Migrations

**Single source of truth for all Data Index database schemas.**

## Purpose

This module contains Flyway migration scripts for the Data Index PostgreSQL storage backend:

- **Staging tables**: `workflow_events_raw`, `task_events_raw` (typed rows from the Vector `postgres` sink)
- **Dead-letter tables**: `workflow_events_dlq`, `task_events_dlq` (rows that failed normalization)
- **Normalized tables**: `workflow_instances`, `task_instances` (queried by GraphQL)

## Architecture

```
Quarkus Flow
    ↓ (structured JSON events with epoch timestamps → stdout)
Vector DaemonSet (VRL extracts typed fields, postgres sink)
    ├─→ workflow_events_raw
    └─→ task_events_raw
    ↓ (data-index-service polls, default every 1s, up to 1000 rows)
WorkflowPersistence / TaskPersistence (idempotent JDBC UPSERT, shared with MODE 3)
    ├─→ workflow_instances
    ├─→ task_instances
    └─→ failed rows: workflow_events_dlq / task_events_dlq
    ↓ (GraphQL queries)
Data Index GraphQL API
```

## Migration Files

### V1__initial_schema.sql

Creates the tables above and their indexes.

The only triggers left are `trg_set_workflow_events_raw_id` and `trg_set_task_events_raw_id`. They fill `id` on staging rows, because the Vector sink inserts explicit `NULL`s for missing columns and bypasses the `BIGSERIAL` default. They don't normalize anything.

## Staging Tables

Staging tables are an append-only inbox. A row is deleted as soon as it is normalized, so the tables stay near-empty and need no retention policy. A growing row count means normalization is falling behind or disabled.

Vector must set `inserted_at` itself, for the same reason it can't rely on the `id` default.

| Vector event field | `workflow_events_raw` column | Conversion |
|---|---|---|
| `instanceId` | `instance_id` | Direct |
| `workflowNamespace` | `namespace` | Direct |
| `workflowName` | `name` | Direct |
| `workflowVersion` | `version` | Direct |
| `status` | `status` | Direct |
| `startTime`, `endTime`, `timestamp` | `started_at`, `ended_at`, `event_timestamp` | Epoch seconds to timestamp |
| `input`, `output` | `input`, `output` | JSON-encoded to `TEXT` |
| `error.*` | `error_type`, `error_title`, `error_detail`, `error_status`, `error_instance` | Direct |

`task_events_raw` follows the same pattern, with `taskName` → `task_name` and `taskPosition` → `task`.

## Normalization

Idempotency lives in the UPSERT SQL in `data-index-ingestion-persistence`, not in this schema:

- **First value wins**: `namespace`, `name`, `version`, `started_at`, `input`
- **Newest event wins** (by `last_event_time`): `status`, `ended_at`, `output`, `error_*`
- **Task identity**: composite key `(instance_id, task)`
- **Task before workflow**: a placeholder workflow row is created and later filled in

If a batch fails, its rows are retried one at a time. A row that still fails moves to its DLQ table with a `failure_reason`, in the same transaction that deletes the staging row.

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

## Notes

- **Idempotent DDL**: all `CREATE` statements use `IF NOT EXISTS`
- **Polling config**: `data-index.ingestion.jdbc-batch.*` in `data-index-service-postgresql`
