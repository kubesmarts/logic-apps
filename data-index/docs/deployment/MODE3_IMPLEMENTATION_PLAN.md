# MODE 3: Kafka Event Streaming (Optional - Not Implemented)

**Status:** ⚠️ Future Reference Only - NOT IMPLEMENTED  
**Target Date:** TBD (if business requirements justify it)  
**Complexity:** High  
**Dependencies:** MODE 1 or MODE 2 deployed

⚠️ **IMPORTANT:** This mode is NOT currently implemented. The `data-index-event-processor` module and Kafka infrastructure have been removed from the codebase. This document serves as a reference for potential future implementation if long-term event replay (30+ days) or multiple consumers become requirements.

## Overview

MODE 3 adds Kafka as an optional event buffer for **long-term event replay** and **multiple consumers**. This mode is NOT required for basic Data Index functionality - MODE 1 and MODE 2 already provide minimal latency and real-time normalization.

**Use MODE 3 only when you need:**
- Event replay from weeks/months ago (beyond Kubernetes log retention)
- Multiple downstream consumers (audit, analytics, external systems)
- Integration with existing Kafka-based event systems

## Why MODE 3 is Optional

**MODE 1 and MODE 2 already provide:**
- ✅ Minimal latency (FluentBit → Storage direct)
- ✅ Real-time normalization (triggers/pipelines)
- ✅ Simple architecture (fewer components)
- ✅ Idempotent event processing
- ⚠️ Limited replay (only what's in `/var/log/containers/` - hours/days)

**MODE 3 adds:**
- ✅ Long-term event replay (weeks/months with Kafka retention)
- ✅ Multiple consumers (not just Data Index)
- ✅ Guaranteed event ordering per workflow (partitioning)
- ❌ More components (Kafka cluster, consumer service)
- ❌ Higher operational complexity
- ❌ Higher infrastructure cost

**Decision criteria:**
- Need replay from 30+ days ago? → MODE 3
- Need multiple consumers beyond Data Index? → MODE 3
- Just need Data Index with search/analytics? → MODE 1 or MODE 2

## Architecture

```
Quarkus Flow App
    ↓ (stdout - JSON events)
Kubernetes /var/log/containers/
    ↓ (FluentBit DaemonSet)
FluentBit Kafka Output
    ↓
Kafka Topics (long retention: 30-90 days)
    - workflow-instance-events
    - task-execution-events
    ↓
Kafka Consumer (Event Processor or direct)
    ↓
PostgreSQL or Elasticsearch
    ↓
Data Index GraphQL API
```

## When to Use MODE 3

| Requirement | MODE 1/2 | MODE 3 |
|-------------|----------|---------|
| Real-time indexing | ✅ | ✅ |
| Replay last 24 hours | ✅ (FluentBit tail DB) | ✅ |
| Replay last 30 days | ❌ K8s logs rotated | ✅ Kafka retention |
| Replay last 6 months | ❌ | ✅ Kafka retention |
| Multiple consumers | ❌ Single destination | ✅ Consumer groups |
| Event audit trail | ⚠️ Raw tables (7 days) | ✅ Kafka (30-90 days) |
| Integration with event mesh | ❌ | ✅ Kafka Connect |

## Implementation Tasks

### Phase 1: Kafka Infrastructure
- [ ] Deploy Strimzi Kafka operator
- [ ] Create Kafka cluster (3 brokers minimum)
- [ ] Create topics with retention policy (30-90 days)
- [ ] Configure partitioning (by instanceId)
- [ ] Kafka monitoring setup

### Phase 2: FluentBit Kafka Output
- [ ] Configure FluentBit Kafka output (replace PGSQL/ES output)
- [ ] Topic routing by event type
- [ ] Partition key selection (instanceId for ordering)
- [ ] Test FluentBit → Kafka flow
- [ ] Error handling and retries

### Phase 3: Kafka Consumer
- [ ] Implement consumer (Quarkus Kafka or standalone)
- [ ] Deserialize workflow/task events
- [ ] Write to PostgreSQL or Elasticsearch (reuse MODE 1/2 storage)
- [ ] Exactly-once semantics (no duplicates)
- [ ] Consumer group configuration

### Phase 4: Event Processing
- [ ] Workflow event processing (reuse normalization logic)
- [ ] Task event processing
- [ ] Out-of-order event handling (same as MODE 1/2)
- [ ] Idempotency (same field-level logic)
- [ ] Error handling and DLQ

### Phase 5: Deployment & Testing
- [ ] KIND cluster deployment script
- [ ] Kafka Helm chart configuration
- [ ] E2E testing guide
- [ ] Event replay testing
- [ ] Production deployment guide

### Phase 6: Advanced Features
- [ ] Event replay tool (read from offset/timestamp)
- [ ] Consumer lag monitoring
- [ ] Schema registry (optional)
- [ ] Multiple consumers (audit, analytics)

## Technical Decisions

### Topic Configuration

**Topics:**
```yaml
workflow-instance-events:
  partitions: 12
  replication-factor: 3
  retention.ms: 2592000000  # 30 days
  compression.type: lz4

task-execution-events:
  partitions: 12
  replication-factor: 3
  retention.ms: 2592000000  # 30 days
  compression.type: lz4
```

**Partition Key:** `instanceId` (all events for a workflow → same partition → ordering)

**Retention:** 30 days default (configurable: 7-90 days based on replay needs)

### Consumer Strategy

**Option A: Kafka Streams (Recommended)**
```properties
kafka.bootstrap.servers=kafka:9092
mp.messaging.incoming.workflow-events.connector=smallrye-kafka
mp.messaging.incoming.workflow-events.topic=workflow-instance-events
mp.messaging.incoming.workflow-events.group.id=data-index-processor
```

**Option B: Direct Elasticsearch Sink (Kafka Connect)**
```json
{
  "connector.class": "io.confluent.connect.elasticsearch.ElasticsearchSinkConnector",
  "topics": "workflow-instance-events,task-execution-events",
  "transforms": "normalize",
  "transforms.normalize.type": "..."
}
```

**Decision:** Option A (more control, reuse normalization logic from MODE 1/2)

### Exactly-Once Semantics

**Pattern:** Kafka transactions + Storage transactions

```java
@Transactional
public void processEvent(WorkflowEvent event) {
  // 1. Begin storage transaction
  
  // 2. Normalize and upsert (idempotent - same logic as MODE 1/2)
  workflowRepository.upsert(normalize(event));
  
  // 3. Commit storage transaction
  
  // 4. Commit Kafka offset (after storage commit)
}
```

**Idempotency:** Reuse field-level logic from V2 migration (same schema)

## Replayability

**FluentBit tail DB replay (MODE 1/2):**
```bash
# Delete tail DB to reprocess logs
kubectl exec -n logging <pod> -- rm /tail-db/fluent-bit-kube.db
kubectl delete pod -n logging <pod>

# Limitation: Only replays logs still in /var/log/containers/ (hours/days)
```

**Kafka replay (MODE 3):**
```bash
# Replay last 30 days (or full retention)
kafka-consumer-groups --bootstrap-server kafka:9092 \
  --group data-index-processor \
  --topic workflow-instance-events \
  --reset-offsets --to-datetime 2026-03-25T00:00:00.000 \
  --execute

# Restart consumer - processes from new offset
kubectl rollout restart deployment/data-index-processor
```

**Limitation:** Cannot replay beyond Kafka retention period (30-90 days)

## Storage Layer Reuse

**MODE 3 reuses storage from MODE 1 or MODE 2:**

| Storage | MODE 1 | MODE 2 | MODE 3 |
|---------|---------|---------|---------|
| PostgreSQL | ✅ Triggers | ❌ | ✅ Consumer writes |
| Elasticsearch | ❌ | ✅ Ingest Pipelines | ✅ Consumer writes |

**MODE 3 changes:**
- Remove triggers (MODE 1) or Ingest Pipelines (MODE 2)
- Consumer applies normalization logic directly
- Same database schema, same field-level idempotency

**Why?** Consumer has full event context, can batch writes, handle retries explicitly.

## Performance Targets

| Metric | Target | Notes |
|--------|--------|-------|
| Event ingestion | 10,000 events/sec | FluentBit → Kafka |
| Event processing | 5,000 events/sec | Kafka → Storage |
| Consumer lag | < 10 seconds | Under normal load |
| Replay throughput | 50,000 events/sec | Batch processing, consumer scaling |

## Monitoring

**Key Metrics:**
- Kafka producer throughput (FluentBit)
- Consumer lag (per partition)
- Consumer group health
- Topic partition distribution
- Kafka disk usage (retention)

**Alerts:**
- Consumer lag > 60 seconds
- Consumer group not in stable state
- Kafka broker down
- Disk usage > 80% (retention cleanup failing)

## Migration Paths

### From MODE 1 (PostgreSQL)

**Steps:**
1. Deploy Kafka cluster
2. Add FluentBit Kafka output (parallel with PGSQL output)
3. Verify dual-write (both Kafka and PostgreSQL)
4. Deploy consumer (writes to PostgreSQL)
5. Remove PostgreSQL triggers (consumer handles normalization)
6. Remove FluentBit PGSQL output (Kafka only)
7. Verify replay capability

**Rollback:** Restore triggers, remove Kafka consumer, restore FluentBit PGSQL output

### From MODE 2 (Elasticsearch)

**Steps:**
1. Deploy Kafka cluster
2. Add FluentBit Kafka output (parallel with ES output)
3. Verify dual-write (both Kafka and Elasticsearch)
4. Deploy consumer (writes to Elasticsearch)
5. Remove Ingest Pipelines (consumer handles normalization)
6. Remove FluentBit ES output (Kafka only)
7. Verify replay capability

**Rollback:** Restore Ingest Pipelines, remove consumer, restore FluentBit ES output

## Dependencies

**Required:**
- Kafka 3.x cluster (Strimzi operator)
- PostgreSQL 14+ (if using MODE 1 storage) OR Elasticsearch 8.x (if using MODE 2 storage)
- Quarkus Kafka Streams 3.x (for consumer)
- FluentBit 3.0+ with Kafka output

**Helm Charts:**
- Strimzi Kafka operator
- Kafka cluster (3 brokers minimum)

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Kafka broker failure | 3-broker cluster, replication factor 3 |
| Consumer lag | Scale consumer instances, batch processing |
| Event ordering violations | Partition by instanceId, single consumer per partition |
| Duplicate events | Idempotent writes (same logic as MODE 1/2) |
| Storage costs | Configure retention based on replay needs (7-90 days) |
| Operational complexity | Only deploy if replay/multiple consumers needed |

## Success Criteria

- [ ] E2E test: workflow → Kafka → Storage → GraphQL
- [ ] Event replay from 30 days ago working
- [ ] Idempotency verified (same as MODE 1/2)
- [ ] Out-of-order events handled (same as MODE 1/2)
- [ ] Performance: 10k events/sec ingestion, 5k events/sec processing
- [ ] Consumer lag < 10 seconds
- [ ] Deployment documented and tested in KIND

## When NOT to Use MODE 3

**Don't use MODE 3 if:**
- You only need Data Index functionality (use MODE 1 or MODE 2)
- You don't need replay beyond 24 hours (FluentBit tail DB is sufficient)
- You don't have multiple consumers (Kafka overhead not justified)
- You want simpler operations (MODE 1/2 have fewer components)
- You have limited infrastructure budget (Kafka cluster cost)

**Use MODE 1 or MODE 2 instead** - they provide minimal latency, real-time normalization, and simpler architecture.

## References

- Strimzi Operator: https://strimzi.io/
- Kafka Exactly-Once: https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/
- Quarkus Kafka: https://quarkus.io/guides/kafka
- FluentBit Kafka: https://docs.fluentbit.io/manual/pipeline/outputs/kafka
- Kafka Consumer Groups: https://kafka.apache.org/documentation/#consumerconfigs

## Summary

**MODE 3 is optional.** It adds long-term event replay and multiple consumer support via Kafka, but comes with higher operational complexity and infrastructure cost.

**For most use cases, MODE 1 (PostgreSQL) or MODE 2 (Elasticsearch) is sufficient.**

**Choose MODE 3 only when:**
- Event replay from 30+ days ago is required
- Multiple downstream consumers need the same events
- Integration with existing Kafka-based event architecture
