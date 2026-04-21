# Elasticsearch Transform Definitions

This directory contains ready-to-deploy Elasticsearch Transform definitions for Data Index Mode 2 architecture.

## Transform Files

### 1. workflow-events-to-instances-transform.json
**Purpose**: Merge workflow CloudEvents into normalized workflow instances

**Input**: `workflow-events-raw` index
**Output**: `workflow-instances` index
**Frequency**: Every 10 seconds
**Delay**: 30 seconds (allow late-arriving events)

**Event Types Processed**:
- `workflow.started`
- `workflow.completed`
- `workflow.faulted`
- `workflow.error`

**Aggregation Logic**:
- Group by: `cloudEvent.data.id` (workflow instance ID)
- `name`: First event value
- `namespace`: First event value
- `version`: First event value
- `status`: Latest value (most recent event)
- `startTime`: Earliest timestamp
- `endTime`: Latest timestamp
- `duration`: Calculated (endTime - startTime)
- `input`: From first event (chronologically)
- `output`: From last event (chronologically)
- `errors`: Last 5 error events

### 2. task-events-to-executions-transform.json
**Purpose**: Merge task CloudEvents into normalized task executions

**Input**: `workflow-events-raw` index
**Output**: `task-executions` index
**Frequency**: Every 10 seconds
**Delay**: 30 seconds

**Event Types Processed**:
- `task.started`
- `task.completed`
- `task.faulted`

**Aggregation Logic**:
- Group by: `cloudEvent.data.taskId` (task execution ID)
- `taskName`: First event value
- `taskPosition`: First event value
- `workflowInstanceId`: First event value
- `status`: Latest value
- `enter`: Earliest timestamp
- `exit`: Latest timestamp
- `duration`: Calculated (exit - enter)
- `inputArgs`: From first event
- `outputArgs`: From last event
- `errorMessage`: From last error event

## Deployment

### Prerequisites
1. Elasticsearch 7.12+ (Transform feature)
2. Raw events index created: `workflow-events-raw`
3. Destination indices created: `workflow-instances`, `task-executions`

### Deploy Transforms

#### Option 1: Using curl (manual)

```bash
# Deploy workflow transform
curl -X PUT "localhost:9200/_transform/workflow-events-to-instances" \
  -H 'Content-Type: application/json' \
  -d @workflow-events-to-instances-transform.json

# Start workflow transform
curl -X POST "localhost:9200/_transform/workflow-events-to-instances/_start"

# Deploy task transform
curl -X PUT "localhost:9200/_transform/task-events-to-executions" \
  -H 'Content-Type: application/json' \
  -d @task-events-to-executions-transform.json

# Start task transform
curl -X POST "localhost:9200/_transform/task-events-to-executions/_start"
```

#### Option 2: Using Elasticsearch Java Client (automated)

See `ElasticsearchTransformDeployer.java` (if implemented)

### Verify Deployment

```bash
# Check transform status
curl "localhost:9200/_transform/_stats?pretty"

# Check specific transform
curl "localhost:9200/_transform/workflow-events-to-instances/_stats?pretty"

# View transform details
curl "localhost:9200/_transform/workflow-events-to-instances?pretty"
```

## Monitoring

### Health Checks

```bash
# List all transforms
curl "localhost:9200/_cat/transforms?v&h=id,state,checkpoint,documents_processed"

# Get detailed stats
curl "localhost:9200/_transform/workflow-events-to-instances/_stats?pretty"
```

### Troubleshooting

**Transform not processing documents:**
```bash
# Check transform state
GET _transform/workflow-events-to-instances/_stats

# If stopped, restart
POST _transform/workflow-events-to-instances/_start
```

**Slow processing:**
```bash
# Increase frequency (process less frequently, bigger batches)
POST _transform/workflow-events-to-instances/_update
{
  "frequency": "30s"
}

# Or increase page size
POST _transform/workflow-events-to-instances/_update
{
  "settings": {
    "max_page_search_size": 1000
  }
}
```

**Check for errors:**
```bash
# View transform details including failure reason
GET _transform/workflow-events-to-instances?pretty
```

## Testing

### 1. Insert Test Event

```bash
# Insert workflow.started event
POST /workflow-events-raw/_doc
{
  "@timestamp": "2026-04-20T16:00:00Z",
  "cloudEvent": {
    "specversion": "1.0",
    "type": "workflow.started",
    "source": "workflow-runtime",
    "id": "ce-test-001",
    "time": "2026-04-20T16:00:00Z",
    "datacontenttype": "application/json",
    "data": {
      "id": "wf-test-001",
      "name": "greeting",
      "namespace": "test",
      "version": "1.0.0",
      "status": "RUNNING",
      "input": {
        "name": "Alice"
      }
    }
  }
}
```

### 2. Wait for Transform

```bash
# Wait 10-40 seconds (frequency + delay)
sleep 40
```

### 3. Verify Result

```bash
# Check if workflow instance was created
GET /workflow-instances/_search
{
  "query": {
    "term": {
      "id": "wf-test-001"
    }
  }
}
```

**Expected Result:**
```json
{
  "id": "wf-test-001",
  "name": "greeting",
  "namespace": "test",
  "version": "1.0.0",
  "status": "RUNNING",
  "startTime": "2026-04-20T16:00:00.000Z",
  "endTime": "2026-04-20T16:00:00.000Z",
  "duration": 0,
  "input": {
    "name": "Alice"
  },
  "last_updated": "2026-04-20T16:00:00.000Z"
}
```

### 4. Insert Completion Event

```bash
POST /workflow-events-raw/_doc
{
  "@timestamp": "2026-04-20T16:00:05Z",
  "cloudEvent": {
    "specversion": "1.0",
    "type": "workflow.completed",
    "source": "workflow-runtime",
    "id": "ce-test-002",
    "time": "2026-04-20T16:00:05Z",
    "datacontenttype": "application/json",
    "data": {
      "id": "wf-test-001",
      "status": "COMPLETED",
      "output": {
        "greeting": "Hello, Alice!"
      }
    }
  }
}
```

### 5. Verify Update

```bash
# Wait and check again
sleep 40

GET /workflow-instances/_doc/wf-test-001
```

**Expected Result:**
```json
{
  "id": "wf-test-001",
  "name": "greeting",
  "status": "COMPLETED",
  "startTime": "2026-04-20T16:00:00.000Z",
  "endTime": "2026-04-20T16:00:05.000Z",
  "duration": 5000,
  "input": {
    "name": "Alice"
  },
  "output": {
    "greeting": "Hello, Alice!"
  }
}
```

## Update/Delete

### Update Transform

```bash
# Stop transform
POST _transform/workflow-events-to-instances/_stop

# Update definition (e.g., change frequency)
POST _transform/workflow-events-to-instances/_update
{
  "frequency": "20s"
}

# Restart
POST _transform/workflow-events-to-instances/_start
```

### Delete Transform

```bash
# Stop first
POST _transform/workflow-events-to-instances/_stop?wait_for_completion=true

# Delete
DELETE _transform/workflow-events-to-instances
```

## Performance Tuning

### Frequency
- **Lower frequency** (e.g., 30s): Better for batch processing, lower ES load
- **Higher frequency** (e.g., 5s): Lower latency, more ES overhead

### Page Size
- **Smaller pages** (e.g., 100): Lower memory, more queries
- **Larger pages** (e.g., 1000): Faster processing, more memory

### Delay
- **Shorter delay** (e.g., 10s): Lower latency
- **Longer delay** (e.g., 60s): Handles late-arriving events better

### Recommended Settings

**Development:**
```json
{
  "frequency": "5s",
  "sync": {
    "time": {
      "delay": "10s"
    }
  },
  "settings": {
    "max_page_search_size": 100
  }
}
```

**Production:**
```json
{
  "frequency": "30s",
  "sync": {
    "time": {
      "delay": "60s"
    }
  },
  "settings": {
    "max_page_search_size": 500
  }
}
```

## References

- [Elasticsearch Transform Documentation](https://www.elastic.co/guide/en/elasticsearch/reference/current/transforms.html)
- [Transform API Reference](https://www.elastic.co/guide/en/elasticsearch/reference/current/transform-apis.html)
- [ELASTICSEARCH-TRANSFORM-GUIDE.md](../../../../docs/ELASTICSEARCH-TRANSFORM-GUIDE.md) - Comprehensive guide
