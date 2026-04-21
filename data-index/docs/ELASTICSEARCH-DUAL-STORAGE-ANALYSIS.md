# Elasticsearch Dual Storage Architecture Analysis

**Date**: 2026-04-17  
**Status**: 🔍 **ARCHITECTURE ANALYSIS**

---

## 🎯 Objective

Support **two storage backends** for different use cases:

1. **PostgreSQL** (Simple users): Event normalization for relational integrity
2. **Elasticsearch** (Heavy users): Direct ingestion for high-performance search

**GraphQL layer** must be **storage-agnostic** and query the appropriate backend.

---

## 📊 Current Architecture Recap

```
FluentBit → Event Tables (PostgreSQL)
              ↓ (Event Processors - Normalization)
         Final Tables (PostgreSQL)
              ↓ (Storage API)
         GraphQL API
```

**Characteristics**:
- ✅ Normalization ensures data integrity
- ✅ Event sourcing (30-day audit trail)
- ✅ Works with any SQL database
- ❌ Normalization adds latency (5s polling interval)
- ❌ Not optimized for full-text search
- ❌ Limited aggregation capabilities

---

## 🏗️ Proposed Dual Storage Architecture

### Option 1: Conditional Routing (Either/Or)

**Configuration-based**: Choose storage at deployment time.

```
FluentBit
    ↓ (conditional output based on config)
    ├─→ PostgreSQL Path (Simple Users)
    │       ↓ Event Tables
    │       ↓ Event Processors (normalization)
    │       ↓ Final Tables
    │       ↓ PostgreSQLStorage (implements Storage API)
    │       ↓ GraphQL API
    │
    └─→ Elasticsearch Path (Heavy Users)
            ↓ Direct to ES index
            ↓ ElasticsearchStorage (implements Storage API)
            ↓ GraphQL API
```

**Storage Resolution**:
```java
@ApplicationScoped
public class StorageResolver {
    
    @Inject
    @ConfigProperty(name = "data-index.storage.backend", defaultValue = "postgresql")
    String storageBackend;
    
    @Inject
    Instance<WorkflowInstanceStorage> storages;
    
    public WorkflowInstanceStorage getStorage() {
        return storages.stream()
            .filter(s -> s.supports(storageBackend))
            .findFirst()
            .orElseThrow();
    }
}
```

**Configuration**:
```properties
# Simple users (default)
data-index.storage.backend=postgresql

# Heavy users
data-index.storage.backend=elasticsearch
```

---

### Option 2: Hybrid (Both Simultaneously)

**Write to both**: FluentBit outputs to PostgreSQL AND Elasticsearch.

```
FluentBit
    ├─→ PostgreSQL (normalization + audit trail)
    │       ↓ Event Processors
    │       ↓ Final Tables (source of truth for relational queries)
    │
    └─→ Elasticsearch (search index)
            ↓ Direct indexing (no normalization)

GraphQL API
    ↓ (query routing based on operation type)
    ├─→ PostgreSQL (getWorkflowInstance by ID, foreign keys)
    └─→ Elasticsearch (search, aggregations, full-text)
```

**Query Routing**:
```java
@ApplicationScoped
public class HybridWorkflowInstanceStorage implements WorkflowInstanceStorage {
    
    @Inject
    PostgreSQLWorkflowInstanceStorage pgStorage;
    
    @Inject
    ElasticsearchWorkflowInstanceStorage esStorage;
    
    @Override
    public WorkflowInstance findById(String id) {
        // Use PostgreSQL for ID lookups (relational integrity)
        return pgStorage.findById(id);
    }
    
    @Override
    public List<WorkflowInstance> findByQuery(QueryFilter filter) {
        // Use Elasticsearch for complex queries (performance)
        return esStorage.findByQuery(filter);
    }
}
```

---

### Option 3: Elasticsearch Primary, PostgreSQL Fallback

**Write to ES first**, optionally replicate to PostgreSQL for audit.

```
FluentBit → Elasticsearch (primary)
              ↓ GraphQL (queries ES)
              
FluentBit → PostgreSQL (optional - audit only)
              ↓ Event Tables (historical archive, compliance)
```

**Use Case**: Heavy users who need search performance, but want audit trail.

---

## 🔍 Detailed Analysis

### Storage Abstraction Layer

**Already Exists**: `org.kubesmarts.logic.dataindex.model.Storage` interface

```java
public interface WorkflowInstanceStorage {
    
    // Single entity retrieval
    WorkflowInstance findById(String id);
    
    // Query with filters
    List<WorkflowInstance> findByQuery(QueryFilter filter);
    
    // Pagination
    Page<WorkflowInstance> findPage(PageRequest pageRequest);
    
    // Aggregations (new)
    Map<String, Long> countByStatus();
    
    // Full-text search (new)
    List<WorkflowInstance> search(String query);
}
```

**Implementations**:
```
org.kubesmarts.logic.dataindex.storage/
├── PostgreSQLWorkflowInstanceStorage   # Current implementation
├── ElasticsearchWorkflowInstanceStorage # NEW implementation
└── HybridWorkflowInstanceStorage       # NEW - routes to both
```

---

### FluentBit Configuration

**Dual Output** (Hybrid approach):

```conf
[OUTPUT]
    Name pgsql
    Match workflow.*,task.*
    Host ${POSTGRES_HOST}
    Port 5432
    Database dataindex
    Table workflow_instance_events
    # ... existing PostgreSQL config

[OUTPUT]
    Name es
    Match workflow.*,task.*
    Host ${ELASTICSEARCH_HOST}
    Port 9200
    Index workflow-instances
    Type _doc
    # No normalization needed - direct indexing
```

**Conditional Output** (Either/Or approach):

```conf
# PostgreSQL output (enabled via environment variable)
[OUTPUT]
    Name pgsql
    Match workflow.*,task.*
    Host ${POSTGRES_HOST}
    # Enabled only if STORAGE_BACKEND=postgresql

# Elasticsearch output (enabled via environment variable)
[OUTPUT]
    Name es
    Match workflow.*,task.*
    Host ${ELASTICSEARCH_HOST}
    # Enabled only if STORAGE_BACKEND=elasticsearch
```

---

### Elasticsearch Index Mapping

**No normalization needed** - FluentBit writes events directly to ES.

**Index: `workflow-instances`**
```json
{
  "mappings": {
    "properties": {
      "instanceId": { "type": "keyword" },
      "namespace": { "type": "keyword" },
      "name": { "type": "keyword" },
      "version": { "type": "keyword" },
      "status": { "type": "keyword" },
      "startTime": { "type": "date" },
      "endTime": { "type": "date" },
      "input": { "type": "object", "enabled": false },
      "output": { "type": "object", "enabled": false },
      "error": {
        "properties": {
          "type": { "type": "keyword" },
          "title": { "type": "text" },
          "detail": { "type": "text" },
          "status": { "type": "integer" }
        }
      }
    }
  }
}
```

**Index: `task-executions`**
```json
{
  "mappings": {
    "properties": {
      "taskExecutionId": { "type": "keyword" },
      "instanceId": { "type": "keyword" },
      "taskPosition": { "type": "keyword" },
      "taskName": { "type": "keyword" },
      "enter": { "type": "date" },
      "exit": { "type": "date" },
      "inputArgs": { "type": "object", "enabled": false },
      "outputArgs": { "type": "object", "enabled": false },
      "errorMessage": { "type": "text" }
    }
  }
}
```

**Key Differences from PostgreSQL**:
- ✅ No event tables (no normalization needed)
- ✅ No foreign keys (denormalized)
- ✅ Full-text search on error.title, error.detail, errorMessage
- ✅ Aggregations (count by status, namespace, etc.)

---

### GraphQL Storage Resolution

**Current** (PostgreSQL only):
```java
@GraphQLApi
public class WorkflowInstanceGraphQLApi {
    
    @Inject
    WorkflowInstanceJPAStorage storage; // Hardcoded to PostgreSQL
}
```

**Proposed** (Storage-agnostic):
```java
@GraphQLApi
public class WorkflowInstanceGraphQLApi {
    
    @Inject
    WorkflowInstanceStorage storage; // Interface - resolves at runtime
    
    // GraphQL resolver code stays the same!
}
```

**CDI Qualifier Approach**:
```java
// Qualifier annotation
@Qualifier
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, TYPE})
public @interface StorageBackend {
    String value() default "postgresql";
}

// Producer
@ApplicationScoped
public class StorageProducer {
    
    @Inject
    @StorageBackend("postgresql")
    PostgreSQLWorkflowInstanceStorage pgStorage;
    
    @Inject
    @StorageBackend("elasticsearch")
    ElasticsearchWorkflowInstanceStorage esStorage;
    
    @Produces
    @ApplicationScoped
    public WorkflowInstanceStorage produceStorage(
            @ConfigProperty(name = "data-index.storage.backend") String backend) {
        
        return switch (backend) {
            case "postgresql" -> pgStorage;
            case "elasticsearch" -> esStorage;
            case "hybrid" -> new HybridWorkflowInstanceStorage(pgStorage, esStorage);
            default -> throw new IllegalArgumentException("Unknown storage: " + backend);
        };
    }
}
```

---

## 📊 Trade-offs Analysis

### PostgreSQL Only (Current)

| Aspect | Rating | Notes |
|--------|--------|-------|
| **Write Performance** | ⭐⭐⭐ | Good (normalization adds ~5s latency) |
| **Read Performance** | ⭐⭐⭐⭐ | Good (indexed queries fast) |
| **Search Capabilities** | ⭐⭐ | Limited (basic SQL LIKE, JSONB queries) |
| **Aggregations** | ⭐⭐⭐ | Good (SQL GROUP BY works) |
| **Data Integrity** | ⭐⭐⭐⭐⭐ | Excellent (foreign keys, transactions) |
| **Audit Trail** | ⭐⭐⭐⭐⭐ | Excellent (event tables, 30-day retention) |
| **Operational Complexity** | ⭐⭐⭐⭐ | Simple (single database) |
| **Cost** | ⭐⭐⭐⭐ | Low (PostgreSQL is free) |

**Best For**: Small to medium deployments (< 10,000 workflows/day)

---

### Elasticsearch Only

| Aspect | Rating | Notes |
|--------|--------|-------|
| **Write Performance** | ⭐⭐⭐⭐⭐ | Excellent (no normalization, direct indexing) |
| **Read Performance** | ⭐⭐⭐⭐⭐ | Excellent (optimized for search) |
| **Search Capabilities** | ⭐⭐⭐⭐⭐ | Excellent (full-text, fuzzy, aggregations) |
| **Aggregations** | ⭐⭐⭐⭐⭐ | Excellent (built-in aggregation framework) |
| **Data Integrity** | ⭐⭐ | Limited (no foreign keys, eventual consistency) |
| **Audit Trail** | ⭐⭐ | Limited (no event sourcing, no normalization history) |
| **Operational Complexity** | ⭐⭐⭐ | Medium (ES cluster management) |
| **Cost** | ⭐⭐⭐ | Medium (ES requires more resources) |

**Best For**: Large deployments (> 100,000 workflows/day), heavy search requirements

---

### Hybrid (PostgreSQL + Elasticsearch)

| Aspect | Rating | Notes |
|--------|--------|-------|
| **Write Performance** | ⭐⭐⭐⭐ | Good (writes to both, but parallel) |
| **Read Performance** | ⭐⭐⭐⭐⭐ | Excellent (route to best backend per query) |
| **Search Capabilities** | ⭐⭐⭐⭐⭐ | Excellent (use ES for search) |
| **Aggregations** | ⭐⭐⭐⭐⭐ | Excellent (use ES for aggregations) |
| **Data Integrity** | ⭐⭐⭐⭐⭐ | Excellent (PostgreSQL as source of truth) |
| **Audit Trail** | ⭐⭐⭐⭐⭐ | Excellent (PostgreSQL event tables) |
| **Operational Complexity** | ⭐⭐ | High (manage both PostgreSQL + ES) |
| **Cost** | ⭐⭐ | High (run both systems) |

**Best For**: Enterprise deployments with compliance requirements + high search load

---

## 🎯 Recommendation Matrix

### Use Case → Storage Backend

| Use Case | Users/Day | Search Requirements | Recommendation |
|----------|-----------|---------------------|----------------|
| Small deployment | < 1,000 | Basic (filter by status, namespace) | **PostgreSQL Only** |
| Medium deployment | 1,000 - 10,000 | Moderate (SQL queries sufficient) | **PostgreSQL Only** |
| Large deployment | 10,000 - 100,000 | Advanced (full-text search needed) | **Elasticsearch Only** |
| Enterprise | > 100,000 | Advanced + Compliance | **Hybrid (PostgreSQL + ES)** |
| SaaS Platform | Variable | Advanced search + Multi-tenancy | **Elasticsearch Only** |

---

### Feature Comparison

| Feature | PostgreSQL | Elasticsearch | Hybrid |
|---------|-----------|---------------|--------|
| **Event Normalization** | ✅ Yes | ❌ No (not needed) | ✅ Yes (PostgreSQL) |
| **Audit Trail** | ✅ 30-day event retention | ❌ No | ✅ Yes (PostgreSQL) |
| **Full-Text Search** | ⚠️ Limited (LIKE queries) | ✅ Excellent | ✅ Excellent (ES) |
| **Aggregations** | ✅ SQL GROUP BY | ✅ ES aggregations framework | ✅ Best of both |
| **Foreign Keys** | ✅ Yes | ❌ No | ✅ Yes (PostgreSQL) |
| **JSON Queries** | ✅ JSONB operators | ✅ Native JSON | ✅ Both |
| **Geo Queries** | ⚠️ PostGIS extension | ✅ Built-in | ✅ Both |
| **Fuzzy Search** | ❌ No | ✅ Yes | ✅ Yes (ES) |
| **Time-series Aggregations** | ⚠️ Manual SQL | ✅ Date histograms | ✅ Excellent (ES) |

---

## 🏗️ Implementation Approach

### Phase 1: Storage Abstraction (Prepare for ES)

**Goal**: Make GraphQL storage-agnostic without adding ES yet.

**Tasks**:
1. ✅ Storage API already exists (`WorkflowInstanceStorage` interface)
2. Rename `WorkflowInstanceJPAStorage` → `PostgreSQLWorkflowInstanceStorage`
3. Add `@StorageBackend("postgresql")` qualifier
4. Update GraphQL to inject `WorkflowInstanceStorage` (interface, not impl)
5. Add `StorageProducer` with configuration-based resolution
6. Test with PostgreSQL only (no functional change)

**Benefit**: Zero risk - just refactoring for future flexibility.

---

### Phase 2: Elasticsearch Storage Implementation

**Goal**: Implement `ElasticsearchStorage`, route via configuration.

**Tasks**:
1. Add Elasticsearch dependencies
   ```xml
   <dependency>
     <groupId>co.elastic.clients</groupId>
     <artifactId>elasticsearch-java</artifactId>
   </dependency>
   <dependency>
     <groupId>io.quarkus</groupId>
     <artifactId>quarkus-elasticsearch-rest-client</artifactId>
   </dependency>
   ```

2. Create `ElasticsearchWorkflowInstanceStorage implements WorkflowInstanceStorage`
   ```java
   @StorageBackend("elasticsearch")
   @ApplicationScoped
   public class ElasticsearchWorkflowInstanceStorage implements WorkflowInstanceStorage {
       
       @Inject
       ElasticsearchClient esClient;
       
       @Override
       public WorkflowInstance findById(String id) {
           GetResponse<WorkflowInstanceDocument> response = esClient.get(
               g -> g.index("workflow-instances").id(id),
               WorkflowInstanceDocument.class
           );
           return map(response.source());
       }
       
       @Override
       public List<WorkflowInstance> findByQuery(QueryFilter filter) {
           // Build ES query from filter
           // Use ES Query DSL for complex searches
       }
   }
   ```

3. Create ES index mappings (see earlier section)

4. Configure FluentBit ES output

5. Test with `data-index.storage.backend=elasticsearch`

---

### Phase 3: Hybrid Storage (Optional)

**Goal**: Support both simultaneously with query routing.

**Tasks**:
1. Create `HybridWorkflowInstanceStorage`
2. Implement query routing logic (ID → PostgreSQL, Search → ES)
3. Configure FluentBit dual output
4. Test with `data-index.storage.backend=hybrid`

---

## 📝 Configuration Examples

### PostgreSQL Only (Default)
```properties
# Storage backend
data-index.storage.backend=postgresql

# PostgreSQL config (existing)
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/dataindex

# Event processor (existing)
data-index.event-processor.enabled=true
data-index.event-processor.interval=5s
```

### Elasticsearch Only
```properties
# Storage backend
data-index.storage.backend=elasticsearch

# Elasticsearch config
quarkus.elasticsearch.hosts=localhost:9200
data-index.elasticsearch.index.workflow-instances=workflow-instances
data-index.elasticsearch.index.task-executions=task-executions

# Event processor DISABLED (no normalization needed)
data-index.event-processor.enabled=false
```

### Hybrid (Both)
```properties
# Storage backend
data-index.storage.backend=hybrid

# PostgreSQL config
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/dataindex

# Elasticsearch config
quarkus.elasticsearch.hosts=localhost:9200

# Event processor ENABLED (for PostgreSQL normalization)
data-index.event-processor.enabled=true

# Query routing
data-index.hybrid.id-lookup=postgresql      # Use PostgreSQL for findById
data-index.hybrid.search=elasticsearch      # Use ES for search queries
data-index.hybrid.aggregations=elasticsearch # Use ES for aggregations
```

---

## 🚀 Migration Strategy

### For Existing PostgreSQL Users

**Gradual Migration**:
1. Enable Elasticsearch in parallel (hybrid mode)
2. FluentBit writes to both PostgreSQL + ES
3. Monitor ES index health
4. Gradually route read queries to ES
5. Eventually disable PostgreSQL event processors (keep audit trail only)

**Rollback Plan**:
- Keep PostgreSQL as source of truth
- Can switch back to `storage.backend=postgresql` instantly
- FluentBit continues writing to PostgreSQL

---

### For New Deployments

**Start with Elasticsearch** if:
- High search requirements
- > 10,000 workflows/day
- No strict relational integrity needs

**Configuration**:
```properties
data-index.storage.backend=elasticsearch
data-index.event-processor.enabled=false
```

**FluentBit** writes directly to ES (no normalization overhead).

---

## 🎯 Decision Matrix

| Question | Answer | Recommendation |
|----------|--------|----------------|
| Do you need full-text search? | No | **PostgreSQL** |
| Do you have > 10,000 workflows/day? | No | **PostgreSQL** |
| Do you need audit trail / compliance? | Yes | **PostgreSQL** or **Hybrid** |
| Do you need fuzzy search / aggregations? | Yes | **Elasticsearch** or **Hybrid** |
| Do you have operational expertise for ES? | No | **PostgreSQL** |
| Is budget tight? | Yes | **PostgreSQL** |
| Do you need best of both worlds? | Yes | **Hybrid** (costs more) |

---

## 📚 References

### Patterns
- **Polyglot Persistence**: [martinfowler.com/bliki/PolyglotPersistence.html](https://martinfowler.com/bliki/PolyglotPersistence.html)
- **CQRS with Multiple Read Models**: [docs.microsoft.com/en-us/azure/architecture/patterns/cqrs](https://docs.microsoft.com/en-us/azure/architecture/patterns/cqrs)
- **Storage Abstraction**: Spring Data Repository pattern

### Technologies
- **Elasticsearch Java Client**: [elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html](https://www.elastic.co/guide/en/elasticsearch/client/java-api-client/current/index.html)
- **Quarkus Elasticsearch**: [quarkus.io/guides/elasticsearch](https://quarkus.io/guides/elasticsearch)
- **FluentBit Elasticsearch Output**: [docs.fluentbit.io/manual/pipeline/outputs/elasticsearch](https://docs.fluentbit.io/manual/pipeline/outputs/elasticsearch)

---

## ✅ Summary

### Best Approach: **Conditional Routing (Option 1)** 

**Why**:
- ✅ Simple to implement (storage abstraction already exists)
- ✅ Flexible (choose backend via configuration)
- ✅ Lower operational complexity than Hybrid
- ✅ Can add Hybrid later if needed
- ✅ Each user chooses what fits their scale

### Implementation Order:
1. **Phase 1**: Storage abstraction refactoring (low risk)
2. **Phase 2**: Elasticsearch storage implementation
3. **Phase 3**: Hybrid support (optional, for enterprise users)

### Default Recommendation:
- **Small/Medium users**: PostgreSQL (current implementation)
- **Large/Heavy users**: Elasticsearch (Phase 2)
- **Enterprise users**: Hybrid (Phase 3, if needed)

**Next Step**: Would you like me to implement Phase 1 (Storage Abstraction) to prepare for Elasticsearch support?
