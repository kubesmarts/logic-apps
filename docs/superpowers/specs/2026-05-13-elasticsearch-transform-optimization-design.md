# Elasticsearch Transform Query Optimization - Design Specification

**Date:** 2026-05-13  
**Status:** Approved  
**Implementation:** 2 PRs (Smart Filtering + Metrics)

---

## Overview

### Problem Statement

The workflow-instances transform currently uses `match_all`, processing every event in the raw indices on each run (1s frequency). As the number of historical events grows (thousands → millions), this becomes increasingly expensive, even though most workflows are already in terminal states and don't need reprocessing.

The task-executions transform already implements smart filtering and demonstrates the pattern works well.

### Solution

Apply the proven smart filtering pattern from task-executions transform to workflow-instances transform, making both transforms scale efficiently with a unified configuration strategy.

Add comprehensive observability via Micrometer metrics to monitor transform health and performance.

### Goals

1. **Performance at Scale**: Transform processing time stays constant regardless of total event count
2. **Unified Configuration**: Single time window property controls both transforms
3. **Production Observability**: Micrometer metrics expose transform health and performance
4. **Comprehensive Testing**: Integration and performance tests validate optimization
5. **Flexible ILM Retention**: Configurable raw event retention to support long-running workflows

### Success Criteria

- ✅ Transform processes only recent events (< 1 hour) + active workflows (non-terminal)
- ✅ Configurable via `data-index.transform.smart-filter.time-window` property
- ✅ Configurable ILM retention via `data-index.ilm.raw-events-retention` property
- ✅ Micrometer metrics show documents processed, indexed, lag for both transforms
- ✅ Integration tests verify smart filtering correctness (out-of-order events, idempotency)
- ✅ Performance tests demonstrate constant processing time as data scales
- ✅ Reduction in Elasticsearch CPU usage (measurable via benchmarks)

### Scope

**PR#1: Smart Filtering + Configuration**
- Implement smart filtering query for workflow-instances transform
- Add configurable time window property
- Add configurable ILM retention property
- Comprehensive integration tests
- Update both transform templates

**PR#2: Micrometer Metrics + Benchmarking**
- Scheduled metrics collector polling Transform Stats API
- Prometheus-compatible metrics for both transforms
- Performance benchmarking tests
- Grafana dashboard examples

---

## Architecture

### Current State

**Workflow Transform:**
```json
{
  "source": {
    "index": "workflow-events-*",
    "query": {
      "match_all": {}  ← Processes ALL events
    }
  }
}
```

**Task Transform:**
```json
{
  "source": {
    "index": "task-events-*",
    "query": {
      "bool": {
        "should": [
          {"range": {"@timestamp": {"gte": "now-1h"}}},
          {
            "bool": {
              "filter": [...],
              "must_not": [terminal states]
            }
          }
        ]
      }
    }
  }
}
```

**Problem:** Workflow transform processes 100% of events, task transform processes ~10-20% (smart filtering).

### Target State

**Both transforms use smart filtering:**
```json
{
  "source": {
    "index": "{workflow|task}-events-*",
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "@timestamp": {
                "gte": "now-{TIME_WINDOW}"
              }
            }
          },
          {
            "bool": {
              "filter": [
                {"range": {"@timestamp": {"lt": "now-{TIME_WINDOW}"}}}
              ],
              "must_not": [
                {"term": {"eventType.keyword": "{TERMINAL_EVENT_1}"}},
                {"term": {"eventType.keyword": "{TERMINAL_EVENT_2}"}}
              ]
            }
          }
        ],
        "minimum_should_match": 1
      }
    }
  }
}
```

**Logic:**
- **Clause 1**: Always process events from last `{TIME_WINDOW}` (catch late arrivals)
- **Clause 2**: For older events, only process if **NOT** in terminal state
- **Result**: Recent events + active workflows/tasks, skip old completed instances

**Terminal States:**
- **Workflow**: `io.serverlessworkflow.workflow.completed.v1`, `io.serverlessworkflow.workflow.faulted.v1`, `io.serverlessworkflow.workflow.cancelled.v1`
- **Task**: `io.serverlessworkflow.task.completed.v1`, `io.serverlessworkflow.task.faulted.v1`

---

## PR#1: Smart Filtering + Configuration

### Configuration Properties

**New Properties:**
```properties
# Time window for smart filtering (ISO-8601 duration or simple format)
# Default: 1h (handles typical network delays and late arrivals)
data-index.transform.smart-filter.time-window=1h

# Valid values: 30m, 1h, 2h, 4h, 1d, 7d
# Or ISO-8601: PT30M, PT1H, PT2H

# Raw event retention (ILM policy)
# Default: 30d (handles workflows up to 30 days duration)
data-index.ilm.raw-events-retention=30d

# Valid values: 7d, 30d, 90d
# Or ISO-8601: P7D, P30D, P90D
```

**Validation Rules:**
1. Time window ≤ ILM retention (can't query deleted events)
2. Valid duration format (matches regex or ISO-8601)
3. Time window minimum: 1m (not practical below this)
4. ILM retention minimum: 1d (below this defeats purpose)

**Configuration Tuning Guidelines:**

| Deployment Size | Time Window | ILM Retention | Rationale |
|-----------------|-------------|---------------|-----------|
| Dev/Test | 30m | 7d | Fast iterations, small dataset |
| Small (< 10K workflows/day) | 1h | 30d | Typical production workload |
| Medium (10K-100K/day) | 2h | 30d | High-throughput buffer |
| Large (> 100K/day) | 4h | 90d | Maximum safety for delays |
| Long-running workflows | 1h | 90d | Extended retention for slow processes |

### Schema Initializer Changes

**ElasticsearchSchemaInitializer.java:**

```java
@ConfigProperty(name = "data-index.transform.smart-filter.time-window", defaultValue = "1h")
String smartFilterTimeWindow;

@ConfigProperty(name = "data-index.ilm.raw-events-retention", defaultValue = "30d")
String rawEventsRetention;

void onStart(@Observes StartupEvent event) {
    if (skipInitSchema || !schemaInitEnabled) {
        return;
    }
    
    validateConfiguration();
    
    try {
        applyIlmPolicies();    // Uses rawEventsRetention
        applyIndexTemplates();
        applyTransforms();     // Uses smartFilterTimeWindow
        LOGGER.info("Elasticsearch schema initialization complete");
    } catch (Exception e) {
        LOGGER.error("Elasticsearch schema initialization failed", e);
        throw new RuntimeException("Failed to initialize Elasticsearch schema", e);
    }
}

private void validateConfiguration() {
    // Validate time window format
    if (!smartFilterTimeWindow.matches("\\d+[mhd]|PT.*")) {
        throw new IllegalArgumentException(
            "Invalid time window format: " + smartFilterTimeWindow + 
            ". Expected: '1h', '30m', '2h', or ISO-8601 (PT1H)"
        );
    }
    
    // Validate ILM retention format
    if (!rawEventsRetention.matches("\\d+d|P\\d+D")) {
        throw new IllegalArgumentException(
            "Invalid ILM retention format: " + rawEventsRetention + 
            ". Expected: '7d', '30d', '90d', or ISO-8601 (P30D)"
        );
    }
    
    // Validate: time window ≤ ILM retention
    long windowMillis = parseToMillis(smartFilterTimeWindow);
    long retentionMillis = parseToMillis(rawEventsRetention);
    
    if (windowMillis > retentionMillis) {
        throw new IllegalArgumentException(
            "Smart filter time window (" + smartFilterTimeWindow + 
            ") cannot exceed ILM retention (" + rawEventsRetention + 
            "). Events older than retention are deleted."
        );
    }
}

private long parseToMillis(String duration) {
    // Parse simple format (e.g., "1h", "30m", "7d")
    // Or ISO-8601 format (e.g., "PT1H", "P7D")
    // Return milliseconds
    // Implementation uses java.time.Duration
}

private void applyTransform(String name, String resourcePath) throws IOException {
    String json = loadResourceAsString(resourcePath);
    
    // Replace time window placeholder
    json = json.replace("{TIME_WINDOW}", smartFilterTimeWindow);
    
    if (transformExists(name)) {
        LOGGER.info("Transform '{}' already exists, checking if started...", name);
        startTransformIfStopped(name);
        return;
    }
    
    // Apply and start transform
    try (InputStream is = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
        PutTransformRequest request = PutTransformRequest.of(builder -> builder
                .transformId(name)
                .withJson(is));
        
        client.transform().putTransform(request);
        LOGGER.info("Transform '{}' applied successfully", name);
        
        startTransform(name);
    }
}

private void applyIlmPolicy(String name, String resourcePath) throws IOException {
    String json = loadResourceAsString(resourcePath);
    
    // Replace retention placeholder
    json = json.replace("{RETENTION_PERIOD}", rawEventsRetention);
    
    if (ilmPolicyExists(name)) {
        LOGGER.info("ILM policy '{}' already exists, skipping", name);
        return;
    }
    
    // Apply ILM policy
    JsonNode rootNode = objectMapper.readTree(json);
    JsonNode policyNode = rootNode.get("policy");
    
    try (InputStream is = new ByteArrayInputStream(policyNode.toString().getBytes(StandardCharsets.UTF_8))) {
        PutLifecycleRequest request = PutLifecycleRequest.of(builder -> builder
                .name(name)
                .policy(p -> p.withJson(is)));
        
        client.ilm().putLifecycle(request);
        LOGGER.info("ILM policy '{}' applied successfully", name);
    }
}
```

### Transform Template Changes

**workflow-instances-transform.json:**

```json
{
  "source": {
    "index": "workflow-events-*",
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "@timestamp": {
                "gte": "now-{TIME_WINDOW}"
              }
            }
          },
          {
            "bool": {
              "filter": [
                {
                  "range": {
                    "@timestamp": {
                      "lt": "now-{TIME_WINDOW}"
                    }
                  }
                }
              ],
              "must_not": [
                {
                  "term": {
                    "eventType.keyword": "io.serverlessworkflow.workflow.completed.v1"
                  }
                },
                {
                  "term": {
                    "eventType.keyword": "io.serverlessworkflow.workflow.faulted.v1"
                  }
                },
                {
                  "term": {
                    "eventType.keyword": "io.serverlessworkflow.workflow.cancelled.v1"
                  }
                }
              ]
            }
          }
        ],
        "minimum_should_match": 1
      }
    }
  },
  "dest": {
    "index": "workflow-instances"
  },
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "@timestamp",
      "delay": "0s"
    }
  },
  "pivot": {
    // ... existing aggregations unchanged
  }
}
```

**task-executions-transform.json:**

Updated to use `{TIME_WINDOW}` placeholder instead of hardcoded `1h`:

```json
{
  "source": {
    "index": "task-events-*",
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "@timestamp": {
                "gte": "now-{TIME_WINDOW}"
              }
            }
          },
          {
            "bool": {
              "filter": [
                {
                  "range": {
                    "@timestamp": {
                      "lt": "now-{TIME_WINDOW}"
                    }
                  }
                }
              ],
              "must_not": [
                {
                  "term": {
                    "eventType.keyword": "io.serverlessworkflow.task.completed.v1"
                  }
                },
                {
                  "term": {
                    "eventType.keyword": "io.serverlessworkflow.task.faulted.v1"
                  }
                }
              ]
            }
          }
        ],
        "minimum_should_match": 1
      }
    }
  },
  // ... rest unchanged
}
```

**ILM Policy Template:**

**data-index-events-retention.json:**

```json
{
  "policy": {
    "phases": {
      "delete": {
        "min_age": "{RETENTION_PERIOD}",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

### Long-Running Workflows & ILM Retention

**Challenge:**

Quarkus Flow emits delta events (only changed fields):
- STARTED event: `{id, status: STARTED, start, input, name, version}`
- COMPLETED event: `{id, status: COMPLETED, end, output}`

Elasticsearch Transforms **overwrite** entire documents (cannot merge).

**Problem Scenario:**
```
Day 0:   STARTED event → workflow-instances: {start, input, name, status: STARTED}
Day 30:  ILM deletes STARTED event (assuming 30d retention)
Day 45:  COMPLETED event arrives
         Transform re-aggregates using only COMPLETED event
         Result: {end, output, status: COMPLETED}
         Lost: start, input, name ❌
```

**Solution:**

Configurable ILM retention matches expected workflow duration:

```properties
# For workflows typically < 30 days
data-index.ilm.raw-events-retention=30d

# For long-running workflows (90 days max)
data-index.ilm.raw-events-retention=90d
```

**Validation:**
- Startup validation ensures `time-window ≤ ilm-retention`
- Clear error message if misconfigured

**Trade-offs:**
- **Longer retention** = more raw event storage (but still temporary)
- **Shorter retention** = risk of data loss for workflows exceeding retention
- **Normalized data always permanent** (workflow-instances, task-executions)

**Future Options** (if workflows regularly exceed 90d):
1. Increase ILM retention further (180d, 365d)
2. Request Quarkus Flow emit partial state (immutable fields in every event)
3. Split retention by event type (terminal: 7d, non-terminal: 90d)

**Decision:** Defer complex solutions to Phase 2. Start with configurable ILM, monitor production data.

---

## PR#2: Micrometer Metrics + Benchmarking

### Metrics Design

**Exposed Metrics** (per transform):

```
# Total documents processed by transform
data_index_transform_documents_processed{transform="workflow-instances-transform"} = 45230
data_index_transform_documents_processed{transform="task-executions-transform"} = 128450

# Total documents indexed to destination
data_index_transform_documents_indexed{transform="workflow-instances-transform"} = 45230
data_index_transform_documents_indexed{transform="task-executions-transform"} = 128450

# Processing lag (processed - indexed)
data_index_transform_lag{transform="workflow-instances-transform"} = 0
data_index_transform_lag{transform="task-executions-transform"} = 15

# Transform state (0=stopped, 1=started, 2=failed, -1=unknown)
data_index_transform_state{transform="workflow-instances-transform"} = 1
data_index_transform_state{transform="task-executions-transform"} = 1

# Last checkpoint timestamp (epoch millis)
data_index_transform_last_checkpoint{transform="workflow-instances-transform"} = 1715612345678
data_index_transform_last_checkpoint{transform="task-executions-transform"} = 1715612345892
```

### Implementation

**New Component: ElasticsearchTransformMetricsCollector**

```java
package org.kubesmarts.logic.dataindex.elasticsearch.metrics;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.transform.GetTransformStatsResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.scheduler.Scheduled;

import java.time.Duration;
import java.util.List;

@ApplicationScoped
public class ElasticsearchTransformMetricsCollector {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchTransformMetricsCollector.class);

    @Inject
    ElasticsearchClient client;

    @Inject
    MeterRegistry registry;

    @ConfigProperty(name = "data-index.metrics.transform.enabled", defaultValue = "true")
    boolean metricsEnabled;

    @ConfigProperty(name = "data-index.metrics.transform.poll-interval", defaultValue = "30s")
    Duration pollInterval;

    private final List<String> transformIds = List.of(
        "workflow-instances-transform",
        "task-executions-transform"
    );

    @Scheduled(every = "{data-index.metrics.transform.poll-interval}")
    void collectTransformMetrics() {
        if (!metricsEnabled) {
            return;
        }

        for (String transformId : transformIds) {
            try {
                var stats = client.transform()
                    .getTransformStats(r -> r.transformId(transformId));

                updateMetrics(transformId, stats);

            } catch (Exception e) {
                LOGGER.warn("Failed to collect metrics for transform '{}': {}", transformId, e.getMessage());
                // Set state to unknown (-1) on error
                registry.gauge("data_index.transform.state",
                    Tags.of("transform", transformId), -1);
            }
        }
    }

    private void updateMetrics(String transformId, GetTransformStatsResponse stats) {
        if (stats.transforms().isEmpty()) {
            LOGGER.warn("Transform '{}' not found, skipping metrics", transformId);
            return;
        }

        var transformStats = stats.transforms().get(0);
        var indexingStats = transformStats.stats();

        // Update gauges
        registry.gauge("data_index.transform.documents_processed",
            Tags.of("transform", transformId),
            indexingStats.documentsProcessed());

        registry.gauge("data_index.transform.documents_indexed",
            Tags.of("transform", transformId),
            indexingStats.documentsIndexed());

        long lag = indexingStats.documentsProcessed() - indexingStats.documentsIndexed();
        registry.gauge("data_index.transform.lag",
            Tags.of("transform", transformId),
            lag);

        registry.gauge("data_index.transform.state",
            Tags.of("transform", transformId),
            mapStateToNumeric(transformStats.state()));

        // Checkpoint timestamp (if available)
        if (transformStats.checkpointing() != null && transformStats.checkpointing().last() != null) {
            long checkpoint = transformStats.checkpointing().last().timestamp();
            registry.gauge("data_index.transform.last_checkpoint",
                Tags.of("transform", transformId),
                checkpoint);
        }
    }

    private int mapStateToNumeric(String state) {
        return switch (state) {
            case "started" -> 1;
            case "stopped" -> 0;
            case "failed" -> 2;
            default -> -1;  // unknown
        };
    }
}
```

**Configuration:**

```properties
# Enable/disable transform metrics collection
data-index.metrics.transform.enabled=true

# How often to poll Transform Stats API
# Recommendation: 30s (balance freshness vs overhead)
data-index.metrics.transform.poll-interval=30s
```

### Grafana Dashboard Example

**Prometheus Queries:**

```promql
# Transform processing rate (events/second)
rate(data_index_transform_documents_processed{transform="workflow-instances-transform"}[5m])

# Transform lag (should stay near 0)
data_index_transform_lag

# Transform state health (1 = healthy)
data_index_transform_state == 1
```

**Alert Rules:**

```yaml
groups:
  - name: elasticsearch_transforms
    rules:
      - alert: TransformStopped
        expr: data_index_transform_state != 1
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Transform {{ $labels.transform }} is not running"
          description: "State: {{ $value }} (0=stopped, 2=failed, -1=unknown)"

      - alert: TransformLagHigh
        expr: data_index_transform_lag > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Transform {{ $labels.transform }} has high lag"
          description: "Lag: {{ $value }} documents"
```

---

## Testing Strategy

### PR#1: Smart Filtering Tests

#### Integration Tests - Smart Filtering Correctness

**New Test Class: `ElasticsearchSmartFilteringIT.java`**

Location: `data-index-storage-elasticsearch/src/test/java/.../`

```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
class ElasticsearchSmartFilteringIT {

    @Inject
    ElasticsearchClient client;

    @Inject
    ObjectMapper objectMapper;

    private static final String RAW_INDEX = "workflow-events-" + LocalDate.now();
    private static final String NORMALIZED_INDEX = "workflow-instances";
    private static final String TRANSFORM_ID = "workflow-instances-transform";

    @BeforeEach
    void setUp() throws Exception {
        ensureTransformStarted();
    }

    @Test
    void testRecentEventsAlwaysProcessed() throws Exception {
        String instanceId = "test-recent-" + UUID.randomUUID();
        Instant now = Instant.now();

        // Insert COMPLETED event (recent, within 1h window)
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           now, null, Map.of("result", "success"), null);

        waitForTransform();

        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
    }

    @Test
    void testOldTerminalEventsSkipped() throws Exception {
        String instanceId = "test-old-terminal-" + UUID.randomUUID();
        Instant oldTime = Instant.now().minus(Duration.ofHours(2)); // > 1h old

        // Insert old COMPLETED event
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           oldTime, null, Map.of("result", "success"), null);

        waitForTransform();

        // Should NOT be processed (old + terminal)
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNull();
    }

    @Test
    void testOldNonTerminalEventsProcessed() throws Exception {
        String instanceId = "test-old-running-" + UUID.randomUUID();
        Instant oldTime = Instant.now().minus(Duration.ofHours(2)); // > 1h old

        // Insert old RUNNING event
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           oldTime, Map.of("orderId", "123"), null, null);

        waitForTransform();

        // SHOULD be processed (old but NON-terminal)
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
    }

    @Test
    void testLateArrivalWithinWindow() throws Exception {
        String instanceId = "test-late-arrival-" + UUID.randomUUID();
        Instant baseTime = Instant.now();

        // Insert COMPLETED event first
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           baseTime.plusSeconds(30), null, Map.of("result", "success"), null);

        waitForTransform();

        // Insert STARTED event late (but within 1h window)
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.started.v1",
                           baseTime, Map.of("orderId", "123"), null, null);

        waitForTransform();

        // Both events should be processed
        WorkflowInstance normalized = getNormalizedInstance(instanceId);
        assertThat(normalized).isNotNull();
        assertThat(normalized.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(normalized.getInput()).isNotNull();
        assertThat(normalized.getOutput()).isNotNull();
    }

    @Test
    void testTransitionFromNonTerminalToTerminal() throws Exception {
        String instanceId = "test-transition-" + UUID.randomUUID();
        Instant oldTime = Instant.now().minus(Duration.ofHours(2));
        Instant recentTime = Instant.now();

        // Insert old RUNNING event (gets processed - non-terminal)
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.running.v1",
                           oldTime, Map.of("orderId", "123"), null, null);

        waitForTransform();

        WorkflowInstance running = getNormalizedInstance(instanceId);
        assertThat(running).isNotNull();
        assertThat(running.getStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);

        // Insert recent COMPLETED event
        insertWorkflowEvent(instanceId, "io.serverlessworkflow.workflow.completed.v1",
                           recentTime, null, Map.of("result", "success"), null);

        waitForTransform();

        // Now workflow is terminal
        WorkflowInstance completed = getNormalizedInstance(instanceId);
        assertThat(completed).isNotNull();
        assertThat(completed.getStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);

        // Future queries should skip old RUNNING event (workflow now terminal)
        // This is verified by checking Transform stats - processed count shouldn't increase
        // when inserting another old RUNNING event
    }

    private void waitForTransform() throws InterruptedException {
        Thread.sleep(5000); // Wait for 1s frequency + buffer
    }

    private void ensureTransformStarted() throws IOException {
        try {
            client.transform().startTransform(r -> r.transformId(TRANSFORM_ID));
        } catch (Exception e) {
            // Already started, ignore
        }
    }

    // Helper methods: insertWorkflowEvent(), getNormalizedInstance()
    // (Same as existing ElasticsearchTransformNormalizationIT)
}
```

#### Configuration Tests

**Test Class: `ElasticsearchTransformConfigurationIT.java`**

```java
@QuarkusTest
@TestProfile(CustomTimeWindowProfile.class) // Sets time-window=30m
class ElasticsearchTransformConfigurationIT {

    @Inject
    ElasticsearchClient client;

    @Test
    void testCustomTimeWindowApplied() throws IOException {
        var response = client.transform()
            .getTransform(r -> r.transformId("workflow-instances-transform"));

        var transform = response.transforms().get(0);
        String sourceQuery = transform.source().toString();

        // Verify query uses configured time window
        assertThat(sourceQuery).contains("now-30m");
        assertThat(sourceQuery).doesNotContain("now-1h");
    }

    @Test
    void testBothTransformsUseSameWindow() throws IOException {
        // Check workflow transform
        var workflowResponse = client.transform()
            .getTransform(r -> r.transformId("workflow-instances-transform"));
        String workflowQuery = workflowResponse.transforms().get(0).source().toString();

        // Check task transform
        var taskResponse = client.transform()
            .getTransform(r -> r.transformId("task-executions-transform"));
        String taskQuery = taskResponse.transforms().get(0).source().toString();

        // Both should use same time window
        assertThat(workflowQuery).contains("now-30m");
        assertThat(taskQuery).contains("now-30m");
    }

    @Test
    void testIlmRetentionConfigured() throws IOException {
        var response = client.ilm()
            .getLifecycle(r -> r.name("data-index-events-retention"));

        var policy = response.get("data-index-events-retention");
        String minAge = policy.policy().phases().delete().minAge().toString();

        // Verify ILM uses configured retention
        assertThat(minAge).isEqualTo("30d");
    }
}

// Test profile with custom time window
class CustomTimeWindowProfile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "data-index.transform.smart-filter.time-window", "30m",
            "data-index.ilm.raw-events-retention", "30d"
        );
    }
}
```

#### Validation Tests

**Test Class: `ElasticsearchConfigurationValidationIT.java`**

```java
@QuarkusTest
class ElasticsearchConfigurationValidationIT {

    @Test
    void testInvalidTimeWindowFormat() {
        // Should fail startup with clear error
        // Test via QuarkusTestProfile with invalid config
    }

    @Test
    void testTimeWindowExceedsRetention() {
        // time-window=8d, retention=7d
        // Should fail startup with error:
        // "Smart filter time window (8d) cannot exceed ILM retention (7d)"
    }
}
```

#### Regression Tests

**Run all existing tests:**
- `ElasticsearchTransformNormalizationIT` - Out-of-order events
- `ElasticsearchWorkflowInstanceStorageIT` - CRUD operations
- `ElasticsearchTaskExecutionStorageIT` - Task storage

**Ensure no breaking changes:**
- Field-level idempotency unchanged
- Status precedence logic unaffected
- Transform aggregation results identical

### PR#2: Metrics & Performance Tests

#### Metrics Collection Tests

**Test Class: `ElasticsearchTransformMetricsIT.java`**

```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
class ElasticsearchTransformMetricsIT {

    @Inject
    MeterRegistry registry;

    @Inject
    ElasticsearchClient client;

    @Test
    void testTransformMetricsCollected() throws Exception {
        // Insert test events
        insertTestWorkflowEvents(10);

        // Wait for transform processing
        Thread.sleep(2000);

        // Wait for metrics poll interval (30s default, override in test)
        Thread.sleep(35000);

        // Verify metrics exist
        var documentsProcessed = registry.find("data_index.transform.documents_processed")
            .tag("transform", "workflow-instances-transform")
            .gauge();

        assertThat(documentsProcessed).isNotNull();
        assertThat(documentsProcessed.value()).isGreaterThan(0);

        var lag = registry.find("data_index.transform.lag")
            .tag("transform", "workflow-instances-transform")
            .gauge();

        assertThat(lag).isNotNull();
        assertThat(lag.value()).isGreaterThanOrEqualTo(0);

        var state = registry.find("data_index.transform.state")
            .tag("transform", "workflow-instances-transform")
            .gauge();

        assertThat(state).isNotNull();
        assertThat(state.value()).isEqualTo(1); // started
    }

    @Test
    void testMetricsExposedViaPrometheus() {
        // GET /q/metrics
        given()
            .when().get("/q/metrics")
            .then()
            .statusCode(200)
            .body(containsString("data_index_transform_documents_processed"))
            .body(containsString("transform=\"workflow-instances-transform\""));
    }

    @Test
    void testMetricsUpdatePeriodically() throws Exception {
        // Capture initial metrics
        double initialProcessed = registry.find("data_index.transform.documents_processed")
            .tag("transform", "workflow-instances-transform")
            .gauge().value();

        // Insert more events
        insertTestWorkflowEvents(5);

        // Wait for transform + metrics poll
        Thread.sleep(37000);

        // Verify metrics increased
        double updatedProcessed = registry.find("data_index.transform.documents_processed")
            .tag("transform", "workflow-instances-transform")
            .gauge().value();

        assertThat(updatedProcessed).isGreaterThan(initialProcessed);
    }
}
```

#### Performance Benchmarking Tests

**Test Class: `ElasticsearchTransformPerformanceBenchmarkIT.java`**

```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
class ElasticsearchTransformPerformanceBenchmarkIT {

    @Inject
    ElasticsearchClient client;

    @Test
    void testSmartFilteringScalesWithDataGrowth() throws Exception {
        // Phase 1: Insert 1K events, 90% terminal (old)
        insertBulkWorkflowEvents(1000, 0.9, Duration.ofHours(2));

        // Measure transform processing time
        long phase1Start = System.currentTimeMillis();
        waitForTransformToProcess();
        long phase1Duration = System.currentTimeMillis() - phase1Start;

        LOGGER.info("Phase 1 (1K events): {} ms", phase1Duration);

        // Phase 2: Insert 10K MORE events, 90% terminal (old)
        insertBulkWorkflowEvents(10000, 0.9, Duration.ofHours(2));

        // Measure transform processing time
        long phase2Start = System.currentTimeMillis();
        waitForTransformToProcess();
        long phase2Duration = System.currentTimeMillis() - phase2Start;

        LOGGER.info("Phase 2 (11K total events): {} ms", phase2Duration);

        // Assert: Processing time delta < 50% (ideally < 20%)
        // Without smart filtering, would be 10x slower
        double increase = (double) phase2Duration / phase1Duration;
        assertThat(increase).isLessThan(1.5); // < 50% increase
    }

    @Test
    void testTransformLagUnderLoad() throws Exception {
        // Insert 1K events rapidly (batch insert)
        insertBulkWorkflowEvents(1000, 0.5, Duration.ofMinutes(30));

        // Monitor lag metric over 10 poll intervals (5 minutes)
        List<Long> lagSamples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Thread.sleep(30000); // Poll interval
            long lag = getLagMetric("workflow-instances-transform");
            lagSamples.add(lag);
            LOGGER.info("Lag sample {}: {}", i, lag);
        }

        // Assert: Max lag < 100 documents
        long maxLag = lagSamples.stream().max(Long::compare).orElse(0L);
        assertThat(maxLag).isLessThan(100);

        // Assert: Lag returns to near-0 within 5 minutes
        long finalLag = lagSamples.get(lagSamples.size() - 1);
        assertThat(finalLag).isLessThan(10);
    }

    private void insertBulkWorkflowEvents(int count, double terminalRatio, Duration ageOffset) {
        // Insert events in bulk
        // terminalRatio: percentage of events in terminal state (COMPLETED)
        // ageOffset: how old the events should be (for testing smart filtering)
    }

    private long getLagMetric(String transformId) {
        var lag = registry.find("data_index.transform.lag")
            .tag("transform", transformId)
            .gauge();
        return lag != null ? (long) lag.value() : -1;
    }
}
```

### Test Coverage Summary

| Test Category | Test Class | Tests | Purpose |
|---------------|------------|-------|---------|
| **Smart Filtering** | `ElasticsearchSmartFilteringIT` | 5 | Verify filtering logic correctness |
| **Configuration** | `ElasticsearchTransformConfigurationIT` | 3 | Verify time window and ILM config applied |
| **Validation** | `ElasticsearchConfigurationValidationIT` | 2 | Verify startup validation catches errors |
| **Regression** | Existing test suites | All | Ensure no breaking changes |
| **Metrics** | `ElasticsearchTransformMetricsIT` | 3 | Verify metrics collection works |
| **Performance** | `ElasticsearchTransformPerformanceBenchmarkIT` | 2 | Validate performance improvement |
| **Total New Tests** | | **15** | Comprehensive coverage |

---

## Error Handling

### Schema Initialization Errors

**Invalid Time Window Format:**

```java
private void validateConfiguration() {
    if (!smartFilterTimeWindow.matches("\\d+[mhd]|PT.*")) {
        throw new IllegalArgumentException(
            "Invalid time window format: " + smartFilterTimeWindow + 
            ". Expected: '1h', '30m', '2h', or ISO-8601 (PT1H)"
        );
    }
}
```

**Behavior:** Service fails to start with clear error message.

---

**Time Window Exceeds ILM Retention:**

```java
if (windowMillis > retentionMillis) {
    throw new IllegalArgumentException(
        "Smart filter time window (" + smartFilterTimeWindow + 
        ") cannot exceed ILM retention (" + rawEventsRetention + 
        "). Events older than retention are deleted."
    );
}
```

**Behavior:** Service fails to start, operator must fix configuration.

---

**Transform Already Exists with Different Config:**

```java
private void applyTransform(String name, String resourcePath) throws IOException {
    if (transformExists(name)) {
        LOGGER.warn("Transform '{}' exists. Manual recreation required if config changed.", name);
        startTransformIfStopped(name);
        return; // Don't auto-recreate (could cause data loss)
    }
    
    // Create new transform
}
```

**Behavior:**
- Detects existing transform
- Logs warning if config changed
- Doesn't auto-recreate (operator must manually stop/delete/recreate)

---

### Metrics Collection Errors

**Elasticsearch Connection Failure:**

```java
@Scheduled(every = "{data-index.metrics.transform.poll-interval}")
void collectTransformMetrics() {
    for (String transformId : transformIds) {
        try {
            var stats = client.transform().getTransformStats(...);
            updateMetrics(transformId, stats);
        } catch (IOException e) {
            LOGGER.error("I/O error collecting transform metrics", e);
            registry.gauge("data_index.transform.state",
                Tags.of("transform", transformId), -1); // unknown
        }
    }
}
```

**Behavior:**
- Transient failures don't crash application
- State metric set to -1 (unknown)
- Operators can alert on `state == -1`

---

**Transform Doesn't Exist:**

```java
private void updateMetrics(String transformId, GetTransformStatsResponse stats) {
    if (stats.transforms().isEmpty()) {
        LOGGER.warn("Transform '{}' not found, skipping metrics", transformId);
        return;
    }
    // Update metrics
}
```

**Behavior:** Graceful degradation, skip metrics for missing transforms.

---

### Edge Cases

**Time Zone Handling:**

All Elasticsearch `now` functions use UTC. No time zone configuration needed.

**Transform Processing Delay:**

- Transform frequency: 1s
- Sync delay: 0s
- Events visible within 1-2 seconds
- Rolling window (`now-1h`) catches late events automatically

**Storage Growth Alerts:**

Monitor raw event storage growth:
```promql
sum(elasticsearch_index_store_size_bytes{index=~"workflow-events-.*|task-events-.*"}) > 10GB
```

---

## Documentation Updates

### CLAUDE.md Changes

Add to "Architecture (MODE 2 - Elasticsearch)" section:

```markdown
### Transform Query Optimization (Smart Filtering)

**Performance Strategy:**
- Transforms use smart filtering to maintain constant performance as data grows
- Only process recent events (< 1 hour) + workflows/tasks still active (non-terminal)
- Old completed workflows/tasks skipped (already aggregated, won't change)

**Configuration:**
```properties
# Time window for smart filtering (default: 1h)
data-index.transform.smart-filter.time-window=1h

# Raw event retention (default: 30d)
data-index.ilm.raw-events-retention=30d
```

**Data Retention:**
- **Raw events** (`workflow-events-*`, `task-events-*`): Configurable (default 30 days)
- **Normalized data** (`workflow-instances`, `task-executions`): **Permanent**
- Raw events are temporary staging; normalized data is your source of truth

**Metrics:**
```bash
curl http://localhost:8080/q/metrics | grep data_index_transform
```
```

### New Document: docs/elasticsearch/TRANSFORM_OPTIMIZATION.md

Full documentation covering:
- How smart filtering works
- Configuration tuning guidelines
- ILM retention model
- Deployment procedures
- Monitoring & alerting
- Troubleshooting guide
- Performance characteristics

(See Design Section 6 above for complete content)

---

## Deployment Strategy

### PR#1: Smart Filtering + Configuration

**Changes:**
1. Update `workflow-instances-transform.json` (add smart filtering query)
2. Update `task-executions-transform.json` (replace hardcoded `1h` with placeholder)
3. Update `data-index-events-retention.json` (replace hardcoded `7d` with placeholder)
4. Modify `ElasticsearchSchemaInitializer` (configuration injection, validation, placeholder replacement)
5. Add integration tests (smart filtering, configuration, validation)
6. Update CLAUDE.md (architecture section)
7. Add `docs/elasticsearch/TRANSFORM_OPTIMIZATION.md`

**Deployment (Greenfield):**
1. Configure properties in `application-elasticsearch.properties`
2. Deploy Data Index service
3. Schema initializer creates transforms with smart filtering
4. Verify via Transform Stats API

**Deployment (Existing Cluster - Manual Transform Recreation):**
1. Update configuration properties
2. Stop existing transforms
3. Delete existing transforms
4. Restart Data Index service (schema initializer recreates with new config)
5. Monitor transform reprocessing (lag metric)

### PR#2: Micrometer Metrics + Benchmarking

**Changes:**
1. Add `ElasticsearchTransformMetricsCollector` component
2. Add configuration properties (poll-interval, enabled flag)
3. Add metrics integration tests
4. Add performance benchmarking tests
5. Update documentation (metrics section, Grafana examples, alerting)

**Deployment:**
1. Deploy updated Data Index service
2. Metrics auto-start polling (if enabled)
3. Configure Prometheus scraping (`/q/metrics`)
4. Import Grafana dashboards
5. Set up alerting rules

---

## Success Metrics

### Performance

**Before Optimization (match_all):**
- 1K events: ~500ms processing time
- 10K events: ~5s processing time (10x increase)
- 100K events: ~50s processing time (100x increase)

**After Optimization (smart filtering):**
- 1K events: ~500ms processing time
- 10K events: ~600ms processing time (1.2x increase)
- 100K events: ~700ms processing time (1.4x increase)

**Target:** < 50% increase in processing time as data scales 10x.

### Observability

- ✅ Transform metrics visible in Grafana
- ✅ Alerts fire when transform stopped/failed
- ✅ Lag trends visible for capacity planning

### Operational

- ✅ Configuration changes require only property update + transform recreation
- ✅ No code changes needed to tune performance
- ✅ Clear error messages guide operators

---

## Future Enhancements (Out of Scope)

1. **Dynamic Time Window Adjustment** - Auto-tune based on event arrival patterns
2. **Per-Transform Configuration** - Different time windows for workflow vs task
3. **Health Check Integration** - Add Transform lag to `/q/health` endpoint
4. **Custom Metrics Endpoint** - `/api/transform/stats` for ad-hoc queries
5. **Elasticsearch Aggregation API** - Expose aggregations via GraphQL
6. **Full-Text Search** - Leverage Elasticsearch search capabilities

---

## References

- Original MODE 2 Design: `docs/superpowers/specs/2026-04-29-elasticsearch-backend-mode2-design.md`
- Elasticsearch Transforms: https://www.elastic.co/guide/en/elasticsearch/reference/current/transforms.html
- Elasticsearch ILM: https://www.elastic.co/guide/en/elasticsearch/reference/current/index-lifecycle-management.html
- Micrometer Metrics: https://micrometer.io/docs
- Quarkus Scheduler: https://quarkus.io/guides/scheduler
