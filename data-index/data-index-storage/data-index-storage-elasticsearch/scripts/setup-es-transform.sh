#!/bin/bash
#
# Elasticsearch Transform + ILM Setup Script
#
# This script configures Elasticsearch for Data Index Mode 2:
# - Creates ILM policy for automatic event cleanup (7 days)
# - Creates raw event indices with flattened fields
# - Creates normalized indices with flattened fields
# - Creates and starts continuous ES Transforms
#
# Usage:
#   ./setup-es-transform.sh [elasticsearch-host]
#
# Example:
#   ./setup-es-transform.sh localhost:9200
#   ./setup-es-transform.sh https://elasticsearch.prod.example.com:9200
#

set -e

ES_HOST="${1:-localhost:9200}"
ES_PROTOCOL="${ES_PROTOCOL:-http}"

echo "======================================================"
echo "Data Index Elasticsearch Transform Setup"
echo "======================================================"
echo "Elasticsearch Host: ${ES_PROTOCOL}://${ES_HOST}"
echo ""

# Check if Elasticsearch is reachable
echo "Checking Elasticsearch connection..."
if ! curl -s "${ES_PROTOCOL}://${ES_HOST}/_cluster/health" > /dev/null; then
    echo "ERROR: Cannot connect to Elasticsearch at ${ES_PROTOCOL}://${ES_HOST}"
    echo "Please check that Elasticsearch is running and the host is correct."
    exit 1
fi
echo "✓ Elasticsearch is reachable"
echo ""

# Step 1: Create ILM Policy
echo "Step 1: Creating ILM policy for event retention (7 days)..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/_ilm/policy/data-index-events-retention" \
  -H 'Content-Type: application/json' \
  -d '{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_primary_shard_size": "50GB"
          }
        }
      },
      "delete": {
        "min_age": "7d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}'
echo ""
echo "✓ ILM policy created"
echo ""

# Step 2: Create Raw Event Indices
echo "Step 2: Creating raw event indices with flattened fields..."

echo "  - Creating workflow-events index..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/workflow-events" \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": {
    "index.lifecycle.name": "data-index-events-retention",
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "event_id": {"type": "keyword"},
      "event_type": {"type": "keyword"},
      "event_time": {"type": "date"},
      "instance_id": {"type": "keyword"},
      "workflow_name": {"type": "keyword"},
      "workflow_version": {"type": "keyword"},
      "workflow_namespace": {"type": "keyword"},
      "status": {"type": "keyword"},
      "start_time": {"type": "date"},
      "end_time": {"type": "date"},
      "input_data": {"type": "flattened"},
      "output_data": {"type": "flattened"},
      "error": {"type": "object", "enabled": false}
    }
  }
}'
echo ""

echo "  - Creating task-events index..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/task-events" \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": {
    "index.lifecycle.name": "data-index-events-retention",
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "event_id": {"type": "keyword"},
      "event_type": {"type": "keyword"},
      "event_time": {"type": "date"},
      "instance_id": {"type": "keyword"},
      "task_execution_id": {"type": "keyword"},
      "task_position": {"type": "keyword"},
      "task_name": {"type": "keyword"},
      "start_time": {"type": "date"},
      "end_time": {"type": "date"},
      "input_args": {"type": "flattened"},
      "output_args": {"type": "flattened"},
      "error": {"type": "object", "enabled": false}
    }
  }
}'
echo ""
echo "✓ Raw event indices created"
echo ""

# Step 3: Create Normalized Indices
echo "Step 3: Creating normalized indices with flattened fields..."

echo "  - Creating workflow-instances index..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/workflow-instances" \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "id": {"type": "keyword"},
      "name": {"type": "keyword"},
      "version": {"type": "keyword"},
      "namespace": {"type": "keyword"},
      "status": {"type": "keyword"},
      "start_time": {"type": "date"},
      "end_time": {"type": "date"},
      "input": {"type": "flattened"},
      "output": {"type": "flattened"},
      "error": {
        "properties": {
          "type": {"type": "keyword"},
          "title": {"type": "text"},
          "detail": {"type": "text"},
          "status": {"type": "integer"}
        }
      },
      "last_update": {"type": "date"}
    }
  }
}'
echo ""

echo "  - Creating task-executions index..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/task-executions" \
  -H 'Content-Type: application/json' \
  -d '{
  "settings": {
    "number_of_shards": 3,
    "number_of_replicas": 1
  },
  "mappings": {
    "properties": {
      "composite_id": {"type": "keyword"},
      "instance_id": {"type": "keyword"},
      "task_position": {"type": "keyword"},
      "task_name": {"type": "keyword"},
      "enter": {"type": "date"},
      "exit": {"type": "date"},
      "input_args": {"type": "flattened"},
      "output_args": {"type": "flattened"},
      "error_message": {"type": "text"},
      "last_update": {"type": "date"}
    }
  }
}'
echo ""
echo "✓ Normalized indices created"
echo ""

# Step 4: Create and Start Transforms
echo "Step 4: Creating and starting ES Transforms..."

echo "  - Creating workflow-instances transform..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/_transform/workflow-instances-transform" \
  -H 'Content-Type: application/json' \
  -d @- << 'EOF'
{
  "source": {
    "index": "workflow-events",
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "event_time": {
                "gte": "now-1h"
              }
            }
          },
          {
            "bool": {
              "must_not": [
                {"term": {"event_type": "workflow.instance.completed"}},
                {"term": {"event_type": "workflow.instance.faulted"}},
                {"term": {"event_type": "workflow.instance.cancelled"}}
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
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "event_time",
      "delay": "5m"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "instance_id"
        }
      }
    },
    "aggregations": {
      "name": {"terms": {"field": "workflow_name"}},
      "version": {"terms": {"field": "workflow_version"}},
      "namespace": {"terms": {"field": "workflow_namespace"}},
      "status": {
        "scripted_metric": {
          "init_script": "state.events = []",
          "map_script": "state.events.add(['status': doc['status'].value, 'event_time': doc['event_time'].value])",
          "combine_script": "return state.events",
          "reduce_script": "def all = _states.flatten(); def terminal = all.find { e -> e.status == 'COMPLETED' || e.status == 'FAULTED' || e.status == 'CANCELLED' }; if (terminal != null) return terminal.status; return all.max { it.event_time }.status;"
        }
      },
      "start_time": {"min": {"field": "start_time"}},
      "end_time": {"max": {"field": "end_time"}},
      "input": {"top_hits": {"size": 1, "_source": ["input_data"]}},
      "output": {"top_hits": {"size": 1, "_source": ["output_data"]}},
      "last_update": {"max": {"field": "event_time"}}
    }
  }
}
EOF
echo ""

echo "  - Starting workflow-instances transform..."
curl -X POST "${ES_PROTOCOL}://${ES_HOST}/_transform/workflow-instances-transform/_start"
echo ""

echo "  - Creating task-executions transform..."
curl -X PUT "${ES_PROTOCOL}://${ES_HOST}/_transform/task-executions-transform" \
  -H 'Content-Type: application/json' \
  -d @- << 'EOF'
{
  "source": {
    "index": "task-events",
    "query": {
      "bool": {
        "should": [
          {"range": {"event_time": {"gte": "now-1h"}}},
          {
            "bool": {
              "must_not": [
                {"term": {"event_type": "task.execution.completed"}},
                {"term": {"event_type": "task.execution.faulted"}}
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
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "event_time",
      "delay": "5m"
    }
  },
  "pivot": {
    "group_by": {
      "composite_id": {
        "terms": {
          "script": {
            "source": "doc['instance_id'].value + ':' + doc['task_position'].value"
          }
        }
      }
    },
    "aggregations": {
      "instance_id": {"terms": {"field": "instance_id"}},
      "task_position": {"terms": {"field": "task_position"}},
      "task_name": {"terms": {"field": "task_name"}},
      "enter": {
        "scripted_metric": {
          "init_script": "state.times = []",
          "map_script": "if (doc.containsKey('start_time') && doc['start_time'].size() > 0) { state.times.add(doc['start_time'].value) }",
          "combine_script": "return state.times",
          "reduce_script": "def all = _states.flatten(); return all.size() > 0 ? all.min() : null"
        }
      },
      "exit": {
        "scripted_metric": {
          "init_script": "state.times = []",
          "map_script": "if (doc.containsKey('end_time') && doc['end_time'].size() > 0) { state.times.add(doc['end_time'].value) }",
          "combine_script": "return state.times",
          "reduce_script": "def all = _states.flatten(); return all.size() > 0 ? all.max() : null"
        }
      },
      "input_args": {"top_hits": {"size": 1, "_source": ["input_args"]}},
      "output_args": {"top_hits": {"size": 1, "_source": ["output_args"]}},
      "last_update": {"max": {"field": "event_time"}}
    }
  }
}
EOF
echo ""

echo "  - Starting task-executions transform..."
curl -X POST "${ES_PROTOCOL}://${ES_HOST}/_transform/task-executions-transform/_start"
echo ""

echo "✓ ES Transforms created and started"
echo ""

# Verify setup
echo "======================================================"
echo "Setup Complete!"
echo "======================================================"
echo ""
echo "Verifying setup..."
echo ""

echo "ILM Policy:"
curl -s "${ES_PROTOCOL}://${ES_HOST}/_ilm/policy/data-index-events-retention" | jq -r '.data-index-events-retention.policy.phases | keys[]'
echo ""

echo "Indices:"
curl -s "${ES_PROTOCOL}://${ES_HOST}/_cat/indices/workflow-events,task-events,workflow-instances,task-executions?v"
echo ""

echo "Transforms:"
curl -s "${ES_PROTOCOL}://${ES_HOST}/_transform/workflow-instances-transform,task-executions-transform/_stats" | jq -r '.transforms[] | "\(.id): \(.state)"'
echo ""

echo "======================================================"
echo "Next Steps:"
echo "======================================================"
echo "1. Configure FluentBit to send events to workflow-events and task-events indices"
echo "2. Monitor transform progress:"
echo "   curl ${ES_PROTOCOL}://${ES_HOST}/_transform/workflow-instances-transform/_stats"
echo "3. Query normalized indices:"
echo "   curl ${ES_PROTOCOL}://${ES_HOST}/workflow-instances/_search"
echo ""
echo "For more information, see:"
echo "  - docs/ARCHITECTURE-SUMMARY.md"
echo "  - data-index-storage/data-index-storage-elasticsearch/README.md"
echo ""
