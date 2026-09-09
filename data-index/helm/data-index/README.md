# Data Index Helm Chart

Helm chart for deploying Data Index in different modes (PostgreSQL, Elasticsearch, or Kafka).

## Overview

This chart supports three deployment modes:

- **MODE 1**: PostgreSQL + FluentBit + Triggers
- **MODE 2**: Elasticsearch + Vector + Transforms
- **MODE 3**: Kafka + Ingestion Service + PostgreSQL

## Quick Start

### Local Testing (KIND)

```bash
# 1. Create KIND cluster with port mappings
kind create cluster --name data-index-test --config kind-cluster.yaml

# 2. Build and load images (see scripts/e2e/common-setup.sh)

# 3. Install chart with desired mode
helm install data-index . -f values-mode1.yaml
```

### MODE 1 (PostgreSQL + FluentBit)

```bash
helm install data-index . -f values-mode1.yaml
```

**Architecture:**
```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                    ↓ (FluentBit tail)
            PostgreSQL raw tables (JSONB)
                    ↓ (BEFORE INSERT triggers)
            PostgreSQL normalized tables
                    ↓ (JPA/Hibernate)
            GraphQL API (SmallRye GraphQL)
```

**Access:**
- GraphQL UI: http://localhost:30080/q/graphql-ui
- GraphQL API: http://localhost:30080/graphql

### MODE 2 (Elasticsearch + Vector)

```bash
helm install data-index . -f values-mode2.yaml
```

**Architecture:**
```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                    ↓ (Vector tail)
            Elasticsearch raw indices
                    ↓ (ES Transform, continuous, 1s)
            Elasticsearch normalized indices
                    ↓ (Elasticsearch Java Client)
            GraphQL API (SmallRye GraphQL)
```

**Access:**
- GraphQL UI: http://localhost:30080/q/graphql-ui
- Elasticsearch: http://localhost:30920

### MODE 3 (Kafka + Ingestion)

```bash
helm install data-index . -f values-mode3.yaml
```

**Architecture:**
```
Quarkus Flow → Kafka (CloudEvents)
                    ↓ (SmallRye Reactive Messaging)
            Data Index Ingestion Service
                    ↓ (JDBC batch UPSERT)
            PostgreSQL normalized tables
                    ↓ (JPA/Hibernate)
            GraphQL API (SmallRye GraphQL)
```

**Access:**
- GraphQL UI: http://localhost:30080/q/graphql-ui
- Ingestion Service: http://localhost:30081
- Kafka: http://localhost:30092

## Configuration

### Common Configuration (values.yaml)

Base configuration shared across all modes.

### Mode-Specific Overrides

- `values-mode1.yaml` - PostgreSQL + FluentBit
- `values-mode2.yaml` - Elasticsearch + Vector
- `values-mode3.yaml` - Kafka + Ingestion

### Key Configuration Options

```yaml
# Deployment mode
mode: mode1  # mode1, mode2, or mode3

# Enable/disable components
postgresql:
  enabled: true
elasticsearch:
  enabled: false
kafka:
  enabled: false
fluentbit:
  enabled: true
vector:
  enabled: false
dataIndexIngestion:
  enabled: false

# Service types (NodePort for KIND, ClusterIP for production)
dataIndex:
  service:
    type: NodePort  # or ClusterIP
```

## KIND Cluster Configuration

The `kind-cluster.yaml` file defines a single-node cluster with port mappings for all services:

| Service | NodePort | Host Port |
|---------|----------|-----------|
| Data Index (GraphQL) | 30080 | 30080 |
| Data Index Ingestion | 30081 | 30081 |
| Workflow Test App | 30082 | 30082 |
| PostgreSQL | 30432 | 30432 |
| Kafka | 30092 | 30092 |
| Elasticsearch | 30920 | 30920 |

## Testing

### E2E Shell Scripts

```bash
# Full test (delete cluster, create, deploy, test)
./scripts/e2e/full-test-mode1.sh
./scripts/e2e/full-test-mode2.sh
./scripts/e2e/full-test-mode3.sh

# Individual steps
./scripts/e2e/common-setup.sh     # Create cluster and build images
./scripts/e2e/test-helm-mode1.sh  # Deploy and test MODE 1
```

### Java E2E Tests

```bash
# Assumes Helm chart already deployed
cd data-index-e2e-tests

mvn test -De2e.mode=mode1
mvn test -De2e.mode=mode2
mvn test -De2e.mode=mode3
```

## Production Deployment

For production deployments:

1. **Change service types** from `NodePort` to `ClusterIP`
2. **Enable persistence** for databases:
   ```yaml
   postgresql:
     persistence:
       enabled: true
       size: 10Gi
   ```
3. **Configure resource limits** appropriately
4. **Use external databases** instead of in-cluster deployments
5. **Use operators** for Kafka (Strimzi) and Elasticsearch

## Uninstalling

```bash
helm uninstall data-index
```

## Files

```
helm/data-index/
├── Chart.yaml              # Chart metadata
├── kind-cluster.yaml       # KIND cluster configuration
├── values.yaml             # Common defaults
├── values-mode1.yaml       # MODE 1 overrides
├── values-mode2.yaml       # MODE 2 overrides
├── values-mode3.yaml       # MODE 3 overrides
├── templates/
│   ├── _helpers.tpl        # Template helpers
│   ├── namespace.yaml      # Namespace definitions
│   ├── postgresql.yaml     # PostgreSQL StatefulSet (MODE 1, 3)
│   ├── elasticsearch.yaml  # Elasticsearch StatefulSet (MODE 2)
│   ├── kafka.yaml          # Kafka StatefulSet (MODE 3)
│   ├── fluentbit-*.yaml    # FluentBit DaemonSet (MODE 1)
│   ├── vector-*.yaml       # Vector DaemonSet (MODE 2)
│   ├── data-index-service.yaml       # GraphQL API
│   ├── data-index-ingestion.yaml     # Kafka Ingestion (MODE 3)
│   └── workflow-test-app.yaml        # Test application
└── configs/
    ├── fluentbit/          # FluentBit configurations
    └── vector/             # Vector configurations
```

## Troubleshooting

### Pods not starting

```bash
# Check pod status
kubectl get pods --all-namespaces

# Check specific pod logs
kubectl logs -n default -l app=data-index-service
```

### GraphQL API not accessible

```bash
# Verify service
kubectl get svc -n default data-index-service

# Port forward (if NodePort not working)
kubectl port-forward -n default svc/data-index-service 8080:8080
```

### FluentBit/Vector not collecting logs

```bash
# Check DaemonSet logs
kubectl logs -n logging -l app=fluentbit
kubectl logs -n logging -l app=vector

# Verify log file exists in workflow pod
kubectl exec -n workflows workflow-test-app-xxx -- ls -la /tmp/quarkus-flow-events.log
```

## More Information

See the main Data Index documentation at `data-index/data-index-docs/` for architecture details, development guides, and operational procedures.
