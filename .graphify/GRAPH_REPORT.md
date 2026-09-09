# Graph Report - logic-apps  (2026-09-09)

## Corpus Check
- 272 files · ~126,512 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1743 nodes · 3949 edges · 129 communities (64 shown, 60 thin omitted)
- Extraction: 85% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 561 edges (avg confidence: 0.81)
- Token cost: 576,976 input · 0 output

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 44
- Community 45
- Community 46
- Community 47
- Community 48
- Community 49
- Community 50
- Community 51
- Community 52
- Community 53
- Community 54
- Community 55
- Community 56
- Community 57
- Community 58
- Community 59
- Community 60
- Community 61
- Community 62
- Community 63
- Community 64
- Community 65
- Community 66
- Community 67
- Community 68
- Community 69
- Community 70
- Community 71
- Community 72
- Community 73
- Community 74
- Community 75
- Community 76
- Community 77
- Community 78
- Community 79
- Community 81
- Community 82
- Community 83
- Community 84
- Community 85
- Community 86
- Community 87
- Community 88
- Community 89
- Community 90
- Community 93
- Community 94
- Community 95
- Community 96
- Community 97
- Community 98
- Community 99
- Community 100
- Community 102
- Community 103
- Community 104
- Community 105
- Community 106
- Community 107
- Community 108
- Community 109
- Community 110
- Community 111
- Community 112
- Community 113
- Community 114
- Community 115
- Community 116
- Community 117
- Community 118
- Community 119
- Community 120
- Community 121
- Community 122
- Community 123
- Community 124
- Community 125
- Community 126
- Community 127
- Community 128

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
- `TestAttributeFilter` --inherits--> `AttributeFilter`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/storage/elasticsearch/TestAttributeFilter.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/query/AttributeFilter.java
- `AbstractJPAStorageFetcher` --implements--> `StorageFetcher`  [EXTRACTED]
  data-index/data-index-storage/data-index-storage-postgresql/src/main/java/org/kubesmarts/logic/dataindex/storage/jpa/AbstractJPAStorageFetcher.java → persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/StorageFetcher.java

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

## Communities (129 total, 60 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.09
Nodes (5): Override, TaskExecution, ElasticsearchTaskExecutionStorage, Override, ElasticsearchTaskExecutionStorageIT

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (14): TaskExecutionStorage, WorkflowInstanceStorage, OrderBy, ASC, DESC, OrderByConverter, TaskExecutionOrderBy, WorkflowInstanceOrderBy (+6 more)

### Community 2 - "Community 2"
Cohesion: 0.06
Nodes (10): BucketEnumDeserializerTest, TestStatus, CANCELLED, COMPLETED, FAULTED, RUNNING, SUSPENDED, BucketStringDeserializerTest (+2 more)

### Community 3 - "Community 3"
Cohesion: 0.07
Nodes (15): ProcessEventFailedException, KafkaLifecycleConsumer, Mapper, Error, Override, LifecycleEventUtils, io.cloudevents.CloudEvent, io.serverlessworkflow.impl.lifecycle.ce.TaskCEData (+7 more)

### Community 4 - "Community 4"
Cohesion: 0.09
Nodes (14): RootResource, RootResource, EventMetrics, EventProcessorMetricsResource, EventProcessorMetricsResponse, WorkflowTestResource, io.quarkus.qute.Template, io.smallrye.health.SmallRyeHealthReporter (+6 more)

### Community 5 - "Community 5"
Cohesion: 0.06
Nodes (39): Data Index Ingestion Service, Data Index Service, Storage Common Module, Elasticsearch Storage Implementation, Flyway Database Migrations, PostgreSQL Storage Implementation, MODE 2 E2E Testing Guide, MODE 3 Kafka Deployment Guide (+31 more)

### Community 7 - "Community 7"
Cohesion: 0.12
Nodes (8): co.elastic.clients.elasticsearch.ilm.ElasticsearchIlmClient, co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient, co.elastic.clients.elasticsearch.transform.ElasticsearchTransformClient, ElasticsearchSchemaInitializer, ElasticsearchSchemaInitializerTest, org.junit.jupiter.api.extension.ExtendWith, org.mockito.junit.jupiter.MockitoExtension, org.mockito.junit.jupiter.MockitoSettings

### Community 9 - "Community 9"
Cohesion: 0.16
Nodes (3): ElasticsearchWorkflowInstanceStorage, Override, ElasticsearchWorkflowInstanceStorageIT

### Community 12 - "Community 12"
Cohesion: 0.09
Nodes (30): ADR-0001: Migrate to Vector for OpenShift Alignment, Antora, CloudEvents, Data Index, data-index-docs, data-index-model, data-index-service, Data Index E2E Tests (+22 more)

### Community 13 - "Community 13"
Cohesion: 0.17
Nodes (12): EventProcessor, TaskPersistence, WorkflowPersistence, Override, TaskExecutionProcessor, WorkflowEventProcessor, io.quarkus.arc.Unremovable, jakarta.inject.Inject (+4 more)

### Community 14 - "Community 14"
Cohesion: 0.09
Nodes (16): Override, Profile, ElasticsearchSchemaTestProfile, Override, CustomTimeWindowProfile, Override, ElasticsearchConfigurationValidationIT, InvalidRetentionProfile (+8 more)

### Community 15 - "Community 15"
Cohesion: 0.08
Nodes (29): Data Consistency Models, GraphQL API, Latency Characteristics, MODE 1 (PostgreSQL), MODE 2 (Elasticsearch), Scaling Strategy, Data Index Service, Elasticsearch 8.11.1 (+21 more)

### Community 16 - "Community 16"
Cohesion: 0.08
Nodes (20): DataIndexAttributeFilter, TestAttributeFilter, FilterCondition, AND, BETWEEN, CONTAINS, CONTAINS_ALL, CONTAINS_ANY (+12 more)

### Community 17 - "Community 17"
Cohesion: 0.14
Nodes (7): Builder, co.elastic.clients.elasticsearch._types.FieldValue, co.elastic.clients.json.JsonData, ElasticsearchQuery, Override, Query, StorageFetcher

### Community 19 - "Community 19"
Cohesion: 0.14
Nodes (12): fromV08State(), WorkflowInstanceStatus, CANCELLED, COMPLETED, FAULTED, PENDING, RUNNING, SUSPENDED (+4 more)

### Community 21 - "Community 21"
Cohesion: 0.17
Nodes (11): co.elastic.clients.elasticsearch.ElasticsearchClient, CancelledWorkflowIT, FaultedWorkflowIT, QuarkusFlowLifecycleIT, SuspendedWorkflowIT, ElasticsearchDevServicesTest, ElasticsearchTransformConfigurationIT, io.micrometer.core.instrument.MeterRegistry (+3 more)

### Community 22 - "Community 22"
Cohesion: 0.10
Nodes (4): AbstractEntity, Override, WorkflowInstanceEntity, WorkflowInstanceJPAStorage

### Community 23 - "Community 23"
Cohesion: 0.17
Nodes (6): com.fasterxml.jackson.databind.JsonNode, com.fasterxml.jackson.databind.node.ObjectNode, JsonUtils, Override, JsonBinaryConverter, jakarta.persistence.AttributeConverter

### Community 24 - "Community 24"
Cohesion: 0.16
Nodes (5): VectorConfigValidationIT, DataIndexE2ETest, io.restassured.response.Response, org.junit.jupiter.api.BeforeAll, org.junit.jupiter.api.condition.EnabledIfSystemProperty

### Community 25 - "Community 25"
Cohesion: 0.24
Nodes (6): JsonPredicateBuilder, PostgresqlJsonPredicateBuilder, jakarta.persistence.criteria.CriteriaBuilder, jakarta.persistence.criteria.Expression, jakarta.persistence.criteria.Root, Override

### Community 27 - "Community 27"
Cohesion: 0.14
Nodes (4): BatchProcessingIT, KafkaConsumer, BinaryCloudEventExtractionIT, KafkaProducer

### Community 28 - "Community 28"
Cohesion: 0.18
Nodes (7): AbstractJPAStorageFetcher, Override, AbstractStorage, Override, jakarta.persistence.EntityManager, jakarta.transaction.Transactional, org.junit.jupiter.api.AfterEach

### Community 29 - "Community 29"
Cohesion: 0.17
Nodes (6): DataIndexAttributeSort, AttributeSort, Override, SortDirection, ASC, DESC

### Community 30 - "Community 30"
Cohesion: 0.13
Nodes (4): com.fasterxml.jackson.annotation.JsonProperty, TaskEventDoc, TransformFieldMappingTest, WorkflowEventDoc

### Community 31 - "Community 31"
Cohesion: 0.18
Nodes (11): TestWorkflow, GraphQLConfiguration, FailingWorkflow, Override, HelloWorldWorkflow, Override, Override, SimpleSetWorkflow (+3 more)

### Community 34 - "Community 34"
Cohesion: 0.14
Nodes (18): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_info(), log_step(), log_success() (+10 more)

### Community 35 - "Community 35"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 36 - "Community 36"
Cohesion: 0.13
Nodes (17): BLUE, cleanup_cluster(), CLUSTER_NAME, GREEN, HELM_CHART_DIR, log_step(), log_success(), main() (+9 more)

### Community 37 - "Community 37"
Cohesion: 0.22
Nodes (11): com.fasterxml.jackson.core.JsonParser, com.fasterxml.jackson.databind.BeanProperty, com.fasterxml.jackson.databind.deser.ContextualDeserializer, com.fasterxml.jackson.databind.DeserializationContext, com.fasterxml.jackson.databind.JsonDeserializer, BucketEnumDeserializer, Override, BucketStringDeserializer (+3 more)

### Community 38 - "Community 38"
Cohesion: 0.20
Nodes (6): com.fasterxml.jackson.databind.ObjectMapper, ObjectMapperProducer, java.sql.Connection, org.apache.kafka.clients.consumer.KafkaConsumer, org.apache.kafka.clients.producer.KafkaProducer, org.junit.jupiter.api.BeforeEach

### Community 39 - "Community 39"
Cohesion: 0.20
Nodes (8): Backend, ELASTICSEARCH, POSTGRESQL, StorageConfiguration, ElasticsearchConfiguration, io.quarkus.runtime.annotations.ConfigRoot, io.smallrye.config.ConfigMapping, io.smallrye.config.WithDefault

### Community 41 - "Community 41"
Cohesion: 0.19
Nodes (6): ErrorEntityMapper, TaskInstanceEntityMapper, WorkflowInstanceEntityMapper, org.mapstruct.AfterMapping, org.mapstruct.Mapper, org.mapstruct.Mapping

### Community 42 - "Community 42"
Cohesion: 0.17
Nodes (4): StartupHealthIT, TaskExecutionFilteringIT, WorkflowExecutionTest, io.quarkus.test.common.QuarkusTestResource

### Community 45 - "Community 45"
Cohesion: 0.16
Nodes (12): ContainsSQLFunction, Override, CustomFunctionsContributor, Override, org.hibernate.boot.model.FunctionContributions, org.hibernate.boot.model.FunctionContributor, org.hibernate.dialect.function.StandardSQLFunction, org.hibernate.metamodel.model.domain.ReturnableType (+4 more)

### Community 46 - "Community 46"
Cohesion: 0.12
Nodes (16): fs, generateSlide(), main(), path, puppeteer, slides, dependencies, puppeteer (+8 more)

### Community 47 - "Community 47"
Cohesion: 0.21
Nodes (17): /var/log/containers/*.log, FluentBit DaemonSet, FluentBit DaemonSet Pod (Node 1), FluentBit DaemonSet Pod (Node 2), FluentBit DaemonSet Pod (Node N), Kubernetes Cluster, Kubernetes Node 1, Kubernetes Node 2 (+9 more)

### Community 48 - "Community 48"
Cohesion: 0.18
Nodes (3): JsonFieldFilter, JsonFilter, TaskExecutionFilter

### Community 50 - "Community 50"
Cohesion: 0.30
Nodes (14): BLUE, GREEN, log_error(), log_info(), log_success(), log_warn(), NC, RED (+6 more)

### Community 51 - "Community 51"
Cohesion: 0.15
Nodes (14): Namespace Helm Template, Vector DaemonSet Template, Workflow Test App Template, FluentBit DaemonSet - MODE 1, Workflow Test App Documentation, Vector DaemonSet - MODE 2, Workflow Test App Deployment, FluentBit DaemonSet - PostgreSQL Mode (+6 more)

### Community 52 - "Community 52"
Cohesion: 0.27
Nodes (8): HealthChecks, EventProcessorHealthCheck, Override, io.smallrye.health.api.Wellness, org.eclipse.microprofile.health.HealthCheck, org.eclipse.microprofile.health.HealthCheckResponse, org.eclipse.microprofile.health.Liveness, org.eclipse.microprofile.health.Readiness

### Community 54 - "Community 54"
Cohesion: 0.20
Nodes (13): BLUE, CLUSTER_NAME, GREEN, log_error(), log_info(), log_step(), log_success(), main() (+5 more)

### Community 55 - "Community 55"
Cohesion: 0.24
Nodes (11): error(), info(), deploy-fluentbit.sh script, step(), error(), generate_configmap(), info(), generate-configmap.sh script (+3 more)

### Community 57 - "Community 57"
Cohesion: 0.21
Nodes (4): DependencyInjectionUtils, Override, TaskExecutionJPAStorage, jakarta.enterprise.inject.Instance

### Community 58 - "Community 58"
Cohesion: 0.26
Nodes (3): Override, JPAQuery, jakarta.persistence.criteria.CriteriaQuery

### Community 59 - "Community 59"
Cohesion: 0.17
Nodes (11): dependencies, @antora/cli, @antora/site-generator, description, name, private, scripts, build (+3 more)

### Community 61 - "Community 61"
Cohesion: 0.20
Nodes (4): PollingEventProcessor, EventProcessorMetrics, StartupEvent, io.quarkus.scheduler.Scheduled

### Community 62 - "Community 62"
Cohesion: 0.20
Nodes (3): Override, TaskInstanceEntityId, jakarta.persistence.Embeddable

### Community 63 - "Community 63"
Cohesion: 0.23
Nodes (12): Log Replay Scenario Diagram, FluentBit, Idempotency, /var/log/containers/, MODE 1: UPSERT with COALESCE, MODE 2: Transform aggregation, PostgreSQL/Elasticsearch Database, T0: Original Events (+4 more)

### Community 64 - "Community 64"
Cohesion: 0.31
Nodes (4): co.elastic.clients.transport.rest5_client.low_level.Rest5Client, ElasticsearchTransformMetricsCollector, io.quarkus.runtime.Startup, io.quarkus.runtime.StartupEvent

### Community 66 - "Community 66"
Cohesion: 0.24
Nodes (6): Override, PostgresqlStorageServiceCapabilities, StorageServiceCapability, COUNT, JSON_QUERY, StorageServiceCapabilityProvider

### Community 67 - "Community 67"
Cohesion: 0.22
Nodes (11): COALESCE Idempotency Mechanism, Final Database State, Event 1 (COMPLETED), Event 2 (RUNNING), Event 3 (CREATED), Immutable Fields, Out-of-Order Event Processing, PostgreSQL Trigger (+3 more)

### Community 68 - "Community 68"
Cohesion: 0.20
Nodes (10): Data Index POC Presentation, Title Slide - Data Index POC, Migration Context Slide, Event Flow Architecture Slide, MODE 1 Architecture Slide, MODE 1 Trigger Logic Slide, MODE 2 Architecture Slide, MODE 2 Transform Logic Slide (+2 more)

### Community 69 - "Community 69"
Cohesion: 0.28
Nodes (9): FluentBit, GraphQL API, PostgreSQL Normalized Tables, PostgreSQL Raw Tables, task_events_raw, task_instances, BEFORE INSERT Trigger, workflow_events_raw (+1 more)

### Community 70 - "Community 70"
Cohesion: 0.25
Nodes (9): FluentBit, GraphQL API, Elasticsearch Normalized Indices, Elasticsearch Raw Indices, task-events, task-executions, Elasticsearch Transform, workflow-events (+1 more)

### Community 71 - "Community 71"
Cohesion: 0.36
Nodes (5): com.github.tomakehurst.wiremock.WireMockServer, HttpBinMockServer, Override, io.quarkus.test.common.QuarkusTestResourceLifecycleManager, WireMockServer

### Community 74 - "Community 74"
Cohesion: 0.29
Nodes (8): Horizontal Scaling, MODE 1 Capacity, MODE 1 Scaling, MODE 2 Capacity, MODE 2 Scaling, Read Replicas, Sharding, Vertical Scaling

### Community 76 - "Community 76"
Cohesion: 0.38
Nodes (4): ElasticsearchClient, TestElasticsearchClientProducer, io.quarkus.arc.DefaultBean, jakarta.enterprise.inject.Produces

### Community 77 - "Community 77"
Cohesion: 0.43
Nodes (7): MODE 2: Elasticsearch, FluentBit DaemonSet, GraphQL API, MODE 1: PostgreSQL, Quarkus Flow App, Storage Backend, User

### Community 78 - "Community 78"
Cohesion: 0.29
Nodes (7): Persistence Abstraction Layer, Infinispan Storage Backend, Protobuf Schema Management, Query Interface, Redis Storage Backend, Storage Interface, StorageService

### Community 79 - "Community 79"
Cohesion: 0.33
Nodes (6): Kafka Helm Template, Kafka Scripts - MODE 3, Kafka Headless Service, Kafka StatefulSet - KRaft Mode, flow-lifecycle-out Topic, MODE 3 - Kafka Ingestion Architecture

### Community 81 - "Community 81"
Cohesion: 0.40
Nodes (6): Red Hat EFK Stack Integration, FluentBit DaemonSet, Fluentd DaemonSet, GraphQL API (Data Index), PostgreSQL / Elasticsearch, Red Hat EFK Elasticsearch

### Community 82 - "Community 82"
Cohesion: 0.50
Nodes (3): Override, KafkaIngestionObjectMapperCustomizer, io.quarkus.jackson.ObjectMapperCustomizer

### Community 84 - "Community 84"
Cohesion: 0.50
Nodes (4): PostgreSQL Mode - FluentBit, normalize_task_event() Function, normalize_workflow_event() Function, PostgreSQL Trigger-Based Normalization

### Community 85 - "Community 85"
Cohesion: 0.67
Nodes (3): FluentBit Configurations Overview, FluentBit MODE 1 - PostgreSQL, FluentBit MODE 2 - Elasticsearch

## Knowledge Gaps
- **225 isolated node(s):** `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name`, `version`, `description` (+220 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 407 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **60 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `WorkflowInstance` connect `Community 26` to `Community 0`, `Community 1`, `Community 3`, `Community 38`, `Community 8`, `Community 9`, `Community 10`, `Community 41`, `Community 13`, `Community 19`, `Community 53`, `Community 22`, `Community 23`, `Community 60`, `Community 30`?**
  _High betweenness centrality (0.111) - this node is a cross-community bridge._
- **Why does `AttributeFilter` connect `Community 20` to `Community 32`, `Community 1`, `Community 42`, `Community 11`, `Community 44`, `Community 16`, `Community 48`, `Community 18`, `Community 17`, `Community 23`, `Community 56`, `Community 25`, `Community 58`, `Community 29`?**
  _High betweenness centrality (0.074) - this node is a cross-community bridge._
- **Why does `TaskExecution` connect `Community 0` to `Community 1`, `Community 65`, `Community 3`, `Community 38`, `Community 41`, `Community 13`, `Community 19`, `Community 23`, `Community 57`, `Community 26`, `Community 30`?**
  _High betweenness centrality (0.052) - this node is a cross-community bridge._
- **Are the 4 inferred relationships involving `WorkflowInstanceEntity` (e.g. with `.setupTestData()` and `.setupTestData()`) actually correct?**
  _`WorkflowInstanceEntity` has 4 INFERRED edges - model-reasoned connections that need verification._
- **What connects `github.com/kubesmarts/logic-apps/data-index/collectors`, `data-index-collectors`, `name` to the rest of the system?**
  _225 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.08885597926693817 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.05839727195225917 - nodes in this community are weakly interconnected._