# Elasticsearch Mode (MODE 2)

Transform-based event normalization - FluentBit → Elasticsearch → Transform → Normalized Indices

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Quarkus Flow Workflow Pods                                         │
│  ┌──────────────────────────────────────────────────────────┐       │
│  │ Quarkus Flow 0.9.0+                                      │       │
│  │ - Structured logging enabled                             │       │
│  │ - Writes to stdout (mixed with app logs)                 │       │
│  └────────────────────────┬─────────────────────────────────┘       │
└───────────────────────────┼─────────────────────────────────────────┘
                            │
                            │  (stdout: App logs + JSON events)
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Kubernetes Node                                                    │
│  /var/log/containers/<pod>_workflows_<container>.log                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │ {"log":"22:51:50 INFO ...\n","stream":"stdout",...}       │    │
│  │ {"log":"{\"instanceId\":\"...\",\"eventType\":...}","...}  │    │
│  └────────────────────────────────────────────────────────────┘    │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  FluentBit DaemonSet                                                │
│  ┌────────────────────────────────────────────────────────────┐    │
│  │  INPUT: tail /var/log/containers/*_workflows_*.log         │    │
│  │  FILTER: parse CRI format → parse nested JSON             │    │
│  │  FILTER: grep eventType (keep only structured events)      │    │
│  │  FILTER: kubernetes metadata enrichment                    │    │
│  │  FILTER: rewrite_tag (route by eventType)                  │    │
│  │  OUTPUT: Elasticsearch es plugin                           │    │
│  │    - workflow.instance → workflow-instance-events-raw-*    │    │
│  │    - workflow.task → task-execution-events-raw-*           │    │
│  └────────────────────────────────────────────────────────────┘    │
└───────┬──────────────────────────────────────────────────────────────┘
        │
        │  (HTTP/JSON to Elasticsearch)
        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Elasticsearch Cluster                                              │
│                                                                     │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ Raw Event Indices (Daily Rollover)                       │      │
│  │ - workflow-instance-events-raw-2026.04.29                │      │
│  │ - task-execution-events-raw-2026.04.29                   │      │
│  └────────────────────┬─────────────────────────────────────┘      │
│                       │                                             │
│                       │  Elasticsearch Transform (Continuous)       │
│                       ▼                                             │
│  ┌──────────────────────────────────────────────────────────┐      │
│  │ Normalized Indices (Transform Destination)               │      │
│  │ - workflow-instances                                     │      │
│  │ - task-executions                                        │      │
│  └────────────────────┬─────────────────────────────────────┘      │
└───────────────────────┼──────────────────────────────────────────────┘
                        │
                        │  (Elasticsearch Java Client queries)
                        ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Data Index GraphQL API                                             │
│  - getWorkflowInstance(id)                                          │
│  - getWorkflowInstances(filter, orderBy, limit, offset)             │
│  - getTaskExecutions(filter, orderBy, limit, offset)                │
└─────────────────────────────────────────────────────────────────────┘
```

## Key Features

### Real-Time Event Capture
FluentBit DaemonSet tails Kubernetes container logs and sends structured events to Elasticsearch immediately.

### Transform-Based Normalization
Elasticsearch Transform continuously aggregates raw events into normalized indices:
- Out-of-order event handling via latest() and scripted_metric aggregations
- No polling required - Transform runs continuously
- No external processing service needed

### Date-Based Raw Indices
Raw events stored in daily indices:
- `workflow-instance-events-raw-YYYY.MM.DD`
- `task-execution-events-raw-YYYY.MM.DD`
- ILM policy manages retention (default: 7 days)

### Normalized Indices
Transform maintains up-to-date normalized indices:
- `workflow-instances` - Current state of each workflow
- `task-executions` - Current state of each task execution
- Data Index queries these indices via Elasticsearch Java Client

## FluentBit Configuration

### Event Routing
```
kube.* → grep eventType → flow.events
  ├─→ workflow.instance → workflow-instance-events-raw-YYYY.MM.DD
  └─→ workflow.task     → task-execution-events-raw-YYYY.MM.DD
```

### Elasticsearch Output
FluentBit uses the `es` output plugin:
- **Logstash Format**: Daily index rollover (`YYYY.MM.DD`)
- **Retry Logic**: 5 retries on failure
- **TLS Support**: Configurable via environment variables
- **Type**: `_doc` (Elasticsearch 8.x standard)

## Configuration Files

### `fluent-bit.conf`
Main configuration with:
- **INPUT**: Tail `/var/log/containers/*_workflows_*.log`
- **FILTER**: Parse CRI format, extract JSON events, grep by `eventType`
- **FILTER**: Kubernetes metadata enrichment
- **FILTER**: Route by eventType (workflow vs task)
- **OUTPUT**: Elasticsearch with daily index rollover

### `parsers.conf`
Parser definitions:
- **cri**: CRI container log format (containerd/CRI-O)
- **docker**: Docker container log format (legacy)
- **json**: Nested JSON event parser

### `kubernetes/daemonset.yaml`
Kubernetes DaemonSet deployment:
- Runs on every node
- Mounts `/var/log/containers` (hostPath)
- Service account with RBAC for pod metadata
- Health checks and resource limits

### `kubernetes/configmap.yaml`
Auto-generated ConfigMap with FluentBit configuration (regenerate via `../generate-configmap.sh`)

## Environment Variables

Set these in the DaemonSet (`kubernetes/daemonset.yaml`):

```yaml
env:
- name: WORKFLOW_NAMESPACE
  value: "workflows"
- name: ELASTICSEARCH_HOST
  value: "elasticsearch.elasticsearch.svc.cluster.local"
- name: ELASTICSEARCH_PORT
  value: "9200"
- name: ELASTICSEARCH_TLS
  value: "Off"  # Set to "On" for HTTPS
- name: ELASTICSEARCH_TLS_VERIFY
  value: "Off"  # Set to "On" to verify certificates
```

For production with TLS:
```yaml
- name: ELASTICSEARCH_TLS
  value: "On"
- name: ELASTICSEARCH_TLS_VERIFY
  value: "On"
```

## Deployment

### Prerequisites

1. **Elasticsearch cluster** running (8.x)
2. **Kubernetes cluster** with workflow pods
3. **Elasticsearch schema initialized** (indices, transforms, ILM)
   - See `data-index-storage-elasticsearch/README.md`

### Deploy FluentBit

```bash
# Option 1: Quick deploy (uses existing ConfigMap)
./deploy.sh

# Option 2: Regenerate ConfigMap from source files
./deploy.sh regenerate

# Option 3: Manual steps
kubectl create namespace logging
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml
```

### Verify Deployment

```bash
# Check pods are running
kubectl get pods -n logging -l app=workflows-fluent-bit-mode2

# View logs
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 --tail=50 -f

# Check Elasticsearch connectivity
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 | grep -i elasticsearch
```

### Verify Event Ingestion

```bash
# 1. Trigger a test workflow
kubectl port-forward -n workflows svc/workflow-test-app 8080:8080 &
curl -X POST http://localhost:8080/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name": "Test"}'

# 2. Check FluentBit captured the events
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 | grep "workflow.started"

# 3. Verify raw events in Elasticsearch
kubectl port-forward -n elasticsearch svc/elasticsearch 9200:9200 &

# Count raw workflow events
curl -s http://localhost:9200/workflow-instance-events-raw-*/_count | jq

# View recent workflow events
curl -s "http://localhost:9200/workflow-instance-events-raw-*/_search?size=5&sort=@timestamp:desc" | jq '.hits.hits[]._source'

# Count raw task events
curl -s http://localhost:9200/task-execution-events-raw-*/_count | jq

# 4. Verify Transform is running
curl -s http://localhost:9200/_transform/workflow-instance-transform/_stats | jq '.transforms[0].state'

# Expected: "started" or "indexing"

# 5. Verify normalized indices
curl -s http://localhost:9200/workflow-instances/_count | jq
curl -s http://localhost:9200/task-executions/_count | jq

# View normalized workflow instances
curl -s "http://localhost:9200/workflow-instances/_search?size=5&sort=start:desc" | jq '.hits.hits[]._source'
```

## Monitoring

### FluentBit Metrics

FluentBit exposes Prometheus metrics on port 2020:

```bash
# Check metrics endpoint
kubectl port-forward -n logging <pod-name> 2020:2020 &
curl http://localhost:2020/api/v1/metrics/prometheus
```

Key metrics:
- `fluentbit_input_records_total` - Total records read
- `fluentbit_output_records_total` - Total records sent to Elasticsearch
- `fluentbit_output_errors_total` - Elasticsearch output errors
- `fluentbit_output_retries_total` - Retry attempts

### FluentBit Logs

```bash
# Follow logs
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 -f

# Check for errors
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 | grep -i error

# Check Elasticsearch connectivity
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 | grep "es.0"
```

### Elasticsearch Monitoring

```bash
# Check raw event counts by day
curl -s "http://localhost:9200/_cat/indices/workflow-instance-events-raw-*?v&s=index"
curl -s "http://localhost:9200/_cat/indices/task-execution-events-raw-*?v&s=index"

# Check Transform stats
curl -s http://localhost:9200/_transform/_stats | jq '.transforms[] | {id, state, documents_processed, documents_indexed}'

# Check ILM policy status
curl -s http://localhost:9200/workflow-instance-events-raw-*/_ilm/explain | jq
```

## Troubleshooting

### No events in Elasticsearch

**Check 1**: FluentBit is running
```bash
kubectl get pods -n logging -l app=workflows-fluent-bit-mode2
```

**Check 2**: FluentBit can read container logs
```bash
kubectl exec -n logging <pod-name> -- ls -la /var/log/containers/*_workflows_*.log
```

**Check 3**: FluentBit is parsing JSON
```bash
kubectl logs -n logging <pod-name> | grep "eventType"
```

**Check 4**: Elasticsearch connectivity
```bash
kubectl logs -n logging <pod-name> | grep -i "elasticsearch"
kubectl logs -n logging <pod-name> | grep -i "connection refused"
```

**Check 5**: Elasticsearch is accepting requests
```bash
kubectl port-forward -n elasticsearch svc/elasticsearch 9200:9200 &
curl -s http://localhost:9200/_cluster/health | jq
```

### Raw events exist but normalized indices empty

**Check 1**: Transform is started
```bash
curl -s http://localhost:9200/_transform/workflow-instance-transform/_stats | jq '.transforms[0].state'
```

Expected: `"started"` or `"indexing"`

If stopped:
```bash
curl -X POST http://localhost:9200/_transform/workflow-instance-transform/_start
```

**Check 2**: Transform errors
```bash
curl -s http://localhost:9200/_transform/workflow-instance-transform/_stats | jq '.transforms[0].stats'
```

Look for `index_failures` or `search_failures`

**Check 3**: Normalized indices exist
```bash
curl -s http://localhost:9200/_cat/indices/workflow-instances,task-executions?v
```

If missing, schema initialization failed. Run:
```bash
# From data-index-service module
mvn quarkus:dev -Dquarkus.profile=elasticsearch
# Schema initializes on startup
```

### FluentBit high memory usage

**Check**: Tail DB size
```bash
kubectl exec -n logging <pod-name> -- ls -lh /tail-db/
```

**Solution**: Increase `Mem_Buf_Limit` in INPUT section or reduce log volume

### Elasticsearch index too large

**Check**: ILM policy active
```bash
curl -s http://localhost:9200/workflow-instance-events-raw-*/_ilm/explain | jq
```

**Solution**: Verify ILM policy is attached and check retention settings:
```bash
curl -s http://localhost:9200/_ilm/policy/workflow-events-retention | jq
```

Default retention: 7 days (configurable in schema initialization)

## Retention & Cleanup

### ILM Policy

Raw event indices have ILM policy attached:
- **Hot phase**: Keep for 7 days (default)
- **Delete phase**: Delete after 7 days

ILM automatically manages index lifecycle:
```bash
# Check ILM status
curl -s http://localhost:9200/_ilm/status | jq

# View policy
curl -s http://localhost:9200/_ilm/policy/workflow-events-retention | jq
```

### Manual Cleanup

Delete specific indices:
```bash
# Delete old workflow event indices
curl -X DELETE http://localhost:9200/workflow-instance-events-raw-2026.04.01

# Delete date range
curl -X DELETE http://localhost:9200/workflow-instance-events-raw-2026.04.*
```

## Configuration Tuning

### Performance

**High event volume** (> 1000 events/sec):
```conf
[OUTPUT]
    Name            es
    Match           workflow.instance
    Host            ${ELASTICSEARCH_HOST}
    Port            ${ELASTICSEARCH_PORT}
    Index           workflow-instance-events-raw
    Logstash_Format On
    Logstash_Prefix workflow-instance-events-raw
    Buffer_Size     4KB      # Increase buffer
    Retry_Limit     10       # More retries
```

**Low memory** nodes:
```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*_${WORKFLOW_NAMESPACE}_*.log
    Mem_Buf_Limit     2MB    # Reduce buffer
```

### Security

**Enable TLS** (production):
```yaml
env:
- name: ELASTICSEARCH_TLS
  value: "On"
- name: ELASTICSEARCH_TLS_VERIFY
  value: "On"
```

**Use Elasticsearch credentials**:
Add to DaemonSet:
```yaml
env:
- name: ELASTICSEARCH_USER
  valueFrom:
    secretKeyRef:
      name: elasticsearch-credentials
      key: username
- name: ELASTICSEARCH_PASSWORD
  valueFrom:
    secretKeyRef:
      name: elasticsearch-credentials
      key: password
```

Update `fluent-bit.conf`:
```conf
[OUTPUT]
    Name            es
    HTTP_User       ${ELASTICSEARCH_USER}
    HTTP_Passwd     ${ELASTICSEARCH_PASSWORD}
```

## Comparison with MODE 1 (PostgreSQL)

| Feature | MODE 1 (PostgreSQL) | MODE 2 (Elasticsearch) |
|---------|-------------------|----------------------|
| **Normalization** | PostgreSQL triggers | Elasticsearch Transform |
| **Real-time** | ✅ Immediate | ✅ Continuous (1-60s delay) |
| **Out-of-order** | ✅ COALESCE in triggers | ✅ latest() in Transform |
| **Raw storage** | JSONB columns | Daily indices |
| **Retention** | Manual cleanup | ILM automatic |
| **Full-text search** | ❌ Limited | ✅ Built-in |
| **Scaling** | Vertical (larger DB) | Horizontal (more nodes) |
| **Complexity** | Lower | Higher |
| **Best for** | Standard deployments | Analytics, search, scale |

## When to Use MODE 2

### ✅ Use Elasticsearch MODE 2 when:
- Need full-text search across workflow data
- High event volume (> 10,000/sec)
- Analytics and dashboards (Kibana)
- Horizontal scaling required
- Long-term event retention with compression

### ❌ Use PostgreSQL MODE 1 instead when:
- Simple query requirements (ID, status filters)
- Lower event volume (< 1,000/sec)
- Existing PostgreSQL infrastructure
- Simpler operations (fewer moving parts)
- Immediate consistency critical

## Next Steps

After FluentBit is running:

1. **Deploy Data Index service** with Elasticsearch backend:
   ```bash
   cd data-index/data-index-service
   mvn quarkus:dev -Dquarkus.profile=elasticsearch
   ```

2. **Query via GraphQL**:
   ```bash
   curl http://localhost:8080/graphql -d '{"query":"{ getWorkflowInstances { id name status } }"}'
   ```

3. **Set up Kibana** (optional) for event visualization:
   ```bash
   kubectl apply -f kibana-deployment.yaml
   ```

## Files Reference

- `fluent-bit.conf` - Main FluentBit configuration
- `parsers.conf` - Log format parsers
- `kubernetes/configmap.yaml` - Auto-generated ConfigMap
- `kubernetes/daemonset.yaml` - FluentBit DaemonSet
- `deploy.sh` - Deployment helper script
- `README.md` - This file

## Support

For issues:
1. Check FluentBit logs: `kubectl logs -n logging -l app=workflows-fluent-bit-mode2`
2. Verify Elasticsearch health: `curl http://localhost:9200/_cluster/health`
3. Check Transform stats: `curl http://localhost:9200/_transform/_stats`
4. Review schema initialization: `data-index-storage-elasticsearch/README.md`

For architecture details, see:
- `data-index/docs/ARCHITECTURE-SUMMARY.md`
- `data-index/docs/deployment/MODE2_ELASTICSEARCH.md` (to be created)
