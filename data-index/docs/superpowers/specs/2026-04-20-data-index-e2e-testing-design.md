# Data Index Cleanup & E2E Testing Design

**Date**: 2026-04-20  
**Status**: Design Complete - Ready for Implementation  
**Approach**: Phased Implementation (4 phases)

---

## Executive Summary

This design covers a comprehensive cleanup and testing initiative for the data-index module:

1. **Module Cleanup & Alignment** - Remove dead code, deprecated implementations, non-functional tests, and unused configuration across all 9 data-index modules
2. **E2E Testing Framework** - Infrastructure-based testing with full topology deployment in KIND clusters
3. **Mode Testing** - Validate all 3 deployment modes (PostgreSQL polling, Kafka, Elasticsearch) produce identical results
4. **CI/CD Integration** - GitHub Actions workflow for parallel testing with result validation

**Key Principle**: Test the real thing - deploy actual container images in Kubernetes, execute workflows, validate end-to-end data flow.

---

## Table of Contents

1. [Goals & Non-Goals](#goals--non-goals)
2. [Phase 1: Module Cleanup & Alignment](#phase-1-module-cleanup--alignment)
3. [Phase 2: Image Build & Topology Structure](#phase-2-image-build--topology-structure)
4. [Phase 3: Full Topology Deployment](#phase-3-full-topology-deployment)
5. [Phase 4: CI/CD Integration](#phase-4-cicd-integration)
6. [Test Expansion Strategy](#test-expansion-strategy)
7. [Success Criteria](#success-criteria)
8. [Timeline](#timeline)

---

## Goals & Non-Goals

### Goals

✅ **Cleanup:**
- Remove all dead code (unused classes, deprecated implementations, non-functional tests, unused configs)
- Realign module boundaries where classes are in wrong modules
- Clean dependency tree (remove unused dependencies)
- One commit per module for traceability

✅ **Testing:**
- Infrastructure-based E2E tests (no JUnit, pure Kubernetes deployment)
- Full topology for each mode (Quarkus Flow → FluentBit → Infrastructure → GraphQL)
- Validate identical results across all 3 modes
- Parallel execution to maximize efficiency
- Easy expansion for new test scenarios

✅ **CI/CD:**
- GitHub Actions workflow for automated testing
- Parallel mode testing (all 3 modes run concurrently)
- Result comparison and validation
- Clear pass/fail criteria

### Non-Goals

❌ Unit test coverage improvements (out of scope for this initiative)  
❌ Performance benchmarking (future work)  
❌ Multi-workflow complex scenarios (start simple: happy path + error state)  
❌ Manual testing procedures (everything automated)

---

## Phase 1: Module Cleanup & Alignment

### Scope

All 9 modules from data-index parent POM:
1. `data-index-integration-tests` (test-only)
2. `data-index-common` (utilities)
3. `data-index-model` (domain entities)
4. `data-index-storage-common` (storage interfaces)
5. `data-index-storage-postgresql` (PostgreSQL implementation)
6. `data-index-storage-elasticsearch` (Elasticsearch implementation)
7. `data-index-event-processor` (polling + Kafka modes)
8. `data-index-service` (GraphQL API)
9. `data-index-graphql` (GraphQL types, if separate)

### Audit Process (Per Module)

**1. Unused Code Detection**
- Use IDE "Find Usages" on each public class/method
- Check if referenced only from deprecated code or non-functional tests
- Verify no reflection-based usage (check `@Produces`, `@Observes`, CDI patterns)
- **Removal criteria**: Zero external references AND not part of public API contract

**2. Deprecated Code Removal**
- Search for `@Deprecated` annotations
- Check git history for deprecation date and reason
- Verify replacement exists (e.g., deprecated `FooV1` replaced by `FooV2`)
- **Removal criteria**: Deprecated >3 months OR replacement is production-ready

**3. Non-Functional Test Cleanup**
- Tests with no assertions (`@Test` method that only instantiates mocks)
- Tests that are always `@Disabled` with no ticket reference
- Tests with only `assertTrue(true)` or similar no-ops
- Integration tests that don't start Quarkus or connect to databases
- **Removal criteria**: Test provides no validation value

**4. Unused Configuration**
- Config properties defined but never referenced (grep for property key)
- YAML/properties files in `src/main/resources` not loaded by any code
- Docker/Kubernetes manifests superseded by current deployment docs
- FluentBit configs not mentioned in current documentation
- **Removal criteria**: No code references AND not documented

**5. Module Boundary Realignment**
- Check for classes in wrong modules (e.g., storage implementation in service module)
- Verify dependency direction follows architecture (model ← storage ← service)
- Look for circular dependencies between modules
- **Action**: Move misplaced classes to correct module

### Audit Order

Process modules from least risky to most critical:

1. **data-index-integration-tests** - Test-only module, safe to clean
2. **data-index-common** - Utilities, easy to verify usage
3. **data-index-model** - Domain entities, check JPA/GraphQL usage
4. **data-index-storage-common** - Interfaces, verify implementations exist
5. **data-index-storage-postgresql** - Check against current integration tests
6. **data-index-storage-elasticsearch** - Newer code, likely cleaner
7. **data-index-event-processor** - Check both polling and Kafka modes
8. **data-index-service** - GraphQL API, check against schema
9. **data-index-graphql** - If separate from service

### Safety Checks

- Run `mvn clean verify` after each module cleanup
- Keep existing integration tests passing throughout
- Document removed code in commit messages with git hash for recovery
- One module per commit for easy rollback
- If unsure about removal, create git branch for investigation

### Deliverables

- Clean module structure with no dead code
- Updated POMs (remove unused dependencies)
- One commit per module with cleanup summary
- Optional: markdown doc listing removed classes/methods with reasoning

---

## Phase 2: Image Build & Topology Structure

### Container Images

Build three container images using Quarkus Jib (no Dockerfile needed):

**1. quarkus-flow-app:test**
- **Location**: `data-index-integration-tests/workflow-runtime/`
- **Purpose**: Workflow runtime that executes test workflows
- **Contents**: 
  - Workflows in `src/main/flow/` (`.yaml` extension)
  - REST endpoints to trigger workflows
  - Structured logging enabled (JSON format to file)
  - Quarkus extensions: `quarkus-flow`, `quarkus-logging-json`
- **Configuration**:
  - `quarkus.log.file.enable=true`
  - `quarkus.log.file.path=/var/log/workflows/quarkus-events.log`
  - `quarkus.log.file.json=true`
- **Image tag**: `data-index-test/quarkus-flow-app:test`

**2. data-index-service:test**
- **Location**: `data-index/data-index-service/`
- **Purpose**: GraphQL API for querying workflow data
- **Capabilities**:
  - Configurable storage backend (PostgreSQL or Elasticsearch)
  - SmallRye GraphQL endpoint
  - Health checks (readiness, liveness)
- **Image tag**: `data-index-test/data-index-service:test`

**3. data-index-event-processor:test**
- **Location**: `data-index/data-index-event-processor/`
- **Purpose**: Process workflow events and populate normalized storage
- **Capabilities**:
  - Configurable mode (polling or Kafka consumer)
  - Handles out-of-order events
  - Observability metrics
- **Image tag**: `data-index-test/data-index-event-processor:test`

### Build Script

**File**: `data-index-integration-tests/scripts/build-images.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🔨 Building container images..."

# Build quarkus-flow app
cd workflow-runtime
./mvnw clean package \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.tag=test \
  -Dquarkus.container-image.group=data-index-test
cd ..

# Build data-index-service
cd ../data-index-service
./mvnw clean package \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.tag=test \
  -Dquarkus.container-image.group=data-index-test
cd ..

# Build data-index-event-processor
cd data-index-event-processor
./mvnw clean package \
  -Dquarkus.container-image.build=true \
  -Dquarkus.container-image.tag=test \
  -Dquarkus.container-image.group=data-index-test
cd ..

echo "✅ Images built:"
echo "  - data-index-test/quarkus-flow-app:test"
echo "  - data-index-test/data-index-service:test"
echo "  - data-index-test/data-index-event-processor:test"
```

### Test Workflows

Create two simple workflows in `data-index-integration-tests/workflow-runtime/src/main/flow/`:

**1. simple-set.yaml** (Happy Path)
```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: simple-set
  version: '1.0.0'
do:
  - setMessage:
      set:
        greeting: Hello from Quarkus Flow!
        timestamp: '${ now() }'
```

**2. test-http-failure.yaml** (Error State)
```yaml
document:
  dsl: '1.0.0'
  namespace: test
  name: test-http-failure
  version: '1.0.0'
do:
  - fetchInvalidEndpoint:
      call: http
      with:
        method: get
        endpoint: http://localhost:28080/status/500
```

### KIND Cluster Configuration

**File**: `data-index-integration-tests/kind/cluster-config.yaml`

```yaml
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  - role: control-plane
    extraMounts:
      - hostPath: /tmp/workflows-{mode}  # Unique per cluster
        containerPath: /var/log/workflows
        readOnly: false
```

**Why minimal configuration:**
- 1 control-plane node (no workers needed)
- 2GB memory limit (fits GHA runners)
- HostPath volume for log sharing between pods
- Fast startup for CI/CD

---

## Phase 3: Full Topology Deployment

### Mode 1: PostgreSQL Polling

**Data Flow:**
```
Quarkus Flow (pod)
        ↓ writes logs to HostPath volume
FluentBit (DaemonSet)
        ↓ reads HostPath, parses CloudEvents
PostgreSQL event tables
        ↓
Event Processor (polling mode, 10s interval)
        ↓ normalizes events
PostgreSQL normalized tables
        ↓
Data Index Service (GraphQL API)
```

**Kubernetes Resources:**

1. **postgresql.yaml** - PostgreSQL database
   - Image: `postgres:15`
   - Database: `dataindex`
   - Init scripts via ConfigMap (event tables + normalized tables DDL)

2. **quarkus-flow-app.yaml** - Workflow runtime
   - Image: `data-index-test/quarkus-flow-app:test`
   - HostPath volume mount: `/var/log/workflows`
   - Structured logging to file enabled
   - REST endpoint exposed on port 8080

3. **fluent-bit.yaml** - Log collector
   - DaemonSet (runs on all nodes)
   - Reads from HostPath `/var/log/workflows/quarkus-events.log`
   - Parses JSON CloudEvents
   - Outputs to PostgreSQL event tables via `pgsql` output plugin
   - ConfigMap with FluentBit configuration

4. **event-processor.yaml** - Event processing
   - Image: `data-index-test/data-index-event-processor:test`
   - Mode: `polling`
   - Polling interval: 10 seconds
   - Connects to PostgreSQL

5. **data-index-service.yaml** - GraphQL API
   - Image: `data-index-test/data-index-service:test`
   - Storage backend: `postgresql`
   - Exposes GraphQL endpoint on port 8080

**Deployment Script**: `scripts/deploy-postgresql.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🐘 Deploying PostgreSQL Mode..."

# Create KIND cluster with unique name
kind create cluster --name data-index-pg --config kind/cluster-config.yaml

# Load images into cluster
kind load docker-image data-index-test/quarkus-flow-app:test --name data-index-pg
kind load docker-image data-index-test/data-index-service:test --name data-index-pg
kind load docker-image data-index-test/data-index-event-processor:test --name data-index-pg

# Create namespace
kubectl create namespace data-index

# Deploy PostgreSQL
kubectl apply -f k8s/postgresql/postgresql-init-configmap.yaml
kubectl apply -f k8s/postgresql/postgresql.yaml
kubectl wait --for=condition=ready pod -l app=postgresql -n data-index --timeout=120s

# Deploy Quarkus Flow app
kubectl apply -f k8s/postgresql/quarkus-flow-app.yaml
kubectl wait --for=condition=ready pod -l app=quarkus-flow-app -n data-index --timeout=120s

# Deploy FluentBit
kubectl apply -f k8s/postgresql/fluent-bit.yaml
kubectl wait --for=condition=ready pod -l app=fluent-bit -n data-index --timeout=60s

# Deploy Event Processor
kubectl apply -f k8s/postgresql/event-processor.yaml
kubectl wait --for=condition=ready pod -l app=event-processor -n data-index --timeout=60s

# Deploy Data Index Service
kubectl apply -f k8s/postgresql/data-index-service.yaml
kubectl wait --for=condition=ready pod -l app=data-index-service -n data-index --timeout=60s

echo "✅ PostgreSQL mode deployed"

# Execute workflows
echo "▶️  Executing workflows..."
kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/simple-set \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice"}'

sleep 2

kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/test-http-failure \
  -H 'Content-Type: application/json' \
  -d '{"url": "http://invalid"}'

# Wait for event processing (2 polling cycles)
echo "⏳ Waiting for event processing..."
sleep 30

echo "✅ PostgreSQL mode ready for validation"
```

**Validation Script**: `scripts/validate-postgresql.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🔍 Validating PostgreSQL mode..."

# Switch to PostgreSQL cluster context
kubectl config use-context kind-data-index-pg

# Port-forward GraphQL service (unique port per mode)
kubectl port-forward -n data-index svc/data-index-service 8081:8080 &
PF_PID=$!
sleep 3

# Execute GraphQL queries
curl -X POST http://localhost:8081/graphql \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "{ getWorkflowInstances { id name status startTime endTime input output } }"
  }' | jq . > /tmp/results/postgresql-mode.json

# Verify we got 2 workflows
WORKFLOW_COUNT=$(jq '.data.getWorkflowInstances | length' /tmp/results/postgresql-mode.json)
if [ "$WORKFLOW_COUNT" -eq 2 ]; then
    echo "✅ Found 2 workflows"
else
    echo "❌ Expected 2 workflows, found $WORKFLOW_COUNT"
    kill $PF_PID
    exit 1
fi

# Cleanup port-forward
kill $PF_PID

echo "✅ PostgreSQL validation complete"
```

---

### Mode 2: Kafka

**Data Flow:**
```
Quarkus Flow (pod)
        ↓ writes logs to HostPath volume
FluentBit (DaemonSet)
        ↓ reads HostPath, parses CloudEvents
Kafka topic (workflow-events)
        ↓
Event Processor (Kafka consumer mode)
        ↓ normalizes events
PostgreSQL normalized tables
        ↓
Data Index Service (GraphQL API)
```

**Key Differences from Mode 1:**

1. **Additional Infrastructure**:
   - Zookeeper deployment (required by Kafka)
   - Kafka broker deployment
   - Topic creation: `workflow-events`

2. **FluentBit Configuration**:
   - Output plugin: `kafka` (instead of `pgsql`)
   - Broker: `kafka.data-index.svc.cluster.local:9092`
   - Topic: `workflow-events`
   - Format: JSON

3. **Event Processor**:
   - Mode: `kafka` (instead of `polling`)
   - Real-time consumption (no polling interval)
   - Consumer group: `data-index-processor`

4. **PostgreSQL**:
   - Only normalized tables (no event tables needed)
   - Kafka acts as event storage

**Deployment Script**: `scripts/deploy-kafka.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "📨 Deploying Kafka Mode..."

kind create cluster --name data-index-kafka --config kind/cluster-config.yaml
kind load docker-image data-index-test/quarkus-flow-app:test --name data-index-kafka
kind load docker-image data-index-test/data-index-service:test --name data-index-kafka
kind load docker-image data-index-test/data-index-event-processor:test --name data-index-kafka

kubectl create namespace data-index

# Deploy Zookeeper
kubectl apply -f k8s/kafka/zookeeper.yaml
kubectl wait --for=condition=ready pod -l app=zookeeper -n data-index --timeout=120s

# Deploy Kafka
kubectl apply -f k8s/kafka/kafka.yaml
kubectl wait --for=condition=ready pod -l app=kafka -n data-index --timeout=120s

# Create topic
kubectl exec -n data-index deployment/kafka -- kafka-topics.sh \
  --create --topic workflow-events \
  --bootstrap-server localhost:9092 \
  --partitions 1 --replication-factor 1

# Deploy PostgreSQL (normalized storage only)
kubectl apply -f k8s/kafka/postgresql-init-configmap.yaml
kubectl apply -f k8s/kafka/postgresql.yaml
kubectl wait --for=condition=ready pod -l app=postgresql -n data-index --timeout=120s

# Deploy Quarkus Flow app
kubectl apply -f k8s/kafka/quarkus-flow-app.yaml
kubectl wait --for=condition=ready pod -l app=quarkus-flow-app -n data-index --timeout=120s

# Deploy FluentBit (outputs to Kafka)
kubectl apply -f k8s/kafka/fluent-bit.yaml
kubectl wait --for=condition=ready pod -l app=fluent-bit -n data-index --timeout=60s

# Deploy Event Processor (Kafka mode)
kubectl apply -f k8s/kafka/event-processor.yaml
kubectl wait --for=condition=ready pod -l app=event-processor -n data-index --timeout=60s

# Deploy Data Index Service
kubectl apply -f k8s/kafka/data-index-service.yaml
kubectl wait --for=condition=ready pod -l app=data-index-service -n data-index --timeout=60s

echo "✅ Kafka mode deployed"

# Execute workflows
echo "▶️  Executing workflows..."
kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/simple-set \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice"}'

sleep 2

kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/test-http-failure \
  -H 'Content-Type: application/json' \
  -d '{"url": "http://invalid"}'

# Wait for real-time processing
echo "⏳ Waiting for event processing..."
sleep 20

echo "✅ Kafka mode ready for validation"
```

**Validation Script**: Similar to PostgreSQL, but uses port 8082 and outputs to `/tmp/results/kafka-mode.json`

---

### Mode 3: Elasticsearch

**Data Flow:**
```
Quarkus Flow (pod)
        ↓ writes logs to HostPath volume
FluentBit (DaemonSet)
        ↓ reads HostPath, parses CloudEvents
Elasticsearch raw index (workflow-events-raw)
        ↓
ES Transform (automatic processing every 10s)
        ↓ aggregates and normalizes
Elasticsearch normalized indices (workflow-instances, task-executions)
        ↓
Data Index Service (GraphQL API with ES storage)
```

**Key Differences from Modes 1 & 2:**

1. **Infrastructure**:
   - Elasticsearch cluster (single node for testing)
   - ES Transform pipelines (no Java event processor)

2. **Event Processing**:
   - ES Transform does the work (not Java code)
   - Transform frequency: 10 seconds
   - Automatic checkpointing

3. **Storage**:
   - Raw events → `workflow-events-raw` index
   - Normalized data → `workflow-instances` and `task-executions` indices
   - Flattened field types for JSON input/output

4. **FluentBit**:
   - Output plugin: `es`
   - Index: `workflow-events-raw`
   - Generate document IDs from CloudEvent IDs

**Deployment Script**: `scripts/deploy-elasticsearch.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🔍 Deploying Elasticsearch Mode..."

kind create cluster --name data-index-es --config kind/cluster-config.yaml
kind load docker-image data-index-test/quarkus-flow-app:test --name data-index-es
kind load docker-image data-index-test/data-index-service:test --name data-index-es

kubectl create namespace data-index

# Deploy Elasticsearch
kubectl apply -f k8s/elasticsearch/elasticsearch.yaml
kubectl wait --for=condition=ready pod -l app=elasticsearch -n data-index --timeout=180s

# Create indices with mappings
kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X PUT localhost:9200/workflow-events-raw \
  -H 'Content-Type: application/json' \
  -d @/config/workflow-events-raw-mapping.json

kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X PUT localhost:9200/workflow-instances \
  -H 'Content-Type: application/json' \
  -d @/config/workflow-instances-mapping.json

kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X PUT localhost:9200/task-executions \
  -H 'Content-Type: application/json' \
  -d @/config/task-executions-mapping.json

# Deploy and start ES Transforms
kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X PUT localhost:9200/_transform/workflow-events-to-instances \
  -H 'Content-Type: application/json' \
  -d @/config/workflow-transform.json

kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X POST localhost:9200/_transform/workflow-events-to-instances/_start

kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X PUT localhost:9200/_transform/task-events-to-executions \
  -H 'Content-Type: application/json' \
  -d @/config/task-transform.json

kubectl exec -n data-index deployment/elasticsearch -- \
  curl -X POST localhost:9200/_transform/task-events-to-executions/_start

# Deploy Quarkus Flow app
kubectl apply -f k8s/elasticsearch/quarkus-flow-app.yaml
kubectl wait --for=condition=ready pod -l app=quarkus-flow-app -n data-index --timeout=120s

# Deploy FluentBit (outputs to ES)
kubectl apply -f k8s/elasticsearch/fluent-bit.yaml
kubectl wait --for=condition=ready pod -l app=fluent-bit -n data-index --timeout=60s

# Deploy Data Index Service (ES storage)
kubectl apply -f k8s/elasticsearch/data-index-service.yaml
kubectl wait --for=condition=ready pod -l app=data-index-service -n data-index --timeout=60s

echo "✅ Elasticsearch mode deployed"

# Execute workflows
echo "▶️  Executing workflows..."
kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/simple-set \
  -H 'Content-Type: application/json' \
  -d '{"name": "Alice"}'

sleep 2

kubectl exec -n data-index deployment/quarkus-flow-app -- \
  curl -X POST http://localhost:8080/flow/test-http-failure \
  -H 'Content-Type: application/json' \
  -d '{"url": "http://invalid"}'

# Wait for ES Transform processing (10s frequency + 30s delay)
echo "⏳ Waiting for ES Transform processing..."
sleep 45

echo "✅ Elasticsearch mode ready for validation"
```

**Validation Script**: Similar to other modes, uses port 8083 and outputs to `/tmp/results/elasticsearch-mode.json`

---

## Phase 4: CI/CD Integration

### GitHub Actions Workflow

**File**: `.github/workflows/data-index-e2e-tests.yml`

**Strategy**: Parallel execution for maximum efficiency
1. Build images once (sequential)
2. Deploy all 3 modes in parallel
3. Validate all 3 modes in parallel
4. Compare results (sequential)

```yaml
name: Data Index E2E Tests

on:
  push:
    branches: [main]
    paths:
      - 'data-index/**'
  pull_request:
    branches: [main]
    paths:
      - 'data-index/**'
  workflow_dispatch:

jobs:
  # Job 1: Build all container images
  build-images:
    runs-on: ubuntu-latest
    timeout-minutes: 20
    steps:
      - uses: actions/checkout@v4
      
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: 'maven'
      
      - name: Build images
        run: |
          cd data-index-integration-tests
          ./scripts/build-images.sh
      
      - name: Save images
        run: |
          docker save data-index-test/quarkus-flow-app:test -o /tmp/quarkus-flow-app.tar
          docker save data-index-test/data-index-service:test -o /tmp/data-index-service.tar
          docker save data-index-test/data-index-event-processor:test -o /tmp/data-index-event-processor.tar
      
      - name: Upload images
        uses: actions/upload-artifact@v4
        with:
          name: docker-images
          path: /tmp/*.tar
          retention-days: 1

  # Job 2: Test PostgreSQL mode
  test-postgresql:
    needs: build-images
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      
      - name: Download images
        uses: actions/download-artifact@v4
        with:
          name: docker-images
          path: /tmp/
      
      - name: Load images
        run: |
          docker load -i /tmp/quarkus-flow-app.tar
          docker load -i /tmp/data-index-service.tar
          docker load -i /tmp/data-index-event-processor.tar
      
      - name: Install KIND
        uses: helm/kind-action@v1
        with:
          install_only: true
      
      - name: Deploy PostgreSQL mode
        run: |
          cd data-index-integration-tests
          ./scripts/deploy-postgresql.sh
      
      - name: Validate PostgreSQL mode
        run: |
          cd data-index-integration-tests
          mkdir -p /tmp/results
          ./scripts/validate-postgresql.sh
      
      - name: Upload results
        uses: actions/upload-artifact@v4
        with:
          name: postgresql-results
          path: /tmp/results/postgresql-mode.json
          retention-days: 1
      
      - name: Cleanup
        if: always()
        run: kind delete cluster --name data-index-pg

  # Job 3: Test Kafka mode
  test-kafka:
    needs: build-images
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      
      - name: Download images
        uses: actions/download-artifact@v4
        with:
          name: docker-images
          path: /tmp/
      
      - name: Load images
        run: |
          docker load -i /tmp/quarkus-flow-app.tar
          docker load -i /tmp/data-index-service.tar
          docker load -i /tmp/data-index-event-processor.tar
      
      - name: Install KIND
        uses: helm/kind-action@v1
        with:
          install_only: true
      
      - name: Deploy Kafka mode
        run: |
          cd data-index-integration-tests
          ./scripts/deploy-kafka.sh
      
      - name: Validate Kafka mode
        run: |
          cd data-index-integration-tests
          mkdir -p /tmp/results
          ./scripts/validate-kafka.sh
      
      - name: Upload results
        uses: actions/upload-artifact@v4
        with:
          name: kafka-results
          path: /tmp/results/kafka-mode.json
          retention-days: 1
      
      - name: Cleanup
        if: always()
        run: kind delete cluster --name data-index-kafka

  # Job 4: Test Elasticsearch mode
  test-elasticsearch:
    needs: build-images
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4
      
      - name: Download images
        uses: actions/download-artifact@v4
        with:
          name: docker-images
          path: /tmp/
      
      - name: Load images
        run: |
          docker load -i /tmp/quarkus-flow-app.tar
          docker load -i /tmp/data-index-service.tar
      
      - name: Install KIND
        uses: helm/kind-action@v1
        with:
          install_only: true
      
      - name: Deploy Elasticsearch mode
        run: |
          cd data-index-integration-tests
          ./scripts/deploy-elasticsearch.sh
      
      - name: Validate Elasticsearch mode
        run: |
          cd data-index-integration-tests
          mkdir -p /tmp/results
          ./scripts/validate-elasticsearch.sh
      
      - name: Upload results
        uses: actions/upload-artifact@v4
        with:
          name: elasticsearch-results
          path: /tmp/results/elasticsearch-mode.json
          retention-days: 1
      
      - name: Cleanup
        if: always()
        run: kind delete cluster --name data-index-es

  # Job 5: Compare results
  compare-results:
    needs: [test-postgresql, test-kafka, test-elasticsearch]
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@v4
      
      - name: Download all results
        uses: actions/download-artifact@v4
        with:
          path: /tmp/all-results/
      
      - name: Install jq
        run: sudo apt-get install -y jq
      
      - name: Compare results
        run: |
          cd data-index-integration-tests
          
          # Copy results to expected location
          mkdir -p /tmp/results
          cp /tmp/all-results/postgresql-results/postgresql-mode.json /tmp/results/
          cp /tmp/all-results/kafka-results/kafka-mode.json /tmp/results/
          cp /tmp/all-results/elasticsearch-results/elasticsearch-mode.json /tmp/results/
          
          # Run validation
          ./scripts/validate-results.sh
      
      - name: Upload comparison failure details
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: comparison-failure
          path: /tmp/results/*.json
          retention-days: 7
```

### Local Development Scripts

**Master Script**: `scripts/run-all-tests.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Data Index E2E Tests - Full Parallel Run"

# Step 1: Build all images
./scripts/build-images.sh

# Step 2: Create results directory
mkdir -p /tmp/results

# Step 3: Deploy all 3 modes in parallel
echo ""
echo "🚀 Deploying all modes in parallel..."
./scripts/deploy-postgresql.sh > /tmp/pg-deploy.log 2>&1 &
PG_PID=$!

./scripts/deploy-kafka.sh > /tmp/kafka-deploy.log 2>&1 &
KAFKA_PID=$!

./scripts/deploy-elasticsearch.sh > /tmp/es-deploy.log 2>&1 &
ES_PID=$!

# Wait for all deployments
echo "⏳ Waiting for deployments to complete..."
wait $PG_PID || { echo "❌ PostgreSQL deployment failed"; cat /tmp/pg-deploy.log; exit 1; }
wait $KAFKA_PID || { echo "❌ Kafka deployment failed"; cat /tmp/kafka-deploy.log; exit 1; }
wait $ES_PID || { echo "❌ Elasticsearch deployment failed"; cat /tmp/es-deploy.log; exit 1; }

echo "✅ All modes deployed successfully"

# Step 4: Validate all modes in parallel
echo ""
echo "🔍 Validating all modes in parallel..."
./scripts/validate-postgresql.sh > /tmp/pg-validate.log 2>&1 &
PG_VAL_PID=$!

./scripts/validate-kafka.sh > /tmp/kafka-validate.log 2>&1 &
KAFKA_VAL_PID=$!

./scripts/validate-elasticsearch.sh > /tmp/es-validate.log 2>&1 &
ES_VAL_PID=$!

# Wait for all validations
echo "⏳ Waiting for validations to complete..."
wait $PG_VAL_PID || { echo "❌ PostgreSQL validation failed"; cat /tmp/pg-validate.log; exit 1; }
wait $KAFKA_VAL_PID || { echo "❌ Kafka validation failed"; cat /tmp/kafka-validate.log; exit 1; }
wait $ES_VAL_PID || { echo "❌ Elasticsearch validation failed"; cat /tmp/es-validate.log; exit 1; }

echo "✅ All validations complete"

# Step 5: Compare results
echo ""
echo "⚖️  Comparing results across all modes..."
./scripts/validate-results.sh

# Step 6: Cleanup
echo ""
echo "🧹 Cleaning up clusters..."
kind delete cluster --name data-index-pg &
kind delete cluster --name data-index-kafka &
kind delete cluster --name data-index-es &
wait

echo ""
echo "🎉 All tests passed!"
```

**Debug Script**: `scripts/debug-mode.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-postgresql}"

if [[ ! "$MODE" =~ ^(postgresql|kafka|elasticsearch)$ ]]; then
    echo "Usage: $0 [postgresql|kafka|elasticsearch]"
    exit 1
fi

echo "🔍 Starting debug mode for: $MODE"

# Deploy the mode
./scripts/deploy-${MODE}.sh

echo ""
echo "✅ Debug cluster ready!"
echo ""
echo "Cluster: data-index-${MODE}"
echo "Context: kind-data-index-${MODE}"
echo ""
echo "Useful commands:"
echo "  kubectl get pods -n data-index"
echo "  kubectl logs -n data-index -l app=quarkus-flow-app -f"
echo "  kubectl logs -n data-index -l app=fluent-bit -f"
echo "  kubectl logs -n data-index -l app=event-processor -f"
echo "  kubectl logs -n data-index -l app=data-index-service -f"
echo ""
echo "To cleanup:"
echo "  kind delete cluster --name data-index-${MODE}"
```

**Result Validation Script**: `scripts/validate-results.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

RESULTS_DIR="${1:-/tmp/results}"

if ! command -v jq &> /dev/null; then
    echo "❌ jq not found. Install with: brew install jq (macOS) or apt-get install jq (Linux)"
    exit 1
fi

echo "🔍 Validating results from: $RESULTS_DIR"

# Normalize JSON function
# Removes timestamp precision differences and sorts keys
normalize_json() {
    jq --sort-keys '
        walk(
            if type == "object" then
                with_entries(
                    if .key | endswith("Time") or .key == "timestamp" then
                        .value |= (
                            if type == "number" then
                                (. / 1000 | floor * 1000)  # Truncate to seconds
                            else
                                .
                            end
                        )
                    else
                        .
                    end
                )
            else
                .
            end
        )
    ' "$1"
}

# Normalize all results
normalize_json "$RESULTS_DIR/postgresql-mode.json" > /tmp/pg-normalized.json
normalize_json "$RESULTS_DIR/kafka-mode.json" > /tmp/kafka-normalized.json
normalize_json "$RESULTS_DIR/elasticsearch-mode.json" > /tmp/es-normalized.json

# Compare PostgreSQL vs Kafka
echo "Comparing PostgreSQL vs Kafka..."
if diff /tmp/pg-normalized.json /tmp/kafka-normalized.json; then
    echo "✅ PostgreSQL and Kafka results match"
else
    echo "❌ PostgreSQL and Kafka results differ"
    echo "See: /tmp/pg-normalized.json vs /tmp/kafka-normalized.json"
    exit 1
fi

# Compare PostgreSQL vs Elasticsearch
echo "Comparing PostgreSQL vs Elasticsearch..."
if diff /tmp/pg-normalized.json /tmp/es-normalized.json; then
    echo "✅ PostgreSQL and Elasticsearch results match"
else
    echo "❌ PostgreSQL and Elasticsearch results differ"
    echo "See: /tmp/pg-normalized.json vs /tmp/es-normalized.json"
    exit 1
fi

echo ""
echo "🎉 All modes produce identical results!"
```

---

## Test Expansion Strategy

The design supports easy expansion for new test scenarios:

### Adding New Workflow Scenarios

**Steps:**
1. Create new workflow in `workflow-runtime/src/main/flow/new-scenario.yaml`
2. Add workflow execution to deployment scripts (execute via curl)
3. No changes needed to validation scripts (they query all workflows)

**Example scenarios to add later:**
- Multi-task workflows (sequential tasks)
- Parallel task execution
- Conditional branching
- Loop/iteration patterns
- Long-running workflows (wait states)
- Workflow with variables and expressions

### Adding New GraphQL Queries

**Steps:**
1. Define query in a shared constants file (e.g., `queries.sh`)
2. Execute query in validation scripts
3. Add to result comparison

**Example queries to add later:**
- Filter workflows by status
- Filter by time range
- Query task executions
- Count workflows by name
- Pagination testing

### Adding New Assertions

**Steps:**
1. Add validation logic to `validate-results.sh`
2. Check specific fields (status, timestamps, input/output)
3. Validate business logic (e.g., error workflows have error status)

### Adding New Deployment Modes

**Steps:**
1. Create `k8s/<mode>/` directory with manifests
2. Create `scripts/deploy-<mode>.sh`
3. Create `scripts/validate-<mode>.sh`
4. Add to `run-all-tests.sh` parallel execution
5. Add to GHA workflow as new job

---

## Success Criteria

### Phase 1 (Cleanup)
✅ All 9 modules audited  
✅ Dead code removed (zero unused classes, deprecated code, non-functional tests)  
✅ `mvn clean verify` passes on all modules  
✅ One commit per module with clear documentation  
✅ No regressions in existing functionality

### Phase 2 (Images & Structure)
✅ Three container images build successfully  
✅ Test workflows execute locally  
✅ KIND cluster configuration works  
✅ Build script completes in <5 minutes

### Phase 3 (Topology)
✅ All 3 modes deploy successfully in KIND  
✅ Quarkus Flow executes workflows in cluster  
✅ FluentBit captures and forwards logs  
✅ Event processing completes (polling, Kafka, ES Transform)  
✅ GraphQL queries return expected data  
✅ All 3 modes produce identical results

### Phase 4 (CI/CD)
✅ GHA workflow runs on every push/PR  
✅ All 3 modes test in parallel  
✅ Result comparison passes  
✅ Total workflow time <30 minutes  
✅ Clear pass/fail status in PR checks

---

## Timeline

**Phase 1: Module Cleanup** (1 week)
- Days 1-2: Modules 1-3 (integration-tests, common, model)
- Days 3-4: Modules 4-6 (storage-common, storage-postgresql, storage-elasticsearch)
- Days 5-7: Modules 7-9 (event-processor, service, graphql)

**Phase 2: Image Build & Structure** (1 week)
- Days 1-2: Quarkus Flow app setup (workflows, REST endpoints, logging)
- Days 3-4: Image build configuration (Jib, POMs)
- Days 5: KIND cluster configuration and build scripts
- Days 6-7: Local testing and refinement

**Phase 3: Topology Deployment** (1 week)
- Days 1-2: PostgreSQL mode (manifests, deployment, validation)
- Days 3-4: Kafka mode (Zookeeper, Kafka, manifests, validation)
- Days 5-7: Elasticsearch mode (ES, transforms, manifests, validation)

**Phase 4: CI/CD Integration** (1 week)
- Days 1-2: GHA workflow creation
- Days 3-4: Parallel execution and result comparison
- Days 5: Testing and debugging
- Days 6-7: Documentation and cleanup

**Total: 4 weeks**

---

## Risk Mitigation

### Risk: Cleanup breaks existing functionality
**Mitigation**: 
- Run `mvn clean verify` after each module
- Keep existing integration tests passing
- One module per commit for easy rollback
- Git branch for investigation of questionable code

### Risk: KIND clusters consume too much memory
**Mitigation**:
- Minimal cluster config (1 node, 2GB limit)
- Sequential cleanup (delete cluster after each mode)
- GHA uses standard runners (7GB available)

### Risk: Tests are flaky (timing issues)
**Mitigation**:
- Generous wait times (30s for polling, 45s for ES Transform)
- Use `kubectl wait --for=condition=ready` for reliable pod startup
- Retry logic in validation scripts

### Risk: Result comparison fails due to timestamp precision
**Mitigation**:
- Normalization function truncates timestamps to seconds
- Compare logical fields (id, name, status) first
- Document known differences in validation script

### Risk: Parallel execution causes resource contention
**Mitigation**:
- Unique cluster names per mode
- Unique ports per mode (8081, 8082, 8083)
- Unique HostPath directories
- GHA provides isolated runners per job

---

## Open Questions

None - design is complete and ready for implementation.

---

## References

- [Data Index Documentation](../README.md)
- [Architecture Summary](../ARCHITECTURE-SUMMARY.md)
- [FluentBit Configuration Guide](../FLUENTBIT-CONFIGURATION.md)
- [Elasticsearch Mode Complete](../ELASTICSEARCH-MODE-DEPLOYMENT-COMPLETE.md)
- [Event Processor Observability](../EVENT-PROCESSOR-OBSERVABILITY.md)
- [Quarkus Flow Documentation](https://docs.quarkiverse.io/quarkus-flow/dev/)
- [KIND Documentation](https://kind.sigs.k8s.io/)
- [FluentBit Documentation](https://docs.fluentbit.io/)
