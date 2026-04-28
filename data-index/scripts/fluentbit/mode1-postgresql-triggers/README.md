# PostgreSQL Mode

Real-time event normalization - no Event Processor needed!

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Quarkus Flow Workflow Pods                                         │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │ Quarkus Flow 0.9.0+                                      │       │
│  │ - Structured logging enabled                             │       │
│  │ - Epoch timestamp format                                 │       │
│  │ - Writes to stdout (mixed with app logs)                 │       │
│  └────────────────────────┬─────────────────────────────────┘       │
└───────────────────────────┼─────────────────────────────────────────┘
                            │
                            │  (stdout: App logs + JSON events)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Kubernetes Node                                                    │
│  /var/log/containers/<pod>_workflows_<container>.log                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ {"log":"22:51:50 INFO ...\n","stream":"stdout",...}       │    │
│  │ {"log":"{\"instanceId\":\"...\",\"eventType\":...}","...}  │    │
│  └────────────────────────────────────────────────────────────┘    │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  FluentBit DaemonSet                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  INPUT: tail /var/log/containers/*_workflows_*.log         │    │
│  │  FILTER: parse docker format → parse nested JSON          │    │
│  │  FILTER: grep eventType (keep only structured events)      │    │
│  │  FILTER: kubernetes metadata enrichment                    │    │
│  │  FILTER: rewrite_tag (route by eventType)                  │    │
│  │  OUTPUT: PostgreSQL pgsql plugin                           │    │
│  │    - workflow.instance.* → workflow_events_raw             │    │
│  │    - workflow.task.* → task_events_raw                     │    │
│  └────────────────────────────────────────────────────────────┘    │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        │  (INSERT: tag TEXT, time TIMESTAMP, data JSONB)
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PostgreSQL Database                                                │
│                                                                     │
│  ┌─────────────────────┐         ┌─────────────────────┐           │
│  │ workflow_events_raw │         │ task_events_raw     │           │
│  │ - tag TEXT          │         │ - tag TEXT          │           │
│  │ - time TIMESTAMP    │         │ - time TIMESTAMP    │           │
│  │ - data JSONB        │         │ - data JSONB        │           │
│  └──────────┬──────────┘         └──────────┬──────────┘           │
│             │                               │                       │
│             │ BEFORE INSERT TRIGGER         │ BEFORE INSERT TRIGGER │
│             ▼                               ▼                       │
│  ┌──────────────────────┐       ┌──────────────────────┐           │
│  │ normalize_workflow() │       │ normalize_task()     │           │
│  │ - Extract from JSONB │       │ - Extract from JSONB │           │
│  │ - UPSERT normalized  │       │ - UPSERT normalized  │           │
│  └──────────┬───────────┘       └──────────┬───────────┘           │
│             │                               │                       │
│             ▼                               ▼                       │
│  ┌──────────────────────┐       ┌──────────────────────┐           │
│  │ workflow_instances   │       │ task_instances       │           │
│  │ - id (PK)            │       │ - task_execution_id  │           │
│  │ - namespace          │       │ - instance_id (FK)   │           │
│  │ - name               │       │ - task_name          │           │
│  │ - version            │       │ - task_position      │           │
│  │ - status             │       │ - status             │           │
│  │ - start              │       │ - start              │           │
│  │ - end                │       │ - end                │           │
│  │ - input (JSONB)      │       │ - input (JSONB)      │           │
│  │ - output (JSONB)     │       │ - output (JSONB)     │           │
│  │ - error_*            │       │ - created_at         │           │
│  │ - created_at         │       │ - updated_at         │           │
│  │ - updated_at         │       └──────────────────────┘           │
│  └──────────────────────┘                                          │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        │  (JPA queries)
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data Index GraphQL API                                             │
│  - getWorkflowInstance(id)                                          │
│  - getWorkflowInstances(filter, orderBy, limit, offset)             │
│  - getTaskInstances(filter, orderBy, limit, offset)                 │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Features

### ✅ No Event Processor Needed
PostgreSQL triggers handle normalization **in real-time** as events are inserted by FluentBit.

### ✅ Out-of-Order Event Handling
Triggers use `UPSERT` with `COALESCE` to handle events arriving in any order:
- Later events don't overwrite earlier data
- Missing workflows auto-created when task events arrive first

### ✅ Idempotent
Same event can be inserted multiple times safely - triggers handle deduplication.

### ✅ Raw Events Preserved
All original events stored in `*_raw` tables for debugging and potential replay.

### ✅ Simpler Architecture
Fewer services to deploy and monitor - just FluentBit and PostgreSQL.

## FluentBit Configuration

### Fixed Schema Requirement
FluentBit pgsql output plugin uses a **fixed schema** (cannot be customized):
- `tag TEXT` - The FluentBit tag (e.g., "workflow.instance.started")
- `time TIMESTAMP WITH TIME ZONE` - Event timestamp
- `data JSONB` - Complete event as JSON

This is why we use raw staging tables + triggers instead of direct column mapping.

### Event Routing
```
flow.events → rewrite_tag filter → workflow.instance.* or workflow.task.*
  ↓                                  ↓                      ↓
workflow_events_raw                task_events_raw
```

## Trigger Functions

### normalize_workflow_event()
Extracts fields from `data` JSONB and UPSERTs into `workflow_instances`:

```sql
INSERT INTO workflow_instances (id, namespace, name, ...)
VALUES (data->>'instanceId', data->>'workflowNamespace', ...)
ON CONFLICT (id) DO UPDATE SET
  status = EXCLUDED.status,
  "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
  ...;
```

### normalize_task_event()
Extracts fields from `data` JSONB and UPSERTs into `task_instances`:

```sql
-- First ensure workflow exists (handle out-of-order)
INSERT INTO workflow_instances (id) VALUES (data->>'instanceId')
ON CONFLICT DO NOTHING;

-- Then upsert task
INSERT INTO task_instances (task_execution_id, instance_id, ...)
VALUES (data->>'taskExecutionId', data->>'instanceId', ...)
ON CONFLICT (task_execution_id) DO UPDATE SET ...;
```

## Configuration Files

### `fluent-bit.conf`
Main configuration with:
- **INPUT**: Tail `/var/log/containers/*_workflows_*.log` (K8s stdout)
- **FILTER**: Parse docker format, extract JSON events, grep by `eventType`
- **FILTER**: `kubernetes` metadata enrichment
- **FILTER**: `rewrite_tag` to route by eventType
- **OUTPUT**: PostgreSQL pgsql plugin to `*_raw` tables

### `parsers.conf`
JSON parser configuration for Quarkus Flow events

### `kubernetes/daemonset.yaml`
Kubernetes DaemonSet to deploy FluentBit on every node

## Environment Variables

Set these in the DaemonSet:

```yaml
env:
- name: POSTGRES_HOST
  value: "postgresql.postgresql.svc.cluster.local"
- name: POSTGRES_PORT
  value: "5432"
- name: POSTGRES_DB
  value: "dataindex"
- name: POSTGRES_USER
  value: "dataindex"
- name: POSTGRES_PASSWORD
  value: "dataindex123"
```

## Deployment

### Prerequisites
1. PostgreSQL database running
2. Database schema with triggers created (see `data-index-storage-migrations`)
3. Kubernetes cluster with workflow pods

### Deploy FluentBit

```bash
# 1. Create namespace
kubectl create namespace logging

# 2. Generate and apply ConfigMap
# From data-index/scripts/fluentbit/ directory:
cd ../..
./generate-configmap.sh mode1-postgresql-triggers mode1-postgresql-triggers/kubernetes/configmap.yaml
kubectl apply -f mode1-postgresql-triggers/kubernetes/configmap.yaml

# Alternatively, create ConfigMap manually (must include ALL files):
# kubectl create configmap workflows-fluent-bit-mode1-config \
#   -n logging \
#   --from-file=fluent-bit.conf=fluent-bit.conf \
#   --from-file=parsers.conf=parsers.conf \
#   --from-file=flatten-event.lua=flatten-event.lua

# 3. Deploy FluentBit DaemonSet
kubectl apply -f kubernetes/daemonset.yaml

# 4. Verify deployment
kubectl get pods -n logging
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=20
```

### Verify Event Ingestion

```bash
# 1. Trigger a workflow
kubectl port-forward -n workflows svc/workflow-test-app 8080:8080 &
curl -X POST http://localhost:8080/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name": "Test"}'

# 2. Check FluentBit captured the events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "workflow.started"

# 3. Verify raw events in PostgreSQL
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT tag, data->>'instanceId', data->>'eventType' FROM workflow_events_raw LIMIT 5;"

# 4. Verify normalized data
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT id, name, status FROM workflow_instances ORDER BY start DESC LIMIT 5;"

# 5. Verify task instances
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT task_execution_id, instance_id, task_name, status FROM task_instances LIMIT 5;"
```

## Monitoring

### FluentBit Metrics

```bash
# Check FluentBit is processing events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "workflow.instance"

# Check for errors
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i error

# Check PostgreSQL connections
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "host=postgresql"
```

### PostgreSQL Monitoring

```bash
# Count workflow instances
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT status, COUNT(*) FROM workflow_instances GROUP BY status;"

# Count task instances
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM task_instances;"

# Check raw events vs normalized
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT 
    (SELECT COUNT(*) FROM workflow_events_raw) as raw_events,
    (SELECT COUNT(*) FROM workflow_instances) as normalized_workflows;"
```

## Troubleshooting

### No events in PostgreSQL

**Check 1**: FluentBit is running
```bash
kubectl get pods -n logging
```

**Check 2**: FluentBit can read container logs
```bash
kubectl exec -n logging <pod-name> -- ls -la /var/log/containers/*_workflows_*.log
```

**Check 3**: FluentBit is parsing JSON
```bash
kubectl logs -n logging <pod-name> | grep "eventType"
```

**Check 4**: PostgreSQL connectivity
```bash
kubectl logs -n logging <pod-name> | grep "postgresql.svc.cluster.local"
```

### Triggers not firing

**Check**: Trigger exists
```bash
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "\d workflow_events_raw"
```

You should see: `Triggers: normalize_workflow_events`

### Events in raw tables but not normalized

**Check**: Trigger function errors
```bash
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT * FROM pg_stat_activity WHERE state = 'idle in transaction';"
```

**Enable trigger logging**:
```sql
ALTER FUNCTION normalize_workflow_event() SET log_min_messages = 'DEBUG';
```

## Retention Policy

Raw staging tables can be cleaned up periodically:

```sql
-- Delete raw events older than 7 days
DELETE FROM workflow_events_raw WHERE time < NOW() - INTERVAL '7 days';
DELETE FROM task_events_raw WHERE time < NOW() - INTERVAL '7 days';
```

Schedule this via PostgreSQL `pg_cron` extension or external cron job.

## Pros and Cons

### ✅ Pros
- **Real-time**: No polling delays - triggers fire immediately
- **Simple**: No Event Processor service to deploy
- **Idempotent**: Safe to replay events
- **Out-of-order handling**: Automatic via UPSERT logic
- **Debugging**: Raw events preserved for troubleshooting
- **Stateless**: FluentBit doesn't maintain state

### ❌ Cons
- **PostgreSQL coupling**: Normalization logic in database
- **Limited flexibility**: Schema changes require trigger updates
- **No complex processing**: Triggers can't do batch operations
- **PostgreSQL load**: Triggers execute on every INSERT

## When to Use

- **All deployments** using PostgreSQL storage
- **Production environments** (scales well with PostgreSQL)
- **Simple to moderate event volumes** (< 10,000 events/sec)
- **Standard normalization** requirements

## Migration Path

If you need different capabilities:
- **Mode 2 (Elasticsearch)**: Better for full-text search and analytics
- **Mode 3 (Kafka)**: Better for event replay and complex event processing
