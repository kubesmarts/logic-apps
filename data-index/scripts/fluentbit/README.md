# FluentBit Configurations for Data Index

This directory contains FluentBit configurations for ingesting Quarkus Flow structured logging events into Data Index.

## Overview

Quarkus Flow emits workflow and task execution events as structured JSON logs. FluentBit captures these logs and routes them to the appropriate storage backend based on the deployment mode.

### Event Flow

```
Quarkus Flow Workflow Pod
    ↓ (emits structured JSON logs to stdout)
Container stdout/stderr
    ↓ (Kubernetes captures to /var/log/containers/)
FluentBit DaemonSet
    ↓ (tails, parses, routes)
Storage Backend (PostgreSQL or Elasticsearch)
    ↓ (normalizes: triggers or transforms)
Data Index GraphQL API
```

## Deployment Modes

### PostgreSQL Mode (Production Ready)
**Directory**: `mode1-postgresql-triggers/`

**Status**: ✅ Production Ready

**Pipeline**: FluentBit → PostgreSQL raw tables → BEFORE INSERT triggers → Normalized tables → GraphQL API

**Architecture:**

FluentBit's [PostgreSQL output](https://docs.fluentbit.io/manual/data-pipeline/outputs/postgresql) requires pre-defined schemas. We use:

1. **Raw tables** - Fixed schema (`tag`, `time`, `data JSONB`) for FluentBit
2. **Triggers** - Extract fields from JSONB, normalize in real-time

See [PostgreSQL Mode Architecture](../../data-index-docs/modules/ROOT/pages/architecture/postgresql-mode.adoc) for details.

#### How it works:
1. FluentBit tails `/var/log/containers/*_workflows_*.log` (Kubernetes container logs)
2. Parses CRI format and extracts JSON events
3. Routes events by type (workflow vs task) using Lua script
4. Inserts into `workflow_events_raw` or `task_events_raw` (tag, time, data JSONB)
5. PostgreSQL BEFORE INSERT triggers fire immediately (< 1ms):
   - Extract fields from JSONB
   - UPSERT into normalized tables (`workflow_instances`, `task_instances`)
   - Handle out-of-order events with COALESCE
6. GraphQL API queries normalized tables via JPA

#### Pros:
- ✅ Real-time normalization (< 1ms trigger execution)
- ✅ No Event Processor service needed
- ✅ Idempotent (handles duplicates and out-of-order events)
- ✅ Raw events preserved for debugging
- ✅ Simple architecture
- ✅ ACID transaction guarantees
- ✅ Works within FluentBit's output plugin constraints

#### Cons:
- ⚠️ PostgreSQL-specific (trigger logic not portable)
- ⚠️ Throughput limited to ~50K workflows/day
- ⚠️ Schema changes require trigger updates
- ⚠️ Normalization logic in SQL (harder to test than application code)

**Use case**: Production deployments with moderate throughput, local development

**Configuration files**:
- `fluent-bit.conf` - Input (tail), filters (Lua), output (PostgreSQL)
- `parsers.conf` - CRI parser for Kubernetes logs
- `flatten-event.lua` - Routes workflow vs task events
- `kubernetes/configmap.yaml` - Generated from source files
- `kubernetes/daemonset.yaml` - FluentBit deployment

---

### Elasticsearch Mode (In Development)
**Directory**: `mode2-elasticsearch/`

**Status**: 📋 Planned (backend not yet implemented)

**Pipeline**: FluentBit → Elasticsearch raw indices → ES Transform → Normalized indices → GraphQL API

**How it works**:
1. FluentBit tails container logs
2. Parses JSON events
3. Sends to Elasticsearch raw indices (`workflow-events`, `task-events`)
4. Elasticsearch Transform (Painless scripts) processes asynchronously (~1s):
   - Aggregate events by instance ID
   - Extract and normalize fields
   - Write to normalized indices (`workflow-instances`, `task-executions`)
5. GraphQL API queries normalized indices via Elasticsearch client

**Pros**:
- ✅ Full-text search capabilities
- ✅ High throughput (100K+ workflows/day)
- ✅ Horizontal scalability
- ✅ Event history preserved

**Cons**:
- ⚠️ Higher complexity (ES cluster, transforms)
- ⚠️ Transform latency (~1s)
- ⚠️ Eventual consistency (no ACID)

**Use case**: Production deployments requiring full-text search or high throughput

---

## Quick Start

### Using Helper Scripts (Recommended)

```bash
# From data-index/scripts/fluentbit/

# Deploy PostgreSQL mode
./deploy-fluentbit.sh postgresql

# Deploy Elasticsearch mode (when available)
./deploy-fluentbit.sh elasticsearch
```

The helper script:
1. Generates ConfigMap from source files (`generate-configmap.sh`)
2. Applies ConfigMap to `logging` namespace
3. Deploys DaemonSet

### Manual Deployment

```bash
# 1. Generate ConfigMap from source files
cd mode1-postgresql-triggers
../generate-configmap.sh

# 2. Apply to Kubernetes
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml

# 3. Verify
kubectl get pods -n logging
kubectl logs -n logging -l app=workflows-fluent-bit
```

## Configuration Files

### FluentBit Configuration (`fluent-bit.conf`)

**Input section** - Tail Kubernetes container logs:
```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_workflows_*.log
    Parser            cri
    Tag               kube.*
    Refresh_Interval  1
    Mem_Buf_Limit     20MB
```

**Filter section** - Parse and route events:
```conf
[FILTER]
    Name    parser
    Match   kube.*
    Key_Name log
    Parser  json

[FILTER]
    Name    lua
    Match   kube.*
    script  flatten-event.lua
    call    flatten_event
```

**Output section** - PostgreSQL:
```conf
[OUTPUT]
    Name      pgsql
    Match     workflow.*
    Host      ${POSTGRES_HOST}
    Port      ${POSTGRES_PORT}
    Database  ${POSTGRES_DB}
    Table     workflow_events_raw
    User      ${POSTGRES_USER}
    Password  ${POSTGRES_PASSWORD}
```

### Parser Configuration (`parsers.conf`)

**CRI parser** (for Kubernetes containerd/CRI-O):
```conf
[PARSER]
    Name   cri
    Format regex
    Regex  ^(?<time>[^ ]+) (?<stream>stdout|stderr) (?<logtag>[^ ]*) (?<log>.*)$
    Time_Key time
    Time_Format %Y-%m-%dT%H:%M:%S.%LZ
```

**JSON parser** (for structured logging events):
```conf
[PARSER]
    Name   json
    Format json
```

### Lua Script (`flatten-event.lua`)

Routes events to correct tables based on event type:
- `workflow.*` events → `workflow_events_raw` table
- `task.*` events → `task_events_raw` table

## Event Types

FluentBit processes Serverless Workflow 1.0.0 CloudEvents:

### Workflow Instance Events
- `io.serverlessworkflow.workflow.started.v1`
- `io.serverlessworkflow.workflow.completed.v1`
- `io.serverlessworkflow.workflow.faulted.v1`
- `io.serverlessworkflow.workflow.cancelled.v1`
- `io.serverlessworkflow.workflow.suspended.v1`
- `io.serverlessworkflow.workflow.resumed.v1`

### Task Execution Events
- `io.serverlessworkflow.task.started.v1`
- `io.serverlessworkflow.task.completed.v1`
- `io.serverlessworkflow.task.faulted.v1`

## Environment Variables

**PostgreSQL mode**:
- `POSTGRES_HOST` - PostgreSQL hostname (default: `postgresql.postgresql.svc.cluster.local`)
- `POSTGRES_PORT` - PostgreSQL port (default: `5432`)
- `POSTGRES_DB` - Database name (default: `dataindex`)
- `POSTGRES_USER` - Database user (default: `dataindex`)
- `POSTGRES_PASSWORD` - Database password (default: `dataindex123`)
- `WORKFLOW_NAMESPACE` - Namespace to tail logs from (default: `workflows`)

**Elasticsearch mode** (when available):
- `ELASTICSEARCH_HOST` - Elasticsearch hostname
- `ELASTICSEARCH_PORT` - Elasticsearch port (default: `9200`)

## Troubleshooting

### FluentBit Pods Not Starting

```bash
# Check pod status
kubectl get pods -n logging

# Check events
kubectl describe pod -n logging <fluentbit-pod>

# Common issues:
# - ConfigMap not found → Apply configmap.yaml first
# - Image pull errors → Check image name in daemonset.yaml
```

### No Events Reaching Storage

```bash
# 1. Check FluentBit is tailing logs
kubectl logs -n logging -l app=workflows-fluent-bit | grep "inotify_fs_add"

# 2. Check parser errors
kubectl logs -n logging -l app=workflows-fluent-bit | grep -i "parser.*error"

# 3. Check output errors
kubectl logs -n logging -l app=workflows-fluent-bit | grep -i "output.*error"

# 4. Verify workflow pods are in correct namespace
kubectl get pods -n workflows

# 5. Check PostgreSQL connectivity
kubectl exec -n logging <fluentbit-pod> -- nc -zv postgresql.postgresql.svc.cluster.local 5432
```

### Wrong Parser (CRI vs Docker)

KIND and modern Kubernetes use **CRI parser** (containerd).

If events aren't parsed:
```bash
# Check container runtime
kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.containerRuntimeVersion}'

# If containerd/cri-o → Use CRI parser (default)
# If docker → Change parser to 'docker' in fluent-bit.conf
```

See [FluentBit Configuration Guide](../../data-index-docs/modules/ROOT/pages/deployment/fluentbit-config.adoc) for details.

### Events Delayed

Normal latency: 5-10 seconds end-to-end

Components:
- FluentBit flush interval: 1 second
- PostgreSQL trigger: < 1ms
- Network latency: varies

If > 10 seconds:
```bash
# Check FluentBit buffer
kubectl logs -n logging -l app=workflows-fluent-bit | grep -i "retry\|buffer"

# Check PostgreSQL performance
kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -c "SELECT COUNT(*) FROM workflow_events_raw;"
```

## Monitoring

### FluentBit Metrics

FluentBit exposes metrics on port 2020:

```bash
kubectl port-forward -n logging <fluentbit-pod> 2020:2020
curl http://localhost:2020/api/v1/metrics
curl http://localhost:2020/api/v1/metrics/prometheus
```

**Key metrics**:
- `fluentbit_input_bytes_total` - Bytes read from logs
- `fluentbit_output_proc_bytes_total` - Bytes sent to output
- `fluentbit_output_retries_total` - Output retry count

### Health Check

```bash
# Check all FluentBit pods are running
kubectl get pods -n logging -l app=workflows-fluent-bit

# Check logs for errors
kubectl logs -n logging -l app=workflows-fluent-bit --tail=100 | grep -i error

# Verify events are flowing
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT COUNT(*), MAX(time) FROM workflow_events_raw;"
```

## See Also

- [FluentBit Configuration Guide](../../data-index-docs/modules/ROOT/pages/deployment/fluentbit-config.adoc)
- [Event Reliability Guide](../../data-index-docs/modules/ROOT/pages/operations/event-reliability.adoc)
- [KIND Deployment Guide](../../data-index-docs/modules/ROOT/pages/deployment/kind-local.adoc)
- [Quarkus Flow Structured Logging](https://docs.quarkiverse.io/quarkus-flow/dev/structured-logging.html)
- [FluentBit Documentation](https://docs.fluentbit.io/)
