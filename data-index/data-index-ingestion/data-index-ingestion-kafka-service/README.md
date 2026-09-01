# Data Index Kafka Ingestion Service

Standalone Quarkus service that implements **MODE 3**: Kafka-based event ingestion for Data Index.

Consumes CloudEvents from Kafka topics published by Quarkus Flow and writes normalized workflow/task data directly to PostgreSQL using JDBC UPSERT with **UTC offset timestamps**.

## MODE 3 vs MODE 1 & MODE 2

| Feature | MODE 1 (FluentBit + Triggers) | MODE 2 (FluentBit + ES Transforms) | MODE 3 (Kafka + JDBC) |
|---|---|---|---|
| **Event source** | Log files | Log files | Kafka topics |
| **Ingestion** | FluentBit DaemonSet | FluentBit DaemonSet | SmallRye Reactive Messaging |
| **Normalization** | PostgreSQL triggers | Elasticsearch transforms | Java JDBC processors |
| **Raw storage** | `workflow_events_raw` table | `workflow-events` index | None (direct to normalized) |
| **Transport security** | File system | File system | SSL/SASL_SSL supported |
| **Dead letter queue** | N/A | N/A | Yes (`data-index-events-dlq`) |
| **Timestamp format** | Converted by trigger | Converted by transform | **OffsetDateTime (UTC)** |
| **Idempotency** | SQL `COALESCE` | Painless script | SQL `COALESCE` + `last_event_time` |
| **FK recovery** | N/A (triggers atomic) | N/A (no FK) | **Savepoint + retry** |

**Use MODE 3 when:**
- Kafka infrastructure already exists
- Security requirements (no log files on disk)
- Need dead letter queue for failed events
- Direct event stream processing required
- Encrypted transport (SSL/SASL_SSL) needed

## Quick Start

### Prerequisites
- Java 17+
- Maven 3.8+
- Docker (for dev services: Kafka + PostgreSQL)

### Development

```bash
# Run in development mode (auto-starts Kafka + PostgreSQL via Dev Services)
mvn quarkus:dev

# Service runs at: http://localhost:8080
# Health checks: http://localhost:8080/q/health
# Readiness: http://localhost:8080/q/health/ready
# Liveness: http://localhost:8080/q/health/live
```

**Dev Services automatically provisions:**
- Kafka broker (RedPanda testcontainer)
- PostgreSQL database
- Required topics: `flow-lifecycle-out`, `data-index-events-dlq`
- Database schema via Flyway migrations

### KIND Cluster Setup

For testing in a local Kubernetes cluster:

```bash
# 1. Setup KIND cluster and dependencies (installs PostgreSQL + Kafka)
cd ../../scripts/kind
./setup-cluster.sh
MODE=kafka ./install-dependencies.sh

# 2. Deploy the data index query service (PostgreSQL backend)
./deploy-data-index.sh kafka

# 3. Initialize the database schema
./init-database-schema.sh

# 4. Deploy the Kafka ingestion service
./deploy-kafka-ingestion.sh

# 5. Deploy test workflow app
./deploy-workflow-app.sh

# 6. Run the end-to-end test
./test-mode3-e2e.sh
```

Topics (`flow-lifecycle-out` and the `data-index-events-dlq` dead-letter topic) are
auto-created on first publish — the Kafka cluster runs with
`KAFKA_AUTO_CREATE_TOPICS_ENABLE=true`. See `data-index/scripts/kafka/README.md` for
cluster details.

## Architecture

```
Quarkus Flow (workflow runtime)
    |
    CloudEvents to Kafka (binary or structured mode, topic: flow-lifecycle-out)
    |
    KafkaLifecycleConsumer (SmallRye Reactive Messaging - batch mode)
    |
    Manual CloudEvent reconstruction (binary: headers → CE object, structured: JSON → CE object)
    |
    CloudEvent validation + payload mapping (Mapper: CloudEvent + LifecycleEvent -> WorkflowInstance / TaskExecution)
    |
    WorkflowEventProcessor / TaskExecutionProcessor (batch processing)
    |
    WorkflowPersistence / TaskPersistence (JDBC batch UPSERT with OffsetDateTime in UTC)
    |
    workflow_instances / task_instances (normalized tables, TIMESTAMP WITH TIME ZONE)

    (records that fail processing -> dead-letter topic: data-index-events-dlq)
```

### CloudEvents Format Support

The ingestion service supports **both CloudEvents content modes**:

**Binary Mode (default)**:
- CloudEvent attributes → Kafka headers (`ce_type`, `ce_source`, `ce_time`, etc.)
- Event data payload → Kafka message body (JSON)
- Quarkus Flow default when `mp.messaging.outgoing.flow-lifecycle-out.cloud-events=false` (or unset)

**Structured Mode**:
- Full CloudEvent envelope → Kafka message body (JSON with `specversion`, `type`, `source`, `data`, etc.)
- No CloudEvent headers
- Quarkus Flow when `mp.messaging.outgoing.flow-lifecycle-out.cloud-events=true`

**Auto-detection**: The consumer inspects each record for `ce_type` header to distinguish modes.

### Why Manual CloudEvent Reconstruction?

Unlike SmallRye's automatic CloudEvent extraction (which only works in per-message mode), this service uses **batch consumption** for high throughput:

**Batch Mode Trade-offs**:
- ✅ **High throughput**: Process up to 1000 Kafka records per poll
- ✅ **Efficient DB writes**: Batch UPSERT reduces transaction overhead
- ✅ **Better for high-volume workflows**: Multiple concurrent workflows generating thousands of events/sec
- ⚠️ **Manual CloudEvent handling**: Must reconstruct CloudEvent objects from headers (binary) or JSON (structured)

**Per-Message Mode Alternative** (not used):
- ✅ Automatic CloudEvent extraction via `CloudEventMetadata`
- ❌ One DB transaction per event (high overhead at scale)
- ❌ Lower throughput under load

**Why reconstruct CloudEvent objects?**
1. **Validation**: CloudEventBuilder ensures events comply with CloudEvents spec
2. **Abstraction**: Uniform interface regardless of binary vs structured mode
3. **Future-proof**: Easy to extract additional CE attributes (`source`, `id`, extensions) if needed
4. **Currently used fields**: `time` (→ `event_timestamp`), `type` (→ event routing)

The overhead of CloudEvent reconstruction is negligible compared to DB UPSERT operations.

### Module Structure

The Kafka ingestion service is composed of two Maven modules:

1. **data-index-ingestion-kafka-processor**: Event models and processing logic
   - `data/WorkflowInstanceEvent.java`, `data/TaskExecutionEvent.java` - Internal event payloads
   - `EventProcessor<T>` - Generic processor interface
   - `WorkflowEventProcessor.java` / `TaskExecutionProcessor.java` - Processor implementations
   - `persistence/WorkflowPersistence.java` - JDBC UPSERT for workflow events
   - `persistence/TaskPersistence.java` - JDBC UPSERT for task events with FK recovery
   - `util/LifecycleEventUtils.java` - Event-type routing + status mapping
   - `ProcessEventFailedException.java` - Thrown on processing failure (triggers DLQ)

2. **data-index-ingestion-kafka-service**: Quarkus application
   - `KafkaLifecycleConsumer.java` - Batch Kafka consumer, manually reconstructs CloudEvents from headers/JSON
   - `Mapper.java` - Maps `CloudEvent` + `LifecycleEvent` to domain model (`WorkflowInstance` / `TaskExecution`)
   - `HealthChecks.java` - Kubernetes health probes
   - `application.properties` - Kafka, database, messaging, and dead-letter-queue configuration

### Processing Guarantees

- **At-least-once delivery**: Kafka offsets committed after successful DB writes
- **Out-of-order handling**: Timestamp-based idempotency (last_event_time)
- **Task before workflow**: If task arrives first, placeholder workflow created via savepoint/retry
- **Field-level idempotency**: Immutable, terminal, and status fields handled correctly
- **UTC timestamps**: All timestamps saved as OffsetDateTime in UTC (TIMESTAMP WITH TIME ZONE)
- **Dead letter queue**: Failed messages sent to `data-index-events-dlq` topic

### Idempotency Rules

**Immutable fields** (first value wins):
- `namespace`, `name`, `version` (workflow)
- `start`, `input` (workflow and task)
- `task_name`, `task_position` (task)

**Terminal fields** (last non-null wins):
- `end`, `output` (workflow and task)
- `last_update` (workflow only)
- Error fields: `error_type`, `error_title`, `error_detail`, `error_status`, `error_instance`

**Status precedence** (via last_event_time comparison):
- Events with the most recent timestamp win the status field

## Configuration

### Key Properties

| Property | Default | Description |
|---|---|---|
| `kafka.bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS` (prod) | Kafka broker URLs |
| `quarkus.datasource.jdbc.url` | (dev services) | PostgreSQL connection |
| `mp.messaging.incoming.data-index-events.topic` | `flow-lifecycle-out` | Kafka topic name |
| `mp.messaging.incoming.data-index-events.group.id` | `data-index-ingestion` | Kafka consumer group |
| `mp.messaging.incoming.data-index-events.batch` | `true` | Enable batch consumption (required for high throughput) |
| `mp.messaging.incoming.data-index-events.cloud-events` | `false` | Disable SmallRye auto-extraction (manual handling for batch mode) |
| `data-index.ingestion.db-batch-size` | `1000` | Max workflows/tasks per DB transaction |
| `mp.messaging.incoming.data-index-events.auto.offset.reset` | `earliest` | Offset reset strategy |
| `mp.messaging.outgoing.data-index-events-dlq.topic` | `data-index-events-dlq` | Dead letter queue topic |

**CloudEvents Mode Compatibility**:
- Supports both binary and structured CloudEvents (auto-detected per record)
- Producer configures mode via `mp.messaging.outgoing.flow-lifecycle-out.cloud-events=true|false`
- Consumer auto-detects by presence of `ce_type` header (binary) vs JSON envelope (structured)

See `src/main/resources/application.properties` for full configuration.

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

### Event Type Routing

- `io.serverlessworkflow.workflow.*` events -> WorkflowEventProcessor (via `LifecycleEventUtils.isWorkflow()`)
- `io.serverlessworkflow.task.*` events -> TaskExecutionProcessor (via `LifecycleEventUtils.isTask()`)

### Timestamp Formats

All timestamp fields (`startTime`, `endTime`, `lastUpdateTime`) are automatically converted to **UTC OffsetDateTime** and stored as `TIMESTAMP WITH TIME ZONE` in PostgreSQL.

Accepted input formats:
- **ISO-8601 with offset**: `2026-05-25T19:40:10.676802-03:00` (recommended)
- **ISO-8601 UTC**: `2026-05-25T22:40:10.676900Z`
- **Unix epoch seconds**: `1747486200` (converted to UTC)

All timestamps are normalized to UTC before database insertion.

## Testing

```bash
# Run all integration tests (uses Quarkus Dev Services for Kafka + PostgreSQL)
mvn test

# Run a single integration test
mvn test -Dtest=KafkaIngestionITest
```

Integration tests (extending `BaseWorkflowLifecycleITest`) verify:
- Workflow started/completed event normalization (`KafkaIngestionITest`)
- Faulted workflow + error field normalization (`FaultedWorkflowITest`)
- Cancelled workflow lifecycle (`CancelledWorkflowITest`)
- Suspended workflow lifecycle (`SuspendedWorkflowITest`)
- Task lifecycle (started -> completed)
- Out-of-order events (task before workflow with savepoint recovery)
- Field-level idempotency (immutable fields preserved on update)
- UTC timestamp conversion and storage

## Health Checks

The service provides three health check endpoints:

```bash
# Readiness: Database connectivity (used by Kubernetes readiness probe)
curl http://localhost:8080/q/health/ready

# Liveness: Service is running (used by Kubernetes liveness probe)
curl http://localhost:8080/q/health/live

# Wellness: Overall health summary
curl http://localhost:8080/q/health
```

**Health Check Details:**
- **Liveness**: Verifies database connection with `SELECT 1` query
- **Readiness**: Same as liveness - ensures DB is available before consuming messages
- **Wellness**: Aggregates all health indicators

Kafka consumer health is automatically monitored via SmallRye Reactive Messaging health integration.

## Error Handling

### Failed Event Processing

When event processing fails (database errors, constraint violations, etc.):

1. **Exception thrown**: `ProcessEventFailedException` wraps the SQL error
2. **Dead letter queue**: Failed message is sent to `data-index-events-dlq` topic
3. **Consumer continues**: Next messages are processed (fail-fast disabled)
4. **Monitoring**: Check DLQ topic for failed events

### Task FK Violation Recovery

If a task event arrives before its parent workflow:

1. **Initial INSERT fails**: Foreign key constraint violation (SQL state `23503`)
2. **Savepoint rollback**: Transaction rolled back to savepoint
3. **Placeholder workflow created**: Minimal workflow row with only `id` and `last_event_time`
4. **Task INSERT retried**: Now succeeds with placeholder workflow in place
5. **Workflow event arrives later**: Updates placeholder with full workflow data

This ensures tasks are never lost due to event ordering issues.

## Deployment

### Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-kafka-ingestion
spec:
  replicas: 1  # Single instance recommended (Kafka consumer group handles scaling)
  template:
    spec:
      containers:
      - name: kafka-ingestion
        image: kubesmarts/data-index-ingestion-kafka-service:999-SNAPSHOT
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          value: "kafka.kafka.svc.cluster.local:9092"
        - name: QUARKUS_DATASOURCE_JDBC_URL
          value: "jdbc:postgresql://postgresql:5432/data-index"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
```

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS` | Yes (prod) | - | Kafka broker URLs (comma-separated) |
| `QUARKUS_DATASOURCE_JDBC_URL` | Yes (prod) | - | PostgreSQL JDBC connection string |
| `QUARKUS_DATASOURCE_USERNAME` | Yes (prod) | - | Database username |
| `QUARKUS_DATASOURCE_PASSWORD` | Yes (prod) | - | Database password |

## Monitoring

### Key Metrics

Monitor the following for production deployments:

- **Kafka consumer lag**: `kafka_consumer_lag` (messages behind)
- **Processing rate**: `kafka_messages_consumed_total`
- **DLQ messages**: Monitor `data-index-events-dlq` topic for failed events
- **Database connection pool**: `agroal_*` metrics
- **Health check failures**: Kubernetes probe failures indicate DB connectivity issues

### Logs

```bash
# Follow service logs
kubectl logs -f deployment/data-index-kafka-ingestion

# Search for errors
kubectl logs deployment/data-index-kafka-ingestion | grep ERROR

# Check DLQ processing
kubectl logs deployment/data-index-kafka-ingestion | grep "dead-letter"
```
