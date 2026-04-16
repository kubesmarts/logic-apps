# Fresh Start - Domain Model & JPA Entities

**Date**: 2026-04-15  
**Status**: ✅ **RESET COMPLETE**

## Design Principle

**Quarkus Flow structured logging events + SW 1.0.0 spec = KING**

Every field in our domain model and JPA entities maps directly to data emitted by Quarkus Flow runtime events. No v0.8 legacy concepts.

## What We Removed (v0.8 Concepts)

❌ `workflowId` - Does not exist in SW 1.0.0 (workflows identified by namespace+name+version)  
❌ `rootWorkflowId` - v0.8 concept  
❌ `processId`, `processName`, etc. - v0.8 BPMN terminology

## Domain Model (Cleaned)

### WorkflowInstanceMeta

**Source**: Quarkus Flow workflow.instance.* events

```java
// From events
private String id;              // instanceId
private String namespace;       // workflowNamespace  
private String name;            // workflowName
private String version;         // workflowVersion
private WorkflowInstanceStatus status;  // status (RUNNING, COMPLETED, FAULTED, etc.)
private ZonedDateTime start;    // startTime from workflow.instance.started
private ZonedDateTime end;      // endTime from workflow.instance.completed/faulted
private ZonedDateTime lastUpdate;  // lastUpdateTime from workflow.instance.status.changed

// TBD fields (not in events yet, may come from operator/workflow metadata)
private String businessKey;
private String endpoint;
private Set<String> roles;
private String rootInstanceId;
private String parentInstanceId;
private String createdBy;
private String updatedBy;
private ZonedDateTime slaDueDate;
private String sourceEventId;
private String sourceEventSource;
```

### WorkflowInstance

```java
// From events
private JsonNode variables;              // input/output from events
private List<TaskExecution> taskExecutions;  // aggregated from workflow.task.* events
private WorkflowInstanceError error;     // error object from workflow.instance.faulted

// TBD
private Set<String> addons;
private Workflow workflow;  // Reference to definition (when we have it)
```

### TaskExecution

**Source**: Quarkus Flow workflow.task.* events

```java
// From events
private String id;                  // taskExecutionId (generated deterministically)
private String taskName;            // taskName
private String taskPosition;        // taskPosition (JSONPointer: "/do/0", "/fork/branches/0/do/1")
private ZonedDateTime enter;        // startTime from workflow.task.started
private ZonedDateTime exit;         // endTime from workflow.task.completed/faulted
private String errorMessage;        // error.title from workflow.task.faulted
private JsonNode inputArgs;         // input from workflow.task.started
private JsonNode outputArgs;        // output from workflow.task.completed
```

### WorkflowInstanceError

**Source**: error object from workflow.instance.faulted event

**Aligns with**: SW 1.0.0 Error spec

```java
private String type;        // "system", "business", "timeout", "communication"
private String title;       // Short error summary
private String detail;      // Detailed message/stack trace
private Integer status;     // HTTP status code
private String instance;    // Error instance ID
```

## JPA Entities (Fresh)

### Package: `org.kubesmarts.logic.dataindex.jpa`

Created 3 entities based ONLY on Quarkus Flow events:

```
data-index-storage-jpa-common/src/main/java/org/kubesmarts/logic/dataindex/jpa/
├── WorkflowInstanceEntity.java         (workflow_instances table)
├── TaskExecutionEntity.java            (task_executions table)
└── WorkflowInstanceErrorEntity.java    (@Embeddable in workflow_instances)
```

### WorkflowInstanceEntity

**Table**: `workflow_instances` (NEW table)

**Javadoc**: Every field documented with event source

```java
@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity {
    
    @Id
    private String id;  // Source: instanceId from events
    
    private String namespace;  // Source: workflowNamespace from events
    private String name;       // Source: workflowName from events
    private String version;    // Source: workflowVersion from events
    
    @Enumerated(EnumType.STRING)
    private WorkflowInstanceStatus status;  // Source: status from events
    
    private ZonedDateTime start;      // Source: startTime from workflow.instance.started
    private ZonedDateTime end;        // Source: endTime from workflow.instance.completed/faulted
    private ZonedDateTime lastUpdate; // Source: lastUpdateTime from workflow.instance.status.changed
    
    @Convert(converter = JsonBinaryConverter.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode variables;  // Source: input/output from events
    
    @OneToMany(mappedBy = "workflowInstance")
    private List<TaskExecutionEntity> taskExecutions;
    
    @Embedded
    private WorkflowInstanceErrorEntity error;  // Source: error from workflow.instance.faulted
}
```

### TaskExecutionEntity

**Table**: `task_executions` (NEW table)

```java
@Entity
@Table(name = "task_executions")
public class TaskExecutionEntity {
    
    @Id
    private String id;  // Source: taskExecutionId from events
    
    private String taskName;      // Source: taskName from events
    private String taskPosition;  // Source: taskPosition from events (JSONPointer!)
    
    private ZonedDateTime enter;  // Source: startTime from workflow.task.started
    private ZonedDateTime exit;   // Source: endTime from workflow.task.completed/faulted
    
    private String errorMessage;  // Source: error.title from workflow.task.faulted
    
    @Convert(converter = JsonBinaryConverter.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode inputArgs;   // Source: input from workflow.task.started
    
    @Convert(converter = JsonBinaryConverter.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode outputArgs;  // Source: output from workflow.task.completed
    
    @ManyToOne(optional = false)
    @JoinColumn(name = "workflow_instance_id")
    private WorkflowInstanceEntity workflowInstance;
}
```

### WorkflowInstanceErrorEntity

**Type**: @Embeddable (embedded in WorkflowInstanceEntity)

```java
@Embeddable
public class WorkflowInstanceErrorEntity {
    
    @Column(name = "error_type")
    private String type;  // Source: error.type from workflow.instance.faulted
    
    @Column(name = "error_title")
    private String title;  // Source: error.title from workflow.instance.faulted
    
    @Column(name = "error_detail", columnDefinition = "TEXT")
    private String detail;  // Source: error.detail from workflow.instance.faulted
    
    @Column(name = "error_status")
    private Integer status;  // Source: error.status from workflow.instance.faulted
    
    @Column(name = "error_instance")
    private String instance;  // Source: error.instance from workflow.instance.faulted
}
```

## Event → Entity Mapping

### workflow.instance.started

```json
{
  "instanceId": "uuid-1234",           → WorkflowInstanceEntity.id
  "workflowNamespace": "default",      → WorkflowInstanceEntity.namespace
  "workflowName": "order-processing",  → WorkflowInstanceEntity.name
  "workflowVersion": "1.0.0",         → WorkflowInstanceEntity.version
  "status": "RUNNING",                 → WorkflowInstanceEntity.status
  "startTime": "2026-04-15T15:30:00Z", → WorkflowInstanceEntity.start
  "input": { "orderId": "12345" }      → WorkflowInstanceEntity.variables
}
```

### workflow.instance.completed

```json
{
  "instanceId": "uuid-1234",           → WorkflowInstanceEntity.id (UPSERT)
  "status": "COMPLETED",               → WorkflowInstanceEntity.status (UPDATE)
  "endTime": "2026-04-15T15:30:30Z",  → WorkflowInstanceEntity.end (UPDATE)
  "output": { "result": "success" }    → WorkflowInstanceEntity.variables (MERGE)
}
```

### workflow.instance.faulted

```json
{
  "instanceId": "uuid-1234",           → WorkflowInstanceEntity.id (UPSERT)
  "status": "FAULTED",                 → WorkflowInstanceEntity.status (UPDATE)
  "endTime": "2026-04-15T15:30:15Z",  → WorkflowInstanceEntity.end (UPDATE)
  "error": {                           → WorkflowInstanceEntity.error
    "type": "system",                  → error.type
    "title": "Service unavailable",    → error.title
    "detail": "Failed to connect...",  → error.detail
    "status": 503,                     → error.status
    "instance": "uuid-error-5678"      → error.instance
  }
}
```

### workflow.task.started

```json
{
  "instanceId": "uuid-1234",           → TaskExecutionEntity.workflowInstance (FK)
  "taskExecutionId": "task-uuid-1",    → TaskExecutionEntity.id
  "taskName": "callPaymentService",    → TaskExecutionEntity.taskName
  "taskPosition": "/do/0",             → TaskExecutionEntity.taskPosition
  "startTime": "2026-04-15T15:30:05Z", → TaskExecutionEntity.enter
  "input": { "amount": 100 }           → TaskExecutionEntity.inputArgs
}
```

### workflow.task.completed

```json
{
  "taskExecutionId": "task-uuid-1",    → TaskExecutionEntity.id (UPSERT)
  "endTime": "2026-04-15T15:30:08Z",  → TaskExecutionEntity.exit (UPDATE)
  "output": { "transactionId": "tx" }  → TaskExecutionEntity.outputArgs (UPDATE)
}
```

### workflow.task.faulted

```json
{
  "taskExecutionId": "task-uuid-1",    → TaskExecutionEntity.id (UPSERT)
  "endTime": "2026-04-15T15:30:07Z",  → TaskExecutionEntity.exit (UPDATE)
  "error": {
    "title": "Connection timeout"      → TaskExecutionEntity.errorMessage
  }
}
```

## Database Schema (NEW)

```sql
CREATE TABLE workflow_instances (
    id VARCHAR(255) PRIMARY KEY,
    namespace VARCHAR(255),
    name VARCHAR(255),
    version VARCHAR(255),
    status VARCHAR(50),  -- RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
    start TIMESTAMP WITH TIME ZONE,
    end TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,
    variables JSONB,
    error_type VARCHAR(255),
    error_title VARCHAR(255),
    error_detail TEXT,
    error_status INTEGER,
    error_instance VARCHAR(255)
);

CREATE TABLE task_executions (
    id VARCHAR(255) PRIMARY KEY,
    workflow_instance_id VARCHAR(255) NOT NULL REFERENCES workflow_instances(id) ON DELETE CASCADE,
    task_name VARCHAR(255),
    task_position VARCHAR(255),  -- JSONPointer: "/do/0"
    enter TIMESTAMP WITH TIME ZONE,
    exit TIMESTAMP WITH TIME ZONE,
    error_message TEXT,
    input_args JSONB,
    output_args JSONB
);

CREATE INDEX idx_workflow_instances_namespace_name ON workflow_instances(namespace, name);
CREATE INDEX idx_workflow_instances_status ON workflow_instances(status);
CREATE INDEX idx_task_executions_workflow_instance ON task_executions(workflow_instance_id);
CREATE INDEX idx_task_executions_position ON task_executions(task_position);
```

## Build Status

```bash
mvn clean compile -DskipTests -pl data-index-storage/data-index-storage-jpa-common -am

[INFO] BUILD SUCCESS
[INFO] Compiling 26 source files (3 new entities + 23 legacy)
```

## Key Benefits

✅ **Pure SW 1.0.0** - No v0.8 concepts  
✅ **Event-driven** - Every field has an event source  
✅ **Clean separation** - New entities in new package  
✅ **Well-documented** - Javadoc shows event sources  
✅ **Ready for FluentBit** - Clear event → table mapping

## Next Steps

1. **Create FluentBit configuration** to parse Quarkus Flow logs → PostgreSQL
2. **Test with real workflows** - Run Quarkus Flow, verify data ingestion
3. **Create MapStruct mappers** - Entity ↔ Domain model
4. **Build GraphQL schema** for v1.0.0 API
5. **THEN create v0.8 adapters** - Translate to ProcessInstance/ProcessDefinition for legacy API

## What About v0.8 API?

**Adapters will handle it:**

```
v0.8 GraphQL Query → Adapter → v1.0.0 Domain Model → JPA Entity → Database
  
Adapter adds:
- workflowId (from namespace+name composite)
- processId, processName (renamed from name)
- nodes (renamed from taskExecutions)
- state (Integer from status enum ordinal)
```

The v0.8 API queries the SAME database but presents it with v0.8 terminology via adapters.

---

**Status**: ✅ **FRESH START COMPLETE**  
**Foundation**: Quarkus Flow events + SW 1.0.0 spec  
**Next**: FluentBit configuration + real workflow testing
