# Data Index Architecture Summary

## Three Deployment Modes

### Mode 1: PostgreSQL Triggers (Production Ready)

```
┌──────────────┐      ┌──────────────┐      ┌────────────────────────┐
│ Quarkus Flow │──┬──>│  FluentBit   │─────>│  PostgreSQL            │
└──────────────┘  │   └──────────────┘      │  - raw tables          │
                  │                          │    • workflow_events_  │
               (logs)                        │      raw (JSONB)       │
                                             │    • task_events_raw   │
                                             │      (JSONB)           │
                                             └────────────────────────┘
                                                       │
                                                       │ BEFORE INSERT triggers
                                                       │ (immediate, < 1ms)
                                                       ▼
                                             ┌────────────────────────┐
                                             │  Trigger Functions     │
                                             │  - Extract JSONB fields│
                                             │  - UPSERT normalized   │
                                             │  - COALESCE for out-of-│
                                             │    order events        │
                                             └────────────────────────┘
                                                       │
                                                       ▼
                                             ┌────────────────────────┐
                                             │  PostgreSQL            │
                                             │  - normalized tables   │
                                             │    • workflow_instances│
                                             │    • task_instances    │
                                             └────────────────────────┘
                                                       │
                                                       ▼
                                             ┌────────────────────────┐
                                             │  Data Index GraphQL    │
                                             └────────────────────────┘
```

**Key Characteristics:**
- ✅ **Production ready** - complete E2E testing
- ✅ **Real-time** - triggers fire immediately (< 1ms)
- ✅ **Simplest deployment** - no Event Processor service
- ✅ **ACID transactions** - guaranteed consistency
- ✅ **Idempotent** - UPSERT with COALESCE handles replays
- ✅ **Out-of-order safe** - COALESCE preserves existing values
- ⚠️ **< 50K workflows/day** throughput (PostgreSQL limit)
- ❌ Limited full-text search

**Configuration:**
```properties
# No event processor configuration needed - triggers handle normalization
kogito.apps.persistence.type=postgresql
kogito.data-index.domain-indexing=false
kogito.data-index.blocking=true
```

---

### Mode 2: Elasticsearch (Search/Analytics)

```
┌──────────────┐      ┌──────────────┐      ┌────────────────────────┐
│ Quarkus Flow │──┬──>│  FluentBit   │──┬──>│  Elasticsearch         │
└──────────────┘  │   └──────────────┘  │   │  - raw event indices   │
                  │                     │   │    • workflow-events   │
               (logs)                   │   │    • task-events       │
                                        │   └────────────────────────┘
                                        │             │
                                        │             │ (ES Transform, automatic, ~1s)
                                        │             ▼
                                        │   ┌────────────────────────┐
                                        │   │  ES Transform          │
                                        │   │  (Painless scripts)    │
                                        │   │  - Out-of-order        │
                                        │   │  - Task correlation    │
                                        │   │  - COALESCE logic      │
                                        │   └────────────────────────┘
                                        │             │
                                        │             ▼
                                        │   ┌────────────────────────┐
                                        └──>│  Elasticsearch         │
                                            │  - normalized indices  │
                                            │    • workflow-instances│
                                            │    • task-executions   │
                                            └────────────────────────┘
                                                      │
                                                      ▼
                                            ┌────────────────────────┐
                                            │  Data Index GraphQL    │
                                            └────────────────────────┘
```

**Key Characteristics:**
- ✅ **No Java event processor code on our side!**
- ✅ Excellent full-text search
- ✅ High throughput (100K+ workflows/day)
- ✅ Simplest scaling path
- ⚠️ ~1s latency (ES Transform)
- ⚠️ Eventual consistency (no ACID)
- ⚠️ Painless scripts for aggregation logic

**Configuration:**
```properties
data-index.event-processor.enabled=false  # ES Transform handles it
data-index.storage.backend=elasticsearch
quarkus.elasticsearch.hosts=elasticsearch:9200
```

**ES Transform Setup:**
```bash
# Create raw event indices
PUT /workflow-events
PUT /task-events

# Create and start transforms
PUT _transform/workflow-instances-transform
POST _transform/workflow-instances-transform/_start

PUT _transform/task-executions-transform
POST _transform/task-executions-transform/_start
```

---

### Mode 3: Kafka + PostgreSQL (Scale + ACID)

```
┌──────────────┐      ┌──────────────┐      ┌────────────────────────┐
│ Quarkus Flow │──┬──>│  FluentBit   │──┬──>│  Kafka                 │
└──────────────┘  │   └──────────────┘  │   │  - topics              │
                  │                     │   │    • workflow-events   │
               (logs)                   │   │    • task-events       │
                                        │   └────────────────────────┘
                                        │             │
                                        │             │ (real-time streaming)
                                        │             ▼
                                        │   ┌────────────────────────┐
                                        │   │  Event Processor       │
                                        │   │  (Java, Kafka consumer)│
                                        │   └────────────────────────┘
                                        │             │
                                        │             ▼
                                        │   ┌────────────────────────┐
                                        └──>│  PostgreSQL            │
                                            │  - normalized tables   │
                                            │    • workflow_instances│
                                            │    • task_executions   │
                                            │  (NO event tables!)    │
                                            └────────────────────────┘
                                                      │
                                                      ▼
                                            ┌────────────────────────┐
                                            │  Data Index GraphQL    │
                                            └────────────────────────┘
```

**Key Characteristics:**
- ✅ Real-time processing (<100ms latency)
- ✅ ACID transactions
- ✅ Event replay (Kafka retention)
- ✅ Highest throughput (100K+ workflows/day)
- ✅ Decoupling (multiple consumers possible)
- ⚠️ Most complex infrastructure
- ⚠️ Kafka operational overhead

**Configuration:**
```properties
data-index.event-processor.mode=kafka
data-index.event-processor.enabled=true
data-index.storage.backend=postgresql

kafka.bootstrap.servers=kafka:9092
mp.messaging.incoming.workflow-events.connector=smallrye-kafka
mp.messaging.incoming.task-events.connector=smallrye-kafka
```

---

## Decision Matrix

| Your Need | Recommended Mode |
|-----------|------------------|
| Getting started, simple deployment | **Mode 1** (Polling + PostgreSQL) |
| Need full-text search, analytics | **Mode 2** (Elasticsearch) |
| Need search + simplest scaling | **Mode 2** (Elasticsearch) |
| Need ACID + high throughput | **Mode 3** (Kafka + PostgreSQL) |
| Need event replay capability | **Mode 3** (Kafka + PostgreSQL) |
| Want to avoid writing event processor code | **Mode 2** (Elasticsearch) |

---

## Migration Path

```
Mode 1 (Polling + PGSQL)
    │
    ├──> Mode 2 (ES) ──────> Scale for search/analytics
    │                        (No Java processor code needed!)
    │
    └──> Mode 3 (Kafka + PGSQL) ──> Scale for ACID + real-time
                                     (Event replay available)
```

**Mode 2 is the sweet spot for most use cases** - it scales well, provides excellent search, and requires no event processor code on our side.

---

## Data Retention Strategy

### Mode 1 & 3: PostgreSQL

**Event Tables** (workflow_instance_events, task_execution_events):
- **Retention**: 30 days (configurable via `data-index.event-processor.retention-days`)
- **Purpose**: Source for event processing, audit trail
- **Cleanup**: Automatic daily job deletes processed events older than retention period
- **Size**: ~1GB per 10K workflows

**Normalized Tables** (workflow_instances, task_executions):
- **Retention**: Forever (permanent history)
- **Purpose**: GraphQL queries
- **Size**: ~100MB per 10K workflows (deduplicated, aggregated)

**Configuration:**
```properties
# Retention period for event tables
data-index.event-processor.retention-days=30

# Cleanup runs daily
quarkus.scheduler.cron.cleanup-events=0 0 2 * * ?
```

### Mode 2: Elasticsearch

**Raw Event Indices** (workflow-events, task-events):
- **Retention**: 7 days (automatic via ILM policy)
- **Purpose**: ES Transform source, late arrival buffer, audit trail
- **Cleanup**: ILM automatically deletes indices older than 7 days
- **Size**: ~100GB per 100K workflows/day

**Normalized Indices** (workflow-instances, task-executions):
- **Retention**: Forever (permanent history)
- **Purpose**: GraphQL queries, analytics
- **Size**: ~10GB per 100K workflows/day (aggregated, deduplicated)

**ILM Policy:**
```json
PUT _ilm/policy/data-index-events-retention
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {"max_age": "1d"}
        }
      },
      "delete": {
        "min_age": "7d",
        "actions": {"delete": {}}
      }
    }
  }
}
```

**Why delete raw events:**
- ✅ Already aggregated into normalized indices
- ✅ 7 days buffer for late arrivals (default delay: 5 min)
- ✅ Normalized indices never deleted (permanent history)

---

## JSON Field Queryability

**Critical Feature**: Users need to query workflow/task input and output data.

**Example**: Find workflows where `input.customerId = "customer-123"`

### PostgreSQL (Modes 1 & 3) ✅ **WORKS**

PostgreSQL JSONB supports querying nested fields:

```sql
-- Query workflow instances by customer ID
SELECT * FROM workflow_instances 
WHERE input_data->>'customerId' = 'customer-123';

-- Complex nested query
SELECT * FROM workflow_instances 
WHERE input_data @> '{"order": {"priority": "high"}}';
```

**GraphQL (when implemented):**
```graphql
{
  getWorkflowInstances(
    filter: {
      input: { customerId: { eq: "customer-123" } }
    }
  ) {
    id
    name
    input
  }
}
```

**Infrastructure**: ✅ `JsonPredicateBuilder` exists for JSONB queries

### Elasticsearch (Mode 2) ✅ **WORKS**

Elasticsearch uses **`flattened` field type** for queryable JSON:

```json
PUT /workflow-instances
{
  "mappings": {
    "properties": {
      "input": {
        "type": "flattened"
      },
      "output": {
        "type": "flattened"
      }
    }
  }
}
```

**Query example:**
```json
GET /workflow-instances/_search
{
  "query": {
    "term": {
      "input.customerId": "customer-123"
    }
  }
}
```

**Benefits:**
- ✅ Arbitrary JSON structure (no schema needed)
- ✅ Dot-notation queries: `input.order.priority`
- ✅ Memory efficient

**Limitations:**
- ⚠️ All values stored as keywords (no full-text search within nested values)
- ⚠️ No per-field scoring

### GraphQL Filtering Status

**Current**: ✅ **IMPLEMENTED** - Basic filtering works  
**Status**: 🚧 Needs integration testing

**What Works:**
1. ✅ Filter input types defined (StringFilter, DateTimeFilter, JsonFilter, etc.)
2. ✅ GraphQL resolver accepts filter parameters
3. ✅ FilterConverter translates GraphQL filters → AttributeFilter
4. ✅ JSON filters marked for JsonPredicateBuilder (`setJson(true)`)
5. ✅ Unit tests passing (10/10)

**Example Query:**
```graphql
{
  getWorkflowInstances(
    filter: {
      status: { eq: COMPLETED }
      input: { eq: { customerId: "customer-123" } }
    }
    limit: 50
  ) {
    id
    name
    input
    output
  }
}
```

**What's Next:**
- Integration tests with PostgreSQL
- Elasticsearch storage implementation

📖 **[See GRAPHQL-FILTERING-TODO.md for details](GRAPHQL-FILTERING-TODO.md)**

---

## GraphQL API: Same for All Modes

The beauty of the Storage abstraction pattern:

```graphql
# Same GraphQL schema for all three modes
query GetWorkflowInstance {
  getWorkflowInstance(id: "instance-123") {
    id
    name
    status
    startTime
    endTime
    taskExecutions {
      taskName
      taskPosition
      enter
      exit
    }
  }
}
```

**GraphQL doesn't know or care which backend is active!**

- Mode 1: Queries PostgreSQL via JPA
- Mode 2: Queries Elasticsearch via RestClient
- Mode 3: Queries PostgreSQL via JPA

The `WorkflowInstanceStorage` interface abstracts it all away.
