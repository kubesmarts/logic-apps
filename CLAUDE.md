# Claude AI Assistant Guidelines - KubeSmarts Logic Apps

**Project:** Data Index v1.0.0 for Serverless Workflow 1.0.0  
**Status:** Production Ready (MODE 1 & MODE 2)  
**Last Updated:** 2026-04-29

---

## **CRITICAL: Documentation is the Source of Truth**

**ALWAYS** consult and update documentation when making changes:

1. **Read first:** Check `data-index/data-index-docs/modules/ROOT/pages/` for existing documentation
2. **Update immediately:** After any code change, update relevant documentation in the `data-index-docs` module
3. **Keep scripts synchronized:** Update `data-index/scripts/` README files to match code changes
4. **Verify examples:** Ensure code examples in documentation match actual working code

**Documentation locations:**
- Main documentation: `data-index/data-index-docs/` (Antora/AsciiDoc)
- Script docs: `data-index/scripts/*/README.md`
- Architecture docs: `data-index/docs/ARCHITECTURE-*.md`

**The documentation module (`data-index-docs`) is served at `/docs` in the running application and is the user-facing manual. It must always be accurate and complete.**

---

## Project Overview

This is a **read-only query service** for Serverless Workflow (SW 1.0.0) runtime execution data. It provides a GraphQL API for querying workflow instances and task executions.

**What it does:**
- Captures Quarkus Flow structured logging events via FluentBit
- Stores raw events in PostgreSQL (MODE 1) or Elasticsearch (MODE 2)
- Normalizes events using PostgreSQL triggers (MODE 1) or Elasticsearch Transforms (MODE 2)
- Exposes normalized data via GraphQL API (SmallRye GraphQL)

**What it does NOT do:**
- Does NOT execute workflows (that's Quarkus Flow's job)
- Does NOT modify workflow state (read-only)
- Does NOT use polling/Event Processor (removed in Phase 1)

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

## Architecture (MODE 2 - Elasticsearch)

```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                      ↓ (FluentBit tail)
              Elasticsearch raw indices (workflow-events, task-events)
                      ↓ (ES Transform, continuous, 1s)
              Elasticsearch normalized indices (workflow-instances, task-executions)
                      ↓ (Elasticsearch Java Client)
              GraphQL API (SmallRye GraphQL)
```

**Key Components:**
- **FluentBit DaemonSet** - Tails log files, sends to Elasticsearch
- **Elasticsearch Transforms** - Normalize events continuously (1s frequency)
- **ILM Policies** - Auto-delete raw events after 7 days
- **Index Templates** - Define mappings and settings for all indices
- **Data Index Service** - Quarkus app with GraphQL API
- **Elasticsearch Java Client** - Official elasticsearch-java library

**NOT used in MODE 2:**
- ❌ Event Processor service (ES Transform handles normalization)
- ❌ PostgreSQL triggers
- ❌ Flyway migrations
- ❌ JPA/Hibernate

**Transform-Based Normalization:**
- Continuous processing (1s frequency)
- Field-level idempotency (COALESCE equivalent)
- Smart filtering (recent events + active workflows only)
- Constant performance as data grows

**Field-Level Idempotency Rules:**
- **Immutable fields** (first value wins): start, input, name, version, namespace
- **Terminal fields** (last non-null wins): end, output, error
- **Status**: Terminal state precedence (COMPLETED/FAULTED/CANCELLED > RUNNING > CREATED)

**When to use MODE 2:**
- Need full-text search capabilities
- Need complex aggregations
- Prefer Elasticsearch ecosystem
- Want auto-scaling storage (ILM)
- Need multi-tenancy with index-per-tenant

---

## Choosing Between MODE 1 and MODE 2

**Use MODE 1 (PostgreSQL) when:**
- Standard relational queries are sufficient
- ACID guarantees are important
- Existing PostgreSQL infrastructure
- Simpler operations (triggers, backups)
- Smaller deployment footprint

**Use MODE 2 (Elasticsearch) when:**
- Need full-text search on workflow data
- Complex aggregations required
- Large-scale deployments (1M+ workflows)
- Auto-scaling storage needed
- Multi-tenancy requirements

**Both modes share:**
- Identical GraphQL API
- Same domain model
- FluentBit ingestion
- Idempotent event processing
- No Event Processor service

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
│   ├── data-index-storage-migrations/ # Flyway SQL migrations (MODE 1)
│   ├── data-index-storage-postgresql/ # JPA implementation (MODE 1)
│   ├── data-index-storage-elasticsearch/ # ES implementation (MODE 2)
│   │   ├── WorkflowInstanceElasticsearchStorage.java
│   │   ├── TaskExecutionElasticsearchStorage.java
│   │   ├── mapper/                # Domain ↔ ES mappers
│   │   └── QueryBuilder.java     # Filter/sort translation
│   └── data-index-elasticsearch-schema/ # ES schema (MODE 2)
│       ├── schema-initializer/    # ILM, templates, transforms
│       └── resources/schema/      # JSON schema files
├── data-index-service/            # Quarkus GraphQL service
│   ├── graphql/                   # GraphQL API
│   │   ├── WorkflowInstanceGraphQLApi.java
│   │   ├── filter/                # GraphQL filter converters
│   │   └── GraphQLConfiguration.java
│   └── service/                   # JAX-RS resources
│       └── RootResource.java      # Landing page
├── data-index-integration-tests/  # E2E tests (MODE 1 & MODE 2)
│   ├── WorkflowInstanceGraphQLApiTest.java (PostgreSQL)
│   └── WorkflowInstanceElasticsearchTest.java (Elasticsearch)
├── data-index-docs/               # User-facing documentation (Antora)
│   └── modules/ROOT/pages/        # AsciiDoc documentation pages
├── docs/                          # Internal documentation
└── scripts/                       # Deployment scripts
    ├── kind/                      # KIND (Kubernetes in Docker) scripts
    └── fluentbit/                 # FluentBit configurations
        ├── mode1-postgresql-triggers/ # MODE 1 FluentBit config
        └── mode2-elasticsearch-transforms/ # MODE 2 FluentBit config
```

---

## Storage Backend Architecture (Maven + Quarkus Profiles)

**Multi-backend support** via profile-based dependency isolation:

### How it works:

1. **Maven Profile** (pom.xml) - Controls which dependencies are included
2. **Quarkus Profile** (application-*.properties) - Configures runtime behavior

### Development:
```bash
# PostgreSQL backend (default, production-ready)
mvn quarkus:dev -Dquarkus.profile=postgresql

# Elasticsearch backend (production-ready)
mvn quarkus:dev -Dquarkus.profile=elasticsearch
```

### What happens (PostgreSQL):
- `-Dquarkus.profile=postgresql` activates Maven profile `postgresql`
- Maven includes only PostgreSQL dependencies (storage, JDBC, Flyway)
- Quarkus loads `application-postgresql.properties`
- Dev Services auto-starts `postgres:15` container
- Flyway runs migrations automatically
- Service starts with PostgreSQL backend

### What happens (Elasticsearch):
- `-Dquarkus.profile=elasticsearch` activates Maven profile `elasticsearch`
- Maven includes only Elasticsearch dependencies (storage, java client, schema)
- Quarkus loads `application-elasticsearch.properties`
- Dev Services auto-starts `elasticsearch:8.11.1` container
- Schema initializer creates ILM policies, index templates, transforms
- Service starts with Elasticsearch backend

### Configuration files:
- `application.properties` - Common config (GraphQL, HTTP, metrics)
- `application-postgresql.properties` - PostgreSQL-specific config
- `application-elasticsearch.properties` - Elasticsearch-specific config (placeholder)

### Maven profiles (in data-index-service/pom.xml):

**`postgresql` profile (activeByDefault=true):**
- data-index-storage-postgresql
- data-index-storage-migrations (Flyway SQL)
- quarkus-hibernate-orm
- quarkus-jdbc-postgresql
- quarkus-flyway

**`elasticsearch` profile:**
- data-index-storage-elasticsearch
- data-index-elasticsearch-schema
- quarkus-elasticsearch-java-client
- quarkus-elasticsearch-rest-client

**Benefit:** Only one backend's dependencies are included in builds, keeping deployments lean.

### Elasticsearch Schema Management

**Schema initialization (MODE 2 only):**
- Auto-applied on startup via `ElasticsearchSchemaInitializer`
- Creates ILM policies, index templates, transforms
- Controlled by `data-index.storage.skip-init-schema` property
- Skip in production: `-Ddata-index.storage.skip-init-schema=true`

**Schema components:**
1. **ILM Policy** (`data-index-events-retention`) - 7-day retention for raw events
2. **Index Templates** - Mappings for all 4 indices
3. **Transforms** - Continuous normalization (workflow-instances, task-executions)

**Schema files location:**
- `data-index-elasticsearch-schema/src/main/resources/schema/`
- `ilm-policy.json` - ILM configuration
- `workflow-events-template.json` - Raw workflow events
- `task-events-template.json` - Raw task events
- `workflow-instances-template.json` - Normalized workflows
- `task-executions-template.json` - Normalized tasks
- `workflow-instances-transform.json` - Workflow normalization transform
- `task-executions-transform.json` - Task normalization transform

**See:** `data-index-docs/modules/ROOT/pages/developers/configuration.adoc`

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

### 1b. Transform-Based Normalization (MODE 2 - Elasticsearch)

**DO:**
- ✅ Raw events stored in `workflow-events` and `task-events` indices
- ✅ Transforms extract fields and aggregate into normalized indices
- ✅ Continuous processing (1s frequency)
- ✅ Field-level idempotency (COALESCE equivalent in Painless)
- ✅ Smart filtering (recent events + active workflows only)

**DON'T:**
- ❌ Don't add Event Processor service (ES Transform handles it)
- ❌ Don't use polling architecture
- ❌ Don't use PostgreSQL triggers for Elasticsearch

**Field-Level Idempotency:**
```
Immutable (first wins):  start, input, name, version, namespace
Terminal (last non-null): end, output, error
Status: COMPLETED/FAULTED/CANCELLED > RUNNING > CREATED
```

**Smart Filtering:**
- Processes events from last 1 hour + workflows still active
- Reduces processing as data grows
- Constant performance regardless of total workflow count

**See:** `data-index/docs/deployment/MODE2_HANDOFF.md`

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

### Error Handling

**Error structure (unified for workflows and tasks):**
```
Database columns → JPA Entity → GraphQL:
error_type       → ErrorEntity.type     → Error.type
error_title      → ErrorEntity.title    → Error.title
error_detail     → ErrorEntity.detail   → Error.detail
error_status     → ErrorEntity.status   → Error.status
error_instance   → ErrorEntity.instance → Error.instance
```

**Domain model:**
- `Error` - Generic error type used by both WorkflowInstance and TaskExecution
- Previously `WorkflowInstanceError` (renamed for reuse)

**JPA entities:**
- `ErrorEntity` - Embeddable entity used by WorkflowInstanceEntity and TaskInstanceEntity
- Previously `WorkflowInstanceErrorEntity` (renamed for reuse)

**GraphQL filtering:**
- `ErrorFilter` - Nested filter for error fields
- `IntFilter` - Integer field filter (used by error.status)
- Available for both workflow instances and task executions

### 4. Entity Naming (MODE 1 - PostgreSQL)

**DO:**
- ✅ `WorkflowInstanceEntity` → `workflow_instances` table
- ✅ `TaskInstanceEntity` → `task_instances` table

**DON'T:**
- ❌ Don't create `TaskExecutionEntity` (we deleted it, caused confusion)
- ❌ Only `TaskInstanceEntity` exists for task data

**Mappers:**
- `WorkflowInstanceEntityMapper` - Domain ↔ JPA for workflows
- `TaskInstanceEntityMapper` - Domain ↔ JPA for tasks

### 5. Document Mapping (MODE 2 - Elasticsearch)

**Indices:**
- `workflow-events` - Raw workflow events
- `task-events` - Raw task events
- `workflow-instances` - Normalized workflow instances
- `task-executions` - Normalized task executions

**Mappers:**
- `WorkflowInstanceMapper` - Domain ↔ ES Map for workflows
- `TaskExecutionMapper` - Domain ↔ ES Map for tasks

**Storage Implementation:**
- `WorkflowInstanceElasticsearchStorage` - CRUD operations
- `TaskExecutionElasticsearchStorage` - CRUD operations
- Uses Elasticsearch Java Client (not REST client)
- No JPA, no entities - direct Map<String, Object> manipulation

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

### Database (MODE 1 - PostgreSQL)

**DO:**
- ✅ Use Flyway migrations in `data-index-storage-migrations`
- ✅ Use JSONB for dynamic/heterogeneous data (input/output)
- ✅ Use triggers for normalization
- ✅ Use COALESCE in UPSERT for idempotency

**DON'T:**
- ❌ Don't manually create tables (use Flyway)
- ❌ Don't add staging tables (we use raw → triggers → normalized)

### Elasticsearch (MODE 2)

**DO:**
- ✅ Use schema initializer for ILM, templates, transforms
- ✅ Use transforms for normalization
- ✅ Use Painless scripts for field-level idempotency
- ✅ Use ILM for auto-deleting old raw events
- ✅ Store JSON as nested objects (not strings)

**DON'T:**
- ❌ Don't manually create indices (use templates)
- ❌ Don't use Flyway with Elasticsearch
- ❌ Don't disable schema initialization in dev mode
- ❌ Don't use REST client (use Java client)

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

### Integration Tests (MODE 1 - PostgreSQL)

**DO:**
- ✅ Write `@QuarkusTest` integration tests
- ✅ Set up test data in `@BeforeEach` using JPA entities
- ✅ Clean up in `@AfterEach`
- ✅ Test GraphQL queries with REST Assured
- ✅ Use known test IDs for assertions

**Example:**
```java
@QuarkusTest
@TestProfile(PostgresqlTestProfile.class)
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

### Integration Tests (MODE 2 - Elasticsearch)

**DO:**
- ✅ Write `@QuarkusTest` integration tests with `ElasticsearchTestProfile`
- ✅ Use Testcontainers for Elasticsearch 8.11.1
- ✅ Index raw events, wait for transform, verify normalized data
- ✅ Test out-of-order events (terminal state wins)
- ✅ Test field-level idempotency

**Example:**
```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
public class WorkflowInstanceElasticsearchTest {
    @Inject ElasticsearchClient client;
    
    @BeforeEach
    public void setup() throws IOException {
        // Create raw event
        Map<String, Object> event = Map.of(
            "id", "test-123",
            "status", "RUNNING",
            "start", Instant.now().toString()
        );
        client.index(i -> i
            .index("workflow-events")
            .id(UUID.randomUUID().toString())
            .document(event));
        
        // Wait for transform
        Thread.sleep(2000);
    }
    
    @Test
    public void testTransformNormalization() throws IOException {
        GetResponse<Map> response = client.get(g -> g
            .index("workflow-instances")
            .id("test-123"), Map.class);
        assertTrue(response.found());
    }
}
```

**DON'T:**
- ❌ Don't mock Elasticsearch (use Testcontainers)
- ❌ Don't test without waiting for transform (1s frequency + buffer)
- ❌ Don't test against different ES version (must be 8.11.1)

---

## Build & Deployment

### Local Development

```bash
# PostgreSQL backend (default)
cd data-index/data-index-service
mvn quarkus:dev -Dquarkus.profile=postgresql

# What this does:
# - Activates Maven postgresql profile → includes only PostgreSQL dependencies
# - Loads application-postgresql.properties
# - Starts postgres:15 container via Dev Services
# - Runs Flyway migrations automatically
# - Service available at http://localhost:8080
# - Documentation at http://localhost:8080/docs
# - Live coding enabled (code changes trigger auto-reload)

# Elasticsearch backend
cd data-index/data-index-service
mvn quarkus:dev -Dquarkus.profile=elasticsearch

# What this does:
# - Activates Maven elasticsearch profile → includes only ES dependencies
# - Loads application-elasticsearch.properties
# - Starts elasticsearch:8.11.1 container via Dev Services
# - Runs schema initialization (ILM, templates, transforms)
# - Service available at http://localhost:8080
# - Elasticsearch at http://localhost:9200
# - Live coding enabled (code changes trigger auto-reload)
```

### Production Build

```bash
# Build with PostgreSQL backend (excludes Flyway for production)
cd data-index/data-index-service
mvn clean package -Dquarkus.profile=postgresql -DskipFlyway=true -DskipTests

# Result: target/quarkus-app/ contains:
# - Optimized Quarkus app (JVM mode)
# - PostgreSQL storage dependencies ONLY
# - NO Flyway (schema managed externally in production)
# - Container image: kubesmarts/data-index-service:999-SNAPSHOT

# Elasticsearch backend
mvn clean package -Dquarkus.profile=elasticsearch -DskipTests

# Result: target/quarkus-app/ contains:
# - Optimized Quarkus app (JVM mode)
# - Elasticsearch storage dependencies ONLY
# - Schema initialization (controlled by skipInitSchema flag)
# - Container image: kubesmarts/data-index-service:999-SNAPSHOT-elasticsearch
```

**Why `-DskipFlyway=true` for PostgreSQL?**
- Flyway & migrations only needed in dev (with Dev Services)
- Production uses external schema management (init jobs, operators)
- Reduces image size by excluding unnecessary dependencies

**Elasticsearch schema in production:**
- Include schema module by default
- Control initialization via runtime flag: `-Ddata-index.storage.skip-init-schema=true`
- Or use init job to pre-create schema before starting service

### KIND Deployment

```bash
cd data-index/scripts/kind

# 1. Setup cluster and PostgreSQL
./setup-cluster.sh
MODE=postgresql ./install-dependencies.sh

# 2. Deploy data-index service
./deploy-data-index.sh postgresql

# 3. Deploy FluentBit (MODE 1)
cd ../fluentbit/mode1-postgresql-triggers
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

### Elasticsearch Deployment

```bash
cd data-index/scripts/kind

# 1. Setup cluster and Elasticsearch
./setup-cluster.sh
MODE=elasticsearch ./install-dependencies.sh

# 2. Deploy data-index service
./deploy-data-index.sh elasticsearch

# 3. Deploy FluentBit (MODE 2)
cd ../fluentbit/mode2-elasticsearch-transforms
./generate-configmap.sh  # Generate from source files
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/daemonset.yaml

# 4. Deploy test workflow app
cd ../../kind
./deploy-workflow-app.sh

# 5. Verify schema created
kubectl port-forward -n data-index svc/elasticsearch 9200:9200
curl http://localhost:9200/_cat/indices?v
# Should see: workflow-events, task-events, workflow-instances, task-executions

# 6. Verify transforms running
curl http://localhost:9200/_transform/workflow-instances-transform
curl http://localhost:9200/_transform/task-executions-transform

# 7. Test GraphQL API
curl http://localhost:30080/graphql -d '{"query":"..."}'
```

**See:** `data-index/docs/deployment/MODE2_E2E_TESTING.md`

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

### Adding a Database Field (MODE 1 - PostgreSQL)

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

### Adding an Elasticsearch Field (MODE 2)

1. **Update index template** in `data-index-elasticsearch-schema/resources/schema/`:
```json
// workflow-instances-template.json
"properties": {
  "priority": { "type": "integer" }
}
```

2. **Update transform** if field comes from events:
```json
// workflow-instances-transform.json
"script": {
  "source": "ctx.priority = ctx._source.priority"
}
```

3. **Update domain model:**
```java
// WorkflowInstance.java
private Integer priority;
// + getters/setters
```

4. **Update mapper:**
```java
// WorkflowInstanceMapper.java
@Mapping(target = "priority", source = "priority")
WorkflowInstance fromDocument(Map<String, Object> document);
```

5. **Recreate template** (dev mode auto-applies on restart)

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

### Testing with Elasticsearch

**Start Elasticsearch in dev mode:**
```bash
cd data-index/data-index-service
mvn quarkus:dev -Dquarkus.profile=elasticsearch
```

**Verify schema created:**
```bash
# Check indices
curl http://localhost:9200/_cat/indices?v

# Check transform status
curl http://localhost:9200/_transform/workflow-instances-transform
curl http://localhost:9200/_transform/task-executions-transform

# Check ILM policy
curl http://localhost:9200/_ilm/policy/data-index-events-retention
```

**Index test events:**
```bash
# Workflow event
curl -X POST http://localhost:9200/workflow-events/_doc -H 'Content-Type: application/json' -d '{
  "id": "test-wf-1",
  "status": "RUNNING",
  "start": "2026-04-29T12:00:00Z",
  "name": "test-workflow",
  "version": "1.0"
}'

# Wait 2 seconds for transform
sleep 2

# Check normalized data
curl http://localhost:9200/workflow-instances/_doc/test-wf-1
```

**Run integration tests:**
```bash
mvn test -Dquarkus.profile=elasticsearch -Dtest=WorkflowInstanceElasticsearchTest
```

### Querying Elasticsearch Directly

**Search normalized workflows:**
```bash
curl -X POST http://localhost:9200/workflow-instances/_search -H 'Content-Type: application/json' -d '{
  "query": {
    "match": { "status": "COMPLETED" }
  }
}'
```

**Check raw events:**
```bash
curl -X POST http://localhost:9200/workflow-events/_search -H 'Content-Type: application/json' -d '{
  "query": { "match_all": {} },
  "sort": [{ "@timestamp": "desc" }],
  "size": 10
}'
```

**Transform stats:**
```bash
curl http://localhost:9200/_transform/workflow-instances-transform/_stats
```

---

## What NOT to Do

### ❌ Architecture

- Don't add Event Processor service (MODE 1 uses triggers, MODE 2 uses transforms)
- Don't use polling architecture
- Don't create staging tables (MODE 1) or separate processing indices (MODE 2)
- Don't add Kafka (MODE 3 not implemented)
- Don't mix PostgreSQL and Elasticsearch in same deployment

### ❌ Dependencies

- Don't add more Kogito dependencies (only persistence-commons-api)
- Don't add kogito-apps-build-parent (removed)
- Don't add kogito-apps-bom (removed)

### ❌ Code

- Don't create TaskExecutionEntity (only TaskInstanceEntity exists)
- Don't use old field names: inputArgs/outputArgs, enter/exit, triggerTime/leaveTime
- Don't expose JsonNode directly in GraphQL (use String getters)
- Don't try to implement custom GraphQL scalar for JsonNode (tried, doesn't work well)
- Don't use `WorkflowInstanceError` or `WorkflowInstanceErrorEntity` (renamed to `Error` and `ErrorEntity`)
- Don't use `TaskExecution.errorMessage` (replaced with `error: Error`)

### ❌ Testing

- Don't skip test data setup (use @BeforeEach)
- Don't leave test data behind (use @AfterEach)
- Don't test against empty database
- Don't test Elasticsearch without waiting for transforms (need 1-2s buffer)
- Don't use different Elasticsearch versions in tests (must be 8.11.1)

### ❌ Elasticsearch Specific

- Don't use Elasticsearch REST client (use Java client)
- Don't disable schema initialization in dev mode
- Don't manually create indices (use templates)
- Don't modify transforms while running (stop, modify, start)
- Don't use different field types in templates vs transforms
- Don't forget ILM policy for raw event indices
- Don't use short retention periods in production (7 days minimum)

---

## Troubleshooting

### Build Issues

**"Cannot find symbol: class TaskExecutionEntity"**
- This entity was deleted. Use `TaskInstanceEntity`

**"Missing version for org.kie.kogito:persistence-commons-api"**
- Check `data-index/pom.xml` has the dependency in `<dependencyManagement>`

**"GraphQL schema error: JsonNode not found"**
- Don't expose JsonNode directly. Use `@Ignore` and provide String getters

### Deployment Issues (MODE 1 - PostgreSQL)

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

### Deployment Issues (MODE 2 - Elasticsearch)

**"Events not in Elasticsearch"**
- Check FluentBit logs: `kubectl logs -n logging -l app=workflows-fluent-bit-mode2`
- Check Elasticsearch connection from FluentBit pod
- Verify log file exists: `/tmp/quarkus-flow-events.log`
- Check indices exist: `curl http://localhost:9200/_cat/indices`

**"Raw indices populated but normalized indices empty"**
- Check transforms running: `curl http://localhost:9200/_transform/workflow-instances-transform/_stats`
- Check transform errors: `curl http://localhost:9200/_transform/workflow-instances-transform` (look for failures)
- Start transform if stopped: `curl -X POST http://localhost:9200/_transform/workflow-instances-transform/_start`
- Check transform filter matches events (smart filtering may exclude old data)

**"Schema not created on startup"**
- Check `data-index.storage.skip-init-schema` is false
- Check service logs for schema initialization errors
- Verify Elasticsearch is reachable before service starts
- Check Elasticsearch version compatibility (must be 8.11.1)

**"Transform processing slow"**
- Check transform frequency setting (default 1s)
- Check smart filter performance (may need to adjust time window)
- Check Elasticsearch cluster health
- Review transform stats for processing time

**"Old events not deleted"**
- Check ILM policy attached to indices: `curl http://localhost:9200/workflow-events/_settings`
- Check ILM policy execution: `curl http://localhost:9200/_ilm/policy/data-index-events-retention`
- Verify retention period (default 7 days)

---

## Key Files Reference

**Architecture:**
- `data-index/docs/ARCHITECTURE-SUMMARY.md` - All deployment modes
- `data-index/docs/deployment/MODE1_HANDOFF.md` - MODE 1 (PostgreSQL) details
- `data-index/docs/deployment/MODE2_HANDOFF.md` - MODE 2 (Elasticsearch) details

**Code (Common):**
- `data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/` - Domain model
- `data-index-service/src/main/java/.../graphql/WorkflowInstanceGraphQLApi.java` - GraphQL API

**Code (MODE 1 - PostgreSQL):**
- `data-index-storage-postgresql/src/main/java/.../entity/` - JPA entities
- `data-index-storage-postgresql/src/main/java/.../mapper/` - MapStruct mappers
- `data-index-storage-migrations/src/main/resources/db/migration/` - Flyway migrations
- `V1__initial_schema.sql` - Schema with triggers

**Code (MODE 2 - Elasticsearch):**
- `data-index-storage-elasticsearch/src/main/java/.../` - Storage implementation
- `data-index-storage-elasticsearch/src/main/java/.../mapper/` - Document mappers
- `data-index-elasticsearch-schema/src/main/java/.../` - Schema initializer
- `data-index-elasticsearch-schema/src/main/resources/schema/` - ILM, templates, transforms

**Configuration:**
- `data-index-service/src/main/resources/application.properties` - Common config
- `data-index-service/src/main/resources/application-postgresql.properties` - PostgreSQL backend
- `data-index-service/src/main/resources/application-elasticsearch.properties` - Elasticsearch backend
- `data-index/scripts/fluentbit/mode1-postgresql-triggers/fluent-bit.conf` - MODE 1 FluentBit
- `data-index/scripts/fluentbit/mode2-elasticsearch-transforms/fluent-bit.conf` - MODE 2 FluentBit

**Testing:**
- `data-index-service/src/test/java/.../graphql/WorkflowInstanceGraphQLApiTest.java` - PostgreSQL
- `data-index-integration-tests/src/test/java/.../WorkflowInstanceElasticsearchTest.java` - Elasticsearch

**Build:**
- `pom.xml` (root) - Generic dependencies, plugin versions
- `data-index/pom.xml` - Data Index specific dependencies
- `data-index-service/pom.xml` - Profile-based dependencies (postgresql/elasticsearch)

---

## Current Status & Next Steps

### ✅ Complete (Phase 1 - MODE 1)

- Trigger-based MODE 1 architecture
- GraphQL API with input/output JSON exposure (String getters)
- Integration tests with proper test data setup
- POM structure consolidation
- Kogito dependencies minimized (7 → 1)
- Documentation updated

### ✅ Complete (Phase 2 - MODE 2)

- Transform-based MODE 2 architecture
- Elasticsearch storage implementation
- Schema module (ILM, templates, transforms)
- Field-level idempotency in transforms
- Smart filtering for constant performance
- Integration tests with Testcontainers
- Dev Services support
- Profile-based dependency isolation

### 🔄 Optional Future Work

**Low Priority:**
1. Inline `persistence-commons-api` source → eliminate Kogito dependency
2. Implement proper GraphQL JSON scalar (industry standard)
3. Add JSON path filtering in GraphQL API (e.g., filter by `input.orderId`)
4. Reorganize root-level docs into subdirectories
5. Add Elasticsearch aggregations API
6. Add full-text search capabilities

**Not Planned:**
- MODE 3 (Kafka) - design documented, not implemented

---

## Questions? Check These First

**"How do I expose a new field in GraphQL?"**
→ MODE 1: Add to JPA entity → Add to domain model → MapStruct auto-maps
→ MODE 2: Add to index template → Add to transform → Add to domain model → Mapper maps

**"How do I query JSON content?"**
→ GraphQL: Can't query into JSON (opaque String)
→ MODE 1: Use `AttributeFilter` with `setJson(true)` for JSONB queries
→ MODE 2: JSON stored as nested object, queryable via Elasticsearch

**"Why aren't my GraphQL changes showing up?"**
→ Rebuild and redeploy container image to KIND

**"How do I test my changes?"**
→ MODE 1: Write `@QuarkusTest` integration test with PostgreSQL profile
→ MODE 2: Write `@QuarkusTest` integration test with Elasticsearch profile + wait for transforms

**"Where are the database triggers?"**
→ MODE 1: `data-index-storage-migrations/.../V1__initial_schema.sql`
→ MODE 2: Not applicable (uses Elasticsearch transforms)

**"Where are the Elasticsearch transforms?"**
→ `data-index-elasticsearch-schema/resources/schema/workflow-instances-transform.json`
→ `data-index-elasticsearch-schema/resources/schema/task-executions-transform.json`

**"How does data flow from Quarkus Flow to GraphQL?"**
→ MODE 1: Quarkus Flow → log file → FluentBit → PostgreSQL raw → triggers → normalized → JPA → GraphQL
→ MODE 2: Quarkus Flow → log file → FluentBit → ES raw indices → transforms → normalized indices → ES client → GraphQL

**"Which mode should I use?"**
→ MODE 1 for standard use cases, smaller deployments, simpler operations
→ MODE 2 for full-text search, aggregations, large scale, multi-tenancy

**"Can I switch modes later?"**
→ Yes, same GraphQL API, just different storage backend
→ Need to redeploy with different profile and reconfigure FluentBit

---

## Remember

- **Read-only** - We don't modify workflow state
- **Two modes** - PostgreSQL (triggers) or Elasticsearch (transforms)
- **No Event Processor** - MODE 1 uses triggers, MODE 2 uses transforms
- **Minimal dependencies** - Only persistence-commons-api from Kogito
- **String for JSON** - Pragmatic, not ideal, but works (both modes)
- **Test with data** - Always setup/cleanup in tests
- **Profile-based** - Only one backend's dependencies included per build
- **Same GraphQL API** - Identical interface regardless of storage backend

For detailed information, see `data-index/docs/README.md`
