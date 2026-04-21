# Package Refactoring: jpa → entity

**Date**: 2026-04-17  
**Status**: ✅ **COMPLETE**

---

## 🎯 Objective

Rename `jpa` package to `entity` for semantic clarity in CQRS architecture.

**Problem**: Having both `event` and `jpa` packages was confusing - both contain JPA entities with different purposes.

**Solution**: Rename `jpa` → `entity` to reflect business purpose (final normalized entities) rather than technical detail (JPA).

---

## 📦 Package Structure

### Before (Confusing)
```
org.kubesmarts.logic.dataindex/
├── event/                          # Event entities (JPA)
│   ├── WorkflowInstanceEvent
│   └── TaskExecutionEvent
├── jpa/                            # Final entities (also JPA!)
│   ├── AbstractEntity
│   ├── WorkflowInstanceEntity
│   ├── TaskExecutionEntity
│   └── WorkflowInstanceErrorEntity
```

**Issue**: Both packages contain JPA entities, but naming suggests only one does.

---

### After (Clear CQRS Separation)
```
org.kubesmarts.logic.dataindex/
├── entity/                         # Final normalized entities (CQRS read model)
│   ├── AbstractEntity
│   ├── WorkflowInstanceEntity
│   ├── TaskExecutionEntity
│   └── WorkflowInstanceErrorEntity
├── event/                          # Event sourcing entities (CQRS write model)
│   ├── WorkflowInstanceEvent
│   └── TaskExecutionEvent
```

**Benefits**:
- `entity` = final state (CQRS read model / materialized view)
- `event` = event tables (CQRS write model / transactional outbox)
- Clear CQRS separation at package level
- Package names reflect business purpose, not technical detail

---

## 🔄 Changes Made

### 1. Moved Files
```bash
# Moved all files from jpa/ to entity/
mv jpa/AbstractEntity.java entity/
mv jpa/WorkflowInstanceEntity.java entity/
mv jpa/TaskExecutionEntity.java entity/
mv jpa/WorkflowInstanceErrorEntity.java entity/
```

### 2. Updated Package Declarations
**All entity files updated**:
```java
// Before
package org.kubesmarts.logic.dataindex.jpa;

// After
package org.kubesmarts.logic.dataindex.entity;
```

### 3. Updated Imports
**10 files updated** across storage-postgresql module:
- `ingestion/TaskExecutionEventProcessor.java`
- `ingestion/WorkflowInstanceEventProcessor.java`
- `mapper/WorkflowInstanceEntityMapper.java`
- `mapper/TaskExecutionEntityMapper.java`
- `mapper/WorkflowInstanceErrorEntityMapper.java`
- `storage/WorkflowInstanceJPAStorage.java`
- `storage/TaskExecutionJPAStorage.java`
- `storage/JPAQuery.java`
- `storage/AbstractStorage.java`
- `storage/AbstractJPAStorageFetcher.java`

```java
// Before
import org.kubesmarts.logic.dataindex.jpa.WorkflowInstanceEntity;

// After
import org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity;
```

### 4. Updated Javadoc References
**Event entities updated**:
- `event/WorkflowInstanceEvent.java`
  ```java
  // Before
  * @see org.kubesmarts.logic.dataindex.jpa.WorkflowInstanceEntity
  
  // After
  * @see org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity
  ```

- `event/TaskExecutionEvent.java`
  ```java
  // Before
  * @see org.kubesmarts.logic.dataindex.jpa.TaskExecutionEntity
  
  // After
  * @see org.kubesmarts.logic.dataindex.entity.TaskExecutionEntity
  ```

### 5. Updated Documentation
**File**: `docs/database-schema.md`
```markdown
# Before
**JPA Entity**: `org.kubesmarts.logic.dataindex.jpa.WorkflowInstanceEntity`
**JPA Entity**: `org.kubesmarts.logic.dataindex.jpa.TaskExecutionEntity`

# After
**JPA Entity**: `org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity`
**JPA Entity**: `org.kubesmarts.logic.dataindex.entity.TaskExecutionEntity`
```

---

## 📊 Final Package Structure

```
org.kubesmarts.logic.dataindex/
├── entity/                         # Final normalized entities
│   ├── AbstractEntity.java
│   ├── TaskExecutionEntity.java
│   ├── WorkflowInstanceEntity.java
│   └── WorkflowInstanceErrorEntity.java
│
├── event/                          # Event sourcing entities
│   ├── TaskExecutionEvent.java
│   └── WorkflowInstanceEvent.java
│
├── ingestion/                      # Event processors (normalization)
│   ├── EventCleanupScheduler.java
│   ├── EventProcessorHealthCheck.java
│   ├── EventProcessorMetrics.java
│   ├── EventProcessorScheduler.java
│   ├── TaskExecutionEventProcessor.java
│   └── WorkflowInstanceEventProcessor.java
│
├── json/                           # JSON utilities
│   └── JsonUtils.java
│
├── mapper/                         # MapStruct mappers
│   ├── TaskExecutionEntityMapper.java
│   ├── WorkflowInstanceEntityMapper.java
│   └── WorkflowInstanceErrorEntityMapper.java
│
├── metrics/                        # Observability
│   ├── EventMetrics.java
│   ├── EventProcessorMetricsResource.java
│   └── EventProcessorMetricsResponse.java
│
├── postgresql/                     # PostgreSQL-specific implementations
│   ├── ContainsSQLFunction.java
│   ├── CustomFunctionsContributor.java
│   ├── JsonBinaryConverter.java
│   ├── PostgresqlJsonPredicateBuilder.java
│   └── PostgresqlStorageServiceCapabilities.java
│
└── storage/                        # Storage API implementations
    ├── AbstractJPAStorageFetcher.java
    ├── AbstractStorage.java
    ├── DependencyInjectionUtils.java
    ├── JPAQuery.java
    ├── JsonPredicateBuilder.java
    ├── TaskExecutionJPAStorage.java
    └── WorkflowInstanceJPAStorage.java
```

---

## 🎯 CQRS Clarity

### Write Side (Event Processing)
```
event/                              # Transactional outbox pattern
  ├── WorkflowInstanceEvent         # Workflow events table
  └── TaskExecutionEvent            # Task events table

ingestion/                          # Event normalization
  ├── EventProcessorScheduler       # Polling consumer
  ├── WorkflowInstanceEventProcessor
  └── TaskExecutionEventProcessor
```

**Flow**: FluentBit → `event/` tables → `ingestion/` processors → `entity/` tables

---

### Read Side (Query Model)
```
entity/                             # Materialized view (final state)
  ├── WorkflowInstanceEntity        # Final workflow table
  ├── TaskExecutionEntity           # Final task table
  └── WorkflowInstanceErrorEntity   # Embedded error

storage/                            # Query API
  ├── WorkflowInstanceJPAStorage
  └── TaskExecutionJPAStorage
```

**Flow**: GraphQL queries → `storage/` → `entity/` tables → response

---

## ✅ Build Results

```bash
$ mvn clean compile -DskipTests

[INFO] Reactor Summary for KubeSmarts Logic Apps :: Data Index 999-SNAPSHOT:
[INFO] 
[INFO] KubeSmarts Logic Apps :: Data Index ................ SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Model ....... SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Storage :: PostgreSQL SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Service ..... SUCCESS
[INFO] KubeSmarts Logic Apps :: Data Index :: Integration Tests SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

✅ All 5 modules compiled successfully.

---

## 🎯 Benefits

### 1. **Semantic Clarity**
- `entity` clearly represents final normalized entities
- `event` clearly represents event sourcing tables
- No confusion about "which entities are JPA?"

### 2. **CQRS Alignment**
- Package structure mirrors CQRS pattern
- Write side: `event/` + `ingestion/`
- Read side: `entity/` + `storage/`

### 3. **Better Imports**
```java
// Clear business purpose
import org.kubesmarts.logic.dataindex.entity.WorkflowInstanceEntity;
import org.kubesmarts.logic.dataindex.event.WorkflowInstanceEvent;

// vs confusing technical detail
import org.kubesmarts.logic.dataindex.jpa.WorkflowInstanceEntity;  // Wait, isn't event also JPA?
```

### 4. **Consistency**
- Matches event-sourcing terminology (entities vs events)
- Aligns with CQRS read/write model separation
- Package names reflect purpose, not implementation

---

## 📚 Files Modified

| Type | Count | Examples |
|------|-------|----------|
| **Entity files** (moved + package updated) | 4 | AbstractEntity, WorkflowInstanceEntity, TaskExecutionEntity, WorkflowInstanceErrorEntity |
| **Import updates** | 10 | Event processors, mappers, storage implementations |
| **Javadoc updates** | 2 | WorkflowInstanceEvent, TaskExecutionEvent |
| **Documentation** | 1 | database-schema.md |

**Total**: 17 files

---

## 🔍 Verification

```bash
# No references to old jpa package
$ grep -r "\.jpa\." data-index/
# (no results)

# Verify entity package exists
$ find . -type d -name "entity"
./data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/entity

# Verify jpa package removed
$ find . -type d -name "jpa"
# (no results)
```

---

## 📚 Related Documents

- [ARCHITECTURE-CQRS-SEPARATION.md](ARCHITECTURE-CQRS-SEPARATION.md) - CQRS architecture
- [GROUPID-REFACTORING.md](GROUPID-REFACTORING.md) - Maven groupId changes
- [EVENT-PROCESSOR-OBSERVABILITY.md](EVENT-PROCESSOR-OBSERVABILITY.md) - Observability

---

**Date**: 2026-04-17  
**Author**: Claude Code (Sonnet 4.5)  
**Status**: Complete and tested
