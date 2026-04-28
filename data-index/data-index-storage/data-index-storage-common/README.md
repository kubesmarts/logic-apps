# Data Index Storage Common

Abstract base classes and common utilities for storage implementations.

## Overview

This module provides reusable components for implementing Data Index storage backends:

- Abstract storage base classes
- Query filter/sort converters
- Common storage utilities

**Does NOT contain:**
- Storage implementations (see `data-index-storage-postgresql`, `data-index-storage-elasticsearch`)
- Domain model (see `data-index-model`)
- Storage interfaces (see `data-index-model/api`)

## Purpose

Reduce code duplication across storage implementations by providing:

1. **Abstract storage base** - Common CRUD operations
2. **Filter converters** - Map GraphQL/API filters to backend-specific queries
3. **Query builders** - Reusable query construction logic

## Project Structure

```
data-index-storage-common/
├── src/main/java/.../storage/
│   ├── AbstractWorkflowInstanceStorage.java
│   ├── AbstractTaskExecutionStorage.java
│   └── filter/
│       ├── FilterConverter.java
│       └── SortConverter.java
└── pom.xml
```

## Abstract Storage Classes

### AbstractWorkflowInstanceStorage

Base implementation of `WorkflowInstanceStorage`:

```java
public abstract class AbstractWorkflowInstanceStorage 
    implements WorkflowInstanceStorage {
    
    // Template method pattern
    @Override
    public Query<WorkflowInstance> query() {
        return createQuery();
    }
    
    // Subclasses implement backend-specific query
    protected abstract Query<WorkflowInstance> createQuery();
    
    // Common utilities
    protected List<WorkflowInstance> applyFilters(
        List<WorkflowInstance> instances,
        List<AttributeFilter> filters
    ) {
        // Filter logic
    }
}
```

**Subclasses:**
- `PostgreSQLWorkflowInstanceStorage` (in `data-index-storage-postgresql`)
- `ElasticsearchWorkflowInstanceStorage` (in `data-index-storage-elasticsearch`, future)

### AbstractTaskExecutionStorage

Similar pattern for `TaskExecutionStorage`.

## Filter Converters

### FilterConverter

Converts `AttributeFilter` (from Kogito API) to backend-specific queries:

```java
public interface FilterConverter<T> {
    // Convert AttributeFilter to backend query type
    T convert(AttributeFilter filter);
}
```

**Implementations:**
- `JpaFilterConverter` - Converts to JPA Criteria API (PostgreSQL)
- `ElasticsearchFilterConverter` - Converts to Elasticsearch Query DSL (future)

### SortConverter

Converts `AttributeSort` to backend-specific sorting:

```java
public interface SortConverter<T> {
    T convert(AttributeSort sort);
}
```

## Usage Example

### PostgreSQL Implementation

```java
// In data-index-storage-postgresql
@ApplicationScoped
public class PostgreSQLWorkflowInstanceStorage 
    extends AbstractWorkflowInstanceStorage {
    
    @Inject
    EntityManager em;
    
    @Inject
    FilterConverter<Predicate> filterConverter;
    
    @Override
    protected Query<WorkflowInstance> createQuery() {
        return new JpaQuery<>(em, WorkflowInstanceEntity.class)
            .withConverter(filterConverter);
    }
    
    @Override
    public WorkflowInstance get(String id) {
        WorkflowInstanceEntity entity = em.find(WorkflowInstanceEntity.class, id);
        return mapper.toDomain(entity);
    }
}
```

### Filter Conversion

```java
// API call with filters
AttributeFilter statusFilter = new AttributeFilter("status", EQUAL, "COMPLETED");

// Convert to JPA Criteria
Predicate jpaPredicate = jpaFilterConverter.convert(statusFilter);
// → cb.equal(root.get("status"), WorkflowInstanceStatus.COMPLETED)

// Or convert to Elasticsearch Query DSL
QueryBuilder esQuery = esFilterConverter.convert(statusFilter);
// → { "term": { "status": "COMPLETED" } }
```

## Dependencies

### Required

```xml
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-model</artifactId>
</dependency>
```

**Used for:** Domain model and storage interfaces

```xml
<dependency>
  <groupId>org.kie.kogito</groupId>
  <artifactId>persistence-commons-api</artifactId>
</dependency>
```

**Used for:** `AttributeFilter`, `AttributeSort`, `Query` interfaces

## Testing

This module contains no implementation-specific tests. Tests are in storage implementation modules:

- `data-index-storage-postgresql` - JPA implementation tests
- `data-index-storage-elasticsearch` - Elasticsearch implementation tests (future)

## Related Modules

- **data-index-model** - Domain model and storage interfaces
- **data-index-storage-postgresql** - PostgreSQL/JPA implementation
- **data-index-storage-elasticsearch** - Elasticsearch implementation (future)
- **data-index-storage-migrations** - Flyway SQL migrations (PostgreSQL only)

## Contributing

**When adding common utilities:**

1. Ensure they're truly reusable across multiple backends (PostgreSQL, Elasticsearch)
2. Don't add backend-specific logic here (put in implementation modules)
3. Use abstract classes for template method pattern
4. Use interfaces for strategy pattern (converters)

**Code style:**
- Keep classes abstract (no concrete implementations)
- Don't add persistence technology dependencies (JPA, Elasticsearch)
- Don't add comments explaining WHAT (only WHY)
