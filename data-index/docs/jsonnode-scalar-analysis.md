# JSON Data Exposure in GraphQL API

**Date**: 2026-04-24  
**Status**: Implemented using String Getters

---

## Summary

Workflow and task input/output data (stored as JSONB in PostgreSQL) is exposed in the GraphQL API as **JSON-formatted strings** via getter methods.

This is **NOT** the industry standard pattern (custom GraphQL scalar would be preferred), but is a pragmatic solution that works with SmallRye GraphQL's type system.

---

## Current Implementation

### Domain Model Fields

**WorkflowInstance** (`data-index-model`):
```java
@Ignore
private JsonNode input;   // Internal - hidden from GraphQL

@Ignore
private JsonNode output;  // Internal - hidden from GraphQL

@JsonProperty("inputData")
public String getInputData() {
    return input != null ? input.toString() : null;
}

@JsonProperty("outputData")
public String getOutputData() {
    return output != null ? output.toString() : null;
}
```

**TaskExecution** (`data-index-model`):
```java
@Ignore
private JsonNode input;   // Internal - hidden from GraphQL

@Ignore
private JsonNode output;  // Internal - hidden from GraphQL

@JsonProperty("inputData")
public String getInputData() {
    return input != null ? input.toString() : null;
}

@JsonProperty("outputData")
public String getOutputData() {
    return output != null ? output.toString() : null;
}
```

---

## GraphQL Schema

JSON fields appear as **String** in GraphQL schema:

```graphql
type WorkflowInstance {
  id: String!
  name: String!
  inputData: String    # JSON as string
  outputData: String   # JSON as string
  # ... other fields
}

type TaskExecution {
  id: String!
  taskName: String!
  inputData: String    # JSON as string
  outputData: String   # JSON as string
  # ... other fields
}
```

---

## GraphQL Query Examples

**Query:**
```graphql
{
  getWorkflowInstances(limit: 1) {
    id
    name
    inputData
    outputData
    taskExecutions {
      id
      taskName
      inputData
      outputData
    }
  }
}
```

**Response:**
```json
{
  "data": {
    "getWorkflowInstances": [
      {
        "id": "01KPY9HWA7HJ87K12KT3M7HSTW",
        "name": "simple-set",
        "inputData": "{}",
        "outputData": "{\"mode\":\"Mode 1\",\"completed\":true}",
        "taskExecutions": [
          {
            "id": "c652833d-baf1-35e8-a432-dfee5c05006e",
            "taskName": "set-0",
            "inputData": "{}",
            "outputData": null
          }
        ]
      }
    ]
  }
}
```

---

## Limitations

### ❌ Cannot Query Into JSON Structure

You **CANNOT** use GraphQL selection syntax to query specific fields within the JSON:

```graphql
{
  getWorkflowInstances {
    inputData {
      orderId    # ❌ This doesn't work
      customerId # ❌ This doesn't work
    }
  }
}
```

The JSON is **opaque** to GraphQL - you get the entire string.

### ✅ Can Filter By JSON Content (Database Level)

You **CAN** filter workflows based on JSON content using database-level JSONB queries (not yet implemented in GraphQL API, but supported by storage layer):

```java
// Storage layer supports JSON path queries
AttributeFilter filter = new AttributeFilter("inputData", EQUAL, "value");
filter.setJson(true);  // Enables JSONB query
```

**Planned GraphQL filter support:**
```graphql
{
  getWorkflowInstances(
    where: {
      inputData: { path: "$.orderId", equals: "123" }
    }
  ) {
    id
    name
    inputData  # Returns full JSON string
  }
}
```

---

## Why String Instead of Custom Scalar?

### Attempted: Custom GraphQL Scalar

Initial attempts to use a custom GraphQL JSON scalar failed:

1. **Jackson Jandex Indexing Issue**: SmallRye GraphQL couldn't scan JsonNode class
   - Solution: Added `quarkus.index-dependency.jackson-databind`
   
2. **SubselectionRequired Error**: GraphQL treated JsonNode as an object type requiring field selection
   - Error: `Subselection required for type 'JsonNode' of field 'input'`
   - SmallRye GraphQL mapped JsonNode as a GraphQL object, not a scalar

### Current Solution: String Getters

The String getter approach:
- ✅ Works immediately - no GraphQL schema issues
- ✅ Clients can parse JSON on their side
- ✅ Simple implementation - no custom scalar registration needed
- ❌ Not industry standard (custom scalar is preferred)
- ❌ JSON is opaque to GraphQL (no field-level selection)

---

## Industry Standard Pattern

The **proper** approach would be a custom GraphQL scalar:

```java
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD})
@Name("JSON")
public @interface JsonScalar {
}

// Usage in model
@JsonScalar
private JsonNode input;

@JsonScalar
private JsonNode output;
```

With scalar coercion registered in SmallRye GraphQL, the schema would show:

```graphql
scalar JSON

type WorkflowInstance {
  input: JSON    # Proper scalar, not String
  output: JSON
}
```

**Why we didn't use this:**
- SmallRye GraphQL's automatic JsonNode handling treated it as an object, not scalar
- Requires deeper integration with SmallRye GraphQL's scalar registry
- Time constraint - String approach works for v1.0.0

---

## Client-Side Usage

Clients receive JSON as strings and must parse them:

**JavaScript:**
```javascript
const result = await graphqlQuery();
const workflow = result.data.getWorkflowInstances[0];
const inputData = JSON.parse(workflow.inputData);
console.log(inputData.orderId);
```

**Java:**
```java
WorkflowInstance wf = graphqlClient.getWorkflowInstance(id);
ObjectMapper mapper = new ObjectMapper();
JsonNode input = mapper.readTree(wf.getInputData());
String orderId = input.get("orderId").asText();
```

---

## Future Improvements

1. **Implement proper GraphQL JSON scalar** - Industry standard pattern
2. **Add JSON path filtering** - Query workflows by nested JSON fields
3. **Consider GraphQL federation** - If querying into JSON becomes critical requirement

---

## Configuration

**Jandex Indexing** (required for SmallRye GraphQL to scan Jackson classes):

```properties
# data-index-service/src/main/resources/application.properties
quarkus.index-dependency.jackson-databind.group-id=com.fasterxml.jackson.core
quarkus.index-dependency.jackson-databind.artifact-id=jackson-databind
```

---

## See Also

- [GRAPHQL_API.md](development/GRAPHQL_API.md) - Complete GraphQL API reference
- [DOMAIN_MODEL.md](development/DOMAIN_MODEL.md) - WorkflowInstance and TaskExecution design
- [DATABASE_SCHEMA.md](development/DATABASE_SCHEMA.md) - PostgreSQL JSONB columns
