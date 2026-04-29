# Quick Start - Error Structure Unification Testing

## Cluster is Running and Ready!

### Quick Access Commands

```bash
# GraphQL UI (Browser)
open http://localhost:30080/q/graphql-ui

# GraphQL API endpoint
curl http://localhost:30080/graphql

# Workflow test app
curl http://localhost:30082/q/health
```

### Test Error Structure in 30 Seconds

```bash
# 1. Query workflow with error
curl http://localhost:30080/graphql -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstance(id: \"test-faulted-wf-001\") { id status error { type title detail status instance } } }"}' \
  -s | python3 -m json.tool

# 2. Query task with error
curl http://localhost:30080/graphql -H "Content-Type: application/json" \
  -d '{"query":"{ getTaskExecution(id: \"test-task-001\") { id taskName error { type title status } } }"}' \
  -s | python3 -m json.tool

# 3. Filter by error status
curl http://localhost:30080/graphql -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(filter: { error: { status: { eq: 500 } } }) { id error { type } } }"}' \
  -s | python3 -m json.tool
```

### Trigger New Workflows

```bash
# Port forward (if needed)
kubectl port-forward -n workflows svc/workflow-test-app 30082:8080 &

# Execute test workflows
curl -X POST http://localhost:30082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"test":"value"}'

curl -X POST http://localhost:30082/test-workflows/hello-world \
  -H "Content-Type: application/json" \
  -d '{"name":"Your Name"}'
```

### Check Database

```bash
# Connect to PostgreSQL
kubectl exec -n postgresql postgresql-0 -it -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex

# Check workflows with errors
SELECT id, name, status, error_type, error_title, error_status 
FROM workflow_instances 
WHERE error_type IS NOT NULL;
```

### Monitor Logs

```bash
# Data Index Service
kubectl logs -n data-index -l app=data-index-service -f

# Workflow App (structured events)
kubectl logs -n workflows -l app=workflow-test-app -f | grep eventType

# FluentBit
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 -f
```

### Test Data Available

- 5 successful workflows (COMPLETED)
- 1 workflow with error (id: test-faulted-wf-001, error_status: 500)
- 1 task with error (id: test-task-001, error_status: 408)

### Full Documentation

- Access Information: KIND_CLUSTER_ACCESS.md
- Deployment Summary: DEPLOYMENT_SUMMARY.md

### Cleanup (When Done)

```bash
kind delete cluster --name data-index-test
```
