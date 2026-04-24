# Event Processor Design (REMOVED)

**Status:** ⚠️ **NOT IMPLEMENTED** - This component was removed from the codebase  
**Removal Date:** 2026-04-24  
**Reason:** Replaced by database triggers (MODE 1) and Ingest Pipelines (MODE 2)

## Why Event Processor Was Removed

The original architecture included an Event Processor service to:
1. Poll raw event staging tables
2. Normalize events into workflow_instances/task_instances
3. Handle out-of-order events and duplicates
4. Mark events as processed

**Problem:** This added unnecessary complexity and operational overhead.

**Solution:** Use built-in database features that handle normalization automatically:
- **MODE 1 (PostgreSQL):** Triggers normalize events on INSERT
- **MODE 2 (Elasticsearch):** Ingest Pipelines normalize events on write

## Current Architecture (Trigger-Based)

### MODE 1: PostgreSQL Triggers

```
FluentBit → workflow_events_raw → BEFORE INSERT TRIGGER → workflow_instances
         → task_events_raw     → BEFORE INSERT TRIGGER → task_instances
```

**Benefits:**
- ✅ Real-time normalization (no polling delay)
- ✅ No separate service to deploy
- ✅ Idempotent (UPSERT with field-level logic)
- ✅ Out-of-order handling (timestamp comparison)
- ✅ Simpler architecture

**Implementation:**
- `V1__initial_schema.sql` - Creates tables and basic triggers
- `V2__add_idempotency.sql` - Adds field-level idempotency logic

See `deployment/MODE1_ARCHITECTURE_UPDATE.md` for details.

### MODE 2: Elasticsearch Ingest Pipelines (Planned)

```
FluentBit → Elasticsearch Ingest Pipeline → Normalized Index
```

**Benefits:**
- ✅ Real-time normalization (no polling)
- ✅ No Event Processor service
- ✅ Idempotent (Painless script with timestamp logic)
- ✅ Built-in Elasticsearch feature

See `deployment/MODE2_IMPLEMENTATION_PLAN.md` for design.

## Original Event Processor Design (Historical Reference)

The Event Processor was designed with the following components:

### 1. Batch Reader
Read unprocessed events from staging tables (`workflow_events`, `task_events`) where `processed = false`.

### 2. Event Sorter
Group events by `instance_id` and sort by timestamp to ensure correct processing order.

### 3. Deduplicator
Remove duplicate events based on:
- workflow_events: `(instance_id, event_type, timestamp)`
- task_events: `(task_execution_id, event_type, timestamp)`

### 4. State Machine
Apply events in order to build workflow/task state:
- Started → Running → Completed/Faulted
- Handle out-of-order events (e.g., Completed arrives before Started)

### 5. Writer
UPSERT to normalized tables:
```sql
INSERT INTO workflow_instances (...) VALUES (...)
ON CONFLICT (id) DO UPDATE SET ...
```

### 6. Marker
Mark processed events:
```sql
UPDATE workflow_events SET processed = true WHERE id IN (...)
```

### Deployment Modes

The Event Processor supported two modes:

**Polling Mode (MODE 1 - Original):**
- Scheduled batch processing every N seconds
- Pros: Simple, no external dependencies
- Cons: Polling delay, database load

**Kafka Consumer Mode (MODE 3 - Planned):**
- Consume from Kafka topics
- Pros: Real-time, scalable, event replay
- Cons: Requires Kafka infrastructure

## Why Triggers/Pipelines Are Better

| Aspect | Event Processor | Triggers/Pipelines |
|--------|----------------|-------------------|
| **Deployment** | Separate service | Built-in database feature |
| **Latency** | Polling delay (1-5s) | Real-time (on INSERT) |
| **Complexity** | Java code, scheduling | SQL/Painless script |
| **Scaling** | Service replicas | Database handles it |
| **Failure Recovery** | Restart service | Automatic retry |
| **Event Replay** | Re-mark as unprocessed | Delete tail DB, reprocess logs |

## Migration to Trigger-Based Architecture

The migration removed:
- `data-index-event-processor` module
- `EventProcessorScheduler.java`
- `KafkaEventConsumer.java`
- `EventProcessorConfiguration.java`
- Polling logic
- `processed` column from staging tables

And added:
- PostgreSQL trigger functions (`normalize_workflow_event()`, `normalize_task_event()`)
- Field-level idempotency logic (V2 migration)
- Timestamp-based out-of-order handling

## When You Might Need an Event Processor

Consider implementing an Event Processor again if:
- Complex event processing (CEP) patterns needed
- Business logic too complex for triggers/pipelines
- Need to enrich events from external APIs
- Cross-event aggregations required

For these cases, consider MODE 3 (Kafka with consumer service) as a future enhancement.

See `deployment/MODE3_IMPLEMENTATION_PLAN.md` for optional Kafka architecture.

## References

- Trigger Implementation: `data-index-storage-migrations/V1__initial_schema.sql`
- Idempotency Logic: `data-index-storage-migrations/V2__add_idempotency.sql`
- FluentBit Config: `scripts/fluentbit/mode1-postgresql-triggers/`
- E2E Tests: `scripts/kind/test-mode1-e2e.sh`
