<!---
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->
# Data Index v1.0.0

**Query service for Serverless Workflow 1.0.0 execution data.**

**Status**: ✅ Event Ingestion Architecture Complete - Ready for Real Workflow Testing  
**Verification**: ✅ Safe, Testable, Runnable - See [Verification Report](VERIFICATION-REPORT.md)

⚠️ **Production Readiness**: This architecture prioritizes **operational simplicity** over unlimited scalability. Suitable for small-medium production (< 1,000 workflows/sec). See **[Production Viability Analysis](docs/production-viability-analysis.md)** for limitations, risks, and enterprise considerations.

🏆 **Key Architectural Strength**: **Resilience Through Decoupling** - Data Index depends ONLY on PostgreSQL schema, NOT on ingestion mechanism. Can migrate FluentBit→Debezium→Kafka with **zero Data Index downtime, zero code changes**. See **[Ingestion Migration Strategy](docs/ingestion-migration-strategy.md)** for migration scenarios.

---

## Overview

Data Index v1.0.0 is a **passive, query-only service** that provides GraphQL API access to workflow execution data ingested by external systems.

**Core Principle**: Data Index does NOT own event infrastructure. FluentBit handles event pipeline (retries, buffering, failures), PostgreSQL owns merge logic (out-of-order events), and Data Index only queries.

### Architecture

```
Quarkus Flow Runtime
    ↓ (emits JSON logs)
FluentBit (owns: retries, buffering, failures)
    ↓ (INSERT into staging tables)
PostgreSQL Staging Tables (workflow_instance_events, task_execution_events)
    ↓ (triggers fire on INSERT)
PostgreSQL Triggers (merge logic with COALESCE for out-of-order events)
    ↓ (UPSERT into final tables)
PostgreSQL Final Tables (workflow_instances, task_executions)
    ↓ (reads via JPA)
Data Index GraphQL API (passive, query-only)
```

**Key Changes from v0.8**:
- ✅ No event processing - read-only query service
- ✅ No Kafka dependencies - logs as transport via FluentBit
- ✅ PostgreSQL-only storage (MongoDB, Infinispan removed)
- ✅ Serverless Workflow 1.0.0 domain model (no legacy BPMN concepts)
- ✅ Out-of-order event handling via PostgreSQL triggers
- 🏆 **Swappable ingestion pipeline** - can migrate FluentBit→Debezium→Kafka with zero Data Index changes

📖 **[Read Full Architecture →](docs/architecture.md)**

---

## Quick Start

### Test GraphQL v1.0.0 API

```bash
# 1. Start PostgreSQL with test data
cd fluent-bit
docker-compose -f docker-compose-triggers.yml up -d
cd ..
docker-compose -f fluent-bit/docker-compose-triggers.yml exec -T postgres \
  psql -U postgres -d dataindex -f - < scripts/test-data-v1.sql

# 2. Start Data Index service
mvn quarkus:dev -pl data-index-service

# 3. Open GraphQL UI
# http://localhost:8080/graphql-ui
```

**Test Query**:
```graphql
{
  getWorkflowInstances {
    id
    name
    status
    startDate
  }
}
```

See **[GraphQL Testing Guide](docs/graphql-testing.md)** for complete testing guide.

### Test FluentBit Event Ingestion

```bash
cd fluent-bit
./test-triggers.sh
```

**Expected Output**:
```
Workflow instances:
uuid-1234 | default | order-processing | COMPLETED | 2026-04-15 15:30:00 | 2026-04-15 15:30:30
uuid-5678 | default | order-processing | FAULTED   | 2026-04-15 15:31:00 | 2026-04-15 15:31:15
```

### Build

```bash
mvn clean compile -DskipTests
```

### Deploy Database Schema

```bash
# Create database
createdb -U postgres dataindex

# Deploy schema with triggers
psql -U postgres -d dataindex -f scripts/schema-with-triggers-v2.sql
```

---

## Documentation

### Core Documentation

| Document | Description |
|----------|-------------|
| **[Architecture](docs/architecture.md)** | 📐 Complete architecture overview and design decisions |
| **[Current State](docs/current-state.md)** | 📊 What's done, what's next, testing status |
| **[Database Schema](docs/database-schema.md)** | 🗄️ Complete schema + event-to-column mappings |
| **[Quarkus Flow Events](docs/quarkus-flow-events.md)** | 📡 Event structure reference from Quarkus Flow runtime |
| **[Event Ingestion Architecture](docs/event-ingestion-architecture.md)** | 🔄 Out-of-order event handling analysis |
| **[FluentBit Configuration](docs/fluentbit-configuration.md)** | ⚙️ FluentBit setup, testing, and troubleshooting |
| **[Domain Model Design](docs/domain-model-design.md)** | 🏗️ Domain model reset decisions (SW 1.0.0 only) |

### Archive

Historical documentation from previous phases: [docs/archive/](docs/archive/)

---

## Domain Model

**Package**: `org.kubesmarts.logic.dataindex.model`

**Classes**:
- `WorkflowInstance` - 13 fields (all from Quarkus Flow events)
- `WorkflowInstanceStatus` - Enum: RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
- `WorkflowInstanceError` - SW 1.0.0 Error spec (type, title, detail, status, instance)
- `TaskExecution` - 7 fields (all from Quarkus Flow events)
- `Workflow` - TBD (will iterate with operator)

**Design Principle**: Every field maps directly to Quarkus Flow structured logging events. No legacy v0.8 BPMN concepts (workflowId, processId, nodes, state integers).

📖 **[Read Domain Model Design →](docs/domain-model-design.md)**

---

## Database Schema

### Final Tables (Query Target)

**workflow_instances** (14 columns):
- Identity: id, namespace, name, version
- Lifecycle: status, start, end, last_update
- Data: input (JSONB), output (JSONB)
- Error: error_type, error_title, error_detail, error_status, error_instance

**task_executions** (9 columns):
- Identity: id, workflow_instance_id (FK)
- Task: task_name, task_position (JSONPointer: "/do/0")
- Lifecycle: enter, exit, error_message
- Data: input_args (JSONB), output_args (JSONB)

### Staging Tables (Event Ingestion)

**workflow_instance_events** (FluentBit format):
- tag VARCHAR
- time TIMESTAMP
- data JSONB (complete Quarkus Flow event)

**task_execution_events** (FluentBit format):
- tag VARCHAR
- time TIMESTAMP
- data JSONB (complete Quarkus Flow event)

### Triggers

- `workflow_instance_event_trigger` - Merges events into workflow_instances
- `task_execution_event_trigger` - Merges events into task_executions

**Out-of-Order Handling**: Triggers use UPSERT + COALESCE to preserve existing values, correctly handling cases where `workflow.instance.completed` arrives before `workflow.instance.started`.

📖 **[Read Database Schema →](docs/database-schema.md)**

---

## Event Ingestion

FluentBit tails Quarkus Flow JSON logs and ingests events into PostgreSQL staging tables. PostgreSQL triggers automatically merge events into final tables.

**Supported Events**:
- `workflow.instance.started` → Creates workflow instance
- `workflow.instance.completed` → Updates end time, output, status
- `workflow.instance.faulted` → Updates end time, error, status
- `workflow.instance.cancelled` → Updates end time, status
- `workflow.instance.suspended` → Updates status
- `workflow.instance.resumed` → Updates status
- `workflow.instance.status.changed` → Updates status, last_update
- `workflow.task.started` → Creates task execution
- `workflow.task.completed` → Updates exit time, output
- `workflow.task.faulted` → Updates exit time, error message

📖 **[Read Event Ingestion Architecture →](docs/event-ingestion-architecture.md)**  
📖 **[Read FluentBit Configuration →](docs/fluentbit-configuration.md)**

---

## Testing

### FluentBit Ingestion Test

```bash
cd fluent-bit
./test-triggers.sh
```

**Test Coverage**:
- ✅ FluentBit parsing of Quarkus Flow JSON events
- ✅ Event filtering (workflow.*, task.*)
- ✅ Insertion into staging tables
- ✅ Trigger-based merging into final tables
- ✅ Out-of-order event handling (COALESCE logic)
- ✅ Successful workflow scenario (uuid-1234)
- ✅ Failed workflow scenario (uuid-5678)

**Test Results** (2026-04-15):

**Workflow Instances**:
```
uuid-1234 | default | order-processing | COMPLETED | 2026-04-15 15:30:00 | 2026-04-15 15:30:30
uuid-5678 | default | order-processing | FAULTED   | 2026-04-15 15:31:00 | 2026-04-15 15:31:15
```

**Task Executions**:
```
task-uuid-1 | uuid-1234 | callPaymentService | /do/0 | 15:30:05 | 15:30:08 | (no error)
task-uuid-2 | uuid-5678 | callPaymentService | /do/0 | 15:31:05 | 15:31:07 | Connection timeout
```

**Architecture Verified**:
- ✓ FluentBit owns event pipeline
- ✓ PostgreSQL owns merge logic
- ✓ Data Index is passive (query-only)
- ✓ Out-of-order events handled correctly

---

## Project Structure

```
data-index/
├── docs/                          # 📚 Documentation
│   ├── architecture.md            # Complete architecture overview
│   ├── current-state.md           # Current status and next steps
│   ├── database-schema.md         # Database schema + event mappings
│   ├── quarkus-flow-events.md     # Quarkus Flow event structure
│   ├── event-ingestion-architecture.md  # Out-of-order event handling
│   ├── fluentbit-configuration.md # FluentBit setup and testing
│   ├── domain-model-design.md     # Domain model decisions
│   └── archive/                   # Historical documentation
│
├── fluent-bit/                    # ⚙️ FluentBit Configuration
│   ├── fluent-bit-triggers.conf   # Main configuration
│   ├── parsers.conf               # JSON parser
│   ├── docker-compose-triggers.yml # Test environment (PostgreSQL + FluentBit)
│   ├── test-triggers.sh           # Automated test script
│   └── sample-events.jsonl        # Test data (8 events, 2 workflows)
│
├── scripts/                       # 🗄️ Database Scripts
│   ├── schema-with-triggers-v2.sql # Complete schema with triggers (main schema)
│   ├── test-data-v1.sql           # Test data for GraphQL API
│   └── create-triggers.sql        # Create triggers on existing tables
│
├── data-index-model/              # 📦 Domain Model
│   ├── org.kubesmarts.logic.dataindex.model/  # Domain entities
│   │   ├── WorkflowInstance.java
│   │   ├── WorkflowInstanceStatus.java
│   │   ├── WorkflowInstanceError.java
│   │   ├── TaskExecution.java
│   │   └── Workflow.java
│   └── org.kubesmarts.logic.dataindex.api/   # Storage interfaces
│       ├── WorkflowInstanceStorage.java
│       └── TaskExecutionStorage.java
│
├── data-index-storage-postgresql/ # 💾 PostgreSQL Storage Implementation
│   ├── org.kubesmarts.logic.dataindex.jpa/   # JPA Entities
│   │   ├── WorkflowInstanceEntity.java
│   │   ├── TaskExecutionEntity.java
│   │   ├── WorkflowInstanceErrorEntity.java
│   │   └── AbstractEntity.java
│   ├── org.kubesmarts.logic.dataindex.mapper/ # MapStruct Mappers
│   │   ├── WorkflowInstanceEntityMapper.java
│   │   ├── TaskExecutionEntityMapper.java
│   │   └── WorkflowInstanceErrorEntityMapper.java
│   ├── org.kubesmarts.logic.dataindex.storage/ # JPA Storage
│   │   ├── WorkflowInstanceJPAStorage.java
│   │   ├── TaskExecutionJPAStorage.java
│   │   ├── AbstractStorage.java
│   │   └── AbstractJPAStorageFetcher.java
│   ├── org.kubesmarts.logic.dataindex.postgresql/ # PostgreSQL-specific
│   │   ├── PostgresqlJsonPredicateBuilder.java
│   │   ├── PostgresqlStorageServiceCapabilities.java
│   │   ├── ContainsSQLFunction.java
│   │   ├── CustomFunctionsContributor.java
│   │   └── JsonBinaryConverter.java
│   └── org.kubesmarts.logic.dataindex.json/  # JSON utilities
│       └── JsonUtils.java
│
├── data-index-service/            # 🚀 Quarkus Service + GraphQL API
│   ├── org.kubesmarts.logic.dataindex.graphql/ # SmallRye GraphQL API
│   │   ├── WorkflowInstanceGraphQLApi.java
│   │   └── JsonNodeScalar.java
│   └── src/main/resources/
│       └── application.properties
│
├── ARCHITECTURE-REORGANIZATION.md # Architecture reorganization guide
├── TEST-GRAPHQL-V1.md             # GraphQL testing guide
├── GRAPHQL-V1-SETUP.md            # GraphQL setup guide
└── README.md                      # This file
```

---

## What's Done

### ✅ Domain Model (Event-Driven)
- 5 domain model classes
- All fields map to Quarkus Flow events
- No legacy v0.8 BPMN concepts

### ✅ JPA Entities
- 3 JPA entities aligned with domain model
- WorkflowInstanceEntity, TaskExecutionEntity, WorkflowInstanceErrorEntity
- Entities extend AbstractEntity for storage layer integration

### ✅ Database Schema
- 2 final tables (workflow_instances, task_executions)
- 2 staging tables (workflow_instance_events, task_execution_events)
- PostgreSQL triggers for event merging
- Handles out-of-order events via COALESCE

### ✅ FluentBit Configuration
- JSON parsing tested ✅
- Event routing configured ✅
- PostgreSQL output configured ✅
- Test environment with Docker Compose ✅
- Automated test script ✅

### ✅ Event Ingestion Architecture
- FluentBit → PostgreSQL staging → Triggers → Final tables
- Out-of-order event handling proven
- Test results verified with sample workflows

### ✅ Storage Layer
- **data-index-model**: Domain model classes + storage API interfaces
- **data-index-storage-postgresql**: Complete PostgreSQL implementation with JPA
- MapStruct mappers for Entity ↔ Domain Model conversion
- WorkflowInstanceJPAStorage and TaskExecutionJPAStorage
- Storage interfaces in `org.kubesmarts.logic.dataindex.api` (no split packages)
- PostgreSQL-specific query builders and JSON handling

### ✅ GraphQL API - Fully Operational
- **data-index-service**: Quarkus service with SmallRye GraphQL
- Modern code-first GraphQL approach (no schema-first)
- Query operations: getWorkflowInstance, getWorkflowInstances, getTaskExecutions
- Test data available (scripts/test-data-v1.sql)
- GraphQL UI enabled at /graphql-ui
- **Status**: Working and tested with real queries ✅
- See **[TEST-GRAPHQL-V1.md](TEST-GRAPHQL-V1.md)** for testing guide

### ✅ Test Data
- 4 workflow instances (COMPLETED, FAULTED, RUNNING, CANCELLED)
- 7 task executions with various scenarios
- SQL script for loading mocked data
- See **[GRAPHQL-V1-SETUP.md](GRAPHQL-V1-SETUP.md)** for testing guide

---

## What's Next

1. ✅ ~~Integrate GraphQL API~~ - **DONE**: SmallRye GraphQL fully operational
2. ✅ ~~Test GraphQL Queries~~ - **DONE**: Tested with multiple query scenarios
3. **Implement Filter/Sort/Pagination** - Add query filtering and pagination support
4. **Real Workflow Testing** - Run Quarkus Flow workflows to verify end-to-end event ingestion
5. **v0.8 Adapters** - Legacy API compatibility (AFTER v1.0.0 proven with real workflows)

📖 **[Read Current State →](docs/current-state.md)**

---

## Key Design Decisions

### 1. Data Index is Passive (Query-Only)
Data Index does NOT own event infrastructure. FluentBit handles retries, buffering, failures. Data Index only queries PostgreSQL.

**Why**: Avoids bottleneck, single point of failure, and operational complexity.

### 2. PostgreSQL Triggers Handle Out-of-Order Events
Database-level logic using UPSERT + COALESCE ensures correct merging even when events arrive out of order.

**Why**: Declarative, tested, no application code needed for merge logic.

### 3. Serverless Workflow 1.0.0 as Source of Truth
Domain model based ONLY on SW 1.0.0 spec + Quarkus Flow events.

**Why**: Clean break from Kogito legacy, forward-compatible with SW spec evolution.

### 4. Staging + Final Tables Pattern
FluentBit writes to staging tables (immutable log), triggers merge into final tables (queryable state).

**Why**: Audit trail, debugging, reprocessing capability.

### 5. FluentBit Owns the Pipeline
FluentBit handles all event pipeline concerns: tailing, parsing, filtering, routing, retries, buffering, backpressure.

**Why**: Battle-tested, production-grade, pluggable outputs.

📖 **[Read Full Architecture →](docs/architecture.md)**

---

## Configuration

### Database Connection

```properties
# PostgreSQL connection
quarkus.datasource.db-kind=postgresql
quarkus.datasource.jdbc.url=jdbc:postgresql://localhost:5432/dataindex
quarkus.datasource.username=postgres
quarkus.datasource.password=${DB_PASSWORD}

# Schema management (production: validate only)
quarkus.hibernate-orm.database.generation=validate
```

### GraphQL

```properties
# Enable GraphQL UI
quarkus.smallrye-graphql.ui.enable=true

# Enable introspection
quarkus.smallrye-graphql.schema-include-introspection-types=true
```

---

## Contributing

### Documentation Guidelines

1. Place all documentation in `/docs` directory
2. Update this README with links to new docs
3. Follow naming convention: `lowercase-with-hyphens.md`
4. Move outdated docs to `/docs/archive`
5. Keep current, accurate docs in `/docs` root

### Schema Evolution

See JPA entity validation guidelines (TODO: create after MapStruct mappers).

---

## Migration from v0.8

### What Changed

1. **No event processing**: Data Index no longer consumes Kafka events
2. **Logs as transport**: FluentBit parses JSON logs and writes to PostgreSQL
3. **Standalone only**: Embedded/compact mode removed
4. **PostgreSQL only**: MongoDB and Infinispan storage removed
5. **SW 1.0.0 domain model**: No legacy BPMN concepts (workflowId, processId, nodes, state integers)
6. **Trigger-based ingestion**: Out-of-order events handled by PostgreSQL triggers

### Migration Steps

1. Deploy PostgreSQL schema: `scripts/schema-with-triggers-v2.sql`
2. Configure FluentBit to parse Quarkus Flow JSON logs
3. Remove Kafka dependencies from workflow runtime
4. Update GraphQL clients to new endpoint (schema TBD - will maintain v0.8 compatibility via adapters)

---

## References

- **Planning Repository**: [logic-v2-planning](../logic-v2-planning/)
- **Quarkus Flow**: [quarkiverse/quarkus-flow](https://github.com/quarkiverse/quarkus-flow)
- **Serverless Workflow**: [serverlessworkflow.io](https://serverlessworkflow.io)
- **FluentBit**: [fluentbit.io](https://fluentbit.io)

---

## License

Apache License 2.0
