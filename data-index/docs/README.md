# Data Index Documentation

**Version:** 1.0.0  
**Status:** Production Ready (MODE 1)  
**Last Updated:** 2026-04-24

## Overview

The Data Index is a read-only query service for Serverless Workflow (SW 1.0.0) execution data. It provides a GraphQL API for querying workflow instances and task executions, with multiple deployment modes optimized for different operational requirements.

**Key Features:**
- ✅ Real-time workflow execution visibility
- ✅ GraphQL API with filtering, sorting, pagination
- ✅ Multiple deployment modes (PostgreSQL, Elasticsearch)
- ✅ Trigger-based normalization (MODE 1)
- ✅ Idempotent event processing (handles replay)
- ✅ Quarkus Flow 0.9.0+ structured logging integration

## Quick Start

```bash
# 1. Review architecture
cat ARCHITECTURE-SUMMARY.md

# 2. Deploy MODE 1 (recommended)
cat deployment/MODE1_HANDOFF.md

# 3. Test end-to-end
cat deployment/MODE1_E2E_TESTING.md
```

## Documentation Structure

### 📋 Architecture (root level)
- **ARCHITECTURE-SUMMARY.md** - Overview of all deployment modes
- **ARCHITECTURE-CQRS-SEPARATION.md** - Command/Query separation design  

### 🚀 Deployment (`deployment/`)
- **MODE1_HANDOFF.md** - PostgreSQL trigger-based (production ready) ✅
- **MODE1_E2E_TESTING.md** - Complete testing guide
- **MODE1_ARCHITECTURE_UPDATE.md** - Migration from polling to triggers
- **MODE1_STDOUT_MIGRATION.md** - Log file migration notes
- **MODE2_IMPLEMENTATION_PLAN.md** - Elasticsearch (planned)
- **MODE3_IMPLEMENTATION_PLAN.md** - Kafka streaming (planned)

### ⚙️ Operations (`operations/`)
- **FLUENTBIT_PARSER_CONFIGURATION.md** - CRI vs Docker parser configuration
- **MODE1_EVENT_RELIABILITY.md** - Event loss mitigation strategies

### 💻 Development (`development/`)
- **GRAPHQL_API.md** - GraphQL API schema and queries
- **DATABASE_SCHEMA.md** - PostgreSQL schema with triggers
- **DOMAIN_MODEL.md** - Java domain model design
- **GRAPHQL_TESTING.md** - GraphQL integration tests

### 📚 Reference (`reference/`)
- **QUARKUS_FLOW_INTEGRATION.md** - Quarkus Flow structured logging
- **FLUENTBIT_ARCHITECTURE.md** - FluentBit log collection
- **EVENT_PROCESSOR_DESIGN.md** - Event processor architecture (legacy)

### 📝 Additional Documentation (root level)
- **jsonnode-scalar-analysis.md** - JSON data exposure in GraphQL
- **MULTI_TENANT_FLUENTBIT.md** - Multi-tenant FluentBit configuration
- **STAGING_TABLE_SCHEMA.md** - Staging table design (legacy)
- **GRAPHQL-FILTERING-*.md** - GraphQL filtering implementation notes

## Deployment Modes

| Mode | Status | Best For |
|------|--------|----------|
| **MODE 1** PostgreSQL Triggers | ✅ Production | All use cases (recommended) |
| **MODE 2** Elasticsearch | 📋 Planned | Advanced search, analytics |
| **MODE 3** Kafka Streaming | ⚠️ Not Implemented | Future: long-term replay (30+ days) |

**Notes:**
- MODE 1 is production-ready with full E2E testing
- MODE 2 simplified: FluentBit → Elasticsearch (no Kafka/Event Processor)
- MODE 3 removed from codebase (optional future feature)

See `deployment/MODE2_IMPLEMENTATION_PLAN.md` and `deployment/MODE3_IMPLEMENTATION_PLAN.md` for details.

## Getting Help

**Common Issues:**
1. Events not reaching PostgreSQL → `operations/FLUENTBIT_PARSER_CONFIGURATION.md`
2. Triggers not normalizing → `operations/TROUBLESHOOTING.md`  
3. GraphQL query errors → `development/GRAPHQL_API.md`

**Support:**
- GitHub Issues: [kubesmarts/logic-apps](https://github.com/kubesmarts/logic-apps/issues)
- Quarkus Flow: [quarkiverse/quarkus-flow](https://github.com/quarkiverse/quarkus-flow)
