# Data Index API Compatibility (v0.8 → v1.0.0)

## Overview

Data Index v1.0.0 maintains backward compatibility with v0.8 GraphQL API while transitioning to a read-only architecture. This document describes the compatibility strategy and API evolution path.

## Current API State

### GraphQL API Structure

**Query Types** (Read Operations):
```graphql
type Query {
    ProcessDefinitions(where: ProcessDefinitionArgument, orderBy: ProcessDefinitionOrderBy, pagination: Pagination): [ProcessDefinition]
    ProcessInstances(where: ProcessInstanceArgument, orderBy: ProcessInstanceOrderBy, pagination: Pagination): [ProcessInstance]
    UserTaskInstances(where: UserTaskInstanceArgument, orderBy: UserTaskInstanceOrderBy, pagination: Pagination): [UserTaskInstance]
    Jobs(where: JobArgument, orderBy: JobOrderBy, pagination: Pagination): [Job]
}
```

**Mutation Types** (Write/Execution Operations):
```graphql
type Mutation {
    # Process Instance Lifecycle
    ProcessInstanceAbort(id: String): String
    ProcessInstanceRetry(id: String): String
    ProcessInstanceSkip(id: String): String
    ProcessInstanceUpdateVariables(id: String, variables: String): String
    ProcessInstanceRescheduleSlaTimer(id: String!, expirationTime: DateTime!): String
    
    # Node/Task Execution
    NodeInstanceTrigger(id: String, nodeId: String): String
    NodeInstanceRetrigger(id: String, nodeInstanceId: String): String
    NodeInstanceCancel(id: String, nodeInstanceId: String): String
    NodeInstanceRescheduleSlaTimer(processInstanceId: String!, nodeInstanceId: String!, expirationTime: DateTime!): String
    
    # Job Management
    JobCancel(id: String): String
    JobReschedule(id: String, data: String): String
    
    # UserTask Management (v0.8 legacy)
    UserTaskInstanceUpdate(taskId: String, ...): String
    UserTaskInstanceCommentCreate(taskId: String, comment: String, user: String): String
    UserTaskInstanceCommentDelete(taskId: String, commentId: String): String
    UserTaskInstanceAttachmentCreate(taskId: String, name: String, uri: String, user: String): String
    UserTaskInstanceAttachmentDelete(taskId: String, attachmentId: String): String
}
```

## v0.8 Compatibility Preservation

### GraphQL Schema Compatibility

The `ProcessInstance` type includes all v0.8 fields to ensure existing GraphQL queries continue to work:

**v0.8 Compatibility Fields**:
```graphql
type ProcessInstance {
    # Core identifiers (v0.8)
    id: String!
    processId: String!
    processName: String
    version: String
    
    # Process hierarchy (v0.8 terminology)
    parentProcessInstanceId: String
    rootProcessInstanceId: String
    rootProcessId: String
    
    # Business identifiers (v0.8)
    businessKey: String
    
    # Runtime service references (v0.8)
    endpoint: String!
    serviceUrl: String
    
    # Audit fields (v0.8)
    createdBy: String
    updatedBy: String
    
    # v0.8 state model
    state: ProcessInstanceState  # Compatible with v0.8 integer values
    nodes: [NodeInstance!]       # v0.8 terminology (vs v1.0.0 TaskExecution)
    milestones: [Milestone!]     # BPMN legacy feature
    
    # Common fields
    roles: [String!]
    variables: JSON
    start: DateTime
    end: DateTime
    cloudEventId: String
    cloudEventSource: String
}
```

### Database Schema Compatibility

PostgreSQL views map v1.0.0 tables to v0.8 schema for backward compatibility:

**Compatibility Views** (see `docs/database-schema-v1.0.0.sql`):
- `process_instances` view → `workflow_instances` table
- `nodes` view → `task_executions` table
- `definitions` view → `workflow_definitions` table

This allows:
1. GraphQL queries to use v0.8 field names
2. JPA storage implementations to query v0.8 views
3. Gradual migration of consumers to v1.0.0 terminology

### State Model Compatibility

**ProcessInstanceState enum** maps to v0.8 integer state codes:
```java
public enum ProcessInstanceState {
    PENDING(0),
    ACTIVE(1),
    COMPLETED(2),
    ABORTED(3),
    SUSPENDED(4),
    ERROR(5);
}
```

GraphQL resolvers use `.ordinal()` to return v0.8-compatible integer values.

## API Split Strategy

### Phase 1-2: Unified API (Current)

For Phase 1-2, we maintain the current unified GraphQL API that includes both queries and mutations:

**Why keep mutations in Data Index?**
1. **v0.8 compatibility**: Existing consumers expect mutations in Data Index GraphQL endpoint
2. **Proxy pattern**: Mutations don't modify Data Index state - they delegate to `KogitoRuntimeClient` which calls workflow runtime HTTP endpoints
3. **Phase focus**: Phase 1-2 focuses on making Data Index read-only for **incoming data** (no event processing), not outbound API

**Current mutation implementation**:
```java
// Example: ProcessInstanceAbort mutation
// File: KogitoRuntimeClientImpl.java
public CompletableFuture<String> abort(ProcessInstance processInstance) {
    // Calls workflow runtime HTTP endpoint: DELETE /{processId}/{processInstanceId}
    return httpClient.delete(processInstance.getEndpoint() + "/" + processInstance.getId());
}
```

Mutations are **thin proxies** that:
- Query Data Index for process instance metadata (endpoint URL, processId)
- Call workflow runtime HTTP API
- Return success/failure status
- **Do not modify Data Index database directly**

### Phase 3: API Split (Future)

In Phase 3, split mutations into a separate **Workflow Management Service**:

**Data Index v1.0.0** (Read-Only Query Service):
```graphql
type Query {
    WorkflowDefinitions(...)    # v1.0.0 terminology
    WorkflowInstances(...)      # v1.0.0 terminology
    TaskExecutions(...)         # v1.0.0 terminology
    Jobs(...)
    
    # v0.8 compatibility aliases (deprecated)
    ProcessDefinitions(...) @deprecated(reason: "Use WorkflowDefinitions")
    ProcessInstances(...) @deprecated(reason: "Use WorkflowInstances")
}
```

**Workflow Management Service** (Execution & Lifecycle):
```graphql
type Mutation {
    # Workflow lifecycle
    WorkflowInstanceAbort(id: String!): WorkflowInstanceAbortResult
    WorkflowInstanceRetry(id: String!): WorkflowInstanceRetryResult
    WorkflowInstanceUpdateVariables(id: String!, variables: JSON!): WorkflowInstance
    
    # Task execution
    TaskExecutionTrigger(workflowInstanceId: String!, taskId: String!): TaskExecution
    TaskExecutionCancel(workflowInstanceId: String!, taskId: String!): TaskExecution
    
    # Job management
    JobCancel(id: String!): Job
    JobReschedule(id: String!, scheduledTime: DateTime!): Job
}
```

**Migration path**:
1. Deploy Workflow Management Service alongside Data Index v1.0.0
2. Update GraphQL gateway to route queries → Data Index, mutations → Management Service
3. Deprecate mutations in Data Index GraphQL schema (return "Use Workflow Management Service" errors)
4. Remove mutation implementations from Data Index

**UserTask mutations**: UserTask is a v0.8 BPMN legacy feature not used in Serverless Workflow 1.0.0. These mutations will be removed entirely in Phase 3, not migrated to Management Service.

## Implementation Status

### Phase 1 (Complete)
- ✅ Removed event processing from Data Index storage layer
- ✅ Transformed storage interfaces to read-only (StorageFetcher pattern)
- ✅ Removed Kafka, reactive messaging dependencies
- ✅ Replaced kogito-jackson-utils with standard Jackson
- ✅ Deleted obsolete modules (MongoDB, InMemory, embedded addons)

### Phase 1-2 (In Progress)
- ✅ GraphQL query API remains functional (v0.8 compatible schema)
- ✅ GraphQL mutation API remains functional (proxy to runtime services)
- 🔄 Database views for v0.8 compatibility (SQL DDL defined, pending PostgreSQL deployment)
- 🔄 FluentBit log ingestion (design complete, pending implementation)
- 🔄 PostgreSQL triggers for state materialization (SQL defined, pending deployment)

### Phase 2 (Planned)
- ⏳ Deploy PostgreSQL with v1.0.0 schema + v0.8 compatibility views
- ⏳ Deploy FluentBit to parse Quarkus Flow JSON logs → PostgreSQL event tables
- ⏳ Test end-to-end: Quarkus Flow → Logs → FluentBit → PostgreSQL → Data Index → GraphQL
- ⏳ Migrate existing consumers from v0.8 to v1.0.0 GraphQL queries

### Phase 3 (Future)
- ⏳ Define Workflow Management Service API
- ⏳ Implement Workflow Management Service (runtime HTTP client)
- ⏳ Deploy GraphQL gateway to route queries/mutations
- ⏳ Deprecate mutations in Data Index
- ⏳ Remove UserTask mutations (BPMN legacy, not in SW 1.0.0)

## Testing Compatibility

### v0.8 GraphQL Query Examples

**Query process instances** (v0.8 schema):
```graphql
query {
  ProcessInstances(where: {state: {equal: ACTIVE}}) {
    id
    processId
    processName
    businessKey
    parentProcessInstanceId
    rootProcessInstanceId
    endpoint
    state
    nodes {
      id
      name
      type
      definitionId
    }
    variables
  }
}
```

**Query with v0.8 pagination**:
```graphql
query {
  ProcessInstances(
    where: {processId: {equal: "order-workflow"}}
    orderBy: {start: DESC}
    pagination: {limit: 10, offset: 0}
  ) {
    id
    processName
    start
    end
    state
  }
}
```

### v0.8 Mutation Examples

**Abort process instance**:
```graphql
mutation {
  ProcessInstanceAbort(id: "abc-123")
}
```

**Update process variables**:
```graphql
mutation {
  ProcessInstanceUpdateVariables(
    id: "abc-123"
    variables: "{\"order\":{\"status\":\"cancelled\"}}"
  )
}
```

## Migration Guidelines for Consumers

### Immediate (Phase 1-2)
- Continue using existing v0.8 GraphQL queries - **no changes required**
- Continue using existing v0.8 GraphQL mutations - **no changes required**
- Test applications against Data Index v1.0.0 GraphQL endpoint

### Phase 2
- Start migrating queries to use v1.0.0 terminology (WorkflowInstance, TaskExecution)
- Update field names: `processId` → `workflowId`, `nodes` → `taskExecutions`
- v0.8 queries continue to work via compatibility views (no breaking changes)

### Phase 3
- Migrate mutations to Workflow Management Service GraphQL endpoint
- Update mutation response handling (richer result types vs simple String)
- Remove UserTask mutation calls (if any) - feature removed in SW 1.0.0

## References

- **Database Schema**: `docs/database-schema-v1.0.0.sql` - PostgreSQL DDL with v0.8 compatibility views
- **Architecture**: `docs/architecture-v1.0.0.md` - Read-only Data Index architecture
- **GraphQL Schema**: `data-index-graphql/src/main/resources/graphql/basic.schema.graphqls`
- **Storage API**: `data-index-storage/data-index-storage-api/src/main/java/org/kie/kogito/index/storage/`
- **Runtime Client**: `data-index-quarkus/data-index-service-quarkus-common/src/main/java/org/kie/kogito/index/quarkus/service/api/KogitoRuntimeClientImpl.java`
