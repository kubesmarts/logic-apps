# MODE 1 End-to-End Testing Guide

Complete guide for testing MODE 1 (PostgreSQL Trigger-Based Normalization) in a KIND cluster.

## Overview

This guide walks through setting up and testing the complete MODE 1 data flow:

```
Quarkus Flow App → FluentBit → PostgreSQL (triggers) → Data Index GraphQL API
```

**Test Duration**: ~15-20 minutes (including cluster setup)

## Prerequisites

- Docker Desktop running
- `kind` CLI installed
- `kubectl` CLI installed
- `helm` CLI installed
- `curl` installed

## Step 1: Clean Up (Optional)

If you have an existing cluster, delete it first:

```bash
kind delete cluster --name data-index-test
```

## Step 2: Create KIND Cluster

```bash
cd data-index/scripts/kind
./setup-cluster.sh
```

**Expected Output:**
```
[INFO] Creating KIND cluster 'data-index-test'...
[INFO] ✓ Cluster created
[INFO] ✓ kubectl configured
[INFO] ✓ Ingress controller installed
[INFO] ==========================================
[INFO] KIND Cluster Setup Complete!
[INFO] ==========================================
```

**Verify:**
```bash
kubectl get nodes
# Should show 1 control-plane node in Ready state
```

## Step 3: Install Dependencies

Install PostgreSQL and FluentBit:

```bash
MODE=postgresql ./install-dependencies.sh
```

**Expected Output:**
```
[INFO] Installing dependencies for Data Index (MODE: postgresql)
[STEP] Creating namespaces...
[STEP] Installing Fluent Bit...
[STEP] Installing PostgreSQL...
[INFO] ✓ Installation complete!
```

**Verify:**
```bash
# Check PostgreSQL
kubectl get pods -n postgresql
# Should show postgresql-0 in Running state

# Check FluentBit
kubectl get pods -n fluent-bit
# Should show fluent-bit-* pods in Running state

# Test PostgreSQL connection
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c "SELECT version();"
# Should show PostgreSQL version
```

## Step 4: Deploy Data Index Service

This deploys the GraphQL API service and runs Flyway migrations (which create trigger functions):

```bash
./deploy-data-index.sh postgresql-polling
# Note: "postgresql-polling" is legacy name, actually runs trigger-based MODE 1
```

**Expected Output:**
```
[STEP] Building data-index-service image...
[STEP] Initializing PostgreSQL database schema...
[STEP] Creating data-index ConfigMap...
[STEP] Deploying data-index-service...
[INFO] ✓ data-index-service is ready
```

**Verify:**
```bash
# Check deployment
kubectl get pods -n data-index
# Should show data-index-service-* in Running state

# Test GraphQL API health
curl http://localhost:30080/q/health
# Should return: {"status":"UP",...}

# Test GraphQL API
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ __schema { queryType { name } } }"}'
# Should return schema information
```

## Step 5: Deploy FluentBit MODE 1 Configuration

Deploy the MODE 1-specific FluentBit configuration (pgsql output with triggers):

```bash
cd ../fluentbit/mode1-postgresql-triggers

# Apply ConfigMap (fluent-bit.conf, parsers.conf)
kubectl apply -f kubernetes/configmap.yaml

# Apply DaemonSet
kubectl apply -f kubernetes/daemonset.yaml
```

**Expected Output:**
```
configmap/workflows-fluent-bit-mode1-config created
daemonset.apps/workflows-fluent-bit-mode1 created
```

**Verify:**
```bash
# Check FluentBit pods
kubectl get pods -n logging -l app=workflows-fluent-bit-mode1
# Should show running pods (one per node)

# Check FluentBit logs (should show PostgreSQL connection)
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50 | grep -i "pgsql\|postgres"
```

## Step 6: Verify Database Schema with Triggers

Check that Flyway migrations created the schema correctly with triggers:

```bash
# Check raw tables exist
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\d workflow_events_raw"
# Should show: Columns: tag, time, data
#              Triggers: normalize_workflow_events

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\d task_events_raw"
# Should show: Columns: tag, time, data
#              Triggers: normalize_task_events

# Check normalized tables exist
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\d workflow_instances"
# Should show: id, namespace, name, version, status, start, end, etc.

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\d task_instances"
# Should show: task_execution_id, instance_id, task_name, etc.

# Check trigger functions exist
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\df normalize_workflow_event"
# Should show function definition

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\df normalize_task_event"
# Should show function definition
```

## Step 7: Deploy Workflow Test Application

Deploy a Quarkus Flow application with test workflows:

```bash
cd ../../kind
./deploy-workflow-app.sh
```

**Expected Output:**
```
[STEP] Creating workflows namespace...
[STEP] Deploying workflow application...
[STEP] Waiting for deployment to be ready...
[INFO] ==========================================
[INFO] Workflow Application Deployed!
[INFO] ==========================================
```

**Verify:**
```bash
# Check deployment
kubectl get pods -n workflows
# Should show workflow-test-app-* in Running state

# Test HTTP endpoint
curl http://localhost:30082/q/health
# Should return: {"status":"UP",...}
```

## Step 8: Execute Test Workflows

Trigger workflows and watch events flow through the system:

### 8.1: Execute Simple Set Workflow

```bash
# Trigger workflow
curl -X POST http://localhost:30082/test/simple-set/start

# Expected output:
# {"instanceId":"<uuid>","status":"COMPLETED"}
```

### 8.2: Watch Events in Real-Time

Open multiple terminal windows to observe the event flow:

**Terminal 1 - Workflow App Logs:**
```bash
kubectl logs -n workflows -l app=workflow-test-app -f | grep "eventType"
```

**Terminal 2 - FluentBit Logs:**
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 -f
```

**Terminal 3 - PostgreSQL Queries:**
```bash
# Watch raw table
watch -n 2 'kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
  "SELECT COUNT(*) FROM workflow_events_raw;"'

# Watch normalized table
watch -n 2 'kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
  "SELECT COUNT(*) FROM workflow_instances;"'
```

### 8.3: Execute More Workflows

```bash
# Hello world workflow
curl -X POST http://localhost:30082/test/hello-world/start

# Hello world fail workflow (tests error handling)
curl -X POST http://localhost:30082/test/hello-world-fail/start

# HTTP success workflow
curl -X POST http://localhost:30082/test/test-http-success/start
```

## Step 9: Verify Event Processing

### 9.1: Check Raw Events Table

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT tag, time, data->>'instanceId' as instance_id, data->>'eventType' as event_type 
   FROM workflow_events_raw 
   ORDER BY time DESC 
   LIMIT 10;"
```

**Expected Output:**
```
             tag              |            time            |  instance_id  |           event_type            
------------------------------+----------------------------+---------------+---------------------------------
 workflow.instance.completed  | 2026-04-23 22:15:32.123+00 | abc-123-...   | workflow.instance.completed
 workflow.instance.started    | 2026-04-23 22:15:31.456+00 | abc-123-...   | workflow.instance.started
```

### 9.2: Check Normalized Workflow Instances

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT id, namespace, name, status, start, \"end\" 
   FROM workflow_instances 
   ORDER BY start DESC 
   LIMIT 5;"
```

**Expected Output:**
```
       id        | namespace |      name       |  status   |           start            |            end             
-----------------+-----------+-----------------+-----------+----------------------------+----------------------------
 abc-123-...     | test      | simple-set      | COMPLETED | 2026-04-23 22:15:31.456+00 | 2026-04-23 22:15:32.123+00
 def-456-...     | test      | hello-world     | COMPLETED | 2026-04-23 22:14:15.789+00 | 2026-04-23 22:14:16.456+00
```

### 9.3: Check Task Instances

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT task_execution_id, instance_id, task_name, status 
   FROM task_instances 
   ORDER BY start DESC 
   LIMIT 5;"
```

**Expected Output:**
```
 task_execution_id | instance_id |  task_name   |  status   
-------------------+-------------+--------------+-----------
 task-001-...      | abc-123-... | setGreeting  | COMPLETED
 task-002-...      | abc-123-... | setMessage   | COMPLETED
```

### 9.4: Verify Trigger Execution

Check that raw count equals normalized count (triggers working):

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT 
     (SELECT COUNT(DISTINCT data->>'instanceId') FROM workflow_events_raw) as raw_instances,
     (SELECT COUNT(*) FROM workflow_instances) as normalized_instances;"
```

**Expected Output:**
```
 raw_instances | normalized_instances 
---------------+----------------------
             3 |                    3
```

**Note**: Raw count should equal or exceed normalized count (some events update existing instances).

## Step 10: Query via GraphQL API

### 10.1: List All Workflow Instances

```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id namespace name version status start end } }"}' | jq
```

**Expected Output:**
```json
{
  "data": {
    "getWorkflowInstances": [
      {
        "id": "abc-123-...",
        "namespace": "test",
        "name": "simple-set",
        "version": "1.0.0",
        "status": "COMPLETED",
        "start": "2026-04-23T22:15:31.456Z",
        "end": "2026-04-23T22:15:32.123Z"
      }
    ]
  }
}
```

### 10.2: Get Specific Workflow Instance

```bash
# Replace <instance-id> with actual ID from previous query
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstance(id: \"<instance-id>\") { id name status input output } }"}' | jq
```

### 10.3: Filter by Status

```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"query { getWorkflowInstances(filter: { status: COMPLETED }) { id name status } }"}' | jq
```

### 10.4: Get Task Instances for Workflow

```bash
# Replace <instance-id> with actual workflow instance ID
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getTaskInstancesByWorkflowInstance(instanceId: \"<instance-id>\") { taskExecutionId taskName taskPosition status start end } }"}' | jq
```

## Step 11: Run Automated GraphQL Tests

Run the automated test suite:

```bash
./test-graphql.sh
```

**Expected Output:**
```
[INFO] Testing Data Index GraphQL API in KIND cluster

[STEP] Test 1: Health endpoint
[INFO] ✓ Health endpoint responding

[STEP] Test 2: GraphQL schema introspection
[INFO] ✓ PASS

[STEP] Test 3: List workflow instances
[INFO] ✓ PASS

...

========================================
Test Summary
========================================
Tests run:    12
Tests passed: 12
Tests failed: 0

[INFO] All tests passed! ✓
```

## Step 12: Test Trigger Out-of-Order Handling

Verify that triggers handle out-of-order events correctly:

```bash
# Insert workflow.completed BEFORE workflow.started
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex <<'EOF'
-- Insert completed event first (out of order)
INSERT INTO workflow_events_raw (tag, time, data) VALUES (
  'workflow.instance.completed',
  NOW(),
  '{"instanceId":"ooo-test-123","workflowNamespace":"test","workflowName":"ooo-test","workflowVersion":"1.0.0","status":"COMPLETED","endTime":1713900100,"output":{"result":"done"}}'::jsonb
);

-- Check normalized table (should have placeholder record)
SELECT id, name, status, "start", "end" FROM workflow_instances WHERE id = 'ooo-test-123';

-- Now insert started event (late arrival)
INSERT INTO workflow_events_raw (tag, time, data) VALUES (
  'workflow.instance.started',
  NOW(),
  '{"instanceId":"ooo-test-123","workflowNamespace":"test","workflowName":"ooo-test","workflowVersion":"1.0.0","status":"RUNNING","startTime":1713900000,"input":{"foo":"bar"}}'::jsonb
);

-- Check normalized table (should have complete record, COALESCE preserved end time)
SELECT id, name, status, "start", "end", input, output FROM workflow_instances WHERE id = 'ooo-test-123';
EOF
```

**Expected Output:**
First query (after completed event):
```
     id      | name | status | start |            end             
-------------+------+--------+-------+----------------------------
 ooo-test-123|      |        |       | 2026-04-23 22:30:00+00
```

Second query (after started event):
```
     id      |   name   |  status   |           start            |            end             |      input      |     output      
-------------+----------+-----------+----------------------------+----------------------------+-----------------+-----------------
 ooo-test-123| ooo-test | RUNNING   | 2026-04-23 22:25:00+00     | 2026-04-23 22:30:00+00     | {"foo":"bar"}   | {"result":"done"}
```

**Note**: The `COALESCE` in trigger preserved the `end` time from the first (out-of-order) event.

## Step 13: Performance Verification

### 13.1: Check Trigger Execution Time

```bash
# Enable pg_stat_statements extension (if not already enabled)
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;"

# Check trigger execution statistics
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT calls, mean_exec_time, max_exec_time, query 
   FROM pg_stat_statements 
   WHERE query LIKE '%normalize_workflow%' 
   ORDER BY mean_exec_time DESC 
   LIMIT 5;"
```

**Expected Output:**
```
 calls | mean_exec_time | max_exec_time |          query           
-------+----------------+---------------+--------------------------
    10 |           2.45 |          5.12 | INSERT INTO workflow_...
```

**Note**: Trigger overhead should be ~1-5ms per event for typical workloads.

### 13.2: Bulk Load Test

Test with multiple concurrent workflows:

```bash
# Execute 10 workflows concurrently
for i in {1..10}; do
  curl -X POST http://localhost:30082/test/simple-set/start &
done
wait

# Check counts
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM workflow_instances;"
```

## Troubleshooting

### Issue: Events not reaching PostgreSQL raw tables

**Diagnosis:**
```bash
# Check FluentBit can read log file
kubectl exec -n logging $(kubectl get pods -n logging -l app=workflows-fluent-bit-mode1 -o jsonpath='{.items[0].metadata.name}') -- \
  ls -la /tmp/quarkus-flow-events.log

# Check FluentBit PostgreSQL connection
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=100 | grep -i "pgsql\|error"
```

**Resolution:**
- Verify PostgreSQL service name in FluentBit config: `postgresql.postgresql.svc.cluster.local`
- Check NetworkPolicy if applicable

### Issue: Raw tables populated but normalized tables empty

**Diagnosis:**
```bash
# Check trigger exists
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "\d workflow_events_raw" | grep "Triggers:"

# Check trigger function
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT prosrc FROM pg_proc WHERE proname = 'normalize_workflow_event';"
```

**Resolution:**
- Re-run Flyway migrations: Delete data-index-service pod to trigger restart
- Manually create triggers from `V1__initial_schema.sql`

### Issue: GraphQL API returns empty results

**Diagnosis:**
```bash
# Check JPA entity table mapping
kubectl logs -n data-index -l app=data-index-service --tail=100 | grep -i "hibernate\|jpa"

# Test direct database query
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
  "SELECT COUNT(*) FROM workflow_instances;"
```

**Resolution:**
- Verify JPA entity `@Table(name = "workflow_instances")` matches database
- Check JPA entity field names match database columns

## Clean Up

Delete the entire cluster:

```bash
kind delete cluster --name data-index-test
```

## Success Criteria

✅ **All components deployed successfully**  
✅ **Workflows execute and complete**  
✅ **Events appear in raw PostgreSQL tables**  
✅ **Triggers populate normalized tables**  
✅ **GraphQL API returns workflow data**  
✅ **Out-of-order events handled correctly (COALESCE test)**  
✅ **Automated test suite passes (12/12 tests)**  
✅ **Trigger execution time < 5ms average**

## Next Steps

- Test with more complex workflows (parallel tasks, error handling, retries)
- Load test with higher concurrency
- Monitor trigger performance under load
- Test PostgreSQL connection pooling limits
- Implement GraphQL pagination testing
- Add monitoring dashboards (Prometheus, Grafana)

## References

- MODE 1 Architecture: `MODE1_ARCHITECTURE_UPDATE.md`
- MODE 1 Status: `MODE1_HANDOFF.md`
- FluentBit Configuration: `../scripts/fluentbit/mode1-postgresql-triggers/README.md`
- Database Migrations: `../data-index-storage-migrations/README.md`
