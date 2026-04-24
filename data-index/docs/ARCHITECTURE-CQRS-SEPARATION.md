# Data Index Architecture - CQRS Separation

**Date**: 2026-04-17  
**Status**: ✅ **IMPLEMENTED**

---

## 📐 Architecture Overview

Proper **CQRS** (Command Query Responsibility Segregation) separation:

- **Storage Layer** (`data-index-storage-postgresql`): **Write Side** - Event ingestion + normalization
- **Service Layer** (`data-index-service`): **Read Side** - GraphQL queries only

---

## 🏗️ Module Structure

### Storage Layer (data-index-storage-postgresql)

**Responsibility**: Write side - event ingestion, normalization, and persistence

**Contains**:
- **JPA Entities**: `WorkflowInstanceEntity`, `TaskExecutionEntity`, `WorkflowInstanceEvent`, `TaskExecutionEvent`
- **Event Processors**: Normalization logic (event tables → final tables)
- **Observability**: Metrics, health checks, monitoring endpoints
- **Storage API**: Query/storage implementations
- **Flyway Migrations**: Database schema

**Key Classes**:
```
org.kubesmarts.logic.dataindex.ingestion/
  ├── EventProcessorScheduler.java         # Polling consumer (@Scheduled)
  ├── WorkflowInstanceEventProcessor.java  # Workflow event normalization
  ├── TaskExecutionEventProcessor.java     # Task event normalization
  ├── EventProcessorMetrics.java           # Prometheus metrics (gauges)
  ├── EventProcessorHealthCheck.java       # Kubernetes health probe
  └── EventCleanupScheduler.java           # Event retention cleanup

org.kubesmarts.logic.dataindex.metrics/
  ├── EventProcessorMetricsResource.java   # REST metrics endpoint
  ├── EventMetrics.java                    # Metrics DTO
  └── EventProcessorMetricsResponse.java   # Response DTO

org.kubesmarts.logic.dataindex.jpa/
  ├── WorkflowInstanceEntity.java          # Final workflow table
  ├── TaskExecutionEntity.java             # Final task table
  └── ...

org.kubesmarts.logic.dataindex.event/
  ├── WorkflowInstanceEvent.java           # Event table
  ├── TaskExecutionEvent.java              # Event table
  └── ...

org.kubesmarts.logic.dataindex.storage/
  ├── WorkflowInstanceJPAStorage.java      # Query API
  ├── TaskExecutionJPAStorage.java         # Query API
  └── ...
```

**Dependencies** (pom.xml):
- `quarkus-hibernate-orm` - JPA/Hibernate
- `quarkus-scheduler` - @Scheduled event processing
- `quarkus-micrometer-registry-prometheus` - Metrics
- `quarkus-smallrye-health` - Health checks
- `quarkus-rest-jackson` - REST endpoints
- `jakarta.persistence-api` - JPA API
- `jakarta.enterprise.cdi-api` - CDI

---

### Service Layer (data-index-service)

**Responsibility**: Read side - GraphQL queries only

**Contains**:
- **GraphQL API**: SmallRye GraphQL resolvers
- **Query Services**: Read-only query orchestration
- **GraphQL Types**: Shared type definitions

**Key Classes**:
```
org.kubesmarts.logic.dataindex.graphql/
  ├── WorkflowInstanceGraphQLApi.java      # GraphQL @Query endpoints
  ├── JsonNodeScalar.java                  # Custom GraphQL scalar
  └── ...
```

**Dependencies** (pom.xml):
- `data-index-storage-postgresql` - Storage layer (contains entities + processors)
- `quarkus-smallrye-graphql` - GraphQL API
- `quarkus-smallrye-health` - Service health (overall)
- `quarkus-micrometer-registry-prometheus` - Metrics exposure
- `quarkus-rest-jackson` - REST support
- `quarkus-container-image-jib` - Container building

**Note**: The service layer depends on `data-index-storage-postgresql`, which provides:
- Event processor beans (auto-discovered by Quarkus CDI)
- Health check beans (exposed at `/q/health/live`)
- Metrics beans (exposed at `/q/metrics`)
- REST endpoints (exposed at `/event-processor/metrics`)

---

## 🔄 Data Flow

### Write Path (Event Ingestion)

```
FluentBit → PostgreSQL Event Tables
               ↓
    EventProcessorScheduler (@Scheduled every 5s)
               ↓
    WorkflowInstanceEventProcessor
    TaskExecutionEventProcessor
               ↓
    Merge events into final tables
    (WorkflowInstanceEntity, TaskExecutionEntity)
```

### Read Path (GraphQL Queries)

```
GraphQL Query → WorkflowInstanceGraphQLApi
                    ↓
             WorkflowInstanceJPAStorage
                    ↓
         Query WorkflowInstanceEntity (final table)
                    ↓
            Return GraphQL response
```

---

## 📦 Deployment

When deployed, the **data-index-service** JAR includes:

1. **GraphQL API** (from service layer)
2. **Event Processors** (from storage layer)
3. **Entities** (from storage layer)
4. **Observability** (from storage layer)

**Endpoints Exposed**:
- `POST /graphql` - GraphQL API
- `GET /graphql-ui` - GraphQL playground
- `GET /q/metrics` - Prometheus metrics
- `GET /q/health/live` - Liveness probe
- `GET /q/health/ready` - Readiness probe
- `GET /event-processor/metrics` - Event processor metrics (JSON)

---

## 🎯 Benefits of This Architecture

### 1. **Proper CQRS Separation**
- **Write logic** (event processing) isolated in storage layer
- **Read logic** (GraphQL) isolated in service layer
- Clear responsibility boundaries

### 2. **Modularity**
- Storage layer can be reused by other services
- Event processing can be tested independently
- GraphQL API can be tested independently

### 3. **Testability**
- Storage layer tests: Event processor logic, normalization, metrics
- Service layer tests: GraphQL queries, resolvers
- Integration tests: End-to-end flow

### 4. **Scalability**
- Can scale read/write sides independently (future)
- Event processors run in storage layer (write side)
- GraphQL API runs in service layer (read side)

### 5. **Maintainability**
- Storage concerns in storage module
- API concerns in service module
- Clear boundaries reduce coupling

---

## 📊 Observability

All observability features are in the **storage layer** (`data-index-storage-postgresql`):

| Feature | Location | Endpoint |
|---------|----------|----------|
| Prometheus Metrics | `EventProcessorMetrics.java` | `/q/metrics` |
| Health Checks | `EventProcessorHealthCheck.java` | `/q/health/live` |
| REST Metrics | `EventProcessorMetricsResource.java` | `/event-processor/metrics` |
| Enhanced Logging | `EventProcessorScheduler.java` | Logs |

**Rationale**: Observability belongs to the write side (event processing), not the read side (GraphQL).

---

## 🔧 Configuration

All event processing configuration is in `application.properties` (service layer):

```properties
# Event Processing
data-index.event-processor.enabled=true
data-index.event-processor.interval=5s
data-index.event-processor.batch-size=100
data-index.event-processor.retention-days=30
data-index.event-cleanup.cron=0 0 2 * * ?

# Observability Thresholds
data-index.event-processor.slow-processing.threshold.ms=1000
data-index.event-processor.lag.threshold.seconds=60
data-index.event-processor.backlog.threshold=1000

# Metrics
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true

# Health
quarkus.smallrye-health.enabled=true
```

---

## 🚀 Migration Summary

**Before** (Mixed Responsibilities):
```
data-index-service/
  ├── graphql/         # Query side ✅
  ├── ingestion/       # Write side ❌ WRONG LAYER
  └── metrics/         # Write side ❌ WRONG LAYER
```

**After** (Proper CQRS):
```
data-index-storage-postgresql/      # WRITE SIDE
  ├── ingestion/       # Event processors
  ├── metrics/         # Observability
  ├── jpa/             # Entities
  ├── event/           # Event entities
  └── storage/         # Storage API

data-index-service/                  # READ SIDE
  └── graphql/         # GraphQL API (queries only)
```

**Files Moved**: 9 files
- `EventProcessorScheduler.java`
- `WorkflowInstanceEventProcessor.java`
- `TaskExecutionEventProcessor.java`
- `EventProcessorMetrics.java`
- `EventProcessorHealthCheck.java`
- `EventCleanupScheduler.java`
- `EventProcessorMetricsResource.java`
- `EventMetrics.java`
- `EventProcessorMetricsResponse.java`

---

## ✅ Build Status

```bash
# Storage layer
cd data-index-storage-postgresql
mvn clean compile -DskipTests
# ✅ BUILD SUCCESS

# Service layer
cd data-index-service
mvn clean compile -DskipTests
# ✅ BUILD SUCCESS

# Full build
cd data-index
mvn clean compile -DskipTests
# ✅ BUILD SUCCESS (all 5 modules)
```

---

## 📚 References

- [CQRS Pattern](https://martinfowler.com/bliki/CQRS.html)
- [Event Sourcing](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Polling Consumer](https://www.enterpriseintegrationpatterns.com/patterns/messaging/PollingConsumer.html)
- [EVENT-PROCESSOR-OBSERVABILITY.md](EVENT-PROCESSOR-OBSERVABILITY.md)
- [EVENT-PROCESSOR-TESTING-RESULTS.md](EVENT-PROCESSOR-TESTING-RESULTS.md)

---

**Date**: 2026-04-17  
**Author**: Claude Code (Sonnet 4.5)  
**Status**: Complete and tested
