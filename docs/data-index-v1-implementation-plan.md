# Data Index v1.0.0 Implementation Plan

**Goal:** Transform Data Index from event-driven indexer to read-only GraphQL query service

**Architecture Change:**
```
BEFORE (Kogito 0.8):
Workflow Runtime → CloudEvents → Kafka → Data Index (event processing) → PostgreSQL → GraphQL

AFTER (SW 1.0.0):
Quarkus Flow → JSON logs → FluentBit → PostgreSQL (triggers) → Data Index (read-only GraphQL)
```

---

## Phase 1: Analysis & Module Cleanup

### ❌ **REMOVE - Event Processing** (No longer needed - FluentBit handles this)

**Modules to DELETE:**
- `data-index/data-index-storage/data-index-storage-common/src/main/java/org/kie/kogito/index/storage/merger/`
  - All `*EventMerger.java` classes
  - ProcessInstanceEventMerger, UserTaskInstanceEventMerger, etc.
  
**Classes to DELETE:**
- `data-index-service/data-index-service-common/src/main/java/org/kie/kogito/index/service/messaging/`
  - `ReactiveMessagingEventConsumer.java`
  - `BlockingMessagingEventConsumer.java`
  - `DomainEventConsumer.java`
  
**Test modules to DELETE:**
- All `*Messaging*IT.java` tests
- All `*Consumer*IT.java` tests

**Dependencies to REMOVE from pom.xml:**
- `quarkus-kafka-client` / `quarkus-reactive-messaging-kafka`
- `quarkus-reactive-messaging-http`
- `kogito-events-*` dependencies
- CloudEvents dependencies

---

### ✅ **KEEP - Core Query Layer**

**Modules to KEEP (with modifications):**

1. **data-index-graphql/**
   - GraphQL schema files (`.graphqls`)
   - Keep existing v0.8 schema for compatibility
   - Add new v1.0.0 schema

2. **data-index-storage/data-index-storage-api/**
   - Domain model interfaces
   - **MODIFY**: Align with SW 1.0.0 terminology (WorkflowInstance, TaskExecution)

3. **data-index-storage/data-index-storage-postgresql/**
   - PostgreSQL storage implementation
   - **MODIFY**: Query from our new schema (workflow_instances, task_executions, etc.)
   - **REMOVE**: Write operations (Data Index becomes read-only)

4. **data-index-graphql/**
   - GraphQL resolvers
   - **MODIFY**: Change data source from event-processed tables to view-based queries

5. **data-index-quarkus/data-index-service-postgresql/**
   - Main service entry point
   - **KEEP**: GraphQL endpoint
   - **REMOVE**: Messaging configuration

---

### 🗑️ **DELETE - Entire Modules** (Not needed in new architecture)

1. **data-index-storage/data-index-storage-mongodb/** - We're PostgreSQL-only
2. **data-index-storage/data-index-storage-inmemory/** - Not for production
3. **data-index-storage/data-index-storage-infinispan/** - Legacy
4. **data-index-quarkus/data-index-service-mongodb/** - MongoDB variant
5. **data-index-quarkus/data-index-service-inmemory/** - Inmemory variant
6. **data-index-quarkus/kogito-addons-quarkus-data-index-persistence/** - Event publishing addon (opposite of what we need)

**Reasoning:** We're standardizing on PostgreSQL-only with FluentBit ingestion.

---

## Phase 2: Database Schema Deployment

**Tasks:**

1. Create Flyway/Liquibase migration scripts
   - Deploy `docs/database-schema-v1.0.0.sql`
   - Create migration from old Kogito schema (if needed for dev/test)

2. Deploy schema to test environment
   - Run DDL
   - Validate triggers work
   - Test views return correct data

**Deliverable:** PostgreSQL database ready with new schema

---

## Phase 3: Domain Model Alignment

**Current domain model** (data-index-storage-api):
```
ProcessInstance → Maps to process_instances table
NodeInstance → Maps to nodes table
UserTaskInstance → Maps to tasks table
Job → Maps to jobs table
```

**New domain model** (SW 1.0.0):
```
WorkflowInstance → Maps to workflow_instances table (native v1.0.0)
TaskExecution → Maps to task_executions table (native v1.0.0)
Job → Maps to jobs table (unchanged)

ProcessInstance → Maps to process_instances VIEW (v0.8 compatibility)
NodeInstance → Maps to nodes VIEW (v0.8 compatibility)
```

**Tasks:**

1. Create new domain model classes:
   - `org.kubesmarts.dataindex.model.WorkflowInstance`
   - `org.kubesmarts.dataindex.model.TaskExecution`

2. Update existing domain model for compatibility:
   - Keep `org.kie.kogito.index.model.ProcessInstance` (queries views)
   - Keep `org.kie.kogito.index.model.NodeInstance` (queries views)

3. Create mappers:
   - JPA entities → Domain model
   - Handle both native tables and compatibility views

**Deliverable:** Domain model supports both v0.8 and v1.0.0 APIs

---

## Phase 4: Storage Layer Refactoring

**Changes to data-index-storage-postgresql:**

1. **Remove write operations**
   ```java
   // DELETE these methods
   public void indexProcessInstance(ProcessInstance instance) // No longer needed
   public void updateProcessInstance(ProcessInstance instance) // No longer needed
   ```

2. **Update query methods** to use new schema
   ```java
   // BEFORE: Query process_instances table directly
   SELECT * FROM process_instances WHERE state = 'ACTIVE'
   
   // AFTER: Query process_instances VIEW (maps to workflow_instances)
   SELECT * FROM process_instances WHERE state = 1 -- ACTIVE enum
   ```

3. **Add new v1.0.0 query methods**
   ```java
   public List<WorkflowInstance> findWorkflowInstances(WorkflowInstanceQuery query)
   public Optional<WorkflowInstance> findWorkflowInstanceById(String id)
   ```

**Deliverable:** Storage layer queries PostgreSQL views/tables (read-only)

---

## Phase 5: GraphQL Schema & Resolvers

### v0.8 API (Compatibility)

**Keep existing schema:**
```graphql
type ProcessInstance {
  id: String!
  processId: String!
  state: ProcessInstanceState!
  variables: JSON
  nodes: [NodeInstance]
}
```

**Update resolvers to query views:**
```java
@Query("ProcessInstances")
public List<ProcessInstance> getProcessInstances(@Argument ProcessInstanceArgument where) {
    // Query process_instances VIEW (maps to workflow_instances table)
    return storageService.findProcessInstances(where);
}
```

### v1.0.0 API (Native)

**Create new schema** (`graphql/workflow-v1.graphqls`):
```graphql
type WorkflowInstance {
  instanceId: String!
  workflowName: String!
  workflowNamespace: String!
  status: WorkflowStatus!
  inputData: JSON
  outputData: JSON
  tasks: [TaskExecution]
}

enum WorkflowStatus {
  PENDING
  RUNNING
  WAITING
  COMPLETED
  FAULTED
  CANCELLED
  SUSPENDED
}
```

**Create new resolvers:**
```java
@Query("WorkflowInstances")
public List<WorkflowInstance> getWorkflowInstances(@Argument WorkflowInstanceArgument where) {
    // Query workflow_instances TABLE directly
    return storageService.findWorkflowInstances(where);
}
```

**Deliverable:** Dual GraphQL API (v0.8 compatibility + v1.0.0 native)

---

## Phase 6: Configuration & Deployment

### Remove Kafka Configuration

**BEFORE** (`application.properties`):
```properties
mp.messaging.incoming.kogito-processinstances-events.connector=smallrye-kafka
mp.messaging.incoming.kogito-processinstances-events.topic=kogito-processinstances-events
```

**AFTER**: ❌ Delete all messaging config - Data Index is read-only

### Update Dockerfile

**BEFORE**:
```dockerfile
# Data Index with Kafka consumer
FROM quarkus/quarkus-micro-image:2.0
ADD target/*-runner /work/application
```

**AFTER**:
```dockerfile
# Data Index GraphQL Gateway (read-only)
FROM quarkus/quarkus-micro-image:2.0
ADD target/*-runner /work/application
# No Kafka, just PostgreSQL JDBC
```

### Kubernetes Deployment

**ConfigMap for Data Index:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: data-index-config
data:
  application.properties: |
    # PostgreSQL connection (read-only)
    quarkus.datasource.jdbc.url=jdbc:postgresql://postgresql:5432/dataindex
    quarkus.datasource.username=dataindex_ro
    quarkus.datasource.password=${POSTGRES_PASSWORD}
    
    # GraphQL endpoint
    quarkus.http.port=8080
    
    # No Kafka - Data Index is read-only query service
```

**Deliverable:** Data Index deploys as read-only GraphQL service

---

## Phase 7: FluentBit Integration

**Deploy FluentBit DaemonSet:**
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: fluent-bit-config
data:
  fluent-bit.conf: |
    [INPUT]
        Name              tail
        Path              /var/log/containers/*quarkus-flow*.log
        Parser            docker
        Tag               quarkus.flow

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
        Host              postgresql
        Port              5432
        User              dataindex
        Password          ${POSTGRES_PASSWORD}
        Database          dataindex
        Table             workflow_events
        Async             false

    [OUTPUT]
        Name              pgsql
        Match             task.event
        Host              postgresql
        Table             task_events
        Async             false
```

**Deliverable:** FluentBit routes Quarkus Flow logs to PostgreSQL

---

## Phase 8: Testing Strategy

### Integration Tests

1. **End-to-end test:**
   ```
   Quarkus Flow workflow → Logs → FluentBit → PostgreSQL → Data Index GraphQL query
   ```

2. **GraphQL v0.8 compatibility test:**
   - Query `ProcessInstances` (should map to workflow_instances view)
   - Verify response matches old API format

3. **GraphQL v1.0.0 native test:**
   - Query `WorkflowInstances` (queries workflow_instances table)
   - Verify SW 1.0.0 terminology

4. **Trigger validation:**
   - INSERT into workflow_events
   - Verify workflow_instances materialized correctly
   - INSERT into task_events
   - Verify task_executions materialized correctly

### Performance Tests

1. Query latency (p50, p95, p99)
2. Concurrent GraphQL queries (100+ clients)
3. Large result sets (1000+ workflow instances)

**Deliverable:** Test suite validates full pipeline

---

## Phase 9: Documentation

1. **Architecture diagram** - New logs-as-transport flow
2. **Migration guide** - Kogito 0.8 → SW 1.0.0
3. **API documentation** - v0.8 compatibility + v1.0.0 native
4. **FluentBit deployment guide** - Kubernetes manifests
5. **Troubleshooting guide** - Common issues

**Deliverable:** Complete documentation for operators and developers

---

## Success Criteria

✅ **Data Index deploys as read-only service** (no Kafka dependencies)  
✅ **GraphQL v0.8 API works unchanged** (queries compatibility views)  
✅ **GraphQL v1.0.0 API available** (queries native SW 1.0.0 tables)  
✅ **FluentBit routes logs to PostgreSQL** (triggers maintain state)  
✅ **No event processing code in Data Index** (simplified architecture)  
✅ **CI/CD pipeline green** (all tests passing)  

---

## Estimated Timeline

| Phase | Effort | Dependencies |
|-------|--------|--------------|
| Phase 1: Module cleanup | 1 week | None |
| Phase 2: Schema deployment | 3 days | Phase 1 |
| Phase 3: Domain model | 1 week | Phase 2 |
| Phase 4: Storage layer | 1 week | Phase 3 |
| Phase 5: GraphQL layer | 2 weeks | Phase 4 |
| Phase 6: Config/Deploy | 3 days | Phase 5 |
| Phase 7: FluentBit | 1 week | Phase 6 |
| Phase 8: Testing | 1 week | Phase 7 |
| Phase 9: Documentation | 3 days | Phase 8 |

**Total:** ~8-9 weeks (2 months)

---

## Risk Mitigation

**Risk:** Breaking existing GraphQL clients  
**Mitigation:** v0.8 compatibility views ensure no API changes

**Risk:** PostgreSQL performance with large workloads  
**Mitigation:** Proper indexing, connection pooling, read replicas

**Risk:** FluentBit configuration complexity  
**Mitigation:** Provide tested ConfigMaps, operator handles deployment

**Risk:** Trigger bugs causing state inconsistency  
**Mitigation:** Comprehensive trigger tests, idempotency via ON CONFLICT

---

## Next Steps

1. **Approve this plan** - Review and adjust timeline/scope
2. **Create feature branch** - `feat/data-index-v1-read-only`
3. **Start Phase 1** - Delete event processing modules
4. **Set up CI** - Ensure tests pass after each phase
