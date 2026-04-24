# FluentBit Configurations for Data Index

This directory contains FluentBit configurations for ingesting Quarkus Flow structured logging events into Data Index.

## Overview

Quarkus Flow emits workflow and task execution events as structured JSON logs. FluentBit captures these logs and routes them to the appropriate storage backend based on the deployment mode.

### Event Flow

```
Quarkus Flow Workflow Pod
    ↓ (emits structured JSON logs)
Container stdout/stderr
    ↓ (captures)
FluentBit DaemonSet
    ↓ (parses & routes by mode)
Storage Backend (PostgreSQL / Elasticsearch / Kafka)
    ↓ (queries/transforms)
Data Index GraphQL API
```

## Deployment Modes

### Mode 1: PostgreSQL Trigger-based Normalization
**Directory**: `mode1-postgresql-triggers/`

**Pipeline**: FluentBit → PostgreSQL raw tables → Triggers → Normalized tables → GraphQL queries

**How it works**:
1. FluentBit tails `/tmp/quarkus-flow-events.log` from workflow pods
2. Routes events by type using `rewrite_tag` filter
3. Inserts into `workflow_events_raw` or `task_events_raw` (tag, time, data JSONB)
4. PostgreSQL BEFORE INSERT triggers extract fields from JSONB and UPSERT into normalized tables
5. GraphQL API queries normalized tables via JPA

**Pros**:
- Real-time normalization (no polling delays)
- No Event Processor service needed
- Idempotent and handles out-of-order events
- Raw events preserved for debugging
- Simpler architecture

**Cons**:
- Normalization logic in database (PostgreSQL-specific)
- Schema changes require trigger updates
- All normalization happens synchronously on INSERT

**Use case**: Production deployments, all scale levels

---

### Mode 2: Elasticsearch
**Directory**: `mode2-elasticsearch/`

**Pipeline**: FluentBit → Elasticsearch raw indices → Transform → Normalized indices → GraphQL queries

**How it works**:
1. FluentBit tails container logs
2. Parses JSON events
3. Sends to Elasticsearch raw indices (`workflow-instance-events-raw`, `task-execution-events-raw`)
4. Elasticsearch Transform aggregates into normalized indices (`workflow-instances`, `task-executions`)
5. GraphQL API queries normalized indices

**Pros**:
- Full-text search capabilities
- Event history preserved in raw indices
- Schema decoupled from ingestion
- Horizontal scalability

**Cons**:
- More complex architecture
- Higher resource usage
- Transform pipeline adds latency

**Use case**: Production deployments with search requirements

---

### Mode 3: Kafka + PostgreSQL
**Directory**: `mode3-kafka-postgresql/`

**Pipeline**: FluentBit → Kafka topics → Consumer → PostgreSQL tables → GraphQL queries

**How it works**:
1. FluentBit tails container logs
2. Parses JSON events
3. Sends to Kafka topics (`workflow-instance-events`, `task-execution-events`)
4. Kafka Consumer processes events and writes to PostgreSQL tables
5. GraphQL API queries tables via JPA

**Pros**:
- Event replay capability
- Decoupled ingestion/storage
- Kafka durability guarantees
- Multiple consumers possible

**Cons**:
- Most complex architecture
- Requires Kafka infrastructure
- Higher operational overhead

**Use case**: Production deployments requiring event replay, multiple downstream consumers

---

## Quick Start

### 1. Choose Your Mode

```bash
cd mode1-postgresql-polling/    # Simple, direct PostgreSQL
cd mode2-elasticsearch/         # Elasticsearch with search
cd mode3-kafka-postgresql/      # Kafka event streaming
```

### 2. Deploy to Kubernetes

Each mode directory contains:
- `fluent-bit.conf` - FluentBit configuration
- `parsers.conf` - JSON parser configuration
- `kubernetes/configmap.yaml` - Kubernetes ConfigMap with FluentBit config
- `kubernetes/daemonset.yaml` - Kubernetes DaemonSet to deploy FluentBit

```bash
# Deploy FluentBit ConfigMap
kubectl apply -f kubernetes/configmap.yaml

# Deploy FluentBit DaemonSet
kubectl apply -f kubernetes/daemonset.yaml
```

### 3. Verify Deployment

```bash
# Check FluentBit pods
kubectl get pods -n fluent-bit

# View FluentBit logs
kubectl logs -n fluent-bit -l app=fluent-bit --tail=50

# Check for errors
kubectl logs -n fluent-bit -l app=fluent-bit | grep -i error
```

## Event Types

FluentBit processes these Serverless Workflow 1.0.0 events:

### Workflow Instance Events
- `io.serverlessworkflow.workflow.started.v1`
- `io.serverlessworkflow.workflow.completed.v1`
- `io.serverlessworkflow.workflow.faulted.v1`
- `io.serverlessworkflow.workflow.cancelled.v1`
- `io.serverlessworkflow.workflow.suspended.v1`
- `io.serverlessworkflow.workflow.resumed.v1`
- `io.serverlessworkflow.workflow.status-changed.v1`

### Task Execution Events
- `io.serverlessworkflow.task.started.v1`
- `io.serverlessworkflow.task.completed.v1`
- `io.serverlessworkflow.task.faulted.v1`

## Common Configuration

All modes share:

### JSON Parser (`parsers.conf`)
```
[PARSER]
    Name   json
    Format json
    Time_Key timestamp
    Time_Format %Y-%m-%dT%H:%M:%S.%L%z
```

### Log Source
FluentBit tails container stdout/stderr from:
- Kubernetes: `/var/log/containers/*_<namespace>_<pod-name>*.log`
- Docker: `/var/lib/docker/containers/*/*.log`

### Environment Variables
Each mode uses environment variables for connection configuration:
- `POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB` (Mode 1, 3)
- `ELASTICSEARCH_HOST`, `ELASTICSEARCH_PORT` (Mode 2)
- `KAFKA_BROKERS` (Mode 3)

## Troubleshooting

### FluentBit not capturing logs
```bash
# Check FluentBit is running
kubectl get pods -n fluent-bit

# Check FluentBit can read log files
kubectl exec -n fluent-bit <pod-name> -- ls -la /var/log/containers/

# Enable debug logging
# Edit configmap, set Log_Level debug, restart pods
```

### Events not reaching storage
```bash
# Check FluentBit output logs
kubectl logs -n fluent-bit -l app=fluent-bit | grep -A 10 OUTPUT

# Test connectivity to storage backend
kubectl exec -n fluent-bit <pod-name> -- nc -zv <postgres-host> 5432
```

### Performance issues
```bash
# Check FluentBit memory/CPU usage
kubectl top pods -n fluent-bit

# Check backpressure (growing buffer)
kubectl logs -n fluent-bit -l app=fluent-bit | grep -i "retry"
```

## See Also

- [Quarkus Flow Structured Logging](https://quarkiverse.github.io/quarkiverse-docs/quarkus-flow/dev/logging.html)
- [FluentBit Documentation](https://docs.fluentbit.io/)
- [Data Index Architecture](../../docs/architecture.md)
