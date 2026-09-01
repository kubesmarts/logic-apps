# Data Index Ingestion - Kafka Mode (MODE 3)

Standalone Kafka-based event ingestion service for Data Index. Consumes CloudEvents from Kafka topics and writes directly to normalized PostgreSQL tables.

## Architecture

```
Quarkus Flow --> Kafka (CloudEvents: binary or structured, topic: flow-lifecycle-out)
                    |
             KafkaLifecycleConsumer (SmallRye Reactive Messaging - batch mode)
                    |
             Manual CloudEvent reconstruction (binary/structured auto-detection)
                    |
             WorkflowEventProcessor / TaskExecutionProcessor (batch processing)
                    |
             WorkflowPersistence / TaskPersistence (JDBC batch UPSERT, field-level idempotency)
                    |
             Database tables (workflow_instances, task_instances)
                    |
             Data Index GraphQL API

   (failed records --> dead-letter topic: data-index-events-dlq)
```

**CloudEvents Support:**
- **Binary mode** (default): CE attributes in Kafka headers, data in message body
- **Structured mode**: Full CloudEvent JSON envelope in message body
- Auto-detection per record (checks for `ce_type` header)

**Batch Processing:**
- Consumes up to 1000 Kafka records per poll for high throughput
- Manually reconstructs CloudEvent objects (batch mode requires manual handling)
- DB writes in configurable batches (`data-index.ingestion.db-batch-size=1000`)

## Modules

- **data-index-ingestion-kafka-processor** - Event models (`WorkflowInstanceEvent`, `TaskExecutionEvent`), processors (`WorkflowEventProcessor`, `TaskExecutionProcessor`), and JDBC UPSERT persistence (`WorkflowPersistence`, `TaskPersistence`)
- **data-index-ingestion-kafka-service** - Quarkus service with the Kafka consumer (`KafkaLifecycleConsumer`), CloudEvent mapping (`Mapper`), health checks, and dead-letter queue handling

## Development

```bash
# Build all modules
cd data-index
mvn clean package -pl data-index-ingestion -am -DskipTests

# Run in dev mode (auto-starts Kafka + PostgreSQL via Quarkus Dev Services)
cd data-index-ingestion/data-index-ingestion-kafka-service
mvn quarkus:dev

# Run integration tests
mvn test -pl data-index-ingestion/data-index-ingestion-kafka-service -am -Dsurefire.failIfNoSpecifiedTests=false
```

## Documentation

- Service configuration & deployment: `data-index-ingestion-kafka-service/README.md`
- Kafka cluster / KIND scripts: `data-index/scripts/kafka/README.md`
