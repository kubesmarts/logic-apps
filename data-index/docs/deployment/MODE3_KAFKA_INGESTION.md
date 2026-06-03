# MODE 3 (Kafka) Deployment Guide

**Status:** Production Ready  
**Last Updated:** 2026-05-29

---

## Overview

MODE 3 is a Kafka-based event ingestion service that provides an alternative to the FluentBit + PostgreSQL triggers approach (MODE 1). This guide covers deployment, configuration, and troubleshooting for production environments.

**Event Pipeline:**
```
Quarkus Flow → Kafka (CloudEvents) → Kafka Ingestion Service → PostgreSQL → GraphQL API
```

**Use MODE 3 when:**
- Kafka infrastructure already exists in your environment
- Security requirements demand events not be written to disk (credit cards, PII, etc.)
- You need encrypted transport (SSL/SASL_SSL)
- Direct stream processing is preferred over log-based ingestion
- You want to leverage Kafka's at-least-once delivery guarantees

---

## Architecture

### Components

1. **Quarkus Flow** - Publishes workflow/task lifecycle events as CloudEvents to Kafka
2. **Kafka Broker** - Topic: `flow-lifecycle-out` (raw CloudEvents)
3. **Data Index Ingestion Service** - Consumes CloudEvents and normalizes to PostgreSQL
4. **PostgreSQL** - Normalized tables: `workflow_instances`, `task_instances`
5. **Data Index GraphQL API** - Query service (same as MODE 1)

### Processing Flow

```
CloudEvent Validation
    ↓
Event Type Routing (workflow vs task)
    ↓
Mapper (CloudEvent → WorkflowInstanceEvent / TaskExecutionEvent)
    ↓
WorkflowEventProcessor / TaskExecutionProcessor
    ↓
JDBC UPSERT with Field-Level Idempotency
    ↓
PostgreSQL normalized tables
    ↓
(failed records → data-index-events-dlq topic)
```

### Key Design Decisions

| Aspect | Decision | Rationale |
|--------|----------|-----------|
| **Event Format** | CloudEvents (v1.0) | Standard, platform-independent, includes metadata |
| **Database Access** | JDBC (not JPA) | Direct SQL for performance, UPSERT idempotency |
| **Normalization** | Java processors (not SQL triggers) | Same idempotency logic as MODE 1, but in-service |
| **Error Handling** | Dead-letter queue | Failed events captured for replay/investigation |
| **Task Identity** | Composite key `(instance_id, task_position)` | Handles Quarkus Flow's changing taskExecutionId per event |
| **FK Recovery** | SavePoint + placeholder workflow | Handles out-of-order task events (before workflow) |

---

## Deployment

### Kubernetes Manifest

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: data-index
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-ingestion-kafka
  namespace: data-index
spec:
  replicas: 1  # Single instance recommended; Kafka consumer group handles scaling
  selector:
    matchLabels:
      app: data-index-ingestion-kafka
  template:
    metadata:
      labels:
        app: data-index-ingestion-kafka
    spec:
      containers:
      - name: kafka-ingestion
        image: kubesmarts/data-index-ingestion-kafka-service:999-SNAPSHOT
        imagePullPolicy: IfNotPresent
        ports:
        - containerPort: 8080
          name: http
        env:
        # Kafka Configuration
        - name: KAFKA_BOOTSTRAP_SERVERS
          valueFrom:
            configMapKeyRef:
              name: kafka-config
              key: bootstrap.servers
        # Database Configuration
        - name: QUARKUS_DATASOURCE_JDBC_URL
          valueFrom:
            secretKeyRef:
              name: database-credentials
              key: jdbc-url
        - name: QUARKUS_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: database-credentials
              key: username
        - name: QUARKUS_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: database-credentials
              key: password
        # Optional: Application Configuration
        - name: QUARKUS_LOG_LEVEL
          value: "INFO"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
          timeoutSeconds: 5
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          timeoutSeconds: 5
        resources:
          requests:
            cpu: 500m
            memory: 512Mi
          limits:
            cpu: 2000m
            memory: 2Gi
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-ingestion-kafka
  namespace: data-index
spec:
  selector:
    app: data-index-ingestion-kafka
  ports:
  - port: 8080
    targetPort: 8080
    name: http
  type: ClusterIP
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: kafka-config
  namespace: data-index
data:
  bootstrap.servers: "kafka.kafka.svc.cluster.local:9092"
---
apiVersion: v1
kind: Secret
metadata:
  name: database-credentials
  namespace: data-index
type: Opaque
stringData:
  jdbc-url: "jdbc:postgresql://postgresql.data-index.svc.cluster.local:5432/data-index"
  username: "data-index"
  password: "CHANGE_ME"
```

### Configuration

#### Required Environment Variables

| Variable | Required | Default | Notes |
|----------|----------|---------|-------|
| `KAFKA_BOOTSTRAP_SERVERS` | Yes (prod) | localhost:29092 (dev) | Comma-separated broker URLs |
| `QUARKUS_DATASOURCE_JDBC_URL` | Yes (prod) | jdbc:h2:mem:test (dev) | PostgreSQL JDBC connection string |
| `QUARKUS_DATASOURCE_USERNAME` | Yes (prod) | (dev services) | Database username |
| `QUARKUS_DATASOURCE_PASSWORD` | Yes (prod) | (dev services) | Database password |

#### Optional Configuration

| Property                                                          | Default | Description |
|-------------------------------------------------------------------|---------|-------------|
| `MP_MESSAGING_INCOMING_DATA_INDEX_EVENTS_TOPIC`                   | `flow-lifecycle-out` | Kafka topic name |
| `MP_MESSAGING_INCOMING_DATA_INDEX_EVENTS_GROUP_ID`                | `data-index-ingestion` | Kafka consumer group |
| `MP_MESSAGING_INCOMING_DATA_INDEX_EVENTS_AUTO_OFFSET_RESET`       | `earliest` | Offset reset strategy |
| `MP_MESSAGING_INCOMING_DATA_INDEX_EVENTS_RETRY_ATTEMPTS`          | `2` | Retries before DLQ |
| `MP_MESSAGING_INCOMING_DATA_INDEX_EVENTS_DEAD_LETTER_QUEUE_TOPIC` | `data-index-events-dlq` | Dead letter queue topic |
| `quarkus.log.level`                                               | `INFO` | Logging level |

#### Kafka Security (Optional)

For SASL/SSL authentication, add to application.properties or environment:

```properties
# SASL Configuration
mp.messaging.incoming.data-index-events.security.protocol=SASL_SSL
mp.messaging.incoming.data-index-events.sasl.mechanism=PLAIN
mp.messaging.incoming.data-index-events.sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="<username>" password="<password>";

# SSL Configuration
mp.messaging.incoming.data-index-events.ssl.truststore.location=/etc/ssl/certs/kafka-truststore.jks
mp.messaging.incoming.data-index-events.ssl.truststore.password=<password>
```

---

## Kafka Topic Setup

### Required Topics

1. **`flow-lifecycle-out`** - Main event topic (published by Quarkus Flow)
2. **`data-index-events-dlq`** - Dead-letter queue for failed records

### Auto-Creation

In development, topics are auto-created when the cluster runs with:
```properties
KAFKA_AUTO_CREATE_TOPICS_ENABLE=true
```

In production, pre-create topics:

```bash
# Create main topic (replicas=3 for HA, partitions=3 for parallelism)
kafka-topics.sh --create \
  --bootstrap-server kafka.kafka.svc.cluster.local:9092 \
  --topic flow-lifecycle-out \
  --replication-factor 3 \
  --partitions 3 \
  --config retention.ms=86400000 \  # 24 hours
  --config min.insync.replicas=2

# Create DLQ topic
kafka-topics.sh --create \
  --bootstrap-server kafka.kafka.svc.cluster.local:9092 \
  --topic data-index-events-dlq \
  --replication-factor 3 \
  --partitions 1 \
  --config retention.ms=604800000  # 7 days (for investigation)
```

---

## Event Format

### CloudEvent (v1.0)

```json
{
  "specversion": "1.0",
  "type": "io.serverlessworkflow.workflow.started.v1",
  "source": "/workflow/executions/01KSGKY66DMS0KPPMFMMR3BJZX",
  "id": "event-123",
  "time": "2026-05-25T22:40:10.676900Z",
  "datacontenttype": "application/json",
  "data": {
    "instanceId": "01KSGKY66DMS0KPPMFMMR3BJZX",
    "workflowName": "order-processing",
    "workflowNamespace": "org.acme",
    "workflowVersion": "1.0.0",
    "status": "RUNNING",
    "startTime": "2026-05-25T19:40:10.676802-03:00",
    "lastUpdateTime": "2026-05-25T19:40:10.676802-03:00",
    "input": { "orderId": "ORD-789" }
  }
}
```

### Supported Event Types

| Event Type | Processor | Field Updates |
|------------|-----------|----------------|
| `io.serverlessworkflow.workflow.started` | WorkflowEventProcessor | start, status→RUNNING |
| `io.serverlessworkflow.workflow.completed` | WorkflowEventProcessor | end, status→COMPLETED, output |
| `io.serverlessworkflow.workflow.faulted` | WorkflowEventProcessor | end, status→FAULTED, error |
| `io.serverlessworkflow.workflow.suspended` | WorkflowEventProcessor | status→SUSPENDED |
| `io.serverlessworkflow.workflow.cancelled` | WorkflowEventProcessor | status→CANCELLED |
| `io.serverlessworkflow.task.started` | TaskExecutionProcessor | start, status→RUNNING |
| `io.serverlessworkflow.task.completed` | TaskExecutionProcessor | end, status→COMPLETED, output |
| `io.serverlessworkflow.task.faulted` | TaskExecutionProcessor | end, status→FAULTED, error |
| `io.serverlessworkflow.task.suspended` | TaskExecutionProcessor | status→SUSPENDED |
| `io.serverlessworkflow.task.cancelled` | TaskExecutionProcessor | status→CANCELLED |

### Timestamp Handling

All timestamp fields are automatically converted to **UTC OffsetDateTime** and stored as `TIMESTAMP WITH TIME ZONE` in PostgreSQL.

Accepted formats:
- **ISO-8601 with offset** (recommended): `2026-05-25T19:40:10.676802-03:00`
- **ISO-8601 UTC**: `2026-05-25T22:40:10.676900Z`
- **Unix epoch seconds**: `1747486200`

---

## Idempotency Guarantees

MODE 3 implements **field-level idempotency** to handle out-of-order and duplicate events:

### Immutable Fields (First Value Wins)

Once set, never updated:
- `workflow.start`, `workflow.input`, `workflow.name`, `workflow.version`, `workflow.namespace`
- `task.start`, `task.input`, `task.taskName`, `task.taskPosition`

### Terminal Fields (Last Non-Null Wins)

Updated only if incoming event is newer (based on `last_event_time`):
- `workflow.end`, `workflow.output`, `workflow.lastUpdate`
- `task.end`, `task.output`
- Error fields: `errorType`, `errorTitle`, `errorDetail`, `errorStatus`, `errorInstance`

### Status Field

Updated based on timestamp and precedence:
- Terminal states win: `COMPLETED`, `FAULTED`, `CANCELLED` > `RUNNING` > `CREATED`
- If incoming event is newer, status is updated
- If incoming event is older, status is preserved

### Example: Out-of-Order Events

```
Event 1 (t=10:00): workflow.started
  → INSERT: id=wf-1, status=RUNNING, start=10:00, last_event_time=10:00

Event 2 (t=10:05): workflow.completed, output={result: "success"}
  → UPDATE: status=COMPLETED, end=10:05, output={result: "success"}, last_event_time=10:05

Event 3 (t=10:01): workflow.completed, output={result: "failure"}  [OUT OF ORDER]
  → SKIP: 10:01 < 10:05, so old status ignored, output not overwritten
```

---

## Error Handling

### Failed Event Processing

When an event cannot be processed (deserialization error, database constraint violation, etc.):

1. **Exception thrown**: `ProcessEventFailedException` wraps the error
2. **Dead-letter queue**: Record automatically sent to `data-index-events-dlq` topic
3. **Consumer continues**: Service immediately processes next message (fail-fast disabled)
4. **Monitoring**: Check DLQ topic to inspect and replay failed events

### Task Before Workflow (Foreign Key Recovery)

If a task event arrives before its parent workflow:

```
1. Task event consumed → INSERT fails (FK constraint violation)
2. Savepoint rolled back
3. Placeholder workflow created: INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
4. Task event retried → INSERT succeeds (FK satisfied)
5. Later: workflow.started event arrives → UPDATE placeholder with full data
```

This ensures no task events are lost due to event ordering issues.

---

## Monitoring

### Health Checks

```bash
# Liveness (service is running)
curl http://localhost:8080/q/health/live

# Readiness (ready to consume events)
curl http://localhost:8080/q/health/ready

# Full health summary
curl http://localhost:8080/q/health
```

### Prometheus Metrics

```bash
# View all metrics
curl http://localhost:8080/q/metrics

# Key metrics to monitor
kafka_messages_consumed_total     # Events processed
kafka_consumer_lag               # Messages behind
agroal_pool_size_current         # Active DB connections
```

### Kubernetes Monitoring

```bash
# Follow service logs
kubectl logs -f deployment/data-index-ingestion-kafka -n data-index

# Search for errors
kubectl logs deployment/data-index-ingestion-kafka -n data-index | grep ERROR

# Check DLQ processing
kubectl logs deployment/data-index-ingestion-kafka -n data-index | grep "dead-letter"

# Watch pod status
kubectl get pods -n data-index -w
```

### Dead-Letter Queue Inspection

```bash
# Check DLQ topic for failed events
kafka-console-consumer.sh \
  --bootstrap-server kafka.kafka.svc.cluster.local:9092 \
  --topic data-index-events-dlq \
  --from-beginning \
  --property print.key=true \
  --max-messages 10

# Extract a failed event for investigation
kafka-console-consumer.sh \
  --bootstrap-server kafka.kafka.svc.cluster.local:9092 \
  --topic data-index-events-dlq \
  --from-beginning \
  --property print.key=true \
  --max-messages 1 | jq '.data'
```

---

## Troubleshooting

### Service won't start

**Symptom:** Pod in CrashLoopBackOff  
**Check:**
```bash
kubectl logs deployment/data-index-ingestion-kafka -n data-index
```

**Common causes:**
- PostgreSQL unreachable → Check `QUARKUS_DATASOURCE_JDBC_URL` and network connectivity
- Kafka unreachable → Check `KAFKA_BOOTSTRAP_SERVERS` and Kafka broker health
- Database schema missing → Run Flyway migrations before starting service

### Events not being consumed

**Symptom:** No events in PostgreSQL, Kafka topic has messages  
**Check:**
```bash
# Verify readiness
kubectl get pods -n data-index | grep data-index-ingestion-kafka

# Check logs for errors
kubectl logs deployment/data-index-ingestion-kafka -n data-index | grep -i "error\|exception"

# Verify Kafka broker connectivity
kubectl exec -it pod/data-index-ingestion-kafka -n data-index -- \
  kafka-broker-api-versions.sh --bootstrap-server KAFKA_BOOTSTRAP_SERVERS
```

**Common causes:**
- Consumer group has lag → Check `kafka_consumer_lag` metric
- Topic name mismatch → Verify `mp.messaging.incoming.data-index-events.topic`
- Kafka authentication failures → Check SASL/SSL configuration

### Data not in PostgreSQL

**Symptom:** Kafka has events, but workflow_instances table is empty  
**Check:**
```bash
# Verify table exists
kubectl exec -it pod/postgresql -- psql -U data-index -d data-index -c "\dt workflow_instances"

# Check for data
kubectl exec -it pod/postgresql -- psql -U data-index -d data-index -c "SELECT COUNT(*) FROM workflow_instances"

# Check DLQ for failed events
kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic data-index-events-dlq --max-messages 5
```

**Common causes:**
- Database connection pool exhausted → Increase `quarkus.datasource.max-size`
- Unique constraint violations → Check DLQ for details
- FK constraint violations on first attempt → Expected, savepoint/retry should handle

### DLQ messages pile up

**Symptom:** data-index-events-dlq topic growing  
**Check:**
```bash
# Count DLQ messages
kafka-run-class.sh kafka.tools.JmxTool \
  --object-name kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec,topic=data-index-events-dlq

# Inspect latest DLQ messages
kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic data-index-events-dlq --max-messages 10
```

**Common causes:**
- Malformed CloudEvents → Fix event publisher (Quarkus Flow)
- Schema mismatch → Upgrade service or downgrade event publisher
- Database unavailable → Temporarily → Events will retry and eventual succeed once DB recovers

---

## Comparison: MODE 1 vs MODE 2 vs MODE 3

| Feature | MODE 1 (FluentBit + Triggers) | MODE 2 (FluentBit + ES Transforms) | MODE 3 (Kafka) |
|---------|-------------------------------|------------------------------------|----|
| **Event Source** | Log files | Log files | Kafka topics |
| **Ingestion** | FluentBit DaemonSet | FluentBit DaemonSet | SmallRye Reactive Messaging |
| **Normalization** | PostgreSQL triggers | Elasticsearch transforms | Java processors (JDBC) |
| **Raw Storage** | `workflow_events_raw` table | `workflow-events` index | None (direct to normalized) |
| **Normalized Storage** | PostgreSQL tables | Elasticsearch indices | PostgreSQL tables |
| **GraphQL API** | QueryService (PostgreSQL) | QueryService (Elasticsearch) | QueryService (PostgreSQL) |
| **Security** | Files on disk | Files on disk | Kafka (SSL/SASL capable) |
| **Performance** | Trigger latency (~10ms) | Transform latency (~1s) | Message latency (~100ms) |
| **Scaling** | Limited by DB triggers | Unlimited (ES scales) | Kafka parallelism |
| **Query Capabilities** | Standard SQL | Full-text search, aggregations | Standard SQL |
| **DLQ** | N/A (triggers atomic) | N/A (no failures) | Yes (`data-index-events-dlq`) |

**Choose MODE 3 if:**
- ✅ Kafka already deployed in your infrastructure
- ✅ Security concern: avoid writing sensitive data to disk
- ✅ Need encrypted Kafka transport (SSL/SASL)
- ✅ Prefer stream-based ingestion
- ✅ Want to leverage Kafka's at-least-once guarantees

**Choose MODE 1 if:**
- ✅ Simplest setup (triggers are atomic, no DLQ needed)
- ✅ Low latency critical (~10ms)
- ✅ No Kafka infrastructure available
- ✅ Log-based ingestion acceptable for your use case

**Choose MODE 2 if:**
- ✅ Need full-text search capabilities
- ✅ Complex aggregations required
- ✅ Large scale (1M+ workflows)
- ✅ Multi-tenancy needs (index-per-tenant)

---

## Local Development

### Quick Start

```bash
cd data-index/data-index-ingestion/data-index-ingestion-kafka-service

# Start in dev mode (auto-starts Kafka + PostgreSQL via Dev Services)
mvn quarkus:dev

# Service runs at: http://localhost:8080
# Health: http://localhost:8080/q/health
```

### Running Integration Tests

```bash
# Run all tests
mvn test

# Run specific test
mvn test -Dtest=KafkaIngestionITest

# Run with logging
mvn test -Dquarkus.log.level=DEBUG
```

### KIND Testing

```bash
cd data-index/scripts/kind

# Setup cluster + dependencies
./setup-cluster.sh
MODE=kafka ./install-dependencies.sh

# Deploy and test
./init-database-schema.sh
./deploy-kafka-ingestion.sh
./test-mode3-e2e.sh
```

---

## References

- **Service README**: `data-index/data-index-ingestion/data-index-ingestion-kafka-service/README.md`
- **Parent Module**: `data-index/data-index-ingestion/README.md`
- **CLAUDE.md**: Full project guidelines and architecture
- **Kind Scripts**: `data-index/scripts/kind/`
- **Issue #23**: GitHub issue for MODE 3 implementation
