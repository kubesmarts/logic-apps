# MODE 1 Event Reliability and Loss Prevention

**Date:** 2026-04-23  
**Status:** Production Readiness Guide

## Overview

MODE 1 uses stdout-based log collection with FluentBit. This document describes potential event loss scenarios and mitigation strategies.

## Event Flow and Guarantees

```
Quarkus Flow App
    ↓ (stdout write - OS buffer)
Kernel Log Buffer
    ↓ (flush to disk)
/var/log/containers/<pod>_<namespace>_<container>.log
    ↓ (FluentBit tail with position tracking)
FluentBit Memory Buffer
    ↓ (PostgreSQL output with retry)
PostgreSQL workflow_events_raw
    ↓ (BEFORE INSERT trigger - synchronous)
PostgreSQL workflow_instances
```

**Critical Points of Failure:**
1. App crash before stdout flush
2. Node termination before log written to disk
3. Log rotation before FluentBit reads
4. FluentBit buffer overflow
5. FluentBit crash before committing position
6. PostgreSQL unavailability
7. Parse/filter errors

## Namespace Configuration

### Current Setup

FluentBit tails logs from a **specific namespace** using pattern:
```
/var/log/containers/*_${WORKFLOW_NAMESPACE}_*.log
```

Where `WORKFLOW_NAMESPACE` env var is set in DaemonSet (default: `workflows`).

### Configuration

**File:** `scripts/fluentbit/mode1-postgresql-triggers/kubernetes/daemonset.yaml`

```yaml
env:
- name: WORKFLOW_NAMESPACE
  value: "workflows"  # Change this to match your deployment namespace
```

**Actual log file example:**
```
/var/log/containers/workflow-test-app-7d8f9c6b5-abc123_workflows_workflow-app-xyz789.log
                     └──────pod-name──────────┘  └─namespace┘ └──container-name+id──┘
```

### Multi-Namespace Support

To capture events from **multiple namespaces**, use one of these approaches:

#### Option 1: Multiple Path Entries
```conf
[INPUT]
    Name    tail
    Path    /var/log/containers/*_workflows_*.log
    Path    /var/log/containers/*_production_*.log
    Path    /var/log/containers/*_staging_*.log
    Parser  docker
    Tag     kube.*
```

#### Option 2: Wildcard All Namespaces + Filter
```conf
[INPUT]
    Name    tail
    Path    /var/log/containers/*.log
    Parser  docker
    Tag     kube.*

[FILTER]
    Name    kubernetes
    Match   kube.*
    ...

[FILTER]
    Name    grep
    Match   kube.*
    Regex   kubernetes.namespace_name ^(workflows|production|staging)$
```

**Recommendation:** Use Option 1 if you know the namespaces (better performance). Use Option 2 for dynamic namespaces.

## Event Loss Scenarios and Mitigation

### 1. Application Crashes Before Stdout Flush

**Scenario:**
```
App: workflow.started event → stdout buffer
App: CRASH (before buffer flush)
Result: Event never written to /var/log/containers/
```

**Risk:** Low  
**Reason:** OS flushes stdout on newline for line-buffered output  
**Mitigation:**
- Quarkus Flow events always end with `\n` (newline)
- OS typically flushes immediately
- If app crashes mid-workflow, workflow will be re-executed (idempotent)

**Monitoring:**
```bash
# Check for crashed pods
kubectl get pods -n workflows --field-selector=status.phase=Failed
```

### 2. Node Termination Before Disk Write

**Scenario:**
```
App: Event → stdout → OS buffer → kernel
Node: SIGTERM (drain starts)
Node: SIGKILL after 30s (grace period)
Result: In-flight events lost if not flushed to /var/log/
```

**Risk:** Medium  
**Reason:** Node drain gives 30s grace period, usually enough  
**Mitigation:**
- Set pod `terminationGracePeriodSeconds: 60` (allow more time)
- Use `preStop` hook to flush logs:
  ```yaml
  lifecycle:
    preStop:
      exec:
        command: ["/bin/sh", "-c", "sleep 5"]  # Let stdout flush
  ```
- Monitor pod evictions:
  ```bash
  kubectl get events --field-selector reason=Evicted
  ```

### 3. Log Rotation Before FluentBit Reads

**Scenario:**
```
Kubernetes: Rotates /var/log/containers/pod.log (size > 10MB)
  - pod.log → pod.log.1
  - New pod.log created
FluentBit: Still reading pod.log.1 (tracked in DB)
Kubernetes: Deletes pod.log.6 (max 5 rotated files)
Result: Events in pod.log.6 lost if FluentBit didn't read them
```

**Risk:** High if FluentBit falls behind  
**Kubernetes Defaults:**
- Max log file size: 10MB
- Max backup files: 5
- Total retention: ~50MB per container

**Mitigation:**

#### A. Increase Kubernetes Log Retention
**Node-level** (requires cluster admin):
```yaml
# /var/lib/kubelet/config.yaml
containerLogMaxSize: 100Mi    # Default: 10Mi
containerLogMaxFiles: 10      # Default: 5
```

#### B. Increase FluentBit Processing Speed
```conf
[INPUT]
    Refresh_Interval  1       # Check for new logs every 1s (default: 5s)
    Mem_Buf_Limit     20MB    # Larger memory buffer

[OUTPUT]
    Workers           2       # Parallel PostgreSQL writes
```

#### C. Monitor FluentBit Lag
```bash
# Check FluentBit position tracking
kubectl exec -n logging <fluentbit-pod> -- \
  cat /tail-db/fluent-bit-kube.db

# Check if FluentBit is falling behind (metrics)
curl http://<fluentbit-pod>:2020/api/v1/metrics/prometheus | grep input_bytes
```

#### D. Alert on High Log Rate
```promql
# Prometheus alert
rate(fluentd_input_bytes_total[5m]) > 1000000  # > 1MB/s
```

### 4. FluentBit Buffer Overflow

**Scenario:**
```
FluentBit: Reading logs faster than PostgreSQL can accept
FluentBit: Memory buffer fills up (Mem_Buf_Limit: 5MB)
FluentBit: Drops oldest records to make room
Result: Events lost
```

**Risk:** High under load  
**Mitigation:**

#### A. Increase Memory Buffer
```conf
[INPUT]
    Mem_Buf_Limit     20MB    # Default: 5MB (increase for bursts)

[SERVICE]
    storage.metrics   on
    storage.path      /tail-db/storage  # Enable filesystem buffering
    storage.max_chunks_up  256          # More buffering capacity
```

#### B. Enable Filesystem Buffering
```conf
[INPUT]
    storage.type      filesystem  # Spill to disk if memory full
```

**DaemonSet:**
```yaml
volumeMounts:
- name: storage-buffer
  mountPath: /tail-db/storage

volumes:
- name: storage-buffer
  emptyDir:
    sizeLimit: 1Gi  # Allow up to 1GB disk buffering
```

#### C. Monitor Buffer Usage
```bash
# FluentBit metrics endpoint
curl http://<fluentbit-pod>:2020/api/v1/metrics | grep buffer
```

### 5. FluentBit Crash Before Position Commit

**Scenario:**
```
FluentBit: Reads events from pod.log
FluentBit: Sends to PostgreSQL successfully
FluentBit: CRASH before updating position in /tail-db/fluent-bit-kube.db
FluentBit: Restarts, re-reads from old position
Result: Duplicate events (NOT loss, but duplication)
```

**Risk:** Low (duplicates handled by triggers)  
**Mitigation:**
- PostgreSQL triggers use UPSERT: `ON CONFLICT (id) DO UPDATE`
- Duplicate events update existing records (idempotent)
- Monitor for crash loop:
  ```bash
  kubectl get pods -n logging -l app=workflows-fluent-bit-mode1 \
    --field-selector=status.phase=CrashLoopBackOff
  ```

### 6. PostgreSQL Unavailability

**Scenario:**
```
FluentBit: Tries to write event to PostgreSQL
PostgreSQL: Connection refused / timeout
FluentBit: Retries up to Retry_Limit (5)
FluentBit: Gives up after 5 retries
Result: Event lost
```

**Risk:** High if PostgreSQL down for extended period  
**Current Configuration:**
```conf
[OUTPUT]
    Async           Off         # Blocking mode (wait for PostgreSQL)
    Retry_Limit     5           # Retry 5 times before giving up
```

**Mitigation:**

#### A. Increase Retry Limit and Delay
```conf
[OUTPUT]
    Retry_Limit     False       # Infinite retries (wait forever)
```

**Warning:** This blocks FluentBit input if PostgreSQL is down long-term, causing buffer overflow.

#### B. Use Storage Buffer (Recommended)
```conf
[INPUT]
    storage.type    filesystem   # Spill to disk during outages

[SERVICE]
    storage.max_chunks_up  512   # Large buffer
```

#### C. PostgreSQL High Availability
- Use PostgreSQL HA solution (Patroni, Stolon, CloudNativePG)
- Connection pooling (PgBouncer)
- Read replicas for failover

#### D. Monitor PostgreSQL Availability
```bash
# Check PostgreSQL health
kubectl get pods -n postgresql -l app.kubernetes.io/component=primary

# Alert on connection failures in FluentBit logs
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i "connection refused\|timeout"
```

### 7. JSON Parse Failures

**Scenario:**
```
App: Outputs truncated/malformed JSON
FluentBit: Fails to parse as JSON
FluentBit: Skips line
Result: Event lost
```

**Risk:** Low (Quarkus Flow uses structured logging library)  
**Mitigation:**

#### A. Monitor Parse Errors
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i "parser\|json.*error"
```

#### B. Emit Unparsed Records
```conf
[FILTER]
    Name              parser
    Key_Name          log
    Parser            json
    Reserve_Data      On
    Preserve_Key      On      # Keep original if parse fails
```

#### C. Log Parse Failures to Separate Table
```conf
[OUTPUT]
    Name   postgresql
    Match  kube.*
    Table  unparsed_events
    # Catch-all for events that didn't match filters
```

## Reliability Guarantees

### What FluentBit Guarantees

✅ **At-least-once delivery** with `Async Off` + position tracking  
✅ **Duplicate handling** via position DB  
✅ **Crash recovery** from last committed position  
✅ **Retry on transient failures** up to `Retry_Limit`  

### What FluentBit Does NOT Guarantee

❌ **Event ordering** - Events can arrive out of order (triggers handle this)  
❌ **Zero loss during node termination** - In-flight events may be lost  
❌ **Infinite buffering** - Buffer limits exist, overflow = loss  
❌ **Persistence across node loss** - Position DB is per-node  

## Production Recommendations

### Minimal (Acceptable Loss Risk)

Current MODE 1 configuration:
- `Mem_Buf_Limit: 5MB`
- `Retry_Limit: 5`
- `Async: Off`
- Position tracking enabled

**Expected Loss:** < 0.1% under normal conditions

### Recommended (Low Loss Risk)

```conf
[INPUT]
    Mem_Buf_Limit     20MB
    storage.type      filesystem
    Refresh_Interval  1

[OUTPUT]
    Retry_Limit       False  # Infinite retries
    Workers           2

[SERVICE]
    storage.path             /tail-db/storage
    storage.max_chunks_up    512
```

**DaemonSet:**
```yaml
volumeMounts:
- name: storage-buffer
  mountPath: /tail-db/storage

volumes:
- name: storage-buffer
  emptyDir:
    sizeLimit: 2Gi

resources:
  requests:
    memory: "256Mi"  # More memory for buffering
  limits:
    memory: "1Gi"
```

**Expected Loss:** < 0.01% under normal conditions

### High Reliability (Near-Zero Loss)

If event loss is unacceptable, consider:

#### Option 1: Dual Write (App-Level)
```
Quarkus Flow → stdout (for observability)
            ↓
            → PostgreSQL (direct insert via JDBC)
```

**Pros:** No intermediary, guaranteed delivery  
**Cons:** App coupled to data-index, requires connection pool

#### Option 2: Kafka Buffer
```
Quarkus Flow → stdout → FluentBit → Kafka → Kafka Consumer → PostgreSQL
```

**Pros:** Kafka durability, replay capability  
**Cons:** More complex (MODE 3 architecture)

#### Option 3: File-Based with Persistent Volumes
```
Quarkus Flow → /data/events.log (PersistentVolume)
            ↓
FluentBit → tail → PostgreSQL
```

**Pros:** Events survive pod/node restarts  
**Cons:** Requires PV provisioning, slower I/O

## Monitoring and Alerting

### Key Metrics to Monitor

1. **FluentBit Health**
```bash
kubectl get pods -n logging -l app=workflows-fluent-bit-mode1
```

2. **Buffer Usage**
```promql
fluentbit_input_bytes_total - fluentbit_output_bytes_total
```

3. **Retry Rate**
```promql
rate(fluentbit_output_retries_total[5m]) > 0
```

4. **Event Count Mismatch**
```sql
-- Compare workflow app logs vs database
SELECT 
  (SELECT COUNT(*) FROM workflow_events_raw) as raw_events,
  (SELECT COUNT(*) FROM workflow_instances) as workflows,
  (SELECT COUNT(*) FROM task_events_raw) as task_events,
  (SELECT COUNT(*) FROM task_instances) as tasks;
```

5. **Log Rotation Rate**
```bash
# Check how often logs rotate (if too fast, FluentBit may fall behind)
ls -lh /var/log/containers/*_workflows_*.log*
```

### Alerts

**Critical:**
- FluentBit pod not running
- PostgreSQL connection failures > 1 min
- Buffer overflow detected

**Warning:**
- Retry rate > 10/min
- Buffer usage > 80%
- Log rotation faster than 1 file/min

## Event Loss Detection

### Verify Event Completeness

#### 1. Check for Gaps in Instance IDs
```sql
-- If workflow IDs are sequential (UUIDs are random)
SELECT id FROM workflow_instances ORDER BY start;
```

#### 2. Compare Task Count to Workflow Definition
```sql
-- Workflow "simple-set" should have exactly 2 tasks
SELECT 
  wi.id,
  wi.name,
  COUNT(ti.task_execution_id) as task_count
FROM workflow_instances wi
LEFT JOIN task_instances ti ON wi.id = ti.instance_id
WHERE wi.name = 'simple-set'
GROUP BY wi.id, wi.name
HAVING COUNT(ti.task_execution_id) != 2;
```

#### 3. Check for Incomplete Workflows
```sql
-- Workflows that started but never completed/faulted
SELECT id, name, status, start, "end"
FROM workflow_instances
WHERE status = 'RUNNING' 
  AND start < NOW() - INTERVAL '1 hour';
```

#### 4. Correlate with Application Metrics
```bash
# If app exposes metrics for workflows started
curl http://workflow-app:8080/q/metrics | grep workflow_started_total

# Compare to database count
SELECT COUNT(*) FROM workflow_instances;
```

## Disaster Recovery

### If Events Are Lost

1. **Check FluentBit logs** for errors
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=1000 > fluentbit.log
grep -i "error\|fail\|drop\|overflow" fluentbit.log
```

2. **Check if events exist in /var/log/containers/**
```bash
kubectl exec -n logging <fluentbit-pod> -- \
  grep "eventType" /var/log/containers/*_workflows_*.log | tail -100
```

3. **Manual replay from container logs** (if still available)
```bash
# Extract missed events
kubectl exec -n logging <fluentbit-pod> -- \
  grep "eventType.*workflow.started" /var/log/containers/pod.log.2 > missed_events.json

# Insert manually into workflow_events_raw
while IFS= read -r event; do
  echo "INSERT INTO workflow_events_raw (tag, time, data) VALUES ('workflow.instance.started', NOW(), '${event}');" | \
    kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex
done < missed_events.json
```

4. **Trigger workflow re-execution** (if idempotent)
```bash
# Re-execute failed workflows
curl -X POST http://workflow-app:8080/workflows/{id}/retry
```

## Conclusion

**Event loss is possible** in stdout-based log collection, but can be minimized to < 0.01% with proper configuration and monitoring.

**For critical workflows**, consider:
- Dual-write (app → PostgreSQL directly)
- Kafka buffering (MODE 3)
- File-based logging with persistent volumes

**For most use cases**, the recommended FluentBit configuration provides sufficient reliability with good operational simplicity.
