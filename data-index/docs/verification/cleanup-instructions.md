# Cleanup Instructions

## When You're Done Testing

### Option 1: Keep Cluster Running (Recommended for Further Testing)

The cluster will remain running and accessible. Port forwards will need to be re-established after system restart:

```bash
# Re-establish port forwards
kubectl port-forward -n data-index service/data-index-service 8080:8080 &
kubectl port-forward -n workflows service/workflow-test-app 8082:8080 &
```

### Option 2: Stop Port Forwards Only

Kill the port forwarding processes:

```bash
# Find port forward processes
ps aux | grep "port-forward"

# Kill them
pkill -f "port-forward.*data-index"
pkill -f "port-forward.*workflow"
```

### Option 3: Delete Test Data Only

Remove test error data but keep real workflow executions:

```bash
kubectl exec -n postgresql postgresql-0 -- sh -c 'PGPASSWORD=rdIjNvhTnx psql -U postgres -d dataindex -c "DELETE FROM task_instances WHERE instance_id IN ('\''test-faulted-workflow-001'\'', '\''test-partial-error-workflow-001'\'');"'

kubectl exec -n postgresql postgresql-0 -- sh -c 'PGPASSWORD=rdIjNvhTnx psql -U postgres -d dataindex -c "DELETE FROM workflow_instances WHERE id IN ('\''test-faulted-workflow-001'\'', '\''test-partial-error-workflow-001'\'');"'
```

### Option 4: Tear Down Entire Cluster

**WARNING:** This will delete all data and require full cluster setup again.

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/kind
kind delete cluster --name data-index-test
```

## Recommended Approach

Leave the cluster running for now. You can:
1. Continue manual testing via GraphQL UI
2. Execute more workflows to test real error scenarios
3. Test with real Quarkus Flow applications

The cluster uses minimal resources and can remain running indefinitely.

## Quick Access Commands

```bash
# GraphQL UI (in browser)
open http://localhost:8080/q/graphql-ui/

# Execute a test workflow
curl -X POST http://localhost:8082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"name": "test"}'

# Check database
kubectl exec -n postgresql postgresql-0 -- sh -c 'PGPASSWORD=rdIjNvhTnx psql -U postgres -d dataindex -c "SELECT id, status, error_type FROM workflow_instances ORDER BY created_at DESC LIMIT 5;"'
```

