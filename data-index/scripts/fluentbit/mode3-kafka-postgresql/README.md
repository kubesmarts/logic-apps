# Mode 3: Kafka + PostgreSQL

**Status**: Configuration placeholder - to be implemented

## Architecture

```
Quarkus Flow Pods
    ↓ (JSON logs)
FluentBit DaemonSet
    ↓ (parse & route)
Kafka Topics
    ↓ (Kafka Consumer)
PostgreSQL Tables
    ↓ (JPA)
Data Index GraphQL API
```

## Configuration

Mode 3 uses Kafka for event buffering and PostgreSQL for querying.

### Event Flow
1. FluentBit captures container logs
2. Parses JSON events
3. Sends to Kafka topics:
   - `workflow-instance-events`
   - `task-execution-events`
4. Kafka Consumer processes events and writes to PostgreSQL:
   - `workflow_instances` table
   - `task_executions` table
5. Data Index queries PostgreSQL via JPA

### Files (To Be Created)
- `fluent-bit.conf` - FluentBit → Kafka output
- `parsers.conf` - JSON parser
- `kubernetes/configmap.yaml` - K8s ConfigMap
- `kubernetes/daemonset.yaml` - K8s DaemonSet

### Kafka Consumer (Separate Component)
Mode 3 requires a separate Kafka consumer component:
- Subscribes to Kafka topics
- Processes events in order
- Writes to PostgreSQL with UPSERT logic
- Handles retries and dead-letter queue

## Notes

This mode requires:
- Kafka/Strimzi cluster
- Kafka consumer deployment (to be implemented)
- PostgreSQL database
- Kafka topic configuration

Benefits:
- Event replay capability
- Decoupled ingestion/storage
- Multiple consumers possible
- Kafka durability guarantees
