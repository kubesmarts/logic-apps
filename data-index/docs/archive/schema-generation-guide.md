# Schema Generation from JPA Entities

This guide shows how to generate PostgreSQL DDL from JPA entities and deploy the schema.

## Quick Start

### Generate Schema from JPA Entities

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index

# Generate DDL from Hibernate
./mvnw clean compile -pl data-index-storage/data-index-storage-jpa-common

# Export schema (requires PostgreSQL profile)
./mvnw quarkus:dev -pl data-index-quarkus/data-index-service-quarkus-postgresql \
  -Dquarkus.hibernate-orm.database.generation=drop-and-create \
  -Dquarkus.hibernate-orm.scripts.generation=create \
  -Dquarkus.hibernate-orm.scripts.generation.create-target=target/schema-generated.sql
```

This generates `target/schema-generated.sql` with CREATE TABLE statements from JPA entities.

### Compare with Reference Schema

```bash
# Compare generated vs reference
diff target/schema-generated.sql docs/database-schema-v1.0.0.sql
```

**Expected differences**:
- Reference schema has more comments
- Reference schema has explicit indexes
- Reference schema has compatibility views
- Reference schema uses `IF NOT EXISTS` clauses

## Deployment Options

### Option 1: Deploy Reference Schema (Recommended)

Use the hand-crafted schema with comments and optimizations:

```bash
psql -U dataindex -d dataindex_db -f docs/database-schema-v1.0.0.sql
```

**Advantages**:
- Comprehensive comments
- Optimized indexes
- v0.8 compatibility views
- Consistent formatting

### Option 2: Deploy Generated Schema

Use Hibernate-generated schema (development/testing):

```bash
psql -U dataindex -d dataindex_db -f target/schema-generated.sql
```

**Advantages**:
- Guaranteed JPA compatibility
- Auto-updated when entities change

**Disadvantages**:
- No comments
- Minimal indexes
- No compatibility views

### Option 3: Hibernate Auto-DDL (Development Only)

Let Quarkus create schema on startup:

```properties
# src/main/resources/application.properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/dataindex_db
quarkus.datasource.username=dataindex
quarkus.datasource.password=dataindex

# Auto-create schema on startup (dev only!)
quarkus.hibernate-orm.database.generation=drop-and-create
quarkus.hibernate-orm.log.sql=true
```

⚠️ **WARNING**: Never use auto-DDL in production! Data loss will occur on restart.

## Production Deployment

### Step 1: Create Database

```bash
# Create database
createdb -U postgres dataindex_db

# Create user
psql -U postgres -c "CREATE USER dataindex WITH PASSWORD 'secure_password';"
psql -U postgres -c "GRANT ALL PRIVILEGES ON DATABASE dataindex_db TO dataindex;"
```

### Step 2: Deploy Schema

```bash
# Deploy reference schema
psql -U dataindex -d dataindex_db -f docs/database-schema-v1.0.0.sql

# Verify tables
psql -U dataindex -d dataindex_db -c "\dt"
```

**Expected tables**:
```
 public | attachments                  | table | dataindex
 public | comments                     | table | dataindex
 public | definitions                  | table | dataindex
 public | definitions_addons           | table | dataindex
 public | definitions_annotations      | table | dataindex
 public | definitions_nodes            | table | dataindex
 public | definitions_nodes_metadata   | table | dataindex
 public | definitions_roles            | table | dataindex
 public | jobs                         | table | dataindex
 public | milestones                   | table | dataindex
 public | nodes                        | table | dataindex
 public | processes                    | table | dataindex
 public | processes_addons             | table | dataindex
 public | processes_roles              | table | dataindex
 public | tasks                        | table | dataindex
 public | tasks_admin_groups           | table | dataindex
 public | tasks_admin_users            | table | dataindex
 public | tasks_excluded_users         | table | dataindex
 public | tasks_potential_groups       | table | dataindex
 public | tasks_potential_users        | table | dataindex
```

### Step 3: Verify Views

```bash
# Check compatibility views
psql -U dataindex -d dataindex_db -c "\dv"
```

**Expected views**:
```
 public | task_executions      | view  | dataindex
 public | workflow_definitions | view  | dataindex
 public | workflow_instances   | view  | dataindex
```

### Step 4: Configure Data Index

```properties
# src/main/resources/application.properties
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://dbhost:5432/dataindex_db
quarkus.datasource.username=dataindex
quarkus.datasource.password=${DB_PASSWORD}

# Validate schema (don't auto-create)
quarkus.hibernate-orm.database.generation=validate
```

### Step 5: Test Connection

```bash
# Start Data Index
./mvnw quarkus:dev -pl data-index-quarkus/data-index-service-quarkus-postgresql

# Should see in logs:
# INFO  [org.hibernate.tool.schema.internal.SchemaValidatorImpl] Validating schema
# INFO  [io.quarkus] data-index-service-postgresql 999-SNAPSHOT on JVM started in 2.456s
```

## Schema Migration

### Using Flyway (Recommended)

**1. Add Flyway extension**:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-flyway</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-postgresql</artifactId>
</dependency>
```

**2. Create migration scripts**:
```
src/main/resources/db/migration/
├── V1.0.0__initial_schema.sql          # Full schema from docs/
├── V1.0.1__add_indexes.sql             # Performance indexes
├── V1.1.0__add_new_field.sql           # Schema evolution
```

**3. Configure Flyway**:
```properties
quarkus.flyway.migrate-at-start=true
quarkus.flyway.baseline-on-migrate=true
quarkus.flyway.baseline-version=1.0.0
quarkus.hibernate-orm.database.generation=none
```

**4. Create baseline migration**:
```bash
cp docs/database-schema-v1.0.0.sql \
   src/main/resources/db/migration/V1.0.0__initial_schema.sql
```

**5. Run migration**:
```bash
./mvnw quarkus:dev -Dquarkus.flyway.migrate-at-start=true
```

**6. Verify migration history**:
```sql
SELECT * FROM flyway_schema_history ORDER BY installed_rank;
```

### Using Liquibase

**1. Add Liquibase extension**:
```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-liquibase</artifactId>
</dependency>
```

**2. Create changelog**:
```xml
<!-- src/main/resources/db/changeLog.xml -->
<databaseChangeLog>
  <changeSet id="1.0.0-initial" author="dataindex">
    <sqlFile path="db/sql/V1.0.0__initial_schema.sql"/>
  </changeSet>
</databaseChangeLog>
```

**3. Configure Liquibase**:
```properties
quarkus.liquibase.migrate-at-start=true
quarkus.liquibase.change-log=db/changeLog.xml
quarkus.hibernate-orm.database.generation=none
```

## Verifying Schema

### Check Table Structure

```sql
-- List all tables
SELECT table_name FROM information_schema.tables 
WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
ORDER BY table_name;

-- Describe processes table
\d processes

-- Check JSONB columns
SELECT column_name, data_type 
FROM information_schema.columns 
WHERE table_name = 'processes' AND data_type = 'jsonb';
```

### Check Indexes

```sql
-- List indexes
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
ORDER BY tablename, indexname;

-- Verify GIN index on variables
SELECT indexname, indexdef 
FROM pg_indexes 
WHERE tablename = 'processes' AND indexname LIKE '%variables%';
```

### Check Foreign Keys

```sql
-- List foreign key constraints
SELECT
    tc.table_name, 
    kcu.column_name, 
    ccu.table_name AS foreign_table_name,
    ccu.column_name AS foreign_column_name,
    tc.constraint_name
FROM information_schema.table_constraints AS tc 
JOIN information_schema.key_column_usage AS kcu
    ON tc.constraint_name = kcu.constraint_name
JOIN information_schema.constraint_column_usage AS ccu
    ON ccu.constraint_name = tc.constraint_name
WHERE tc.constraint_type = 'FOREIGN KEY'
ORDER BY tc.table_name;
```

### Verify Views

```sql
-- Check view definitions
SELECT table_name, view_definition 
FROM information_schema.views 
WHERE table_schema = 'public';

-- Test v1.0.0 compatibility view
SELECT workflowId, workflowName, state 
FROM workflow_instances 
LIMIT 5;
```

## Troubleshooting

### Error: "relation does not exist"

**Problem**: Table not created

**Solution**:
```bash
# Check if tables exist
psql -U dataindex -d dataindex_db -c "\dt"

# Re-run schema script
psql -U dataindex -d dataindex_db -f docs/database-schema-v1.0.0.sql
```

### Error: "column does not exist"

**Problem**: JPA entity field name doesn't match database column

**Solution**: Add `@Column` annotation with exact database name:
```java
@Column(name = "startTime")  // Matches SQL column name exactly
private ZonedDateTime start;
```

### Error: "operator does not exist: jsonb = character varying"

**Problem**: Querying JSONB column without JSON operators

**Solution**: Use JSONB path operators:
```java
// Wrong
criteriaBuilder.equal(root.get("variables"), someString);

// Correct
criteriaBuilder.function("jsonb_extract_path_text", String.class, 
    root.get("variables"), criteriaBuilder.literal("keyName"));
```

### Error: "Hibernate schema validation failed"

**Problem**: Schema doesn't match entities

**Solution**:
```bash
# Generate fresh schema from entities
./mvnw quarkus:dev -Dquarkus.hibernate-orm.database.generation=drop-and-create

# Or fix SQL to match entities:
# 1. Compare generated vs deployed schema
# 2. Apply missing columns/constraints
```

## Docker Deployment

### PostgreSQL Container

```bash
# Start PostgreSQL
docker run --name dataindex-postgres \
  -e POSTGRES_DB=dataindex_db \
  -e POSTGRES_USER=dataindex \
  -e POSTGRES_PASSWORD=dataindex \
  -p 5432:5432 \
  -v $(pwd)/docs/database-schema-v1.0.0.sql:/docker-entrypoint-initdb.d/init.sql \
  -d postgres:15

# Schema auto-loads on first start via init.sql
```

### Data Index Container

```bash
# Build Data Index image
./mvnw clean package -Dquarkus.container-image.build=true

# Run Data Index
docker run --name dataindex \
  --link dataindex-postgres:postgres \
  -e QUARKUS_DATASOURCE_JDBC_URL=jdbc:postgresql://postgres:5432/dataindex_db \
  -e QUARKUS_DATASOURCE_USERNAME=dataindex \
  -e QUARKUS_DATASOURCE_PASSWORD=dataindex \
  -e QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION=validate \
  -p 8080:8080 \
  -d data-index-service-postgresql:999-SNAPSHOT
```

## Kubernetes Deployment

### PostgreSQL StatefulSet

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: dataindex-schema
data:
  init.sql: |
    -- Paste contents of docs/database-schema-v1.0.0.sql here
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: dataindex-postgres
spec:
  serviceName: dataindex-postgres
  replicas: 1
  template:
    spec:
      containers:
      - name: postgres
        image: postgres:15
        env:
        - name: POSTGRES_DB
          value: dataindex_db
        - name: POSTGRES_USER
          value: dataindex
        - name: POSTGRES_PASSWORD
          valueFrom:
            secretKeyRef:
              name: dataindex-db-secret
              key: password
        volumeMounts:
        - name: schema
          mountPath: /docker-entrypoint-initdb.d
      volumes:
      - name: schema
        configMap:
          name: dataindex-schema
```

### Data Index Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dataindex
spec:
  replicas: 3
  template:
    spec:
      containers:
      - name: dataindex
        image: quay.io/yourorg/data-index-service-postgresql:1.0.0
        env:
        - name: QUARKUS_DATASOURCE_JDBC_URL
          value: jdbc:postgresql://dataindex-postgres:5432/dataindex_db
        - name: QUARKUS_DATASOURCE_USERNAME
          value: dataindex
        - name: QUARKUS_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: dataindex-db-secret
              key: password
        - name: QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION
          value: validate
```

## References

- **JPA Schema**: `data-index-storage-jpa-common/src/main/java/org/kie/kogito/index/jpa/model/`
- **SQL Schema**: `docs/database-schema-v1.0.0.sql`
- **Schema Validation**: `docs/jpa-schema-validation.md`
- **Quarkus Flyway**: https://quarkus.io/guides/flyway
- **Quarkus Liquibase**: https://quarkus.io/guides/liquibase
- **PostgreSQL Docker**: https://hub.docker.com/_/postgres
