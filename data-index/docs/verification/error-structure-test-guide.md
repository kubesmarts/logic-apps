# Error Structure Testing Quick Reference

## Access Points

**GraphQL API:** http://localhost:8080/graphql
**GraphQL UI:** http://localhost:8080/q/graphql-ui/
**Workflow Test App:** http://localhost:8082

## Sample GraphQL Queries

### 1. Get All Workflows with Error Details
```graphql
query GetAllWorkflows {
  getWorkflowInstances {
    id
    name
    status
    error {
      type
      title
      status
      detail
      instance
    }
    taskExecutions {
      id
      taskName
      status
      error {
        type
        title
        status
        detail
        instance
      }
    }
  }
}
```

### 2. Filter Workflows by Error Type
```graphql
query FilterByErrorType {
  getWorkflowInstances(filter: {error: {type: {like: "%runtime%"}}}) {
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

### 3. Filter Workflows by Error Status Code
```graphql
query FilterByErrorStatus {
  getWorkflowInstances(filter: {error: {status: {eq: 500}}}) {
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

### 4. Get Faulted Workflows Only
```graphql
query GetFaultedWorkflows {
  getWorkflowInstances(filter: {status: {eq: FAULTED}}) {
    id
    name
    status
    error {
      type
      title
      status
      detail
      instance
    }
  }
}
```

### 5. Get Specific Workflow with Task Errors
```graphql
query GetWorkflowWithTasks {
  getWorkflowInstances(filter: {id: {eq: "test-faulted-workflow-001"}}) {
    id
    name
    status
    error {
      type
      title
      status
      detail
      instance
    }
    taskExecutions {
      id
      taskName
      status
      error {
        type
        title
        status
        detail
        instance
      }
    }
  }
}
```

## Using curl

### Query All Workflows
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"query { getWorkflowInstances { id name status error { type title status } } }"}' \
  | jq '.'
```

### Filter by Error Type
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"query { getWorkflowInstances(filter: {error: {type: {like: \"%runtime%\"}}}) { id status error { type title } } }"}' \
  | jq '.'
```

## Test Data Available

1. **test-faulted-workflow-001** - FAULTED workflow with Runtime Error (500) and failed task
2. **test-partial-error-workflow-001** - COMPLETED workflow with one failed task
3. **01KQAMRJ7ZZ5X5Y6Y2V0W1W00V** - COMPLETED workflow with no errors

## Database Queries

### Check Workflow Errors
```bash
kubectl exec -n postgresql postgresql-0 -- sh -c 'PGPASSWORD=rdIjNvhTnx psql -U postgres -d dataindex -c "SELECT id, status, error_type, error_title, error_status FROM workflow_instances WHERE error_type IS NOT NULL;"'
```

### Check Task Errors
```bash
kubectl exec -n postgresql postgresql-0 -- sh -c 'PGPASSWORD=rdIjNvhTnx psql -U postgres -d dataindex -c "SELECT task_execution_id, instance_id, status, error_type, error_title FROM task_instances WHERE error_type IS NOT NULL;"'
```

## Expected Results

- Successful workflows have `error: null`
- Faulted workflows have `error: { type, title, status, detail, instance }`
- Failed tasks have `error` structure populated
- Completed workflows can have failed tasks (partial errors)
- All RFC 7807 error fields are populated correctly

