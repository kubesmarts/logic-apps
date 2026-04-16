# JsonNodeScalar Analysis

**Date**: 2026-04-16  
**Status**: NOT USED (Redundant)

---

## Summary

`JsonNodeScalar.java` exists in `data-index-service/src/main/java/org/kubesmarts/logic/dataindex/graphql/` but is **NOT being used** anywhere in the codebase.

SmallRye GraphQL handles `JsonNode` serialization natively via configuration property.

---

## Analysis

### Location
```
data-index-service/src/main/java/org/kubesmarts/logic/dataindex/graphql/JsonNodeScalar.java
```

### Purpose (Intended)
- Custom GraphQL scalar adapter for Jackson `JsonNode`
- Maps `JsonNode` to GraphQL JSON scalar (String representation)
- Uses `@AdaptToScalar(Scalar.String.class)` annotation

### Usage Check
**Result**: ❌ **NOT USED**

```bash
# No imports found
grep -r "import.*JsonNodeScalar" data-index/
# Result: No matches
```

**Files mentioning JsonNodeScalar**:
1. `data-index-service/src/main/java/.../JsonNodeScalar.java` (the class itself)
2. `docs/archive/ARCHITECTURE-REORGANIZATION.md` (documentation)
3. `README.md` (documentation)

---

## Why It's Not Needed

SmallRye GraphQL handles `JsonNode` natively via **configuration property**:

**File**: `data-index-service/src/main/resources/application.properties`

```properties
# Map JsonNode to GraphQL Object scalar
quarkus.smallrye-graphql.scalar.com.fasterxml.jackson.databind.JsonNode=Object
```

This configuration tells SmallRye GraphQL to map `JsonNode` to GraphQL `Object` scalar automatically.

---

## JsonNode Fields in Domain Model

`JsonNode` is used in:

**WorkflowInstance** (`data-index-model`):
- `private JsonNode input;`
- `private JsonNode output;`

**TaskExecution** (`data-index-model`):
- `private JsonNode inputArgs;`
- `private JsonNode outputArgs;`

**GraphQL API** returns these objects directly:
- `getWorkflowInstance(id: String): WorkflowInstance`
- `getWorkflowInstances(): [WorkflowInstance]`
- `getTaskExecutions(workflowInstanceId: String): [TaskExecution]`

SmallRye GraphQL serializes the `JsonNode` fields using the configured scalar mapping.

---

## Recommendation

**Option 1**: Remove `JsonNodeScalar.java` (it's not used and configuration handles it)

**Option 2**: Keep it for reference (in case we need custom JsonNode serialization later)

**Current State**: Keeping it for now, but it can be safely removed without affecting functionality.

---

## GraphQL Schema

When querying the GraphQL schema, `JsonNode` fields appear as `Object`:

```graphql
type WorkflowInstance {
  id: String!
  input: Object    # JsonNode → Object scalar
  output: Object   # JsonNode → Object scalar
  # ... other fields
}

type TaskExecution {
  id: String!
  inputArgs: Object   # JsonNode → Object scalar
  outputArgs: Object  # JsonNode → Object scalar
  # ... other fields
}
```

---

## Testing

Test queries work correctly without `JsonNodeScalar`:

```graphql
query GetWorkflowWithData {
  getWorkflowInstance(id: "wf-success-001") {
    id
    input    # Returns JSON object
    output   # Returns JSON object
  }
}
```

**Conclusion**: SmallRye GraphQL configuration is sufficient. `JsonNodeScalar.java` is redundant.

---

**See Also**:
- [GraphQL Testing Guide](graphql-testing.md) - Test queries and usage
- [Domain Model Design](domain-model-design.md) - WorkflowInstance and TaskExecution spec
