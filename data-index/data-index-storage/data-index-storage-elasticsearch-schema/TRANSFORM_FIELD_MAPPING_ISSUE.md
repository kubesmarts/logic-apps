# Transform Field Mapping Issue

## Problem

The Elasticsearch transforms use `terms` aggregations for string fields (name, version, namespace, taskName). 
This creates bucket structures instead of simple string values.

### Current Behavior

**Transform configuration:**
```json
"name": {
  "terms": {
    "field": "workflowName.keyword",
    "size": 1
  }
}
```

**Resulting document:**
```json
{
  "id": "01KRCA0Q7JYN7HDZFDKYTJZ4HE",
  "name": {
    "petstore": 1      // ❌ Object with workflow name as key
  },
  "status": {
    "FAULTED": {
      "priority": 3.0
    }
  }
}
```

### Impact

1. **GraphQL filters fail**: `filter:{name:{eq:"petstore"}}` returns nothing because name isn't a string
2. **Java mapping fails**: WorkflowInstance.name expects String, not Map<String, Integer>
3. **Client queries broken**: Cannot filter by workflow name, version, or namespace

## Solution

Replace `terms` aggregations with `scripted_metric` aggregations that extract simple string values.

### Fixed Configuration

**For immutable string fields (first value wins):**
```json
"name": {
  "scripted_metric": {
    "init_script": "state.value = null",
    "map_script": "if (state.value == null && params._source.containsKey('workflowName')) { state.value = params._source.workflowName }",
    "combine_script": "return state.value",
    "reduce_script": "return params._aggs.stream().filter(v -> v != null).findFirst().orElse(null)"
  }
}
```

**Resulting document:**
```json
{
  "id": "01KRCA0Q7JYN7HDZFDKYTJZ4HE",
  "name": "petstore",    // ✅ Simple string value
  "version": "1.0.0",    // ✅ Simple string value
  "namespace": "org.acme", // ✅ Simple string value
  "status": "FAULTED"    // ✅ Already fixed
}
```

## Fields That Need Fixing

### workflow-instances-transform.json
- `name` (workflowName)
- `version` (workflowVersion)
- `namespace` (workflowNamespace)

### task-executions-transform.json  
- `taskName`
- `taskPosition`

## Test Coverage

See `TransformFieldMappingTest.java` which:
1. Indexes a raw workflow event
2. Waits for transform to process
3. Queries by name filter
4. **Currently FAILS** because name is a bucket structure
5. **Will PASS** after transforms are fixed

## References

- Issue discovered: petstore workflow query returns empty with name filter
- Root cause: `terms` aggregation creates buckets, not values
- Related: Status field was already fixed to use priority-based aggregation producing simple strings
