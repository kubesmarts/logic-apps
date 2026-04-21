# Event Processor Observability Guide

**Date**: 2026-04-17  
**Status**: ✅ **IMPLEMENTED** - Production Ready  
**Build Status**: ✅ SUCCESS

---

## 📊 Overview

Comprehensive observability for the **Event Processor** (scheduler/batch processing) that normalizes events from event tables into final tables.

### What's Included

1. **Prometheus Metrics** - Gauges, timers, and counters
2. **Health Checks** - Kubernetes liveness/readiness probes
3. **Enhanced Logging** - Backlog, duration, and warning logs
4. **REST Metrics Endpoint** - Dashboard-ready JSON metrics

---

## 🎯 Key Metrics

### Prometheus Metrics

All metrics are available at: `http://localhost:8080/q/metrics`

#### 1. Processing Lag (Gauge)

**Metric**: `event_processor_lag_seconds`

**Description**: Average age of unprocessed events (NOW - event_time)

**Labels**:
- `processor="workflow"` - Workflow instance events
- `processor="task"` - Task execution events

**Example**:
```
event_processor_lag_seconds{processor="workflow"} 2.5
event_processor_lag_seconds{processor="task"} 3.1
```

**Use Case**: Alert if lag > 60s (events not being processed fast enough)

---

#### 2. Event Backlog (Gauge)

**Metric**: `event_processor_backlog_total`

**Description**: Number of unprocessed events (processed=false)

**Labels**:
- `processor="workflow"` - Workflow instance events
- `processor="task"` - Task execution events

**Example**:
```
event_processor_backlog_total{processor="workflow"} 0
event_processor_backlog_total{processor="task"} 0
```

**Use Case**: Alert if backlog > 1000 (events accumulating faster than processing)

---

#### 3. Oldest Unprocessed Event Age (Gauge)

**Metric**: `event_processor_oldest_unprocessed_age_seconds`

**Description**: Age of oldest unprocessed event

**Labels**:
- `processor="workflow"` - Workflow instance events
- `processor="task"` - Task execution events

**Example**:
```
event_processor_oldest_unprocessed_age_seconds{processor="workflow"} 0
event_processor_oldest_unprocessed_age_seconds{processor="task"} 0
```

**Use Case**: Detect stuck events (event older than 5 minutes but still unprocessed)

---

#### 4. Batch Processing Duration (Timer)

**Metric**: `event_processor_batch_duration_seconds`

**Description**: Time taken to process a batch of events

**Labels**:
- `processor="workflow"` - Workflow instance processor
- `processor="task"` - Task execution processor

**Example**:
```
event_processor_batch_duration_seconds_sum{processor="workflow"} 0.567
event_processor_batch_duration_seconds_count{processor="workflow"} 8
event_processor_batch_duration_seconds_max{processor="workflow"} 0.095
```

**Percentiles**:
```
event_processor_batch_duration_seconds{processor="workflow",quantile="0.5"} 0.067
event_processor_batch_duration_seconds{processor="workflow",quantile="0.95"} 0.089
event_processor_batch_duration_seconds{processor="workflow",quantile="0.99"} 0.095
```

**Use Case**: Monitor performance, alert if p95 > 1s

---

#### 5. Events Processed (Counter)

**Metric**: `event_processor_events_processed_total`

**Description**: Total number of events processed

**Labels**:
- `processor="workflow"|"task"` - Processor type
- `status="success"|"failed"` - Processing result

**Example**:
```
event_processor_events_processed_total{processor="workflow",status="success"} 14
event_processor_events_processed_total{processor="task",status="success"} 12
```

**Use Case**: Monitor processing rate (events/sec), track success rate

---

#### 6. Processing Errors (Counter)

**Metric**: `event_processor_errors_total`

**Description**: Number of processing errors

**Labels**:
- `processor="workflow"|"task"` - Processor type
- `type="PersistenceException"|"..."` - Error type

**Example**:
```
event_processor_errors_total{processor="workflow",type="PersistenceException"} 0
```

**Use Case**: Alert on any errors (error rate > 0)

---

#### 7. Batch Size (Gauge)

**Metric**: `event_processor_batch_size`

**Description**: Number of events in last processed batch

**Labels**:
- `processor="workflow"|"task"` - Processor type

**Example**:
```
event_processor_batch_size{processor="workflow"} 4
event_processor_batch_size{processor="task"} 4
```

**Use Case**: Monitor batch utilization (if consistently at max, may need to increase batch size)

---

## 🏥 Health Checks

### Liveness Probe

**Endpoint**: `http://localhost:8080/q/health/live`

**Criteria**:
- Processing lag < 60s (configurable)
- Backlog < 1000 events (configurable)

**Response Example** (Healthy):
```json
{
  "status": "UP",
  "checks": [
    {
      "name": "event-processor",
      "status": "UP",
      "data": {
        "enabled": true,
        "workflowEventLagSeconds": 2,
        "taskEventLagSeconds": 3,
        "lagThresholdSeconds": 60,
        "workflowEventBacklog": 0,
        "taskEventBacklog": 0,
        "backlogThreshold": 1000,
        "lagHealthy": true,
        "backlogHealthy": true
      }
    }
  ]
}
```

**Response Example** (Unhealthy):
```json
{
  "status": "DOWN",
  "checks": [
    {
      "name": "event-processor",
      "status": "DOWN",
      "data": {
        "enabled": true,
        "workflowEventLagSeconds": 125,
        "taskEventLagSeconds": 98,
        "lagThresholdSeconds": 60,
        "workflowEventBacklog": 2500,
        "taskEventBacklog": 1800,
        "backlogThreshold": 1000,
        "lagHealthy": false,
        "backlogHealthy": false
      }
    }
  ]
}
```

### Kubernetes Integration

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: data-index
spec:
  containers:
  - name: data-index
    image: data-index:latest
    ports:
    - containerPort: 8080
    livenessProbe:
      httpGet:
        path: /q/health/live
        port: 8080
      initialDelaySeconds: 30
      periodSeconds: 10
      timeoutSeconds: 5
      failureThreshold: 3
    readinessProbe:
      httpGet:
        path: /q/health/ready
        port: 8080
      initialDelaySeconds: 10
      periodSeconds: 5
      timeoutSeconds: 3
```

---

## 📝 Enhanced Logging

### Log Levels

- **TRACE**: Event processor disabled messages
- **DEBUG**: Batch processing details, backlog calculations
- **INFO**: Batch processing summary, backlog warnings
- **WARN**: Slow processing warnings
- **ERROR**: Processing failures

### Log Examples

#### Normal Operation (No Backlog)
```
TRACE EventProcessorScheduler - Checking for unprocessed events...
INFO  WorkflowInstanceEventProcessor - Processed 4 workflow instance events (2 instances)
INFO  TaskExecutionEventProcessor - Processed 4 task execution events (2 tasks)
INFO  EventProcessorScheduler - Processed 4 workflow events, 4 task events in 89ms
```

#### With Backlog
```
TRACE EventProcessorScheduler - Checking for unprocessed events...
INFO  EventProcessorScheduler - Event processor backlog: 150 workflow events, 120 task events
INFO  WorkflowInstanceEventProcessor - Processed 100 workflow instance events (85 instances)
INFO  TaskExecutionEventProcessor - Processed 100 task execution events (92 tasks)
INFO  EventProcessorScheduler - Processed 100 workflow events, 100 task events in 234ms
```

#### Slow Processing Warning
```
TRACE EventProcessorScheduler - Checking for unprocessed events...
INFO  EventProcessorScheduler - Event processor backlog: 500 workflow events, 450 task events
INFO  WorkflowInstanceEventProcessor - Processed 100 workflow instance events (88 instances)
INFO  TaskExecutionEventProcessor - Processed 100 task execution events (95 tasks)
INFO  EventProcessorScheduler - Processed 100 workflow events, 100 task events in 1523ms
WARN  EventProcessorScheduler - Slow event processing: 1523ms for 200 events (threshold: 1000ms)
```

#### Processing Error
```
ERROR EventProcessorScheduler - Error processing events, will retry on next scheduled run
jakarta.persistence.PersistenceException: ...
```

---

## 🔌 REST Metrics Endpoint

### Endpoint

**URL**: `http://localhost:8080/event-processor/metrics`  
**Method**: GET  
**Content-Type**: application/json

### Response

```json
{
  "workflowEvents": {
    "unprocessedCount": 0,
    "processedLast1Hour": 14,
    "processedLast24Hours": 14,
    "averageProcessingLagSeconds": 2.5,
    "maxProcessingLagSeconds": 5.1,
    "oldestUnprocessedAgeSeconds": null
  },
  "taskEvents": {
    "unprocessedCount": 0,
    "processedLast1Hour": 12,
    "processedLast24Hours": 12,
    "averageProcessingLagSeconds": 3.2,
    "maxProcessingLagSeconds": 6.4,
    "oldestUnprocessedAgeSeconds": null
  }
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| `unprocessedCount` | long | Number of unprocessed events (backlog) |
| `processedLast1Hour` | long | Events processed in last 1 hour |
| `processedLast24Hours` | long | Events processed in last 24 hours |
| `averageProcessingLagSeconds` | double | Average time from event_time to processed_at |
| `maxProcessingLagSeconds` | double | Maximum time from event_time to processed_at |
| `oldestUnprocessedAgeSeconds` | long | Age of oldest unprocessed event (null if none) |

### Use Cases

- **Grafana Dashboards**: Query this endpoint from Grafana for custom visualizations
- **Custom Alerting**: Poll this endpoint and trigger alerts based on thresholds
- **Operational Visibility**: Quick snapshot of event processor health
- **Capacity Planning**: Track processing rates over time

---

## ⚙️ Configuration

### application.properties

```properties
# ============================================================================
# Event Processor Configuration
# ============================================================================

# Enable/disable event processing
data-index.event-processor.enabled=true

# Processing interval (how often to poll for events)
data-index.event-processor.interval=5s

# Batch size (events to process per run)
data-index.event-processor.batch-size=100

# Event retention period (delete processed events after N days)
data-index.event-processor.retention-days=30

# Cleanup schedule (cron expression)
data-index.event-cleanup.cron=0 0 2 * * ?

# Slow processing threshold (warn if batch takes longer than this)
data-index.event-processor.slow-processing.threshold.ms=1000

# ============================================================================
# Health Check Thresholds
# ============================================================================

# Processing lag threshold (health check fails if lag exceeds this)
data-index.event-processor.lag.threshold.seconds=60

# Backlog threshold (health check fails if backlog exceeds this)
data-index.event-processor.backlog.threshold=1000

# ============================================================================
# Metrics Configuration
# ============================================================================

# Enable Prometheus metrics endpoint
quarkus.micrometer.enabled=true
quarkus.micrometer.registry-enabled-default=true
quarkus.micrometer.export.prometheus.enabled=true
quarkus.micrometer.export.prometheus.path=/q/metrics

# Enable health checks
quarkus.smallrye-health.enabled=true
quarkus.smallrye-health.ui.enable=true
```

---

## 📈 Grafana Dashboard

### Example Dashboard Panels

#### 1. Event Processing Lag
```promql
# Average processing lag
event_processor_lag_seconds{processor="workflow"}
event_processor_lag_seconds{processor="task"}
```

**Panel Type**: Time series  
**Alert**: > 60s

---

#### 2. Event Backlog
```promql
# Unprocessed event count
event_processor_backlog_total{processor="workflow"}
event_processor_backlog_total{processor="task"}
```

**Panel Type**: Time series  
**Alert**: > 1000

---

#### 3. Processing Rate
```promql
# Events processed per second (rate over 5m)
rate(event_processor_events_processed_total{status="success"}[5m])
```

**Panel Type**: Time series  
**Unit**: events/sec

---

#### 4. Batch Processing Duration (p95)
```promql
# 95th percentile batch processing time
histogram_quantile(0.95, 
  rate(event_processor_batch_duration_seconds_bucket[5m]))
```

**Panel Type**: Time series  
**Unit**: seconds  
**Alert**: > 1s

---

#### 5. Error Rate
```promql
# Error rate (errors per minute)
rate(event_processor_errors_total[1m]) * 60
```

**Panel Type**: Time series  
**Alert**: > 0

---

#### 6. Processing Throughput
```promql
# Total events processed (last 24h)
increase(event_processor_events_processed_total{status="success"}[24h])
```

**Panel Type**: Stat  
**Unit**: events

---

## 🚨 Alerting Rules

### Prometheus Alert Rules

```yaml
groups:
  - name: event_processor_alerts
    interval: 30s
    rules:
      # High processing lag
      - alert: EventProcessorHighLag
        expr: event_processor_lag_seconds > 60
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Event processor lag is high"
          description: "{{ $labels.processor }} event processor lag is {{ $value }}s (threshold: 60s)"

      # High backlog
      - alert: EventProcessorHighBacklog
        expr: event_processor_backlog_total > 1000
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Event processor backlog is high"
          description: "{{ $labels.processor }} event backlog is {{ $value }} (threshold: 1000)"

      # Stuck events (oldest unprocessed > 5 minutes)
      - alert: EventProcessorStuckEvents
        expr: event_processor_oldest_unprocessed_age_seconds > 300
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Event processor has stuck events"
          description: "{{ $labels.processor }} has events stuck for {{ $value }}s"

      # Slow processing (p95 > 1s)
      - alert: EventProcessorSlowProcessing
        expr: |
          histogram_quantile(0.95, 
            rate(event_processor_batch_duration_seconds_bucket[5m])) > 1
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Event processor is slow"
          description: "{{ $labels.processor }} p95 processing time is {{ $value }}s"

      # Processing errors
      - alert: EventProcessorErrors
        expr: rate(event_processor_errors_total[5m]) > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Event processor errors detected"
          description: "{{ $labels.processor }} is experiencing {{ $value }} errors/sec (type: {{ $labels.type }})"

      # Health check failure
      - alert: EventProcessorUnhealthy
        expr: up{job="data-index"} == 0
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Event processor health check failed"
          description: "Data Index event processor is unhealthy or down"
```

---

## 🔍 Troubleshooting

### Problem: High Processing Lag

**Symptoms**:
- `event_processor_lag_seconds` > 60
- Logs show consistent backlog

**Diagnosis**:
```bash
# Check backlog
curl http://localhost:8080/event-processor/metrics | jq '.workflowEvents.unprocessedCount'

# Check Prometheus
event_processor_lag_seconds{processor="workflow"}
```

**Solutions**:
1. **Increase batch size**: `data-index.event-processor.batch-size=200`
2. **Decrease polling interval**: `data-index.event-processor.interval=2s`
3. **Scale horizontally**: Run multiple Data Index instances (with leader election)
4. **Optimize database**: Add indexes, tune PostgreSQL

---

### Problem: High Backlog

**Symptoms**:
- `event_processor_backlog_total` > 1000
- Events accumulating faster than processing

**Diagnosis**:
```bash
# Check processing rate vs incoming rate
curl http://localhost:8080/event-processor/metrics | jq '.workflowEvents.processedLast1Hour'

# Check batch processing duration
event_processor_batch_duration_seconds_max
```

**Solutions**:
1. **Increase processing capacity**: Scale up (larger batch) or scale out (more instances)
2. **Check for bottlenecks**: Database slow? Network issues?
3. **Verify FluentBit rate**: Is event ingestion too fast?

---

### Problem: Slow Processing

**Symptoms**:
- Logs: `WARN Slow event processing: 1523ms for 200 events`
- `event_processor_batch_duration_seconds{quantile="0.95"}` > 1s

**Diagnosis**:
```bash
# Check database query performance
EXPLAIN ANALYZE SELECT e FROM WorkflowInstanceEvent e WHERE e.processed = false ORDER BY e.eventTime ASC;

# Check database connections
SELECT count(*) FROM pg_stat_activity WHERE datname = 'dataindex';
```

**Solutions**:
1. **Database tuning**: Increase connection pool, optimize queries
2. **Reduce batch size**: Smaller batches = faster processing
3. **Add database indexes**: Already present, but verify

---

### Problem: Processing Errors

**Symptoms**:
- `event_processor_errors_total` > 0
- Logs: `ERROR Error processing events`

**Diagnosis**:
```bash
# Check error logs
grep "ERROR.*EventProcessor" /var/log/data-index.log

# Check error type
event_processor_errors_total{type="..."}
```

**Solutions**:
1. **Check database connectivity**: Connection pool exhausted?
2. **Check event data integrity**: Malformed JSON in event data?
3. **Check for deadlocks**: PostgreSQL logs?

---

## 📚 Implementation Files

| File | Description |
|------|-------------|
| `EventProcessorMetrics.java` | Prometheus gauges (lag, backlog, oldest age) |
| `EventProcessorHealthCheck.java` | Kubernetes health check |
| `EventProcessorScheduler.java` | Enhanced logging with backlog and duration |
| `WorkflowInstanceEventProcessor.java` | Metrics for workflow event processing |
| `TaskExecutionEventProcessor.java` | Metrics for task event processing |
| `EventProcessorMetricsResource.java` | REST endpoint for dashboard metrics |
| `EventMetrics.java` | DTO for event metrics |
| `EventProcessorMetricsResponse.java` | DTO for metrics response |
| `application.properties` | Configuration |

---

## 🎯 Best Practices

### 1. Set Appropriate Thresholds

**Development**:
```properties
data-index.event-processor.lag.threshold.seconds=120
data-index.event-processor.backlog.threshold=5000
data-index.event-processor.slow-processing.threshold.ms=2000
```

**Production**:
```properties
data-index.event-processor.lag.threshold.seconds=60
data-index.event-processor.backlog.threshold=1000
data-index.event-processor.slow-processing.threshold.ms=1000
```

---

### 2. Monitor Key Metrics

**Must Monitor**:
- Processing lag (alert if > 60s)
- Backlog (alert if > 1000)
- Error rate (alert if > 0)
- Health check status

**Nice to Monitor**:
- Batch processing duration (p95, p99)
- Processing throughput (events/sec)
- Batch size utilization

---

### 3. Set Up Alerts

**Critical Alerts** (Page on-call):
- Event processor unhealthy (health check fails)
- Processing errors > 0
- Stuck events (oldest > 5 minutes)

**Warning Alerts** (Slack notification):
- High lag (> 60s for 5 minutes)
- High backlog (> 1000 for 5 minutes)
- Slow processing (p95 > 1s for 10 minutes)

---

### 4. Regular Reviews

**Daily**:
- Check Grafana dashboard for anomalies
- Review error logs (if any)

**Weekly**:
- Review processing trends (throughput, lag)
- Adjust batch size/interval if needed
- Check retention cleanup (event table size)

**Monthly**:
- Capacity planning (processing rate vs growth)
- Review alert thresholds
- Optimize if needed

---

## ✅ Summary

**Implemented Observability**:
- ✅ Prometheus metrics (7 metric types)
- ✅ Health checks (Kubernetes integration)
- ✅ Enhanced logging (backlog, duration, warnings)
- ✅ REST metrics endpoint (dashboard-ready)

**Ready For**:
- ✅ Grafana dashboards
- ✅ Prometheus alerts
- ✅ Kubernetes health probes
- ✅ Production monitoring

**Build Status**: ✅ SUCCESS  
**Production Ready**: ✅ YES

---

**Date**: 2026-04-17  
**Author**: Claude Code (Sonnet 4.5)  
**Status**: Complete and tested
