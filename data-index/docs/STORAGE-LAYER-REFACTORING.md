# Storage Layer Refactoring - Event Processor Isolation

**Date**: 2026-04-17  
**Status**: ✅ **COMPLETE**

---

## 🎯 Objective

Isolate event processor logic into a common abstraction layer that can be reused across multiple storage backends (PostgreSQL, Elasticsearch, etc.).

---

## 📦 Module Structure (Before)

```
data-index/
├── data-index-model/                          # Domain models
├── data-index-storage-postgresql/             # PostgreSQL storage + event processors
│   ├── entity/                                # JPA entities
│   ├── event/                                 # Event entities
│   ├── ingestion/                             # Event processors (PostgreSQL-specific)
│   ├── metrics/                               # Metrics REST endpoints
│   └── storage/                               # Storage implementations
└── data-index-service/                        # GraphQL API
```

**Problem**: Event processing logic was tightly coupled to PostgreSQL (EntityManager, JPA queries).

---

## 📦 Module Structure (After)

```
data-index/
├── data-index-model/                          # Domain models
├── data-index-storage/                        # NEW - Storage parent POM
│   ├── data-index-storage-common/             # NEW - Shared abstractions + orchestration
│   │   ├── api/                               # Storage interfaces
│   │   │   ├── EventProcessor.java            # Backend-agnostic processor interface
│   │   │   └── EventRepository.java           # Backend-agnostic repository interface
│   │   ├── ingestion/                         # Backend-agnostic orchestration
│   │   │   ├── EventProcessorScheduler.java   # Polls processors every 5s
│   │   │   ├── EventProcessorMetrics.java     # Prometheus metrics
│   │   │   ├── EventProcessorHealthCheck.java # Kubernetes health checks
│   │   │   └── EventCleanupScheduler.java     # Event retention cleanup
│   │   └── metrics/                           # Metrics REST endpoints
│   │       ├── EventProcessorMetricsResource.java
│   │       ├── EventMetrics.java
│   │       └── EventProcessorMetricsResponse.java
│   ├── data-index-storage-postgresql/         # PostgreSQL-specific implementation
│   │   ├── entity/                            # JPA final entities
│   │   ├── event/                             # JPA event entities
│   │   ├── processor/                         # PostgreSQL event processors
│   │   │   ├── PostgreSQLWorkflowInstanceEventProcessor.java
│   │   │   └── PostgreSQLTaskExecutionEventProcessor.java
│   │   ├── repository/                        # PostgreSQL event repositories
│   │   │   ├── WorkflowInstanceEventRepository.java
│   │   │   └── TaskExecutionEventRepository.java
│   │   └── storage/                           # PostgreSQL storage implementations
│   └── data-index-storage-elasticsearch/      # NEW - Elasticsearch skeleton
│       ├── document/                          # ES document models (to be implemented)
│       ├── processor/                         # ES event processors (to be implemented)
│       ├── repository/                        # ES event repositories (to be implemented)
│       └── storage/                           # ES storage implementations (to be implemented)
└── data-index-service/                        # GraphQL API (unchanged)
```

---

## 🏗️ Architecture Improvements

### 1. Storage Abstraction Layer (Common)

**New Interfaces**:

```java
public interface EventProcessor<E> {
    int processBatch(int batchSize);
    String getProcessorName();
    long getBacklog();
    long getOldestUnprocessedAgeSeconds();
}

public interface EventRepository<E> {
    List<E> findUnprocessedEvents(int limit);
    void markAsProcessed(List<E> events);
    long countUnprocessed();
    Instant findOldestUnprocessedEventTime();
    int deleteOlderThan(Instant cutoffTime);
}
```

**Benefits**:
- ✅ Backend-agnostic (works with PostgreSQL, Elasticsearch, any future backend)
- ✅ Clear separation of concerns (orchestration vs implementation)
- ✅ Testable (can mock implementations)

---

### 2. Backend-Agnostic Orchestration

**EventProcessorScheduler** (in storage-common):

```java
@ApplicationScoped
public class EventProcessorScheduler {
    
    @Inject
    Instance<EventProcessor<?>> eventProcessors;  // CDI discovers all implementations
    
    @Scheduled(every = "${data-index.event-processor.interval:5s}")
    public void processEvents() {
        for (EventProcessor<?> processor : eventProcessors) {
            int processed = processor.processBatch(batchSize);
            // Log, metrics, etc.
        }
    }
}
```

**Benefits**:
- ✅ Discovers all EventProcessor implementations via CDI
- ✅ Works with PostgreSQL processors today, Elasticsearch processors tomorrow
- ✅ No code changes needed when adding new storage backends

---

### 3. PostgreSQL-Specific Implementation

**PostgreSQLWorkflowInstanceEventProcessor**:

```java
@ApplicationScoped
public class PostgreSQLWorkflowInstanceEventProcessor 
        implements EventProcessor<WorkflowInstanceEvent> {
    
    @Inject
    EntityManager entityManager;  // PostgreSQL-specific
    
    @Inject
    WorkflowInstanceEventRepository eventRepository;
    
    @Override
    public int processBatch(int batchSize) {
        // Fetch events using repository
        List<WorkflowInstanceEvent> events = eventRepository.findUnprocessedEvents(batchSize);
        
        // Merge into JPA entities (PostgreSQL-specific logic)
        for (WorkflowInstanceEvent event : events) {
            WorkflowInstanceEntity instance = entityManager.find(...);
            mergeEvent(instance, event);
            entityManager.merge(instance);
        }
        
        // Mark as processed
        eventRepository.markAsProcessed(events);
        
        return events.size();
    }
}
```

**Benefits**:
- ✅ Implements common EventProcessor interface
- ✅ Uses PostgreSQL-specific tools (EntityManager, JPA)
- ✅ Isolated from other backends (no shared code)

---

### 4. Elasticsearch-Specific Implementation (Skeleton)

**Future** - To be implemented:

```java
@ApplicationScoped
public class ElasticsearchWorkflowInstanceEventProcessor 
        implements EventProcessor<WorkflowInstanceEvent> {
    
    @Inject
    ElasticsearchClient esClient;  // Elasticsearch-specific
    
    @Inject
    WorkflowInstanceEventRepository eventRepository;  // ES version
    
    @Override
    public int processBatch(int batchSize) {
        // Fetch events from ES event index
        List<WorkflowInstanceEvent> events = eventRepository.findUnprocessedEvents(batchSize);
        
        // Merge into ES documents (Elasticsearch-specific logic)
        for (WorkflowInstanceEvent event : events) {
            UpdateRequest<WorkflowInstanceDocument> update = buildUpdate(event);
            esClient.update(update, WorkflowInstanceDocument.class);
        }
        
        // Mark as processed in ES
        eventRepository.markAsProcessed(events);
        
        return events.size();
    }
}
```

**Benefits**:
- ✅ Same interface as PostgreSQL processor
- ✅ Backend-agnostic scheduler discovers and calls it automatically
- ✅ No changes to orchestration code

---

## 📊 Dependency Graph

```
data-index-service
  └─> data-index-storage-postgresql
      ├─> data-index-storage-common
      │   └─> data-index-model
      └─> data-index-model

Future (Elasticsearch):
data-index-service
  └─> data-index-storage-elasticsearch (via config)
      ├─> data-index-storage-common
      │   └─> data-index-model
      └─> data-index-model
```

---

## 🎯 Key Design Decisions

### 1. **Event Processors in Common vs Specific**

**Decision**: Event processor **orchestration** in common, **implementation** in specific modules.

**Rationale**:
- Orchestration (scheduling, metrics, health checks) is backend-agnostic
- Implementation (JPA queries, ES queries) is backend-specific
- Clear separation allows adding new backends without touching orchestration

---

### 2. **CDI-Based Discovery**

**Decision**: Use CDI `Instance<EventProcessor<?>>` to discover implementations.

**Rationale**:
- ✅ Automatic discovery of all EventProcessor beans
- ✅ No manual registration required
- ✅ Works across modules (PostgreSQL, Elasticsearch, future backends)
- ✅ Quarkus-native approach

---

### 3. **Repository Pattern**

**Decision**: Introduce `EventRepository<E>` interface for CRUD operations on events.

**Rationale**:
- ✅ Separates event CRUD from processing logic
- ✅ Testable (can mock repository)
- ✅ Backend-specific (PostgreSQL uses JPA, Elasticsearch uses ES client)

---

## ✅ Build Status

```bash
$ mvn clean compile -DskipTests

[INFO] Reactor Summary for KubeSmarts Logic Apps :: Data Index 999-SNAPSHOT:
[INFO] 
[INFO] KubeSmarts Logic Apps :: Data Index ................ SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Model ....... SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Storage ..... SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Storage :: Common SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Storage :: PostgreSQL SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Storage :: Elasticsearch SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Service ..... SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Integration Tests SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

---

## 📚 Files Created/Modified

### Created (Common)
- `data-index-storage/pom.xml` - Parent POM
- `data-index-storage-common/pom.xml` - Common module POM
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/api/EventProcessor.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/api/EventRepository.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorScheduler.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorMetrics.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorHealthCheck.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventCleanupScheduler.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/metrics/EventProcessorMetricsResource.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/metrics/EventMetrics.java`
- `data-index-storage-common/src/main/java/org/kubesmarts/logic/dataindex/metrics/EventProcessorMetricsResponse.java`

### Created (PostgreSQL)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/processor/PostgreSQLWorkflowInstanceEventProcessor.java`
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/processor/PostgreSQLTaskExecutionEventProcessor.java`
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/repository/WorkflowInstanceEventRepository.java`
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/repository/TaskExecutionEventRepository.java`

### Created (Elasticsearch Skeleton)
- `data-index-storage-elasticsearch/pom.xml`
- `data-index-storage-elasticsearch/README.md`

### Modified
- `data-index/pom.xml` - Updated modules, added dependency management
- `data-index-storage-postgresql/pom.xml` - Changed parent, added storage-common dependency
- `data-index-service/pom.xml` - Updated comment about storage dependencies

### Deleted
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorScheduler.java` (moved to common)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorMetrics.java` (moved to common)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventProcessorHealthCheck.java` (moved to common)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/EventCleanupScheduler.java` (moved to common)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/WorkflowInstanceEventProcessor.java` (refactored to processor/)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/ingestion/TaskExecutionEventProcessor.java` (refactored to processor/)
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/metrics/*` (moved to common)

---

## 🚀 Next Steps

### For Elasticsearch Implementation
1. Implement ES document models (WorkflowInstanceDocument, TaskExecutionDocument)
2. Implement ES event repositories (WorkflowInstanceEventRepository, TaskExecutionEventRepository)
3. Implement ES event processors (PostgreSQLWorkflowInstanceEventProcessor, PostgreSQLTaskExecutionEventProcessor)
4. Implement ES storage (ElasticsearchWorkflowInstanceStorage, ElasticsearchTaskExecutionStorage)
5. Add integration tests with Testcontainers Elasticsearch

### For Storage Backend Selection
1. Create `StorageBackend` CDI qualifier
2. Create `StorageProducer` that selects backend based on config
3. Update GraphQL to inject `WorkflowInstanceStorage` (interface, not impl)
4. Add config property: `data-index.storage.backend=postgresql|elasticsearch`

---

## 📖 References

- [CQRS Separation](ARCHITECTURE-CQRS-SEPARATION.md) - Write/read side isolation
- [Elasticsearch Analysis](ELASTICSEARCH-DUAL-STORAGE-ANALYSIS.md) - Dual storage architecture
- [Event Processing Patterns](event-processing-patterns-analysis.md) - Transactional Outbox, CQRS
- [Current State](current-state.md) - Implementation status

---

**Date**: 2026-04-17  
**Author**: Claude Code (Sonnet 4.5)  
**Status**: Complete
