# KIND Scripts - Data Index Local Testing

Quick reference for deploying and testing Data Index on local KIND (Kubernetes in Docker) clusters.

## Overview

These scripts support **three deployment modes** with end-to-end testing:

| Mode | Ingestion | Normalization | Storage | Status |
|------|-----------|---------------|---------|--------|
| **MODE 1** | FluentBit → PostgreSQL | PostgreSQL Triggers | PostgreSQL | ✅ Production |
| **MODE 2** | FluentBit → Elasticsearch | ES Transforms | Elasticsearch | ✅ Production |
| **MODE 3** | Kafka (CloudEvents) | Java Processors | PostgreSQL | ✅ Production |

## Quick Start

```bash
# MODE 1 - PostgreSQL with FluentBit
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh
./deploy-data-index.sh postgresql
./deploy-workflow-app.sh
./test-mode1-e2e.sh

# MODE 2 - Elasticsearch with FluentBit
./setup-cluster.sh
MODE=elasticsearch ./install-dependencies.sh
./deploy-data-index.sh elasticsearch
./deploy-workflow-app.sh
./test-mode2-e2e.sh

# MODE 3 - Kafka Ingestion
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh
MODE=kafka ./install-dependencies.sh  # Additional Kafka install
./deploy-data-index.sh kafka
./deploy-kafka-ingestion.sh
MODE=kafka ./deploy-workflow-app.sh
./test-mode3-e2e.sh
```

## Script Reference

### Core Setup Scripts

#### `setup-cluster.sh`
Creates a KIND cluster with NodePort mappings for local access.

**Usage:**
```bash
./setup-cluster.sh

# Custom cluster name
CLUSTER_NAME=my-cluster ./setup-cluster.sh
```

**What it does:**
- Creates KIND cluster named `data-index-test` (default)
- Configures single control-plane node
- Maps NodePorts for external access:
  - `30080` → GraphQL API (Data Index service)
  - `30432` → PostgreSQL
  - `30920` → Elasticsearch
  - `30900` → Kafka

**Requirements:** KIND, Docker

---

#### `install-dependencies.sh`
Installs storage and messaging dependencies based on deployment mode.

**Usage:**
```bash
# PostgreSQL (MODE 1 and MODE 3)
MODE=postgresql ./install-dependencies.sh

# Elasticsearch (MODE 2)
MODE=elasticsearch ./install-dependencies.sh

# Kafka (MODE 3)
MODE=kafka ./install-dependencies.sh
```

**What it installs:**

**PostgreSQL mode:**
- PostgreSQL 16.x (Bitnami Helm chart)
- Namespace: `postgresql`
- Credentials: `dataindex` / `dataindex123`
- NodePort: `localhost:30432`

**Elasticsearch mode:**
- Elasticsearch 9.4.1 (StatefulSet, no ECK operator)
- Namespace: `elasticsearch`
- Security: Fully disabled (dev/test only)
- NodePort: `localhost:30920`

**Kafka mode:**
- Kafka (KRaft single-node)
- Namespace: `kafka`
- Bootstrap servers: `kafka.kafka.svc.cluster.local:9092`
- NodePort: `localhost:30900`

---

#### `init-database-schema.sh`
Initializes PostgreSQL schema for MODE 1 and MODE 3 (when Flyway is disabled).

**Usage:**
```bash
./init-database-schema.sh
```

**What it does:**
- Applies SQL migration: `V1__initial_schema.sql`
- Creates normalized tables: `workflow_instances`, `task_instances`
- Creates raw event tables: `workflow_events_raw`, `task_events_raw` (MODE 1 only)
- Sets up triggers for normalization (MODE 1 only)

**Note:** MODE 3 ingestion service runs Flyway automatically on startup, so this script is rarely needed for MODE 3.

---

### Deployment Scripts

#### `deploy-data-index.sh <mode>`
Builds and deploys the Data Index service.

**Usage:**
```bash
# MODE 1 - PostgreSQL backend
./deploy-data-index.sh postgresql

# MODE 2 - Elasticsearch backend
./deploy-data-index.sh elasticsearch

# MODE 3 - PostgreSQL backend (query service only)
./deploy-data-index.sh kafka
```

**What it does:**
1. Builds container image: `kubesmarts/data-index-service:999-SNAPSHOT`
2. Loads image to KIND cluster
3. Creates `data-index` namespace
4. Deploys service with backend-specific configuration
5. Creates NodePort service on port `30080`

**Services deployed:**
- `data-index-service` (GraphQL API)
- Readiness/liveness probes configured
- Environment variables for backend connection

**Access points:**
- GraphQL API: `http://localhost:30080/graphql`
- GraphQL UI: `http://localhost:30080/q/graphql-ui`
- Health: `http://localhost:30080/q/health`

---

#### `deploy-kafka-ingestion.sh`
Deploys the Kafka ingestion service (MODE 3 only).

**Usage:**
```bash
./deploy-kafka-ingestion.sh
```

**What it does:**
1. Builds container image: `kubesmarts/data-index-ingestion-kafka-service:999-SNAPSHOT`
2. Loads image to KIND cluster
3. Deploys ingestion service to `data-index` namespace
4. Connects to Kafka topic `flow-lifecycle-out`
5. Configures PostgreSQL connection for normalization
6. Enables Flyway migration on startup

**Configuration:**
- Consumer group: `data-index-ingestion`
- Dead-letter queue: `data-index-events-dlq`
- Consumes CloudEvents from Kafka
- Normalizes events to PostgreSQL using JDBC UPSERT

---

#### `deploy-workflow-app.sh`
Deploys a test workflow application.

**Usage:**
```bash
# MODE 1 and MODE 2 (stdout logging)
./deploy-workflow-app.sh

# MODE 3 (Kafka CloudEvents)
MODE=kafka ./deploy-workflow-app.sh
```

**What it does:**
1. Builds container image: `local/workflow-test-app:1.0.0`
2. Loads image to KIND cluster
3. Deploys to `workflows` namespace
4. Configures logging/event publishing based on mode

**Test workflows available:**
- `/test-workflows/simple-set` - Simple workflow with 2 set operations
- `/test-workflows/hello-world` - Hello world workflow
- `/test-workflows/failing` - Intentional failure for testing

**Access:**
```bash
# Port-forward to access locally
kubectl port-forward -n workflows svc/workflow-test-app 8082:8080

# Execute workflow
curl -X POST http://localhost:8082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name":"test-execution"}'
```

---

### Test Scripts

#### `test-mode1-e2e.sh`
Complete end-to-end test for MODE 1 (PostgreSQL + FluentBit + Triggers).

**Usage:**
```bash
./test-mode1-e2e.sh
```

**What it tests:**
1. ✓ Workflow execution triggers events
2. ✓ FluentBit collects events from container logs
3. ✓ PostgreSQL receives raw events
4. ✓ Triggers normalize events in real-time
5. ✓ GraphQL API returns normalized data
6. ✓ Idempotency (replay events don't create duplicates)

**Duration:** ~5-6 minutes

**See also:** [Local Development with KIND](../../data-index-docs/modules/ROOT/pages/deployment/kind-local.adoc)

---

#### `test-mode2-e2e.sh`
Complete end-to-end test for MODE 2 (Elasticsearch + FluentBit + Transforms).

**Usage:**
```bash
./test-mode2-e2e.sh
```

**What it tests:**
1. ✓ Elasticsearch deployment and schema initialization
2. ✓ FluentBit collection to Elasticsearch
3. ✓ Raw events in Elasticsearch indices
4. ✓ ES Transform normalization (~1s frequency)
5. ✓ GraphQL API queries normalized indices
6. ✓ Idempotency
7. ✓ Out-of-order event handling
8. ✓ Smart filtering performance

**Duration:** ~5-7 minutes (includes Elasticsearch startup)

**See also:** [Elasticsearch Deployment](../../data-index-docs/modules/ROOT/pages/deployment/elasticsearch.adoc#end-to-end-testing)

---

#### `test-mode3-e2e.sh`
Complete end-to-end test for MODE 3 (Kafka + CloudEvents + Java Processors).

**Usage:**
```bash
./test-mode3-e2e.sh
```

**What it tests:**
1. ✓ Kafka broker deployment and connectivity
2. ✓ Workflow app publishes CloudEvents to Kafka
3. ✓ Ingestion service consumes from Kafka
4. ✓ Events normalized to PostgreSQL via JDBC
5. ✓ GraphQL API returns normalized data
6. ✓ Idempotency (UPSERT prevents duplicates)
7. ✓ Consumer group lag monitoring
8. ✓ Dead-letter queue handling

**Duration:** ~6-8 minutes (includes Kafka startup)

**CloudEvents verification:** Tests verify proper CloudEvent structure and routing.

**See also:** [Kafka Deployment](../../data-index-docs/modules/ROOT/pages/deployment/kafka.adoc#end-to-end-testing)

---

#### `test-graphql.sh`
Standalone GraphQL API test (runs against any deployed mode).

**Usage:**
```bash
./test-graphql.sh
```

**What it tests:**
- GraphQL introspection
- `getWorkflowInstances` query
- `getWorkflowInstanceById` query
- Filtering and pagination
- Task execution retrieval

**Requirements:** Data Index service must be deployed and accessible.

---

## Configuration Files

#### `elasticsearch-statefulset.yaml`
Kubernetes manifest for Elasticsearch deployment (MODE 2).

**Features:**
- Single-node Elasticsearch 9.4.1
- Security fully disabled (dev/test only)
- Init container for `vm.max_map_count` tuning
- Persistent volume for data
- NodePort service on `30920`

**Usage:**
```bash
kubectl apply -f elasticsearch-statefulset.yaml
```

---

## Common Workflows

### Clean Slate Deployment

```bash
# Delete existing cluster
kind delete cluster --name data-index-test

# Start fresh
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh
./deploy-data-index.sh postgresql
./deploy-workflow-app.sh

# Verify
./test-mode1-e2e.sh
```

### Switch Between Modes

```bash
# From MODE 1 to MODE 2
kubectl delete namespace data-index
MODE=elasticsearch ./install-dependencies.sh
./deploy-data-index.sh elasticsearch
./test-mode2-e2e.sh

# From MODE 2 to MODE 3
kubectl delete namespace data-index elasticsearch
MODE=postgresql ./install-dependencies.sh
MODE=kafka ./install-dependencies.sh
./deploy-data-index.sh kafka
./deploy-kafka-ingestion.sh
MODE=kafka ./deploy-workflow-app.sh
./test-mode3-e2e.sh
```

### Debug Event Flow

```bash
# MODE 1 - Check FluentBit → PostgreSQL
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 -f
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT COUNT(*) FROM workflow_events_raw;"

# MODE 2 - Check FluentBit → Elasticsearch → Transform
kubectl logs -n logging -l app=workflows-fluent-bit-mode2 -f
curl http://localhost:30920/workflow-events-*/_count
curl http://localhost:30920/_transform/workflow-instances-transform/_stats

# MODE 3 - Check Kafka → Ingestion Service → PostgreSQL
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic flow-lifecycle-out --from-beginning
kubectl logs -n data-index -l app=data-index-ingestion-kafka-service -f
```

### Cleanup

```bash
# Delete specific namespaces
kubectl delete namespace data-index workflows logging postgresql elasticsearch kafka

# Delete entire cluster
kind delete cluster --name data-index-test

# Clean Docker images
docker rmi kubesmarts/data-index-service:999-SNAPSHOT
docker rmi kubesmarts/data-index-ingestion-kafka-service:999-SNAPSHOT
docker rmi local/workflow-test-app:1.0.0
```

---

## Troubleshooting

### Cluster won't start
```bash
# Check Docker
docker info

# Delete and recreate
kind delete cluster --name data-index-test
./setup-cluster.sh
```

### Pods stuck in ImagePullBackOff
```bash
# Verify images loaded to KIND
docker exec -it data-index-test-control-plane crictl images | grep data-index

# Reload if missing
kind load docker-image kubesmarts/data-index-service:999-SNAPSHOT --name data-index-test
```

### No events flowing
```bash
# MODE 1/2 - Check FluentBit
kubectl get pods -n logging
kubectl logs -n logging -l app=workflows-fluent-bit | grep -i error

# MODE 3 - Check Kafka connectivity
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list
```

### Tests fail
```bash
# Check all pods are ready
kubectl get pods --all-namespaces

# Check service endpoints
curl http://localhost:30080/q/health
curl http://localhost:30432  # PostgreSQL (should refuse connection)
curl http://localhost:30920  # Elasticsearch (should return JSON)
```

---

## Environment Variables

Override default values:

```bash
# Cluster name
export CLUSTER_NAME=my-test-cluster

# Project root (auto-detected)
export PROJECT_ROOT=/path/to/logic-apps

# Skip cluster creation in tests
export SKIP_CLUSTER_CREATION=true
```

---

## Requirements

- **KIND** (v0.20+)
- **kubectl** (v1.28+)
- **Docker** (v24+)
- **Helm** (v3.12+) - for PostgreSQL installation
- **Java 17+** - for building services
- **Maven 3.9+** - for building services
- **jq** - for JSON parsing in tests
- **curl** - for HTTP requests

**Verify:**
```bash
kind version
kubectl version --client
docker --version
helm version
java -version
mvn -version
jq --version
```

---

## Additional Resources

- [Local Development with KIND](../../data-index-docs/modules/ROOT/pages/deployment/kind-local.adoc)
- [PostgreSQL Mode Architecture](../../data-index-docs/modules/ROOT/pages/architecture/postgresql-mode.adoc)
- [Elasticsearch Mode Architecture](../../data-index-docs/modules/ROOT/pages/architecture/elasticsearch-mode.adoc)
- [Kafka Mode Architecture](../../data-index-docs/modules/ROOT/pages/architecture/kafka-mode.adoc)
- [Configuration Reference](../../data-index-docs/modules/ROOT/pages/developers/configuration.adoc)
- [Troubleshooting Guide](../../data-index-docs/modules/ROOT/pages/developers/troubleshooting.adoc)
