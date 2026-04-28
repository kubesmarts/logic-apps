# Mode 2: Elasticsearch

**Status**: Configuration placeholder - to be implemented

## Architecture

```
Quarkus Flow Pods
    ↓ (JSON logs)
FluentBit DaemonSet
    ↓ (parse & route)
Elasticsearch Raw Indices
    ↓ (Elasticsearch Transform)
Elasticsearch Normalized Indices
    ↓ (Elasticsearch Java Client)
Data Index GraphQL API
```

## Configuration

Mode 2 uses Elasticsearch for both event storage and querying.

### Event Flow
1. FluentBit captures container logs
2. Parses JSON events
3. Sends to Elasticsearch raw indices:
   - `workflow-instance-events-raw-{yyyy.MM.dd}`
   - `task-execution-events-raw-{yyyy.MM.dd}`
4. Elasticsearch Transform aggregates into normalized indices:
   - `workflow-instances`
   - `task-executions`
5. Data Index queries normalized indices via Elasticsearch Java Client

### Files (To Be Created)
- `fluent-bit.conf` - FluentBit → Elasticsearch output
- `parsers.conf` - JSON parser
- `kubernetes/configmap.yaml` - K8s ConfigMap
- `kubernetes/daemonset.yaml` - K8s DaemonSet
- `elasticsearch-transforms.json` - Elasticsearch Transform definitions

## Notes

This mode requires:
- Elasticsearch 8.x cluster
- `data-index-storage-elasticsearch` module
- Elasticsearch Transform pipeline configuration

See `data-index-storage/data-index-storage-elasticsearch/` for storage implementation.
