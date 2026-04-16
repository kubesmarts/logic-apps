# Event Ingestion Strategy - Handling Out-of-Order Events

**Date**: 2026-04-15  
**Critical Issue**: Events can arrive out of order in distributed systems

## The Problem

Quarkus Flow emits events in this order:
1. `workflow.instance.started` (status=RUNNING, start time, input)
2. `workflow.task.started` (task details, input)
3. `workflow.task.completed` (output, end time)
4. `workflow.instance.completed` (status=COMPLETED, end time, output)

But FluentBit/network may deliver them out of order:
- ❌ `completed` arrives before `started`
- ❌ `faulted` arrives before `started`
- ❌ Events replayed due to failures

**Current broken approach**:
- `started` → INSERT ... ON CONFLICT (✅ works)
- `completed` → UPDATE only (❌ fails if row doesn't exist)

## Solution 1: UPSERT All Events (Naive)

Every event does INSERT ... ON CONFLICT:

```sql
-- Event: workflow.instance.completed (arrives FIRST)
INSERT INTO workflow_instances (id, status, "end", output)
VALUES ('uuid-1234', 'COMPLETED', '2026-04-15T15:30:30Z', '{"result":"success"}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    "end" = EXCLUDED.end,
    output = EXCLUDED.output;

-- Event: workflow.instance.started (arrives LATER)
INSERT INTO workflow_instances (id, namespace, name, version, status, start, input)
VALUES ('uuid-1234', 'default', 'order-processing', '1.0.0', 'RUNNING', '2026-04-15T15:30:00Z', '{"orderId":"12345"}'::jsonb)
ON CONFLICT (id) DO UPDATE SET
    namespace = EXCLUDED.namespace,
    name = EXCLUDED.name,
    version = EXCLUDED.version,
    status = EXCLUDED.status,  -- ❌ WRONG! Overwrites COMPLETED with RUNNING
    start = EXCLUDED.start,
    input = EXCLUDED.input;
```

**Problem**: Later events overwrite earlier events, even if semantically incorrect (RUNNING overwrites COMPLETED).

## Solution 2: UPSERT with Smart Merge (COALESCE)

Preserve existing non-null values, only fill in missing data:

```sql
-- Event: workflow.instance.completed (arrives FIRST)
INSERT INTO workflow_instances (
    id, status, "end", output,
    namespace, name, version, start, input, last_update
)
VALUES (
    'uuid-1234', 'COMPLETED', '2026-04-15T15:30:30Z', '{"result":"success"}'::jsonb,
    NULL, NULL, NULL, NULL, NULL, NULL
)
ON CONFLICT (id) DO UPDATE SET
    status = COALESCE(EXCLUDED.status, workflow_instances.status),
    "end" = COALESCE(EXCLUDED.end, workflow_instances."end"),
    output = COALESCE(EXCLUDED.output, workflow_instances.output),
    -- Don't overwrite existing values with NULL
    namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
    name = COALESCE(workflow_instances.name, EXCLUDED.name),
    version = COALESCE(workflow_instances.version, EXCLUDED.version),
    start = COALESCE(workflow_instances.start, EXCLUDED.start),
    input = COALESCE(workflow_instances.input, EXCLUDED.input),
    last_update = COALESCE(EXCLUDED.last_update, workflow_instances.last_update);

-- Event: workflow.instance.started (arrives LATER)
INSERT INTO workflow_instances (
    id, namespace, name, version, status, start, input,
    "end", output, last_update
)
VALUES (
    'uuid-1234', 'default', 'order-processing', '1.0.0', 'RUNNING', '2026-04-15T15:30:00Z', '{"orderId":"12345"}'::jsonb,
    NULL, NULL, NULL
)
ON CONFLICT (id) DO UPDATE SET
    -- Fill in missing identity fields
    namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
    name = COALESCE(workflow_instances.name, EXCLUDED.name),
    version = COALESCE(workflow_instances.version, EXCLUDED.version),
    start = COALESCE(workflow_instances.start, EXCLUDED.start),
    input = COALESCE(workflow_instances.input, EXCLUDED.input),
    -- Don't overwrite final status with initial status
    status = COALESCE(workflow_instances.status, EXCLUDED.status),
    "end" = COALESCE(workflow_instances."end", EXCLUDED."end"),
    output = COALESCE(workflow_instances.output, EXCLUDED.output),
    last_update = COALESCE(workflow_instances.last_update, EXCLUDED.last_update);
```

**Problem**: Still overwrites status incorrectly. COALESCE prefers existing values, but what if RUNNING is existing and COMPLETED is new?

## Solution 3: Timestamp-Based Merge (Best)

Use event timestamps to determine which data is "newer":

```sql
-- Add event_timestamp column to track when event occurred
ALTER TABLE workflow_instances ADD COLUMN event_timestamp TIMESTAMP WITH TIME ZONE;

-- Event: workflow.instance.completed (timestamp: 2026-04-15T15:30:30Z)
INSERT INTO workflow_instances (
    id, status, "end", output, event_timestamp,
    namespace, name, version, start, input, last_update
)
VALUES (
    'uuid-1234', 'COMPLETED', '2026-04-15T15:30:30Z', '{"result":"success"}'::jsonb, '2026-04-15T15:30:30Z',
    NULL, NULL, NULL, NULL, NULL, NULL
)
ON CONFLICT (id) DO UPDATE SET
    -- Update if new event is newer OR existing is NULL
    status = CASE 
        WHEN EXCLUDED.event_timestamp >= workflow_instances.event_timestamp OR workflow_instances.status IS NULL 
        THEN EXCLUDED.status 
        ELSE workflow_instances.status 
    END,
    "end" = CASE 
        WHEN EXCLUDED.event_timestamp >= workflow_instances.event_timestamp OR workflow_instances."end" IS NULL 
        THEN EXCLUDED."end" 
        ELSE workflow_instances."end" 
    END,
    output = CASE 
        WHEN EXCLUDED.event_timestamp >= workflow_instances.event_timestamp OR workflow_instances.output IS NULL 
        THEN EXCLUDED.output 
        ELSE workflow_instances.output 
    END,
    event_timestamp = GREATEST(workflow_instances.event_timestamp, EXCLUDED.event_timestamp),
    -- Always fill in missing identity fields (they don't change)
    namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
    name = COALESCE(workflow_instances.name, EXCLUDED.name),
    version = COALESCE(workflow_instances.version, EXCLUDED.version),
    start = COALESCE(workflow_instances.start, EXCLUDED.start),
    input = COALESCE(workflow_instances.input, EXCLUDED.input);
```

**Pros**:
- ✅ Handles out-of-order events correctly
- ✅ Later events override earlier events (COMPLETED wins over RUNNING)
- ✅ Idempotent (replaying same event doesn't change data)
- ✅ Identity fields (namespace, name, version) filled in from any event

**Cons**:
- ❌ Requires modifying schema to add event_timestamp
- ❌ Complex SQL that's hard to express in FluentBit configuration

## Solution 4: Application-Level Ingestion (Recommended)

FluentBit → HTTP → Custom Quarkus Service → JPA → PostgreSQL

**Why**:
1. **Complex Logic**: Event merging logic is too complex for SQL/FluentBit config
2. **Type Safety**: JPA entities provide type checking and validation
3. **Business Rules**: Can implement custom merging logic (status transitions, validation)
4. **Observability**: Can log/trace event processing, detect anomalies
5. **Testing**: Can unit test event merging logic

**Architecture**:
```
Quarkus Flow Runtime
    ↓ (emits)
JSON Logs (/var/log/quarkus-flow/*.log)
    ↓ (tails & parses)
FluentBit
    ↓ (HTTP POST to /api/events)
Data Index Ingestion Service (Quarkus)
    ├── EventIngestionResource (REST endpoints)
    ├── WorkflowInstanceService (merge logic)
    └── TaskExecutionService (merge logic)
    ↓ (JPA save/merge)
PostgreSQL (workflow_instances, task_executions)
    ↓ (reads)
Data Index GraphQL Service
```

**Ingestion Service Logic** (pseudo-code):

```java
@ApplicationScoped
public class WorkflowInstanceService {
    
    @Inject
    WorkflowInstanceRepository repository;
    
    @Transactional
    public void handleEvent(WorkflowInstanceEvent event) {
        WorkflowInstanceEntity entity = repository.findById(event.getInstanceId())
            .orElse(new WorkflowInstanceEntity());
        
        // Merge event data into entity
        if (event instanceof WorkflowStartedEvent started) {
            entity.setId(started.getInstanceId());
            entity.setNamespace(started.getWorkflowNamespace());
            entity.setName(started.getWorkflowName());
            entity.setVersion(started.getWorkflowVersion());
            entity.setStatus(WorkflowInstanceStatus.RUNNING);
            entity.setStart(started.getStartTime());
            entity.setInput(started.getInput());
        }
        else if (event instanceof WorkflowCompletedEvent completed) {
            // Only update if not already set OR new event is later
            if (entity.getId() == null) {
                entity.setId(completed.getInstanceId());
            }
            entity.setStatus(WorkflowInstanceStatus.COMPLETED);
            entity.setEnd(completed.getEndTime());
            entity.setOutput(completed.getOutput());
        }
        else if (event instanceof WorkflowFaultedEvent faulted) {
            if (entity.getId() == null) {
                entity.setId(faulted.getInstanceId());
            }
            entity.setStatus(WorkflowInstanceStatus.FAULTED);
            entity.setEnd(faulted.getEndTime());
            
            WorkflowInstanceErrorEntity error = new WorkflowInstanceErrorEntity();
            error.setType(faulted.getError().getType());
            error.setTitle(faulted.getError().getTitle());
            error.setDetail(faulted.getError().getDetail());
            error.setStatus(faulted.getError().getStatus());
            error.setInstance(faulted.getError().getInstance());
            entity.setError(error);
        }
        
        repository.persist(entity);  // JPA handles INSERT vs UPDATE
    }
}
```

## Recommended Path Forward

1. **Short-term (Testing)**: Use FluentBit stdout to verify event parsing works ✅ DONE
2. **Medium-term (Production)**: Build Quarkus Ingestion Service with:
   - REST endpoints for workflow/task events
   - Smart merge logic in service layer
   - JPA repositories for persistence
3. **FluentBit Configuration**: Change output from pgsql to HTTP:
   ```
   [OUTPUT]
       Name   http
       Match  workflow.instance.*
       Host   data-index-ingestion
       Port   8080
       URI    /api/events/workflow-instance
       Format json
   ```

## Next Steps

1. Create `data-index-ingestion-service` Quarkus module
2. Define REST API for event ingestion
3. Implement WorkflowInstanceService with merge logic
4. Implement TaskExecutionService with merge logic
5. Update FluentBit to HTTP output
6. Test with sample events (including out-of-order scenarios)

---

**Key Insight**: Out-of-order event processing requires application-level logic, not just SQL. FluentBit is excellent for parsing and routing, but complex merging belongs in the application layer.
