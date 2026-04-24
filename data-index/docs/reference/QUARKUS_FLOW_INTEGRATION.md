# Quarkus Flow Structured Logging Analysis

**Date**: 2026-04-15  
**Purpose**: Document the structured logging format emitted by Quarkus Flow runtime to inform Data Index v1.0.0 domain model design

## Overview

Quarkus Flow emits structured JSON logs for all workflow lifecycle events. These logs are ingested by FluentBit and written to PostgreSQL, where Data Index queries them via GraphQL.

Understanding this event structure is critical for:
1. Designing the correct domain model fields
2. Creating appropriate adapters for v0.8 compatibility
3. Ensuring GraphQL queries can return the expected data

## Event Sources

**Implementation**: `io.quarkiverse.flow.structuredlogging.EventFormatter`

**Listener**: `io.quarkiverse.flow.structuredlogging.StructuredLoggingListener`

**SDK Events**: `io.serverlessworkflow.impl.lifecycle.*Event`

## Event Types

### Workflow Instance Events

| Event | CloudEvent Type | Description |
|-------|----------------|-------------|
| `workflow.instance.started` | `io.serverlessworkflow.workflow.started.v1` | Workflow execution started |
| `workflow.instance.completed` | `io.serverlessworkflow.workflow.completed.v1` | Workflow completed successfully |
| `workflow.instance.faulted` | `io.serverlessworkflow.workflow.faulted.v1` | Workflow failed with error |
| `workflow.instance.cancelled` | `io.serverlessworkflow.workflow.cancelled.v1` | Workflow cancelled |
| `workflow.instance.suspended` | `io.serverlessworkflow.workflow.suspended.v1` | Workflow paused |
| `workflow.instance.resumed` | `io.serverlessworkflow.workflow.resumed.v1` | Workflow resumed |
| `workflow.instance.status.changed` | `io.serverlessworkflow.workflow.status-changed.v1` | Status changed |

### Task Events

| Event | CloudEvent Type | Description |
|-------|----------------|-------------|
| `workflow.task.started` | `io.serverlessworkflow.task.started.v1` | Task execution started |
| `workflow.task.completed` | `io.serverlessworkflow.task.completed.v1` | Task completed successfully |
| `workflow.task.faulted` | `io.serverlessworkflow.task.faulted.v1` | Task failed with error |
| `workflow.task.cancelled` | `io.serverlessworkflow.task.cancelled.v1` | Task cancelled |
| `workflow.task.suspended` | `io.serverlessworkflow.task.suspended.v1` | Task suspended |
| `workflow.task.resumed` | `io.serverlessworkflow.task.resumed.v1` | Task resumed |
| `workflow.task.retried` | `io.serverlessworkflow.task.retried.v1` | Task retried |

## Workflow Instance Event Structure

### Common Fields (All Workflow Events)

```json
{
  "eventType": "io.serverlessworkflow.workflow.started.v1",
  "timestamp": "2026-04-15T15:30:00Z",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0"
}
```

### workflow.instance.started

```json
{
  "eventType": "io.serverlessworkflow.workflow.started.v1",
  "timestamp": "2026-04-15T15:30:00Z",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0",
  "status": "RUNNING",
  "startTime": "2026-04-15T15:30:00Z",
  "input": { "orderId": "12345" }
}
```

**Domain Model Impact**:
- WorkflowInstanceMeta needs: `id`, `workflowNamespace`, `workflowName`, `version`
- WorkflowInstanceMeta needs: `status` (enum), `start` (ZonedDateTime)
- WorkflowInstance needs: `variables` (JsonNode) - stores input/output

### workflow.instance.completed

```json
{
  "eventType": "io.serverlessworkflow.workflow.completed.v1",
  "timestamp": "2026-04-15T15:30:30Z",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0",
  "status": "COMPLETED",
  "endTime": "2026-04-15T15:30:30Z",
  "output": { "result": "success" }
}
```

**Domain Model Impact**:
- WorkflowInstanceMeta needs: `end` (ZonedDateTime)
- Output merged into `variables` field

### workflow.instance.faulted ⭐ CRITICAL

```json
{
  "eventType": "io.serverlessworkflow.workflow.faulted.v1",
  "timestamp": "2026-04-15T15:30:15Z",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0",
  "status": "FAULTED",
  "endTime": "2026-04-15T15:30:15Z",
  "error": {
    "type": "system",
    "title": "Service unavailable",
    "detail": "Failed to connect to payment service\nat com.example...\n",
    "status": 503,
    "instance": "uuid-error-5678"
  },
  "input": { "orderId": "12345" }
}
```

**Domain Model Impact**:
- WorkflowInstanceError needs: `type`, `title`, `detail`, `status`, `instance`
- Aligns with SW 1.0.0 Error spec
- Error types: `system`, `business`, `timeout`, `communication`

### workflow.instance.cancelled

```json
{
  "eventType": "io.serverlessworkflow.workflow.cancelled.v1",
  "timestamp": "2026-04-15T15:30:20Z",
  "instanceId": "uuid-1234",
  "status": "CANCELLED",
  "endTime": "2026-04-15T15:30:20Z"
}
```

### workflow.instance.suspended

```json
{
  "eventType": "io.serverlessworkflow.workflow.suspended.v1",
  "timestamp": "2026-04-15T15:30:10Z",
  "instanceId": "uuid-1234",
  "status": "SUSPENDED"
}
```

### workflow.instance.resumed

```json
{
  "eventType": "io.serverlessworkflow.workflow.resumed.v1",
  "timestamp": "2026-04-15T15:30:25Z",
  "instanceId": "uuid-1234",
  "status": "RUNNING"
}
```

### workflow.instance.status.changed

```json
{
  "eventType": "io.serverlessworkflow.workflow.status-changed.v1",
  "timestamp": "2026-04-15T15:30:05Z",
  "instanceId": "uuid-1234",
  "status": "RUNNING",
  "lastUpdateTime": "2026-04-15T15:30:05Z"
}
```

**Domain Model Impact**:
- WorkflowInstanceMeta needs: `lastUpdate` (ZonedDateTime)

## Task Event Structure

### Common Fields (All Task Events)

```json
{
  "eventType": "io.serverlessworkflow.task.started.v1",
  "timestamp": "2026-04-15T15:30:05Z",
  "instanceId": "uuid-1234",
  "taskExecutionId": "generated-uuid",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0"
}
```

**Domain Model Impact**:
- TaskExecution needs: `id` (taskExecutionId), `taskName`, `taskPosition`
- taskPosition is JSONPointer (e.g., "/do/0", "/fork/branches/0/do/1")

### taskExecutionId Generation

**Implementation**:
```java
private String generateTaskExecutionId(TaskEvent event) {
    String composite = event.workflowContext().instanceData().id() +
            ":" + event.taskContext().position().jsonPointer() +
            ":" + event.eventDate().toInstant().toEpochMilli();
    return UUID.nameUUIDFromBytes(composite.getBytes()).toString();
}
```

**Deterministic**: Same instance + task position + timestamp → same UUID

### workflow.task.started

```json
{
  "eventType": "io.serverlessworkflow.task.started.v1",
  "timestamp": "2026-04-15T15:30:05Z",
  "instanceId": "uuid-1234",
  "taskExecutionId": "task-uuid-1",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0",
  "status": "RUNNING",
  "startTime": "2026-04-15T15:30:05Z",
  "input": { "amount": 100 }
}
```

**Domain Model Impact**:
- TaskExecution needs: `enter` (ZonedDateTime for startTime)
- TaskExecution needs: `inputArgs` (JsonNode)

### workflow.task.completed

```json
{
  "eventType": "io.serverlessworkflow.task.completed.v1",
  "timestamp": "2026-04-15T15:30:08Z",
  "instanceId": "uuid-1234",
  "taskExecutionId": "task-uuid-1",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0",
  "status": "COMPLETED",
  "endTime": "2026-04-15T15:30:08Z",
  "output": { "transactionId": "tx-5678" }
}
```

**Domain Model Impact**:
- TaskExecution needs: `exit` (ZonedDateTime for endTime)
- TaskExecution needs: `outputArgs` (JsonNode)

### workflow.task.faulted ⭐ CRITICAL

```json
{
  "eventType": "io.serverlessworkflow.task.faulted.v1",
  "timestamp": "2026-04-15T15:30:07Z",
  "instanceId": "uuid-1234",
  "taskExecutionId": "task-uuid-1",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0",
  "status": "FAILED",
  "endTime": "2026-04-15T15:30:07Z",
  "error": {
    "title": "Connection timeout",
    "type": "java.net.SocketTimeoutException"
  },
  "input": { "amount": 100 }
}
```

**Domain Model Impact**:
- TaskExecution needs: `errorMessage` field
- For tasks, error is simpler (just title + type, no full Error object)
- Input included for debugging context

### workflow.task.cancelled

```json
{
  "eventType": "io.serverlessworkflow.task.cancelled.v1",
  "timestamp": "2026-04-15T15:30:09Z",
  "taskExecutionId": "task-uuid-1",
  "status": "CANCELLED",
  "endTime": "2026-04-15T15:30:09Z"
}
```

### workflow.task.suspended

```json
{
  "eventType": "io.serverlessworkflow.task.suspended.v1",
  "timestamp": "2026-04-15T15:30:06Z",
  "taskExecutionId": "task-uuid-1",
  "status": "SUSPENDED"
}
```

### workflow.task.resumed

```json
{
  "eventType": "io.serverlessworkflow.task.resumed.v1",
  "timestamp": "2026-04-15T15:30:10Z",
  "taskExecutionId": "task-uuid-1",
  "status": "RUNNING"
}
```

### workflow.task.retried

```json
{
  "eventType": "io.serverlessworkflow.task.retried.v1",
  "timestamp": "2026-04-15T15:30:11Z",
  "taskExecutionId": "task-uuid-1",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0"
}
```

**Note**: Retry count not available in event (would need separate tracking)

## Status Enums

### Workflow Status

**SDK Enum**: `io.serverlessworkflow.impl.WorkflowStatus`

**Values**:
- `RUNNING` - Currently executing
- `COMPLETED` - Finished successfully
- `FAULTED` - Failed with error
- `CANCELLED` - Cancelled by user/system
- `SUSPENDED` - Paused/waiting

**Domain Model Mapping** → `WorkflowInstanceStatus`:
```java
PENDING(0)   -> Not in SDK (custom for v0.8 compatibility)
ACTIVE(1)    -> RUNNING
COMPLETED(2) -> COMPLETED
ABORTED(3)   -> CANCELLED
SUSPENDED(4) -> SUSPENDED
ERROR(5)     -> FAULTED
```

### Task Status

**Note**: SDK has no TaskStatus enum, so values are strings in EventFormatter

**Values**:
- `RUNNING`
- `COMPLETED`
- `FAILED`
- `CANCELLED`
- `SUSPENDED`

**Domain Model**: May not need separate enum (can use strings or reuse WorkflowInstanceStatus)

## Configuration Options

**Config Class**: `io.quarkiverse.flow.config.FlowStructuredLoggingConfig`

```properties
quarkus.flow.structured-logging.enabled=true
quarkus.flow.structured-logging.events=workflow.*,workflow.task.faulted
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.flow.structured-logging.include-task-payloads=false
quarkus.flow.structured-logging.include-error-context=true
quarkus.flow.structured-logging.payload-max-size=10240
quarkus.flow.structured-logging.truncate-preview-size=1024
quarkus.flow.structured-logging.stack-trace-max-lines=20
quarkus.flow.structured-logging.log-level=INFO
```

**Impact**: Large payloads may be truncated in logs (shows `__truncated__: true`)

## Domain Model Design Decisions

### ✅ Add `namespace` Field

**Source**: `workflowNamespace` in all workflow events

**Applied To**:
- Workflow.namespace
- WorkflowInstanceMeta.workflowNamespace

### ✅ Error Structure Matches SW 1.0.0 Spec

**Source**: `error` object in faulted events

**Applied To**: WorkflowInstanceError
```java
private String type;        // "system", "business", "timeout", "communication"
private String title;       // Short error summary
private String detail;      // Detailed message/stack trace
private Integer status;     // HTTP status code
private String instance;    // Error instance ID
```

**Reference**: https://github.com/serverlessworkflow/specification/blob/main/dsl-reference.md#error

### ✅ Task Position as JSONPointer

**Source**: `taskPosition` in all task events

**Applied To**: TaskExecution.taskPosition
```java
private String taskPosition;  // "/do/0", "/fork/branches/0/do/1"
```

**Critical**: This is the unique identifier for tasks in SW 1.0.0

### ✅ Task Execution ID Generation

**Source**: Deterministic UUID generation in EventFormatter

**Applied To**: TaskExecution.id (will be generated by FluentBit/Data Index ingestion)

### ✅ No Task Definition Model Needed

**Observation**: Events don't include task type or definition details

**Conclusion**: Tasks are identified by position in workflow document (stored as JSON blob)

**Decision**: Don't create Task.java model (as user suspected)

## Missing Data in Events

### Data NOT Emitted by Quarkus Flow

1. **businessKey** - Would need custom metadata
2. **roles** - Not in lifecycle events
3. **addons** - Not in lifecycle events
4. **endpoint** - Not in lifecycle events
5. **rootInstanceId** - Not in lifecycle events (need to track parent-child relationships separately)
6. **parentInstanceId** - Not in lifecycle events
7. **createdBy/updatedBy** - Not in lifecycle events
8. **slaDueDate** - Not in lifecycle events

**Strategy**: These fields may need to be:
- Extracted from workflow definition metadata
- Added via custom event enrichment
- Populated by operator/control plane
- Kept for v0.8 compatibility only

## Next Steps

1. **Verify Model Completeness**: Ensure domain model can store all event data
2. **Plan FluentBit Ingestion**: How to parse JSON logs → PostgreSQL rows
3. **Design Aggregation Logic**: How to build WorkflowInstance from multiple events
4. **Create v0.8 Adapters**: Map new model to legacy ProcessInstance structure

---

**Analysis Complete**: Domain model now aligned with actual Quarkus Flow event structure
