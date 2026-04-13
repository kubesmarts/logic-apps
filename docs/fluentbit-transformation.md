# FluentBit Transformation Rules for Quarkus Flow Events

## Overview

FluentBit processes Quarkus Flow structured logging events (JSON format emitted to stdout) and writes them to PostgreSQL tables that Data Index queries.

**Architecture**:
```
Quarkus Flow Runtime → stdout (JSON events) → FluentBit → PostgreSQL Event Tables
                                                                ↓ (triggers)
                                                          State Tables → Data Index (GraphQL)
```

## Design Philosophy

**Simple, reliable, performant**:

1. **FluentBit responsibility**: Parse JSON logs, INSERT into event tables
2. **PostgreSQL responsibility**: Event sourcing + state materialization via triggers
3. **Data Index responsibility**: Read-only GraphQL queries

**Why triggers instead of FluentBit scripts**:
- ✅ **No shell scripts to deploy** - just SQL schema with triggers
- ✅ **Transactional consistency** - event + state update in single transaction
- ✅ **Better performance** - triggers run in-process in PostgreSQL
- ✅ **Easier testing** - standard SQL, deterministic behavior
- ✅ **Better security** - no shell execution with database credentials
- ✅ **Simpler operations** - FluentBit config is just JSON parsing + INSERT

## Event Processing Flow

1. **Quarkus Flow** emits structured JSON logs to stdout
2. **FluentBit** captures stdout, parses JSON, routes to event tables
3. **PostgreSQL** receives INSERTs into event tables (workflow_events, task_events)
4. **PostgreSQL triggers** automatically materialize current state tables (workflow_instances, task_executions)
5. **Data Index** queries PostgreSQL via GraphQL (read-only)

## Event Types and Trigger Processing

### Workflow Lifecycle Events

| Event Type | FluentBit Action | PostgreSQL Trigger Action |
|------------|-----------------|--------------------------|
| `workflow.instance.started` | INSERT into `workflow_events` | INSERT into `workflow_instances` (new row) |
| `workflow.instance.completed` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, end_time, output_data) |
| `workflow.instance.failed` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, end_time, error_message, error_details) |
| `workflow.instance.cancelled` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, end_time) |
| `workflow.instance.suspended` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, last_update_time) |
| `workflow.instance.resumed` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, last_update_time) |
| `workflow.instance.status.changed` | INSERT into `workflow_events` | UPDATE `workflow_instances` (status, last_update_time) |

### Task Lifecycle Events

| Event Type | FluentBit Action | PostgreSQL Trigger Action |
|------------|-----------------|--------------------------|
| `workflow.task.started` | INSERT into `task_events` | INSERT into `task_executions` (new row) |
| `workflow.task.completed` | INSERT into `task_events` | UPDATE `task_executions` (status, end_time, output_data) |
| `workflow.task.failed` | INSERT into `task_events` | UPDATE `task_executions` (status, end_time, error_message, error_details) |
| `workflow.task.cancelled` | INSERT into `task_events` | UPDATE `task_executions` (status, end_time) |
| `workflow.task.suspended` | INSERT into `task_events` | UPDATE `task_executions` (status, last_update_time) |
| `workflow.task.resumed` | INSERT into `task_events` | UPDATE `task_executions` (status, last_update_time) |
| `workflow.task.retried` | INSERT into `task_events` | UPDATE `task_executions` (retry_count + 1, last_update_time) |

**Key design principle**: FluentBit only appends to event tables. PostgreSQL triggers maintain current state tables automatically.

## FluentBit Configuration

FluentBit configuration is simple: parse JSON logs and INSERT into event tables. PostgreSQL triggers handle the rest.

### Input Configuration

```ini
[INPUT]
    Name              tail
    Path              /var/log/containers/*quarkus-flow*.log
    Parser            docker
    Tag               quarkus.flow
    Refresh_Interval  5
    Mem_Buf_Limit     5MB
    Skip_Long_Lines   On
```

### Filter - Parse JSON Events

```ini
[FILTER]
    Name         parser
    Match        quarkus.flow
    Key_Name     log
    Parser       json
    Reserve_Data On
```

### Filter - Route by Event Type

```ini
[FILTER]
    Name    rewrite_tag
    Match   quarkus.flow
    Rule    eventType ^workflow\.instance\. workflow.event false
    Rule    eventType ^workflow\.task\. task.event false
```

### Output - PostgreSQL (Direct INSERT)

```ini
# Workflow Events - Direct INSERT (trigger handles workflow_instances materialization)
[OUTPUT]
    Name              pgsql
    Match             workflow.event
    Host              ${POSTGRES_HOST}
    Port              5432
    User              ${POSTGRES_USER}
    Password          ${POSTGRES_PASSWORD}
    Database          dataindex
    Table             workflow_events
    Timestamp_Key     event_timestamp
    Async             false

# Task Events - Direct INSERT (trigger handles task_executions materialization)
[OUTPUT]
    Name              pgsql
    Match             task.event
    Host              ${POSTGRES_HOST}
    Port              5432
    User              ${POSTGRES_USER}
    Password          ${POSTGRES_PASSWORD}
    Database          dataindex
    Table             task_events
    Timestamp_Key     event_timestamp
    Async             false
```

### Complete ConfigMap Example

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
  namespace: openshift-serverless-logic
data:
  fluent-bit.conf: |
    [SERVICE]
        Flush        5
        Daemon       Off
        Log_Level    info

    [INPUT]
        Name              tail
        Path              /var/log/containers/*quarkus-flow*.log
        Parser            docker
        Tag               quarkus.flow
        Refresh_Interval  5
        Mem_Buf_Limit     5MB
        Skip_Long_Lines   On

    [FILTER]
        Name         parser
        Match        quarkus.flow
        Key_Name     log
        Parser       json
        Reserve_Data On

    [FILTER]
        Name    rewrite_tag
        Match   quarkus.flow
        Rule    eventType ^workflow\.instance\. workflow.event false
        Rule    eventType ^workflow\.task\. task.event false

    [OUTPUT]
        Name              pgsql
        Match             workflow.event
        Host              ${POSTGRES_HOST}
        Port              5432
        User              ${POSTGRES_USER}
        Password          ${POSTGRES_PASSWORD}
        Database          dataindex
        Table             workflow_events
        Timestamp_Key     event_timestamp
        Async             false

    [OUTPUT]
        Name              pgsql
        Match             task.event
        Host              ${POSTGRES_HOST}
        Port              5432
        User              ${POSTGRES_USER}
        Password          ${POSTGRES_PASSWORD}
        Database          dataindex
        Table             task_events
        Timestamp_Key     event_timestamp
        Async             false

  parsers.conf: |
    [PARSER]
        Name   json
        Format json
        Time_Key timestamp
        Time_Format %Y-%m-%dT%H:%M:%S.%L
```

## PostgreSQL Column Mapping

FluentBit uses the PostgreSQL output plugin which automatically maps JSON fields to table columns. You need to configure the column mapping in the pgsql output configuration.

### Example Column Mapping Configuration

```ini
[OUTPUT]
    Name              pgsql
    Match             workflow.event
    Host              ${POSTGRES_HOST}
    Port              5432
    User              ${POSTGRES_USER}
    Password          ${POSTGRES_PASSWORD}
    Database          dataindex
    Table             workflow_events
    # Map JSON fields to columns (field_name:column_name)
    Columns           instance_id:instance_id,event_type:event_type,timestamp:event_timestamp,workflow_namespace:workflow_namespace,workflow_name:workflow_name,workflow_version:workflow_version,status:status,start_time:start_time,end_time:end_time,last_update_time:last_update_time,input:input_data,output:output_data,error:error_details
    Async             false
```

**Note**: The actual column mapping syntax depends on your FluentBit pgsql plugin version. Consult the FluentBit documentation for the exact syntax. The triggers will handle materializing the current state tables from these event inserts.

## Field Mapping Reference

### Quarkus Flow Event → workflow_instances table

| Event Field | DB Column | Notes |
|------------|-----------|-------|
| `instanceId` | `instance_id` | Primary key |
| `workflowNamespace` | `workflow_namespace` | |
| `workflowName` | `workflow_name` | |
| `workflowVersion` | `workflow_version` | Optional |
| `status` | `status` | RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED |
| `startTime` | `start_time` | ISO 8601 timestamp |
| `endTime` | `end_time` | Only on completed/failed/cancelled |
| `lastUpdateTime` | `last_update_time` | Updated on every event |
| `input` | `input_data` | JSONB (may be truncated) |
| `output` | `output_data` | JSONB (may be truncated) |
| `error.message` | `error_message` | Text extracted from error object |
| `error` | `error_details` | Full error object as JSONB |

### Quarkus Flow Event → task_executions table

| Event Field | DB Column | Notes |
|------------|-----------|-------|
| `taskExecutionId` | `task_execution_id` | Primary key (UUID) |
| `instanceId` | `instance_id` | Foreign key to workflow_instances |
| `taskName` | `task_name` | |
| `taskPosition` | `task_position` | Sequential task number |
| `status` | `status` | RUNNING, COMPLETED, FAILED, CANCELLED, SUSPENDED |
| `startTime` | `start_time` | ISO 8601 timestamp |
| `endTime` | `end_time` | Only on completed/failed/cancelled |
| `lastUpdateTime` | `last_update_time` | Updated on every event |
| `input` | `input_data` | JSONB (may be truncated) |
| `output` | `output_data` | JSONB (may be truncated) |
| `error.message` | `error_message` | Text extracted from error object |
| `error` | `error_details` | Full error object as JSONB |

## Error Handling

### Duplicate Events
- **workflow_events** / **task_events**: Always INSERT (append-only log, duplicates are allowed)
- **workflow_instances** / **task_executions**: Triggers use `ON CONFLICT DO NOTHING` for idempotency
- If the same event arrives twice, it's logged twice in event tables but only updates state tables once

### Missing Fields
- Database columns allow NULL where appropriate
- Triggers use `COALESCE()` and conditional logic to handle missing optional fields
- FluentBit should send all fields present in the JSON event

### Failed Writes
- FluentBit `Async false` ensures synchronous writes
- Failed writes logged to FluentBit error output
- PostgreSQL trigger failures will cause transaction rollback (event won't be persisted)
- Monitor FluentBit error logs for write failures

## Monitoring

### FluentBit Metrics
- Events processed per second
- PostgreSQL write latency
- Failed transformations count

### PostgreSQL Metrics
- Table row counts (growth rate)
- Index hit ratio
- Query performance (p50, p95, p99)

## Deployment

### Components

1. **PostgreSQL Database** with schema and triggers (from database-schema-v1.0.0.sql)
2. **FluentBit DaemonSet** in Kubernetes
3. **Data Index Service** (GraphQL query service)

### Kubernetes Resources

```yaml
---
apiVersion: v1
kind: Secret
metadata:
  name: dataindex-postgres-credentials
  namespace: openshift-serverless-logic
type: Opaque
stringData:
  POSTGRES_HOST: "postgresql.dataindex.svc.cluster.local"
  POSTGRES_USER: "dataindex"
  POSTGRES_PASSWORD: "changeme"
  POSTGRES_DB: "dataindex"

---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: fluent-bit
  namespace: openshift-serverless-logic
spec:
  selector:
    matchLabels:
      app: fluent-bit
  template:
    metadata:
      labels:
        app: fluent-bit
    spec:
      serviceAccountName: fluent-bit
      containers:
      - name: fluent-bit
        image: fluent/fluent-bit:2.2
        envFrom:
        - secretRef:
            name: dataindex-postgres-credentials
        volumeMounts:
        - name: config
          mountPath: /fluent-bit/etc/
        - name: varlog
          mountPath: /var/log
          readOnly: true
        - name: varlibdockercontainers
          mountPath: /var/lib/docker/containers
          readOnly: true
      volumes:
      - name: config
        configMap:
          name: fluent-bit-config
      - name: varlog
        hostPath:
          path: /var/log
      - name: varlibdockercontainers
        hostPath:
          path: /var/lib/docker/containers
```

### Deployment Flow

1. **Operator** creates PostgreSQL database with schema (including triggers)
2. **Operator** deploys FluentBit DaemonSet with ConfigMap
3. **Operator** deploys Data Index GraphQL service
4. **Users** deploy Quarkus Flow workflows
5. **FluentBit** automatically captures logs and writes to PostgreSQL
6. **PostgreSQL triggers** maintain current state tables
7. **Data Index** serves GraphQL queries

No shell scripts, no custom images, just standard components with configuration.
