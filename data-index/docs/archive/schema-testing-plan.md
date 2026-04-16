# Schema Testing and Validation Plan

This document outlines the testing strategy for validating PostgreSQL schema consistency between JPA entities and the reference DDL.

## Overview

Data Index v1.0.0 uses two schema definitions that must remain synchronized:

1. **JPA Entities** (`data-index-storage-jpa-common/src/main/java/org/kie/kogito/index/jpa/model/*.java`)
   - Source of truth for runtime ORM
   - Hibernate generates DDL from these at build time
   - Must match PostgreSQL schema for queries to work

2. **Reference DDL** (`docs/database-schema-v1.0.0.sql`)
   - Hand-crafted production schema
   - Includes optimization indexes, comments, views
   - Used for manual deployments and documentation

## Schema Generation Process

### Step 1: Generate Schema from JPA Entities

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index

# Run schema generation script
./scripts/generate-schema.sh
```

**What it does**:
1. Compiles JPA entities
2. Builds data-index-service-postgresql module
3. Uses Hibernate ORM to export DDL from entity annotations
4. Outputs `target/generated-schema/schema-generated.sql`

**Expected output**:
- CREATE TABLE statements for all entities
- ALTER TABLE statements for foreign keys
- Basic indexes (from JPA @Index annotations)

**Not included in generated schema**:
- Comments (COMMENT ON TABLE/COLUMN)
- Optimization indexes (custom indexes in reference DDL)
- Compatibility views (workflow_instances, task_executions)
- IF NOT EXISTS clauses

### Step 2: Compare Schemas

```bash
# Run comparison script
./scripts/compare-schemas.sh
```

**What it validates**:
- Table names match between generated and reference
- No BPMN legacy tables (milestones, tasks, comments, attachments)
- Column definitions are consistent
- Foreign keys are defined
- Index counts (reference may have more)

**Expected results**:
- ✅ PASS: All core tables match (processes, definitions, nodes, jobs)
- ⚠️ Reference has additional indexes (expected - performance optimization)
- ⚠️ Reference has comments and views (expected - documentation)
- ❌ FAIL: BPMN legacy tables found in generated schema → **Action required**

## Validation Criteria

### Critical (Must Pass)

✅ **Table structure match**:
- Generated schema includes: `definitions`, `processes`, `nodes`, `jobs`
- Generated schema includes collection tables: `definitions_roles`, `processes_roles`, etc.
- Generated schema includes definition nodes: `definitions_nodes`, `definitions_nodes_metadata`

❌ **No BPMN legacy tables**:
- `milestones` must NOT be in generated schema
- `tasks` must NOT be in generated schema
- `comments` must NOT be in generated schema
- `attachments` must NOT be in generated schema
- All `tasks_*` tables must NOT be in generated schema

✅ **Primary keys match**:
- `definitions` has composite PK (id, version)
- `processes` has PK (id)
- `nodes` has PK (id)
- `jobs` has PK (id)

✅ **Foreign keys exist**:
- `processes` → `definitions` (processId, version)
- `nodes` → `processes` (processInstanceId) with CASCADE DELETE
- `definitions_nodes` → `definitions` (process_id, process_version)

### Acceptable Differences

ℹ️ **More indexes in reference schema**:
- Reference has GIN indexes on JSONB columns
- Reference has optimization indexes on frequently-queried columns
- JPA entities may not have all @Index annotations

ℹ️ **Comments only in reference**:
- Hibernate doesn't generate COMMENT ON statements
- Reference schema has comprehensive documentation comments

ℹ️ **Views only in reference**:
- `workflow_instances`, `task_executions`, `workflow_definitions`
- Compatibility views for v1.0.0 terminology
- Not part of JPA entity model

ℹ️ **DDL syntax differences**:
- Reference uses `IF NOT EXISTS`
- Column ordering may differ
- Constraint naming may differ

### Issues to Fix

If comparison finds these issues, JPA entities must be updated:

❌ **BPMN entity still exists**:
- Remove `MilestoneEntity.java`
- Remove `UserTaskInstanceEntity.java`
- Remove `CommentEntity.java`
- Remove `AttachmentEntity.java`
- Update `ProcessInstanceEntity.java` to remove `@OneToMany` milestones field

❌ **Column type mismatch**:
- Example: Generated has `VARCHAR`, reference has `TEXT`
- Fix JPA entity field annotation: `@Column(columnDefinition = "TEXT")`

❌ **Missing foreign key**:
- Generated schema missing FK constraint
- Fix JPA entity: Add `@JoinColumn` and `@ForeignKey` annotations

❌ **Wrong table/column name**:
- Naming strategy mismatch
- Fix JPA entity: Add `@Table(name = "...")` or `@Column(name = "...")`

## Fixing Schema Inconsistencies

### Remove BPMN Legacy Entities

**Issue**: MilestoneEntity and UserTaskInstanceEntity still exist in codebase

**Files to modify**:
1. Delete `MilestoneEntity.java`
2. Delete `UserTaskInstanceEntity.java`
3. Delete `CommentEntity.java`
4. Delete `AttachmentEntity.java`
5. Edit `ProcessInstanceEntity.java`:
   ```java
   // REMOVE:
   @OneToMany(cascade = CascadeType.ALL, mappedBy = "processInstance")
   private List<MilestoneEntity> milestones;
   ```

6. Update storage implementations to remove milestone/task references

**Verification**:
```bash
# Re-generate schema
./scripts/generate-schema.sh

# Verify BPMN tables are gone
grep -i "milestones\|tasks\|comments\|attachments" target/generated-schema/schema-generated.sql
# Should return no results
```

### Add Missing Indexes to JPA

**Issue**: Reference schema has performance indexes not in generated schema

**Solution**: Add @Index annotations to entities

Example - add GIN index on variables:
```java
@Entity
@Table(name = "processes", 
       indexes = @Index(name = "idx_processes_variables", columnList = "variables"))
public class ProcessInstanceEntity {
    @Convert(converter = JsonBinaryConverter.class)
    @Column(columnDefinition = "jsonb")
    private JsonNode variables;
}
```

**Note**: Some indexes (like GIN for JSONB) may not be expressible in JPA. Keep these only in reference DDL.

### Fix Column Type Mismatches

**Issue**: Generated has `VARCHAR(255)` but reference has `TEXT`

**Solution**: Use `@Column(columnDefinition = "...")` for exact control

```java
// Before
private String description;

// After
@Column(columnDefinition = "TEXT")
private String description;
```

## Automated Testing

### Integration Test

Create a Quarkus test that validates schema at runtime:

```java
@QuarkusTest
@TestProfile(PostgreSQLSchemaValidationTestProfile.class)
public class SchemaValidationIT {
    
    @Inject
    EntityManager em;
    
    @Test
    public void testCoreTablesExist() {
        assertTableExists("definitions");
        assertTableExists("processes");
        assertTableExists("nodes");
        assertTableExists("jobs");
    }
    
    @Test
    public void testBpmnTablesDoNotExist() {
        assertTableDoesNotExist("milestones");
        assertTableDoesNotExist("tasks");
        assertTableDoesNotExist("comments");
        assertTableDoesNotExist("attachments");
    }
    
    @Test
    public void testForeignKeysExist() {
        assertForeignKeyExists("processes", "definitions");
        assertForeignKeyExists("nodes", "processes");
    }
    
    @Test
    public void testJsonbColumnsExist() {
        assertColumnType("processes", "variables", "jsonb");
        assertColumnType("nodes", "inputArgs", "jsonb");
        assertColumnType("nodes", "outputArgs", "jsonb");
    }
    
    private void assertTableExists(String tableName) {
        Query q = em.createNativeQuery(
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_name = ?");
        q.setParameter(1, tableName);
        assertThat(q.getResultList()).isNotEmpty();
    }
    
    private void assertTableDoesNotExist(String tableName) {
        Query q = em.createNativeQuery(
            "SELECT table_name FROM information_schema.tables " +
            "WHERE table_schema = 'public' AND table_name = ?");
        q.setParameter(1, tableName);
        assertThat(q.getResultList()).isEmpty();
    }
    
    private void assertForeignKeyExists(String table, String referencedTable) {
        Query q = em.createNativeQuery(
            "SELECT tc.constraint_name FROM information_schema.table_constraints tc " +
            "JOIN information_schema.constraint_column_usage ccu " +
            "  ON ccu.constraint_name = tc.constraint_name " +
            "WHERE tc.constraint_type = 'FOREIGN KEY' " +
            "  AND tc.table_name = ? " +
            "  AND ccu.table_name = ?");
        q.setParameter(1, table);
        q.setParameter(2, referencedTable);
        assertThat(q.getResultList()).isNotEmpty();
    }
    
    private void assertColumnType(String table, String column, String expectedType) {
        Query q = em.createNativeQuery(
            "SELECT data_type FROM information_schema.columns " +
            "WHERE table_name = ? AND column_name = ?");
        q.setParameter(1, table);
        q.setParameter(2, column);
        List<?> result = q.getResultList();
        assertThat(result).isNotEmpty();
        assertThat(result.get(0)).isEqualTo(expectedType);
    }
}
```

### CI/CD Integration

Add schema validation to CI pipeline:

```yaml
# .github/workflows/schema-validation.yml
name: Schema Validation

on: [push, pull_request]

jobs:
  validate-schema:
    runs-on: ubuntu-latest
    
    services:
      postgres:
        image: postgres:15
        env:
          POSTGRES_DB: dataindex_db
          POSTGRES_USER: dataindex
          POSTGRES_PASSWORD: dataindex
        options: >-
          --health-cmd pg_isready
          --health-interval 10s
          --health-timeout 5s
          --health-retries 5
    
    steps:
      - uses: actions/checkout@v3
      
      - name: Set up JDK 21
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'
      
      - name: Generate schema from JPA
        run: ./scripts/generate-schema.sh
      
      - name: Compare schemas
        run: ./scripts/compare-schemas.sh
      
      - name: Run schema validation tests
        run: mvn test -pl data-index-quarkus/data-index-service-postgresql -Dtest=SchemaValidationIT
```

## Success Criteria

### Phase 1: Schema Generation ✅
- [x] Scripts created (`generate-schema.sh`, `compare-schemas.sh`)
- [x] Reference DDL created (`docs/database-schema-v1.0.0.sql`)
- [ ] Generated schema matches reference (core tables)
- [ ] No BPMN legacy tables in generated schema

### Phase 2: Entity Cleanup
- [ ] Remove MilestoneEntity, UserTaskInstanceEntity, CommentEntity, AttachmentEntity
- [ ] Update ProcessInstanceEntity (remove milestones field)
- [ ] Update storage implementations
- [ ] Re-generate schema and verify

### Phase 3: Automated Testing
- [ ] Create SchemaValidationIT integration test
- [ ] Add CI/CD pipeline for schema validation
- [ ] Document schema evolution process

### Phase 4: Compatibility Layer Testing
- [ ] Test v0.8 GraphQL queries against v1.0.0 schema
- [ ] Verify compatibility views work correctly
- [ ] Test query performance with indexes

## Next Steps

1. **Run schema generation** (in progress)
   ```bash
   ./scripts/generate-schema.sh
   ```

2. **Compare and analyze**
   ```bash
   ./scripts/compare-schemas.sh
   ```

3. **Fix issues** (if BPMN entities found)
   - Remove BPMN legacy entities from JPA model
   - Update ProcessInstanceEntity

4. **Verify consistency**
   - Re-run generation and comparison
   - All checks should pass

5. **Plan compatibility testing**
   - Design v0.8 GraphQL API test cases
   - Test queries against compatibility views
   - Validate mutation proxying

## References

- **Schema Generation Guide**: `docs/schema-generation-guide.md`
- **JPA Validation Guide**: `docs/jpa-schema-validation.md`
- **Reference DDL**: `docs/database-schema-v1.0.0.sql`
- **Generation Script**: `scripts/generate-schema.sh`
- **Comparison Script**: `scripts/compare-schemas.sh`
