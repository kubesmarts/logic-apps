# JPA Entity to PostgreSQL Schema Validation

This document describes how to validate and maintain consistency between JPA entities and the PostgreSQL schema.

## Overview

Data Index uses JPA/Hibernate for ORM with PostgreSQL. The database schema is defined in two places:

1. **JPA Entities** (`data-index-storage-jpa-common/src/main/java/org/kie/kogito/index/jpa/model/`)
   - Java classes with JPA annotations
   - Hibernate generates DDL from these entities
   - Used at runtime for database queries

2. **SQL DDL** (`docs/database-schema-v1.0.0.sql`)
   - Explicit CREATE TABLE statements
   - Includes comments, indexes, and constraints
   - Used for manual deployments and documentation

## Entity-to-Table Mapping

| JPA Entity | Table Name | Purpose |
|------------|------------|---------|
| `ProcessDefinitionEntity` | `definitions` | Workflow definitions |
| `ProcessInstanceEntity` | `processes` | Workflow instances |
| `NodeEntity` | `definitions_nodes` | Workflow definition nodes |
| `NodeInstanceEntity` | `nodes` | Workflow node instances (executions) |
| `JobEntity` | `jobs` | Scheduled jobs and timers |
| ~~`MilestoneEntity`~~ | ~~`milestones`~~ | ❌ **REMOVED** - BPMN legacy, not in SW 1.0.0 |
| ~~`UserTaskInstanceEntity`~~ | ~~`tasks`~~ | ❌ **REMOVED** - BPMN legacy, not in SW 1.0.0 |
| ~~`CommentEntity`~~ | ~~`comments`~~ | ❌ **REMOVED** - BPMN legacy |
| ~~`AttachmentEntity`~~ | ~~`attachments`~~ | ❌ **REMOVED** - BPMN legacy |

**NOTE**: Milestone and UserTask entities will be completely removed from Data Index v1.0.0. The JPA entities still exist in the codebase for v0.8 compatibility during migration, but the database tables are NOT created in the v1.0.0 schema.

## Core Tables

### definitions (ProcessDefinitionEntity)

**Entity file**: `ProcessDefinitionEntity.java`

**Key fields**:
```java
@Id String id                        // Workflow ID
@Id String version                   // Version (composite key)
String name                          // Display name
String description
String type                          // Workflow type
byte[] source                        // Workflow definition (YAML/JSON)
String endpoint                      // Runtime service endpoint
JsonNode metadata                    // JSONB metadata
Set<String> roles                    // @ElementCollection -> definitions_roles
Set<String> addons                   // @ElementCollection -> definitions_addons
Set<String> annotations              // @ElementCollection -> definitions_annotations
List<NodeEntity> nodes               // @OneToMany -> definitions_nodes
```

**Collection tables**:
- `definitions_roles` - RBAC roles
- `definitions_addons` - Quarkus extensions
- `definitions_annotations` - K8s-style annotations

**Composite key**: `@IdClass(ProcessDefinitionKey.class)` - (id, version)

### processes (ProcessInstanceEntity)

**Entity file**: `ProcessInstanceEntity.java`

**Key fields**:
```java
@Id String id                        // Instance UUID
String processId                     // FK to definitions.id
String version                       // FK to definitions.version
String processName
Integer state                        // 0-5 (PENDING...ERROR)
String businessKey
String endpoint
ZonedDateTime start
ZonedDateTime end
ZonedDateTime lastUpdate
String rootProcessInstanceId
String rootProcessId
String parentProcessInstanceId
String createdBy
String updatedBy
ZonedDateTime slaDueDate
String cloudEventId
String cloudEventSource
JsonNode variables                   // JSONB variables
Set<String> roles                    // @ElementCollection -> processes_roles
Set<String> addons                   // @ElementCollection -> processes_addons
List<NodeInstanceEntity> nodes       // @OneToMany -> nodes
List<MilestoneEntity> milestones     // @OneToMany -> milestones
ProcessInstanceErrorEntity error     // @Embedded (in same table)
```

**Collection tables**:
- `processes_roles` - RBAC roles for instance
- `processes_addons` - Addons for instance

**Foreign key**: `(processId, version)` → `definitions(id, version)`

### nodes (NodeInstanceEntity)

**Entity file**: `NodeInstanceEntity.java`

**Key fields**:
```java
@Id String id                        // Node instance UUID
String name
String nodeId                        // Node ID from workflow definition
String type                          // StartNode, EndNode, ActionNode, etc.
String definitionId                  // Workflow definition node reference
ZonedDateTime enter
ZonedDateTime exit
ZonedDateTime slaDueDate
Boolean retrigger
String errorMessage
CancelType cancelType                // ENUM: ABORTED, SKIPPED, OBSOLETE
@ManyToOne ProcessInstanceEntity processInstance  // FK to processes
JsonNode inputArgs                   // JSONB input
JsonNode outputArgs                  // JSONB output
```

**Foreign key**: `processInstanceId` → `processes(id)` with CASCADE DELETE

### definitions_nodes (NodeEntity)

**Entity file**: `NodeEntity.java`

**Key fields**:
```java
@Id String id                        // Node ID (within workflow)
String name
String uniqueId
String type                          // Node type
@Id @ManyToOne ProcessDefinitionEntity processDefinition  // Composite FK
Map<String, String> metadata         // @ElementCollection -> definitions_nodes_metadata
```

**Composite key**: `@IdClass(NodeEntityId.class)` - (id, process_id, process_version)

**Collection table**: `definitions_nodes_metadata` - Key-value node metadata

### jobs (JobEntity)

**Entity file**: `JobEntity.java`

**Key fields**:
```java
@Id String id                        // Job UUID
String processId
String processInstanceId
String nodeInstanceId
String rootProcessId
String rootProcessInstanceId
ZonedDateTime expirationTime         // When job fires
Integer priority
String callbackEndpoint
Long repeatInterval                  // Milliseconds (NULL = one-time)
Integer repeatLimit                  // -1 = infinite
String scheduledId                   // External scheduler ID
Integer retries
String status                        // SCHEDULED, EXECUTED, RETRY, CANCELED, ERROR
ZonedDateTime lastUpdate
Integer executionCounter
String endpoint
String exceptionMessage
String exceptionDetails
```

**No foreign keys** - jobs are loosely coupled to process instances

## JSONB Columns

PostgreSQL JSONB columns use Hibernate custom converter:

**Converter**: `org.kie.kogito.persistence.postgresql.hibernate.JsonBinaryConverter`

**JSONB columns**:
- `definitions.metadata` → `JsonNode`
- `processes.variables` → `JsonNode`
- `nodes.inputArgs` → `JsonNode`
- `nodes.outputArgs` → `JsonNode`
- `tasks.inputs` → `ObjectNode`
- `tasks.outputs` → `ObjectNode`

**JPA annotation**:
```java
@Convert(converter = JsonBinaryConverter.class)
@Column(columnDefinition = "jsonb")
private JsonNode variables;
```

**Query support**: GIN indexes enable efficient JSON path queries:
```sql
CREATE INDEX idx_processes_variables ON processes USING GIN (variables);
```

## Validating Schema Consistency

### Option 1: Hibernate DDL Generation (Development)

Configure Hibernate to validate schema on startup:

```properties
# application.properties
quarkus.hibernate-orm.database.generation=validate
quarkus.hibernate-orm.log.sql=true
```

**Modes**:
- `validate` - Check entities match database (fail on mismatch)
- `update` - Auto-alter database (unsafe for production)
- `drop-and-create` - Recreate schema (dev/test only)
- `none` - No validation (production)

### Option 2: Schema Diff Tool

Use `schemaSpy` or `liquibase-diff` to compare generated DDL vs actual schema:

```bash
# Generate DDL from JPA entities
./mvnw clean compile quarkus:hibernate-orm-schema-export \
  -Dquarkus.hibernate-orm.sql-load-script=no-load-script

# Compare with docs/database-schema-v1.0.0.sql
diff target/schema.sql docs/database-schema-v1.0.0.sql
```

### Option 3: Integration Test

Create a Quarkus test that validates schema:

```java
@QuarkusTest
public class SchemaValidationTest {
    
    @Inject
    EntityManager em;
    
    @Test
    public void validateProcessInstanceTable() {
        // Query table metadata
        Query q = em.createNativeQuery("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'processes'");
        List<Object[]> columns = q.getResultList();
        
        // Assert expected columns exist
        assertTrue(columns.stream().anyMatch(c -> "id".equals(c[0])));
        assertTrue(columns.stream().anyMatch(c -> "processId".equals(c[0])));
        assertTrue(columns.stream().anyMatch(c -> "variables".equals(c[0]) && "jsonb".equals(c[1])));
    }
}
```

## Common Schema Issues

### Issue 1: JSONB Converter Not Found

**Error**: `org.kie.kogito.persistence.postgresql.hibernate.JsonBinaryConverter` not on classpath

**Fix**: Add dependency to `pom.xml`:
```xml
<dependency>
    <groupId>org.kie.kogito</groupId>
    <artifactId>kogito-persistence-postgresql</artifactId>
</dependency>
```

### Issue 2: Column Name Mismatch

**Error**: `Hibernate found column 'start_time' but entity expects 'startTime'`

**Fix**: Use `@Column(name = "startTime")` to override Hibernate naming strategy:
```java
@Column(name = "startTime")
private ZonedDateTime start;
```

### Issue 3: Missing Foreign Key Constraint

**Error**: Entity has `@ManyToOne` but SQL has no FK constraint

**Fix**: Add constraint to SQL DDL:
```sql
CONSTRAINT fk_nodes_process
    FOREIGN KEY (processInstanceId)
    REFERENCES processes(id)
    ON DELETE CASCADE
```

### Issue 4: Cascade Delete Not Working

**Error**: Delete parent entity doesn't delete children

**Fix**: Ensure both JPA and SQL have cascade:

**JPA**:
```java
@OneToMany(cascade = CascadeType.ALL, mappedBy = "processInstance")
private List<NodeInstanceEntity> nodes;
```

**SQL**:
```sql
ON DELETE CASCADE
```

## Deployment Strategies

### Strategy 1: JPA Auto-DDL (Development Only)

Let Hibernate create/update schema automatically:

```properties
quarkus.hibernate-orm.database.generation=update
```

⚠️ **WARNING**: Never use `update` or `drop-and-create` in production!

### Strategy 2: Manual SQL Deployment (Recommended for Production)

1. Deploy `docs/database-schema-v1.0.0.sql` via migration tool (Flyway/Liquibase)
2. Configure Data Index to validate only:
   ```properties
   quarkus.hibernate-orm.database.generation=validate
   ```

### Strategy 3: Flyway/Liquibase Migrations

Create versioned migration scripts:

**V1__initial_schema.sql**:
```sql
-- Copy from docs/database-schema-v1.0.0.sql
```

**V2__add_indexes.sql**:
```sql
CREATE INDEX idx_processes_businessKey ON processes(businessKey);
```

**Quarkus configuration**:
```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.hibernate-orm.database.generation=none
```

## Schema Evolution

When adding new fields to entities:

1. **Update JPA entity** (`ProcessInstanceEntity.java`):
   ```java
   private String newField;
   ```

2. **Update SQL DDL** (`database-schema-v1.0.0.sql`):
   ```sql
   ALTER TABLE processes ADD COLUMN newField VARCHAR(255);
   ```

3. **Create migration script** (`V3__add_new_field.sql`):
   ```sql
   ALTER TABLE processes ADD COLUMN newField VARCHAR(255);
   ```

4. **Update GraphQL schema** if field should be queryable

5. **Update mappers** (`ProcessInstanceEntityMapper.java`)

6. **Run validation tests**

## References

- **JPA Entities**: `data-index-storage-jpa-common/src/main/java/org/kie/kogito/index/jpa/model/`
- **SQL Schema**: `docs/database-schema-v1.0.0.sql`
- **Hibernate Docs**: https://hibernate.org/orm/documentation/
- **PostgreSQL JSONB**: https://www.postgresql.org/docs/current/datatype-json.html
- **Quarkus Hibernate ORM**: https://quarkus.io/guides/hibernate-orm
