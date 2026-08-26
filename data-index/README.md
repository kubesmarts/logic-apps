# Data Index v1.0.0

**Query service for Open Workflow 1.0.0 execution data.**

**Status**: Production Ready (MODE 1, MODE 2 & MODE 3)

## Overview

Data Index provides a GraphQL API for querying workflow execution data from Quarkus Flow applications.

**Deployment Modes:**
- **MODE 1** (PostgreSQL + FluentBit + Triggers) - Production ready
- **MODE 2** (Elasticsearch + FluentBit + Transforms) - Production ready
- **MODE 3** (Kafka + SmallRye Reactive Messaging) - Production ready

## Quick Start

```bash
# Start with PostgreSQL backend (MODE 1)
cd data-index-service
mvn quarkus:dev -Dquarkus.profile=postgresql

# GraphQL UI: http://localhost:8080/graphql-ui
# Documentation: http://localhost:8080/docs
```

## Documentation

📚 **[Complete Documentation](data-index-docs/)** - Build with `mvn clean package`

**Key Resources:**
- [Getting Started](data-index-docs/modules/ROOT/pages/getting-started.adoc)
- [Architecture Overview](data-index-docs/modules/ROOT/pages/architecture/overview.adoc)
- [Deployment Guides](data-index-docs/modules/ROOT/pages/deployment/)
- [GraphQL API](data-index-docs/modules/ROOT/pages/api/)

For AI assistants working on this codebase, see [CLAUDE.md](../CLAUDE.md) for comprehensive guidelines.

## Project Structure

```
data-index/
├── data-index-docs/              # User-facing Antora documentation
├── data-index-model/             # Domain model
├── data-index-storage/           # Storage implementations (PostgreSQL, Elasticsearch)
├── data-index-service/           # Quarkus GraphQL service
├── data-index-ingestion/         # MODE 3 Kafka ingestion
├── data-index-integration-tests/ # E2E tests
├── workflow-test-app/            # Test workflow application
└── scripts/                      # Deployment scripts (KIND, FluentBit, Kafka)
```

## Build

```bash
mvn clean install
```

Requires Java 17 and Maven 3.9.11+.

## Configuration

See [Configuration Guide](data-index-docs/modules/ROOT/pages/developers/configuration.adoc) for all configuration options.

## License

Apache License 2.0
