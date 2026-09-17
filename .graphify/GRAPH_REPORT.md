# Graph Report - logic-apps  (2026-09-17)

## Corpus Check
- 214 files · ~131,352 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 51 file(s) not represented in the graph (top: .adoc 21, .properties 11, (none) 9)

## Summary
- 2057 nodes · 4700 edges · 137 communities (82 shown, 55 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 738 edges (avg confidence: 0.86)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `bd446d25`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- ElasticsearchTaskExecutionStorageIT
- OrderBy
- org.junit.jupiter.api.Test
- KafkaLifecycleConsumer
- kafka/service/RootResource.java
- MODE 3 (Kafka + Ingestion Service + PostgreSQL)
- BaseWorkflowLifecycleIT
- ElasticsearchSchemaInitializerTest
- Workflow
- WorkflowInstance
- TaskExecution
- StringFilter
- Data Index E2E Tests
- com.fasterxml.jackson.databind.ObjectMapper
- ElasticsearchTransformMetricsIT
- MODE 1 (PostgreSQL)
- FilterCondition
- ElasticsearchQuery
- .convert
- WorkflowInstanceStatus
- AttributeFilter
- io.quarkus.test.junit.QuarkusTest
- WorkflowInstanceEntity
- com.fasterxml.jackson.databind.JsonNode
- DataIndexE2ETest
- jakarta.persistence.criteria.CriteriaBuilder
- Claude AI Assistant Guidelines - KubeSmarts Logic Apps
- .createWorkflowDefinition
- Storage
- Error
- com.fasterxml.jackson.annotation.JsonProperty
- ADR 0002: Workflow Gateway Architecture
- DateTimeFilter
- TaskInstanceEntity
- full-test-mode1.sh
- full-test-mode2.sh
- full-test-mode3.sh
- BucketEnumDeserializer.java
- ElasticsearchTaskExecutionStorage.java
- ElasticsearchConfiguration
- ErrorEntity
- WorkflowInstanceEntityMapper
- WorkflowInstanceGraphQLApiTest
- IntFilter
- ContainsSQLFunction.java
- generate-slides.js
- /tmp/quarkus-flow-events.log
- FilterConverter
- Important Design Decisions
- verify-infrastructure.sh
- Workflow Test App Documentation
- KafkaLifecycleConsumer.java
- AttributeSort
- common-setup.sh
- generate-configmap.sh
- EventProcessorHealthCheck.java
- jakarta.persistence.EntityManager
- Query
- data-index-docs/package.json
- TaskInstanceEntity.java
- Storage Backend Architecture (Maven + Quarkus Profiles)
- BaseWorkflowLifecycleIT.java
- T2: Replay via FluentBit
- ElasticsearchTransformMetricsCollector.java
- VectorConfigValidationIT.java
- StorageServiceCapability
- COALESCE Idempotency Mechanism
- Presentation Slides Index
- PostgreSQL Normalized Tables
- Elasticsearch Normalized Indices
- HttpBinMockServer.java
- Mapper.java
- WorkflowInstanceGraphQLApi.java
- MODE 1 Scaling
- ElasticsearchSchemaInitializer.java
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
- AbstractJPAStorageFetcher.java
- ElasticsearchSchemaInitializerTest.java
- Architectural Decision Framework
- ElasticsearchSchemaInitializationIT.java
- Documentation Index
- ElasticsearchTransformIntegrationTest
- Helm Chart Documentation
- JPAQuery.java
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
- TaskExecutionStorageIT
- Reference Examples
- TestStatus
- ElasticsearchTaskExecutionStorage
- zoneddatetime
- Mapper
- StartupHealthIT.java
- data-index/CLAUDE.md

## God Nodes (most connected - your core abstractions)
1. `WorkflowInstance` - 80 edges
2. `TaskExecution` - 77 edges
3. `AttributeFilter` - 59 edges
4. `WorkflowInstanceEntity` - 58 edges
5. `TaskInstanceEntity` - 57 edges
6. `WorkflowInstanceStatus` - 37 edges
7. `OrderBy` - 33 edges
8. `StringFilter` - 33 edges
9. `ElasticsearchTaskExecutionStorageIT` - 32 edges
10. `Workflow` - 31 edges

## Surprising Connections (you probably didn't know these)
- `Elasticsearch Schema Management` --references--> `ElasticsearchSchemaInitializer`  [INFERRED]
  CLAUDE.md → data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/schema/ElasticsearchSchemaInitializer.java
- `Key Files Reference` --references--> `HealthChecks`  [INFERRED]
  CLAUDE.md → data-index/data-index-ingestion/data-index-ingestion-kafka-service/src/main/java/org/kubesmarts/logic/dataindex/ingestion/kafka/service/HealthChecks.java
- `Adding a New GraphQL Query` --references--> `WorkflowInstanceGraphQLApiTest`  [INFERRED]
  CLAUDE.md → data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java
- `Architecture (MODE 3 - Kafka)` --references--> `TaskExecution`  [INFERRED]
  CLAUDE.md → data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/TaskExecution.java
- `Knowledge Graph (graphify)` --references--> `TaskExecution`  [INFERRED]
  CLAUDE.md → data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/TaskExecution.java

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

## Communities (137 total, 55 thin omitted)

### Community 1 - "OrderBy"
Cohesion: 0.14
Nodes (5): OrderBy, ASC, DESC, TaskExecutionOrderBy, WorkflowInstanceOrderBy

### Community 2 - "org.junit.jupiter.api.Test"
Cohesion: 0.08
Nodes (5): BucketEnumDeserializerTest, BucketStringDeserializerTest, ElasticsearchSchemaInitializationIT, ElasticsearchTransformConfigurationIT, org.junit.jupiter.api.Test

### Community 3 - "KafkaLifecycleConsumer"
Cohesion: 0.21
Nodes (8): KafkaLifecycleConsumer, io.cloudevents.CloudEvent, io.serverlessworkflow.impl.lifecycle.ce.WorkflowCEData, io.smallrye.reactive.messaging.MutinyEmitter, org.apache.kafka.clients.consumer.ConsumerRecord, org.apache.kafka.clients.consumer.ConsumerRecords, org.eclipse.microprofile.reactive.messaging.Incoming, org.eclipse.microprofile.reactive.messaging.Message

### Community 4 - "kafka/service/RootResource.java"
Cohesion: 0.05
Nodes (29): RootResource, TestWorkflow, RootResource, PollingEventProcessor, EventMetrics, EventProcessorMetricsResource, EventProcessorMetricsResponse, FailingWorkflow (+21 more)

### Community 5 - "MODE 3 (Kafka + Ingestion Service + PostgreSQL)"
Cohesion: 0.07
Nodes (34): Data Index Ingestion Service, Data Index Service, Storage Common Module, Elasticsearch Storage Implementation, Flyway Database Migrations, PostgreSQL Storage Implementation, MODE 3 Kafka Deployment Guide, Elasticsearch Transform Optimization (+26 more)

### Community 6 - "BaseWorkflowLifecycleIT"
Cohesion: 0.10
Nodes (5): BaseWorkflowLifecycleIT, CancelledWorkflowIT, FaultedWorkflowIT, KafkaIngestionIT, SuspendedWorkflowIT

### Community 7 - "ElasticsearchSchemaInitializerTest"
Cohesion: 0.16
Nodes (8): co.elastic.clients.elasticsearch.ilm.ElasticsearchIlmClient, co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient, co.elastic.clients.elasticsearch.transform.ElasticsearchTransformClient, ElasticsearchSchemaInitializerTest, io.quarkus.runtime.StartupEvent, org.junit.jupiter.api.extension.ExtendWith, org.mockito.junit.jupiter.MockitoExtension, org.mockito.junit.jupiter.MockitoSettings

### Community 9 - "WorkflowInstance"
Cohesion: 0.05
Nodes (11): WorkflowInstanceStorage, Override, WorkflowInstance, ElasticsearchWorkflowInstanceStorage, Override, ElasticsearchSmartFilteringIT, ElasticsearchStorageIntegrationTest, ElasticsearchClient (+3 more)

### Community 10 - "TaskExecution"
Cohesion: 0.13
Nodes (6): TaskExecutionStorage, Override, TaskExecution, Override, TaskExecutionJPAStorage, io.serverlessworkflow.impl.lifecycle.ce.TaskCEData

### Community 12 - "Data Index E2E Tests"
Cohesion: 0.09
Nodes (30): ADR-0001: Migrate to Vector for OpenShift Alignment, Antora, CloudEvents, Data Index, data-index-docs, data-index-model, data-index-service, Data Index E2E Tests (+22 more)

### Community 13 - "com.fasterxml.jackson.databind.ObjectMapper"
Cohesion: 0.10
Nodes (24): com.fasterxml.jackson.databind.ObjectMapper, TaskPersistence, WorkflowPersistence, Override, TaskExecutionProcessor, WorkflowEventProcessor, Override, KafkaIngestionObjectMapperCustomizer (+16 more)

### Community 14 - "ElasticsearchTransformMetricsIT"
Cohesion: 0.06
Nodes (18): Override, Profile, ElasticsearchSchemaTestProfile, Override, CustomTimeWindowProfile, Override, ElasticsearchConfigurationValidationIT, InvalidRetentionProfile (+10 more)

### Community 15 - "MODE 1 (PostgreSQL)"
Cohesion: 0.08
Nodes (29): Data Consistency Models, GraphQL API, Latency Characteristics, MODE 1 (PostgreSQL), MODE 2 (Elasticsearch), Scaling Strategy, Data Index Service, Elasticsearch 8.11.1 (+21 more)

### Community 16 - "FilterCondition"
Cohesion: 0.08
Nodes (20): DataIndexAttributeFilter, TestAttributeFilter, FilterCondition, AND, BETWEEN, CONTAINS, CONTAINS_ALL, CONTAINS_ANY (+12 more)

### Community 17 - "ElasticsearchQuery"
Cohesion: 0.22
Nodes (5): Builder, co.elastic.clients.elasticsearch._types.FieldValue, co.elastic.clients.json.JsonData, ElasticsearchQuery, Override

### Community 18 - ".convert"
Cohesion: 0.17
Nodes (3): JsonFilter, TaskExecutionFilter, WorkflowInstanceFilter

### Community 19 - "WorkflowInstanceStatus"
Cohesion: 0.12
Nodes (11): fromV08State(), WorkflowInstanceStatus, CANCELLED, COMPLETED, FAULTED, PENDING, RUNNING, SUSPENDED (+3 more)

### Community 21 - "io.quarkus.test.junit.QuarkusTest"
Cohesion: 0.10
Nodes (36): arrays, assertthat, bulkoperation, bulkrequest, bulkresponse, co.elastic.clients.elasticsearch.ElasticsearchClient, containsstring, contenttype (+28 more)

### Community 22 - "WorkflowInstanceEntity"
Cohesion: 0.11
Nodes (3): Override, WorkflowInstanceEntity, org.mapstruct.AfterMapping

### Community 23 - "com.fasterxml.jackson.databind.JsonNode"
Cohesion: 0.17
Nodes (7): com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.node.ObjectNode, JsonUtils, Override, JsonBinaryConverter, jakarta.persistence.AttributeConverter, uncheckedioexception

### Community 24 - "DataIndexE2ETest"
Cohesion: 0.23
Nodes (4): DataIndexE2ETest, io.restassured.response.Response, org.junit.jupiter.api.BeforeAll, org.junit.jupiter.api.condition.EnabledIfSystemProperty

### Community 25 - "jakarta.persistence.criteria.CriteriaBuilder"
Cohesion: 0.27
Nodes (5): PostgresqlJsonPredicateBuilder, jakarta.persistence.criteria.CriteriaBuilder, jakarta.persistence.criteria.Expression, jakarta.persistence.criteria.Root, Override

### Community 26 - "Claude AI Assistant Guidelines - KubeSmarts Logic Apps"
Cohesion: 0.07
Nodes (27): Architecture (MODE 1 - Production), Architecture (MODE 2 - Elasticsearch), Architecture (MODE 3 - Kafka), Build & Deployment, Choosing Between MODE 1 and MODE 2, Claude AI Assistant Guidelines - KubeSmarts Logic Apps, Code Structure, ✅ Complete (Phase 1 - MODE 1) (+19 more)

### Community 28 - "Storage"
Cohesion: 0.17
Nodes (4): Dependencies, Minimal External Dependencies, Storage, StorageService

### Community 30 - "com.fasterxml.jackson.annotation.JsonProperty"
Cohesion: 0.15
Nodes (3): com.fasterxml.jackson.annotation.JsonProperty, TaskEventDoc, WorkflowEventDoc

### Community 31 - "ADR 0002: Workflow Gateway Architecture"
Cohesion: 0.05
Nodes (38): 1. Architecture, 1. Execution Response (`POST /v1/{namespace}/{name}/{version}`), 2. Lifecycle Events (CloudEvents), 2. Request Routing Strategy by Type, 3. Data-Index Integration, 4. Runtime Changes Required, 5. Operator Responsibilities, 6. Traffic Management Preserved (+30 more)

### Community 33 - "TaskInstanceEntity"
Cohesion: 0.08
Nodes (8): Build Issues, Deployment Issues (MODE 1 - PostgreSQL), Deployment Issues (MODE 2 - Elasticsearch), Troubleshooting, Override, TaskInstanceEntity, Override, TaskInstanceEntityId

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
Nodes (11): com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.BeanProperty, com.fasterxml.jackson.databind.deser.ContextualDeserializer, com.fasterxml.jackson.databind.DeserializationContext, com.fasterxml.jackson.databind.JsonDeserializer, BucketEnumDeserializer, Override, BucketStringDeserializer (+3 more)

### Community 38 - "ElasticsearchTaskExecutionStorage.java"
Cohesion: 0.19
Nodes (12): arraylist, boolquery, deleterequest, deleteresponse, hit, indexresponse, loggerfactory, querybuilders (+4 more)

### Community 39 - "ElasticsearchConfiguration"
Cohesion: 0.19
Nodes (9): configphase, Backend, ELASTICSEARCH, POSTGRESQL, StorageConfiguration, ElasticsearchConfiguration, io.quarkus.runtime.annotations.ConfigRoot, io.smallrye.config.ConfigMapping (+1 more)

### Community 40 - "ErrorEntity"
Cohesion: 0.09
Nodes (8): ❌ Architecture, ❌ Code, ❌ Dependencies, ❌ Elasticsearch Specific, ❌ Testing, What NOT to Do, ErrorEntity, Override

### Community 41 - "WorkflowInstanceEntityMapper"
Cohesion: 0.20
Nodes (8): 4. Entity Naming (MODE 1 - PostgreSQL), ErrorEntityMapper, TaskInstanceEntityMapper, WorkflowInstanceEntityMapper, injectionstrategy, mappingtarget, org.mapstruct.Mapper, org.mapstruct.Mapping

### Community 42 - "WorkflowInstanceGraphQLApiTest"
Cohesion: 0.11
Nodes (3): StartupHealthIT, TaskExecutionFilteringIT, WorkflowInstanceGraphQLApiTest

### Community 45 - "ContainsSQLFunction.java"
Cohesion: 0.12
Nodes (16): ContainsSQLFunction, Override, CustomFunctionsContributor, Override, iterator, org.hibernate.boot.model.FunctionContributions, org.hibernate.boot.model.FunctionContributor, org.hibernate.dialect.function.StandardSQLFunction (+8 more)

### Community 46 - "generate-slides.js"
Cohesion: 0.11
Nodes (18): fs, generateSlide(), main(), path, puppeteer, slides, dependencies, puppeteer (+10 more)

### Community 47 - "/tmp/quarkus-flow-events.log"
Cohesion: 0.21
Nodes (17): /var/log/containers/*.log, FluentBit DaemonSet, FluentBit DaemonSet Pod (Node 1), FluentBit DaemonSet Pod (Node 2), FluentBit DaemonSet Pod (Node N), Kubernetes Cluster, Kubernetes Node 1, Kubernetes Node 2 (+9 more)

### Community 48 - "FilterConverter"
Cohesion: 0.13
Nodes (7): Code Style & Conventions, Database (MODE 1 - PostgreSQL), Elasticsearch (MODE 2), GraphQL, Java Code, FilterConverter, JsonFieldFilter

### Community 49 - "Important Design Decisions"
Cohesion: 0.22
Nodes (9): 1. Trigger-Based Normalization (Not Polling), 1b. Transform-Based Normalization (MODE 2 - Elasticsearch), 1c. Task Instance Composite Key (Quarkus Flow ID Issue), 2. JSON Field Exposure (String Getters), 3. Field Names - Open Workflow Alignment, 5. Document Mapping (MODE 2 - Elasticsearch), Error Handling, Important Design Decisions (+1 more)

### Community 50 - "verify-infrastructure.sh"
Cohesion: 0.30
Nodes (14): BLUE, GREEN, log_error(), log_info(), log_success(), log_warn(), NC, RED (+6 more)

### Community 51 - "Workflow Test App Documentation"
Cohesion: 0.18
Nodes (12): Namespace Helm Template, Vector DaemonSet Template, Workflow Test App Template, Workflow Test App Documentation, Vector DaemonSet - MODE 2, Workflow Test App Deployment, HelloWorldWorkflow - Test Workflow, Logging Namespace (+4 more)

### Community 52 - "KafkaLifecycleConsumer.java"
Cohesion: 0.11
Nodes (14): bytescloudeventdata, channel, cloudeventbuilder, cloudeventdata, completablefuture, completionstage, EventProcessor, ProcessEventFailedException (+6 more)

### Community 53 - "AttributeSort"
Cohesion: 0.17
Nodes (7): DataIndexAttributeSort, OrderByConverter, AttributeSort, Override, SortDirection, ASC, DESC

### Community 54 - "common-setup.sh"
Cohesion: 0.20
Nodes (13): BLUE, CLUSTER_NAME, GREEN, log_error(), log_info(), log_step(), log_success(), main() (+5 more)

### Community 55 - "generate-configmap.sh"
Cohesion: 0.24
Nodes (11): error(), info(), deploy-fluentbit.sh script, step(), error(), generate_configmap(), info(), generate-configmap.sh script (+3 more)

### Community 56 - "EventProcessorHealthCheck.java"
Cohesion: 0.21
Nodes (11): Key Files Reference, HealthChecks, EventProcessorHealthCheck, Override, healthcheckresponsebuilder, io.smallrye.health.api.Wellness, jakarta.enterprise.inject.Instance, org.eclipse.microprofile.health.HealthCheck (+3 more)

### Community 57 - "jakarta.persistence.EntityManager"
Cohesion: 0.13
Nodes (8): AbstractJPAStorageFetcher, Override, AbstractStorage, Override, DependencyInjectionUtils, JsonPredicateBuilder, jakarta.persistence.EntityManager, jakarta.transaction.Transactional

### Community 58 - "Query"
Cohesion: 0.27
Nodes (3): Override, JPAQuery, Query

### Community 59 - "data-index-docs/package.json"
Cohesion: 0.17
Nodes (11): dependencies, @antora/cli, @antora/site-generator, description, name, private, scripts, build (+3 more)

### Community 60 - "TaskInstanceEntity.java"
Cohesion: 0.11
Nodes (18): cascadetype, column, embedded, embeddedid, enumerated, enumtype, foreignkey, id (+10 more)

### Community 61 - "Storage Backend Architecture (Maven + Quarkus Profiles)"
Cohesion: 0.25
Nodes (8): Configuration files:, Development:, Elasticsearch Schema Management, How it works:, Maven profiles (in data-index-service/pom.xml):, Storage Backend Architecture (Maven + Quarkus Profiles), What happens (Elasticsearch):, What happens (PostgreSQL):

### Community 62 - "BaseWorkflowLifecycleIT.java"
Cohesion: 0.16
Nodes (20): assertions, await, awaitility, chronounit, consumerrecord, java.sql.Connection, kafkaconsumer, offsetdatetime (+12 more)

### Community 63 - "T2: Replay via FluentBit"
Cohesion: 0.23
Nodes (12): Log Replay Scenario Diagram, FluentBit, Idempotency, /var/log/containers/, MODE 1: UPSERT with COALESCE, MODE 2: Transform aggregation, PostgreSQL/Elasticsearch Database, T0: Original Events (+4 more)

### Community 64 - "ElasticsearchTransformMetricsCollector.java"
Cohesion: 0.08
Nodes (24): atomiclong, co.elastic.clients.transport.rest5_client.low_level.Rest5Client, concurrenthashmap, configproperty, EventProcessorMetrics, StartupEvent, TestElasticsearchClientProducer, ElasticsearchTransformMetricsCollector (+16 more)

### Community 65 - "VectorConfigValidationIT.java"
Cohesion: 0.14
Nodes (14): assumethat, com.github.dockerjava.api.DockerClient, Override, VectorConfigValidationIT, WaitForContainerExitStrategy, dockerstatus, files, genericcontainer (+6 more)

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

### Community 71 - "HttpBinMockServer.java"
Cohesion: 0.31
Nodes (6): com.github.tomakehurst.wiremock.WireMockServer, HttpBinMockServer, Override, io.quarkus.test.common.QuarkusTestResourceLifecycleManager, options, wiremock

### Community 72 - "Mapper.java"
Cohesion: 0.13
Nodes (20): cloudevent, lifecycleevents, taskcancelledcedata, taskcompletedcedata, taskcompletedcedatawithoutput, taskfailedcedata, taskresumedcedata, taskretriedcedata (+12 more)

### Community 73 - "WorkflowInstanceGraphQLApi.java"
Cohesion: 0.19
Nodes (6): WorkflowInstanceGraphQLApi, name, nonnull, org.eclipse.microprofile.graphql.Description, org.eclipse.microprofile.graphql.GraphQLApi, org.eclipse.microprofile.graphql.Query

### Community 74 - "MODE 1 Scaling"
Cohesion: 0.29
Nodes (8): Horizontal Scaling, MODE 1 Capacity, MODE 1 Scaling, MODE 2 Capacity, MODE 2 Scaling, Read Replicas, Sharding, Vertical Scaling

### Community 75 - "ElasticsearchSchemaInitializer.java"
Cohesion: 0.11
Nodes (15): assertequals, assertnotnull, asserttrue, bufferedreader, bytearrayinputstream, collectors, LoadSQL, TransformFieldMappingTest (+7 more)

### Community 77 - "FluentBit DaemonSet"
Cohesion: 0.43
Nodes (7): MODE 2: Elasticsearch, FluentBit DaemonSet, GraphQL API, MODE 1: PostgreSQL, Quarkus Flow App, Storage Backend, User

### Community 78 - "Persistence Abstraction Layer"
Cohesion: 0.29
Nodes (7): Persistence Abstraction Layer, Infinispan Storage Backend, Protobuf Schema Management, Query Interface, Redis Storage Backend, Storage Interface, StorageService

### Community 79 - "Kafka Helm Template"
Cohesion: 0.33
Nodes (6): Kafka Helm Template, Kafka Scripts - MODE 3, Kafka Headless Service, Kafka StatefulSet - KRaft Mode, flow-lifecycle-out Topic, MODE 3 - Kafka Ingestion Architecture

### Community 81 - "Red Hat EFK Stack Integration"
Cohesion: 0.40
Nodes (6): Red Hat EFK Stack Integration, FluentBit DaemonSet, Fluentd DaemonSet, GraphQL API (Data Index), PostgreSQL / Elasticsearch, Red Hat EFK Elasticsearch

### Community 82 - "Common Tasks"
Cohesion: 0.29
Nodes (7): Adding a Database Field (MODE 1 - PostgreSQL), Adding a New GraphQL Query, Adding an Elasticsearch Field (MODE 2), Common Tasks, Querying Elasticsearch Directly, Testing with Elasticsearch, Updating Documentation

### Community 85 - "FluentBit Configurations Overview"
Cohesion: 0.67
Nodes (3): FluentBit Configurations Overview, FluentBit MODE 1 - PostgreSQL, FluentBit MODE 2 - Elasticsearch

### Community 91 - "AbstractJPAStorageFetcher.java"
Cohesion: 0.15
Nodes (6): AbstractEntity, entity, enumset, function, multi, StorageFetcher

### Community 92 - "ElasticsearchSchemaInitializerTest.java"
Cohesion: 0.13
Nodes (14): any, assertthatthrownby, elasticsearchexception, errorresponse, injectmocks, mock, mockito, putindextemplaterequest (+6 more)

### Community 94 - "ElasticsearchSchemaInitializationIT.java"
Cohesion: 0.13
Nodes (14): getindextemplaterequest, getindextemplateresponse, getlifecyclerequest, getlifecycleresponse, gettransformrequest, gettransformresponse, ilmpolicy, indextemplate (+6 more)

### Community 98 - "JPAQuery.java"
Cohesion: 0.20
Nodes (9): attribute, collection, jakarta.persistence.criteria.CriteriaQuery, join, order, persistenceexception, predicate, stream (+1 more)

### Community 130 - "Reference Examples"
Cohesion: 0.33
Nodes (5): For Operator Developers, Manual Deployment, NOT For Embedding, Purpose, Reference Examples

### Community 131 - "TestStatus"
Cohesion: 0.33
Nodes (6): TestStatus, CANCELLED, COMPLETED, FAULTED, RUNNING, SUSPENDED

### Community 133 - "zoneddatetime"
Cohesion: 0.29
Nodes (5): ignore, jsondeserialize, jsonprocessingexception, objects, zoneddatetime

### Community 134 - "Mapper"
Cohesion: 0.22
Nodes (3): Mapper, LifecycleEventUtils, io.serverlessworkflow.impl.WorkflowError

### Community 135 - "StartupHealthIT.java"
Cohesion: 0.33
Nodes (4): WorkflowExecutionTest, io.quarkus.test.common.QuarkusTestResource, matchers, restassured

## Knowledge Gaps
- **296 isolated node(s):** `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name`, `version`, `description` (+291 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 581 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **55 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `WorkflowInstance` connect `WorkflowInstance` to `KafkaLifecycleConsumer`, `zoneddatetime`, `ElasticsearchTaskExecutionStorage.java`, `Mapper.java`, `Workflow`, `TaskExecution`, `WorkflowInstanceGraphQLApi.java`, `WorkflowInstanceEntityMapper`, `com.fasterxml.jackson.databind.ObjectMapper`, `WorkflowInstanceStatus`, `KafkaLifecycleConsumer.java`, `io.quarkus.test.junit.QuarkusTest`, `com.fasterxml.jackson.databind.JsonNode`, `Claude AI Assistant Guidelines - KubeSmarts Logic Apps`, `com.fasterxml.jackson.annotation.JsonProperty`, `ADR 0002: Workflow Gateway Architecture`?**
  _High betweenness centrality (0.129) - this node is a cross-community bridge._
- **Why does `WorkflowInstanceEntity` connect `WorkflowInstanceEntity` to `TaskInstanceEntity`, `ErrorEntity`, `WorkflowInstanceEntityMapper`, `WorkflowInstance`, `.setupTestData`, `ContainsSQLFunction.java`, `com.fasterxml.jackson.databind.ObjectMapper`, `WorkflowInstanceStatus`, `io.quarkus.test.junit.QuarkusTest`, `com.fasterxml.jackson.databind.JsonNode`, `Claude AI Assistant Guidelines - KubeSmarts Logic Apps`, `AbstractJPAStorageFetcher.java`, `TaskInstanceEntity.java`, `ADR 0002: Workflow Gateway Architecture`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **Why does `TaskExecution` connect `TaskExecution` to `ElasticsearchTaskExecutionStorageIT`, `TaskExecutionStorageIT`, `ElasticsearchTransformIntegrationTest`, `ElasticsearchTaskExecutionStorage`, `zoneddatetime`, `ElasticsearchTaskExecutionStorage.java`, `Mapper.java`, `WorkflowInstance`, `WorkflowInstanceGraphQLApi.java`, `WorkflowInstanceEntityMapper`, `.get`, `com.fasterxml.jackson.databind.ObjectMapper`, `KafkaLifecycleConsumer.java`, `io.quarkus.test.junit.QuarkusTest`, `com.fasterxml.jackson.databind.JsonNode`, `Claude AI Assistant Guidelines - KubeSmarts Logic Apps`, `com.fasterxml.jackson.annotation.JsonProperty`?**
  _High betweenness centrality (0.051) - this node is a cross-community bridge._
- **Are the 3 inferred relationships involving `WorkflowInstance` (e.g. with `Workflow Application ID Observability` and `Architecture (MODE 3 - Kafka)`) actually correct?**
  _`WorkflowInstance` has 3 INFERRED edges - model-reasoned connections that need verification._
- **Are the 2 inferred relationships involving `TaskExecution` (e.g. with `Architecture (MODE 3 - Kafka)` and `Knowledge Graph (graphify)`) actually correct?**
  _`TaskExecution` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `WorkflowInstanceEntity` (e.g. with `Workflow Application ID Observability` and `4. Entity Naming (MODE 1 - PostgreSQL)`) actually correct?**
  _`WorkflowInstanceEntity` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name` to the rest of the system?**
  _296 weakly-connected nodes found - possible documentation gaps or missing edges._