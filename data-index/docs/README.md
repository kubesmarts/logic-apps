# Data Index Documentation

**Last Updated**: 2026-04-16

---

## Overview

This directory contains all documentation for Data Index v1.0.0, a passive query service for Serverless Workflow 1.0.0 execution data.

**Core Principle**: Data Index does NOT own event infrastructure. FluentBit handles event pipeline, PostgreSQL owns merge logic, Data Index only queries.

---

## Core Documentation

| Document | Description | Status |
|----------|-------------|--------|
| [Architecture](architecture.md) | Complete architecture overview, design decisions, data flow diagrams | ✅ Current |
| [Current State](current-state.md) | What's done, what's next, test results | ✅ Current |
| [Database Schema](database-schema.md) | Complete schema + event-to-column mappings | ✅ Current |
| [Quarkus Flow Events](quarkus-flow-events.md) | Event structure reference from Quarkus Flow runtime | ✅ Current |
| [Event Ingestion Architecture](event-ingestion-architecture.md) | Out-of-order event handling analysis (4 approaches compared) | ✅ Current |
| [FluentBit Configuration](fluentbit-configuration.md) | FluentBit setup, testing, troubleshooting | ✅ Current |
| [Domain Model Design](domain-model-design.md) | Domain model reset decisions (SW 1.0.0 only, no v0.8 legacy) | ✅ Current |
| [GraphQL Testing Guide](graphql-testing.md) | How to test the GraphQL API with sample queries | ✅ Current |
| [Production Viability Analysis](production-viability-analysis.md) | Enterprise readiness assessment, limitations, alternatives | ✅ Current |
| [Ingestion Migration Strategy](ingestion-migration-strategy.md) | How to migrate FluentBit→Debezium→Kafka without changing Data Index | ✅ Current |

---

## Quick Navigation

### By Topic

**Architecture & Design**:
- [Architecture Overview](architecture.md) - Start here for complete system design
- [Design Decisions](#key-design-decisions) - Why we made specific architectural choices

**Database**:
- [Database Schema](database-schema.md) - Tables, columns, indexes, triggers
- [Event Mappings](database-schema.md#field-by-field-event-mapping) - How events map to database columns

**Events & Ingestion**:
- [Quarkus Flow Events](quarkus-flow-events.md) - Event structure and fields
- [Event Ingestion Architecture](event-ingestion-architecture.md) - How events flow into PostgreSQL
- [FluentBit Configuration](fluentbit-configuration.md) - Event pipeline setup

**Domain Model**:
- [Domain Model Design](domain-model-design.md) - WorkflowInstance, TaskExecution, Error spec
- [JPA Entities](domain-model-design.md#jpa-entities) - Entity classes and mappings

**Status & Planning**:
- [Current State](current-state.md) - What's done, what's next
- [Test Results](current-state.md#build-status) - Latest test results

### By Audience

**For Architects**:
1. [Architecture](architecture.md) - Complete system design
2. [Event Ingestion Architecture](event-ingestion-architecture.md) - Out-of-order event handling
3. [Design Decisions](#key-design-decisions) - Rationale for key choices

**For Developers**:
1. [Database Schema](database-schema.md) - Tables and triggers
2. [Domain Model Design](domain-model-design.md) - Java classes
3. [Quarkus Flow Events](quarkus-flow-events.md) - Event structure
4. [Current State](current-state.md) - What's implemented

**For DevOps/SRE**:
1. [FluentBit Configuration](fluentbit-configuration.md) - Event pipeline setup
2. [Database Schema](database-schema.md) - PostgreSQL schema deployment
3. [Architecture](architecture.md#architecture-diagram) - System components

---

## Key Design Decisions

### 1. Data Index is Passive (Query-Only)

**Decision**: Data Index does NOT handle events directly. It only queries PostgreSQL.

**Rationale**:
- ✅ No bottleneck: FluentBit handles event pipeline
- ✅ No failure points: Data Index can restart without losing events
- ✅ Separation of concerns: Event ingestion vs. querying are separate
- ✅ Scalability: Data Index scales independently of event volume

**See**: [Architecture - Data Index is Passive](architecture.md#1-data-index-is-passive-query-only)

### 2. PostgreSQL Triggers Handle Out-of-Order Events

**Decision**: Use PostgreSQL triggers with COALESCE-based UPSERT.

**Rationale**:
- ✅ Database-level logic (declarative, tested)
- ✅ Handles `completed` arriving before `started`
- ✅ No application code needed for merge logic

**See**: [Event Ingestion Architecture - Solution 4](event-ingestion-architecture.md#solution-4-application-level-ingestion-recommended)

### 3. Serverless Workflow 1.0.0 as Source of Truth

**Decision**: Domain model based ONLY on SW 1.0.0 spec + Quarkus Flow events.

**Rationale**:
- ✅ Clean break from Kogito legacy
- ✅ Every field traceable to specific event
- ✅ Forward-compatible with SW spec evolution

**See**: [Domain Model Design](domain-model-design.md)

### 4. Staging + Final Tables Pattern

**Decision**: FluentBit writes to staging tables, triggers merge into final tables.

**Rationale**:
- ✅ Staging tables preserve raw events (audit trail)
- ✅ Final tables optimized for queries
- ✅ Can reprocess events by replaying staging data

**See**: [Architecture - Staging + Final Tables](architecture.md#3-staging-tables--final-tables-pattern)

### 5. FluentBit Owns the Pipeline

**Decision**: FluentBit handles all event pipeline concerns.

**Rationale**:
- ✅ Battle-tested (production-grade log shipper)
- ✅ Built-in retry/buffering logic
- ✅ Pluggable outputs (can add Elasticsearch, etc.)

**See**: [Architecture - FluentBit Owns the Pipeline](architecture.md#5-fluentbit-owns-the-event-pipeline)

---

## Archive

Historical documentation from previous phases (v0.8 cleanup, phase-based planning) is in [archive/](archive/).

These docs are kept for historical context but are NOT accurate for v1.0.0:
- `phase-*.md` - Phase-based planning docs (replaced by current-state.md)
- `bpmn-entity-removal.md` - BPMN entity cleanup (completed)
- `jpa-schema-validation.md` - Old JPA validation approach
- `schema-testing-plan.md` - Superseded by FluentBit test approach
- `api-compatibility-v0.8.md` - Will be revisited after v1.0.0 GraphQL implemented

---

## Contributing

When adding documentation:
1. Place new docs in `/docs` (this directory)
2. Update this README with a link
3. Update [../README.md](../README.md) with a link
4. Follow naming convention: `lowercase-with-hyphens.md`
5. Move outdated docs to `/docs/archive`

---

## References

- **Main README**: [../README.md](../README.md)
- **Quarkus Flow**: [github.com/quarkiverse/quarkus-flow](https://github.com/quarkiverse/quarkus-flow)
- **Serverless Workflow Spec**: [serverlessworkflow.io](https://serverlessworkflow.io)
- **FluentBit**: [fluentbit.io](https://fluentbit.io)
