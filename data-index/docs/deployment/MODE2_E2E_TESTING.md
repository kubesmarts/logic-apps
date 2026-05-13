# MODE 2 (Elasticsearch) End-to-End Testing Guide

**Status:** Production Ready  
**Last Updated:** 2026-04-29

---

## Overview

Complete guide for testing MODE 2 Elasticsearch backend in a local KIND cluster. Tests the full pipeline:

```
Quarkus Flow → stdout → K8s logs → FluentBit → Elasticsearch → ES Transform → GraphQL
```

---

## Quick Start (Automated)

The `test-mode2-e2e.sh` script handles everything automatically:

```bash
cd data-index/scripts/kind
./test-mode2-e2e.sh
```

**What it does:**
1. Creates KIND cluster (if needed)
2. Installs Elasticsearch (via ECK operator)
3. Deploys Data Index service (Elasticsearch mode)
4. Waits for schema initialization (ILM, templates, transforms)
5. Deploys FluentBit (Elasticsearch output)
6. Deploys test workflow application
7. Verifies event flow through pipeline
8. Tests GraphQL API
9. Verifies idempotency

**Expected output:**
```
[INFO] ==========================================
[INFO] MODE 2 E2E Test Complete!
[INFO] ==========================================

Pipeline Flow:
  Quarkus Flow → stdout → FluentBit → Elasticsearch → ES Transform → GraphQL

Verification Results:
  ✓ Elasticsearch cluster running
  ✓ Data Index service deployed
  ✓ Schema initialized (ILM, templates, transforms)
  ✓ FluentBit collecting events
  ✓ Raw events in Elasticsearch
  ✓ ES Transform normalizing events
  ✓ GraphQL API responding
  ✓ Idempotency working

[INFO] ✓ All tests passed!
```

---

## Manual Testing Steps

### Prerequisites

**Required tools:**
- Docker Desktop (with Kubernetes enabled)
- kubectl
- Helm 3
- KIND
- jq (for JSON parsing)

**Check versions:**
```bash
docker version
kubectl version --client
helm version
kind version
jq --version
```

---

### Step 1: Create KIND Cluster

```bash
cd data-index/scripts/kind
./setup-cluster.sh
```

**Verify:**
```bash
kind get clusters
# Should show: data-index-test

kubectl config current-context
# Should show: kind-data-index-test
```

---

### Step 2: Install Elasticsearch

**Install ECK operator:**
```bash
kubectl create -f https://download.elastic.co/downloads/eck/2.12.1/crds.yaml
kubectl apply -f https://download.elastic.co/downloads/eck/2.12.1/operator.yaml
```

**Wait for operator:**
```bash
kubectl wait --namespace elastic-system \
  --for=condition=ready pod \
  --selector=control-plane=elastic-operator \
  --timeout=300s
```

**Create Elasticsearch cluster:**
```bash
kubectl apply -f - <<EOF
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: data-index-es
  namespace: elasticsearch
spec:
  version: 8.11.1
  nodeSets:
  - name: default
    count: 1
    config:
      node.store.allow_mmap: false
      xpack.security.enabled: false
      xpack.security.http.ssl.enabled: false
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          resources:
            requests:
              memory: 1Gi
              cpu: 500m
            limits:
              memory: 2Gi
              cpu: 2000m
    volumeClaimTemplates:
    - metadata:
        name: elasticsearch-data
      spec:
        accessModes:
        - ReadWriteOnce
        resources:
          requests:
            storage: 2Gi
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-es-http-nodeport
  namespace: elasticsearch
spec:
  type: NodePort
  selector:
    elasticsearch.k8s.elastic.co/cluster-name: data-index-es
  ports:
  - port: 9200
    targetPort: 9200
    nodePort: 30920
    protocol: TCP
    name: http
EOF
```

**Wait for Elasticsearch (this may take 5-10 minutes):**
```bash
kubectl wait --namespace elasticsearch \
  --for=jsonpath='{.status.health}'=green \
  elasticsearch/data-index-es \
  --timeout=600s
```

**Verify:**
```bash
curl http://localhost:30920
# Should return Elasticsearch cluster info

curl http://localhost:30920/_cluster/health?pretty
# Should show: "status" : "green"
```

---

### Step 3: Deploy Data Index Service

```bash
cd data-index/scripts/kind
./deploy-data-index.sh elasticsearch
```

**What this does:**
1. Builds container image: `kubesmarts/data-index-service-elasticsearch:999-SNAPSHOT`
2. Loads image to KIND cluster
3. Creates namespace `data-index`
4. Deploys service with Elasticsearch configuration
5. Exposes on NodePort 30080

**Verify deployment:**
```bash
kubectl get pods -n data-index
# Should show: data-index-service-xxx Running

kubectl logs -n data-index -l app=data-index-service --tail=50
# Should show: "Quarkus started in X.XXXs"
```

**Verify schema initialization:**
```bash
# Check ILM policy
curl http://localhost:30920/_ilm/policy/data-index-events-retention?pretty

# Check index templates
curl http://localhost:30920/_index_template/workflow-events?pretty
curl http://localhost:30920/_index_template/workflow-instances?pretty

# Check transforms
curl http://localhost:30920/_transform/workflow-instances-transform?pretty
curl http://localhost:30920/_transform/task-executions-transform?pretty
```

---

### Step 4: Deploy FluentBit

```bash
cd data-index/scripts/fluentbit/elasticsearch
./deploy.sh
```

**What this does:**
1. Generates ConfigMap from `fluent-bit.conf` and `parsers.conf`
2. Creates namespace `logging`
3. Deploys FluentBit DaemonSet with Elasticsearch output
4. Configures routing: workflow events → workflow-events, task events → task-events

**Verify:**
```bash
kubectl get pods -n logging
# Should show: workflows-fluent-bit-mode2-xxx Running

kubectl logs -n logging -l app=workflows-fluent-bit-mode2 --tail=20
# Should show: "output:es:es.0" connected
```

---

### Step 5: Deploy Test Workflow Application

```bash
cd data-index/scripts/kind
./deploy-workflow-app.sh
```

**What this does:**
1. Builds test workflow application
2. Loads image to KIND
3. Deploys to namespace `workflows`
4. Application emits structured logging events to stdout

**Verify:**
```bash
kubectl get pods -n workflows
# Should show: workflow-test-app-xxx Running

kubectl logs -n workflows -l app=workflow-test-app --tail=20
# Should show JSON events:
# {"eventType":"io.serverlessworkflow.workflow.instance.started", ...}
```

---

### Step 6: Verify Event Flow

**Check raw events in Elasticsearch:**
```bash
# Count raw workflow events
curl -s http://localhost:30920/workflow-events/_count | jq

# View raw events
curl -s http://localhost:30920/workflow-events/_search?pretty

# Sample raw event structure:
{
  "event_type": "io.serverlessworkflow.workflow.instance.started",
  "event_time": "2026-04-29T10:30:45.123Z",
  "instance_id": "abc-123",
  "workflow_name": "test-workflow",
  "status": "RUNNING",
  "input": { "customerId": "customer-123" }
}
```

**Check ES Transform status:**
```bash
curl http://localhost:30920/_transform/workflow-instances-transform/_stats?pretty

# Should show:
# "state": "started"
# "documents_processed": > 0
```

**Check normalized instances:**
```bash
# Count normalized workflow instances
curl -s http://localhost:30920/workflow-instances/_count | jq

# View normalized instances
curl -s http://localhost:30920/workflow-instances/_search?pretty

# Sample normalized instance:
{
  "id": "abc-123",
  "name": "test-workflow",
  "version": "1.0.0",
  "status": "COMPLETED",
  "start": "2026-04-29T10:30:45.123Z",
  "end": "2026-04-29T10:30:46.456Z",
  "input": { "customerId": "customer-123" },
  "output": { "result": "success" }
}
```

---

### Step 7: Test GraphQL API

**Introspection query:**
```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __schema { queryType { name } } }"}' | jq
```

**Get all workflow instances:**
```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name version status start end } }"}' | jq
```

**Get workflow by ID:**
```bash
# Replace <WORKFLOW_ID> with actual ID from previous query
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstanceById(id: \"<WORKFLOW_ID>\") { id name status input output } }"}' | jq
```

**Filter workflows by status:**
```bash
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(where: { status: { eq: COMPLETED } }) { id name status } }"}' | jq
```

**GraphQL UI (browser):**
```
http://localhost:30080/q/graphql-ui
```

---

### Step 8: Test Idempotency

**Restart workflow app (generates duplicate events):**
```bash
kubectl rollout restart deployment/workflow-test-app -n workflows
kubectl wait --namespace workflows \
  --for=condition=available deployment/workflow-test-app \
  --timeout=120s
```

**Wait for events to process:**
```bash
sleep 20
```

**Verify no duplicate instances:**
```bash
# Count instances before and after should be the same
curl -X POST http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id } }"}' | jq '.data.getWorkflowInstances | length'
```

**Why this works:**
- ES Transform groups by `instance_id`
- Field-level idempotency rules prevent overwrites
- Same instance ID → same document → no duplicates

---

### Step 9: Test Out-of-Order Events

**Simulate out-of-order events manually:**
```bash
# Insert COMPLETED event
curl -X POST http://localhost:30920/workflow-events/_doc \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "io.serverlessworkflow.workflow.instance.completed",
    "event_time": "2026-04-29T10:30:50Z",
    "instance_id": "test-out-of-order",
    "workflow_name": "test-workflow",
    "status": "COMPLETED",
    "end": "2026-04-29T10:30:50Z"
  }'

# Wait for transform
sleep 3

# Insert STARTED event (arrives late)
curl -X POST http://localhost:30920/workflow-events/_doc \
  -H "Content-Type: application/json" \
  -d '{
    "event_type": "io.serverlessworkflow.workflow.instance.started",
    "event_time": "2026-04-29T10:30:45Z",
    "instance_id": "test-out-of-order",
    "workflow_name": "test-workflow",
    "status": "RUNNING",
    "start": "2026-04-29T10:30:45Z",
    "input": { "test": "out-of-order" }
  }'

# Wait for transform
sleep 3

# Check final status (should be COMPLETED, not RUNNING)
curl -s http://localhost:30920/workflow-instances/_doc/test-out-of-order | jq '.{status: ._source.status, start: ._source.start, end: ._source.end, input: ._source.input}'
```

**Expected result:**
```json
{
  "status": "COMPLETED",
  "start": "2026-04-29T10:30:45Z",
  "end": "2026-04-29T10:30:50Z",
  "input": { "test": "out-of-order" }
}
```

**Why this works:**
- Immutable fields (`start`, `input`): first value wins
- Terminal fields (`end`): last non-null wins
- Status: terminal state (COMPLETED) overrides transient (RUNNING)

---

## Troubleshooting

### No raw events in Elasticsearch

**Symptoms:**
```bash
curl http://localhost:30920/workflow-events/_count
# Returns: {"count": 0}
```

**Diagnosis:**
```bash
# Check FluentBit logs
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 --tail=50

# Check workflow app logs
kubectl logs -n workflows -l app=workflow-test-app --tail=50

# Check FluentBit connectivity
kubectl exec -n logging -it <fluentbit-pod> -- curl http://data-index-es-http.elasticsearch:9200
```

**Common causes:**
1. Workflow app not emitting events → Check app logs for JSON events
2. FluentBit can't reach Elasticsearch → Check service name/port in ConfigMap
3. CRI parser not working → Check FluentBit logs for "parser" errors

---

### Raw events exist but no normalized instances

**Symptoms:**
```bash
curl http://localhost:30920/workflow-events/_count
# Returns: {"count": 10}

curl http://localhost:30920/workflow-instances/_count
# Returns: {"count": 0}
```

**Diagnosis:**
```bash
# Check transform status
curl http://localhost:30920/_transform/workflow-instances-transform/_stats?pretty

# Should show:
# "state": "started"
# "documents_processed": > 0

# If state is "stopped", start it:
curl -X POST http://localhost:30920/_transform/workflow-instances-transform/_start
```

**Common causes:**
1. Transform not started → Start it manually
2. Transform query filters out all events → Check smart filtering query
3. Transform delay too high → Wait longer (default 5m delay)

---

### GraphQL returns empty results

**Symptoms:**
```bash
curl -X POST http://localhost:30080/graphql \
  -d '{"query":"{ getWorkflowInstances { id } }"}' | jq
# Returns: {"data": {"getWorkflowInstances": []}}
```

**Diagnosis:**
```bash
# Check Data Index logs
kubectl logs -n data-index -l app=data-index-service --tail=50

# Check Elasticsearch connection from Data Index pod
kubectl exec -n data-index -it <data-index-pod> -- curl http://data-index-es-http.elasticsearch:9200

# Verify instances exist in Elasticsearch
curl http://localhost:30920/workflow-instances/_search?pretty
```

**Common causes:**
1. Data Index can't reach Elasticsearch → Check service configuration
2. Wrong index name in Data Index config → Check `application-elasticsearch.properties`
3. Elasticsearch query fails → Check Data Index logs for errors

---

### Idempotency test fails (count increases)

**Symptoms:**
```bash
# Before restart: 5 instances
# After restart: 10 instances (should still be 5)
```

**Diagnosis:**
```bash
# Check if same instance IDs have multiple documents
curl -s http://localhost:30920/workflow-instances/_search?pretty | jq '.hits.hits[] | {id: ._id, instance_id: ._source.id}'

# Check transform configuration
curl http://localhost:30920/_transform/workflow-instances-transform?pretty
# Verify: "group_by": { "id": { "terms": { "field": "instance_id" } } }
```

**Common causes:**
1. Transform not grouping by `instance_id` → Fix transform definition
2. Different `instance_id` values for same workflow → Check Quarkus Flow event generation
3. Transform not using field-level idempotency → Check scripted_metric aggregations

---

## Performance Testing

### Load Test Setup

```bash
# Deploy multiple workflow apps
kubectl scale deployment/workflow-test-app -n workflows --replicas=5

# Wait for all pods
kubectl wait --namespace workflows \
  --for=condition=ready pod \
  --selector=app=workflow-test-app \
  --timeout=120s

# Monitor event rate
watch -n 1 'curl -s http://localhost:30920/workflow-events/_count | jq'
```

### Monitor Transform Performance

```bash
# Watch transform stats
watch -n 1 'curl -s http://localhost:30920/_transform/workflow-instances-transform/_stats | jq ".transforms[0] | {state, processed: .stats.documents_processed, indexed: .stats.documents_indexed}"'
```

### Expected Performance

**Small load (< 100 workflows/day):**
- Raw event → normalized: ~1-2 seconds
- Transform frequency: 1s
- Query latency: < 100ms

**Medium load (100-10K workflows/day):**
- Raw event → normalized: ~1-3 seconds
- Transform frequency: 1s (smart filtering keeps load constant)
- Query latency: < 200ms

**Large load (> 10K workflows/day):**
- Raw event → normalized: ~2-5 seconds
- Transform frequency: 1s
- Query latency: < 500ms
- Consider scaling Elasticsearch cluster (3+ nodes)

---

## Cleanup

**Delete cluster:**
```bash
kind delete cluster --name data-index-test
```

**Delete only Elasticsearch:**
```bash
kubectl delete namespace elasticsearch
```

**Delete only Data Index:**
```bash
kubectl delete namespace data-index
```

**Delete only FluentBit:**
```bash
cd data-index/scripts/fluentbit/elasticsearch
./deploy.sh delete
```

---

## Next Steps

1. **Production deployment:** See `deployment/elasticsearch.adoc` in user docs
2. **Performance tuning:** Adjust transform frequency, ES cluster size
3. **Multi-namespace:** Deploy workflow apps to different namespaces
4. **Scaling:** Add more Elasticsearch nodes for horizontal scaling

---

## References

- **Architecture:** `data-index-docs/modules/ROOT/pages/architecture/elasticsearch-mode.adoc`
- **Deployment:** `data-index-docs/modules/ROOT/pages/deployment/elasticsearch.adoc`
- **FluentBit Config:** `data-index-docs/modules/ROOT/pages/deployment/fluentbit-config.adoc`
- **ES Transform Details:** `data-index/data-index-storage/data-index-storage-elasticsearch/README.md`
- **MODE 1 Comparison:** `docs/deployment/MODE1_E2E_TESTING.md`
