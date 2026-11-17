# ADR-0002: Replace Trigger-Based Normalization with JDBC Batch Normalization (MODE 1)

**Status:** Accepted
**Date:** 2026-11-03
**Deciders:** KubeSmarts Engineering Team
**Issues:** [#74](https://github.com/kubesmarts/logic-apps/issues/74), [#105](https://github.com/kubesmarts/logic-apps/issues/105)
**Implemented in:** [PR #113](https://github.com/kubesmarts/logic-apps/pull/113)

## Context

MODE 1 normalizes events via PostgreSQL triggers: `workflow_events_raw`/`task_events_raw` (JSONB) + `BEFORE INSERT` triggers that parse JSONB and UPSERT into `workflow_instances`/`task_instances` with field-level idempotency (COALESCE/GREATEST logic). The raw tables are append-only and never shrink.

Meanwhile MODE 3 (Kafka) already has an idempotent normalization path that is **not PL/pgSQL**: `KafkaLifecycleConsumer` → `WorkflowEventProcessor`/`TaskExecutionProcessor` → `WorkflowPersistence`/`TaskPersistence` (JDBC, `persistBatch()`, same field-level-idempotent `ON CONFLICT DO UPDATE` logic as the triggers, expressed in Java + plain SQL files). `WorkflowEventProcessor`/`TaskExecutionProcessor` take `List<WorkflowInstance>`/`List<TaskExecution>`; they don't know Kafka exists.

So the same idempotency logic exists twice (PL/pgSQL for MODE 1, Java/SQL for MODE 3), and any trigger-based design keeps it that way.

Triggers also have a concurrency problem. Vector's workflow and task sinks write concurrently, and both triggers update `workflow_instances`. Under load they deadlock, and Vector treats a deadlock as non-retriable and drops the whole batch (see Benchmark).

## Alternatives Considered

1. **Self-targeting trigger with typed columns** (Vector inserts typed rows directly into `workflow_instances`/`task_instances`; a trigger on those tables does the idempotent UPSERT, guarded by `pg_trigger_depth()`)
   - ✅ No raw tables, no raw-table growth
   - ❌ Idempotency logic still duplicated in PL/pgSQL
   - ❌ Same trigger-side deadlock exposure between the workflow and task sinks

2. **Status quo** (raw tables + triggers)
   - ❌ Unbounded raw-table growth
   - ❌ Same duplication and deadlock problems as #1

3. **JDBC batch normalization in a new standalone service**
   - ✅ One idempotency implementation shared with MODE 3
   - ✅ Same shape as `data-index-ingestion-kafka-service`
   - ❌ One more deployment to build, ship and operate per MODE 1 install

4. **JDBC batch normalization inside `data-index-service`** (SELECTED)
   - ✅ One idempotency implementation (Java/SQL) shared by MODE 1 and MODE 3
   - ✅ Staging table is processed and deleted immediately, bounded by design
   - ✅ Typed columns extracted in VRL, no JSONB parsing in the database
   - ✅ No new deployment: MODE 1 stays Vector + PostgreSQL + `data-index-service`
   - ❌ Query and ingestion share one process and connection pool
   - ❌ MODE 3 runs the same image, so polling needs an on/off switch

## Decision

**MODE 1 normalizes via typed staging tables polled by `data-index-service`, reusing MODE 3's persistence code, instead of PostgreSQL triggers.**

```
Quarkus Flow → stdout → Vector (typed VRL extraction)
                               ↓ (postgres sink)
                 workflow_events_raw / task_events_raw
                 (typed columns, append-only, no normalization triggers)
                               ↓ (WorkflowEventPoller / TaskEventPoller, @Scheduled)
                 SELECT ... ORDER BY inserted_at LIMIT :batchSize FOR UPDATE SKIP LOCKED
                               ↓ map row → WorkflowInstance / TaskExecution
                 WorkflowEventProcessor.processBatch() / TaskExecutionProcessor.processBatch()
                 [same classes MODE 3 uses]
                               ↓
                 workflow_instances / task_instances  (idempotent UPSERT, own commit)
                               ↓
                 DELETE FROM *_events_raw WHERE id = ANY(:ids read above)
```

No retention window: a staging row lives until the next poll normalizes it, then it is deleted. The staging table stays near-empty under steady state.

### Module layout

- **`data-index-ingestion-persistence`** (NEW, replaces `data-index-ingestion-kafka-processor`): `WorkflowPersistence`, `TaskPersistence`, `LoadSQL`, UPSERT SQL, `EventProcessor<T>`, `WorkflowEventProcessor`, `TaskExecutionProcessor`, `ProcessEventFailedException`. No Kafka types.
- **`data-index-ingestion-kafka-service`**: keeps the Kafka-specific code (`KafkaLifecycleConsumer`, `Mapper`, CloudEvent reconstruction) and depends on the persistence module.
- **`data-index-service-postgresql`**: gets the pollers (`WorkflowEventPoller`, `TaskEventPoller`), the row types and mappers, `SavepointHelper`, and the staging/DLQ SQL.

### Staging table shape

Same table names (`workflow_events_raw`/`task_events_raw`), restructured from `(tag, time, data JSONB)` to typed columns mirroring the domain model (`id BIGSERIAL PK`, `instance_id`, `status`, `started_at`, `ended_at`, `input`, `output`, error columns, `event_timestamp`, `inserted_at`). Append-only, no `ON CONFLICT`; idempotency happens downstream in `persistBatch()`.

`inserted_at` is set by VRL (`now()`), not by its column `DEFAULT`; see below for why.

### Staging row ID: `BEFORE INSERT` trigger

The goal of this ADR is to remove all triggers from the MODE 1 schema. A minimal ID-assignment trigger per staging table stays, by decision.

**Problem:** the poller selects and deletes staging rows by `id`, a `BIGSERIAL` (auto-increment) primary key. Vector's `postgres` sink inserts via `jsonb_populate_recordset(null::<table>, ...)`, which builds each row from the table's full row type. Any column missing from the VRL output, `id` included, arrives as an explicit `NULL`, so the sequence `DEFAULT` never applies and the insert fails the `NOT NULL` primary key. VRL cannot draw values from a PostgreSQL sequence, so it cannot supply the `id` itself.

**Decision:** keep one `BEFORE INSERT` trigger per staging table (`set_workflow_events_raw_id`, `set_task_events_raw_id`) that sets `NEW.id := nextval(...)` when `id` is `NULL`.

- It only assigns the key. It does not parse, normalize or touch any other table, so none of the trigger problems from the Context (duplicated idempotency logic, deadlocks between sinks) apply.
- `inserted_at` has the same problem but needs no trigger: VRL sets it with `now()`.
- Inserts from other clients that omit `id` (tests, manual inserts) keep working as with a normal `BIGSERIAL`.

**Consequence:** MODE 1 is not trigger-free. If Vector's sink ever honours column defaults, these two triggers can be dropped in a new migration.

### Transactions and correctness

The poller and the processor use **different connections**:

- **Connection A (poller):** one transaction per poll that holds the `FOR UPDATE SKIP LOCKED` row locks and does the staging `DELETE`s and DLQ moves.
- **Connection B (processor):** `persistBatch()` commits the UPSERT on its own.

Normalization commits before the staging delete, so delivery is **at-least-once**: if the delete is lost, the rows are normalized again on the next poll, which the idempotent UPSERT makes harmless.

Rules that follow from this:

- **Delete by the exact IDs read in that poll**, never by time cutoff, so a row Vector inserts concurrently is never deleted unprocessed. `SKIP LOCKED` lets several replicas poll without double work.
- **Every staging write on connection A runs inside a savepoint** (`SavepointHelper.run`). In PostgreSQL a failed statement aborts the whole transaction: later statements fail with `current transaction is aborted` and `COMMIT` becomes a silent `ROLLBACK`. Rolling back to the savepoint keeps the transaction usable, so one failed write never undoes the rest of the poll.
- **Processing failures and staging failures are handled separately.** Only a processing failure sends a row to the DLQ. A failed `DELETE` after successful normalization leaves the row staged for replay; it never goes to the DLQ.
- **Use as few savepoints as possible.** A savepoint that writes becomes a subtransaction, and PostgreSQL caches only 64 per transaction; beyond that, other sessions fall back to `pg_subtrans` lookups, which causes contention. Successful rows are therefore deleted with one `deleteBatch` per poll (one savepoint); only rows going to the DLQ get a savepoint each.

### Configuration

Three properties under `data-index.ingestion.jdbc-batch`:

| Property | Default | Notes |
|---|---|---|
| `enabled` | `true` | Helm sets `false` in `values-mode3.yaml`; MODE 3 shares the image and has no raw tables to poll |
| `poll-interval` | `1s` | Quarkus `@Scheduled(every = ...)`, `concurrentExecution = SKIP`. Values below 1s are ignored |
| `batch-size` | `1000` | Matches MODE 3's `db-batch-size`. Upper bound depends on heap (see Benchmark) |

### Task-before-workflow ordering

No change to `TaskPersistence`. Its FK-violation fallback (savepoint rollback → placeholder insert via `ON CONFLICT (id) DO NOTHING` → retry) resolves within the same `persist()` call; it is not a cross-poll retry and never blocks the queue. Its safety comes from the SQL, not from Kafka, so swapping the caller is safe. Covered by `JdbcBatchPollingTest` (task before its workflow, and a task racing a workflow batch for the same instance).

If the workflow never arrives (for example, its event lands in the DLQ), the placeholder stays a placeholder with `NULL` `namespace`/`status`. That is an accepted steady state.

### Failure handling

A failure that is not the self-healing FK case must not sit at the head of `ORDER BY inserted_at` blocking every row behind it. Dead-letter tables mirror MODE 3's `data-index-events-dlq` topic:

- `workflow_events_dlq` / `task_events_dlq`: raw columns + `failure_reason`.
- When `processBatch()` fails, the poller falls back to one `process()` call per row. Rows that succeed go to the final batch delete; rows that fail are moved to the DLQ (insert + delete in one savepoint, so the move is atomic).
- Each `process()` in the fallback is its own transaction on connection B. That is what isolates the bad row: a single transaction would let one failure roll back the good rows. It costs one commit (WAL flush) per row, only while a batch contains a bad row.

Remaining gap: if the DLQ write itself fails, the row stays staged and is retried every poll. With a deterministic failure (malformed JSON, invalid enum) that repeats forever. Thanks to the savepoints, it no longer blocks or rolls back the other rows. It is made observable rather than fixed (see Metrics).

### Metrics

Three Micrometer counters, tagged `type=workflow|task`, following MODE 2's `data_index.<mode>.<thing>` naming:

- `data_index.jdbc_batch.events.processed`: rows normalized and deleted from staging (batch path or fallback).
- `data_index.jdbc_batch.events.dlq`: rows moved to `*_events_dlq`.
- `data_index.jdbc_batch.dlq.write.failed`: DLQ writes that failed (the gap above). This is the counter to alert on.

## Benchmark

Single 30s run of `order100` at 150 rps (100 tasks per workflow, ~30k events/s) with [flow-bench](https://github.com/gmunozfe/flow-bench). Vector 0.54, PostgreSQL 15 (2 CPU), local Docker, 1 GiB service heap.

| Variant | Normalized/s | Lag p50 / p95 | Drained after load | PG CPU | Result |
|---|---|---|---|---|---|
| trigger | ~30,000 | 0.1s / 4.1s | no | 97% | **800 events lost, 25 workflows incomplete** |
| poll 1s / 1000 (default) | ~1,600 | 14.6s / 28.2s | no (859k pending) | 30% | delayed, no loss |
| poll 1s / 10000 | ~6,600 | 12.4s / 23.0s | yes, 82s | 40% | complete, empty DLQ |
| poll 1s / 40000 | ~20,400 | 8.8s / 12.1s | yes, 7.8s | 84% | complete, empty DLQ |
| poll 5s–30s / 200k–1.2M | ~0–550 | 15–23s / 27–37s | no | 26–73% | `OutOfMemoryError` every tick, stalled |

- **Trigger:** 8 deadlocks between the workflow and task sinks; Vector dropped the affected batches.
- **Poll:** never lost an event. One batch per tick caps throughput at roughly `batch-size` events/s per table.
- **Oversized batches:** the batch does not fit in heap, every tick fails with `OutOfMemoryError` before reaching the DLQ fallback, and ingestion stalls (no loss) until `batch-size` is lowered.

## Problems Found During Implementation

1. **Standalone service (Alternative 3) was built first**, then the pollers were moved into `data-index-service` and the service was removed. That required the `enabled` switch, because MODE 3 runs the same image.
2. **Helm default:** with polling disabled by default, a plain `helm install` (default `mode1`) would accumulate raw events and never normalize them. Fixed by defaulting to `enabled: true` and disabling it only in `values-mode3.yaml`.
3. **Vector `postgres` sink ignores column defaults** (`jsonb_populate_recordset`): `id` arrived as `NULL`. Led to the `BEFORE INSERT` id trigger (see Staging row ID) and to VRL setting `inserted_at`.
4. **Timestamp precision:** VRL first truncated timestamps to milliseconds. The shared UPSERT accepts equal timestamps (`>=`), so a replayed `RUNNING` within the same millisecond could overwrite `COMPLETED`. Fixed by converting with nanosecond precision, as the old triggers kept microseconds.
5. **`to_int(null)` returns `0` in VRL:** events without `error.status` got `error_status = 0`, and the mappers attached errors to successful workflows/tasks. Fixed by guarding the conversion.
6. **Aborted transaction after a failed staging write:** a failed DLQ insert or `DELETE` was caught and the loop continued, so later statements failed and the final `COMMIT` rolled back the poll's earlier deletes. Because normalization had already committed, the same oldest rows were replayed every poll and the queue stalled. Fixed with savepoints (see Transactions and correctness).
7. **Good rows sent to the DLQ:** `process()` and `deleteSingle()` shared one `try`, so a failed `DELETE` after successful normalization was reported as a processing failure. Fixed by separating the two steps.
8. **Subtransaction overflow:** the first savepoint fix used one savepoint per row in the fallback, up to ~1000 subtransactions per poll. Replaced by a single batch delete.
9. **`V1__initial_schema.sql` edited in place** instead of adding `V2`. Accepted because V1 has not been released; it would break Flyway checksum validation on an existing install.

## Non-Goals

Garbage-collecting old **normalized** `workflow_instances`/`task_instances` rows (completed workflows past a retention window) is #105's literal ask and is **not** solved here; this ADR only keeps the staging tables bounded. It stays open as separate work.

## Consequences

**Positive**
- Single idempotent UPSERT implementation shared by MODE 1 and MODE 3.
- No trigger deadlocks between Vector sinks; no events lost in the benchmark.
- Staging tables are self-bounding; no retention config or cleanup job.
- No new deployment for MODE 1.

**Negative**
- Normalization latency depends on `poll-interval` and `batch-size` instead of being immediate (p50 ~12s vs 0.1s in the benchmark under sustained load).
- The default `batch-size=1000` caps each poller at ~1000 events/s, too low for task-heavy workflows.
- Two ID-assignment triggers remain on the staging tables (see Staging row ID).
- `batch-size` has no guard against heap size; a misconfiguration stalls ingestion silently.
- Ingestion competes with GraphQL queries for the service's CPU, heap and connection pool (up to 2 connections per poller during a poll).

## Open Questions

1. **Default `batch-size`:** the benchmark suggests `10000` (~10k events/s per table, peak 239 MiB of 1 GiB heap). Draining in a loop per tick would remove the ceiling entirely.
2. **Heap guard for `batch-size`:** cap it relative to heap, or handle oversized batches, so a misconfiguration fails loudly.
3. **Fallback cost:** bisecting a failed batch instead of one transaction per row would cut commits from ~N to ~log N when few rows are bad. Deferred until `data_index.jdbc_batch.events.dlq` shows the fallback is common.
4. **Retry cutoff for DLQ write failures:** the row in the remaining gap is visible but still retried forever.
