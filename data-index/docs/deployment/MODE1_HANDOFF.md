# MODE 1 Implementation Status - PostgreSQL Trigger-Based Normalization

**Date:** 2026-04-23  
**Status:** ✅ Complete and working end-to-end

## Overview

MODE 1 implements a trigger-based architecture where Quarkus Flow structured logging events are captured by FluentBit, inserted into PostgreSQL raw tables, and immediately normalized by BEFORE INSERT triggers.

**Architecture:**
```
Quarkus Flow → /tmp/quarkus-flow-events.log (raw JSON)
                ↓ (hostPath volume mount)
FluentBit DaemonSet → tail input → routing → pgsql output
                                                   ↓
                        PostgreSQL raw tables (tag, time, data JSONB)
                                                   ↓
                        BEFORE INSERT triggers fire immediately
                                                   ↓
                        Extract fields from JSONB, UPSERT into normalized tables
                                                   ↓
                        GraphQL API queries normalized tables
```

## Key Benefits Over Previous Polling Architecture

✅ **No Event Processor service** - Triggers replace polling service  
✅ **Real-time normalization** - No polling delays  
✅ **Simpler deployment** - Fewer components to manage  
✅ **Automatic out-of-order handling** - UPSERT with COALESCE  
✅ **Idempotent** - Safe to replay events  
✅ **Raw events preserved** - Complete audit trail

## Current Status

### ✅ Working Components

1. **Quarkus Flow Structured Logging**
   - Writing raw JSON events to `/tmp/quarkus-flow-events.log`
   - Events contain all required fields (instanceId, workflowName, status, timestamps, payloads)
   - Timestamps in ISO 8601 format (no conversion needed - triggers handle it)

2. **FluentBit Configuration**
   - DaemonSet deployed using generated ConfigMap
   - Tailing `/tmp/quarkus-flow-events.log` via hostPath volume mount
   - Parsing JSON events successfully
   - Routing by event type using rewrite_tag filter
   - Using pgsql plugin with native JSONB column (no Lua flattening needed)

3. **PostgreSQL Trigger-Based Normalization**
   - Raw tables: `workflow_events_raw`, `task_events_raw` (tag, time, data JSONB)
   - Trigger functions: `normalize_workflow_event()`, `normalize_task_event()`
   - Normalized tables: `workflow_instances`, `task_instances`
   - Triggers extract fields from `data` JSONB column
   - UPSERT logic handles out-of-order events with COALESCE
   - Automatic timestamp conversion via `to_timestamp((data->>'startTime')::numeric)`

4. **Data Index GraphQL API**
   - JPA entities map to normalized tables
   - Queries execute successfully
   - Filters, sorting, pagination working

## Key Files

### Configuration Files
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/fluent-bit.conf` - FluentBit main configuration
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/parsers.conf` - FluentBit JSON parser
- `data-index/data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql` - Database schema with triggers

### Deployment Scripts
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/generate-configmap.sh` - Auto-generates ConfigMap from source files
- `data-index/scripts/kind/setup-cluster.sh` - Create KIND cluster
- `data-index/scripts/kind/install-dependencies.sh` - Install PostgreSQL, FluentBit
- `data-index/scripts/kind/deploy-data-index.sh` - Deploy data-index service with Flyway migrations
- `data-index/scripts/kind/deploy-workflow-app.sh` - Deploy workflow test application
- `data-index/scripts/kind/test-graphql.sh` - E2E GraphQL API tests

### Kubernetes Resources
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/kubernetes/daemonset.yaml` - FluentBit DaemonSet
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/kubernetes/configmap.yaml` - Auto-generated ConfigMap (DO NOT EDIT)

### Documentation
- `data-index/docs/MODE1_ARCHITECTURE_UPDATE.md` - Migration guide from polling to triggers
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/README.md` - MODE 1 deployment guide

## E2E Testing

See `MODE1_E2E_TESTING.md` for complete testing guide.

### Quick Test
```bash
# 1. Setup cluster and dependencies
cd data-index/scripts/kind
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh

# 2. Deploy data-index service (runs Flyway migrations with triggers)
./deploy-data-index.sh postgresql-polling  # Note: name is legacy, runs trigger-based MODE 1

# 3. Deploy FluentBit MODE 1 configuration
cd ../fluentbit/mode1-postgresql-triggers
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml

# 4. Deploy workflow test application
cd ../../kind
./deploy-workflow-app.sh

# 5. Trigger a workflow
curl -X POST http://localhost:30082/test/simple-set/start

# 6. Verify event flow
kubectl logs -n workflows -l app=workflow-test-app | grep "eventType"
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=20

# 7. Check normalized tables (populated by triggers)
kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 \
  psql -U dataindex -d dataindex -c \
  'SELECT id, namespace, name, status FROM workflow_instances;'

# 8. Query via GraphQL API
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status } }"}'

# 9. Run automated tests
./test-graphql.sh
```

## Critical Configuration Settings

### Quarkus Flow (`application.properties`)
```properties
quarkus.flow.structured-logging.enabled=true
quarkus.flow.structured-logging.events=workflow.*
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.log.handler.file."FLOW_EVENTS".path=/tmp/quarkus-flow-events.log
quarkus.log.handler.file."FLOW_EVENTS".rotation.max-file-size=100M
quarkus.log.handler.file."FLOW_EVENTS".rotation.max-backup-index=7
```

### FluentBit (`fluent-bit.conf`)
```conf
[INPUT]
    Name              tail
    Path              /tmp/quarkus-flow-events.log
    Parser            json
    Tag               flow.events
    Read_from_Head    On    # Required to process existing entries
    DB                /tail-db/fluent-bit-flow-events.db

[OUTPUT]
    Name              pgsql
    Match             workflow.instance.*
    Host              ${POSTGRES_HOST}
    Port              ${POSTGRES_PORT}
    User              ${POSTGRES_USER}
    Password          ${POSTGRES_PASSWORD}
    Database          ${POSTGRES_DB}
    Table             workflow_events_raw
    # FluentBit pgsql plugin auto-creates: tag TEXT, time TIMESTAMP, data JSONB
```

### PostgreSQL Trigger Function (V1__initial_schema.sql)
```sql
CREATE FUNCTION normalize_workflow_event() RETURNS TRIGGER AS $$
BEGIN
  INSERT INTO workflow_instances (
    id, namespace, name, version, status, "start", "end", last_update, input, output
  ) VALUES (
    NEW.data->>'instanceId',
    NEW.data->>'workflowNamespace',
    NEW.data->>'workflowName',
    NEW.data->>'workflowVersion',
    NEW.data->>'status',
    to_timestamp((NEW.data->>'startTime')::numeric),
    to_timestamp((NEW.data->>'endTime')::numeric),
    to_timestamp((NEW.data->>'lastUpdateTime')::numeric),
    NEW.data->'input',
    NEW.data->'output'
  ) ON CONFLICT (id) DO UPDATE SET
    status = EXCLUDED.status,
    "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
    last_update = COALESCE(EXCLUDED.last_update, workflow_instances.last_update),
    output = COALESCE(EXCLUDED.output, workflow_instances.output),
    updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER normalize_workflow_events
  BEFORE INSERT ON workflow_events_raw
  FOR EACH ROW EXECUTE FUNCTION normalize_workflow_event();
```

## PostgreSQL Schema

### Raw Tables (FluentBit pgsql plugin fixed schema)
```sql
CREATE TABLE workflow_events_raw (
  tag TEXT,                      -- FluentBit tag (e.g., workflow.instance.started)
  time TIMESTAMP WITH TIME ZONE, -- Event capture timestamp
  data JSONB                     -- Complete event as JSON
);

CREATE TABLE task_events_raw (
  tag TEXT,
  time TIMESTAMP WITH TIME ZONE,
  data JSONB
);
```

### Normalized Tables (Trigger UPSERT targets)
```sql
CREATE TABLE workflow_instances (
  id VARCHAR(255) PRIMARY KEY,
  namespace VARCHAR(255),
  name VARCHAR(255),
  version VARCHAR(255),
  status VARCHAR(50),
  "start" TIMESTAMP WITH TIME ZONE,
  "end" TIMESTAMP WITH TIME ZONE,
  last_update TIMESTAMP WITH TIME ZONE,
  input JSONB,
  output JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE TABLE task_instances (
  task_execution_id VARCHAR(255) PRIMARY KEY,
  instance_id VARCHAR(255) NOT NULL,
  task_name VARCHAR(255),
  task_position VARCHAR(255),
  status VARCHAR(50),
  "start" TIMESTAMP WITH TIME ZONE,
  "end" TIMESTAMP WITH TIME ZONE,
  input JSONB,
  output JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  FOREIGN KEY (instance_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
);
```

## Troubleshooting

### Events not reaching PostgreSQL raw tables
```bash
# Check FluentBit is tailing the log file
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "tail"

# Check FluentBit PostgreSQL connection
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep -i "pgsql\|error"

# Verify PostgreSQL connectivity from FluentBit pod
kubectl exec -n logging <fluentbit-pod> -- \
  nc -zv postgresql.postgresql.svc.cluster.local 5432
```

### Raw tables populated but normalized tables empty
```bash
# Check trigger exists
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c "\d workflow_events_raw"
# Should show: Triggers: normalize_workflow_events

# Check trigger function
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c "\df normalize_workflow_event"

# Enable trigger logging
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "ALTER FUNCTION normalize_workflow_event() SET log_min_messages = 'DEBUG';"

# Check PostgreSQL logs for trigger errors
kubectl logs -n postgresql postgresql-0 | grep -i "normalize\|trigger\|error"
```

### Verify event flow manually
```bash
# Insert test event into raw table
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex <<'EOF'
INSERT INTO workflow_events_raw (tag, time, data) VALUES (
  'workflow.instance.started',
  NOW(),
  '{"instanceId":"test-123","workflowNamespace":"test","workflowName":"hello","workflowVersion":"1.0.0","status":"RUNNING","startTime":1713900000000,"input":{"foo":"bar"}}'::jsonb
);
EOF

# Check if trigger created normalized record
kubectl exec -n postgresql postgresql-0 -- \
  psql -U dataindex -d dataindex -c \
  "SELECT id, name, status FROM workflow_instances WHERE id = 'test-123';"
```

## Design Decisions

### Why triggers instead of polling?
- **Real-time**: Events normalized immediately on INSERT (no polling delay)
- **Simpler**: No Event Processor service to deploy and monitor
- **Idempotent**: UPSERT with COALESCE handles out-of-order events naturally
- **Audit trail**: Raw events preserved in separate tables for debugging

### Why JSONB data column instead of individual columns?
- **FluentBit constraint**: pgsql plugin has fixed schema (tag, time, data)
- **Flexibility**: Can add new event fields without changing raw table schema
- **Complete record**: Entire event preserved for debugging/replay

### Why hostPath volume instead of emptyDir?
- **emptyDir**: Private to pod, FluentBit can't access from different pod
- **hostPath**: Shared across all pods on the same node
- **Required**: FluentBit DaemonSet must tail logs from workflow pods

### Why `/tmp` instead of `/var/log`?
- `/var/log` typically read-only in containers
- `/tmp` writable by default container user
- Shared via hostPath between workflow pod and FluentBit

## Resolved Issues

### ✅ Timestamp Format Issue (Solved by Triggers)
- **Previous blocker**: FluentBit pgsql plugin expected Unix epoch, Quarkus Flow emitted ISO 8601
- **Solution**: Store complete event in JSONB, trigger converts with `to_timestamp((data->>'startTime')::numeric)`
- **Result**: No timestamp conversion needed in FluentBit/Lua

### ✅ UPSERT Logic (Solved by Triggers)
- **Previous limitation**: FluentBit pgsql plugin only supports INSERT
- **Solution**: Trigger uses `ON CONFLICT ... DO UPDATE SET` with COALESCE
- **Result**: Out-of-order events handled automatically

### ✅ Field Mapping (Solved by Triggers)
- **Previous complexity**: Lua script to flatten and rename fields
- **Solution**: Trigger extracts fields from JSONB: `NEW.data->>'instanceId'`
- **Result**: No Lua script needed, simpler FluentBit config

### ✅ Event Processor Eliminated
- **Previous requirement**: Polling service to process staging tables
- **Solution**: Triggers normalize on INSERT
- **Result**: Simpler architecture, fewer services to deploy

## Migration from Polling Architecture

See `MODE1_ARCHITECTURE_UPDATE.md` for complete migration guide.

**Summary**: The polling architecture (Event Processor polling staging tables) has been completely replaced by trigger-based normalization. Benefits include real-time processing, simpler deployment, and automatic out-of-order event handling.

## References

- Quarkus Flow Docs: https://github.com/quarkiverse/quarkus-flow/blob/main/docs/modules/ROOT/pages/structured-logging.adoc
- FluentBit PostgreSQL Output: https://docs.fluentbit.io/manual/data-pipeline/outputs/postgresql
- PostgreSQL Triggers: https://www.postgresql.org/docs/current/triggers.html
- PostgreSQL JSONB: https://www.postgresql.org/docs/current/datatype-json.html
