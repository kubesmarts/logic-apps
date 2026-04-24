# Claude AI Assistant Guidelines - KubeSmarts Logic Apps

**Project:** Data Index v1.0.0 for Serverless Workflow 1.0.0  
**Status:** Production Ready (MODE 1)  
**Last Updated:** 2026-04-24

---

## Project Overview

This is a **read-only query service** for Serverless Workflow (SW 1.0.0) runtime execution data. It provides a GraphQL API for querying workflow instances and task executions.

**What it does:**
- Captures Quarkus Flow structured logging events via FluentBit
- Stores raw events in PostgreSQL JSONB columns
- Normalizes events using PostgreSQL BEFORE INSERT triggers (real-time)
- Exposes normalized data via GraphQL API (SmallRye GraphQL)

**What it does NOT do:**
- Does NOT execute workflows (that's Quarkus Flow's job)
- Does NOT modify workflow state (read-only)
- Does NOT use polling/Event Processor (trigger-based since Phase 1)

---

## Architecture (MODE 1 - Production)

```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                      ↓ (FluentBit tail)
              PostgreSQL raw tables (JSONB)
                      ↓ (BEFORE INSERT triggers)
              PostgreSQL normalized tables
                      ↓ (JPA/Hibernate)
              GraphQL API (SmallRye GraphQL)
```

**Key Components:**
- **FluentBit DaemonSet** - Tails log files, sends to PostgreSQL
- **PostgreSQL Triggers** - Normalize events immediately on INSERT
- **Data Index Service** - Quarkus app with GraphQL API
- **JPA Entities** - Map to normalized tables (workflow_instances, task_instances)

**NOT used in MODE 1:**
- ❌ Event Processor service (removed in Phase 1)
- ❌ Polling (triggers are immediate)
- ❌ Staging tables (raw tables → triggers → normalized tables)

---

## Code Structure

```
data-index/
├── data-index-model/              # Domain model (GraphQL types)
│   ├── WorkflowInstance.java
│   ├── TaskExecution.java
│   ├── WorkflowInstanceStatus.java
│   └── api/                       # Storage interfaces
├── data-index-storage/
│   ├── data-index-storage-common/ # Abstract storage layer
│   ├── data-index-storage-migrations/ # Flyway SQL migrations
│   ├── data-index-storage-postgresql/ # JPA implementation
│   └── data-index-storage-elasticsearch/ # ES implementation (unused)
├── data-index-service/            # Quarkus GraphQL service
│   ├── graphql/                   # GraphQL API
│   │   ├── WorkflowInstanceGraphQLApi.java
│   │   ├── filter/                # GraphQL filter converters
│   │   └── GraphQLConfiguration.java
│   └── service/                   # JAX-RS resources
│       └── RootResource.java      # Landing page
├── data-index-integration-tests/  # E2E tests
└── docs/                          # Documentation
```

---

## Important Design Decisions

### 1. Trigger-Based Normalization (Not Polling)

**DO:**
- ✅ Raw events stored in `workflow_events_raw` and `task_events_raw` (tag, time, data JSONB)
- ✅ Triggers extract fields from JSONB and UPSERT into normalized tables
- ✅ COALESCE handles out-of-order events
- ✅ Real-time processing (< 1ms latency)

**DON'T:**
- ❌ Don't add Event Processor service (we removed it in Phase 1)
- ❌ Don't use polling architecture
- ❌ Don't reference "staging tables" (we use raw tables + triggers)

**See:** `data-index/docs/deployment/MODE1_HANDOFF.md`

### 2. JSON Field Exposure (String Getters)

**DO:**
- ✅ Store input/output as JSONB in PostgreSQL
- ✅ Expose via String getters in GraphQL: `getInputData()`, `getOutputData()`
- ✅ Return JSON as string: `input.toString()`
- ✅ Hide JsonNode fields with `@Ignore`

**DON'T:**
- ❌ Don't expose JsonNode fields directly (GraphQL schema issues)
- ❌ Don't try to use custom GraphQL scalar (we tried, SmallRye issues)
- ❌ Don't reference old field names: `inputArgs`, `outputArgs` (now: `input`, `output`)

**Why String approach:**
- Pragmatic solution that works immediately
- Clients parse JSON client-side
- NOT industry standard (custom scalar preferred, but complex)
- JSON is opaque to GraphQL (no field-level selection)

**See:** `data-index/docs/jsonnode-scalar-analysis.md`

### 3. Field Names - Critical Mapping

**Database columns → JPA Entity → GraphQL:**
```
task_instances.task_execution_id → TaskInstanceEntity.taskExecutionId → TaskExecution.id
task_instances.start             → TaskInstanceEntity.start          → TaskExecution.start (@JsonProperty("startDate"))
task_instances.end               → TaskInstanceEntity.end            → TaskExecution.end (@JsonProperty("endDate"))
task_instances.input             → TaskInstanceEntity.input          → TaskExecution.input (@Ignore, exposed via getInputData())
```

**IMPORTANT:**
- Database uses `start`/`end` (reserved SQL keywords, quoted)
- GraphQL uses `startDate`/`endDate` (via `@JsonProperty`)
- Never use old names: `enter`/`exit`, `triggerTime`/`leaveTime`

### 4. Entity Naming - Only Two Entities

**DO:**
- ✅ `WorkflowInstanceEntity` → `workflow_instances` table
- ✅ `TaskInstanceEntity` → `task_instances` table

**DON'T:**
- ❌ Don't create `TaskExecutionEntity` (we deleted it, caused confusion)
- ❌ Only `TaskInstanceEntity` exists for task data

**Mappers:**
- `WorkflowInstanceEntityMapper` - Domain ↔ JPA for workflows
- `TaskInstanceEntityMapper` - Domain ↔ JPA for tasks

---

## Dependencies

### Minimal External Dependencies

**Kogito (only 1):**
```xml
<dependency>
  <groupId>org.kie.kogito</groupId>
  <artifactId>persistence-commons-api</artifactId>
  <version>999-SNAPSHOT</version>
</dependency>
```

**What we use from it:**
- `org.kie.kogito.persistence.api.Storage`
- `org.kie.kogito.persistence.api.query.AttributeFilter`
- `org.kie.kogito.persistence.api.query.AttributeSort`

**DON'T add these (removed in Phase 1):**
- ❌ `kogito-api`
- ❌ `kogito-events-core`
- ❌ `jobs-common-embedded`
- ❌ `kogito-addons-*-jpa`
- ❌ `kie-addons-quarkus-flyway`

**Apache Snapshots Repository:**
- Required for `persistence-commons-api:999-SNAPSHOT`
- TODO: Can be removed if we inline the source or use released version

---

## Code Style & Conventions

### Java Code

**DO:**
- ✅ Use MapStruct for entity mapping
- ✅ Use `@Ignore` for fields hidden from GraphQL
- ✅ Use `@JsonProperty` for GraphQL field name mapping
- ✅ Keep domain model (data-index-model) clean of JPA annotations
- ✅ Keep JPA entities (storage-postgresql) separate from domain model
- ✅ Write integration tests (`*IT.java`) not unit tests

**DON'T:**
- ❌ Don't mix domain model and JPA entities
- ❌ Don't add comments explaining WHAT (code should be self-documenting)
- ❌ Only add comments for WHY (constraints, workarounds, non-obvious invariants)
- ❌ Don't add features beyond requirements (YAGNI)
- ❌ Don't add error handling for impossible scenarios

### Database

**DO:**
- ✅ Use Flyway migrations in `data-index-storage-migrations`
- ✅ Use JSONB for dynamic/heterogeneous data (input/output)
- ✅ Use triggers for normalization
- ✅ Use COALESCE in UPSERT for idempotency

**DON'T:**
- ❌ Don't manually create tables (use Flyway)
- ❌ Don't add staging tables (we use raw → triggers → normalized)

### GraphQL

**DO:**
- ✅ Use SmallRye GraphQL annotations
- ✅ Keep API in `WorkflowInstanceGraphQLApi.java`
- ✅ Convert GraphQL filters to storage layer filters in `FilterConverter`
- ✅ Return JSON data as String (via getters)

**DON'T:**
- ❌ Don't expose internal types (entities, mappers) in GraphQL
- ❌ Don't try custom scalars for JsonNode (causes issues)

---

## Testing Approach

### Integration Tests

**DO:**
- ✅ Write `@QuarkusTest` integration tests
- ✅ Set up test data in `@BeforeEach` using JPA entities
- ✅ Clean up in `@AfterEach`
- ✅ Test GraphQL queries with REST Assured
- ✅ Use known test IDs for assertions

**Example:**
```java
@QuarkusTest
public class WorkflowInstanceGraphQLApiTest {
    @Inject EntityManager em;
    
    @BeforeEach
    @Transactional
    public void setupTestData() {
        // Create JPA entities
        WorkflowInstanceEntity wf = new WorkflowInstanceEntity();
        wf.setId("test-123");
        // ... set other fields
        em.persist(wf);
    }
    
    @Test
    public void testQuery() {
        given()
            .contentType(ContentType.JSON)
            .body("{\"query\":\"{ ... }\"}")
        .when()
            .post("/graphql")
        .then()
            .statusCode(200);
    }
}
```

**DON'T:**
- ❌ Don't write unit tests (we test the full stack)
- ❌ Don't mock the database (use real PostgreSQL via Quarkus dev services)
- ❌ Don't assume data exists (create it in @BeforeEach)

---

## Build & Deployment

### Local Build

```bash
# Full build
mvn clean install -DskipTests

# Data Index only
cd data-index
mvn clean install -DskipTests

# Container image
cd data-index/data-index-service
mvn clean package -DskipTests
# Result: kubesmarts/data-index-service:999-SNAPSHOT
```

### KIND Deployment

```bash
cd data-index/scripts/kind

# 1. Setup cluster and PostgreSQL
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh

# 2. Deploy data-index service
./deploy-data-index.sh postgresql-polling  # name is legacy, uses triggers

# 3. Deploy FluentBit (MODE 1)
cd ../fluentbit/mode1-postgresql-polling
./generate-configmap.sh  # Generate from source files
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml

# 4. Deploy test workflow app
cd ../../kind
./deploy-workflow-app.sh

# 5. Test
curl http://localhost:30080/graphql -d '{"query":"..."}'
```

**See:** `data-index/docs/deployment/MODE1_E2E_TESTING.md`

---

## Common Tasks

### Adding a New GraphQL Query

1. **Add method to `WorkflowInstanceGraphQLApi`:**
```java
@Query
public List<WorkflowInstance> getWorkflowsByStatus(
    @DefaultValue("RUNNING") WorkflowInstanceStatus status
) {
    AttributeFilter filter = new AttributeFilter("status", EQUAL, status);
    return workflowInstanceStorage.query()
        .filter(List.of(filter))
        .execute();
}
```

2. **Test it:**
```graphql
{ getWorkflowsByStatus(status: COMPLETED) { id name } }
```

3. **Add integration test** in `WorkflowInstanceGraphQLApiTest`

### Adding a Database Field

1. **Create Flyway migration** in `data-index-storage-migrations`:
```sql
-- V2__add_priority_field.sql
ALTER TABLE workflow_instances ADD COLUMN priority INTEGER;
```

2. **Update JPA entity:**
```java
// WorkflowInstanceEntity.java
private Integer priority;
// + getters/setters
```

3. **Update domain model:**
```java
// WorkflowInstance.java
private Integer priority;
// + getters/setters
```

4. **Update MapStruct mapper** (auto-maps if names match)

5. **Update trigger** if field comes from events:
```sql
-- In normalize_workflow_event() function
priority = (NEW.data->>'priority')::integer
```

### Updating Documentation

**When to update:**
- Architecture changes → `ARCHITECTURE-SUMMARY.md`
- New deployment mode → `deployment/MODE*_*.md`
- GraphQL API changes → `development/GRAPHQL_API.md`
- Database schema changes → `development/DATABASE_SCHEMA.md`

**Where files are:**
- Main docs index: `data-index/docs/README.md`
- Architecture: `data-index/docs/ARCHITECTURE-*.md`
- Deployment guides: `data-index/docs/deployment/`
- Development guides: `data-index/docs/development/`
- Operations guides: `data-index/docs/operations/`

---

## What NOT to Do

### ❌ Architecture

- Don't add Event Processor service (we use triggers)
- Don't use polling architecture
- Don't create staging tables
- Don't add Kafka (MODE 3 not implemented)
- Don't add MODE 2 Elasticsearch (not implemented yet)

### ❌ Dependencies

- Don't add more Kogito dependencies (only persistence-commons-api)
- Don't add kogito-apps-build-parent (removed)
- Don't add kogito-apps-bom (removed)

### ❌ Code

- Don't create TaskExecutionEntity (only TaskInstanceEntity exists)
- Don't use old field names: inputArgs/outputArgs, enter/exit, triggerTime/leaveTime
- Don't expose JsonNode directly in GraphQL (use String getters)
- Don't try to implement custom GraphQL scalar for JsonNode (tried, doesn't work well)

### ❌ Testing

- Don't skip test data setup (use @BeforeEach)
- Don't leave test data behind (use @AfterEach)
- Don't test against empty database

---

## Troubleshooting

### Build Issues

**"Cannot find symbol: class TaskExecutionEntity"**
- This entity was deleted. Use `TaskInstanceEntity`

**"Missing version for org.kie.kogito:persistence-commons-api"**
- Check `data-index/pom.xml` has the dependency in `<dependencyManagement>`

**"GraphQL schema error: JsonNode not found"**
- Don't expose JsonNode directly. Use `@Ignore` and provide String getters

### Deployment Issues

**"Events not in database"**
- Check FluentBit logs: `kubectl logs -n logging -l app=workflows-fluent-bit-mode1`
- Check PostgreSQL connection from FluentBit pod
- Verify log file exists: `/tmp/quarkus-flow-events.log`

**"Raw tables populated but normalized tables empty"**
- Check triggers exist: `\d workflow_events_raw` in psql
- Check trigger functions: `\df normalize_workflow_event`
- Check PostgreSQL logs for trigger errors

**"GraphQL query returns empty taskExecutions"**
- Check foreign key relationship: `instance_id` in task_instances → `id` in workflow_instances
- Check mapper sets bidirectional relationship
- Check JPA entities have `@OneToMany` / `@ManyToOne`

---

## Key Files Reference

**Architecture:**
- `data-index/docs/ARCHITECTURE-SUMMARY.md` - All deployment modes
- `data-index/docs/deployment/MODE1_HANDOFF.md` - MODE 1 details

**Code:**
- `data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/` - Domain model
- `data-index-storage-postgresql/src/main/java/.../entity/` - JPA entities
- `data-index-storage-postgresql/src/main/java/.../mapper/` - MapStruct mappers
- `data-index-service/src/main/java/.../graphql/WorkflowInstanceGraphQLApi.java` - GraphQL API

**Database:**
- `data-index-storage-migrations/src/main/resources/db/migration/` - Flyway migrations
- `V1__initial_schema.sql` - Schema with triggers

**Configuration:**
- `data-index-service/src/main/resources/application.properties` - Quarkus config
- `data-index/scripts/fluentbit/mode1-postgresql-polling/fluent-bit.conf` - FluentBit config

**Testing:**
- `data-index-service/src/test/java/.../graphql/WorkflowInstanceGraphQLApiTest.java`

**Build:**
- `pom.xml` (root) - Generic dependencies, plugin versions
- `data-index/pom.xml` - Data Index specific dependencies

---

## Current Status & Next Steps

### ✅ Complete (Phase 1)

- Trigger-based MODE 1 architecture
- GraphQL API with input/output JSON exposure (String getters)
- Integration tests with proper test data setup
- POM structure consolidation
- Kogito dependencies minimized (7 → 1)
- Documentation updated

### 🔄 Optional Future Work

**Low Priority:**
1. Inline `persistence-commons-api` source → eliminate Kogito dependency
2. Implement proper GraphQL JSON scalar (industry standard)
3. Add JSON path filtering in GraphQL API (e.g., filter by `input.orderId`)
4. Reorganize root-level docs into subdirectories

**Not Planned:**
- MODE 2 (Elasticsearch) - design documented, not implemented
- MODE 3 (Kafka) - design documented, not implemented

---

## Questions? Check These First

**"How do I expose a new field in GraphQL?"**
→ Add to JPA entity → Add to domain model → MapStruct auto-maps

**"How do I query JSON content?"**
→ GraphQL: Can't query into JSON (opaque String)
→ Storage layer: Use `AttributeFilter` with `setJson(true)` for JSONB queries

**"Why aren't my GraphQL changes showing up?"**
→ Rebuild and redeploy container image to KIND

**"How do I test my changes?"**
→ Write `@QuarkusTest` integration test with proper setup/cleanup

**"Where are the database triggers?"**
→ `data-index-storage-migrations/.../V1__initial_schema.sql`

**"How does data flow from Quarkus Flow to GraphQL?"**
→ Quarkus Flow → log file → FluentBit → PostgreSQL raw → triggers → normalized → JPA → GraphQL

---

## Remember

- **Read-only** - We don't modify workflow state
- **Trigger-based** - No Event Processor, no polling
- **Minimal dependencies** - Only persistence-commons-api from Kogito
- **String for JSON** - Pragmatic, not ideal, but works
- **Test with data** - Always setup/cleanup in tests

For detailed information, see `data-index/docs/README.md`
