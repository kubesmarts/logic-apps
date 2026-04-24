# MODE 1 Architecture Update - From Polling to Trigger-based Normalization

## Summary

MODE 1 has been completely redesigned from a **polling-based Event Processor** architecture to a **trigger-based normalization** architecture using PostgreSQL BEFORE INSERT triggers.

**Result**: Simpler, faster, and no Event Processor service needed for MODE 1!

## What Changed

### Before (Polling Architecture)
```
Quarkus Flow → FluentBit → PostgreSQL staging tables (workflow_events, task_events)
                                    ↓ (processed=FALSE)
                            Event Processor (polls every 5s)
                                    ↓ (batch processing)
                            Normalized tables (workflow_instances, task_instances)
                                    ↓
                            GraphQL API
```

**Problems**:
- Event Processor adds deployment complexity
- Polling introduces latency (5-second intervals)
- Staging tables need cleanup (retention policy)
- More services to monitor and maintain

### After (Trigger-based Architecture)
```
Quarkus Flow → FluentBit → PostgreSQL raw tables (workflow_events_raw, task_events_raw)
                                    ↓ (BEFORE INSERT trigger fires immediately)
                            PostgreSQL Trigger Functions
                            - Extract fields from JSONB data column
                            - UPSERT into normalized tables
                                    ↓
                            Normalized tables (workflow_instances, task_instances)
                                    ↓
                            GraphQL API
```

**Benefits**:
- ✅ No Event Processor service needed
- ✅ Real-time normalization (no polling delay)
- ✅ Simpler deployment (one less service)
- ✅ Automatic out-of-order event handling
- ✅ Idempotent (safe to replay events)
- ✅ Raw events preserved for debugging

## Technical Changes

### 1. FluentBit Configuration

**Before**: Custom Lua script to flatten JSON → Map to PostgreSQL columns
```lua
-- flatten_event.lua
new_record["id"] = record["instanceId"]
new_record["namespace"] = record["workflowNamespace"]
-- ... many field mappings
```

**After**: No Lua script needed - FluentBit pgsql plugin writes entire event as JSONB
```conf
[OUTPUT]
    Name   pgsql
    Table  workflow_events_raw
    # Automatically creates: tag TEXT, time TIMESTAMP, data JSONB
```

### 2. Database Schema

**Before**: Staging tables with `processed` flag for Event Processor
```sql
CREATE TABLE workflow_events (
  event_id BIGSERIAL PRIMARY KEY,
  inserted_at TIMESTAMP DEFAULT NOW(),
  id VARCHAR(255),
  namespace VARCHAR(255),
  name VARCHAR(255),
  ... -- 15+ individual columns
  processed BOOLEAN DEFAULT FALSE,  -- ← Event Processor polls this
  processed_at TIMESTAMP
);
```

**After**: Simple raw table + trigger functions
```sql
-- Raw staging table (FluentBit pgsql plugin fixed schema)
CREATE TABLE workflow_events_raw (
  tag TEXT,
  time TIMESTAMP WITH TIME ZONE,
  data JSONB  -- Complete event as JSON
);

-- Trigger function extracts and normalizes
CREATE FUNCTION normalize_workflow_event() RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO workflow_instances (
    id, namespace, name, ...
  ) VALUES (
    NEW.data->>'instanceId',
    NEW.data->>'workflowNamespace',
    NEW.data->>'workflowName',
    ...
  ) ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
    ...;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER normalize_workflow_events
  BEFORE INSERT ON workflow_events_raw
  FOR EACH ROW EXECUTE FUNCTION normalize_workflow_event();
```

### 3. Deployment Components

**Removed**:
- ❌ Event Processor service deployment
- ❌ Event Processor configuration
- ❌ Polling scheduler
- ❌ Batch processing logic
- ❌ Retention policy cleanup jobs

**Simplified**:
- ✅ Just FluentBit DaemonSet
- ✅ Just PostgreSQL with triggers
- ✅ Migration scripts include trigger definitions

## FluentBit pgsql Plugin Constraint

The FluentBit PostgreSQL output plugin has a **fixed table schema** that cannot be customized:

```sql
CREATE TABLE <table_name> (
  tag TEXT,              -- FluentBit tag (workflow.instance.started, etc.)
  time TIMESTAMP,        -- Event timestamp
  data JSONB            -- Complete record as JSON
);
```

This constraint is why we:
1. **Cannot** have FluentBit directly map to individual columns
2. **Must** use raw staging tables with this exact schema
3. **Use** PostgreSQL triggers to extract fields from the `data` JSONB column

See: https://docs.fluentbit.io/manual/data-pipeline/outputs/postgresql

## Out-of-Order Event Handling

Triggers automatically handle events arriving in any order using `UPSERT` with `COALESCE`:

```sql
-- Example: workflow.completed arrives before workflow.started
-- First insert creates placeholder
INSERT INTO workflow_instances (id) VALUES ('abc-123')
ON CONFLICT (id) DO NOTHING;

-- Later insert fills in details
INSERT INTO workflow_instances (id, namespace, name, ...)
VALUES ('abc-123', 'org.acme', 'hello-world', ...)
ON CONFLICT (id) DO UPDATE SET
  namespace = EXCLUDED.namespace,
  name = EXCLUDED.name,
  start = COALESCE(EXCLUDED.start, workflow_instances.start),
  "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
  ...;
```

The `COALESCE` ensures existing non-null values aren't overwritten by nulls from out-of-order events.

## Migration Guide

### For Existing Deployments

1. **Update database schema** - Run V1__initial_schema.sql migration
   - Creates `*_raw` tables
   - Creates `workflow_instances` and `task_instances` normalized tables
   - Creates trigger functions

2. **Update FluentBit configuration** - Use new config from `mode1-postgresql-triggers/`
   - No Lua script needed
   - Simplified routing

3. **Remove Event Processor** - No longer needed
   - Delete Event Processor deployment
   - Remove Event Processor configuration

### For New Deployments

1. Deploy PostgreSQL with Flyway migrations
2. Deploy FluentBit DaemonSet
3. Deploy workflow applications
4. That's it! No Event Processor needed.

## Performance Considerations

### Trigger Overhead

PostgreSQL triggers execute **synchronously** on every INSERT:
- Adds ~1-5ms per event (depending on hardware)
- Acceptable for most workloads (< 10,000 events/sec)
- For higher throughput, consider MODE 3 (Kafka) with async processing

### Indexing

Normalized tables have indexes for fast GraphQL queries:
- `workflow_instances`: namespace+name, status, start timestamp
- `task_instances`: instance_id, status, start timestamp

Raw tables have minimal indexes (just time + tag) for cleanup queries.

## Monitoring

### Key Metrics

1. **FluentBit health**
   ```bash
   kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "postgresql.svc"
   ```

2. **Raw vs normalized counts** (should match)
   ```sql
   SELECT 
     (SELECT COUNT(*) FROM workflow_events_raw) as raw,
     (SELECT COUNT(*) FROM workflow_instances) as normalized;
   ```

3. **Trigger execution time** (via pg_stat_statements extension)
   ```sql
   SELECT calls, mean_exec_time, query
   FROM pg_stat_statements
   WHERE query LIKE '%normalize_workflow%';
   ```

## Troubleshooting

### Events in raw tables but not normalized

Check trigger exists:
```bash
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c "\d workflow_events_raw"
```

Should show: `Triggers: normalize_workflow_events`

### Trigger errors

Enable trigger logging:
```sql
ALTER FUNCTION normalize_workflow_event() SET log_min_messages = 'DEBUG';
```

Check PostgreSQL logs:
```bash
kubectl logs -n postgresql postgresql-0 | grep normalize
```

## Future Enhancements

### Potential Optimizations

1. **Bulk trigger processing** - Batch multiple INSERTs in single trigger call
2. **Async triggers** - Use PostgreSQL LISTEN/NOTIFY for async normalization
3. **Partitioning** - Partition raw tables by time for faster cleanup
4. **Materialized views** - Pre-compute aggregations for GraphQL queries

### MODE 3 Integration

Event Processor remains available for MODE 3 (Kafka):
- Kafka consumption
- Complex event processing
- Multiple consumers
- Event replay capabilities

## References

- FluentBit PostgreSQL Output: https://docs.fluentbit.io/manual/data-pipeline/outputs/postgresql
- PostgreSQL Triggers: https://www.postgresql.org/docs/current/triggers.html
- PostgreSQL JSONB: https://www.postgresql.org/docs/current/datatype-json.html
- Quarkus Flow 0.9.0: https://github.com/quarkiverse/quarkus-flow

## Summary of Files Changed

### Renamed
- `scripts/fluentbit/mode1-postgresql-polling/` → `scripts/fluentbit/mode1-postgresql-triggers/`

### Updated
- `data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql`
  - Changed from staging tables with `processed` flag
  - To raw tables (tag, time, data) + trigger functions

- `data-index-storage-migrations/README.md`
  - Updated architecture diagrams
  - Added trigger-based normalization section

- `scripts/fluentbit/mode1-postgresql-triggers/fluent-bit.conf`
  - Removed Lua flatten script
  - Simplified to just routing + pgsql output

- `scripts/fluentbit/mode1-postgresql-triggers/README.md`
  - Completely rewritten for trigger-based architecture

- `scripts/fluentbit/README.md`
  - Updated MODE 1 description

- `data-index-event-processor/pom.xml`
  - Updated description (MODE 3 only, not MODE 1)

### Removed (No longer needed for MODE 1)
- Lua `flatten-event.lua` script (FluentBit uses pgsql plugin's native JSONB)
- Event Processor polling configuration
- Staging table cleanup/retention logic

---

**Status**: ✅ Complete and tested end-to-end in KIND cluster
**Date**: 2026-04-23
