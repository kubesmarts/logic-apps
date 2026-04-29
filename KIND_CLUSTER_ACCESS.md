# KIND Cluster Access Information - Error Structure Unification Testing

## Cluster Status

```bash
kubectl cluster-info --context kind-data-index-test
kubectl get nodes
```

## Deployed Components

### 1. PostgreSQL Database
- **Namespace**: postgresql
- **Pod**: postgresql-0
- **Service**: postgresql (NodePort 30432)
- **Connection**: postgresql://dataindex:dataindex123@localhost:30432/dataindex

### 2. Data Index Service
- **Namespace**: data-index
- **Deployment**: data-index-service
- **Service**: data-index-service (NodePort 30080)

### 3. FluentBit
- **Namespace**: logging
- **DaemonSet**: workflows-fluent-bit-mode1
- **Purpose**: Captures structured logs from workflow pods and sends to PostgreSQL

### 4. Workflow Test Application
- **Namespace**: workflows
- **Deployment**: workflow-test-app
- **Service**: workflow-test-app (NodePort 30082)

## Access Endpoints

### GraphQL API (Data Index)
```bash
# GraphQL endpoint
http://localhost:30080/graphql

# GraphQL UI
http://localhost:30080/q/graphql-ui

# Health check
curl http://localhost:30080/q/health
```

### Workflow Test App
```bash
# Port forward required (if NodePort not accessible)
kubectl port-forward -n workflows svc/workflow-test-app 30082:8080 &

# Health check
curl http://localhost:30082/q/health

# Execute workflows
curl -X POST http://localhost:30082/test-workflows/simple-set \
  -H "Content-Type: application/json" \
  -d '{"test":"value"}'

curl -X POST http://localhost:30082/test-workflows/hello-world \
  -H "Content-Type: application/json" \
  -d '{"name":"Test User"}'
```

## GraphQL Queries - Testing Error Structure

### Query all workflows with error structure
```graphql
{
  getWorkflowInstances {
    id
    name
    status
    error {
      type
      title
      detail
      status
      instance
    }
  }
}
```

### Query specific workflow by ID
```graphql
{
  getWorkflowInstance(id: "test-faulted-wf-001") {
    id
    name
    status
    error {
      type
      title
      detail
      status
      instance
    }
  }
}
```

### Filter workflows by error status (500)
```graphql
{
  getWorkflowInstances(filter: { error: { status: { eq: 500 } } }) {
    id
    name
    status
    error {
      type
      title
      status
    }
  }
}
```

### Filter workflows by FAULTED status
```graphql
{
  getWorkflowInstances(filter: { status: { eq: FAULTED } }) {
    id
    name
    status
    error {
      type
      title
      detail
    }
  }
}
```

### Query task executions with error structure
```graphql
{
  getTaskExecutions {
    id
    taskName
    error {
      type
      title
      detail
      status
      instance
    }
  }
}
```

### Filter tasks by error status (408)
```graphql
{
  getTaskExecutions(filter: { error: { status: { eq: 408 } } }) {
    id
    taskName
    error {
      type
      title
      status
    }
  }
}
```

## curl Examples

### Query workflows with errors
```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status error { type title detail status instance } } }"}' \
  -s | python3 -m json.tool
```

### Filter by error status 500
```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(filter: { error: { status: { eq: 500 } } }) { id name status error { type title status } } }"}' \
  -s | python3 -m json.tool
```

### Query faulted workflows
```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(filter: { status: { eq: FAULTED } }) { id name status error { type title } } }"}' \
  -s | python3 -m json.tool
```

### Query task with error
```bash
curl http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getTaskExecution(id: \"test-task-001\") { id taskName status error { type title detail status instance } } }"}' \
  -s | python3 -m json.tool
```

## Database Access

### Connect to PostgreSQL
```bash
kubectl exec -n postgresql postgresql-0 -it -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex
```

### Check workflow instances with errors
```sql
SELECT id, name, status, error_type, error_title, error_detail, error_status 
FROM workflow_instances 
WHERE error_type IS NOT NULL;
```

### Check task instances with errors
```sql
SELECT task_execution_id, task_name, status, error_type, error_title, error_status 
FROM task_instances 
WHERE error_type IS NOT NULL;
```

### Check raw events
```sql
SELECT COUNT(*) FROM workflow_events_raw;
SELECT COUNT(*) FROM task_events_raw;
```

## Test Data

The cluster contains:
- **5 successful workflows** (simple-set, hello-world)
- **1 test workflow with error** (id: test-faulted-wf-001, error_status: 500)
- **1 test task with error** (id: test-task-001, error_status: 408)

## Pod Logs

### Data Index Service logs
```bash
kubectl logs -n data-index -l app=data-index-service -f
```

### Workflow app logs (structured events)
```bash
kubectl logs -n workflows -l app=workflow-test-app -f
```

### FluentBit logs
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 -f
```

### View structured events in workflow logs
```bash
kubectl logs -n workflows -l app=workflow-test-app | grep "eventType"
```

## Cleanup (DO NOT RUN - Cluster should stay running)

```bash
# Only run this when testing is complete
kind delete cluster --name data-index-test
```

## Verification Checklist

- [ ] GraphQL API accessible at http://localhost:30080/graphql
- [ ] GraphQL UI accessible at http://localhost:30080/q/graphql-ui
- [ ] Workflow instances query returns error structure
- [ ] Task executions query returns error structure
- [ ] Error filtering works (by status, type, etc.)
- [ ] Test workflow with error (test-faulted-wf-001) is queryable
- [ ] Test task with error (test-task-001) is queryable
- [ ] Database schema has error columns (error_type, error_title, error_detail, error_status, error_instance)
- [ ] FluentBit is capturing events (check raw tables)
- [ ] Triggers are normalizing events (check workflow_instances and task_instances)
