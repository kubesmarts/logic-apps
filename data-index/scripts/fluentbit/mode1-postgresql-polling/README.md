# Mode 1: PostgreSQL Polling

Direct ingestion from FluentBit to PostgreSQL tables.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Quarkus Flow Workflow Pods                                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐                          │
│  │ Pod 1    │  │ Pod 2    │  │ Pod 3    │                          │
│  │ stdout   │  │ stdout   │  │ stdout   │                          │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘                          │
└───────┼─────────────┼─────────────┼────────────────────────────────┘
        │             │             │
        │  (JSON logs from Quarkus Flow structured logging)
        │             │             │
        ▼             ▼             ▼
┌─────────────────────────────────────────────────────────────────────┐
│  FluentBit DaemonSet                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  INPUT: tail /var/log/containers/*workflow*.log            │    │
│  │  FILTER: grep eventType (workflow.* or task.*)             │    │
│  │  FILTER: lua flatten_event()                               │    │
│  │  OUTPUT: PostgreSQL INSERT/UPDATE                          │    │
│  └────────────────────────────────────────────────────────────┘    │
└───────┬──────────────────────────────────────────────────────────── │
        │
        │  (INSERT/UPDATE SQL)
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  PostgreSQL Database                                                │
│  ┌──────────────────────┐   ┌──────────────────────┐               │
│  │ workflow_instances   │   │ task_executions      │               │
│  │ - id (PK)            │   │ - id (PK)            │               │
│  │ - namespace          │   │ - workflow_instance_id (FK)          │
│  │ - name               │   │ - task_name          │               │
│  │ - version            │   │ - task_position      │               │
│  │ - status             │   │ - enter              │               │
│  │ - start              │   │ - exit               │               │
│  │ - end                │   │ - input_args (JSONB) │               │
│  │ - input (JSONB)      │   │ - output_args (JSONB)│               │
│  │ - output (JSONB)     │   │ - error_message      │               │
│  │ - error_*            │   └──────────────────────┘               │
│  │ - last_update        │                                          │
│  └──────────────────────┘                                          │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        │  (JPA queries)
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data Index GraphQL API                                             │
│  - getWorkflowInstance(id)                                          │
│  - getWorkflowInstances(filter, orderBy, limit, offset)             │
│  - getTaskExecutions(filter, orderBy, limit, offset)                │
└─────────────────────────────────────────────────────────────────────┘
```

## Event Processing

### Workflow Instance Events

| Event | FluentBit Action | SQL Operation |
|-------|------------------|---------------|
| `workflow.started.v1` | Parse → Flatten → Route | `INSERT INTO workflow_instances (id, namespace, name, version, status, start, input) VALUES (...) ON CONFLICT (id) DO UPDATE ...` |
| `workflow.completed.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status='COMPLETED', "end"=..., output=... WHERE id=...` |
| `workflow.faulted.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status='FAULTED', "end"=..., error_type=..., error_title=..., error_detail=... WHERE id=...` |
| `workflow.cancelled.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status='CANCELLED', "end"=... WHERE id=...` |
| `workflow.suspended.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status='SUSPENDED' WHERE id=...` |
| `workflow.resumed.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status='RUNNING' WHERE id=...` |
| `workflow.status-changed.v1` | Parse → Flatten → Route | `UPDATE workflow_instances SET status=..., last_update=... WHERE id=...` |

### Task Execution Events

| Event | FluentBit Action | SQL Operation |
|-------|------------------|---------------|
| `task.started.v1` | Parse → Flatten → Route | `INSERT INTO task_executions (id, workflow_instance_id, task_name, task_position, enter, input_args) VALUES (...) ON CONFLICT (id) DO UPDATE ...` |
| `task.completed.v1` | Parse → Flatten → Route | `UPDATE task_executions SET exit=..., output_args=... WHERE id=...` |
| `task.faulted.v1` | Parse → Flatten → Route | `UPDATE task_executions SET exit=..., error_message=... WHERE id=...` |

## Configuration Files

### `fluent-bit.conf`
Main configuration with:
- **INPUT**: Tail container logs from `/var/log/containers/*workflow*.log`
- **FILTER**: Filter by eventType pattern, flatten nested JSON
- **OUTPUT**: PostgreSQL INSERT/UPDATE statements for each event type

### `parsers.conf`
JSON parser configuration for Quarkus Flow events

### `flatten-event.lua`
Lua script to flatten nested JSON fields:
- `error.type` → `error_type`
- `error.title` → `error_title`
- JSONB fields preserved as-is for PostgreSQL

### `kubernetes/configmap.yaml`
Kubernetes ConfigMap containing FluentBit config files

### `kubernetes/daemonset.yaml`
Kubernetes DaemonSet to deploy FluentBit on every node

## Environment Variables

Set these in the Kubernetes Secret or ConfigMap:

```bash
POSTGRES_HOST=postgresql.postgresql.svc.cluster.local
POSTGRES_PORT=5432
POSTGRES_DB=dataindex
POSTGRES_USER=dataindex
POSTGRES_PASSWORD=dataindex123
```

## Deployment

### Prerequisites
1. PostgreSQL database running
2. Database schema created (`scripts/schema.sql`)
3. Kubernetes cluster with workflow pods

### Deploy FluentBit

```bash
# 1. Create ConfigMap with FluentBit configuration
kubectl apply -f kubernetes/configmap.yaml

# 2. Create Secret with PostgreSQL credentials
kubectl create secret generic fluent-bit-postgresql \
  --namespace fluent-bit \
  --from-literal=POSTGRES_HOST=postgresql.postgresql.svc.cluster.local \
  --from-literal=POSTGRES_PORT=5432 \
  --from-literal=POSTGRES_DB=dataindex \
  --from-literal=POSTGRES_USER=dataindex \
  --from-literal=POSTGRES_PASSWORD=dataindex123

# 3. Deploy FluentBit DaemonSet
kubectl apply -f kubernetes/daemonset.yaml

# 4. Verify deployment
kubectl get pods -n fluent-bit
kubectl logs -n fluent-bit -l app=fluent-bit --tail=20
```

### Verify Event Ingestion

```bash
# 1. Deploy a test workflow
kubectl apply -f ../../integration-tests/workflows/hello-world.yaml

# 2. Check FluentBit captured the events
kubectl logs -n fluent-bit -l app=fluent-bit | grep "workflow.started"

# 3. Verify data in PostgreSQL
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT id, name, status FROM workflow_instances ORDER BY start DESC LIMIT 5;"

# 4. Query via GraphQL API
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status } }"}'
```

## Monitoring

### FluentBit Metrics

```bash
# Check FluentBit is processing events
kubectl logs -n fluent-bit -l app=fluent-bit | grep "workflow.instance"

# Check for errors
kubectl logs -n fluent-bit -l app=fluent-bit | grep -i error

# Check retry attempts (indicates PostgreSQL connectivity issues)
kubectl logs -n fluent-bit -l app=fluent-bit | grep -i retry
```

### PostgreSQL Monitoring

```bash
# Count workflow instances
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT status, COUNT(*) FROM workflow_instances GROUP BY status;"

# Count task executions
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM task_executions;"

# Check latest events
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT id, name, status, start FROM workflow_instances ORDER BY start DESC LIMIT 10;"
```

## Troubleshooting

### No events in PostgreSQL

**Check 1**: FluentBit is running
```bash
kubectl get pods -n fluent-bit
```

**Check 2**: FluentBit can read logs
```bash
kubectl exec -n fluent-bit <pod-name> -- ls -la /var/log/containers/ | grep workflow
```

**Check 3**: FluentBit is parsing JSON
```bash
kubectl logs -n fluent-bit -l app=fluent-bit | grep "workflow.started"
```

**Check 4**: PostgreSQL connectivity
```bash
kubectl exec -n fluent-bit <pod-name> -- \
  nc -zv postgresql.postgresql.svc.cluster.local 5432
```

### Duplicate key errors

FluentBit uses `ON CONFLICT (id) DO UPDATE` for workflow.started events. If you see duplicate key errors, the UPSERT logic may not be working.

**Solution**: Check PostgreSQL logs for constraint violations
```bash
kubectl logs -n postgresql postgresql-0 | grep -i "duplicate key"
```

### Events missing fields

**Check**: Lua flatten script is working
```bash
# Enable FluentBit debug logging
# Edit configmap, set Log_Level debug
kubectl edit configmap fluent-bit-config -n fluent-bit

# Restart pods
kubectl rollout restart daemonset/fluent-bit -n fluent-bit

# Check flattened output
kubectl logs -n fluent-bit -l app=fluent-bit | grep flatten
```

## Pros and Cons

### ✅ Pros
- **Simple**: Direct log-to-database pipeline
- **Low latency**: No intermediate staging
- **Easy debugging**: Direct SQL in FluentBit config
- **Stateless**: FluentBit doesn't maintain state
- **Atomic operations**: UPSERT guarantees consistency

### ❌ Cons
- **Schema coupling**: FluentBit config tied to PostgreSQL schema
- **No event replay**: Events are not preserved after processing
- **Limited scalability**: Direct database writes can create bottlenecks
- **No event history**: Can't query historical event stream
- **Rigid**: Schema changes require FluentBit config updates

## When to Use

- **Development/testing** environments
- **Small-scale deployments** (< 100 workflow executions/sec)
- **Simple architectures** without event replay requirements
- **Direct SQL control** needed for specific use cases

## Migration Path

If you need event replay or better scalability:
- **Mode 2 (Elasticsearch)**: Better for search and analytics
- **Mode 3 (Kafka)**: Better for event replay and multiple consumers
