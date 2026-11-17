# Graph Report - logic-apps  (2026-11-17)

## Corpus Check
- 279 files · ~135,652 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 55 file(s) not represented in the graph (top: .adoc 21, .properties 13, (none) 10)

## Summary
- 3191 nodes · 6314 edges · 288 communities (138 shown, 150 thin omitted)
- Extraction: 88% EXTRACTED · 12% INFERRED · 0% AMBIGUOUS · INFERRED: 763 edges (avg confidence: 0.87)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `a50a8ce0`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- steps
- OrderBy
- org.junit.jupiter.api.Test
- KafkaLifecycleConsumer
- EventMetrics
- Data Index Ingestion Service
- BaseWorkflowLifecycleIT
- ElasticsearchSchemaInitializerTest
- Workflow
- WorkflowInstance
- TaskExecution
- StringFilter
- Data Index E2E Tests
- jakarta.enterprise.context.ApplicationScoped
- io.quarkus.test.junit.QuarkusTestProfile
- MODE 1 (PostgreSQL)
- FilterCondition
- Query
- .convert
- WorkflowInstanceStatus
- AttributeFilter
- WorkflowInstanceEntity
- com.fasterxml.jackson.databind.JsonNode
- DataIndexE2ETest
- jakarta.persistence.criteria.CriteriaBuilder
- Claude AI Assistant Guidelines - KubeSmarts Logic Apps
- .createWorkflowDefinition
- Dependencies
- Error
- TransformFieldMappingTest
- steps
- DateTimeFilter
- TaskInstanceEntity
- full-test-mode1.sh
- full-test-mode2.sh
- full-test-mode3.sh
- BucketEnumDeserializer.java
- ElasticsearchConfiguration
- ErrorEntity
- WorkflowInstanceEntityMapper
- WorkflowInstanceGraphQLApiTest
- IntFilter
- ContainsSQLFunction.java
- generate-slides.js
- /tmp/quarkus-flow-events.log
- Code Style & Conventions
- Important Design Decisions
- verify-infrastructure.sh
- Namespace Helm Template
- KafkaLifecycleConsumer.java
- AttributeSort
- common-setup.sh
- generate-configmap.sh
- EventProcessorHealthCheck.java
- jakarta.persistence.EntityManager
- JPAQuery
- data-index-docs/package.json
- TaskInstanceEntity.java
- Storage Backend Architecture (Maven + Quarkus Profiles)
- io.quarkus.test.junit.QuarkusTest
- T2: Replay via FluentBit
- ElasticsearchSchemaInitializer.java
- VectorConfigValidationIT
- StorageServiceCapability
- Data Index POC Presentation
- PostgreSQL Normalized Tables
- Elasticsearch Normalized Indices
- HttpBinMockServer.java
- Storage
- MODE 1 Scaling
- kafka/service/RootResource.java
- report-failure
- FluentBit DaemonSet
- Persistence Abstraction Layer
- Kafka Helm Template
- description
- Red Hat EFK Stack Integration
- Common Tasks
- TestModel
- ElasticsearchSchemaInitializer
- FluentBit Configurations Overview
- data-index/collectors
- KIND Cluster Configuration
- PostgreSQL Helm Template
- Kafka Kubernetes Manifest
- setup-es-transform.sh
- ElasticsearchStorageIntegrationTest
- java.sql.Connection
- Architectural Decision Framework
- Documentation Index
- ElasticsearchTransformIntegrationTest
- Helm Chart Documentation
- Default Helm Values
- Vector ConfigMap Template
- MODE 1 Idempotency Diagram
- ElasticsearchWorkflowInstanceStorageIT
- steps
- values-mode3.yaml
- values-mode1.yaml
- values-mode2.yaml
- steps
- antora-playbook.yaml
- workflowApp
- env
- org.kubesmarts.logic.apps:data-index-model
- ADR-0002: Replace Trigger-Based Normalization with JDBC Batch Normalization (MODE 1)
- labels
- labels
- JdbcBatchPollingTest
- steps
- .toWorkflowInstance
- io.quarkiverse.flow.Flow
- limits
- co.elastic.clients.elasticsearch.ElasticsearchClient
- release
- github.com/kubesmarts/logic-apps/data-index/collectors
- org.kie.kogito:persistence-commons
- org.kubesmarts:logic-apps
- org.kubesmarts.logic.apps:data-index
- ports
- pr-title.yml
- TaskExecutionStorageIT
- Reference Examples
- TestStatus
- MigrationStreams
- com.fasterxml.jackson.databind.ObjectMapper
- Mapper
- VectorConfigValidationIT.java
- jakarta.ws.rs.Path
- data-index/CLAUDE.md
- labels
- spec
- spec
- Chart.yaml
- dataIndex
- jobs
- mode1-postgresql/daemonset.yaml
- ClusterRoleBinding data-index-vector-mode1
- mode2-elasticsearch/daemonset.yaml
- ClusterRoleBinding data-index-vector-mode2
- antora.yml
- vector-mode2-elasticsearch.yaml
- transforms
- deploy-postgresql
- containers
- selector
- containers
- selector
- ElasticsearchSmartFilteringIT
- sinks
- Service data-index-vector-mode1
- Service data-index-vector-mode2
- jakarta.transaction.Transactional
- transforms
- debug_filter
- route_by_type
- elasticsearch_task
- route_by_type
- values.yaml
- pullPolicy
- postgres_workflow
- elasticsearch_workflow
- extraPortMappings
- nodePort
- containers
- spec
- Release Process
- release.yml
- RELEASE.md
- ElasticsearchTransformPerformanceBenchmarkIT
- postgres_task
- kubernetes_logs
- kubernetes_logs
- postgresql
- kafka.yaml
- with
- steps
- publish
- [Unreleased]
- filter_workflow_events
- kafka
- setup
- version
- What NOT to Do
- annotations
- securityContext
- annotations
- securityContext
- PollingEventProcessor
- request
- persistence
- volumeClaimTemplates
- Build & Deployment
- JdbcBatchPollingDisabledTest
- debug_stdout
- deploy-elasticsearch
- deploy-kafka
- Troubleshooting
- Current Status & Next Steps
- QuarkusFlowLifecycleIT
- build_task_row
- build_workflow_row
- bulk
- build-elasticsearch
- build-postgresql
- summary
- Testing Approach
- Architecture (MODE 2 - Elasticsearch)
- Data Index Service
- Storage Common Module
- Elasticsearch Storage Implementation
- Flyway Database Migrations
- PostgreSQL Storage Implementation
- MODE 3 Kafka Deployment Guide
- Elasticsearch Transform Optimization
- Data Index Helm Chart
- Data Index Ingestion Template
- Data Index Service Template
- Elasticsearch Template
- MODE 1 Helm Values
- MODE 2 Helm Values
- MODE 3 Helm Values
- Vector DaemonSet Template
- Workflow Test App Template
- Kafka Scripts - MODE 3
- Workflow Test App Documentation
- Kafka Headless Service
- Kafka StatefulSet - KRaft Mode
- PostgreSQL StatefulSet
- Vector DaemonSet - MODE 2
- Workflow Test App Deployment
- Kafka KRaft Single-Node Deployment
- MODE 1 (PostgreSQL + FluentBit + Triggers)
- MODE 2 (Elasticsearch + Vector + Transforms)
- MODE 3 (Kafka + Ingestion Service + PostgreSQL)
- FluentBit DaemonSet
- Fluentd DaemonSet
- GraphQL API (Data Index)
- PostgreSQL / Elasticsearch
- Red Hat EFK Elasticsearch
- Title Slide - Data Index POC
- Migration Context Slide
- Event Flow Architecture Slide
- MODE 1 Architecture Slide
- MODE 1 Trigger Logic Slide
- MODE 2 Architecture Slide
- MODE 2 Transform Logic Slide
- Presentations Directory Documentation
- FluentBit MODE 1 - PostgreSQL
- FluentBit MODE 2 - Elasticsearch
- HelloWorldWorkflow - Test Workflow
- flow-lifecycle-out Topic
- Logging Namespace
- Workflows Namespace
- MODE 3 - Kafka Ingestion Architecture
- MODE 3 Kafka Maven Profile
- Batch Processing
- Field-Level Idempotency
- Smart Filtering
- Transform-Based Normalization
- Trigger-Based Normalization
- org.kubesmarts.logic.apps:data-index-collectors
- org.kubesmarts.logic.apps:data-index-ingestion
- org.kubesmarts.logic.apps:data-index-integration-tests
- org.kubesmarts.logic.apps:data-index-storage
- Presentation Slides Index
- Quarkus Flow Structured Logging
- SimpleSetWorkflow - Test Workflow
- CloudEvents
- Elasticsearch
- Elasticsearch Transforms
- Flyway
- Grafana
- Helm
- Index Lifecycle Management (ILM)
- JPA/Hibernate
- Apache Kafka
- KIND (Kubernetes in Docker)
- PostgreSQL
- Prometheus

## God Nodes (most connected - your core abstractions)
1. `WorkflowInstance` - 82 edges
2. `TaskExecution` - 80 edges
3. `AttributeFilter` - 59 edges
4. `TaskInstanceEntity` - 57 edges
5. `WorkflowInstanceEntity` - 57 edges
6. `WorkflowInstanceStatus` - 38 edges
7. `OrderBy` - 33 edges
8. `StringFilter` - 33 edges
9. `ElasticsearchTaskExecutionStorageIT` - 32 edges
10. `Workflow` - 31 edges

## Surprising Connections (you probably didn't know these)
- `Task-before-workflow ordering` --references--> `JdbcBatchPollingTest`  [INFERRED]
  adrs/0002-jdbc-batch-normalization-mode1.md → data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/ingestion/JdbcBatchPollingTest.java
- `GraphQL` --references--> `FilterConverter`  [INFERRED]
  CLAUDE.md → data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/FilterConverter.java
- `Elasticsearch Schema Management` --references--> `ElasticsearchSchemaInitializer`  [INFERRED]
  CLAUDE.md → data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/schema/ElasticsearchSchemaInitializer.java
- `❌ Code` --references--> `ErrorEntity`  [INFERRED]
  CLAUDE.md → data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/jpa/entity/ErrorEntity.java
- `Build Issues` --references--> `TaskInstanceEntity`  [INFERRED]
  CLAUDE.md → data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/jpa/entity/TaskInstanceEntity.java

## Import Cycles
- None detected.

## Hyperedges (group relationships)
- **mode1_idempotency_event_merge** —  [INFERRED]
- **MODE 1 Data Flow Pipeline** — docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_fluentbit, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_raw_tables, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_trigger, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_normalized_tables, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_graphql_api [INFERRED]
- **Current FluentBit Stack** — docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_fluentbit_daemonset, docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_postgresql_elasticsearch, docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_graphql_api_current [INFERRED]
- **** —  [INFERRED]
- **Normalized Data Storage** — docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_normalized_tables, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_workflow_instances, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_task_instances [INFERRED]
- **Raw Event Storage** — docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_raw_tables, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_workflow_events_raw, docs_presentations_2026_05_20_data_index_poc_diagrams_mode1_architecture_task_events_raw [INFERRED]
- **Red Hat EFK Stack** — docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_fluentd_daemonset, docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_redhat_efk_elasticsearch, docs_presentations_2026_05_20_data_index_poc_diagrams_redhat_productization_graphql_api_current [INFERRED]
- **mode1_field_level_idempotency_rules** —  [INFERRED]
- **Capacity Threshold Comparison** — docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode1_capacity, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode2_capacity, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode1_scaling, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode2_scaling [INFERRED]
- **Scaling Strategy Comparison** — docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode1_scaling, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_mode2_scaling, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_vertical_scaling, docs_presentations_2026_05_20_data_index_poc_diagrams_mode_comparison_horizontal_scaling [INFERRED]
- **implements** —  [INFERRED]
- **implements** —  [INFERRED]
- **implements** —  [INFERRED]
- **monitors** —  [INFERRED]

## Communities (288 total, 150 thin omitted)

### Community 0 - "steps"
Cohesion: 0.04
Nodes (47): default, description, required, type, description, required, type, name (+39 more)

### Community 1 - "OrderBy"
Cohesion: 0.13
Nodes (6): OrderBy, ASC, DESC, OrderByConverter, TaskExecutionOrderBy, WorkflowInstanceOrderBy

### Community 2 - "org.junit.jupiter.api.Test"
Cohesion: 0.08
Nodes (3): BucketEnumDeserializerTest, BucketStringDeserializerTest, ElasticsearchSchemaInitializationIT

### Community 6 - "BaseWorkflowLifecycleIT"
Cohesion: 0.10
Nodes (5): BaseWorkflowLifecycleIT, CancelledWorkflowIT, FaultedWorkflowIT, KafkaIngestionIT, SuspendedWorkflowIT

### Community 10 - "TaskExecution"
Cohesion: 0.06
Nodes (6): TaskPersistence, TaskExecution, TaskEventRowMapper, ElasticsearchTaskExecutionStorage, ElasticsearchTaskExecutionStorageIT, TaskExecutionJPAStorage

### Community 12 - "Data Index E2E Tests"
Cohesion: 0.07
Nodes (30): Antora, CloudEvents, Data Index, data-index-docs, data-index-model, data-index-service, Main, Data Index E2E Tests (+22 more)

### Community 13 - "jakarta.enterprise.context.ApplicationScoped"
Cohesion: 0.10
Nodes (7): EventProcessor, LoadSQL, WorkflowPersistence, TaskExecutionProcessor, WorkflowEventProcessor, GraphQLConfiguration, EventProcessorMetrics

### Community 14 - "io.quarkus.test.junit.QuarkusTestProfile"
Cohesion: 0.07
Nodes (11): Profile, PollingDisabled, ElasticsearchSchemaTestProfile, CustomTimeWindowProfile, ElasticsearchConfigurationValidationIT, InvalidRetentionProfile, InvalidTimeWindowProfile, TimeWindowExceedsRetentionProfile (+3 more)

### Community 15 - "MODE 1 (PostgreSQL)"
Cohesion: 0.08
Nodes (29): Data Consistency Models, GraphQL API, Latency Characteristics, MODE 1 (PostgreSQL), MODE 2 (Elasticsearch), Scaling Strategy, Data Index Service, Elasticsearch 8.11.1 (+21 more)

### Community 16 - "FilterCondition"
Cohesion: 0.08
Nodes (19): DataIndexAttributeFilter, TestAttributeFilter, FilterCondition, AND, BETWEEN, CONTAINS, CONTAINS_ALL, CONTAINS_ANY (+11 more)

### Community 18 - ".convert"
Cohesion: 0.15
Nodes (4): FilterConverter, JsonFilter, TaskExecutionFilter, WorkflowInstanceFilter

### Community 19 - "WorkflowInstanceStatus"
Cohesion: 0.13
Nodes (10): WorkflowInstanceStatus, CANCELLED, COMPLETED, FAULTED, PENDING, RUNNING, SUSPENDED, WAITING (+2 more)

### Community 26 - "Claude AI Assistant Guidelines - KubeSmarts Logic Apps"
Cohesion: 0.14
Nodes (13): Architecture (MODE 1 - Production), Architecture (MODE 3 - Kafka), Choosing Between MODE 1 and MODE 2, Claude AI Assistant Guidelines - KubeSmarts Logic Apps, Code Structure, **CRITICAL: Claude's Role - Reviewer, Tester, Documentation Writer**, **CRITICAL: Development Process - ASK Before Changing**, **CRITICAL: Documentation is the Source of Truth** (+5 more)

### Community 30 - "TransformFieldMappingTest"
Cohesion: 0.15
Nodes (3): TaskEventDoc, TransformFieldMappingTest, WorkflowEventDoc

### Community 31 - "steps"
Cohesion: 0.04
Nodes (46): name, runs-on, steps, strategy, timeout-minutes, MAVEN_ARGS, maven-args, jobs (+38 more)

### Community 34 - "full-test-mode1.sh"
Cohesion: 0.14
Nodes (18): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_info(), log_step(), log_success() (+10 more)

### Community 35 - "full-test-mode2.sh"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 36 - "full-test-mode3.sh"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 37 - "BucketEnumDeserializer.java"
Cohesion: 0.22
Nodes (3): BucketEnumDeserializer, BucketStringDeserializer, EpochMillisZonedDateTimeDeserializer

### Community 39 - "ElasticsearchConfiguration"
Cohesion: 0.19
Nodes (5): Backend, ELASTICSEARCH, POSTGRESQL, StorageConfiguration, ElasticsearchConfiguration

### Community 41 - "WorkflowInstanceEntityMapper"
Cohesion: 0.20
Nodes (4): 4. Entity Naming (MODE 1 - PostgreSQL), ErrorEntityMapper, TaskInstanceEntityMapper, WorkflowInstanceEntityMapper

### Community 42 - "WorkflowInstanceGraphQLApiTest"
Cohesion: 0.10
Nodes (3): StartupHealthIT, TaskExecutionFilteringIT, WorkflowInstanceGraphQLApiTest

### Community 46 - "generate-slides.js"
Cohesion: 0.11
Nodes (16): fs, generateSlide(), main(), path, puppeteer, slides, dependencies, puppeteer@^25.0.0 (+8 more)

### Community 47 - "/tmp/quarkus-flow-events.log"
Cohesion: 0.21
Nodes (17): /var/log/containers/*.log, FluentBit DaemonSet, FluentBit DaemonSet Pod (Node 1), FluentBit DaemonSet Pod (Node 2), FluentBit DaemonSet Pod (Node N), Kubernetes Cluster, Kubernetes Node 1, Kubernetes Node 2 (+9 more)

### Community 48 - "Code Style & Conventions"
Cohesion: 0.40
Nodes (5): Code Style & Conventions, Database (MODE 1 - PostgreSQL), Elasticsearch (MODE 2), GraphQL, Java Code

### Community 49 - "Important Design Decisions"
Cohesion: 0.22
Nodes (9): 1. JDBC Polling Normalization (MODE 1), 1b. Transform-Based Normalization (MODE 2 - Elasticsearch), 1c. Task Instance Composite Key (Quarkus Flow ID Issue), 2. JSON Field Exposure (String Getters), 3. Field Names - Open Workflow Alignment, 5. Document Mapping (MODE 2 - Elasticsearch), Error Handling, Important Design Decisions (+1 more)

### Community 50 - "verify-infrastructure.sh"
Cohesion: 0.27
Nodes (13): BLUE, GREEN, log_error(), log_info(), log_success(), NC, RED, verify-infrastructure.sh script (+5 more)

### Community 53 - "AttributeSort"
Cohesion: 0.19
Nodes (5): DataIndexAttributeSort, AttributeSort, SortDirection, ASC, DESC

### Community 54 - "common-setup.sh"
Cohesion: 0.20
Nodes (13): BLUE, CLUSTER_NAME, GREEN, log_error(), log_info(), log_step(), log_success(), main() (+5 more)

### Community 55 - "generate-configmap.sh"
Cohesion: 0.24
Nodes (11): error(), info(), deploy-fluentbit.sh script, step(), error(), generate_configmap(), info(), generate-configmap.sh script (+3 more)

### Community 56 - "EventProcessorHealthCheck.java"
Cohesion: 0.21
Nodes (3): Key Files Reference, HealthChecks, EventProcessorHealthCheck

### Community 57 - "jakarta.persistence.EntityManager"
Cohesion: 0.13
Nodes (4): AbstractJPAStorageFetcher, DependencyInjectionUtils, JsonPredicateBuilder, StorageFetcher

### Community 59 - "data-index-docs/package.json"
Cohesion: 0.20
Nodes (11): dependencies, @antora/cli@^3.1.9, @antora/site-generator@^3.1.9, description, name, private, scripts, build (+3 more)

### Community 61 - "Storage Backend Architecture (Maven + Quarkus Profiles)"
Cohesion: 0.25
Nodes (8): Configuration files:, Development:, Elasticsearch Schema Management, How it works:, Maven profiles (in data-index-service/pom.xml):, Storage Backend Architecture (Maven + Quarkus Profiles), What happens (Elasticsearch):, What happens (PostgreSQL):

### Community 63 - "T2: Replay via FluentBit"
Cohesion: 0.23
Nodes (12): Log Replay Scenario Diagram, FluentBit, Idempotency, /var/log/containers/, MODE 1: UPSERT with COALESCE, MODE 2: Transform aggregation, PostgreSQL/Elasticsearch Database, T0: Original Events (+4 more)

### Community 66 - "StorageServiceCapability"
Cohesion: 0.24
Nodes (5): PostgresqlStorageServiceCapabilities, StorageServiceCapability, COUNT, JSON_QUERY, StorageServiceCapabilityProvider

### Community 69 - "PostgreSQL Normalized Tables"
Cohesion: 0.28
Nodes (9): FluentBit, GraphQL API, PostgreSQL Normalized Tables, PostgreSQL Raw Tables, task_events_raw, task_instances, BEFORE INSERT Trigger, workflow_events_raw (+1 more)

### Community 70 - "Elasticsearch Normalized Indices"
Cohesion: 0.25
Nodes (9): FluentBit, GraphQL API, Elasticsearch Normalized Indices, Elasticsearch Raw Indices, task-events, task-executions, Elasticsearch Transform, workflow-events (+1 more)

### Community 73 - "Storage"
Cohesion: 0.11
Nodes (6): TaskExecutionStorage, WorkflowInstanceStorage, WorkflowInstanceGraphQLApi, WorkflowInstanceJPAStorage, Storage, StorageService

### Community 74 - "MODE 1 Scaling"
Cohesion: 0.29
Nodes (8): Horizontal Scaling, MODE 1 Capacity, MODE 1 Scaling, MODE 2 Capacity, MODE 2 Scaling, Read Replicas, Sharding, Vertical Scaling

### Community 76 - "report-failure"
Cohesion: 0.06
Nodes (36): if, uses, with, GH_REPO, GH_TOKEN, LABEL, RUN_URL, jobs (+28 more)

### Community 77 - "FluentBit DaemonSet"
Cohesion: 0.43
Nodes (7): MODE 2: Elasticsearch, FluentBit DaemonSet, GraphQL API, MODE 1: PostgreSQL, Quarkus Flow App, Storage Backend, User

### Community 78 - "Persistence Abstraction Layer"
Cohesion: 0.29
Nodes (7): Persistence Abstraction Layer, Infinispan Storage Backend, Protobuf Schema Management, Query Interface, Redis Storage Backend, Storage Interface, StorageService

### Community 82 - "Common Tasks"
Cohesion: 0.29
Nodes (7): Adding a Database Field (MODE 1 - PostgreSQL), Adding a New GraphQL Query, Adding an Elasticsearch Field (MODE 2), Common Tasks, Querying Elasticsearch Directly, Testing with Elasticsearch, Updating Documentation

### Community 92 - "java.sql.Connection"
Cohesion: 0.13
Nodes (7): FailureReason, SavepointHelper, SqlAction, TaskEventPoller, TaskEventRow, WorkflowEventPoller, WorkflowEventRow

### Community 104 - "steps"
Cohesion: 0.07
Nodes (29): environment, runs-on, steps, concurrency, cancel-in-progress, group, name, url (+21 more)

### Community 105 - "values-mode3.yaml"
Cohesion: 0.09
Nodes (26): dataIndex, image, jdbcBatch, service, storage, dataIndexIngestion, enabled, service (+18 more)

### Community 106 - "values-mode1.yaml"
Cohesion: 0.08
Nodes (25): dataIndex, image, jdbcBatch, service, storage, dataIndexIngestion, enabled, elasticsearch (+17 more)

### Community 107 - "values-mode2.yaml"
Cohesion: 0.09
Nodes (23): dataIndex, image, service, storage, dataIndexIngestion, enabled, elasticsearch, enabled (+15 more)

### Community 108 - "steps"
Cohesion: 0.09
Nodes (21): PYTHONUNBUFFERED, jobs, update-graph, name, on, workflow_dispatch, contents, env (+13 more)

### Community 109 - "antora-playbook.yaml"
Cohesion: 0.10
Nodes (19): asciidoc, attributes, hide-uri-scheme, page-pagination, tabs-sync-option, snapshot, url, content (+11 more)

### Community 110 - "workflowApp"
Cohesion: 0.13
Nodes (20): livenessProbe, readinessProbe, path, port, failureThreshold, httpGet, initialDelaySeconds, periodSeconds (+12 more)

### Community 111 - "env"
Cohesion: 0.18
Nodes (19): steps, steps, steps, INPUT_RELEASE, INPUT_TAG, IS_LATEST, RELEASE_TAG, REVISION (+11 more)

### Community 112 - "org.kubesmarts.logic.apps:data-index-model"
Cohesion: 0.17
Nodes (19): org.kie.kogito:persistence-commons-api, org.kubesmarts.logic.apps:data-index-docs, org.kubesmarts.logic.apps:data-index-e2e-tests, org.kubesmarts.logic.apps:data-index-ingestion-kafka-service, org.kubesmarts.logic.apps:data-index-ingestion-persistence, org.kubesmarts.logic.apps:data-index-integration-tests-elasticsearch, org.kubesmarts.logic.apps:data-index-integration-tests-postgresql, org.kubesmarts.logic.apps:data-index-model (+11 more)

### Community 113 - "ADR-0002: Replace Trigger-Based Normalization with JDBC Batch Normalization (MODE 1)"
Cohesion: 0.11
Nodes (17): ADR-0002: Replace Trigger-Based Normalization with JDBC Batch Normalization (MODE 1), Alternatives Considered, Benchmark, Configuration, Consequences, Context, Decision, Failure handling (+9 more)

### Community 114 - "labels"
Cohesion: 0.15
Nodes (18): apiVersion, data, kind, metadata, apiVersion, kind, metadata, vector.yaml (+10 more)

### Community 115 - "labels"
Cohesion: 0.15
Nodes (18): apiVersion, data, kind, metadata, apiVersion, kind, metadata, vector.yaml (+10 more)

### Community 117 - "steps"
Cohesion: 0.11
Nodes (17): runs-on, steps, jobs, build, name, on, pull_request, push (+9 more)

### Community 119 - "io.quarkiverse.flow.Flow"
Cohesion: 0.20
Nodes (4): TestWorkflow, FailingWorkflow, HelloWorldWorkflow, SimpleSetWorkflow

### Community 120 - "limits"
Cohesion: 0.14
Nodes (17): resources, elasticsearch, enabled, resources, cpu, memory, resources, cpu (+9 more)

### Community 122 - "release"
Cohesion: 0.14
Nodes (16): release, tag, on, push, workflow_call, workflow_dispatch, tags, default (+8 more)

### Community 127 - "ports"
Cohesion: 0.17
Nodes (15): app, name, nodePort, port, protocol, targetPort, app, matchLabels (+7 more)

### Community 128 - "pr-title.yml"
Cohesion: 0.13
Nodes (14): name, runs-on, steps, GITHUB_TOKEN, jobs, conventional-commit, name, on (+6 more)

### Community 130 - "Reference Examples"
Cohesion: 0.33
Nodes (5): For Operator Developers, Manual Deployment, NOT For Embedding, Purpose, Reference Examples

### Community 131 - "TestStatus"
Cohesion: 0.33
Nodes (6): TestStatus, CANCELLED, COMPLETED, FAULTED, RUNNING, SUSPENDED

### Community 132 - "MigrationStreams"
Cohesion: 0.21
Nodes (6): MigrationReport, MigrationStreams, DATA_INDEX, QUARTZ, RUNTIME, StreamResult

### Community 135 - "VectorConfigValidationIT.java"
Cohesion: 0.10
Nodes (4): WorkflowExecutionTest, LogicAppsMigratorConfig, MigrationRunner, MigrationRunnerIT

### Community 138 - "labels"
Cohesion: 0.21
Nodes (14): Service kafka-headless, Service kafka, app, labels, name, namespace, apiVersion, apiVersion (+6 more)

### Community 139 - "spec"
Cohesion: 0.15
Nodes (13): dnsPolicy, hostNetwork, priorityClassName, serviceAccountName, tolerations, volumes, spec, effect (+5 more)

### Community 140 - "spec"
Cohesion: 0.15
Nodes (13): dnsPolicy, hostNetwork, priorityClassName, serviceAccountName, tolerations, volumes, spec, effect (+5 more)

### Community 141 - "Chart.yaml"
Cohesion: 0.15
Nodes (12): apiVersion, appVersion, description, keywords, kubeVersion, maintainers, email, name (+4 more)

### Community 142 - "dataIndex"
Cohesion: 0.15
Nodes (13): dataIndex, graphql, jdbcBatch, replicas, storage, enabled, path, ui (+5 more)

### Community 143 - "jobs"
Cohesion: 0.15
Nodes (12): name, needs, runs-on, concurrency, cancel-in-progress, group, jobs, build-kafka (+4 more)

### Community 144 - "mode1-postgresql/daemonset.yaml"
Cohesion: 0.17
Nodes (11): apiVersion, kind, metadata, rules, ServiceAccount data-index-vector-mode1, ClusterRole data-index-vector-mode1, apiGroups, resources (+3 more)

### Community 145 - "ClusterRoleBinding data-index-vector-mode1"
Cohesion: 0.17
Nodes (12): apiVersion, kind, metadata, roleRef, subjects, ClusterRoleBinding data-index-vector-mode1, apiGroup, kind (+4 more)

### Community 146 - "mode2-elasticsearch/daemonset.yaml"
Cohesion: 0.17
Nodes (11): apiVersion, kind, metadata, rules, ServiceAccount data-index-vector-mode2, ClusterRole data-index-vector-mode2, apiGroups, resources (+3 more)

### Community 147 - "ClusterRoleBinding data-index-vector-mode2"
Cohesion: 0.17
Nodes (12): apiVersion, kind, metadata, roleRef, subjects, ClusterRoleBinding data-index-vector-mode2, apiGroup, kind (+4 more)

### Community 148 - "antora.yml"
Cohesion: 0.17
Nodes (11): asciidoc, attributes, page-pagination, project-version, sectanchors, source-highlighter, display_version, name (+3 more)

### Community 149 - "vector-mode2-elasticsearch.yaml"
Cohesion: 0.17
Nodes (11): api, address, enabled, playground, data_dir, address, default_namespace, inputs (+3 more)

### Community 150 - "transforms"
Cohesion: 0.17
Nodes (12): inputs, type, inputs, source, type, inputs, source, type (+4 more)

### Community 151 - "deploy-postgresql"
Cohesion: 0.26
Nodes (12): with, with, name, needs, secrets, uses, with, deploy-postgresql (+4 more)

### Community 152 - "containers"
Cohesion: 0.18
Nodes (11): env, image, imagePullPolicy, livenessProbe, name, ports, readinessProbe, resources (+3 more)

### Community 153 - "selector"
Cohesion: 0.18
Nodes (11): spec, app, mode, maxUnavailable, app, matchLabels, mode, selector (+3 more)

### Community 154 - "containers"
Cohesion: 0.18
Nodes (11): env, image, imagePullPolicy, livenessProbe, name, ports, readinessProbe, resources (+3 more)

### Community 155 - "selector"
Cohesion: 0.18
Nodes (11): spec, app, mode, maxUnavailable, app, matchLabels, mode, selector (+3 more)

### Community 157 - "sinks"
Cohesion: 0.18
Nodes (11): encoding, inputs, type, codec, address, default_namespace, inputs, type (+3 more)

### Community 158 - "Service data-index-vector-mode1"
Cohesion: 0.20
Nodes (10): Service data-index-vector-mode1, name, port, protocol, targetPort, apiVersion, kind, spec (+2 more)

### Community 159 - "Service data-index-vector-mode2"
Cohesion: 0.20
Nodes (10): Service data-index-vector-mode2, name, port, protocol, targetPort, apiVersion, kind, spec (+2 more)

### Community 161 - "transforms"
Cohesion: 0.20
Nodes (9): api, address, enabled, data_dir, inputs, source, type, transforms (+1 more)

### Community 162 - "debug_filter"
Cohesion: 0.22
Nodes (10): source, type, condition, inputs, type, condition, inputs, type (+2 more)

### Community 163 - "route_by_type"
Cohesion: 0.20
Nodes (10): inputs, route, type, task_events, workflow_events, source, type, route_by_type (+2 more)

### Community 164 - "elasticsearch_task"
Cohesion: 0.22
Nodes (10): max_size, type, buffer, compression, endpoints, inputs, mode, type (+2 more)

### Community 165 - "route_by_type"
Cohesion: 0.20
Nodes (10): inputs, route, type, task_events, workflow_events, source, type, route_by_type (+2 more)

### Community 166 - "values.yaml"
Cohesion: 0.20
Nodes (9): dataIndexIngestion, enabled, replicas, resources, global, imagePullPolicy, imagePullSecrets, namespace (+1 more)

### Community 167 - "pullPolicy"
Cohesion: 0.47
Nodes (10): image, image, image, pullPolicy, repository, tag, image, image (+2 more)

### Community 168 - "postgres_workflow"
Cohesion: 0.25
Nodes (9): max_events, timeout_secs, batch, batch, endpoint, inputs, table, type (+1 more)

### Community 169 - "elasticsearch_workflow"
Cohesion: 0.22
Nodes (9): healthcheck, compression, endpoints, healthcheck, inputs, mode, type, enabled (+1 more)

### Community 170 - "extraPortMappings"
Cohesion: 0.22
Nodes (8): apiVersion, containerPort, hostPort, protocol, kind, nodes, extraPortMappings, role

### Community 171 - "nodePort"
Cohesion: 0.50
Nodes (9): service, service, service, service, service, nodePort, port, type (+1 more)

### Community 172 - "containers"
Cohesion: 0.22
Nodes (9): env, image, livenessProbe, name, ports, readinessProbe, resources, volumeMounts (+1 more)

### Community 173 - "spec"
Cohesion: 0.22
Nodes (9): StatefulSet kafka, replicas, serviceName, template, apiVersion, kind, spec, metadata (+1 more)

### Community 174 - "Release Process"
Cohesion: 0.22
Nodes (9): Breaking changes, Git tags, Helm, How a release works, Images, Release checklist, Release Process, Rollback (+1 more)

### Community 175 - "release.yml"
Cohesion: 0.22
Nodes (8): jobs, tag, name, permissions, contents, packages, name, runs-on

### Community 176 - "RELEASE.md"
Cohesion: 0.29
Nodes (5): Changelog, Build and test, Contributing, Pull requests, Releases

### Community 178 - "postgres_task"
Cohesion: 0.25
Nodes (8): enabled, endpoint, healthcheck, inputs, table, type, healthcheck, postgres_task

### Community 179 - "kubernetes_logs"
Cohesion: 0.25
Nodes (8): type, auto_partial_merge, extra_field_selector, self_node_name, type, sources, internal_metrics, kubernetes_logs

### Community 180 - "kubernetes_logs"
Cohesion: 0.25
Nodes (8): type, auto_partial_merge, extra_field_selector, self_node_name, type, sources, internal_metrics, kubernetes_logs

### Community 181 - "postgresql"
Cohesion: 0.25
Nodes (8): postgresql, database, enabled, existingSecret, host, password, schema, username

### Community 182 - "kafka.yaml"
Cohesion: 0.25
Nodes (7): Namespace kafka, Service kafka-nodeport, apiVersion, kind, metadata, apiVersion, kind

### Community 183 - "with"
Cohesion: 0.25
Nodes (8): with, cache, distribution, java-version, name, path, ref, retention-days

### Community 184 - "steps"
Cohesion: 0.25
Nodes (8): RELEASE, env, name, run, uses, with, steps, ref

### Community 185 - "publish"
Cohesion: 0.25
Nodes (8): publish, name, needs, secrets, uses, with, release, tag

### Community 186 - "[Unreleased]"
Cohesion: 0.29
Nodes (7): Added, Changed, Deprecated, Fixed, Removed, Security, [Unreleased]

### Community 187 - "filter_workflow_events"
Cohesion: 0.33
Nodes (7): source, type, condition, condition, inputs, type, filter_workflow_events

### Community 188 - "kafka"
Cohesion: 0.29
Nodes (7): kafka, enabled, replicas, resources, topics, deadLetterQueue, lifecycleEvents

### Community 189 - "setup"
Cohesion: 0.29
Nodes (7): setup, IS_LATEST, TAG, VERSION, name, outputs, runs-on

### Community 190 - "version"
Cohesion: 0.29
Nodes (7): version, on, workflow_dispatch, description, required, type, inputs

### Community 191 - "What NOT to Do"
Cohesion: 0.33
Nodes (6): ❌ Architecture, ❌ Code, ❌ Dependencies, ❌ Elasticsearch Specific, ❌ Testing, What NOT to Do

### Community 192 - "annotations"
Cohesion: 0.33
Nodes (6): prometheus.io/path, prometheus.io/port, prometheus.io/scrape, annotations, template, metadata

### Community 193 - "securityContext"
Cohesion: 0.33
Nodes (6): type, fsGroup, runAsNonRoot, runAsUser, seccompProfile, securityContext

### Community 194 - "annotations"
Cohesion: 0.33
Nodes (6): prometheus.io/path, prometheus.io/port, prometheus.io/scrape, annotations, template, metadata

### Community 195 - "securityContext"
Cohesion: 0.33
Nodes (6): type, fsGroup, runAsNonRoot, runAsUser, seccompProfile, securityContext

### Community 197 - "request"
Cohesion: 0.53
Nodes (6): request, request, concurrency, retry_attempts, retry_initial_backoff_secs, retry_max_duration_secs

### Community 198 - "persistence"
Cohesion: 0.60
Nodes (6): persistence, persistence, enabled, size, storageClass, persistence

### Community 199 - "volumeClaimTemplates"
Cohesion: 0.33
Nodes (6): requests, accessModes, resources, volumeClaimTemplates, metadata, spec

### Community 200 - "Build & Deployment"
Cohesion: 0.40
Nodes (5): Build & Deployment, Elasticsearch Deployment, KIND Deployment, Local Development, Production Build

### Community 202 - "debug_stdout"
Cohesion: 0.40
Nodes (5): encoding, inputs, type, codec, debug_stdout

### Community 203 - "deploy-elasticsearch"
Cohesion: 0.40
Nodes (5): name, needs, secrets, uses, deploy-elasticsearch

### Community 204 - "deploy-kafka"
Cohesion: 0.40
Nodes (5): name, needs, secrets, uses, deploy-kafka

### Community 205 - "Troubleshooting"
Cohesion: 0.50
Nodes (4): Build Issues, Deployment Issues (MODE 1 - PostgreSQL), Deployment Issues (MODE 2 - Elasticsearch), Troubleshooting

### Community 206 - "Current Status & Next Steps"
Cohesion: 0.50
Nodes (4): ✅ Complete (Phase 1 - MODE 1), ✅ Complete (Phase 2 - MODE 2), Current Status & Next Steps, 🔄 Optional Future Work

### Community 208 - "build_task_row"
Cohesion: 0.50
Nodes (4): inputs, source, type, build_task_row

### Community 209 - "build_workflow_row"
Cohesion: 0.50
Nodes (4): inputs, source, type, build_workflow_row

### Community 210 - "bulk"
Cohesion: 0.67
Nodes (4): action, index, bulk, bulk

### Community 211 - "build-elasticsearch"
Cohesion: 0.50
Nodes (4): name, needs, runs-on, build-elasticsearch

### Community 212 - "build-postgresql"
Cohesion: 0.50
Nodes (4): name, needs, runs-on, build-postgresql

### Community 213 - "summary"
Cohesion: 0.50
Nodes (4): summary, name, needs, runs-on

### Community 214 - "Testing Approach"
Cohesion: 0.67
Nodes (3): Integration Tests (MODE 1 - PostgreSQL), Integration Tests (MODE 2 - Elasticsearch), Testing Approach

## Knowledge Gaps
- **223 isolated node(s):** `github.com/kubesmarts/logic-apps/data-index/collectors`, `org.kubesmarts.logic.apps:data-index-collectors`, `org.kubesmarts.logic.apps:data-index-docs`, `org.kubesmarts.logic.apps:data-index-e2e-tests`, `org.kubesmarts.logic.apps:data-index-ingestion` (+218 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 1179 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **150 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `WorkflowInstance` connect `WorkflowInstance` to `KafkaLifecycleConsumer`, `Workflow`, `TaskExecution`, `jakarta.enterprise.context.ApplicationScoped`, `WorkflowInstanceStatus`, `list`, `com.fasterxml.jackson.databind.JsonNode`, `Claude AI Assistant Guidelines - KubeSmarts Logic Apps`, `ElasticsearchSmartFilteringIT`, `TransformFieldMappingTest`, `TransformFieldMappingTest.java`, `WorkflowInstanceEntityMapper`, `KafkaLifecycleConsumer.java`, `io.quarkus.test.junit.QuarkusTest`, `Mapper.java`, `Storage`, `ElasticsearchStorageIntegrationTest`, `ElasticsearchWorkflowInstanceStorageIT`, `.toWorkflowInstance`?**
  _High betweenness centrality (0.046) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `WorkflowInstance` (e.g. with `Architecture (MODE 3 - Kafka)` and `Knowledge Graph (graphify)`) actually correct?**
  _`WorkflowInstance` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `github.com/kubesmarts/logic-apps/data-index/collectors`, `org.kubesmarts.logic.apps:data-index-collectors`, `org.kubesmarts.logic.apps:data-index-docs` to the rest of the system?**
  _223 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `steps` be split into smaller, more focused modules?**
  _Cohesion score 0.041666666666666664 - nodes in this community are weakly interconnected._
- **Why does `TaskExecution` connect `TaskExecution` to `ElasticsearchTransformIntegrationTest`, `TaskExecutionStorageIT`, `TransformFieldMappingTest.java`, `Mapper.java`, `Storage`, `WorkflowInstance`, `WorkflowInstanceEntityMapper`, `jakarta.enterprise.context.ApplicationScoped`, `KafkaLifecycleConsumer.java`, `list`, `com.fasterxml.jackson.databind.JsonNode`, `Claude AI Assistant Guidelines - KubeSmarts Logic Apps`, `ElasticsearchStorageIntegrationTest`, `TransformFieldMappingTest`?**
  _High betweenness centrality (0.029) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `TaskExecution` (e.g. with `Architecture (MODE 3 - Kafka)` and `Knowledge Graph (graphify)`) actually correct?**
  _`TaskExecution` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `OrderBy` be split into smaller, more focused modules?**
  _Cohesion score 0.12773109243697478 - nodes in this community are weakly interconnected._