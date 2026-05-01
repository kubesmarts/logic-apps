# Elasticsearch Backend (MODE 2) - Implementation Design

**Date:** 2026-04-29  
**Status:** Approved  
**Scope:** Full end-to-end MODE 2 implementation with ES Transform  
**Approach:** Vertical slice (WorkflowInstance → TaskExecution → FluentBit)

---

## Overview

Implement MODE 2 Elasticsearch backend for Data Index v1.0.0, providing high-performance search and analytics for large-scale workflow deployments. This design uses **ES Transform** for event normalization, mirroring the trigger-based approach of MODE 1 PostgreSQL while offering Elasticsearch's scalability and search capabilities.

---

## Architecture

### High-Level Flow

```
Quarkus Flow App
    ↓ (stdout - structured JSON events)
Kubernetes /var/log/containers/*.log
    ↓ (FluentBit DaemonSet - tail input)
FluentBit ES Output Plugin
    ↓ (direct write, no transformation)
Elasticsearch Raw Event Indices
    - workflow-events (timestamped, ILM 7-day retention)
    - task-events (timestamped, ILM 7-day retention)
    ↓ (ES Transform - continuous, every 1s)
Elasticsearch Normalized Indices
    - workflow-instances (aggregated, permanent retention)
    - task-executions (aggregated, permanent retention)
    ↓ (Elasticsearch Java Client)
Data Index Service (Quarkus)
    - ElasticsearchWorkflowInstanceStorage
    - ElasticsearchTaskExecutionStorage
    ↓
GraphQL API (SmallRye GraphQL)
```

### Key Architectural Decisions

**1. ES Transform over Ingest Pipelines**
- **Why Transform:** Handles out-of-order events naturally via aggregations (`min`, `max`, `top_hits`)
- **Reprocessing:** Can delete destination index and reset Transform if normalization logic changes
- **Monitoring:** Built-in Transform Stats API for operational visibility
- **Trade-off:** 1-60 second latency vs trigger-on-write (acceptable for read-only query service)

**2. Flattened Fields for JSON Data**
- **Type:** Elasticsearch `flattened` field type for `input` and `output`
- **Benefit:** Enables dot-notation queries: `input.customerId = "123"` without schema definition
- **Limitation:** No full-text search inside JSON values (keyword-only matching)
- **Consistency:** Matches PostgreSQL JSONB queryability

**3. ILM for Raw Event Cleanup**
- **Retention:** 7 days for raw events (Transform already aggregated them)
- **Permanent:** Normalized indices kept forever (permanent workflow history)
- **Rationale:** Prevents unbounded growth while maintaining audit trail window

**4. No Event Processor Service**
- **Replacement:** ES Transform handles all event processing
- **Benefit:** Fewer components to deploy/maintain, higher resilience (ES cluster manages Transform)
- **Consistency:** Maintains MODE 1's minimal architecture philosophy

**5. Schema Isolation (mirrors PostgreSQL approach)**
- **New Module:** `data-index-storage-elasticsearch-schema` (parallel to `data-index-storage-migrations`)
- **Dev/Test:** Auto-initialize schema from JSON scripts in resources
- **Production:** External operator applies schema, Data Index assumes it exists
- **Universal Flag:** `skipInitSchema` controls both PostgreSQL and Elasticsearch

---

## Module Structure

```
data-index/
├── data-index-storage/
│   ├── data-index-storage-common/                # Storage interfaces
│   ├── data-index-storage-migrations/            # PostgreSQL Flyway SQL
│   ├── data-index-storage-elasticsearch-schema/  # NEW: Elasticsearch JSON scripts
│   ├── data-index-storage-postgresql/            # PostgreSQL JPA
│   └── data-index-storage-elasticsearch/         # Elasticsearch storage (exists)
└── data-index-service/
    ├── data-index-service-core/                  # Shared GraphQL API
    ├── data-index-service-postgresql/            # PostgreSQL service
    └── data-index-service-elasticsearch/         # Elasticsearch service (scaffold only)
```

### New Module: data-index-storage-elasticsearch-schema

**Purpose:** Isolate Elasticsearch schema scripts (ILM, index templates, transforms)

**Structure:**
```
data-index-storage-elasticsearch-schema/
├── pom.xml
└── src/main/resources/
    └── elasticsearch/
        ├── ilm/
        │   └── data-index-events-retention.json
        ├── index-templates/
        │   ├── workflow-events.json
        │   ├── workflow-instances.json
        │   ├── task-events.json
        │   └── task-executions.json
        └── transforms/
            ├── workflow-instances-transform.json
            └── task-executions-transform.json
```

**Responsibilities:**
- Provides `ElasticsearchSchemaInitializer` for dev/test mode
- Loads JSON scripts from resources
- Applies to Elasticsearch cluster on startup (if `data-index.init-schema=true`)
- Packages scripts for operator consumption in production

---

## Schema Design

### 1. ILM Policy: data-index-events-retention.json

**Purpose:** Delete raw events after 7 days (already aggregated by Transform)

```json
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_primary_shard_size": "50GB"
          }
        }
      },
      "delete": {
        "min_age": "7d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

**Rationale:**
- 7-day buffer handles late-arriving events (Transform delay: 60s default)
- Rollover daily prevents single index from growing too large
- Automatic deletion reduces storage costs

---

### 2. Index Templates

#### workflow-events.json (Raw Events)

```json
{
  "index_patterns": ["workflow-events-*"],
  "template": {
    "settings": {
      "index.lifecycle.name": "data-index-events-retention",
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "event_id": {"type": "keyword"},
        "event_type": {"type": "keyword"},
        "event_time": {"type": "date"},
        "instance_id": {"type": "keyword"},
        "workflow_name": {"type": "keyword"},
        "workflow_version": {"type": "keyword"},
        "workflow_namespace": {"type": "keyword"},
        "status": {"type": "keyword"},
        "start_time": {"type": "date"},
        "end_time": {"type": "date"},
        "input_data": {
          "type": "flattened"
        },
        "output_data": {
          "type": "flattened"
        },
        "error": {
          "type": "object",
          "enabled": false
        }
      }
    }
  }
}
```

**Key Points:**
- **Flattened fields:** `input_data`, `output_data` for queryable JSON
- **Error as object disabled:** Raw JSON storage without indexing (reduces overhead)
- **ILM attached:** Automatic lifecycle management

#### workflow-instances.json (Normalized)

```json
{
  "index_patterns": ["workflow-instances"],
  "template": {
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "id": {"type": "keyword"},
        "name": {"type": "keyword"},
        "version": {"type": "keyword"},
        "namespace": {"type": "keyword"},
        "status": {"type": "keyword"},
        "start": {"type": "date"},
        "end": {"type": "date"},
        "input": {
          "type": "flattened"
        },
        "output": {
          "type": "flattened"
        },
        "error": {
          "properties": {
            "type": {"type": "keyword"},
            "title": {"type": "text"},
            "detail": {"type": "text"},
            "status": {"type": "integer"},
            "instance": {"type": "keyword"}
          }
        },
        "last_update": {"type": "date"}
      }
    }
  }
}
```

**Key Points:**
- **No ILM:** Permanent retention (workflow history)
- **Error structure:** Matches domain model (`Error` type)
- **Field naming:** `start`/`end` (not `start_time`/`end_time`) to match GraphQL API

---

### 3. ES Transform Configuration

#### workflow-instances-transform.json

**Purpose:** Aggregate workflow events by `instance_id`, handle out-of-order events

```json
{
  "source": {
    "index": "workflow-events-*",
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "event_time": {
                "gte": "now-1h"
              }
            }
          },
          {
            "bool": {
              "must_not": [
                {"term": {"event_type": "workflow.instance.completed"}},
                {"term": {"event_type": "workflow.instance.faulted"}},
                {"term": {"event_type": "workflow.instance.cancelled"}}
              ]
            }
          }
        ]
      }
    }
  },
  "dest": {
    "index": "workflow-instances"
  },
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "event_time",
      "delay": "60s"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "instance_id"
        }
      }
    },
    "aggregations": {
      "name": {
        "terms": {
          "field": "workflow_name",
          "size": 1
        }
      },
      "version": {
        "terms": {
          "field": "workflow_version",
          "size": 1
        }
      },
      "namespace": {
        "terms": {
          "field": "workflow_namespace",
          "size": 1
        }
      },
      "status": {
        "scripted_metric": {
          "init_script": "state.events = []",
          "map_script": "state.events.add(['status': doc['status'].value, 'time': doc['event_time'].value.toInstant().toEpochMilli()])",
          "combine_script": "return state.events",
          "reduce_script": "def all = params._aggs.stream().flatMap(e -> e.stream()).collect(Collectors.toList()); def terminal = all.stream().filter(e -> e.status == 'COMPLETED' || e.status == 'FAULTED' || e.status == 'CANCELLED').findFirst(); if (terminal.isPresent()) return terminal.get().status; return all.stream().max(Comparator.comparing(e -> e.time)).get().status;"
        }
      },
      "start": {
        "min": {
          "field": "start_time"
        }
      },
      "end": {
        "max": {
          "field": "end_time"
        }
      },
      "input": {
        "top_hits": {
          "size": 1,
          "sort": [{"event_time": {"order": "asc"}}],
          "_source": ["input_data"]
        }
      },
      "output": {
        "top_hits": {
          "size": 1,
          "sort": [{"event_time": {"order": "desc"}}],
          "_source": ["output_data"]
        }
      },
      "error": {
        "top_hits": {
          "size": 1,
          "sort": [{"event_time": {"order": "desc"}}],
          "_source": ["error"]
        }
      },
      "last_update": {
        "max": {
          "field": "event_time"
        }
      }
    }
  }
}
```

**Key Design Decisions:**

**Query Optimization (Performance):**
```json
"should": [
  {"range": {"event_time": {"gte": "now-1h"}}},  // Always process recent
  {"must_not": [...terminal states...]}           // Process non-terminal old events
]
```
- Recent events (< 1 hour): Always process (catch late arrivals)
- Old events (> 1 hour): Only process if not terminal (skip completed workflows)
- **Result:** Constant performance even with millions of events

**Aggregation Strategy (Out-of-Order Handling):**
- **Immutable fields:** `name`, `version`, `namespace` (first event wins via `terms` size 1)
- **Start time:** `min(start_time)` (earliest event wins, even if late)
- **End time:** `max(end_time)` (latest completion wins)
- **Status:** Scripted metric (terminal state wins, else latest by timestamp)
- **Input:** `top_hits` sorted by `event_time ASC` (first event wins)
- **Output:** `top_hits` sorted by `event_time DESC` (last event wins)

**Sync Configuration:**
- **Frequency:** 1 second (near real-time)
- **Delay:** 60 seconds (wait for late events before considering data final)

---

### 4. Task Execution Schema (Phase 2)

Similar structure to workflow instances:
- `task-events.json` (raw events, flattened `input_args`/`output_args`)
- `task-executions.json` (normalized)
- `task-executions-transform.json` (aggregate by composite ID: `instance_id:task_position`)

---

## Data Flow

### Write Path: Event → Normalized Index

```
1. Quarkus Flow App
   └─> Emits JSON event to stdout
       {
         "event_type": "workflow.instance.started",
         "instance_id": "wf-123",
         "event_time": "2026-04-29T10:00:00Z",
         "workflow_name": "order-processing",
         "status": "RUNNING",
         "start_time": "2026-04-29T10:00:00Z",
         "input_data": {"orderId": "order-456"}
       }

2. Kubernetes Container Runtime
   └─> Writes to /var/log/containers/quarkus-flow-xyz.log

3. FluentBit DaemonSet
   └─> Tail plugin reads log
   └─> CRI parser extracts JSON
   └─> ES output writes to workflow-events-2026.04.29

4. Elasticsearch (raw index)
   └─> Document indexed in workflow-events-2026.04.29
   └─> ILM policy attached (7-day retention)

5. ES Transform (runs every 1s)
   └─> Queries workflow-events-* for new/updated events
   └─> Groups by instance_id
   └─> Aggregates: min(start), max(end), status logic, etc.
   └─> Upserts into workflow-instances/wf-123

6. Elasticsearch (normalized index)
   └─> Document updated in workflow-instances
   └─> Available for GraphQL queries
```

### Read Path: GraphQL → Elasticsearch

```
1. GraphQL Query
   query {
     getWorkflowInstances(
       filter: {status: {eq: COMPLETED}, input: {customerId: {eq: "123"}}}
       limit: 10
     ) {
       id
       name
       status
       input
     }
   }

2. WorkflowInstanceGraphQLApi
   └─> FilterConverter translates GraphQL → AttributeFilter

3. ElasticsearchWorkflowInstanceStorage
   └─> query().filter([...]).limit(10).execute()

4. ElasticsearchQuery
   └─> Builds ES Query DSL:
       {
         "bool": {
           "must": [
             {"term": {"status": "COMPLETED"}},
             {"term": {"input.customerId": "123"}}
           ]
         }
       }

5. Elasticsearch
   └─> Search workflow-instances index
   └─> Returns matching documents

6. ElasticsearchQuery
   └─> Deserializes JSON → WorkflowInstance domain models

7. GraphQL Response
   └─> Returns WorkflowInstance list
```

### Out-of-Order Event Handling Example

**Scenario:** COMPLETED event arrives before STARTED

```
T+0:    Event arrives: COMPLETED
        - event_time: 2026-04-29T10:05:00Z
        - instance_id: wf-123
        - status: COMPLETED
        - end_time: 2026-04-29T10:05:00Z
        - output_data: {"result": "success"}

T+1s:   Transform runs
        - Creates workflow-instances/wf-123
        - status = COMPLETED
        - start = null (no start event yet)
        - end = 2026-04-29T10:05:00Z
        - output = {"result": "success"}

T+30s:  Event arrives: STARTED (late!)
        - event_time: 2026-04-29T10:00:00Z
        - instance_id: wf-123
        - status: RUNNING
        - start_time: 2026-04-29T10:00:00Z
        - input_data: {"orderId": "order-456"}

T+31s:  Transform runs
        - Re-aggregates workflow-instances/wf-123
        - status = COMPLETED (terminal wins)
        - start = MIN(null, 2026-04-29T10:00:00Z) = 2026-04-29T10:00:00Z
        - end = MAX(2026-04-29T10:05:00Z, null) = 2026-04-29T10:05:00Z
        - input = top_hits sorted ASC = {"orderId": "order-456"}
        - output = top_hits sorted DESC = {"result": "success"}

Result: Complete workflow instance with all fields, despite events arriving out-of-order
```

---

## Configuration

### Universal Schema Initialization

**Flag:** `skipInitSchema` (controls both PostgreSQL and Elasticsearch)

**Application Properties:**
```properties
# Universal schema initialization control
# Dev/Test: true (auto-initialize)
# Production: false (operator handles schema)
data-index.init-schema=${skipInitSchema:true}

# PostgreSQL: Map to Flyway
%postgresql.quarkus.flyway.migrate-at-start=${data-index.init-schema}

# Elasticsearch: Used by ElasticsearchSchemaInitializer
%elasticsearch.data-index.init-schema=${data-index.init-schema}
```

**Build Commands:**

```bash
# Dev Mode - auto-initialize schema (default)
mvn quarkus:dev -Dquarkus.profile=postgresql      # Flyway runs
mvn quarkus:dev -Dquarkus.profile=elasticsearch    # ES schema initializer runs

# Production - skip schema initialization
mvn clean package -Dquarkus.profile=postgresql -DskipInitSchema=true
mvn clean package -Dquarkus.profile=elasticsearch -DskipInitSchema=true
```

**Behavior Matrix:**

| Build Command | Backend | Schema Initialization |
|---------------|---------|----------------------|
| `mvn quarkus:dev -Dquarkus.profile=postgresql` | PostgreSQL | ✅ Flyway runs migrations |
| `mvn quarkus:dev -Dquarkus.profile=elasticsearch` | Elasticsearch | ✅ ES schema initializer runs |
| `mvn package -Dquarkus.profile=postgresql -DskipInitSchema=true` | PostgreSQL | ❌ Flyway disabled |
| `mvn package -Dquarkus.profile=elasticsearch -DskipInitSchema=true` | Elasticsearch | ❌ ES schema disabled |

### Elasticsearch Configuration

**application-elasticsearch.properties:**
```properties
# Elasticsearch connection (Dev Services auto-starts container)
%dev.quarkus.elasticsearch.devservices.enabled=true
%dev.quarkus.elasticsearch.devservices.image-name=docker.elastic.co/elasticsearch/elasticsearch:8.11.0
%dev.quarkus.elasticsearch.devservices.port=9200

# Production (external cluster)
%prod.quarkus.elasticsearch.hosts=elasticsearch.elasticsearch.svc.cluster.local:9200

# Data Index ES configuration
data-index.elasticsearch.workflow-instance-index=workflow-instances
data-index.elasticsearch.task-execution-index=task-executions
data-index.elasticsearch.refresh-policy=wait_for
data-index.elasticsearch.number-of-shards=3
data-index.elasticsearch.number-of-replicas=1
data-index.elasticsearch.auto-create-indices=true
```

---

## Testing Strategy

### Phase 1: Integration Tests (Testcontainers)

**Scope:** Storage layer + Transform normalization

**Test Environment:**
- Testcontainers Elasticsearch 8.11+
- Quarkus Test framework
- Test indices: `test-workflow-instances`, `test-workflow-events`
- Schema auto-initialization enabled

**Test Categories:**

#### 1. Storage Layer Tests
```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
class ElasticsearchWorkflowInstanceStorageTest {
    
    @Test
    void testCRUDOperations() {
        // put, get, containsKey, remove, clear
    }
    
    @Test
    void testQueryWithFilters() {
        // status=COMPLETED, namespace=default
    }
    
    @Test
    void testJsonFieldQuery() {
        // Flattened field: input.customerId = "123"
    }
    
    @Test
    void testPagination() {
        // limit, offset
    }
    
    @Test
    void testSorting() {
        // sort by start, end, status
    }
}
```

#### 2. Transform Normalization Tests
```java
@QuarkusTest
class WorkflowInstanceTransformTest {
    
    @Inject
    ElasticsearchClient client;
    
    @Test
    void testOutOfOrderEvents() {
        // 1. Write COMPLETED event to workflow-events
        // 2. Write STARTED event to workflow-events
        // 3. Trigger Transform (or wait 1s)
        // 4. Read from workflow-instances
        // 5. Assert: start and end both present
    }
    
    @Test
    void testStatusDeterminationTerminalWins() {
        // STARTED → RUNNING → COMPLETED
        // Assert: status = COMPLETED
    }
    
    @Test
    void testImmutableFieldsFirstWins() {
        // STARTED with input A
        // COMPLETED with input B
        // Assert: input = A (first event wins)
    }
    
    @Test
    void testLateArrivalHandling() {
        // COMPLETED arrives first
        // STARTED arrives 30s later
        // Assert: Both start and end present
    }
}
```

#### 3. Schema Initialization Tests
```java
@QuarkusTest
class ElasticsearchSchemaInitializerTest {
    
    @Inject
    ElasticsearchClient client;
    
    @Test
    void testIndexTemplatesCreated() {
        // Verify workflow-events and workflow-instances templates exist
    }
    
    @Test
    void testTransformCreated() {
        // Verify workflow-instances-transform exists and is started
    }
    
    @Test
    void testILMPolicyCreated() {
        // Verify data-index-events-retention policy exists
    }
    
    @Test
    void testIdempotency() {
        // Run initializer twice, no errors
    }
}
```

#### 4. GraphQL Integration Tests
```java
@QuarkusTest
class WorkflowInstanceGraphQLApiElasticsearchTest {
    
    @Test
    void testGetWorkflowInstances() {
        // Insert test data via storage
        // Query via GraphQL
        // Assert results
    }
    
    @Test
    void testFilterByStatus() {
        // GraphQL filter → ES Query DSL
    }
    
    @Test
    void testJsonFieldFilter() {
        // input: {customerId: {eq: "123"}}
        // → ES: input.customerId = "123"
    }
}
```

**Test Profile:**
```java
public class ElasticsearchTestProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "data-index.init-schema", "true",
            "quarkus.elasticsearch.devservices.enabled", "true",
            "data-index.elasticsearch.workflow-instance-index", "test-workflow-instances",
            "data-index.elasticsearch.task-execution-index", "test-task-executions"
        );
    }
}
```

### Phase 2: E2E Tests (Deferred)

**Scope:** Full stack with FluentBit + Kubernetes

**Not in this design scope:**
- KIND cluster deployment
- FluentBit integration testing
- Performance benchmarking
- Multi-namespace validation

---

## Error Handling

### 1. Elasticsearch Connection Failures

**Strategy:** Fail fast, propagate to GraphQL

```java
@Override
public WorkflowInstance get(String id) {
    try {
        GetResponse<WorkflowInstance> response = client.get(...);
        return response.found() ? response.source() : null;
    } catch (IOException e) {
        throw new RuntimeException("Failed to get workflow instance: " + id, e);
    }
}
```

**GraphQL Response:**
```json
{
  "errors": [{
    "message": "Failed to get workflow instance: wf-123",
    "extensions": {"classification": "DataFetchingException"}
  }]
}
```

### 2. Transform Processing Errors

**Monitoring:** ES Transform Stats API

```bash
GET _transform/workflow-instances-transform/_stats
```

**Failure Handling:**
- Transform logs errors to Elasticsearch logs
- Failed transforms can be stopped, reset, and restarted
- Monitor `documents_processed` vs `documents_indexed` gap

**Recovery:**
```bash
# Stop transform
POST _transform/workflow-instances-transform/_stop

# Reset (optional, only if needed)
POST _transform/workflow-instances-transform/_reset

# Start transform
POST _transform/workflow-instances-transform/_start
```

### 3. Invalid Event Data

**Scenario:** Event missing `instance_id`

**Transform Behavior:** Skips document (can't group without group_by field)

**Monitoring:** Check Transform stats for processed vs indexed gap

### 4. Schema Initialization Failures

**Dev/Test Mode:**
```java
void onStart(@Observes StartupEvent event) {
    if (!initSchema) return;
    
    try {
        applyILMPolicies();
        applyIndexTemplates();
        applyTransforms();
    } catch (Exception e) {
        LOGGER.error("Failed to initialize ES schema", e);
        // Don't fail startup - allow manual recovery
    }
}
```

**Production Mode:** Operator ensures schema exists before starting Data Index

### 5. Health Checks

**Elasticsearch Connection:**
```java
@Readiness
class ElasticsearchHealthCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        try {
            return client.ping().value() 
                ? HealthCheckResponse.up("elasticsearch-connection")
                : HealthCheckResponse.down("elasticsearch-connection");
        } catch (Exception e) {
            return HealthCheckResponse.down("elasticsearch-connection");
        }
    }
}
```

**Transform Health:**
```java
@Readiness
class ElasticsearchTransformHealthCheck implements HealthCheck {
    @Override
    public HealthCheckResponse call() {
        try {
            TransformStats stats = client.transform()
                .getTransformStats(r -> r.transformId("workflow-instances-transform"));
            
            TransformState state = stats.transforms().get(0).state();
            boolean healthy = state == TransformState.Started;
            
            return HealthCheckResponse.builder()
                .name("elasticsearch-transform")
                .status(healthy)
                .withData("state", state.toString())
                .build();
        } catch (Exception e) {
            return HealthCheckResponse.down("elasticsearch-transform");
        }
    }
}
```

---

## Implementation Phases

### Phase 1: WorkflowInstance Full Stack

**Goal:** Complete end-to-end workflow instance feature with Transform validation

#### Phase 1.1: Schema Module Setup

**Tasks:**
1. Create `data-index-storage-elasticsearch-schema` module
   - Add to `data-index-storage/pom.xml` modules list
   - Create module pom.xml with ES dependencies
2. Create ILM policy JSON (`data-index-events-retention.json`)
3. Create index templates:
   - `workflow-events.json` (raw events, flattened fields)
   - `workflow-instances.json` (normalized, flattened fields)
4. Create Transform configuration (`workflow-instances-transform.json`)
5. Implement `ElasticsearchSchemaInitializer`
   - Load JSON scripts from resources
   - Apply via Elasticsearch Java Client
   - Handle errors gracefully
6. Add universal `data-index.init-schema` configuration
   - Update `application.properties` for both backends

**Success Criteria:**
- ✅ Module builds successfully
- ✅ Schema scripts are valid JSON
- ✅ `ElasticsearchSchemaInitializer` loads and applies scripts
- ✅ Dev mode auto-initializes schema on startup

**Estimated Time:** 4-6 hours

#### Phase 1.2: Storage Layer Completion

**Tasks:**
1. Review `ElasticsearchWorkflowInstanceStorage` (already implemented)
2. Review `ElasticsearchQuery` (already implemented)
3. Test all filter types work (EQUAL, IN, LIKE, GT, GTE, LT, LTE, IS_NULL, NOT_NULL, BETWEEN)
4. Verify flattened field queries work (`input.customerId`)

**Success Criteria:**
- ✅ All CRUD operations work (get, put, remove, containsKey, clear)
- ✅ Query filtering works for all supported conditions
- ✅ Flattened field queries work
- ✅ Pagination works (limit, offset)
- ✅ Sorting works

**Estimated Time:** 2-3 hours

#### Phase 1.3: Integration Tests

**Tasks:**
1. Create `ElasticsearchTestProfile` (enables schema auto-init)
2. Expand `ElasticsearchStorageIntegrationTest` for WorkflowInstance
   - CRUD tests
   - Filter tests (all condition types)
   - Flattened field query tests
   - Pagination tests
   - Sorting tests
3. Create `WorkflowInstanceTransformTest`
   - Out-of-order event handling
   - Status determination (terminal wins)
   - Field idempotency (immutable fields)
   - Late arrival handling
4. Create `ElasticsearchSchemaInitializerTest`
   - Verify templates created
   - Verify Transform created and started
   - Verify ILM policy created
   - Test idempotency

**Success Criteria:**
- ✅ All storage tests pass
- ✅ Transform normalization tests pass (out-of-order, idempotency)
- ✅ Schema initialization tests pass
- ✅ Test cleanup works (clear indices between tests)

**Estimated Time:** 6-8 hours

#### Phase 1.4: Service Module Implementation

**Tasks:**
1. Add application code to `data-index-service-elasticsearch` module
   - Currently only has pom.xml, no Java code
2. Configure Quarkus Elasticsearch Dev Services
   - `application-elasticsearch.properties`
3. Wire GraphQL API to `ElasticsearchWorkflowInstanceStorage`
   - Verify GraphQL API already backend-agnostic (should be)
4. Add health checks (ES connection, Transform)
5. Test manually in dev mode
   - `mvn quarkus:dev -Dquarkus.profile=elasticsearch`
   - Query GraphQL endpoint

**Success Criteria:**
- ✅ Service starts in dev mode
- ✅ Elasticsearch Dev Services container starts
- ✅ Schema auto-initialized on startup (logs confirm)
- ✅ GraphQL query returns workflow instances
- ✅ GraphQL filtering works
- ✅ Health checks pass (/q/health)

**Estimated Time:** 4-5 hours

#### Phase 1.5: GraphQL Integration Tests

**Tasks:**
1. Create `WorkflowInstanceGraphQLApiElasticsearchTest`
   - Test `getWorkflowInstances` query
   - Test filtering (status, namespace, etc.)
   - Test JSON field filtering (`input.customerId`)
   - Test pagination
2. Follow existing test pattern from MODE 1 PostgreSQL tests

**Success Criteria:**
- ✅ GraphQL queries work via REST Assured
- ✅ GraphQL filtering translates to ES Query DSL correctly
- ✅ JSON field filters work
- ✅ All tests pass with Testcontainers Elasticsearch

**Estimated Time:** 3-4 hours

**Phase 1 Total Time:** ~20-26 hours (~3-4 days)

---

### Phase 2: TaskExecution Full Stack

**Goal:** Complete end-to-end task execution feature (mirror Phase 1)

#### Phase 2.1: Schema Scripts

**Tasks:**
1. Create `task-events.json` index template
   - Flattened `input_args`, `output_args`
   - ILM policy attached
2. Create `task-executions.json` index template
   - Normalized task data
3. Create `task-executions-transform.json` configuration
   - Group by composite ID: `instance_id:task_position`
   - Aggregation logic (similar to workflow instances)

**Success Criteria:**
- ✅ Index templates created
- ✅ Transform configuration created
- ✅ Schema initializer applies task-related scripts

**Estimated Time:** 3-4 hours

#### Phase 2.2: Storage & Tests

**Tasks:**
1. Review `ElasticsearchTaskExecutionStorage` (already implemented)
2. Add integration tests for TaskExecution
   - CRUD tests
   - Filter tests
   - Flattened field query tests
3. Add Transform normalization tests for tasks
   - Out-of-order handling
   - Field idempotency

**Success Criteria:**
- ✅ Task storage CRUD works
- ✅ Task query filtering works
- ✅ Transform handles out-of-order task events

**Estimated Time:** 5-6 hours

#### Phase 2.3: Service & GraphQL

**Tasks:**
1. Wire GraphQL API to `ElasticsearchTaskExecutionStorage`
   - Verify TaskExecution queries work
2. Add GraphQL integration tests for TaskExecution
   - Test `getTaskExecutions` query
   - Test filtering

**Success Criteria:**
- ✅ GraphQL `getTaskExecutions` works
- ✅ Task filtering works
- ✅ All tests pass

**Estimated Time:** 3-4 hours

**Phase 2 Total Time:** ~11-14 hours (~2 days)

---

### Phase 3: FluentBit Integration & Documentation

**Goal:** Complete full pipeline, document deployment

#### Phase 3.1: FluentBit ES Output

**Tasks:**
1. Create FluentBit configuration
   - `data-index/scripts/fluentbit/elasticsearch/fluent-bit.conf`
2. Configure ES output plugin for workflow-events
3. Configure ES output plugin for task-events
4. Test locally with sample events
   - Manual curl to FluentBit → ES → Transform → GraphQL

**Success Criteria:**
- ✅ FluentBit sends events to Elasticsearch
- ✅ Events appear in raw indices
- ✅ Transform processes events
- ✅ Normalized indices populated
- ✅ Can query via GraphQL

**Estimated Time:** 4-5 hours

#### Phase 3.2: Documentation

**Tasks:**
1. Update `CLAUDE.md` with MODE 2 instructions
   - Architecture section
   - Build commands with `skipInitSchema`
   - Schema module structure
2. Document operator responsibilities
   - Schema application in production
   - Transform management
3. Document health checks
4. Update architecture diagrams (if applicable)

**Success Criteria:**
- ✅ MODE 2 build/deployment documented
- ✅ Schema initialization approach documented
- ✅ Operator integration documented
- ✅ Developer onboarding clear

**Estimated Time:** 3-4 hours

**Phase 3 Total Time:** ~7-9 hours (~1 day)

---

## Overall Success Criteria

### Functional Requirements
- ✅ WorkflowInstance fully queryable via GraphQL
- ✅ TaskExecution fully queryable via GraphQL
- ✅ ES Transform handles out-of-order events correctly
- ✅ Flattened field queries work (JSON field filtering)
- ✅ ILM cleans up raw events after 7 days
- ✅ Schema auto-initialization works in dev/test mode
- ✅ Schema skipped in production mode (`skipInitSchema=true`)

### Testing
- ✅ All integration tests pass (storage, Transform, GraphQL)
- ✅ Test coverage mirrors MODE 1 PostgreSQL
- ✅ Transform normalization tested (out-of-order, idempotency, late arrival)
- ✅ Schema initialization tested (templates, transforms, ILM)

### Configuration
- ✅ Universal `skipInitSchema` flag works for both backends
- ✅ Dev mode auto-initializes schema (PostgreSQL Flyway, Elasticsearch schema initializer)
- ✅ Production mode skips schema initialization (operator handles it)

### Documentation
- ✅ CLAUDE.md updated with MODE 2 approach
- ✅ Build commands documented
- ✅ Schema module structure documented
- ✅ Operator responsibilities clear

---

## Non-Goals (Deferred)

**E2E Phase (not in this scope):**
- ❌ Full KIND cluster deployment with FluentBit
- ❌ Performance benchmarking (target: >1000 queries/sec)
- ❌ Multi-namespace testing
- ❌ Production hardening (monitoring, alerting, scaling)
- ❌ Migration tooling (MODE 1 → MODE 2)

---

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| **Transform performance degrades over time** | High | Smart query filtering (only recent + non-terminal events) |
| **Transform processing errors** | Medium | Monitor Transform stats, implement health checks, allow reset/restart |
| **Testcontainers ES startup slow** | Low | Use shared container across tests, accept slower test suite |
| **Flattened field limitations** | Low | Document limitation (no full-text search inside JSON), acceptable trade-off |
| **Schema script JSON errors** | Medium | Validate JSON on build, test schema initialization in CI |
| **Operator integration unclear** | Medium | Document operator responsibilities, provide script references |

---

## Dependencies

### Required
- Elasticsearch 8.11+ cluster (3 nodes minimum for production)
- FluentBit 3.0+ with Elasticsearch output plugin
- Quarkus Elasticsearch Java Client extension
- elasticsearch-java library (8.11.1)

### Dev/Test
- Testcontainers Elasticsearch
- Quarkus Dev Services

### Production
- External Elasticsearch cluster (managed or self-hosted)
- External operator for schema management
- FluentBit DaemonSet

---

## Timeline Estimate

| Phase | Estimated Time |
|-------|---------------|
| Phase 1: WorkflowInstance Full Stack | 3-4 days |
| Phase 2: TaskExecution Full Stack | 2 days |
| Phase 3: FluentBit + Documentation | 1 day |
| **Total** | **6-7 days** |

**Assumptions:**
- Developer familiar with Elasticsearch concepts
- No major blockers in existing storage implementation
- Transform configurations work as designed (no major tuning needed)

---

## References

- [Elasticsearch Transforms Documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/transforms.html)
- [Elasticsearch Flattened Field Type](https://www.elastic.co/guide/en/elasticsearch/reference/current/flattened.html)
- [Elasticsearch ILM](https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html)
- [FluentBit Elasticsearch Output](https://docs.fluentbit.io/manual/pipeline/outputs/elasticsearch)
- [Quarkus Elasticsearch Guide](https://quarkus.io/guides/elasticsearch)
- Existing Documentation: `data-index/docs/deployment/MODE2_IMPLEMENTATION_PLAN.md`
- Existing Documentation: `data-index-storage-elasticsearch/README.md`
