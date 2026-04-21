# FluentBit Configuration for Elasticsearch Mode

This directory contains FluentBit configurations for Data Index Mode 2 (Elasticsearch Transform).

## Files

### 1. fluent-bit-elasticsearch.conf
**Purpose**: Main FluentBit configuration for standalone/VM deployments

**Features**:
- Tails workflow runtime log files
- Parses CloudEvents from logs
- Ships directly to Elasticsearch raw events index
- Buffers events locally for reliability
- Retry logic for failed sends
- Metrics endpoint for monitoring

### 2. parsers.conf
**Purpose**: Log parsers for different input formats

**Parsers Included**:
- `json` - Generic JSON logs
- `cloudevents` - CloudEvents format
- `quarkus` - Quarkus application logs
- `docker` - Docker container logs
- `kubernetes` - Kubernetes pod logs
- `java_multiline` - Java stack traces (multiline)

### 3. fluent-bit-elasticsearch-kubernetes.yaml
**Purpose**: Kubernetes DaemonSet deployment

**Components**:
- DaemonSet (runs on every node)
- ServiceAccount + RBAC (permissions)
- ConfigMap (FluentBit config)
- Secret (Elasticsearch credentials)
- Service (metrics endpoint)

## Deployment

### Prerequisites

1. **Elasticsearch cluster** running and accessible
2. **Raw events index** created or auto-create enabled
3. **Workflow runtime** configured to output CloudEvents

### Option 1: Standalone/VM Deployment

#### Install FluentBit

```bash
# Ubuntu/Debian
curl https://raw.githubusercontent.com/fluent/fluent-bit/master/install.sh | sh

# RHEL/CentOS
curl https://raw.githubusercontent.com/fluent/fluent-bit/master/install.sh | sh

# macOS
brew install fluent-bit
```

#### Configure

```bash
# Copy configuration files
sudo mkdir -p /etc/fluent-bit
sudo cp fluent-bit-elasticsearch.conf /etc/fluent-bit/fluent-bit.conf
sudo cp parsers.conf /etc/fluent-bit/parsers.conf

# Set Elasticsearch connection
export ES_HOST=elasticsearch.example.com
export ES_PORT=9200
export ES_USER=elastic
export ES_PASSWORD=changeme
```

#### Start FluentBit

```bash
# Systemd
sudo systemctl start fluent-bit
sudo systemctl enable fluent-bit
sudo systemctl status fluent-bit

# Or run directly
fluent-bit -c /etc/fluent-bit/fluent-bit.conf
```

### Option 2: Docker Deployment

```bash
docker run -d \
  --name fluent-bit \
  -v /var/log/workflows:/var/log/workflows:ro \
  -v $(pwd)/fluent-bit-elasticsearch.conf:/fluent-bit/etc/fluent-bit.conf \
  -v $(pwd)/parsers.conf:/fluent-bit/etc/parsers.conf \
  -e ES_HOST=elasticsearch \
  -e ES_PORT=9200 \
  -e ES_USER=elastic \
  -e ES_PASSWORD=changeme \
  -p 2020:2020 \
  fluent/fluent-bit:3.0
```

### Option 3: Kubernetes Deployment

#### Configure Elasticsearch Connection

Edit `fluent-bit-elasticsearch-kubernetes.yaml`:

```yaml
# Update Secret
apiVersion: v1
kind: Secret
metadata:
  name: elasticsearch-credentials
stringData:
  ES_USER: "your-username"
  ES_PASSWORD: "your-password"

# Update DaemonSet env vars
env:
  - name: ES_HOST
    value: "your-elasticsearch-host"  # Change this!
  - name: ES_PORT
    value: "9200"
```

#### Deploy

```bash
kubectl apply -f fluent-bit-elasticsearch-kubernetes.yaml
```

#### Verify

```bash
# Check DaemonSet
kubectl get daemonset -n logging

# Check pods
kubectl get pods -n logging -l app=fluent-bit

# Check logs
kubectl logs -n logging -l app=fluent-bit --tail=50

# Check metrics
kubectl port-forward -n logging svc/fluent-bit 2020:2020
curl http://localhost:2020/api/v1/metrics
```

## Configuration Customization

### Log File Path

Edit `fluent-bit-elasticsearch.conf`:

```ini
[INPUT]
    Name              tail
    Path              /your/custom/path/*.log  # Change this!
```

### Elasticsearch Index

```ini
[OUTPUT]
    Index           your-custom-index  # Change this!
```

### Filtering

Only process specific event types:

```ini
[FILTER]
    Name    grep
    Match   workflow.events.*
    Regex   cloudEvent.type ^workflow\.completed$  # Only completed events
```

### Performance Tuning

```ini
[SERVICE]
    Flush        10  # Increase flush interval for better batching

[OUTPUT]
    Buffer_Size  10MB  # Larger buffer for bulk sends
    Workers      4     # More workers for parallel processing
```

## Monitoring

### FluentBit Metrics

```bash
# Metrics endpoint (JSON)
curl http://localhost:2020/api/v1/metrics

# Pretty print
curl http://localhost:2020/api/v1/metrics | jq .
```

**Key Metrics**:
- `output_proc_records_total` - Total records sent
- `output_proc_bytes_total` - Total bytes sent
- `output_errors_total` - Total errors
- `output_retries_total` - Total retries

### Prometheus Integration

FluentBit exposes Prometheus metrics on `/api/v1/metrics/prometheus`:

```yaml
# prometheus.yml
scrape_configs:
  - job_name: 'fluent-bit'
    static_configs:
      - targets: ['fluent-bit:2020']
```

### Grafana Dashboards

Import FluentBit dashboard:
- Dashboard ID: 7752
- URL: https://grafana.com/grafana/dashboards/7752

## Troubleshooting

### No Events Appearing in Elasticsearch

**Check FluentBit logs:**
```bash
# Systemd
sudo journalctl -u fluent-bit -f

# Docker
docker logs -f fluent-bit

# Kubernetes
kubectl logs -n logging -l app=fluent-bit -f
```

**Common issues**:
1. **Wrong log path** - Verify `Path` in INPUT section
2. **Parser mismatch** - Check if logs match parser format
3. **Network connectivity** - Test ES connection: `curl http://ES_HOST:ES_PORT`
4. **Authentication** - Verify ES credentials

### Events Not Parsed Correctly

**Enable debug logging:**
```ini
[SERVICE]
    Log_Level    debug
```

**Test parser:**
```bash
# Test with sample log
echo '{"cloudEvent": {"type": "workflow.started"}}' | \
  fluent-bit -c /etc/fluent-bit/fluent-bit.conf -i stdin -o stdout
```

### High Memory Usage

**Limit buffer size:**
```ini
[INPUT]
    Mem_Buf_Limit     5MB

[OUTPUT]
    Buffer_Size       5MB
```

**Enable storage buffering:**
```ini
[SERVICE]
    storage.path              /var/log/fluentbit-storage/
    storage.max_chunks_up     128
    storage.backlog.mem_limit 5M
```

### Events Lost on FluentBit Restart

**Enable persistent storage:**
```ini
[INPUT]
    DB  /var/log/fluentbit-tail.db  # Track file position

[SERVICE]
    storage.path  /var/log/fluentbit-storage/  # Buffer events to disk
```

## Testing

### Send Test Event

```bash
# Write test CloudEvent to log file
cat >> /var/log/workflows/test.log <<EOF
{
  "cloudEvent": {
    "specversion": "1.0",
    "type": "workflow.started",
    "source": "test",
    "id": "test-001",
    "time": "2026-04-20T16:00:00Z",
    "data": {
      "id": "wf-test-001",
      "name": "test-workflow",
      "status": "RUNNING"
    }
  }
}
EOF
```

### Verify in Elasticsearch

```bash
# Wait 5-10 seconds, then search
curl -X GET "http://localhost:9200/workflow-events-raw/_search?pretty" \
  -H 'Content-Type: application/json' \
  -d '{
    "query": {
      "term": {
        "cloudEvent.id": "test-001"
      }
    }
  }'
```

## Performance Benchmarks

**Typical Performance** (VM with 2 CPU, 4GB RAM):
- Throughput: 10,000 events/sec
- Latency: < 100ms (p99)
- Memory: 50-100MB
- CPU: 5-10%

**Tuning for High Volume**:
- Increase `Workers` to 4-8
- Increase `Buffer_Size` to 10-20MB
- Set `Flush` to 10-30s
- Use storage buffering

## Security

### TLS/SSL

Enable TLS for Elasticsearch connection:

```ini
[OUTPUT]
    tls         On
    tls.verify  On
    tls.ca_file /path/to/ca.crt
```

### Authentication

**Basic Auth:**
```ini
[OUTPUT]
    HTTP_User   elastic
    HTTP_Passwd changeme
```

**API Key:**
```ini
[OUTPUT]
    Header Authorization "ApiKey your-api-key-here"
```

### Network Policies (Kubernetes)

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: fluent-bit-egress
  namespace: logging
spec:
  podSelector:
    matchLabels:
      app: fluent-bit
  policyTypes:
    - Egress
  egress:
    - to:
        - namespaceSelector:
            matchLabels:
              name: elasticsearch
      ports:
        - protocol: TCP
          port: 9200
```

## Migration from PostgreSQL Mode

If migrating from PostgreSQL/Kafka mode:

1. **Keep both running** during transition
2. **Configure FluentBit** to send to Elasticsearch
3. **Create ES Transforms** (see transforms/README.md)
4. **Verify data flow** end-to-end
5. **Switch GraphQL** to use Elasticsearch storage
6. **Decommission** PostgreSQL event processor

## References

- [FluentBit Documentation](https://docs.fluentbit.io/)
- [Elasticsearch Output Plugin](https://docs.fluentbit.io/manual/pipeline/outputs/elasticsearch)
- [Tail Input Plugin](https://docs.fluentbit.io/manual/pipeline/inputs/tail)
- [ELASTICSEARCH-TRANSFORM-GUIDE.md](../../../../docs/ELASTICSEARCH-TRANSFORM-GUIDE.md)
- [ARCHITECTURE-SUMMARY.md](../../../../docs/ARCHITECTURE-SUMMARY.md)
