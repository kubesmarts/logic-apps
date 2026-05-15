# Elasticsearch Transform Query Optimization

## Overview

Smart filtering reduces Transform processing load by skipping old completed workflows/tasks, ensuring constant performance as your deployment scales.

## How It Works

### Data Flow & Retention

```
Raw Events (configurable retention, default 30d)
  workflow-events-* ──┐
  task-events-*       │
                      ├──> Transform (1s frequency)
                      │    - Smart filtering
                      │    - Field aggregation
                      │
Normalized Data (permanent) ◄─┘
  workflow-instances
  task-executions
```

**Raw Event Lifecycle:**
1. FluentBit writes events to `workflow-events-YYYY.MM.DD`
2. Transform aggregates events into `workflow-instances` (within 1-2 seconds)
3. ILM deletes raw index after retention period (already aggregated, no longer needed)

**Normalized Data:** Kept forever - this is your permanent workflow history.

---

### Smart Filtering Strategy

**Problem:** Without filtering, Transform processes ALL events every run:
- Day 1: 1K events → 1K processed ✓
- Day 30: 30K events → 30K processed ✗ (but 90% already completed)
- Day 365: 365K events → 365K processed ✗✗ (scale problem)

**Solution:** Only process events that can still change:

```
Process IF:
  - Event is recent (< time window) OR
  - Workflow/task is NOT in terminal state

Skip IF:
  - Event is old (> time window) AND
  - Workflow/task is COMPLETED/FAULTED/CANCELLED
```

**Result:**
- Day 1: 1K events → 1K processed
- Day 30: 30K events → 3K processed (only recent + active)
- Day 365: 365K events → 3K processed (constant!)

---

## Configuration

### Time Window

```properties
# application-elasticsearch.properties
data-index.transform.smart-filter.time-window=1h
```

**Tuning Guidelines:**

| Deployment Size | Recommended Window | Rationale |
|-----------------|-------------------|-----------|
| Dev/Test | 30m | Fast iterations, small dataset |
| Small (< 10K workflows/day) | 1h (default) | Handles typical network delays |
| Medium (10K-100K/day) | 2h | More buffer for high-throughput |
| Large (> 100K/day) | 4h | Maximum safety for event delays |

**Constraints:**
- **Minimum:** 1m (not recommended - might miss late events)
- **Maximum:** Must be ≤ ILM retention period
- **Recommended:** 1h-4h (balance between safety and performance)

### ILM Retention

```properties
# application-elasticsearch.properties
data-index.ilm.raw-events-retention=30d
```

**Tuning Guidelines:**

| Workflow Duration | Recommended Retention | Rationale |
|------------------|-----------------------|-----------|
| < 7 days | 7d | Minimal storage |
| < 30 days | 30d (default) | Standard workflows |
| < 90 days | 90d | Long-running processes |

**Important:** If workflows can run longer than retention period, increase retention or risk data loss. See "Long-Running Workflows" section.

---

## Long-Running Workflows

**Challenge:** Quarkus Flow emits delta events. If STARTED event is deleted before COMPLETED arrives, transform loses start time and input data.

**Solution:** Set ILM retention ≥ max expected workflow duration

**Example:**
- Workflow typically completes in 7 days: Use retention=7d
- Workflow can take up to 30 days: Use retention=30d
- Workflow can take up to 90 days: Use retention=90d

**Validation:** Time window must be ≤ ILM retention (enforced at startup)

---

## Testing

### Integration Tests

```bash
# Run smart filtering tests
mvn test -Dtest=ElasticsearchSmartFilteringIT

# Run configuration tests
mvn test -Dtest=ElasticsearchTransformConfigurationIT
```

### Manual Testing

```bash
# Start with custom config
mvn quarkus:dev \
  -Ddata-index.transform.smart-filter.time-window=30m \
  -Ddata-index.ilm.raw-events-retention=30d

# Verify transform query
curl http://localhost:9200/_transform/workflow-instances-transform | jq '.transforms[0].source.query'

# Should see: "now-30m" in the query
```

---

## Metrics & Monitoring

### Exposed Metrics

The Data Index service exposes Prometheus-compatible metrics for both transforms:

**Metrics:**
- `data_index_transform_documents_processed{transform="..."}` - Total documents processed
- `data_index_transform_documents_indexed{transform="..."}` - Total documents indexed
- `data_index_transform_lag{transform="..."}` - Processing lag (processed - indexed)
- `data_index_transform_state{transform="..."}` - Transform state (0=stopped, 1=started, 2=failed, -1=unknown)
- `data_index_transform_last_checkpoint{transform="..."}` - Last checkpoint timestamp (epoch millis)

**Transforms tracked:**
- `workflow-instances-transform`
- `task-executions-transform`

### Prometheus Endpoint

Metrics are available at:
```
GET /q/metrics
```

Example output:
```
# HELP data_index_transform_documents_processed  
# TYPE data_index_transform_documents_processed gauge
data_index_transform_documents_processed{transform="workflow-instances-transform"} 45230.0
data_index_transform_documents_processed{transform="task-executions-transform"} 128450.0

# HELP data_index_transform_lag  
# TYPE data_index_transform_lag gauge
data_index_transform_lag{transform="workflow-instances-transform"} 0.0
data_index_transform_lag{transform="task-executions-transform"} 15.0
```

### Configuration

```properties
# Enable/disable transform metrics collection
data-index.metrics.transform.enabled=true

# How often to poll Transform Stats API
# Default: 30s (recommended for production)
# Lower values (5s-10s) for development
# Higher values (60s-120s) for high-scale deployments
data-index.metrics.transform.poll-interval=30s
```

### Grafana Dashboard

**Recommended Queries:**

```promql
# Transform processing rate (events/second)
rate(data_index_transform_documents_processed{transform="workflow-instances-transform"}[5m])

# Transform lag (should stay near 0)
data_index_transform_lag

# Transform health (1 = healthy)
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
          description: "Lag: {{ $value }} documents behind"
```

**Dashboard Panels:**

1. **Processing Rate** (Graph)
   - Query: `rate(data_index_transform_documents_processed[5m])`
   - Y-axis: events/second
   - Legend: `{{ transform }}`

2. **Lag** (Graph)
   - Query: `data_index_transform_lag`
   - Y-axis: document count
   - Threshold: Warning at 100, Critical at 1000

3. **State** (Stat)
   - Query: `data_index_transform_state`
   - Mappings: 0=Stopped (red), 1=Running (green), 2=Failed (red), -1=Unknown (yellow)

4. **Total Processed** (Stat)
   - Query: `data_index_transform_documents_processed`
   - Format: number with commas

### Troubleshooting

**High Lag**

Symptom: `data_index_transform_lag` consistently > 100

Possible causes:
1. **High event volume** - Transform processing can't keep up
   - Check `rate(data_index_transform_documents_processed[5m])`
   - Consider reducing event volume or increasing Elasticsearch resources

2. **Slow Elasticsearch** - Cluster under load
   - Check Elasticsearch cluster metrics (CPU, memory, disk I/O)
   - Review `_transform/<id>/_stats` for slow search/index times

3. **Transform stopped** - Check `data_index_transform_state`
   - If not `1` (started), investigate Elasticsearch logs
   - Restart transform if needed

**Metrics Not Updating**

Symptom: Metrics stuck at same value

Possible causes:
1. **Metrics collection disabled** - Check `data-index.metrics.transform.enabled=true`
2. **Transform not running** - Check transform state via Elasticsearch API
3. **Scheduler not running** - Check application logs for scheduled job execution

**Manual Verification:**

```bash
# Check metrics endpoint
curl http://localhost:8080/q/metrics | grep data_index_transform

# Check transform stats directly
curl http://localhost:9200/_transform/workflow-instances-transform/_stats?pretty
```

---

## Troubleshooting

### Validation Errors

**Error:** "Invalid time window format: xyz"

**Solution:** Use valid format: `1h`, `30m`, `2h`, `7d` or ISO-8601 (`PT1H`, `P7D`)

---

**Error:** "Smart filter time window (8d) cannot exceed ILM retention (7d)"

**Solution:** Either:
1. Reduce time window: `data-index.transform.smart-filter.time-window=7d`
2. Increase retention: `data-index.ilm.raw-events-retention=8d`

---

### Performance Issues

**Symptom:** Transform processing time increasing

**Diagnosis:**
```bash
# Check transform stats
curl http://localhost:9200/_transform/workflow-instances-transform/_stats
```

**Solutions:**
1. Verify smart filtering is active (check transform query)
2. Reduce time window if too wide
3. Check for large number of non-terminal workflows

---

## References

- Main Documentation: `CLAUDE.md`
- Implementation Plan: `docs/superpowers/plans/2026-05-13-elasticsearch-transform-optimization-pr1.md`
- Design Spec: `docs/superpowers/specs/2026-05-13-elasticsearch-transform-optimization-design.md`
