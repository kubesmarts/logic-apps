# Graph Report - logic-apps  (2026-09-09)

## Corpus Check
- 219 files · ~127,148 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1822 nodes · 4025 edges · 138 communities (74 shown, 60 thin omitted)
- Extraction: 82% EXTRACTED · 18% INFERRED · 0% AMBIGUOUS · INFERRED: 728 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `90a4b5e6`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- ElasticsearchTaskExecutionStorageIT
- OrderBy
- org.junit.jupiter.api.Test
- KafkaLifecycleConsumer.java
- EventMetrics
- MODE 3 (Kafka + Ingestion Service + PostgreSQL)
- KafkaIngestionIT
- ElasticsearchSchemaInitializer
- Workflow
- WorkflowInstance
- TaskExecution
- StringFilter
- Data Index E2E Tests
- org.slf4j.Logger
- io.quarkus.test.junit.QuarkusTestProfile
- MODE 1 (PostgreSQL)
- FilterCondition
- ElasticsearchQuery
- .convert
- WorkflowInstanceStatus
- AttributeFilter
- io.quarkus.test.junit.QuarkusTest
- WorkflowInstanceEntity
- JsonUtils
- DataIndexE2ETest
- jakarta.persistence.criteria.CriteriaBuilder
- Claude AI Assistant Guidelines - KubeSmarts Logic Apps
- .createWorkflowDefinition
- jakarta.transaction.Transactional
- Error
- ElasticsearchTransformIntegrationTest.java
- jakarta.enterprise.context.ApplicationScoped
- DateTimeFilter
- TaskInstanceEntity
- full-test-mode1.sh
- full-test-mode2.sh
- full-test-mode3.sh
- com.fasterxml.jackson.databind.DeserializationContext
- com.fasterxml.jackson.databind.ObjectMapper
- ElasticsearchConfiguration
- ErrorEntity
- WorkflowInstanceEntityMapper
- .equalTo
- IntFilter
- ContainsSQLFunction.java
- generate-slides.js
- /tmp/quarkus-flow-events.log
- JsonFieldFilter
- Important Design Decisions
- verify-infrastructure.sh
- Workflow Test App Documentation
- TaskExecutionProcessor
- QuarkusFlowLifecycleIT
- common-setup.sh
- generate-configmap.sh
- WorkflowInstanceGraphQLApiTest
- jakarta.persistence.EntityManager
- Query
- data-index-docs/package.json
- org.junit.jupiter.api.AfterEach
- Storage Backend Architecture (Maven + Quarkus Profiles)
- TaskInstanceEntityId
- T2: Replay via FluentBit
- ElasticsearchTransformMetricsCollector
- TaskExecutionStorageIT
- StorageServiceCapability
- COALESCE Idempotency Mechanism
- Presentation Slides Index
- PostgreSQL Normalized Tables
- Elasticsearch Normalized Indices
- HttpBinMockServer
- ElasticsearchTransformMetricsIT
- ElasticsearchTransformPerformanceBenchmarkIT
- MODE 1 Scaling
- jakarta.annotation.PostConstruct
- TaskExecutionFilter
- FluentBit DaemonSet
- Persistence Abstraction Layer
- Kafka Helm Template
- BaseWorkflowLifecycleIT
- Red Hat EFK Stack Integration
- Common Tasks
- TestModel
- PostgreSQL Trigger-Based Normalization
- FluentBit Configurations Overview
- data-index/collectors
- KIND Cluster Configuration
- PostgreSQL Helm Template
- Kafka Kubernetes Manifest
- setup-es-transform.sh
- Architectural Decision Framework
- Multi-Tenant FluentBit Configuration
- Documentation Index
- FluentBit Configuration Files
- Helm Chart Documentation
- FluentBit ConfigMap Template
- Default Helm Values
- Vector ConfigMap Template
- MODE 1 Idempotency Diagram
- data-index-collectors
- data-index-docs
- data-index-e2e-tests
- data-index-ingestion
- data-index-ingestion-kafka-processor
- data-index-ingestion-kafka-service
- data-index-integration-tests
- data-index-integration-tests-elasticsearch
- data-index-integration-tests-postgresql
- data-index-model
- data-index-service
- data-index-service-core
- data-index-service-elasticsearch
- data-index-service-postgresql
- data-index-storage
- data-index-storage-common
- data-index-storage-elasticsearch
- data-index-storage-elasticsearch-schema
- data-index-storage-migrations
- data-index-storage-postgresql
- github.com/kubesmarts/logic-apps/data-index/collectors
- org.kie.kogito:persistence-commons
- org.kubesmarts:logic-apps
- org.kubesmarts.logic.apps:data-index
- persistence-commons-api
- workflow-test-app
- What NOT to Do
- Reference Examples
- TestStatus
- Build & Deployment
- Code Style & Conventions
- Troubleshooting
- Current Status & Next Steps
- Testing Approach
- data-index/CLAUDE.md

## God Nodes (most connected - your core abstractions)
1. `WorkflowInstance` - 77 edges
2. `TaskExecution` - 75 edges
3. `AttributeFilter` - 59 edges
4. `WorkflowInstanceEntity` - 55 edges
5. `TaskInstanceEntity` - 54 edges
6. `WorkflowInstanceStatus` - 37 edges
7. `OrderBy` - 33 edges
8. `StringFilter` - 33 edges
9. `ElasticsearchTaskExecutionStorageIT` - 32 edges
10. `Workflow` - 31 edges

## Surprising Connections (you probably didn't know these)
- `DataIndexAttributeFilter` --inherits--> `AttributeFilter`  [EXTRACTED]
  data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/filter/DataIndexAttributeFilter.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/AttributeFilter.java
- `ElasticsearchQuery` --references--> `AttributeFilter`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-elasticsearch/src/main/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/ElasticsearchQuery.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/AttributeFilter.java
- `ElasticsearchQuery` --references--> `AttributeSort`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-elasticsearch/src/main/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/ElasticsearchQuery.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/AttributeSort.java
- `ElasticsearchQuery` --implements--> `Query`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-elasticsearch/src/main/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/ElasticsearchQuery.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/Query.java
- `TestAttributeFilter` --inherits--> `AttributeFilter`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/TestAttributeFilter.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/AttributeFilter.java

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

## Communities (138 total, 60 thin omitted)

### Community 0 - "ElasticsearchTaskExecutionStorageIT"
Cohesion: 0.10
Nodes (4): ElasticsearchTaskExecutionStorage, Override, ElasticsearchTaskExecutionStorageIT, ElasticsearchTransformIntegrationTest

### Community 1 - "OrderBy"
Cohesion: 0.07
Nodes (12): DataIndexAttributeSort, OrderBy, ASC, DESC, OrderByConverter, TaskExecutionOrderBy, WorkflowInstanceOrderBy, AttributeSort (+4 more)

### Community 2 - "org.junit.jupiter.api.Test"
Cohesion: 0.08
Nodes (4): BucketEnumDeserializerTest, BucketStringDeserializerTest, ElasticsearchSchemaInitializationIT, org.junit.jupiter.api.Test

### Community 3 - "KafkaLifecycleConsumer.java"
Cohesion: 0.12
Nodes (12): ProcessEventFailedException, KafkaLifecycleConsumer, Mapper, LifecycleEventUtils, io.cloudevents.CloudEvent, io.serverlessworkflow.impl.lifecycle.ce.TaskCEData, io.serverlessworkflow.impl.WorkflowError, io.smallrye.reactive.messaging.MutinyEmitter (+4 more)

### Community 4 - "EventMetrics"
Cohesion: 0.06
Nodes (24): HealthChecks, RootResource, RootResource, PollingEventProcessor, EventProcessorHealthCheck, Override, EventMetrics, EventProcessorMetricsResource (+16 more)

### Community 5 - "MODE 3 (Kafka + Ingestion Service + PostgreSQL)"
Cohesion: 0.06
Nodes (39): Data Index Ingestion Service, Data Index Service, Storage Common Module, Elasticsearch Storage Implementation, Flyway Database Migrations, PostgreSQL Storage Implementation, MODE 2 E2E Testing Guide, MODE 3 Kafka Deployment Guide (+31 more)

### Community 7 - "ElasticsearchSchemaInitializer"
Cohesion: 0.12
Nodes (8): co.elastic.clients.elasticsearch.ilm.ElasticsearchIlmClient, co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient, co.elastic.clients.elasticsearch.transform.ElasticsearchTransformClient, ElasticsearchSchemaInitializer, ElasticsearchSchemaInitializerTest, org.junit.jupiter.api.extension.ExtendWith, org.mockito.junit.jupiter.MockitoExtension, org.mockito.junit.jupiter.MockitoSettings

### Community 9 - "WorkflowInstance"
Cohesion: 0.06
Nodes (10): Override, WorkflowInstance, ElasticsearchWorkflowInstanceStorage, Override, ElasticsearchSmartFilteringIT, ElasticsearchStorageIntegrationTest, ElasticsearchClient, ElasticsearchTransformNormalizationIT (+2 more)

### Community 10 - "TaskExecution"
Cohesion: 0.11
Nodes (6): com.fasterxml.jackson.databind.JsonNode, TaskPersistence, Override, TaskExecution, Override, TaskExecutionJPAStorage

### Community 12 - "Data Index E2E Tests"
Cohesion: 0.09
Nodes (30): ADR-0001: Migrate to Vector for OpenShift Alignment, Antora, CloudEvents, Data Index, data-index-docs, data-index-model, data-index-service, Data Index E2E Tests (+22 more)

### Community 13 - "org.slf4j.Logger"
Cohesion: 0.24
Nodes (11): WorkflowPersistence, WorkflowEventProcessor, io.quarkus.arc.Unremovable, jakarta.inject.Inject, java.sql.Connection, java.sql.PreparedStatement, java.sql.SQLException, javax.sql.DataSource (+3 more)

### Community 14 - "io.quarkus.test.junit.QuarkusTestProfile"
Cohesion: 0.10
Nodes (14): ElasticsearchSchemaTestProfile, Override, CustomTimeWindowProfile, Override, ElasticsearchConfigurationValidationIT, InvalidRetentionProfile, InvalidTimeWindowProfile, Override (+6 more)

### Community 15 - "MODE 1 (PostgreSQL)"
Cohesion: 0.08
Nodes (29): Data Consistency Models, GraphQL API, Latency Characteristics, MODE 1 (PostgreSQL), MODE 2 (Elasticsearch), Scaling Strategy, Data Index Service, Elasticsearch 8.11.1 (+21 more)

### Community 16 - "FilterCondition"
Cohesion: 0.08
Nodes (20): DataIndexAttributeFilter, TestAttributeFilter, FilterCondition, AND, BETWEEN, CONTAINS, CONTAINS_ALL, CONTAINS_ANY (+12 more)

### Community 17 - "ElasticsearchQuery"
Cohesion: 0.23
Nodes (5): Builder, co.elastic.clients.elasticsearch._types.FieldValue, co.elastic.clients.json.JsonData, ElasticsearchQuery, Override

### Community 19 - "WorkflowInstanceStatus"
Cohesion: 0.13
Nodes (11): fromV08State(), WorkflowInstanceStatus, CANCELLED, COMPLETED, FAULTED, PENDING, RUNNING, SUSPENDED (+3 more)

### Community 21 - "io.quarkus.test.junit.QuarkusTest"
Cohesion: 0.28
Nodes (6): co.elastic.clients.elasticsearch.ElasticsearchClient, ElasticsearchDevServicesTest, ElasticsearchTransformConfigurationIT, io.quarkus.test.junit.QuarkusTest, io.quarkus.test.junit.TestProfile, org.junit.jupiter.api.BeforeEach

### Community 23 - "JsonUtils"
Cohesion: 0.27
Nodes (5): com.fasterxml.jackson.databind.node.ObjectNode, JsonUtils, Override, JsonBinaryConverter, jakarta.persistence.AttributeConverter

### Community 24 - "DataIndexE2ETest"
Cohesion: 0.16
Nodes (5): VectorConfigValidationIT, DataIndexE2ETest, io.restassured.response.Response, org.junit.jupiter.api.BeforeAll, org.junit.jupiter.api.condition.EnabledIfSystemProperty

### Community 25 - "jakarta.persistence.criteria.CriteriaBuilder"
Cohesion: 0.30
Nodes (4): PostgresqlJsonPredicateBuilder, jakarta.persistence.criteria.CriteriaBuilder, jakarta.persistence.criteria.Expression, Override

### Community 26 - "Claude AI Assistant Guidelines - KubeSmarts Logic Apps"
Cohesion: 0.11
Nodes (18): Architecture (MODE 1 - Production), Architecture (MODE 2 - Elasticsearch), Architecture (MODE 3 - Kafka), Choosing Between MODE 1 and MODE 2, Claude AI Assistant Guidelines - KubeSmarts Logic Apps, Code Structure, **CRITICAL: Claude's Role - Reviewer, Tester, Documentation Writer**, **CRITICAL: Development Process - ASK Before Changing** (+10 more)

### Community 27 - ".createWorkflowDefinition"
Cohesion: 0.14
Nodes (4): BatchProcessingIT, KafkaConsumer, BinaryCloudEventExtractionIT, KafkaProducer

### Community 28 - "jakarta.transaction.Transactional"
Cohesion: 0.06
Nodes (16): TaskExecutionStorage, WorkflowInstanceStorage, WorkflowInstanceGraphQLApi, AbstractJPAStorageFetcher, Override, AbstractStorage, Override, AbstractEntity (+8 more)

### Community 30 - "ElasticsearchTransformIntegrationTest.java"
Cohesion: 0.13
Nodes (4): com.fasterxml.jackson.annotation.JsonProperty, TaskEventDoc, TransformFieldMappingTest, WorkflowEventDoc

### Community 31 - "jakarta.enterprise.context.ApplicationScoped"
Cohesion: 0.12
Nodes (14): GraphQLConfiguration, ElasticsearchClient, TestElasticsearchClientProducer, FailingWorkflow, Override, HelloWorldWorkflow, Override, Override (+6 more)

### Community 34 - "full-test-mode1.sh"
Cohesion: 0.14
Nodes (18): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_info(), log_step(), log_success() (+10 more)

### Community 35 - "full-test-mode2.sh"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 36 - "full-test-mode3.sh"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 37 - "com.fasterxml.jackson.databind.DeserializationContext"
Cohesion: 0.22
Nodes (11): com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.BeanProperty, com.fasterxml.jackson.databind.deser.ContextualDeserializer, com.fasterxml.jackson.databind.DeserializationContext, com.fasterxml.jackson.databind.JsonDeserializer, BucketEnumDeserializer, Override, BucketStringDeserializer (+3 more)

### Community 38 - "com.fasterxml.jackson.databind.ObjectMapper"
Cohesion: 0.27
Nodes (5): com.fasterxml.jackson.databind.ObjectMapper, Override, KafkaIngestionObjectMapperCustomizer, ObjectMapperProducer, io.quarkus.jackson.ObjectMapperCustomizer

### Community 39 - "ElasticsearchConfiguration"
Cohesion: 0.20
Nodes (8): Backend, ELASTICSEARCH, POSTGRESQL, StorageConfiguration, ElasticsearchConfiguration, io.quarkus.runtime.annotations.ConfigRoot, io.smallrye.config.ConfigMapping, io.smallrye.config.WithDefault

### Community 41 - "WorkflowInstanceEntityMapper"
Cohesion: 0.19
Nodes (6): ErrorEntityMapper, TaskInstanceEntityMapper, WorkflowInstanceEntityMapper, org.mapstruct.AfterMapping, org.mapstruct.Mapper, org.mapstruct.Mapping

### Community 42 - ".equalTo"
Cohesion: 0.16
Nodes (4): StartupHealthIT, TaskExecutionFilteringIT, WorkflowExecutionTest, io.quarkus.test.common.QuarkusTestResource

### Community 45 - "ContainsSQLFunction.java"
Cohesion: 0.16
Nodes (12): ContainsSQLFunction, Override, CustomFunctionsContributor, Override, org.hibernate.boot.model.FunctionContributions, org.hibernate.boot.model.FunctionContributor, org.hibernate.dialect.function.StandardSQLFunction, org.hibernate.metamodel.model.domain.ReturnableType (+4 more)

### Community 46 - "generate-slides.js"
Cohesion: 0.12
Nodes (16): fs, generateSlide(), main(), path, puppeteer, slides, dependencies, puppeteer (+8 more)

### Community 47 - "/tmp/quarkus-flow-events.log"
Cohesion: 0.21
Nodes (17): /var/log/containers/*.log, FluentBit DaemonSet, FluentBit DaemonSet Pod (Node 1), FluentBit DaemonSet Pod (Node 2), FluentBit DaemonSet Pod (Node N), Kubernetes Cluster, Kubernetes Node 1, Kubernetes Node 2 (+9 more)

### Community 49 - "Important Design Decisions"
Cohesion: 0.20
Nodes (10): 1. Trigger-Based Normalization (Not Polling), 1b. Transform-Based Normalization (MODE 2 - Elasticsearch), 1c. Task Instance Composite Key (Quarkus Flow ID Issue), 2. JSON Field Exposure (String Getters), 3. Field Names - Open Workflow Alignment, 4. Entity Naming (MODE 1 - PostgreSQL), 5. Document Mapping (MODE 2 - Elasticsearch), Error Handling (+2 more)

### Community 50 - "verify-infrastructure.sh"
Cohesion: 0.30
Nodes (14): BLUE, GREEN, log_error(), log_info(), log_success(), log_warn(), NC, RED (+6 more)

### Community 51 - "Workflow Test App Documentation"
Cohesion: 0.15
Nodes (14): Namespace Helm Template, Vector DaemonSet Template, Workflow Test App Template, FluentBit DaemonSet - MODE 1, Workflow Test App Documentation, Vector DaemonSet - MODE 2, Workflow Test App Deployment, FluentBit DaemonSet - PostgreSQL Mode (+6 more)

### Community 52 - "TaskExecutionProcessor"
Cohesion: 0.25
Nodes (3): EventProcessor, Override, TaskExecutionProcessor

### Community 53 - "QuarkusFlowLifecycleIT"
Cohesion: 0.25
Nodes (5): Override, Profile, QuarkusFlowLifecycleIT, TestWorkflow, QuarkusFlowLifecycleIT.Profile

### Community 54 - "common-setup.sh"
Cohesion: 0.20
Nodes (13): BLUE, CLUSTER_NAME, GREEN, log_error(), log_info(), log_step(), log_success(), main() (+5 more)

### Community 55 - "generate-configmap.sh"
Cohesion: 0.24
Nodes (11): error(), info(), deploy-fluentbit.sh script, step(), error(), generate_configmap(), info(), generate-configmap.sh script (+3 more)

### Community 57 - "jakarta.persistence.EntityManager"
Cohesion: 0.21
Nodes (3): DependencyInjectionUtils, JsonPredicateBuilder, jakarta.persistence.EntityManager

### Community 58 - "Query"
Cohesion: 0.23
Nodes (5): Override, JPAQuery, jakarta.persistence.criteria.CriteriaQuery, jakarta.persistence.criteria.Root, Query

### Community 59 - "data-index-docs/package.json"
Cohesion: 0.17
Nodes (11): dependencies, @antora/cli, @antora/site-generator, description, name, private, scripts, build (+3 more)

### Community 60 - "org.junit.jupiter.api.AfterEach"
Cohesion: 0.44
Nodes (3): jakarta.persistence.Entity, jakarta.persistence.Table, org.junit.jupiter.api.AfterEach

### Community 61 - "Storage Backend Architecture (Maven + Quarkus Profiles)"
Cohesion: 0.25
Nodes (8): Configuration files:, Development:, Elasticsearch Schema Management, How it works:, Maven profiles (in data-index-service/pom.xml):, Storage Backend Architecture (Maven + Quarkus Profiles), What happens (Elasticsearch):, What happens (PostgreSQL):

### Community 62 - "TaskInstanceEntityId"
Cohesion: 0.20
Nodes (3): Override, TaskInstanceEntityId, jakarta.persistence.Embeddable

### Community 63 - "T2: Replay via FluentBit"
Cohesion: 0.23
Nodes (12): Log Replay Scenario Diagram, FluentBit, Idempotency, /var/log/containers/, MODE 1: UPSERT with COALESCE, MODE 2: Transform aggregation, PostgreSQL/Elasticsearch Database, T0: Original Events (+4 more)

### Community 64 - "ElasticsearchTransformMetricsCollector"
Cohesion: 0.19
Nodes (8): co.elastic.clients.transport.rest5_client.low_level.Rest5Client, EventProcessorMetrics, StartupEvent, ElasticsearchTransformMetricsCollector, io.micrometer.core.instrument.MeterRegistry, io.quarkus.runtime.Startup, io.quarkus.runtime.StartupEvent, io.quarkus.scheduler.Scheduled

### Community 66 - "StorageServiceCapability"
Cohesion: 0.24
Nodes (6): Override, PostgresqlStorageServiceCapabilities, StorageServiceCapability, COUNT, JSON_QUERY, StorageServiceCapabilityProvider

### Community 67 - "COALESCE Idempotency Mechanism"
Cohesion: 0.22
Nodes (11): COALESCE Idempotency Mechanism, Final Database State, Event 1 (COMPLETED), Event 2 (RUNNING), Event 3 (CREATED), Immutable Fields, Out-of-Order Event Processing, PostgreSQL Trigger (+3 more)

### Community 68 - "Presentation Slides Index"
Cohesion: 0.20
Nodes (10): Data Index POC Presentation, Title Slide - Data Index POC, Migration Context Slide, Event Flow Architecture Slide, MODE 1 Architecture Slide, MODE 1 Trigger Logic Slide, MODE 2 Architecture Slide, MODE 2 Transform Logic Slide (+2 more)

### Community 69 - "PostgreSQL Normalized Tables"
Cohesion: 0.28
Nodes (9): FluentBit, GraphQL API, PostgreSQL Normalized Tables, PostgreSQL Raw Tables, task_events_raw, task_instances, BEFORE INSERT Trigger, workflow_events_raw (+1 more)

### Community 70 - "Elasticsearch Normalized Indices"
Cohesion: 0.25
Nodes (9): FluentBit, GraphQL API, Elasticsearch Normalized Indices, Elasticsearch Raw Indices, task-events, task-executions, Elasticsearch Transform, workflow-events (+1 more)

### Community 71 - "HttpBinMockServer"
Cohesion: 0.36
Nodes (5): com.github.tomakehurst.wiremock.WireMockServer, HttpBinMockServer, Override, io.quarkus.test.common.QuarkusTestResourceLifecycleManager, WireMockServer

### Community 74 - "MODE 1 Scaling"
Cohesion: 0.29
Nodes (8): Horizontal Scaling, MODE 1 Capacity, MODE 1 Scaling, MODE 2 Capacity, MODE 2 Scaling, Read Replicas, Sharding, Vertical Scaling

### Community 77 - "FluentBit DaemonSet"
Cohesion: 0.43
Nodes (7): MODE 2: Elasticsearch, FluentBit DaemonSet, GraphQL API, MODE 1: PostgreSQL, Quarkus Flow App, Storage Backend, User

### Community 78 - "Persistence Abstraction Layer"
Cohesion: 0.29
Nodes (7): Persistence Abstraction Layer, Infinispan Storage Backend, Protobuf Schema Management, Query Interface, Redis Storage Backend, Storage Interface, StorageService

### Community 79 - "Kafka Helm Template"
Cohesion: 0.33
Nodes (6): Kafka Helm Template, Kafka Scripts - MODE 3, Kafka Headless Service, Kafka StatefulSet - KRaft Mode, flow-lifecycle-out Topic, MODE 3 - Kafka Ingestion Architecture

### Community 80 - "BaseWorkflowLifecycleIT"
Cohesion: 0.15
Nodes (4): BaseWorkflowLifecycleIT, CancelledWorkflowIT, FaultedWorkflowIT, SuspendedWorkflowIT

### Community 81 - "Red Hat EFK Stack Integration"
Cohesion: 0.40
Nodes (6): Red Hat EFK Stack Integration, FluentBit DaemonSet, Fluentd DaemonSet, GraphQL API (Data Index), PostgreSQL / Elasticsearch, Red Hat EFK Elasticsearch

### Community 82 - "Common Tasks"
Cohesion: 0.29
Nodes (7): Adding a Database Field (MODE 1 - PostgreSQL), Adding a New GraphQL Query, Adding an Elasticsearch Field (MODE 2), Common Tasks, Querying Elasticsearch Directly, Testing with Elasticsearch, Updating Documentation

### Community 84 - "PostgreSQL Trigger-Based Normalization"
Cohesion: 0.50
Nodes (4): PostgreSQL Mode - FluentBit, normalize_task_event() Function, normalize_workflow_event() Function, PostgreSQL Trigger-Based Normalization

### Community 85 - "FluentBit Configurations Overview"
Cohesion: 0.67
Nodes (3): FluentBit Configurations Overview, FluentBit MODE 1 - PostgreSQL, FluentBit MODE 2 - Elasticsearch

### Community 129 - "What NOT to Do"
Cohesion: 0.33
Nodes (6): ❌ Architecture, ❌ Code, ❌ Dependencies, ❌ Elasticsearch Specific, ❌ Testing, What NOT to Do

### Community 130 - "Reference Examples"
Cohesion: 0.33
Nodes (5): For Operator Developers, Manual Deployment, NOT For Embedding, Purpose, Reference Examples

### Community 131 - "TestStatus"
Cohesion: 0.33
Nodes (6): TestStatus, CANCELLED, COMPLETED, FAULTED, RUNNING, SUSPENDED

### Community 132 - "Build & Deployment"
Cohesion: 0.40
Nodes (5): Build & Deployment, Elasticsearch Deployment, KIND Deployment, Local Development, Production Build

### Community 133 - "Code Style & Conventions"
Cohesion: 0.40
Nodes (5): Code Style & Conventions, Database (MODE 1 - PostgreSQL), Elasticsearch (MODE 2), GraphQL, Java Code

### Community 134 - "Troubleshooting"
Cohesion: 0.50
Nodes (4): Build Issues, Deployment Issues (MODE 1 - PostgreSQL), Deployment Issues (MODE 2 - Elasticsearch), Troubleshooting

### Community 135 - "Current Status & Next Steps"
Cohesion: 0.50
Nodes (4): ✅ Complete (Phase 1 - MODE 1), ✅ Complete (Phase 2 - MODE 2), Current Status & Next Steps, 🔄 Optional Future Work

### Community 136 - "Testing Approach"
Cohesion: 0.67
Nodes (3): Integration Tests (MODE 1 - PostgreSQL), Integration Tests (MODE 2 - Elasticsearch), Testing Approach

## Knowledge Gaps
- **288 isolated node(s):** `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name`, `version`, `description` (+283 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 473 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **60 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `WorkflowInstance` connect `WorkflowInstance` to `KafkaLifecycleConsumer.java`, `com.fasterxml.jackson.databind.ObjectMapper`, `Workflow`, `WorkflowInstanceEntityMapper`, `TaskExecution`, `org.slf4j.Logger`, `WorkflowInstanceStatus`, `io.quarkus.test.junit.QuarkusTest`, `jakarta.transaction.Transactional`, `ElasticsearchTransformIntegrationTest.java`, `jakarta.enterprise.context.ApplicationScoped`?**
  _High betweenness centrality (0.092) - this node is a cross-community bridge._
- **Why does `AttributeFilter` connect `AttributeFilter` to `DateTimeFilter`, `.equalTo`, `StringFilter`, `IntFilter`, `FilterCondition`, `JsonFieldFilter`, `.convert`, `ElasticsearchQuery`, `JsonUtils`, `WorkflowInstanceGraphQLApiTest`, `jakarta.persistence.EntityManager`, `Query`, `jakarta.transaction.Transactional`, `jakarta.persistence.criteria.CriteriaBuilder`?**
  _High betweenness centrality (0.068) - this node is a cross-community bridge._
- **Why does `TaskExecution` connect `TaskExecution` to `ElasticsearchTaskExecutionStorageIT`, `TaskExecutionStorageIT`, `KafkaLifecycleConsumer.java`, `com.fasterxml.jackson.databind.ObjectMapper`, `WorkflowInstance`, `WorkflowInstanceEntityMapper`, `org.slf4j.Logger`, `org.junit.jupiter.api.AfterEach`, `TaskExecutionProcessor`, `io.quarkus.test.junit.QuarkusTest`, `jakarta.transaction.Transactional`, `ElasticsearchTransformIntegrationTest.java`, `jakarta.enterprise.context.ApplicationScoped`?**
  _High betweenness centrality (0.048) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `WorkflowInstanceEntity` (e.g. with `.setupTestData()` and `.setupTestData()`) actually correct?**
  _`WorkflowInstanceEntity` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name` to the rest of the system?**
  _288 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `ElasticsearchTaskExecutionStorageIT` be split into smaller, more focused modules?**
  _Cohesion score 0.09623015873015874 - nodes in this community are weakly interconnected._
- **Should `OrderBy` be split into smaller, more focused modules?**
  _Cohesion score 0.07337662337662337 - nodes in this community are weakly interconnected._