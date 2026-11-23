# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
See [docs/RELEASE.md](docs/RELEASE.md).

## [Unreleased]

### Added

- Vector (MODE 1 and MODE 2) can tail several workflow namespaces feeding one Data Index ([#86](https://github.com/kubesmarts/logic-apps/issues/86)).

### Changed

- **BREAKING** Vector env `WORKFLOW_NAMESPACE` → `WORKFLOW_NAMESPACES` (comma-separated), Helm value `vector.workflowNamespace` → `vector.workflowNamespaces` (list) ([#86](https://github.com/kubesmarts/logic-apps/issues/86)).

### Deprecated

### Removed

### Fixed

### Security

## [2.0.0-Alpha4] - 2026-11-20

First pre-release of Data Index for Open Workflow 1.0.0.

### Added

- GraphQL API (SmallRye GraphQL) for workflow instances and task executions ([#4](https://github.com/kubesmarts/logic-apps/pull/4), [#6](https://github.com/kubesmarts/logic-apps/pull/6)).
- MODE 1 (PostgreSQL): Vector writes Quarkus Flow events to raw tables, and data-index-service polls and normalizes them with JDBC batch UPSERTs ([#73](https://github.com/kubesmarts/logic-apps/pull/73), [#113](https://github.com/kubesmarts/logic-apps/pull/113), [ADR-0002](https://github.com/kubesmarts/logic-apps/pull/145)). Vector schema configurable via `POSTGRES_SCHEMA` ([#84](https://github.com/kubesmarts/logic-apps/pull/84)).
- MODE 2 (Elasticsearch): storage backend normalized by Elasticsearch Transforms with smart filtering and Prometheus transform metrics ([#10](https://github.com/kubesmarts/logic-apps/pull/10), [#11](https://github.com/kubesmarts/logic-apps/pull/11), [#12](https://github.com/kubesmarts/logic-apps/pull/12)).
- MODE 3 (Kafka): ingestion service consuming CloudEvents (binary and structured) in batches, dead-lettering failed records individually ([#25](https://github.com/kubesmarts/logic-apps/pull/25), [#33](https://github.com/kubesmarts/logic-apps/pull/33), [#56](https://github.com/kubesmarts/logic-apps/pull/56)).
- `db-migrator` app for flow-persistence, flow-quartz and Data Index migrations, published as `quay.io/kubesmarts/logic-db-migrator` ([#88](https://github.com/kubesmarts/logic-apps/pull/88), [#160](https://github.com/kubesmarts/logic-apps/pull/160)).
- Unified `Error` type for workflow instances and task executions ([#9](https://github.com/kubesmarts/logic-apps/pull/9)).
- Helm chart and E2E tests for all modes, plus a nightly E2E against Quarkus Flow SNAPSHOT ([#55](https://github.com/kubesmarts/logic-apps/pull/55), [#65](https://github.com/kubesmarts/logic-apps/pull/65), [#135](https://github.com/kubesmarts/logic-apps/pull/135), [#154](https://github.com/kubesmarts/logic-apps/pull/154)).
- Multi-arch image publishing to quay.io and release workflow with pre-release support ([#80](https://github.com/kubesmarts/logic-apps/pull/80), [#132](https://github.com/kubesmarts/logic-apps/pull/132), [#167](https://github.com/kubesmarts/logic-apps/pull/167)).

### Changed

- **BREAKING** Configuration prefix renamed: `data-index.*` → `logic.apps.data-index.*`, `DATA_INDEX_*` → `LOGIC_APPS_DATA_INDEX_*` ([#143](https://github.com/kubesmarts/logic-apps/pull/143)).
- **BREAKING** Images renamed: `quay.io/kubesmarts/data-index-service` → `quay.io/kubesmarts/logic-data-index`, `quay.io/kubesmarts/data-index-ingestion` → `quay.io/kubesmarts/logic-data-index-ingestion`. Tag suffixes are unchanged ([#159](https://github.com/kubesmarts/logic-apps/pull/159)).
- Field names aligned with the Open Workflow 1.0.0 spec (`startedAt`, `endedAt`, `task`, `input`, `output`). GraphQL filters still accept the old names ([#38](https://github.com/kubesmarts/logic-apps/pull/38)).
- Task instances keyed by `(instance_id, task)`; task ID is derived from it ([#21](https://github.com/kubesmarts/logic-apps/pull/21), [#36](https://github.com/kubesmarts/logic-apps/pull/36)).
- MODE 2 log collection moved from FluentBit to Vector ([#62](https://github.com/kubesmarts/logic-apps/pull/62)).
- JSON payloads passed to ingestion persistence as strings ([#153](https://github.com/kubesmarts/logic-apps/pull/153)).
- Quarkus 3.40.1.2, Quarkus Flow 1.4.0; data-index modules aligned with the Quarkus platform BOM ([#136](https://github.com/kubesmarts/logic-apps/pull/136), [#137](https://github.com/kubesmarts/logic-apps/pull/137), [#166](https://github.com/kubesmarts/logic-apps/pull/166)).
- Dependency updates managed by Renovate ([#60](https://github.com/kubesmarts/logic-apps/pull/60)).

### Removed

- Redundant `task_execution_id` column ([#36](https://github.com/kubesmarts/logic-apps/pull/36)).
- PostgreSQL normalization triggers, replaced by polling ([#113](https://github.com/kubesmarts/logic-apps/pull/113)).
- Deprecated and unrecognized Quarkus config keys ([#91](https://github.com/kubesmarts/logic-apps/pull/91)).

### Fixed

- PostgreSQL: invalid HQL path in `getWorkflowInstances` ([#14](https://github.com/kubesmarts/logic-apps/pull/14)).
- PostgreSQL: duplicate task executions, concurrency-unsafe task normalization and deadlocks under load ([#7](https://github.com/kubesmarts/logic-apps/pull/7), [#17](https://github.com/kubesmarts/logic-apps/pull/17), [#22](https://github.com/kubesmarts/logic-apps/pull/22)).
- Elasticsearch: dynamic mappings for `instance_id` and normalized timestamps in transforms ([#26](https://github.com/kubesmarts/logic-apps/pull/26), [#28](https://github.com/kubesmarts/logic-apps/pull/28)).
- Elasticsearch: transforms skipping raw events ([#141](https://github.com/kubesmarts/logic-apps/pull/141)).
- Transform metrics gauges re-registered on every poll ([#30](https://github.com/kubesmarts/logic-apps/pull/30)).
- Vector now waits for Elasticsearch to be ready ([#107](https://github.com/kubesmarts/logic-apps/pull/107)).

[Unreleased]: https://github.com/kubesmarts/logic-apps/compare/v2.0.0-Alpha4...HEAD
[2.0.0-Alpha4]: https://github.com/kubesmarts/logic-apps/commits/v2.0.0-Alpha4
