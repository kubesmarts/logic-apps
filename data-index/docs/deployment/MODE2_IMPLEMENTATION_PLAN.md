# MODE 2: Elasticsearch Implementation Plan

**Status:** 📋 Planned  
**Target Date:** TBD  
**Complexity:** Medium  
**Dependencies:** MODE 1 complete ✅

## Overview

MODE 2 uses Elasticsearch for storing and querying workflow execution data, providing advanced search capabilities, time-series analytics, and horizontal scalability.

**Architecture simplified:** FluentBit writes directly to Elasticsearch using the ES output plugin. Elasticsearch Ingest Pipelines normalize events in real-time (same pattern as PostgreSQL triggers in MODE 1).

## Architecture

```
Quarkus Flow App
    ↓ (stdout - JSON events)
Kubernetes /var/log/containers/
    ↓ (FluentBit DaemonSet)
FluentBit ES Output Plugin
    ↓ (Ingest Pipeline - normalize on write)
Elasticsearch Indices
    - workflow-instances (normalized, searchable)
    - task-instances (normalized, searchable)
    - workflow-events-raw (raw, debugging, 7-day retention)
    - task-events-raw (raw, debugging, 7-day retention)
    ↓ (Elasticsearch REST API)
Data Index GraphQL API
```

## Benefits Over MODE 1

| Feature | MODE 1 (PostgreSQL) | MODE 2 (Elasticsearch) |
|---------|---------------------|------------------------|
| Full-text search | ❌ Limited (LIKE) | ✅ Advanced (Lucene) |
| Time-series queries | ⚠️ Basic | ✅ Optimized |
| Horizontal scaling | ⚠️ Vertical only | ✅ Cluster sharding |
| Schema flexibility | ❌ Rigid (DDL) | ✅ Dynamic mapping |
| Aggregations | ⚠️ Basic SQL | ✅ Advanced (buckets) |
| Event replay | ❌ No | ⚠️ Reindex from raw (limited retention) |
| Normalization | ✅ PostgreSQL triggers | ✅ Ingest Pipelines |
| Deployment complexity | ✅ Simple (PGSQL + FluentBit) | ⚠️ Moderate (ES cluster + FluentBit) |

## Why No Kafka/Event Processor?

**Previous architecture:** FluentBit → Kafka → Event Processor → Elasticsearch

**Simplified architecture:** FluentBit → Elasticsearch (Ingest Pipelines)

**Rationale:**
- FluentBit already has Elasticsearch output plugin
- Elasticsearch Ingest Pipelines normalize events (same as PostgreSQL triggers)
- No need for Kafka buffering (FluentBit handles retries)
- No need for Event Processor (Ingest Pipelines handle normalization)
- Fewer components = simpler deployment, less operational overhead

**Result:** Same minimal latency as MODE 1, fewer services to deploy.

## Use Cases

**Choose MODE 2 when you need:**
- Full-text search across workflow/task data
- Complex time-series analytics
- High query throughput (1000s req/sec)
- Schema evolution without downtime
- Multiple search indices (by namespace, by date, etc.)
- Horizontal scalability

**Choose MODE 1 when you need:**
- Relational integrity (foreign keys, transactions)
- Simpler deployment (single PostgreSQL instance)
- Lower infrastructure cost
- JPA/Hibernate ORM

## Implementation Tasks

### Phase 1: Elasticsearch Infrastructure
- [ ] Deploy Elasticsearch cluster (ECK operator or Helm)
- [ ] Create index templates (workflow-instances, task-instances)
- [ ] Create index templates (workflow-events-raw, task-events-raw)
- [ ] Configure ILM policies (7-day retention on raw indices)
- [ ] Elasticsearch health checks

### Phase 2: Ingest Pipelines
- [ ] Design workflow normalization pipeline (extract fields from JSON event)
- [ ] Design task normalization pipeline
- [ ] Handle out-of-order events (timestamp-based updates)
- [ ] Idempotency logic (field-level COALESCE equivalent)
- [ ] Test pipelines with sample events
- [ ] Document pipeline configuration

### Phase 3: FluentBit Integration
- [ ] Configure FluentBit Elasticsearch output
- [ ] Route workflow events to workflow-instances index (with pipeline)
- [ ] Route task events to task-instances index (with pipeline)
- [ ] Store raw events in *-events-raw indices
- [ ] Test FluentBit → Elasticsearch flow
- [ ] Verify CRI parser compatibility

### Phase 4: Elasticsearch Storage Layer
- [ ] Create Elasticsearch mappings (workflow_instances, task_instances)
- [ ] Implement ElasticsearchWorkflowInstanceRepository
- [ ] Implement ElasticsearchTaskInstanceRepository
- [ ] Add Elasticsearch health checks
- [ ] Unit tests for repository layer

### Phase 5: GraphQL API Integration
- [ ] Wire Elasticsearch repositories to GraphQL resolvers
- [ ] Implement filtering (Elasticsearch Query DSL)
- [ ] Implement sorting (Elasticsearch sort)
- [ ] Implement pagination (search_after)
- [ ] Full-text search in GraphQL schema

### Phase 6: Deployment & Testing
- [ ] KIND cluster deployment script
- [ ] Elasticsearch Helm chart configuration
- [ ] E2E testing guide
- [ ] Performance benchmarks
- [ ] Production deployment guide

## Technical Decisions

### FluentBit to Elasticsearch Direct

**Decision:** Use FluentBit Elasticsearch output plugin with Ingest Pipelines

**Configuration:**
```conf
[OUTPUT]
    Name            es
    Match           workflow.instance.*
    Host            ${ES_HOST}
    Port            ${ES_PORT}
    Index           workflow-instances
    Type            _doc
    Pipeline        workflow-normalization
    Retry_Limit     5
    Suppress_Type_Name On

[OUTPUT]
    Name            es
    Match           workflow.task.*
    Host            ${ES_HOST}
    Port            ${ES_PORT}
    Index           task-instances
    Type            _doc
    Pipeline        task-normalization
    Retry_Limit     5
```

**Raw events for debugging:**
```conf
[OUTPUT]
    Name            es
    Match           workflow.instance.*
    Host            ${ES_HOST}
    Port            ${ES_PORT}
    Index           workflow-events-raw-%Y.%m.%d
    Type            _doc
    Logstash_Format On
    Logstash_Prefix workflow-events-raw
```

**Rationale:**
- No intermediate Kafka layer needed
- Ingest Pipelines handle normalization on write (like PostgreSQL triggers)
- FluentBit handles retries and backpressure
- Simpler architecture, fewer failure points

### Ingest Pipeline Design

**Decision:** Elasticsearch Ingest Pipelines for normalization (equivalent to PostgreSQL triggers)

**Workflow normalization pipeline:**
```json
{
  "description": "Normalize workflow events (same logic as MODE 1 triggers)",
  "processors": [
    {
      "set": {
        "field": "_id",
        "value": "{{instanceId}}"
      }
    },
    {
      "script": {
        "lang": "painless",
        "source": """
          // Field-level idempotency (same as PostgreSQL trigger)
          def existing = ctx._source;
          def incoming = ctx;
          
          // Immutable fields: First event wins
          if (existing.containsKey('start') && existing.start != null) {
            ctx.start = existing.start;
            ctx.input = existing.input;
            ctx.name = existing.name;
          }
          
          // Terminal fields: Preserve if already set
          if (existing.containsKey('end') && existing.end != null) {
            ctx.end = existing.end;
            ctx.output = existing.output;
          }
          
          // Status: Use timestamp to determine winner
          if (existing.containsKey('last_event_time')) {
            if (incoming.eventTime <= existing.last_event_time) {
              ctx.status = existing.status;
            }
          }
          
          // Timestamp: Keep latest
          ctx.last_event_time = Math.max(
            ctx.eventTime ?: 0, 
            existing.last_event_time ?: 0
          );
        """
      }
    },
    {
      "convert": {
        "field": "startTime",
        "type": "long",
        "target_field": "start"
      }
    },
    {
      "date": {
        "field": "start",
        "formats": ["UNIX"]
      }
    }
  ]
}
```

**Rationale:**
- Same field-level idempotency logic as PostgreSQL triggers
- Handle out-of-order events (COMPLETED before STARTED)
- Use document `_id` = instanceId for upsert behavior
- Immutable fields (start, input, name) never overwritten
- Terminal fields (end, output) preserved once set
- Status determined by event timestamp

### Index Strategy

**Decision:** Separate indices for raw vs normalized, time-based for raw

**Indices:**
- `workflow-instances` (normalized, searchable, long retention)
- `task-instances` (normalized, searchable, long retention)
- `workflow-events-raw-YYYY.MM.DD` (time-based, 7-day ILM)
- `task-events-raw-YYYY.MM.DD` (time-based, 7-day ILM)

**Rationale:**
- Normalized indices for GraphQL queries (fast, optimized mappings)
- Raw indices for debugging (all original data, short retention)
- Time-based raw indices enable efficient cleanup (ILM deletes old indices)

### Schema Mapping

**Decision:** Explicit mappings with strict dynamic enforcement

**Workflow instances mapping:**
```json
{
  "mappings": {
    "dynamic": "strict",
    "properties": {
      "id": {"type": "keyword"},
      "namespace": {"type": "keyword"},
      "name": {
        "type": "text",
        "fields": {
          "keyword": {"type": "keyword"}
        }
      },
      "version": {"type": "keyword"},
      "status": {"type": "keyword"},
      "start": {"type": "date"},
      "end": {"type": "date"},
      "last_update": {"type": "date"},
      "last_event_time": {"type": "date"},
      "input": {"type": "object", "enabled": false},
      "output": {"type": "object", "enabled": false},
      "error_type": {"type": "keyword"},
      "error_title": {"type": "text"},
      "error_detail": {"type": "text"},
      "error_status": {"type": "integer"},
      "error_instance": {"type": "keyword"}
    }
  }
}
```

**Rationale:**
- `keyword` for exact match, aggregations, sorting
- `text` for full-text search (with `.keyword` subfield for exact match)
- `object` with `enabled: false` for JSONB (store but don't index - saves space)
- `strict` dynamic to prevent schema pollution

## Dependencies

**Required:**
- Elasticsearch 8.x cluster (3 nodes minimum for production)
- FluentBit 3.0+ with Elasticsearch output
- Quarkus Elasticsearch REST client
- elasticsearch-java library

**Helm Charts:**
- ECK (Elastic Cloud on Kubernetes) operator
- Elasticsearch cluster

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Ingest Pipeline errors | Monitor ingest failures, dead letter queue |
| Storage costs (raw + normalized) | 7-day ILM on raw indices |
| Query complexity | Document Query DSL patterns, provide examples |
| Schema evolution | Use index aliases, versioned indices |
| FluentBit backpressure | Configure buffer limits, monitor ES cluster health |

## Success Criteria

- [ ] E2E test: workflow execution → Elasticsearch → GraphQL query
- [ ] Full-text search working (e.g., search workflow name)
- [ ] Time-series aggregation working (e.g., workflows per day)
- [ ] Out-of-order events handled correctly (COMPLETED before STARTED)
- [ ] Idempotency verified (replay events, no data corruption)
- [ ] Performance: > 1000 queries/sec on 3-node cluster
- [ ] Deployment documented and tested in KIND

## Comparison with MODE 1

| Aspect | MODE 1 (PostgreSQL) | MODE 2 (Elasticsearch) |
|--------|---------------------|------------------------|
| **Normalization** | PostgreSQL triggers | Ingest Pipelines |
| **FluentBit Output** | pgsql plugin | es plugin |
| **Idempotency** | SQL UPSERT + COALESCE | Painless script + doc update |
| **Storage** | Single RDBMS | Distributed cluster |
| **Query Language** | SQL (JPA) | Query DSL (REST API) |
| **Components** | FluentBit + PostgreSQL | FluentBit + Elasticsearch |
| **Deployment** | Simple | Moderate (cluster) |

## Migration from MODE 1

**Path:** MODE 1 → MODE 2

**Steps:**
1. Deploy Elasticsearch cluster
2. Create index templates and Ingest Pipelines
3. Deploy FluentBit with ES output (parallel with PGSQL output initially)
4. Verify dual-write (both PostgreSQL and Elasticsearch)
5. Switch Data Index API to Elasticsearch repositories
6. Remove FluentBit PGSQL output
7. Decommission PostgreSQL

**Rollback:** Switch FluentBit output and Data Index API back to PostgreSQL

## References

- Elasticsearch Ingest Pipelines: https://www.elastic.co/guide/en/elasticsearch/reference/current/ingest.html
- FluentBit Elasticsearch Output: https://docs.fluentbit.io/manual/pipeline/outputs/elasticsearch
- ECK Operator: https://www.elastic.co/guide/en/cloud-on-k8s/current/index.html
- Painless Scripting: https://www.elastic.co/guide/en/elasticsearch/reference/current/modules-scripting-painless.html

## Next Steps

1. Create Elasticsearch index templates
2. Design and test Ingest Pipelines
3. Configure FluentBit ES output
4. Test in KIND cluster
5. Implement Elasticsearch repository layer
6. Document deployment procedure
