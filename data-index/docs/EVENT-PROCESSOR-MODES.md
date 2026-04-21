# Event Processor Modes

**Status**: ✅ **IMPLEMENTED** - Two modes available

---

## Overview

The Data Index event processor supports **two distinct processing modes**, each optimized for different deployment scenarios and scale requirements.

```
┌─────────────────────────────────────────────────────────────┐
│                   Event Processor Service                    │
│                                                              │
│  ┌──────────────────────┐  ┌──────────────────────┐        │
│  │  PostgreSQL Polling  │  │    Kafka Real-Time   │        │
│  │  (Simple)            │  │    (Scale)           │        │
│  └──────────────────────┘  └──────────────────────┘        │
│           │                          │                      │
│           └──────────────────────────┘                      │
│                       │                                     │
│            ┌──────────┴──────────┐                         │
│            │  EventProcessor     │                         │
│            │  implementations    │                         │
│            └─────────────────────┘                         │
│                       │                                     │
│            ┌──────────┴──────────┐                         │
│            │  Storage Backend    │                         │
│            │  (PostgreSQL or ES) │                         │
│            └─────────────────────┘                         │
└─────────────────────────────────────────────────────────────┘
```

---

## Mode 1: Polling (PostgreSQL Event Tables)

**Best for**: Simple deployments, < 10K workflows/day, minimal infrastructure

### Architecture

```
FluentBit (sidecar) → PostgreSQL Event Tables (transactional outbox)
                           ↓ (Poll every 5s)
                      EventProcessorScheduler
                           ↓
                PostgreSQL Final Tables (workflow_instances, task_executions)
                           ↓
                   Data Index Service (GraphQL API)
```

### Configuration

**Profile**: Default (`application.properties`)

```properties
# Event processor mode
data-index.event-processor.mode=polling
data-index.event-processor.enabled=true
data-index.event-processor.interval=5s
data-index.event-processor.batch-size=100
data-index.event-processor.retention-days=30

# Storage backend (only PostgreSQL in polling mode)
data-index.storage.backend=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/dataindex
```

### Components

- **EventProcessorScheduler**: Polls event tables every 5 seconds
- **EventCleanupScheduler**: Deletes processed events older than retention period (daily at 2 AM)
- **PostgreSQLWorkflowInstanceEventProcessor**: Processes workflow events
- **PostgreSQLTaskExecutionEventProcessor**: Processes task events

### How It Works

1. **FluentBit** writes JSON logs to PostgreSQL event tables (`workflow_instance_events`, `task_execution_events`)
2. **EventProcessorScheduler** runs every 5s (configurable)
3. For each processor:
   - Fetch batch of unprocessed events (`processed=false`)
   - Group events by instance ID and task position
   - Merge events using COALESCE logic (completed > running > started)
   - Write to final tables
   - Mark events as processed (`processed=true`)
4. **EventCleanupScheduler** deletes old processed events daily (30-day retention)

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-event-processor
spec:
  replicas: 1  # Scale to 2 for HA
  template:
    spec:
      containers:
      - name: event-processor
        image: org.kubesmarts.logic.apps/data-index-event-processor:999-SNAPSHOT
        env:
        - name: DATA_INDEX_EVENT_PROCESSOR_MODE
          value: "polling"
        - name: QUARKUS_DATASOURCE_JDBC_URL
          value: "jdbc:postgresql://postgres:5432/dataindex"
```

### Scaling

- **Low traffic** (< 1K workflows/day): 1 replica
- **Medium traffic** (1K-10K workflows/day): 2 replicas for HA
- **High traffic** (> 10K workflows/day): Switch to Kafka mode

### Pros

✅ **Simplest architecture** - PostgreSQL only, no Kafka
✅ Event replay from PostgreSQL tables
✅ Transactional consistency (events + final tables in same DB)
✅ Easy to reason about and troubleshoot
✅ 30-day audit trail

### Cons

❌ 5-second latency (polling interval)
❌ Database load from polling queries
❌ Limited horizontal scaling (2 replicas max)
❌ Not suitable for high throughput (> 10K workflows/day)

---

## Mode 2: Kafka (Event-Driven with Any Storage Backend)

**Best for**: Large deployments, > 10K workflows/day, real-time requirements

### Architecture

```
FluentBit (sidecar) → Kafka Topics (workflow-events, task-events)
                           ↓ (Event-driven @Incoming)
                     KafkaEventConsumer
                           ↓
         ┌─────────────────┴─────────────────┐
         ↓                                   ↓
  PostgreSQL Final Tables          Elasticsearch Final Indices
  (high consistency)                (search + analytics)
         ↓                                   ↓
     Data Index Service               Data Index Service
      (GraphQL API)                     (GraphQL API)
```

### Configuration

**Profile**: `kafka` (`application-kafka.properties`)

Activate with: `-Dquarkus.profile=kafka`

#### Option 1: Kafka + PostgreSQL

```properties
# Kafka mode
data-index.event-processor.mode=kafka
data-index.event-processor.enabled=false  # Disable polling

# Kafka
kafka.bootstrap.servers=localhost:9092
mp.messaging.incoming.workflow-events.topic=workflow-events
mp.messaging.incoming.task-events.topic=task-events

# Storage backend: PostgreSQL
data-index.storage.backend=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://postgres:5432/dataindex
```

#### Option 2: Kafka + Elasticsearch

```properties
# Kafka mode
data-index.event-processor.mode=kafka
data-index.event-processor.enabled=false

# Kafka
kafka.bootstrap.servers=localhost:9092
mp.messaging.incoming.workflow-events.topic=workflow-events
mp.messaging.incoming.task-events.topic=task-events

# Storage backend: Elasticsearch
data-index.storage.backend=elasticsearch
quarkus.elasticsearch.hosts=elasticsearch:9200
data-index.elasticsearch.index.workflow-instances=workflow-instances
data-index.elasticsearch.index.task-executions=task-executions
```

### FluentBit Configuration

```conf
[OUTPUT]
    Name kafka
    Match workflow.*
    Brokers kafka:9092
    Topics workflow-events

[OUTPUT]
    Name kafka
    Match task.*
    Brokers kafka:9092
    Topics task-events
```

### Components

- **KafkaEventConsumer**: Reactive consumer with `@Incoming` annotations
- **PostgreSQLWorkflowInstanceEventProcessor**: Processes workflow events (same as polling mode)
- **PostgreSQLTaskExecutionEventProcessor**: Processes task events (same as polling mode)
- **ElasticsearchWorkflowInstanceEventProcessor**: Processes workflow events to ES (future)
- **ElasticsearchTaskExecutionEventProcessor**: Processes task events to ES (future)

### How It Works

1. **FluentBit** publishes JSON events to Kafka topics
2. **KafkaEventConsumer** consumes events reactively:
   ```java
   @Incoming("workflow-events")
   @Blocking
   public void consumeWorkflowEvent(WorkflowInstanceEvent event) {
       processor.processEvent(event);  // Single event processing
   }
   ```
3. Event processors use **same COALESCE logic** as polling mode
4. Write directly to final tables/indices (PostgreSQL or Elasticsearch)
5. Kafka handles backpressure, retries, and offset management
6. **No intermediate event tables** - Kafka IS the event store

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-event-processor
spec:
  replicas: 3  # Scale based on Kafka partitions
  template:
    spec:
      containers:
      - name: event-processor
        image: org.kubesmarts.logic.apps/data-index-event-processor:999-SNAPSHOT
        env:
        - name: QUARKUS_PROFILE
          value: "kafka"
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka:9092"
        - name: DATA_INDEX_STORAGE_BACKEND
          value: "postgresql"  # or "elasticsearch"
```

### Scaling

- **Horizontal scaling**: Add more replicas (up to number of Kafka partitions)
- Each replica consumes from different partition
- Kafka consumer group provides automatic load balancing
- Typical: 3-5 replicas for HA and throughput
- Can handle > 100K workflows/day

### Pros

✅ **< 1 second latency** (event-driven, no polling)
✅ **True horizontal scaling** (Kafka partitions)
✅ **Better throughput** (parallel consumption)
✅ **Backpressure handling** (Kafka)
✅ **Event replay from Kafka** (retention policy)
✅ **Decouples ingestion from processing**
✅ **Works with any storage backend** (PostgreSQL or Elasticsearch)
✅ **No intermediate event tables** (Kafka handles event storage)

### Cons

❌ Additional infrastructure (Kafka cluster)
❌ More complex deployment
❌ Need to configure FluentBit Kafka output

---

## Mode Comparison

| Feature | Polling (PostgreSQL) | Kafka (PostgreSQL or ES) |
|---------|---------------------|--------------------------|
| **Latency** | 5 seconds | < 1 second |
| **Scalability** | ⭐⭐⭐ (2 replicas max) | ⭐⭐⭐⭐⭐ (horizontal scaling) |
| **Complexity** | ⭐⭐⭐⭐⭐ (simplest) | ⭐⭐⭐ (requires Kafka) |
| **Event Storage** | PostgreSQL event tables | Kafka topics |
| **Event Retention** | 30 days (configurable) | Kafka retention policy |
| **Infrastructure** | PostgreSQL only | Kafka + (PostgreSQL OR Elasticsearch) |
| **Throughput** | 1K-10K/day | > 100K/day |
| **HA** | 2 replicas max | 5+ replicas |
| **Event Replay** | From PostgreSQL | From Kafka |
| **Storage Backend** | PostgreSQL only | PostgreSQL OR Elasticsearch |

---

## Storage Backend Selection (Kafka Mode Only)

In Kafka mode, you can choose the storage backend:

### PostgreSQL Backend

**Use when**:
- Need strong consistency and ACID transactions
- Relational queries are important
- Existing PostgreSQL expertise/infrastructure

**Pros**:
- ✅ ACID transactions
- ✅ Strong consistency
- ✅ Relational queries
- ✅ Simpler operations

**Cons**:
- ❌ Limited horizontal scaling
- ❌ No full-text search
- ❌ Less efficient for analytics

### Elasticsearch Backend

**Use when**:
- Need full-text search capabilities
- Analytics and aggregations are important
- Large-scale deployments (> 100K workflows/day)

**Pros**:
- ✅ Full-text search
- ✅ Better horizontal scaling
- ✅ Excellent for analytics
- ✅ Aggregations and dashboards

**Cons**:
- ❌ Eventual consistency
- ❌ No transactions
- ❌ More complex operations

---

## Migration Path

### Phase 1: Start Simple (Polling)
```
FluentBit → PostgreSQL Event Tables → Processor → PostgreSQL Final Tables
```
- Deploy with PostgreSQL polling
- Simple, proven architecture
- Good for initial rollout and < 10K workflows/day

### Phase 2: Scale Ingestion (Add Kafka)
```
FluentBit → Kafka → Processor → PostgreSQL Final Tables
```
- Add Kafka cluster
- Update FluentBit configuration (switch to Kafka output)
- Change event processor profile to `kafka`
- Keep PostgreSQL backend
- Scale horizontally with Kafka partitions

### Phase 3: Add Search (Add Elasticsearch)
```
FluentBit → Kafka → Processor → Elasticsearch Final Indices
```
- Add Elasticsearch cluster
- Implement Elasticsearch event processors
- Switch storage backend to `elasticsearch`
- Or run both PostgreSQL and Elasticsearch processors in parallel

---

## Mode Selection at Startup

The event processor validates configuration and logs the active mode:

```
==========================================================
Event Processor Configuration:
  Mode: kafka
  Storage Backend: postgresql
  Kafka Servers: localhost:9092
  ✓ Kafka mode active - KafkaEventConsumer will consume
==========================================================
```

**Validation**:
- Warns if Kafka mode is selected but `kafka.bootstrap.servers` is missing
- Warns if Kafka events are received when mode is not `kafka`
- Skips scheduled processing when mode is not `polling`

---

## COALESCE Logic (All Modes)

Both modes use the **same event merging logic**:

```java
// PostgreSQL example (same for Elasticsearch)
if ("started".equals(eventType) && task.getEnter() == null) {
    task.setEnter(event.getStartTime());
    task.setInputArgs(event.getInputArgs());
}

if ("completed".equals(eventType)) {
    task.setExit(event.getEndTime());  // Always update
    task.setOutputArgs(event.getOutputArgs());
}
```

**Rules**:
- `started` fields only set if NULL (first seen wins)
- `completed` fields always update (last seen wins)
- `faulted` fields always update (last seen wins)
- Out-of-order events handled correctly (completed can arrive before started)

---

## Testing

### Integration Tests

```bash
# Polling mode tests
mvn test -Dtest=PollingModeIT

# Kafka mode tests
mvn test -Dtest=KafkaModeIT
```

### Manual Testing

```bash
# Test polling mode
java -jar data-index-event-processor-runner.jar

# Test Kafka mode with PostgreSQL
java -jar data-index-event-processor-runner.jar -Dquarkus.profile=kafka

# Test Kafka mode with Elasticsearch
java -jar data-index-event-processor-runner.jar \
  -Dquarkus.profile=kafka \
  -Ddata-index.storage.backend=elasticsearch
```

---

## Monitoring

### Metrics (Prometheus)

```
# Polling mode
event_processor_events_processed_total{processor="workflow",mode="polling",status="success"}
event_processor_batch_duration{processor="workflow",mode="polling"}

# Kafka mode
event_processor_events_processed_total{processor="workflow",mode="kafka",status="success"}
event_processor_event_duration{processor="workflow",mode="kafka"}
```

### Health Checks

```bash
# Liveness
curl http://localhost:8081/q/health/live

# Readiness
curl http://localhost:8081/q/health/ready
```

**Health Criteria** (Polling mode only):
- Lag < 60 seconds (oldest unprocessed event)
- Backlog < 1000 events

---

## References

- [Event Processor README](../data-index-event-processor/README.md)
- [Storage Layer Refactoring](STORAGE-LAYER-REFACTORING.md)
- [CQRS Separation](ARCHITECTURE-CQRS-SEPARATION.md)
