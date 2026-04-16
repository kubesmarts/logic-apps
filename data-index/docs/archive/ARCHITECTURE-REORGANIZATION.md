# Data Index Architecture Reorganization

**Date**: 2026-04-16  
**Status**: ✅ **COMPLETED** - Clean 3-module architecture achieved

---

## What Changed

### Summary

Reorganized Data Index from complex multi-module v0.8 architecture to a clean **3-module structure** for Serverless Workflow 1.0.0:
- ✅ **data-index-model** → Domain models + storage API interfaces
- ✅ **data-index-storage-postgresql** → PostgreSQL JPA implementation
- ✅ **data-index-service** → Quarkus service with SmallRye GraphQL API

**Key Decisions**:
- ✅ Deleted ALL v0.8 modules (clean break from legacy)
- ✅ Removed "v1" suffix (this is now the only version)
- ✅ Fixed split package warning (storage interfaces → `api` package)
- ✅ Combined GraphQL + Service layers (no need for separation in single-API world)

---

## Before (v0.8 Multi-Module Architecture)

### Problem: Over-Engineered for Current Needs

```
data-index/
├── data-index-common/                  # Utilities
├── data-index-storage/                 # Storage layer parent
│   ├── data-index-storage-api/         # Storage interfaces
│   ├── data-index-storage-jpa-common/  # JPA common code
│   ├── data-index-storage-postgresql/  # PostgreSQL impl
│   └── data-index-storage-mongodb/     # MongoDB impl (unused)
├── data-index-graphql/                 # GraphQL infrastructure (vert.x)
├── data-index-graphql-addons/          # GraphQL addons
├── data-index-service/                 # Service layer
│   └── data-index-service-common/      # Service logic
├── data-index-mutations/               # Legacy mutations
├── data-index-test-utils/              # Test utilities
└── data-index-quarkus/                 # Quarkus runtime
    ├── data-index-service-postgresql/  # PostgreSQL runtime
    └── data-index-service-mongodb/     # MongoDB runtime (unused)
```

**Issues**:
1. ❌ Designed for multi-storage support (MongoDB, Infinispan) - no longer needed
2. ❌ Designed for multi-framework support (Spring Boot, Quarkus) - Quarkus-only now
3. ❌ Designed for v0.8 + v1.0 coexistence - v0.8 removed
4. ❌ Split package warning (`org.kubesmarts.logic.dataindex.storage` in 2 modules)
5. ❌ "v1" suffix implied multiple versions would coexist
6. ❌ Separation between GraphQL and Service layers unnecessary (single API)

---

## After (Clean 3-Module Architecture)

### Solution: Simplified Structure

```
data-index/
├── data-index-model/                   # 📦 Domain Model
│   ├── org.kubesmarts.logic.dataindex.model/    # Domain entities
│   │   ├── WorkflowInstance.java
│   │   ├── WorkflowInstanceStatus.java
│   │   ├── WorkflowInstanceError.java
│   │   ├── TaskExecution.java
│   │   └── Workflow.java
│   └── org.kubesmarts.logic.dataindex.api/     # Storage interfaces
│       ├── WorkflowInstanceStorage.java        # (moved from .storage)
│       └── TaskExecutionStorage.java           # (moved from .storage)
│
├── data-index-storage-postgresql/      # 💾 PostgreSQL Storage
│   ├── org.kubesmarts.logic.dataindex.jpa/     # JPA Entities
│   ├── org.kubesmarts.logic.dataindex.mapper/  # MapStruct Mappers
│   ├── org.kubesmarts.logic.dataindex.storage/ # JPA Storage Impl
│   ├── org.kubesmarts.logic.dataindex.postgresql/ # PostgreSQL-specific
│   └── org.kubesmarts.logic.dataindex.json/    # JSON utilities
│
└── data-index-service/                 # 🚀 Quarkus + GraphQL
    ├── org.kubesmarts.logic.dataindex.graphql/ # SmallRye GraphQL API
    │   ├── WorkflowInstanceGraphQLApi.java
    │   └── JsonNodeScalar.java
    └── src/main/resources/
        └── application.properties
```

**Benefits**:
1. ✅ Single storage backend (PostgreSQL only - matches Red Hat strategy)
2. ✅ Single runtime (Quarkus only - no Spring Boot complexity)
3. ✅ Single API version (SW 1.0.0 only - clean break from legacy)
4. ✅ No split packages (storage interfaces in `.api` package)
5. ✅ No version suffixes (this IS the version)
6. ✅ GraphQL + Service combined (no artificial separation)

---

## Detailed Changes

### 1. Deleted v0.8 Modules

**Removed entirely**:
- ❌ `data-index-common` - Utilities moved to storage-postgresql
- ❌ `data-index-graphql` - v0.8 vert.x GraphQL (replaced by SmallRye)
- ❌ `data-index-graphql-addons` - v0.8 addons
- ❌ `data-index-mutations` - Legacy mutation support
- ❌ `data-index-test-utils` - Test utilities
- ❌ `data-index-service` - Service layer
- ❌ `data-index-storage` - Old storage parent with multi-backend support
- ❌ `data-index-quarkus` - Runtime assembly modules

**Why**: Clean break from v0.8 architecture. v0.8 compatibility will be added LATER via adapters on top of new architecture, not as coexisting infrastructure.

---

### 2. Renamed v1 Modules (Removed Suffix)

**Before** → **After**:
- `data-index-storage-postgresql-v1` → `data-index-storage-postgresql`
- `data-index-service-v1` → `data-index-service`
- `data-index-model` → `data-index-model` (unchanged)

**Updated**:
- All POM artifact IDs
- All dependency references
- All module declarations in parent POM
- All documentation

**Why**: "v1" suffix implied multiple versions would coexist. This is now THE architecture. When v0.8 compatibility is added, it will be as adapters/facades, not parallel infrastructure.

---

### 3. Fixed Split Package Warning

**Problem**: `org.kubesmarts.logic.dataindex.storage` existed in both:
- `data-index-model` (storage interfaces)
- `data-index-storage-postgresql` (storage implementations)

**Solution**: Moved storage interfaces to new package:
- `org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage`
- `org.kubesmarts.logic.dataindex.api.TaskExecutionStorage`

**Files Updated**:
- `data-index-model/src/main/java/org/kubesmarts/logic/dataindex/api/*`
- `data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/*` (import updates)
- `data-index-service/src/main/java/org/kubesmarts/logic/dataindex/graphql/*` (import updates)

**Result**: No more split package warnings during build ✅

---

### 4. Combined GraphQL + Service Layers

**Before**: Artificial separation between GraphQL API and Service layer
- `data-index-graphql` - GraphQL API classes
- `data-index-service-common` - Service logic
- `data-index-service-postgresql` - Runtime assembly

**After**: Single service module with GraphQL
- `data-index-service` - Quarkus service + SmallRye GraphQL API

**Why**: 
- No need for separation in single-API world
- Service layer was essentially empty (no business logic beyond storage queries)
- GraphQL API directly calls storage layer (no intermediate service needed)
- Simpler dependency graph

---

## Build Verification

### Before Reorganization
- ⚠️ 15+ modules
- ⚠️ Split package warnings
- ⚠️ "v1" suffix on new modules
- ⚠️ Coexistence with v0.8 infrastructure

### After Reorganization
- ✅ 3 clean modules
- ✅ No split package warnings
- ✅ No version suffixes
- ✅ Clean break from v0.8
- ✅ Build time: ~7 seconds
- ✅ Startup time: ~2.3 seconds
- ✅ Container image: `org.kie.kogito/data-index-service:999-SNAPSHOT`

---

## Module Dependencies

```
data-index-service
    ↓ depends on
data-index-storage-postgresql
    ↓ depends on
data-index-model
```

**Clean hierarchy**: No circular dependencies, clear layering.

---

## Migration Guide (v0.8 → Current)

### For Users

**Old command**:
```bash
mvn quarkus:dev -pl data-index-quarkus/data-index-service-postgresql
```

**New command**:
```bash
mvn quarkus:dev -pl data-index-service
```

### For Developers

**Old imports**:
```java
import org.kubesmarts.logic.dataindex.storage.WorkflowInstanceStorage;
```

**New imports**:
```java
import org.kubesmarts.logic.dataindex.api.WorkflowInstanceStorage;
```

---

## Rationale

### Why Delete v0.8 Instead of Coexist?

**Decision**: Clean break from v0.8, add compatibility layer later if needed.

**Reasoning**:
1. **Complexity reduction**: v0.8 infrastructure (vert.x GraphQL, MongoDB support, Spring Boot support) was designed for different requirements
2. **Maintenance burden**: Maintaining two parallel stacks is expensive
3. **Clear migration path**: Forced migration to new model ensures everyone benefits from improvements
4. **Adapter pattern**: v0.8 compatibility can be added LATER as thin adapters on top of new GraphQL API

**Plan for v0.8 compatibility** (future):
- Create adapter GraphQL schema that mimics v0.8 API
- Map v0.8 queries to v1.0 SmallRye GraphQL queries
- No changes to storage layer needed (already supports both models)

### Why Combine GraphQL + Service?

**Decision**: Single module for Quarkus service + GraphQL API.

**Reasoning**:
1. **No business logic**: Service layer was essentially pass-through to storage
2. **GraphQL IS the service**: SmallRye GraphQL handles HTTP/JSON/schema
3. **Simpler testing**: Test GraphQL API directly, not through service layer
4. **Fewer modules**: Reduces Maven complexity

### Why Remove "v1" Suffix?

**Decision**: Modules are named without version suffix.

**Reasoning**:
1. **This is THE version**: Not "v1" vs "v2", this is the current architecture
2. **Git provides versioning**: Tags and branches handle version history
3. **Future versions**: If architecture changes significantly, create new modules at that time
4. **Cleaner names**: `data-index-service` vs `data-index-service-v1`

---

## Next Steps

1. ✅ ~~Delete v0.8 modules~~
2. ✅ ~~Rename v1 modules (remove suffix)~~
3. ✅ ~~Fix split package warning~~
4. ✅ ~~Verify build and tests~~
5. ✅ ~~Update documentation~~
6. **Real workflow testing** - Test with actual Quarkus Flow runtime
7. **Filter/Sort/Pagination** - Implement GraphQL query features
8. **v0.8 adapter** - IF needed, create compatibility layer

---

## References

- **Parent POM**: `data-index/pom.xml` - Module definitions
- **Build Status**: All modules build successfully
- **Container Image**: `org.kie.kogito/data-index-service:999-SNAPSHOT`
- **GraphQL Endpoint**: `http://localhost:8080/graphql`
- **GraphQL UI**: `http://localhost:8080/graphql-ui`
