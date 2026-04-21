# Elasticsearch Transform Implementation Guide

**Date**: 2026-04-20  
**Context**: Mode 2 Architecture - ES Transform for event processing

---

## Overview

**Elasticsearch Transform** is a built-in ES feature that automatically processes raw events into normalized documents. This is the core of **Mode 2** architecture.

```
FluentBit → ES raw indices → ES Transform (automatic) → Normalized indices → GraphQL
```

**Key Insight:** Instead of writing Java code to process events, we configure ES Transform pipelines that run inside Elasticsearch itself!

---

## Why ES Transform?

### Advantages
✅ **No Java code** - Transform logic expressed as Elasticsearch queries  
✅ **Automatic execution** - Runs continuously, processes new documents  
✅ **Native performance** - Runs inside ES cluster (no network overhead)  
✅ **Resilient** - Survives restarts, tracks progress automatically  
✅ **Scalable** - Distributed execution across ES nodes  

### vs. Java Event Processor
| Feature | ES Transform | Java Event Processor |
|---------|-------------|---------------------|
| **Language** | Elasticsearch Query DSL | Java |
| **Execution** | Inside ES cluster | Separate process |
| **State Management** | Automatic (ES checkpoints) | Manual (Kafka offsets, DB polling) |
| **Deployment** | ES configuration | Application deployment |
| **Debugging** | ES APIs, Kibana | Application logs, debugger |

---

## How ES Transform Works

### 1. Continuous Transform
```
[Raw Events Index]
        ↓
   ES Transform (runs every N seconds)
        ↓
[Normalized Index]
```

**Workflow:**
1. Transform reads new documents from source index
2. Applies aggregation/pivot logic
3. Writes results to destination index
4. Checkpoints progress (automatic resume)

### 2. Event Processing Model

**Raw Event:**
```json
{
  "@timestamp": "2026-04-20T15:00:00Z",
  "cloud event": {
    "type": "workflow.started",
    "source": "workflow-runtime",
    "data": {
      "id": "wf-123",
      "name": "greeting",
      "namespace": "default",
      "status": "RUNNING"
    }
  }
}
```

**Normalized Document (after transform):**
```json
{
  "id": "wf-123",
  "name": "greeting",
  "namespace": "default",
  "status": "RUNNING",
  "startTime": "2026-04-20T15:00:00Z",
  "lastUpdated": "2026-04-20T15:00:00Z"
}
```

---

## Creating ES Transforms

### Step 1: Create Raw Event Index

```bash
PUT /workflow-events-raw
{
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "cloudEvent": {
        "properties": {
          "type": { "type": "keyword" },
          "source": { "type": "keyword" },
          "id": { "type": "keyword" },
          "data": {
            "type": "flattened"
          }
        }
      }
    }
  }
}
```

### Step 2: Create Destination Index

```bash
PUT /workflow-instances
{
  "mappings": {
    "properties": {
      "id": { "type": "keyword" },
      "name": { "type": "keyword" },
      "namespace": { "type": "keyword" },
      "version": { "type": "keyword" },
      "status": { "type": "keyword" },
      "startTime": { "type": "date" },
      "endTime": { "type": "date" },
      "input": { "type": "flattened" },
      "output": { "type": "flattened" }
    }
  }
}
```

### Step 3: Create Transform

```bash
PUT _transform/workflow-events-to-instances
{
  "source": {
    "index": "workflow-events-raw",
    "query": {
      "bool": {
        "must": [
          {
            "terms": {
              "cloudEvent.type": [
                "workflow.started",
                "workflow.completed",
                "workflow.faulted"
              ]
            }
          }
        ]
      }
    }
  },
  "dest": {
    "index": "workflow-instances"
  },
  "frequency": "5s",
  "sync": {
    "time": {
      "field": "@timestamp",
      "delay": "60s"
    }
  },
  "pivot": {
    "group_by": {
      "workflow_id": {
        "terms": {
          "field": "cloudEvent.data.id"
        }
      }
    },
    "aggregations": {
      "latest_event": {
        "scripted_metric": {
          "init_script": "state.events = []",
          "map_script": "state.events.add(['timestamp': doc['@timestamp'].value, 'type': doc['cloudEvent.type'].value, 'data': params._source.cloudEvent.data])",
          "combine_script": "return state.events",
          "reduce_script": """
            def all_events = [];
            for (state in states) {
              all_events.addAll(state);
            }
            all_events.sort((a, b) -> b.timestamp.compareTo(a.timestamp));
            return all_events.size() > 0 ? all_events[0] : null;
          """
        }
      },
      "start_time": {
        "min": {
          "field": "@timestamp"
        }
      },
      "end_time": {
        "max": {
          "field": "@timestamp"
        }
      }
    }
  },
  "description": "Transform workflow events to workflow instances",
  "settings": {
    "max_page_search_size": 500
  }
}
```

### Step 4: Start Transform

```bash
POST _transform/workflow-events-to-instances/_start
```

### Step 5: Monitor Transform

```bash
GET _transform/workflow-events-to-instances/_stats
```

---

## Real-World Transform Example

### Workflow Instance Transform (Complete)

This transform merges multiple event types into a single workflow instance document:

```bash
PUT _transform/workflow-events-to-instances-v2
{
  "source": {
    "index": "workflow-events-raw"
  },
  "dest": {
    "index": "workflow-instances"
  },
  "frequency": "10s",
  "sync": {
    "time": {
      "field": "@timestamp",
      "delay": "30s"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "cloudEvent.data.id"
        }
      }
    },
    "aggregations": {
      "name": {
        "terms": {
          "field": "cloudEvent.data.name",
          "size": 1
        }
      },
      "namespace": {
        "terms": {
          "field": "cloudEvent.data.namespace",
          "size": 1
        }
      },
      "version": {
        "terms": {
          "field": "cloudEvent.data.version",
          "size": 1
        }
      },
      "status": {
        "terms": {
          "field": "cloudEvent.data.status",
          "size": 1,
          "order": { "_key": "desc" }
        }
      },
      "startTime": {
        "min": {
          "field": "@timestamp"
        }
      },
      "endTime": {
        "max": {
          "field": "@timestamp",
          "missing": null
        }
      },
      "input": {
        "top_hits": {
          "size": 1,
          "sort": [ { "@timestamp": { "order": "asc" } } ],
          "_source": { "includes": [ "cloudEvent.data.input" ] }
        }
      },
      "output": {
        "top_hits": {
          "size": 1,
          "sort": [ { "@timestamp": { "order": "desc" } } ],
          "_source": { "includes": [ "cloudEvent.data.output" ] }
        }
      }
    }
  }
}
```

**What this does:**
- Groups events by workflow ID
- Takes latest status (most recent event)
- Takes earliest timestamp as startTime
- Takes latest timestamp as endTime
- Takes input from first event
- Takes output from last event

---

## Task Execution Transform

Similar pattern for task executions:

```bash
PUT _transform/task-events-to-executions
{
  "source": {
    "index": "workflow-events-raw",
    "query": {
      "bool": {
        "must": [
          {
            "terms": {
              "cloudEvent.type": [
                "task.started",
                "task.completed",
                "task.faulted"
              ]
            }
          }
        ]
      }
    }
  },
  "dest": {
    "index": "task-executions"
  },
  "frequency": "10s",
  "sync": {
    "time": {
      "field": "@timestamp",
      "delay": "30s"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "cloudEvent.data.taskId"
        }
      }
    },
    "aggregations": {
      "taskName": {
        "terms": {
          "field": "cloudEvent.data.taskName",
          "size": 1
        }
      },
      "taskPosition": {
        "terms": {
          "field": "cloudEvent.data.taskPosition",
          "size": 1
        }
      },
      "status": {
        "terms": {
          "field": "cloudEvent.data.status",
          "size": 1,
          "order": { "_key": "desc" }
        }
      },
      "enter": {
        "min": {
          "field": "@timestamp"
        }
      },
      "exit": {
        "max": {
          "field": "@timestamp",
          "missing": null
        }
      }
    }
  }
}
```

---

## Advanced: Painless Script Transform

For complex transformations, use Painless scripts:

```bash
PUT _transform/workflow-events-advanced
{
  "source": {
    "index": "workflow-events-raw"
  },
  "dest": {
    "index": "workflow-instances"
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "cloudEvent.data.id"
        }
      }
    },
    "aggregations": {
      "computed_fields": {
        "scripted_metric": {
          "init_script": """
            state.events = []
          """,
          "map_script": """
            state.events.add([
              'timestamp': doc['@timestamp'].value,
              'type': doc['cloudEvent.type'].value,
              'data': params._source.cloudEvent.data
            ])
          """,
          "combine_script": """
            return state.events
          """,
          "reduce_script": """
            // Merge all events
            def all_events = [];
            for (state in states) {
              all_events.addAll(state);
            }
            
            // Sort by timestamp
            all_events.sort((a, b) -> a.timestamp.compareTo(b.timestamp));
            
            // Build workflow instance
            def instance = [:];
            instance.id = all_events[0].data.id;
            instance.name = all_events[0].data.name;
            instance.namespace = all_events[0].data.namespace;
            
            // Start time = first event
            instance.startTime = all_events[0].timestamp;
            
            // End time = last completed/faulted event
            for (event in all_events.reverse()) {
              if (event.type == 'workflow.completed' || event.type == 'workflow.faulted') {
                instance.endTime = event.timestamp;
                break;
              }
            }
            
            // Status = latest status
            instance.status = all_events[-1].data.status;
            
            // Input = first event
            instance.input = all_events[0].data.input;
            
            // Output = last event with output
            for (event in all_events.reverse()) {
              if (event.data.output != null) {
                instance.output = event.data.output;
                break;
              }
            }
            
            return instance;
          """
        }
      }
    }
  }
}
```

---

## Transform Management

### Start/Stop

```bash
# Start
POST _transform/workflow-events-to-instances/_start

# Stop
POST _transform/workflow-events-to-instances/_stop

# Stop and wait for checkpoint
POST _transform/workflow-events-to-instances/_stop?wait_for_completion=true
```

### Update Transform

```bash
# Must stop first
POST _transform/workflow-events-to-instances/_stop

# Update
POST _transform/workflow-events-to-instances/_update
{
  "frequency": "30s"
}

# Restart
POST _transform/workflow-events-to-instances/_start
```

### Delete Transform

```bash
POST _transform/workflow-events-to-instances/_stop
DELETE _transform/workflow-events-to-instances
```

### Monitor Progress

```bash
GET _transform/workflow-events-to-instances/_stats
```

**Response:**
```json
{
  "transforms": [
    {
      "id": "workflow-events-to-instances",
      "state": "started",
      "stats": {
        "documents_processed": 15430,
        "documents_indexed": 523,
        "trigger_count": 154,
        "index_time_in_ms": 1234,
        "search_time_in_ms": 5678
      },
      "checkpointing": {
        "last": {
          "checkpoint": 42,
          "timestamp_millis": 1713631200000
        },
        "next": {
          "checkpoint": 43,
          "timestamp_millis": 1713631210000
        }
      }
    }
  ]
}
```

---

## Integration with FluentBit

### FluentBit Configuration

```conf
[OUTPUT]
    Name  es
    Match workflow.events.*
    Host  elasticsearch
    Port  9200
    Index workflow-events-raw
    Type  _doc
    Suppress_Type_Name On
    
    # CloudEvents format
    Format json
    Json_Date_Key @timestamp
    Json_Date_Format iso8601
```

### FluentBit Parses CloudEvents

```conf
[FILTER]
    Name  parser
    Match workflow.events.*
    Key_Name log
    Parser json
    Reserve_Data On
```

---

## Deployment Strategy

### Development
1. **Manual index creation** - Create indices with mappings
2. **Create transforms** - Define transform pipelines
3. **Start transforms** - Begin processing
4. **Test with FluentBit** - Send sample events

### Production
1. **Index templates** - Auto-create indices with correct mappings
2. **Transform deployment** - Store transform definitions in version control
3. **CI/CD pipeline** - Apply transforms during deployment
4. **Monitoring** - Alert on transform failures

---

## Monitoring & Troubleshooting

### Health Checks

```bash
# Check transform status
GET _transform/_stats

# Check transform health
GET _cat/transforms?v

# Check specific transform
GET _transform/workflow-events-to-instances/_stats
```

### Common Issues

**Transform not processing new documents:**
```bash
# Check if transform is running
GET _transform/workflow-events-to-instances/_stats

# Restart transform
POST _transform/workflow-events-to-instances/_stop
POST _transform/workflow-events-to-instances/_start
```

**Slow transform performance:**
```bash
# Increase frequency (less frequent = better batch processing)
POST _transform/workflow-events-to-instances/_update
{
  "frequency": "30s"  // Was 5s
}

# Increase page size
POST _transform/workflow-events-to-instances/_update
{
  "settings": {
    "max_page_search_size": 1000  // Was 500
  }
}
```

**Missing documents in destination:**
```bash
# Check transform stats for errors
GET _transform/workflow-events-to-instances/_stats

# Check Elasticsearch logs
GET _cat/transforms?v&h=id,state,failure_reason
```

---

## Testing Transforms

### 1. Insert Test Event

```bash
POST /workflow-events-raw/_doc
{
  "@timestamp": "2026-04-20T15:00:00Z",
  "cloudEvent": {
    "type": "workflow.started",
    "source": "workflow-runtime",
    "id": "ce-test-1",
    "data": {
      "id": "wf-test-123",
      "name": "greeting",
      "namespace": "test",
      "version": "1.0.0",
      "status": "RUNNING",
      "input": {
        "name": "John"
      }
    }
  }
}
```

### 2. Wait for Transform

```bash
# Wait 10-30 seconds (depending on frequency setting)
sleep 30
```

### 3. Check Destination

```bash
GET /workflow-instances/_search
{
  "query": {
    "term": {
      "id": "wf-test-123"
    }
  }
}
```

### 4. Insert Completion Event

```bash
POST /workflow-events-raw/_doc
{
  "@timestamp": "2026-04-20T15:00:10Z",
  "cloudEvent": {
    "type": "workflow.completed",
    "source": "workflow-runtime",
    "id": "ce-test-2",
    "data": {
      "id": "wf-test-123",
      "status": "COMPLETED",
      "output": {
        "greeting": "Hello, John!"
      }
    }
  }
}
```

### 5. Verify Update

```bash
GET /workflow-instances/_doc/wf-test-123
```

**Expected:**
```json
{
  "id": "wf-test-123",
  "name": "greeting",
  "namespace": "test",
  "status": "COMPLETED",
  "startTime": "2026-04-20T15:00:00Z",
  "endTime": "2026-04-20T15:00:10Z",
  "input": {
    "name": "John"
  },
  "output": {
    "greeting": "Hello, John!"
  }
}
```

---

## Comparison: ES Transform vs Java Event Processor

| Aspect | ES Transform | Java Event Processor |
|--------|-------------|---------------------|
| **Deployment Complexity** | Low (ES config) | Medium (app + infra) |
| **Development Language** | Query DSL + Painless | Java |
| **Debugging** | ES APIs, Kibana | IDE, logs |
| **Performance** | Native ES (fast) | Network overhead |
| **Flexibility** | Limited to ES capabilities | Unlimited (Java code) |
| **State Management** | Automatic checkpoints | Manual (Kafka/DB) |
| **Error Handling** | Built-in retry | Custom code |
| **Scalability** | ES cluster scaling | Application scaling |
| **Cost** | Included in ES | Additional compute |

**Recommendation:**
- **Use ES Transform** when event logic is simple aggregation/merging
- **Use Java Processor** when needing complex business logic, external API calls, or fine-grained control

---

## Next Steps

1. ✅ **Elasticsearch storage implementation complete** (our work today)
2. ⏳ **Create ES Transform pipelines** (next: implement transforms above)
3. ⏳ **Configure FluentBit** to send events to raw indices
4. ⏳ **Test end-to-end** - FluentBit → ES Transform → GraphQL
5. ⏳ **Monitoring setup** - Grafana dashboards for transform health

---

## References

- **Elasticsearch Transform Docs**: https://www.elastic.co/guide/en/elasticsearch/reference/current/transforms.html
- **Painless Scripting**: https://www.elastic.co/guide/en/elasticsearch/painless/current/index.html
- **Transform API**: https://www.elastic.co/guide/en/elasticsearch/reference/current/transform-apis.html
- **ARCHITECTURE-SUMMARY.md**: Mode 2 architecture details
