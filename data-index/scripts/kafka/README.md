# Kafka Scripts — MODE 3 (Kafka Ingestion)

Kubernetes manifests for the Kafka broker used in MODE 3 event ingestion.

## Overview

MODE 3 replaces FluentBit log collection with direct Kafka event streaming:

```
workflow-test-app (kafka profile)
    ↓ CloudEvents (topic: flow-lifecycle-out)
Kafka broker (this directory)
    ↓ SmallRye Reactive Messaging
data-index-ingestion-kafka-service
    ↓ JDBC UPSERT
PostgreSQL (workflow_instances, task_instances)
    ↓ JPA / Hibernate
Data Index GraphQL API
```

**No FluentBit, no log files, no raw event tables.** Events flow directly from Quarkus Flow
to the ingestion service via Kafka CloudEvents.

## Directory Structure

```
kafka/
├── README.md                    # This file
└── kubernetes/
    └── kafka.yaml               # Kafka StatefulSet + Services (KRaft, single-node)
```

## Kafka Deployment

### What is deployed

| Resource              | Name               | Namespace | Purpose                                     |
|-----------------------|--------------------|-----------|---------------------------------------------|
| StatefulSet           | `kafka`            | `kafka`   | Single-node Kafka broker + controller (KRaft) |
| Service (Headless)    | `kafka-headless`   | `kafka`   | StatefulSet DNS for pod-to-pod communication  |
| Service (ClusterIP)   | `kafka`            | `kafka`   | Stable bootstrap address for clients          |
| Service (NodePort)    | `kafka-nodeport`   | `kafka`   | External access for debugging (port 30900)    |

### Bootstrap addresses

| Context            | Address                                      |
|--------------------|----------------------------------------------|
| In-cluster clients | `kafka.kafka.svc.cluster.local:9092`         |
| KIND host (debug)  | `localhost:30900`                            |

### Topics

| Topic                  | Created by             | Description                         |
|------------------------|------------------------|-------------------------------------|
| `flow-lifecycle-out`   | Auto-created on publish | Workflow + task lifecycle CloudEvents |

Auto-creation is enabled (`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`). The topic is
created the first time `workflow-test-app` publishes an event.

## Quick Start (KIND)

```bash
cd data-index/scripts/kind

# 1. Create cluster (adds NodePort 30900 for Kafka)
./setup-cluster.sh

# 2. Install dependencies (PostgreSQL + Kafka)
MODE=kafka ./install-dependencies.sh

# 3. Initialize database schema (create workflow_instances, task_instances tables)
./init-database-schema.sh

# 4. Create Kafka topic (flow-lifecycle-out)
./create-kafka-topic.sh

# 5. Deploy query service + Kafka ingestion service
./deploy-data-index.sh kafka

# 6. Deploy workflow-test-app with Kafka profile
MODE=kafka ./deploy-workflow-app.sh

# 7. Run end-to-end test
./test-mode3-e2e.sh
```

## Manual Kafka Deployment

```bash
# Apply the manifest (creates kafka namespace + all resources)
kubectl apply -f kubernetes/kafka.yaml

# Wait for Kafka to be ready
kubectl wait --namespace kafka \
  --for=condition=ready pod/kafka-0 \
  --timeout=120s

# Verify broker is listening
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

## Debugging

### List topics

```bash
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

### Describe the flow-lifecycle-out topic

```bash
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic flow-lifecycle-out
```

### Consume messages (watch live events)

```bash
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic flow-lifecycle-out \
  --from-beginning
```

### Check consumer group lag

```bash
kubectl exec -n kafka kafka-0 -- \
  /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --group data-index-ingestion
```

### Broker logs

```bash
kubectl logs -n kafka kafka-0 -f
```

## Configuration Notes

**KRaft mode** (no ZooKeeper): the broker and controller roles run in the same process
(`KAFKA_PROCESS_ROLES=broker,controller`). This simplifies the deployment to a single
StatefulSet pod with no external coordination service.

**Single-node settings**: all replication factors are set to `1` (see `kafka.yaml`). This
is intentional — this deployment is for integration testing, not production.

**Storage**: data is persisted in a `1Gi` PersistentVolumeClaim. KIND's default storage
class provisions `hostPath` volumes, so data survives pod restarts but is lost when the
cluster is deleted.

## Related Documentation

- [Kafka Ingestion Deploy Script](../kind/deploy-kafka-ingestion.sh)
- [MODE 3 E2E Test](../kind/test-mode3-e2e.sh)
