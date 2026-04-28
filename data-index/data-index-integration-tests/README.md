# Data Index Integration Tests

Integration tests for Data Index with different storage backends.

## Overview

This module contains integration tests that verify the complete Data Index stack:

- GraphQL API (SmallRye GraphQL)
- Storage layer implementations
- Database backends (PostgreSQL, Elasticsearch)
- End-to-end data flow

**Tests are organized by storage backend** to ensure clean dependency isolation and independent execution.

## Module Structure

```
data-index-integration-tests/
├── pom.xml (parent aggregator)
├── data-index-integration-tests-postgresql/
│   ├── pom.xml (PostgreSQL-specific dependencies)
│   ├── src/test/java/
│   │   └── .../graphql/
│   │       └── WorkflowInstanceGraphQLApiTest.java
│   └── src/test/resources/
│       └── application.properties (PostgreSQL test config)
└── data-index-integration-tests-elasticsearch/
    ├── pom.xml (Elasticsearch-specific dependencies - future)
    └── src/test/java/ (Elasticsearch tests - future)
```

## Running Tests

### PostgreSQL Integration Tests

```bash
# Run all PostgreSQL integration tests
mvn test -pl data-index-integration-tests-postgresql

# Run specific test
mvn test -pl data-index-integration-tests-postgresql -Dtest=WorkflowInstanceGraphQLApiTest

# From integration tests directory
cd data-index-integration-tests/data-index-integration-tests-postgresql
mvn test
```

**What happens:**
1. Maven Surefire activates PostgreSQL profile (`-Dquarkus.profile=postgresql`)
2. Quarkus Dev Services auto-starts PostgreSQL 15 container
3. Flyway runs migrations from `data-index-storage-migrations`
4. Tests execute against real PostgreSQL database
5. Container auto-stops after tests

**No manual database setup required!**

### Elasticsearch Integration Tests (Future)

```bash
mvn test -pl data-index-integration-tests-elasticsearch
```

### Run All Integration Tests

```bash
# From data-index root
mvn test -pl data-index-integration-tests

# Or explicitly
mvn test -pl data-index-integration-tests-postgresql,data-index-integration-tests-elasticsearch
```

## Test Configuration

### PostgreSQL Module

**Profile activation** (in `pom.xml`):
```xml
<systemPropertyVariables>
  <quarkus.profile>postgresql</quarkus.profile>
</systemPropertyVariables>
```

**Test configuration** (`application.properties`):
```properties
# Dev Services auto-starts PostgreSQL
quarkus.datasource.devservices.enabled=true
quarkus.datasource.devservices.image-name=postgres:15

# Flyway migrations
quarkus.flyway.migrate-at-start=true

# Hibernate settings
quarkus.hibernate-orm.mapping.format.global=ignore
```

**No `-Dquarkus.profile=postgresql` needed** - it's configured in `pom.xml`!

### Elasticsearch Module

Profile activated via `pom.xml` (same pattern as PostgreSQL).

## What's Tested

### WorkflowInstanceGraphQLApiTest

Tests the complete GraphQL API:

**✅ Workflow instance queries:**
- `getWorkflowInstances(limit)` - List workflows
- `getWorkflowInstance(id)` - Single workflow by ID
- Workflow fields: id, namespace, name, status, startDate, endDate

**✅ Task execution queries:**
- `getTaskExecutions(limit)` - List task executions
- `getTaskExecutionsByWorkflowInstance(workflowInstanceId)` - Tasks for workflow
- Task fields: id, taskName, taskPosition, status, startDate, endDate

**✅ Relationships:**
- Workflow → TaskExecutions (one-to-many)
- Lazy loading via GraphQL

**✅ JSON fields:**
- `inputData` / `outputData` exposed as String
- Workflow and task JSON payloads

**✅ GraphQL schema:**
- Introspection query
- Schema types validation

## Test Data Setup

Tests use `@BeforeEach` to create test data:

```java
@BeforeEach
@Transactional
public void setupTestData() {
    // Create workflow instances with JPA entities
    WorkflowInstanceEntity workflow = new WorkflowInstanceEntity();
    workflow.setId("test-workflow-1");
    // ... set fields
    em.persist(workflow);
    
    // Create task instances
    TaskInstanceEntity task = new TaskInstanceEntity();
    task.setInstanceId("test-workflow-1");
    // ... set fields
    em.persist(task);
}
```

**Cleanup** in `@AfterEach`:
```java
@AfterEach
@Transactional
public void cleanupTestData() {
    em.createQuery("DELETE FROM TaskInstanceEntity").executeUpdate();
    em.createQuery("DELETE FROM WorkflowInstanceEntity").executeUpdate();
}
```

## Dependencies

### PostgreSQL Module Dependencies

```xml
<!-- Service under test -->
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-service</artifactId>
  <scope>test</scope>
</dependency>

<!-- PostgreSQL storage -->
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-storage-postgresql</artifactId>
  <scope>test</scope>
</dependency>

<!-- Flyway migrations -->
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-storage-migrations</artifactId>
  <scope>test</scope>
</dependency>

<!-- Quarkus PostgreSQL -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-hibernate-orm</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jdbc-postgresql</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-flyway</artifactId>
  <scope>test</scope>
</dependency>
```

All dependencies are `test` scope - **no production code in this module.**

## CI Integration

### GitHub Actions

```yaml
- name: Run PostgreSQL integration tests
  run: |
    mvn test -pl data-index/data-index-integration-tests-postgresql

- name: Run Elasticsearch integration tests (future)
  run: |
    mvn test -pl data-index/data-index-integration-tests-elasticsearch
```

### Parallelization

Integration test modules can run in parallel:

```bash
mvn test -pl data-index-integration-tests-postgresql &
mvn test -pl data-index-integration-tests-elasticsearch &
wait
```

## Troubleshooting

### "Dev Services failed to start"

**Issue:** PostgreSQL container failed to start.

**Solution:**
```bash
# Check Docker is running
docker ps

# Pull PostgreSQL image manually
docker pull postgres:15

# Check ports are available
lsof -i :5432
```

### "Flyway migration failed"

**Issue:** Migration SQL error.

**Solution:**
```bash
# Check migration files exist
ls data-index-storage-migrations/src/main/resources/db/migration/

# Run with debug logging
mvn test -Dquarkus.log.category.org.flywaydb.level=DEBUG
```

### "Test compilation errors"

**Issue:** Cannot find JPA entities.

**Solution:**
- Verify `data-index-storage-postgresql` is in dependencies
- Check `test` scope is set
- Rebuild: `mvn clean install -pl data-index-storage-postgresql`

## Adding New Tests

### 1. Create test class

```java
package org.kubesmarts.logic.dataindex.graphql;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
public class MyNewTest {
    
    @Test
    public void testSomething() {
        // Test code
    }
}
```

### 2. Run test

```bash
mvn test -pl data-index-integration-tests-postgresql -Dtest=MyNewTest
```

### 3. For Elasticsearch tests

Create in `data-index-integration-tests-elasticsearch/src/test/java/`

Same pattern - Elasticsearch profile auto-activated.

## Best Practices

**✅ DO:**
- Use `@BeforeEach` to set up clean test data
- Use `@AfterEach` to clean up (prevent test pollution)
- Use `@Transactional` for setup/cleanup methods
- Test against real database (Dev Services)
- Keep tests focused (one behavior per test)

**❌ DON'T:**
- Don't share test data between tests
- Don't assume data exists (always create in setup)
- Don't use mocks for database (test the real thing)
- Don't skip cleanup (causes flaky tests)

## Related Modules

- **data-index-service** - Service under test
- **data-index-storage-postgresql** - PostgreSQL storage implementation
- **data-index-storage-elasticsearch** - Elasticsearch storage implementation (future)
- **data-index-storage-migrations** - Flyway SQL migration scripts

## Contributing

**When adding integration tests:**

1. Choose correct module (PostgreSQL or Elasticsearch)
2. Add test class in `src/test/java/`
3. Use `@QuarkusTest` annotation
4. Set up/clean up test data properly
5. Test via GraphQL API (not direct storage layer)
6. Run locally before pushing: `mvn test -pl <module>`

**Code style:**
- Keep tests readable (descriptive test names)
- Don't add comments explaining WHAT (only WHY)
- Use AssertJ assertions for readability
