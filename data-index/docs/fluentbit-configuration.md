# FluentBit Configuration for Data Index v1.0.0

**Purpose**: Ingest Quarkus Flow structured logging events into PostgreSQL

**Event Flow**:
```
Quarkus Flow Runtime
    ↓ (emits)
Structured JSON Logs (/var/log/quarkus-flow/*.log)
    ↓ (parses)
FluentBit (this configuration)
    ↓ (writes UPSERT)
PostgreSQL (workflow_instances, task_executions tables)
    ↓ (reads)
JPA Entities
    ↓ (maps via MapStruct)
Domain Models
    ↓ (exposes)
GraphQL API
```

## Files

| File | Purpose |
|------|---------|
| `fluent-bit.conf` | Main FluentBit configuration with event routing and PostgreSQL output |
| `parsers.conf` | JSON parser for Quarkus Flow event logs |
| `.env.example` | PostgreSQL connection configuration template |
| `docker-compose.yml` | Test environment with FluentBit + PostgreSQL |

## Event Mapping

### Workflow Instance Events

| Event | Action | Table | Fields |
|-------|--------|-------|--------|
| `workflow.instance.started` | INSERT (UPSERT) | `workflow_instances` | id, namespace, name, version, status, start, input |
| `workflow.instance.completed` | UPDATE | `workflow_instances` | status, end, output |
| `workflow.instance.faulted` | UPDATE | `workflow_instances` | status, end, error_* |
| `workflow.instance.cancelled` | UPDATE | `workflow_instances` | status, end |
| `workflow.instance.suspended` | UPDATE | `workflow_instances` | status |
| `workflow.instance.resumed` | UPDATE | `workflow_instances` | status |
| `workflow.instance.status.changed` | UPDATE | `workflow_instances` | status, last_update |

### Task Execution Events

| Event | Action | Table | Fields |
|-------|--------|-------|--------|
| `workflow.task.started` | INSERT (UPSERT) | `task_executions` | id, workflow_instance_id, task_name, task_position, enter, input_args |
| `workflow.task.completed` | UPDATE | `task_executions` | exit, output_args |
| `workflow.task.faulted` | UPDATE | `task_executions` | exit, error_message |

## Configuration

### Environment Variables

FluentBit uses environment variables for PostgreSQL connection:

```bash
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=dataindex
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
```

Copy `.env.example` to `.env` and update with your values.

### Log Path

FluentBit tails logs from:
```
/var/log/quarkus-flow/*.log
```

Configure Quarkus Flow to write structured logging to this path, or update the `Path` in `[INPUT]` section.

## Running FluentBit

### Option 1: Local FluentBit Installation

```bash
# Install FluentBit (macOS)
brew install fluent-bit

# Export environment variables
export $(cat .env | xargs)

# Run FluentBit with this configuration
fluent-bit -c fluent-bit.conf
```

### Option 2: Docker Compose (Recommended for Testing)

```bash
# Start PostgreSQL + FluentBit
docker-compose up -d

# View FluentBit logs
docker-compose logs -f fluent-bit

# Stop services
docker-compose down
```

## Testing

### 1. Create Database Schema

Run the PostgreSQL schema from `../DATABASE-SCHEMA-V1.md`:

```bash
psql -h localhost -U postgres -d dataindex -f ../scripts/schema.sql
```

### 2. Generate Test Events

Run a Quarkus Flow workflow to generate structured logging events:

```bash
# Example: Run a simple workflow
curl -X POST http://localhost:8080/workflows/order-processing \
  -H "Content-Type: application/json" \
  -d '{"orderId": "12345"}'
```

### 3. Verify Ingestion

Query PostgreSQL to verify events were ingested:

```sql
-- Check workflow instances
SELECT id, namespace, name, status, start FROM workflow_instances ORDER BY start DESC LIMIT 10;

-- Check task executions
SELECT id, workflow_instance_id, task_name, task_position, enter FROM task_executions ORDER BY enter DESC LIMIT 10;
```

## Event Format Example

### workflow.instance.started

**Input** (from Quarkus Flow log):
```json
{
  "eventType": "io.serverlessworkflow.workflow.started.v1",
  "timestamp": "2026-04-15T15:30:00Z",
  "instanceId": "uuid-1234",
  "workflowNamespace": "default",
  "workflowName": "order-processing",
  "workflowVersion": "1.0.0",
  "status": "RUNNING",
  "startTime": "2026-04-15T15:30:00Z",
  "input": { "orderId": "12345" }
}
```

**Output** (PostgreSQL INSERT):
```sql
INSERT INTO workflow_instances (id, namespace, name, version, status, start, input)
VALUES ('uuid-1234', 'default', 'order-processing', '1.0.0', 'RUNNING', '2026-04-15 15:30:00+00', '{"orderId": "12345"}'::jsonb)
ON CONFLICT (id) DO UPDATE SET ...;
```

### workflow.task.started

**Input** (from Quarkus Flow log):
```json
{
  "eventType": "io.serverlessworkflow.task.started.v1",
  "timestamp": "2026-04-15T15:30:05Z",
  "instanceId": "uuid-1234",
  "taskExecutionId": "task-uuid-1",
  "taskName": "callPaymentService",
  "taskPosition": "/do/0",
  "startTime": "2026-04-15T15:30:05Z",
  "input": { "amount": 100 }
}
```

**Output** (PostgreSQL INSERT):
```sql
INSERT INTO task_executions (id, workflow_instance_id, task_name, task_position, enter, input_args)
VALUES ('task-uuid-1', 'uuid-1234', 'callPaymentService', '/do/0', '2026-04-15 15:30:05+00', '{"amount": 100}'::jsonb)
ON CONFLICT (id) DO UPDATE SET ...;
```

## UPSERT Strategy

FluentBit uses PostgreSQL's `INSERT ... ON CONFLICT DO UPDATE` for idempotent event processing:

- **workflow.instance.started**: UPSERT by `id` (handles duplicate/replay events)
- **workflow.task.started**: UPSERT by `id` (taskExecutionId is deterministic)
- **All other events**: UPDATE by `id` (assumes instance/task already exists)

This ensures:
- ✅ Events can be replayed without errors
- ✅ Out-of-order events are handled gracefully
- ✅ No duplicate data in database

## Troubleshooting

### FluentBit can't connect to PostgreSQL

```bash
# Check PostgreSQL is running
docker-compose ps

# Test connection manually
psql -h localhost -U postgres -d dataindex -c "SELECT 1;"
```

### Events not appearing in database

```bash
# Check FluentBit logs for errors
docker-compose logs fluent-bit

# Verify events are being received
docker-compose logs fluent-bit | grep eventType

# Check PostgreSQL logs
docker-compose logs postgres
```

### Invalid SQL errors

```bash
# FluentBit doesn't support nested JSON field access like ${error.type}
# Use Lua filter to flatten nested fields before OUTPUT
```

## Limitations

### Nested JSON Field Access

FluentBit's `${field}` syntax doesn't support nested JSON (e.g., `${error.type}`).

**Workaround**: Add Lua filter to flatten nested fields:

```lua
function flatten_error(tag, timestamp, record)
    if record["error"] then
        record["error_type"] = record["error"]["type"]
        record["error_title"] = record["error"]["title"]
        record["error_detail"] = record["error"]["detail"]
        record["error_status"] = record["error"]["status"]
        record["error_instance"] = record["error"]["instance"]
    end
    return 2, timestamp, record
end
```

**TODO**: Add Lua filter to configuration.

## Next Steps

1. ✅ FluentBit configuration created
2. ⏭️ Create Lua filter for nested JSON flattening
3. ⏭️ Create PostgreSQL schema migration script
4. ⏭️ Test with real Quarkus Flow workflows
5. ⏭️ Create MapStruct mappers (Entity ↔ Domain model)
6. ⏭️ Generate GraphQL schema from domain model

---

**Status**: ✅ FluentBit configuration complete - Ready for testing with Lua filter addition
