# Data Index Storage PostgreSQL

PostgreSQL storage implementation using JPA/Hibernate ORM.

## Overview

Production-ready storage backend for Data Index that:

- Stores workflow and task execution data in PostgreSQL
- Uses JPA entities with Hibernate ORM
- Implements storage interfaces from `data-index-model`
- Provides MapStruct mappers for entity ↔ domain conversion

**Status:** ✅ Production Ready (PostgreSQL mode)

## Architecture

```
GraphQL API (data-index-service)
        ↓
Storage Interface (data-index-model/api)
        ↓
Storage Implementation (this module)
        ↓
JPA Entities → PostgreSQL
```

## Project Structure

```
data-index-storage-postgresql/
├── src/main/java/.../postgresql/
│   ├── storage/
│   │   ├── PostgreSQLWorkflowInstanceStorage.java
│   │   └── PostgreSQLTaskExecutionStorage.java
│   ├── entity/
│   │   ├── WorkflowInstanceEntity.java
│   │   └── TaskInstanceEntity.java
│   └── mapper/
│       ├── WorkflowInstanceEntityMapper.java
│       └── TaskInstanceEntityMapper.java
└── pom.xml
```

## JPA Entities

### WorkflowInstanceEntity

Maps to `workflow_instances` table:

```java
@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity {
    @Id
    private String id;  // Workflow instance ID (UUID)
    
    private String namespace;
    private String name;
    
    @Enumerated(EnumType.STRING)
    private WorkflowInstanceStatus status;
    
    @Column(name = "\"start\"")  // Quoted - SQL reserved keyword
    private ZonedDateTime start;
    
    @Column(name = "\"end\"")    // Quoted - SQL reserved keyword
    private ZonedDateTime end;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;
    
    @Column(name = "last_event_time")
    private ZonedDateTime lastEventTime;
    
    @Column(name = "created_at")
    private ZonedDateTime createdAt;
    
    @Column(name = "updated_at")
    private ZonedDateTime updatedAt;
    
    // Relationship loaded separately via GraphQL
    @Transient
    private List<TaskExecution> taskExecutions;
}
```

**Important:**
- `start`/`end` columns are quoted (SQL reserved keywords)
- `input`/`output` are JSONB columns
- `taskExecutions` is transient (loaded separately, not JPA relationship)

### TaskInstanceEntity

Maps to `task_instances` table:

```java
@Entity
@Table(name = "task_instances")
public class TaskInstanceEntity {
    @Column(name = "task_execution_id")
    private String taskExecutionId;  // Event ID (unique)
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // Synthetic database ID
    
    @Column(name = "instance_id")
    private String instanceId;  // Parent workflow ID
    
    @Column(name = "task_name")
    private String taskName;
    
    @Column(name = "task_position")
    private String taskPosition;
    
    private String status;
    
    @Column(name = "\"start\"")
    private ZonedDateTime start;
    
    @Column(name = "\"end\"")
    private ZonedDateTime end;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode input;
    
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode output;
    
    @Column(name = "last_event_time")
    private ZonedDateTime lastEventTime;
}
```

**Key difference:** `id` (GraphQL) comes from `taskExecutionId` (event ID), NOT the synthetic database `id`.

## MapStruct Mappers

### Entity ↔ Domain Conversion

```java
@Mapper(componentModel = "cdi")
public interface WorkflowInstanceEntityMapper {
    WorkflowInstance toDomain(WorkflowInstanceEntity entity);
    WorkflowInstanceEntity toEntity(WorkflowInstance domain);
}
```

**Mappings:**
- `start` → `start` (field names match)
- `input` → `input` (JsonNode passthrough)
- `taskExecutions` → loaded separately (not mapped)

```java
@Mapper(componentModel = "cdi")
public interface TaskInstanceEntityMapper {
    @Mapping(source = "taskExecutionId", target = "id")
    TaskExecution toDomain(TaskInstanceEntity entity);
    
    @Mapping(source = "id", target = "taskExecutionId")
    TaskInstanceEntity toEntity(TaskExecution domain);
}
```

**Key mapping:** `taskExecutionId` ↔ `id` (event ID becomes domain ID)

## Storage Implementation

### PostgreSQLWorkflowInstanceStorage

```java
@ApplicationScoped
public class PostgreSQLWorkflowInstanceStorage 
    implements WorkflowInstanceStorage {
    
    @Inject
    EntityManager em;
    
    @Inject
    WorkflowInstanceEntityMapper mapper;
    
    @Override
    public WorkflowInstance get(String id) {
        WorkflowInstanceEntity entity = em.find(
            WorkflowInstanceEntity.class, id
        );
        return entity != null ? mapper.toDomain(entity) : null;
    }
    
    @Override
    public Query<WorkflowInstance> query() {
        return new JpaQuery<>(em, mapper);
    }
}
```

## Database Schema

Schema is managed by Flyway migrations in `data-index-storage-migrations` module:

- `V1__initial_schema.sql` - Create tables, triggers, functions
- Future: `V2__*.sql`, `V3__*.sql`, etc.

**Tables:**
- `workflow_instances` - Normalized workflow data
- `task_instances` - Normalized task data
- `workflow_events_raw` - Raw JSONB events from FluentBit
- `task_events_raw` - Raw JSONB task events

**Triggers:**
- `normalize_workflow_event()` - Extracts fields from JSONB, UPSERTs to normalized tables
- `normalize_task_event()` - Same for task events

See: [Database Schema Documentation](../../data-index-docs/modules/ROOT/pages/developers/database-schema.adoc)

## Configuration

### Development (Dev Services)

Quarkus Dev Services auto-starts PostgreSQL:

```properties
# application-postgresql.properties
quarkus.datasource.devservices.enabled=true
quarkus.datasource.devservices.image-name=postgres:15
quarkus.datasource.devservices.db-name=dataindex
quarkus.datasource.devservices.username=dataindex
quarkus.datasource.devservices.password=dataindex123
```

### Production

```properties
# Provided via environment variables
quarkus.datasource.jdbc.url=${QUARKUS_DATASOURCE_JDBC_URL}
quarkus.datasource.username=${QUARKUS_DATASOURCE_USERNAME}
quarkus.datasource.password=${QUARKUS_DATASOURCE_PASSWORD}
```

### Hibernate Configuration

```properties
# Use UTC for timestamps
quarkus.hibernate-orm.jdbc.timezone=UTC

# Convert camelCase to snake_case
quarkus.hibernate-orm.physical-naming-strategy=org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy

# Never auto-generate schema (Flyway handles it)
quarkus.hibernate-orm.database.generation=none
```

## Testing

### Integration Tests

```java
@QuarkusTest
@TestTransaction
public class PostgreSQLStorageTest {
    
    @Inject
    EntityManager em;
    
    @Inject
    WorkflowInstanceStorage storage;
    
    @BeforeEach
    void setup() {
        // Create test data
        WorkflowInstanceEntity entity = new WorkflowInstanceEntity();
        entity.setId("test-123");
        em.persist(entity);
    }
    
    @Test
    void testGet() {
        WorkflowInstance instance = storage.get("test-123");
        assertNotNull(instance);
        assertEquals("test-123", instance.getId());
    }
}
```

**Important:** Tests use real PostgreSQL via Quarkus Dev Services.

## Dependencies

### Required

```xml
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-model</artifactId>
</dependency>
```

**Provides:** Domain model, storage interfaces

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-hibernate-orm</artifactId>
</dependency>
```

**Provides:** JPA/Hibernate ORM

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jdbc-postgresql</artifactId>
</dependency>
```

**Provides:** PostgreSQL JDBC driver

```xml
<dependency>
  <groupId>org.mapstruct</groupId>
  <artifactId>mapstruct</artifactId>
</dependency>
```

**Provides:** Entity ↔ domain mappers

## Field Naming Mapping

| Database Column | JPA Entity Field | Domain Model Field | GraphQL Field |
|----------------|-----------------|-------------------|---------------|
| `id` | `id` | `id` | `id` |
| `start` | `start` | `start` | `startDate` |
| `end` | `end` | `end` | `endDate` |
| `input` | `input` | `input` | `inputData` |
| `task_execution_id` | `taskExecutionId` | `id` | `id` |
| `instance_id` | `instanceId` | `instanceId` | `instanceId` |
| `task_position` | `taskPosition` | `taskPosition` | `taskPosition` |

**Never use these old names:**
- `inputArgs`, `outputArgs`
- `enter`, `exit`
- `triggerTime`, `leaveTime`

## Related Modules

- **data-index-model** - Domain model and storage interfaces
- **data-index-storage-common** - Abstract storage base classes
- **data-index-storage-migrations** - Flyway SQL migrations
- **data-index-service** - GraphQL API using this storage

## Contributing

**When adding fields:**

1. Add column to database schema (Flyway migration)
2. Add field to JPA entity
3. Add field to domain model
4. MapStruct auto-maps if names match (or add `@Mapping`)
5. Update trigger if field comes from events

**Code style:**
- Use MapStruct for entity ↔ domain conversion
- Don't create bidirectional JPA relationships (load separately)
- Don't add comments explaining WHAT (only WHY)
- Quote SQL reserved keywords: `@Column(name = "\"start\"")`
