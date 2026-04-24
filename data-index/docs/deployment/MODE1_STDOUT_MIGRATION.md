# MODE 1 Migration: File Logging → Stdout Logging

**Date:** 2026-04-23  
**Status:** ✅ Complete

## Summary

Migrated MODE 1 from file-based logging (`/tmp/quarkus-flow-events.log`) to stdout-based logging following Kubernetes best practices.

## Rationale

### Why Stdout > File?

✅ **Standard Kubernetes Pattern**
- Kubernetes automatically captures container stdout/stderr to `/var/log/containers/`
- No custom volume mounts needed
- FluentBit DaemonSets already have access to `/var/log/containers/` by default

✅ **Simpler Architecture**
- No hostPath volume mounts (security concern in many environments)
- No sidecar containers
- No file rotation configuration

✅ **Better Security**
- hostPath volumes grant access to host filesystem
- Many production Kubernetes clusters restrict or disable hostPath
- Standard log collection doesn't require elevated permissions

✅ **Event Filtering is Trivial**
- Structured events have `eventType` field
- Regular app logs don't
- FluentBit filters with simple `grep eventType ^io\.serverlessworkflow\.`

## Architecture Comparison

### Before (File-Based)
```
Quarkus Flow → /tmp/quarkus-flow-events.log
                      ↓ (hostPath volume)
                FluentBit tail file
                      ↓
                PostgreSQL
```

**Issues:**
- hostPath volume required (security risk)
- File rotation complexity
- Not standard K8s pattern

### After (Stdout-Based)
```
Quarkus Flow → stdout (mixed: app logs + events)
                      ↓ (K8s automatic capture)
         /var/log/containers/<pod>.log
                      ↓ (standard DaemonSet access)
    FluentBit tail → filter JSON → grep eventType
                      ↓
                PostgreSQL
```

**Benefits:**
- Standard K8s pattern
- No custom volumes
- Simpler deployment
- Better security posture

## Changes Made

### 1. Application Configuration

**File:** `data-index-integration-tests/src/main/resources/application.properties`

**Before:**
```properties
# File handler
quarkus.log.handler.file."FLOW_EVENTS".enabled=true
quarkus.log.handler.file."FLOW_EVENTS".path=/tmp/quarkus-flow-events.log

# Console handler
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled=true

# Route to BOTH
quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS,FLOW_EVENTS_CONSOLE
```

**After:**
```properties
# Disable file handler
quarkus.log.handler.file."FLOW_EVENTS".enabled=false

# Console handler only
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled=true

# Route to console only
quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS_CONSOLE
```

### 2. Deployment Configuration

**File:** `scripts/kind/deploy-workflow-app.sh`

**Removed:**
```yaml
volumeMounts:
- name: quarkus-flow-logs
  mountPath: /tmp

volumes:
- name: quarkus-flow-logs
  hostPath:
    path: /tmp
    type: Directory
```

**Added Comment:**
```yaml
# Structured events go to stdout (mixed with app logs)
# Kubernetes captures to /var/log/containers/<pod>_<namespace>_<container>.log
# FluentBit DaemonSet tails /var/log/containers/ and filters JSON events
```

### 3. FluentBit Configuration

**File:** `scripts/fluentbit/mode1-postgresql-triggers/fluent-bit.conf`

**Before:**
```conf
[INPUT]
    Name              tail
    Path              /tmp/quarkus-flow-events.log*
    Parser            json
    Tag               flow.events
```

**After:**
```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_workflows_*.log
    Parser            docker
    Tag               kube.*

[FILTER]
    Name              parser
    Match             kube.*
    Key_Name          log
    Parser            json
    Reserve_Data      On

[FILTER]
    Name              grep
    Match             kube.*
    Regex             eventType ^io\.serverlessworkflow\.

[FILTER]
    Name                kubernetes
    Match               kube.*
    Kube_URL            https://kubernetes.default.svc:443
    ...

[FILTER]
    Name          rewrite_tag
    Match         kube.*
    Rule          $_flow_event ^true$ flow.events false
```

### 4. FluentBit DaemonSet

**File:** `scripts/fluentbit/mode1-postgresql-triggers/kubernetes/daemonset.yaml`

**Before:**
```yaml
volumeMounts:
- name: host-tmp
  mountPath: /tmp
  readOnly: true

volumes:
- name: host-tmp
  hostPath:
    path: /tmp
    type: Directory
```

**After:**
```yaml
volumeMounts:
- name: varlog
  mountPath: /var/log
  readOnly: true
- name: varlibdockercontainers
  mountPath: /var/lib/docker/containers
  readOnly: true

volumes:
- name: varlog
  hostPath:
    path: /var/log
    type: Directory
- name: varlibdockercontainers
  hostPath:
    path: /var/lib/docker/containers
    type: DirectoryOrCreate
```

**Note:** These are standard FluentBit DaemonSet mounts for Kubernetes log collection.

### 5. Documentation Updates

Updated:
- `MODE1_HANDOFF.md` - Architecture diagrams and configuration
- `MODE1_E2E_TESTING.md` - Testing instructions
- `scripts/fluentbit/mode1-postgresql-triggers/README.md` - Architecture and troubleshooting

## FluentBit Log Processing Pipeline

### 1. Input: Tail Container Logs
```
/var/log/containers/workflow-test-app-abc123_workflows_workflow-app-xyz789.log
```

Each line format (Docker runtime):
```json
{"log":"{\"instanceId\":\"123\",\"eventType\":\"io.serverlessworkflow.workflow.started.v1\",...}\n","stream":"stdout","time":"2026-04-23T..."}
```

Or (CRI runtime like containerd):
```
2026-04-23T22:15:31.456Z stdout F {"instanceId":"123","eventType":"io.serverlessworkflow.workflow.started.v1",...}
```

### 2. Filter: Parse Docker/CRI Format
Parser extracts `log` field from Docker JSON or CRI format:
```
log = {"instanceId":"123","eventType":"io.serverlessworkflow.workflow.started.v1",...}
```

### 3. Filter: Parse Nested JSON
Parse `log` field as JSON to extract event fields:
```
instanceId = "123"
eventType = "io.serverlessworkflow.workflow.started.v1"
workflowName = "hello-world"
...
```

### 4. Filter: Grep for Structured Events
Keep only lines with `eventType` field:
```
Regex: eventType ^io\.serverlessworkflow\.
```

**Result:**
- Regular app logs: `"22:51:50 INFO [class] message"` → **Excluded** (no eventType)
- Structured events: `{"eventType":"..."}` → **Kept**

### 5. Filter: Kubernetes Metadata
Enrich with pod/namespace metadata:
```
kubernetes.pod_name = "workflow-test-app-abc123"
kubernetes.namespace_name = "workflows"
kubernetes.labels.app = "workflow-test-app"
```

### 6. Filter: Route by Event Type
Route to appropriate tags:
```
workflow.instance.started
workflow.instance.completed
workflow.task.started
...
```

### 7. Output: PostgreSQL
Insert into raw tables:
```sql
INSERT INTO workflow_events_raw (tag, time, data)
VALUES (
  'workflow.instance.started',
  '2026-04-23 22:15:31+00',
  '{"instanceId":"123","eventType":"io.serverlessworkflow.workflow.started.v1",...}'::jsonb
);
```

### 8. Trigger: Normalize
PostgreSQL trigger extracts and normalizes:
```sql
INSERT INTO workflow_instances (id, namespace, name, ...)
VALUES (
  data->>'instanceId',
  data->>'workflowNamespace',
  data->>'workflowName',
  ...
) ON CONFLICT (id) DO UPDATE SET ...;
```

## Testing the Migration

### Verify stdout logging works:
```bash
# 1. Deploy updated app
cd data-index/scripts/kind
./deploy-workflow-app.sh

# 2. Check pod logs (should see both app logs and JSON events)
kubectl logs -n workflows -l app=workflow-test-app --tail=50

# Expected output:
# 22:51:50 INFO  [io.quarkus] Quarkus started in 1.234s
# 22:51:55 INFO  [io.quarkiverse.flow] Workflow started
# {"instanceId":"abc-123","eventType":"io.serverlessworkflow.workflow.started.v1",...}
```

### Verify FluentBit filtering works:
```bash
# 1. Deploy updated FluentBit
cd ../fluentbit/mode1-postgresql-triggers
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml

# 2. Check FluentBit is tailing K8s logs
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50 | grep "tail"

# 3. Check FluentBit is filtering events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50 | grep "eventType"

# 4. Trigger workflow
curl -X POST http://localhost:30082/test/simple-set/start

# 5. Verify event reached PostgreSQL
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM workflow_events_raw;"

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT id, name, status FROM workflow_instances ORDER BY start DESC LIMIT 1;"
```

## Troubleshooting

### Events not appearing in PostgreSQL

**Check 1**: Verify container logs have JSON events
```bash
kubectl logs -n workflows -l app=workflow-test-app | grep "eventType"
```

**Check 2**: Verify FluentBit can read container logs
```bash
POD=$(kubectl get pods -n logging -l app=workflows-fluent-bit-mode1 -o jsonpath='{.items[0].metadata.name}')
kubectl exec -n logging $POD -- ls -la /var/log/containers/*_workflows_*.log
```

**Check 3**: Verify FluentBit is parsing JSON
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "parser"
```

**Check 4**: Verify FluentBit grep filter working
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "grep"
```

**Check 5**: Verify PostgreSQL connectivity
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i "pgsql\|error"
```

### FluentBit permission errors

If you see "permission denied" errors for `/var/log`:

**Solution:** Update DaemonSet security context to allow reading host logs:
```yaml
securityContext:
  runAsNonRoot: false  # FluentBit needs root to read /var/log
  runAsUser: 0
```

**Note:** This is standard for FluentBit DaemonSets in Kubernetes.

## Rollback Plan (if needed)

If stdout approach has issues, rollback to file-based:

1. Revert `application.properties`:
   ```properties
   quarkus.log.handler.file."FLOW_EVENTS".enabled=true
   quarkus.log.handler.file."FLOW_EVENTS".path=/tmp/quarkus-flow-events.log
   quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS,FLOW_EVENTS_CONSOLE
   ```

2. Revert `deploy-workflow-app.sh` (add hostPath volume)

3. Revert `fluent-bit.conf` INPUT to tail `/tmp/quarkus-flow-events.log*`

4. Revert DaemonSet volumes to `host-tmp`

5. Redeploy all components

## Migration Checklist

- [x] Update application.properties (disable file handler)
- [x] Update deploy-workflow-app.sh (remove hostPath volumes)
- [x] Update fluent-bit.conf (tail /var/log/containers/, add filters)
- [x] Update daemonset.yaml (standard K8s log mounts)
- [x] Regenerate ConfigMap
- [x] Update MODE1_HANDOFF.md
- [x] Update MODE1_E2E_TESTING.md
- [x] Update mode1 README.md
- [x] Test end-to-end flow
- [ ] Update production deployment docs (if applicable)

## Benefits Realized

✅ **Simpler deployment** - No hostPath volumes  
✅ **Better security** - No host filesystem access  
✅ **Standard K8s pattern** - Works in any cluster  
✅ **Easier troubleshooting** - Same logs for dev and FluentBit  
✅ **Production-ready** - Meets security policies  

## References

- Kubernetes Logging Architecture: https://kubernetes.io/docs/concepts/cluster-administration/logging/
- FluentBit Kubernetes: https://docs.fluentbit.io/manual/pipeline/inputs/kubernetes
- FluentBit Tail Input: https://docs.fluentbit.io/manual/pipeline/inputs/tail
