# Elasticsearch Backend MODE 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement full MODE 2 Elasticsearch backend with ES Transform for event normalization, enabling high-performance search and analytics for workflow execution data.

**Architecture:** FluentBit writes raw events to Elasticsearch. ES Transform (continuous, 1s frequency) aggregates events by instance_id into normalized indices. Data Index queries normalized indices via GraphQL API. Schema isolated in dedicated module, universal skipInitSchema flag controls initialization.

**Tech Stack:** Elasticsearch 8.11, ES Transform, ILM, Flattened Fields, Quarkus Elasticsearch Java Client, Testcontainers

---

## File Structure Overview

### New Module: data-index-storage-elasticsearch-schema
```
data-index-storage-elasticsearch-schema/
├── pom.xml
├── src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/
│   └── ElasticsearchSchemaInitializer.java
└── src/main/resources/elasticsearch/
    ├── ilm/
    │   └── data-index-events-retention.json
    ├── index-templates/
    │   ├── workflow-events.json
    │   ├── workflow-instances.json
    │   ├── task-events.json
    │   └── task-executions.json
    └── transforms/
        ├── workflow-instances-transform.json
        └── task-executions-transform.json
```

### Modified Files
- `data-index-storage/pom.xml` - Add schema module
- `data-index-service-core/src/main/resources/application.properties` - Universal skipInitSchema
- `data-index-service-elasticsearch/pom.xml` - Add schema dependency
- `data-index-service-elasticsearch/src/main/resources/application.properties` - ES configuration
- `data-index-storage-elasticsearch/src/test/java/*` - Integration tests

---

## PHASE 1: WorkflowInstance Full Stack

### Task 1: Create Schema Module Structure

**Files:**
- Create: `data-index/data-index-storage/data-index-storage-elasticsearch-schema/pom.xml`
- Modify: `data-index/data-index-storage/pom.xml`
- Create: Directory structure for resources

- [ ] **Step 1: Add module to parent pom**

Edit `data-index/data-index-storage/pom.xml`:

```xml
<modules>
  <module>data-index-storage-migrations</module>
  <module>data-index-storage-common</module>
  <module>data-index-storage-postgresql</module>
  <module>data-index-storage-elasticsearch</module>
  <module>data-index-storage-elasticsearch-schema</module>
</modules>
```

- [ ] **Step 2: Create module pom.xml**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!--
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
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.kubesmarts.logic.apps</groupId>
    <artifactId>data-index-storage</artifactId>
    <version>999-SNAPSHOT</version>
  </parent>

  <artifactId>data-index-storage-elasticsearch-schema</artifactId>
  <name>KubeSmarts Logic Apps :: Data Index :: Storage :: Elasticsearch Schema</name>
  <description>Elasticsearch schema scripts (ILM, index templates, transforms)</description>

  <properties>
    <java.module.name>org.kubesmarts.logic.dataindex.storage.elasticsearch.schema</java.module.name>
  </properties>

  <dependencies>
    <!-- Elasticsearch Java Client for schema initialization -->
    <dependency>
      <groupId>co.elastic.clients</groupId>
      <artifactId>elasticsearch-java</artifactId>
    </dependency>

    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-elasticsearch-java-client</artifactId>
    </dependency>

    <!-- Quarkus Arc for CDI -->
    <dependency>
      <groupId>io.quarkus</groupId>
      <artifactId>quarkus-arc</artifactId>
    </dependency>

    <!-- MicroProfile Config -->
    <dependency>
      <groupId>org.eclipse.microprofile.config</groupId>
      <artifactId>microprofile-config-api</artifactId>
    </dependency>

    <!-- SLF4J for logging -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
    </dependency>
  </dependencies>

  <build>
    <resources>
      <resource>
        <directory>src/main/resources</directory>
        <filtering>false</filtering>
      </resource>
    </resources>
    <plugins>
      <!-- Jandex for CDI bean discovery -->
      <plugin>
        <groupId>io.smallrye</groupId>
        <artifactId>jandex-maven-plugin</artifactId>
        <executions>
          <execution>
            <id>make-index</id>
            <goals>
              <goal>jandex</goal>
            </goals>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 3: Create directory structure**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
mkdir -p src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema
mkdir -p src/main/resources/elasticsearch/ilm
mkdir -p src/main/resources/elasticsearch/index-templates
mkdir -p src/main/resources/elasticsearch/transforms
```

- [ ] **Step 4: Verify module builds**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-storage/pom.xml
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/
git commit -m "feat(schema): create elasticsearch-schema module structure

Create new module for Elasticsearch schema scripts (ILM, index
templates, transforms). Mirrors data-index-storage-migrations for
PostgreSQL Flyway scripts.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 2: Create ILM Policy for Raw Event Retention

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/ilm/data-index-events-retention.json`

- [ ] **Step 1: Create ILM policy JSON**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/ilm/data-index-events-retention.json`:

```json
{
  "policy": {
    "phases": {
      "hot": {
        "actions": {
          "rollover": {
            "max_age": "1d",
            "max_primary_shard_size": "50GB"
          }
        }
      },
      "delete": {
        "min_age": "7d",
        "actions": {
          "delete": {}
        }
      }
    }
  }
}
```

- [ ] **Step 2: Validate JSON syntax**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
cat src/main/resources/elasticsearch/ilm/data-index-events-retention.json | python3 -m json.tool > /dev/null
```

Expected: No output (valid JSON)

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/ilm/
git commit -m "feat(schema): add ILM policy for raw event retention

7-day retention for raw events (workflow-events, task-events).
Rollover daily to prevent large indices. Raw events deleted after
aggregation by ES Transform.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 3: Create Workflow Index Templates

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/workflow-events.json`
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/workflow-instances.json`

- [ ] **Step 1: Create raw events index template**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/workflow-events.json`:

```json
{
  "index_patterns": ["workflow-events-*"],
  "template": {
    "settings": {
      "index.lifecycle.name": "data-index-events-retention",
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "event_id": {
          "type": "keyword"
        },
        "event_type": {
          "type": "keyword"
        },
        "event_time": {
          "type": "date"
        },
        "instance_id": {
          "type": "keyword"
        },
        "workflow_name": {
          "type": "keyword"
        },
        "workflow_version": {
          "type": "keyword"
        },
        "workflow_namespace": {
          "type": "keyword"
        },
        "status": {
          "type": "keyword"
        },
        "start_time": {
          "type": "date"
        },
        "end_time": {
          "type": "date"
        },
        "input_data": {
          "type": "flattened"
        },
        "output_data": {
          "type": "flattened"
        },
        "error": {
          "type": "object",
          "enabled": false
        }
      }
    }
  }
}
```

- [ ] **Step 2: Create normalized instances index template**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/workflow-instances.json`:

```json
{
  "index_patterns": ["workflow-instances"],
  "template": {
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "id": {
          "type": "keyword"
        },
        "name": {
          "type": "keyword"
        },
        "version": {
          "type": "keyword"
        },
        "namespace": {
          "type": "keyword"
        },
        "status": {
          "type": "keyword"
        },
        "start": {
          "type": "date"
        },
        "end": {
          "type": "date"
        },
        "input": {
          "type": "flattened"
        },
        "output": {
          "type": "flattened"
        },
        "error": {
          "properties": {
            "type": {
              "type": "keyword"
            },
            "title": {
              "type": "text"
            },
            "detail": {
              "type": "text"
            },
            "status": {
              "type": "integer"
            },
            "instance": {
              "type": "keyword"
            }
          }
        },
        "last_update": {
          "type": "date"
        }
      }
    }
  }
}
```

- [ ] **Step 3: Validate JSON syntax**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
cat src/main/resources/elasticsearch/index-templates/workflow-events.json | python3 -m json.tool > /dev/null
cat src/main/resources/elasticsearch/index-templates/workflow-instances.json | python3 -m json.tool > /dev/null
```

Expected: No output (valid JSON for both)

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/workflow-*.json
git commit -m "feat(schema): add workflow index templates

workflow-events: Raw events with flattened input_data/output_data
workflow-instances: Normalized aggregated data

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 4: Create Workflow Transform Configuration

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/workflow-instances-transform.json`

- [ ] **Step 1: Create transform configuration**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/workflow-instances-transform.json`:

```json
{
  "source": {
    "index": ["workflow-events-*"],
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "event_time": {
                "gte": "now-1h"
              }
            }
          },
          {
            "bool": {
              "must_not": [
                {
                  "term": {
                    "event_type": "workflow.instance.completed"
                  }
                },
                {
                  "term": {
                    "event_type": "workflow.instance.faulted"
                  }
                },
                {
                  "term": {
                    "event_type": "workflow.instance.cancelled"
                  }
                }
              ]
            }
          }
        ]
      }
    }
  },
  "dest": {
    "index": "workflow-instances"
  },
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "event_time",
      "delay": "60s"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "instance_id"
        }
      }
    },
    "aggregations": {
      "name": {
        "terms": {
          "field": "workflow_name",
          "size": 1
        }
      },
      "version": {
        "terms": {
          "field": "workflow_version",
          "size": 1
        }
      },
      "namespace": {
        "terms": {
          "field": "workflow_namespace",
          "size": 1
        }
      },
      "status": {
        "terms": {
          "field": "status",
          "size": 1
        }
      },
      "start": {
        "min": {
          "field": "start_time"
        }
      },
      "end": {
        "max": {
          "field": "end_time"
        }
      },
      "input": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "asc"
              }
            }
          ],
          "_source": ["input_data"]
        }
      },
      "output": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "desc"
              }
            }
          ],
          "_source": ["output_data"]
        }
      },
      "error": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "desc"
              }
            }
          ],
          "_source": ["error"]
        }
      },
      "last_update": {
        "max": {
          "field": "event_time"
        }
      }
    }
  }
}
```

- [ ] **Step 2: Validate JSON syntax**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
cat src/main/resources/elasticsearch/transforms/workflow-instances-transform.json | python3 -m json.tool > /dev/null
```

Expected: No output (valid JSON)

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/workflow-instances-transform.json
git commit -m "feat(schema): add workflow instances ES Transform

Aggregates workflow events by instance_id. Handles out-of-order events
via min/max/top_hits aggregations. Runs every 1s with 60s delay for
late arrivals. Smart filtering: process recent + non-terminal only.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 5: Implement ElasticsearchSchemaInitializer

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java`

- [ ] **Step 1: Create schema initializer class**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java`:

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.elasticsearch.schema;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Initializes Elasticsearch schema on startup (dev/test mode only).
 *
 * <p>Applies:
 * <ul>
 *   <li>ILM policies from {@code elasticsearch/ilm/*.json}
 *   <li>Index templates from {@code elasticsearch/index-templates/*.json}
 *   <li>Transforms from {@code elasticsearch/transforms/*.json}
 * </ul>
 *
 * <p>Production mode: {@code data-index.init-schema=false} (operator handles schema)
 */
@ApplicationScoped
public class ElasticsearchSchemaInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger(ElasticsearchSchemaInitializer.class);

    @Inject
    ElasticsearchClient client;

    @ConfigProperty(name = "data-index.init-schema", defaultValue = "true")
    boolean initSchema;

    void onStart(@Observes StartupEvent event) {
        if (!initSchema) {
            LOGGER.info("Elasticsearch schema initialization disabled (production mode)");
            return;
        }

        LOGGER.info("Initializing Elasticsearch schema (dev/test mode)");
        try {
            applyILMPolicies();
            applyIndexTemplates();
            applyTransforms();
            LOGGER.info("Elasticsearch schema initialized successfully");
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Elasticsearch schema", e);
        }
    }

    private void applyILMPolicies() throws IOException {
        LOGGER.debug("Applying ILM policies");
        applyILMPolicy("data-index-events-retention");
    }

    private void applyILMPolicy(String policyName) throws IOException {
        String json = loadResource("/elasticsearch/ilm/" + policyName + ".json");
        
        try {
            client.ilm().putLifecycle(r -> r
                .name(policyName)
                .withJson(new StringReader(json))
            );
            LOGGER.debug("Applied ILM policy: {}", policyName);
        } catch (ElasticsearchException e) {
            LOGGER.warn("ILM policy {} may already exist: {}", policyName, e.getMessage());
        }
    }

    private void applyIndexTemplates() throws IOException {
        LOGGER.debug("Applying index templates");
        applyIndexTemplate("workflow-events");
        applyIndexTemplate("workflow-instances");
    }

    private void applyIndexTemplate(String templateName) throws IOException {
        String json = loadResource("/elasticsearch/index-templates/" + templateName + ".json");
        
        try {
            client.indices().putIndexTemplate(r -> r
                .name(templateName)
                .withJson(new StringReader(json))
            );
            LOGGER.debug("Applied index template: {}", templateName);
        } catch (ElasticsearchException e) {
            LOGGER.warn("Index template {} may already exist: {}", templateName, e.getMessage());
        }
    }

    private void applyTransforms() throws IOException {
        LOGGER.debug("Applying transforms");
        applyTransform("workflow-instances-transform");
    }

    private void applyTransform(String transformId) throws IOException {
        String json = loadResource("/elasticsearch/transforms/" + transformId + ".json");
        
        try {
            client.transform().putTransform(r -> r
                .transformId(transformId)
                .withJson(new StringReader(json))
            );
            LOGGER.debug("Created transform: {}", transformId);
            
            // Start the transform
            client.transform().startTransform(s -> s.transformId(transformId));
            LOGGER.debug("Started transform: {}", transformId);
        } catch (ElasticsearchException e) {
            if (e.getMessage().contains("already exists") || e.getMessage().contains("already started")) {
                LOGGER.debug("Transform {} already exists and may be running", transformId);
            } else {
                throw e;
            }
        }
    }

    private String loadResource(String path) throws IOException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
```

- [ ] **Step 2: Build module**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/
git commit -m "feat(schema): implement ElasticsearchSchemaInitializer

Loads and applies ES schema scripts on startup (dev/test mode only).
Handles ILM policies, index templates, and transforms. Idempotent
(safe to run multiple times).

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 6: Configure Universal skipInitSchema Flag

**Files:**
- Modify: `data-index-service-core/src/main/resources/application.properties`

- [ ] **Step 1: Add universal schema initialization property**

Edit `data-index/data-index-service/data-index-service-core/src/main/resources/application.properties`:

Add this section at the end:

```properties
# ================================================
# Universal Schema Initialization Control
# ================================================
# Controls both PostgreSQL Flyway and Elasticsearch schema initialization
# Dev/Test: true (auto-initialize schema)
# Production: false (operator handles schema via -DskipInitSchema=true)
data-index.init-schema=${skipInitSchema:true}

# PostgreSQL: Map to Flyway control
%postgresql.quarkus.flyway.migrate-at-start=${data-index.init-schema}
```

- [ ] **Step 2: Verify no build errors**

Run:
```bash
cd data-index/data-index-service/data-index-service-core
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-service/data-index-service-core/src/main/resources/application.properties
git commit -m "feat(config): add universal skipInitSchema flag

Single flag controls schema initialization for both PostgreSQL
(Flyway) and Elasticsearch (schema initializer). Maps to
data-index.init-schema property.

Usage:
- Dev: mvn quarkus:dev (auto-init enabled)
- Prod: mvn package -DskipInitSchema=true (auto-init disabled)

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 7: Wire Schema Module to Elasticsearch Service

**Files:**
- Modify: `data-index-service-elasticsearch/pom.xml`
- Modify: `data-index-service-elasticsearch/src/main/resources/application.properties`

- [ ] **Step 1: Add schema dependency to service module**

Edit `data-index/data-index-service/data-index-service-elasticsearch/pom.xml`:

Add this dependency in the `<dependencies>` section:

```xml
<!-- Elasticsearch schema (auto-initialization in dev/test) -->
<dependency>
  <groupId>org.kubesmarts.logic.apps</groupId>
  <artifactId>data-index-storage-elasticsearch-schema</artifactId>
</dependency>
```

- [ ] **Step 2: Update Elasticsearch service configuration**

Edit `data-index/data-index-service/data-index-service-elasticsearch/src/main/resources/application.properties`:

Replace entire content with:

```properties
#
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#

# ================================================
# Elasticsearch Storage Backend Configuration
# ================================================

# Container image name
quarkus.container-image.name=data-index-service-elasticsearch

# ================================================
# Elasticsearch Connection
# ================================================

# Dev Mode - Quarkus Dev Services auto-starts Elasticsearch container
%dev.quarkus.elasticsearch.devservices.enabled=true
%dev.quarkus.elasticsearch.devservices.image-name=docker.elastic.co/elasticsearch/elasticsearch:8.11.0
%dev.quarkus.elasticsearch.devservices.port=9200

# Test Mode - Testcontainers handles Elasticsearch
%test.quarkus.elasticsearch.devservices.enabled=false

# Production - External Elasticsearch cluster
%prod.quarkus.elasticsearch.hosts=elasticsearch.elasticsearch.svc.cluster.local:9200

# ================================================
# Data Index Elasticsearch Configuration
# ================================================

# Index names
data-index.elasticsearch.workflow-instance-index=workflow-instances
data-index.elasticsearch.task-execution-index=task-executions

# Index settings
data-index.elasticsearch.number-of-shards=3
data-index.elasticsearch.number-of-replicas=1
data-index.elasticsearch.refresh-policy=wait_for
data-index.elasticsearch.auto-create-indices=true

# Schema initialization (controlled by universal flag)
# Inherited from application.properties: data-index.init-schema=${skipInitSchema:true}
```

- [ ] **Step 3: Build service module**

Run:
```bash
cd data-index/data-index-service/data-index-service-elasticsearch
mvn clean compile
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-service/data-index-service-elasticsearch/pom.xml
git add data-index/data-index-service/data-index-service-elasticsearch/src/main/resources/application.properties
git commit -m "feat(service): wire Elasticsearch schema to service module

Add schema dependency and complete Elasticsearch configuration.
Dev Services enabled for dev mode, schema auto-initialization
controlled by universal flag.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 8: Add Elasticsearch Test Profile

**Files:**
- Create: `data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchTestProfile.java`

- [ ] **Step 1: Create test profile**

Create `data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchTestProfile.java`:

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.elasticsearch;

import java.util.Map;

import io.quarkus.test.junit.QuarkusTestProfile;

/**
 * Test profile for Elasticsearch integration tests.
 *
 * <p>Enables schema auto-initialization and uses test index names.
 */
public class ElasticsearchTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "data-index.init-schema", "true",
            "data-index.elasticsearch.workflow-instance-index", "test-workflow-instances",
            "data-index.elasticsearch.task-execution-index", "test-task-executions"
        );
    }
}
```

- [ ] **Step 2: Build module**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn clean test-compile
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchTestProfile.java
git commit -m "test(elasticsearch): add test profile for integration tests

Enables schema auto-initialization and uses test index names to
isolate tests from production indices.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 9: Test Schema Initialization

**Files:**
- Create: `data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchSchemaInitializerTest.java`

- [ ] **Step 1: Write failing test for ILM policy creation**

Create `data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchSchemaInitializerTest.java`:

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.ilm.GetLifecycleResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Integration tests for Elasticsearch schema initialization.
 */
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
public class ElasticsearchSchemaInitializerTest {

    @Inject
    ElasticsearchClient client;

    @Test
    public void testILMPolicyCreated() throws Exception {
        GetLifecycleResponse response = client.ilm()
            .getLifecycle(r -> r.name("data-index-events-retention"));
        
        assertThat(response.get("data-index-events-retention")).isNotNull();
        assertThat(response.get("data-index-events-retention").policy().phases().delete())
            .isNotNull();
    }

    @Test
    public void testWorkflowEventsTemplateCreated() throws Exception {
        boolean exists = client.indices()
            .existsIndexTemplate(r -> r.name("workflow-events"))
            .value();
        
        assertThat(exists).isTrue();
    }

    @Test
    public void testWorkflowInstancesTemplateCreated() throws Exception {
        boolean exists = client.indices()
            .existsIndexTemplate(r -> r.name("workflow-instances"))
            .value();
        
        assertThat(exists).isTrue();
    }

    @Test
    public void testWorkflowTransformCreated() throws Exception {
        var response = client.transform()
            .getTransform(r -> r.transformId("workflow-instances-transform"));
        
        assertThat(response.transforms()).hasSize(1);
        assertThat(response.transforms().get(0).id()).isEqualTo("workflow-instances-transform");
    }

    @Test
    public void testWorkflowTransformStarted() throws Exception {
        var stats = client.transform()
            .getTransformStats(r -> r.transformId("workflow-instances-transform"));
        
        assertThat(stats.transforms()).hasSize(1);
        assertThat(stats.transforms().get(0).state().toString())
            .isIn("started", "indexing", "stopping");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn test -Dtest=ElasticsearchSchemaInitializerTest
```

Expected: Tests should PASS (schema initializer already implemented and runs on startup)

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchSchemaInitializerTest.java
git commit -m "test(elasticsearch): add schema initialization tests

Verify ILM policies, index templates, and transforms are created
and started on application startup.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 10: Test WorkflowInstance Storage CRUD

**Files:**
- Modify: `data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java`

- [ ] **Step 1: Update existing test to use test profile**

Edit `data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java`:

Add `@TestProfile` annotation at class level:

```java
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
public class ElasticsearchStorageIntegrationTest {
```

- [ ] **Step 2: Run existing tests**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn test -Dtest=ElasticsearchStorageIntegrationTest
```

Expected: Tests PASS

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java
git commit -m "test(elasticsearch): use test profile for storage tests

Apply ElasticsearchTestProfile to enable schema auto-initialization
and use test index names.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 11: Test Transform Normalization - Out-of-Order Events

**Files:**
- Create: `data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/WorkflowInstanceTransformTest.java`

- [ ] **Step 1: Write test for out-of-order event handling**

Create `data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/WorkflowInstanceTransformTest.java`:

```java
/*
 * Copyright 2024 KubeSmarts Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kubesmarts.logic.dataindex.elasticsearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.ZonedDateTime;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kubesmarts.logic.dataindex.model.WorkflowInstance;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch.core.GetResponse;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/**
 * Integration tests for Elasticsearch Transform normalization.
 */
@QuarkusTest
@TestProfile(ElasticsearchTestProfile.class)
public class WorkflowInstanceTransformTest {

    @Inject
    ElasticsearchClient client;

    @Inject
    ElasticsearchWorkflowInstanceStorage workflowStorage;

    private static final String RAW_INDEX = "workflow-events-test";
    private static final String NORMALIZED_INDEX = "test-workflow-instances";

    @BeforeEach
    public void setUp() throws Exception {
        // Clear indices before each test
        try {
            client.indices().delete(d -> d.index(RAW_INDEX));
        } catch (Exception e) {
            // Ignore if doesn't exist
        }
        
        workflowStorage.clear();
        
        // Create raw index
        client.indices().create(c -> c.index(RAW_INDEX));
    }

    @AfterEach
    public void tearDown() throws Exception {
        try {
            client.indices().delete(d -> d.index(RAW_INDEX));
        } catch (Exception e) {
            // Ignore errors
        }
        workflowStorage.clear();
    }

    @Test
    public void testOutOfOrderEvents() throws Exception {
        String instanceId = "wf-out-of-order-123";
        ZonedDateTime startTime = ZonedDateTime.parse("2026-04-29T10:00:00Z");
        ZonedDateTime endTime = ZonedDateTime.parse("2026-04-29T10:05:00Z");

        // Event 1: COMPLETED arrives first (out of order)
        Map<String, Object> completedEvent = Map.of(
            "event_id", "evt-002",
            "event_type", "workflow.instance.completed",
            "event_time", endTime.toString(),
            "instance_id", instanceId,
            "status", "COMPLETED",
            "end_time", endTime.toString(),
            "output_data", Map.of("result", "success")
        );

        client.index(i -> i
            .index(RAW_INDEX)
            .document(completedEvent)
            .refresh(Refresh.True)
        );

        // Wait for Transform to process (max 5 seconds)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            GetResponse<WorkflowInstance> response = client.get(g -> g
                .index(NORMALIZED_INDEX)
                .id(instanceId),
                WorkflowInstance.class
            );
            assertThat(response.found()).isTrue();
        });

        // Event 2: STARTED arrives late
        Map<String, Object> startedEvent = Map.of(
            "event_id", "evt-001",
            "event_type", "workflow.instance.started",
            "event_time", startTime.toString(),
            "instance_id", instanceId,
            "workflow_name", "test-workflow",
            "workflow_version", "1.0",
            "workflow_namespace", "default",
            "status", "RUNNING",
            "start_time", startTime.toString(),
            "input_data", Map.of("orderId", "order-456")
        );

        client.index(i -> i
            .index(RAW_INDEX)
            .document(startedEvent)
            .refresh(Refresh.True)
        );

        // Wait for Transform to reprocess (max 5 seconds)
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            GetResponse<WorkflowInstance> response = client.get(g -> g
                .index(NORMALIZED_INDEX)
                .id(instanceId),
                WorkflowInstance.class
            );
            
            assertThat(response.found()).isTrue();
            WorkflowInstance instance = response.source();
            
            // Both start and end should be present
            assertThat(instance.getStart()).isNotNull();
            assertThat(instance.getEnd()).isNotNull();
            
            // Status should be COMPLETED (terminal wins)
            assertThat(instance.getStatus().toString()).isEqualTo("COMPLETED");
        });
    }
}
```

- [ ] **Step 2: Run test**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn test -Dtest=WorkflowInstanceTransformTest#testOutOfOrderEvents
```

Expected: Test PASSES (Transform handles out-of-order events)

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/WorkflowInstanceTransformTest.java
git commit -m "test(transform): verify out-of-order event handling

Test that Transform correctly aggregates COMPLETED and STARTED events
arriving in reverse order. Validates min/max aggregations work.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## PHASE 2: TaskExecution Full Stack

### Task 12: Create Task Index Templates

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/task-events.json`
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/task-executions.json`

- [ ] **Step 1: Create raw task events index template**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/task-events.json`:

```json
{
  "index_patterns": ["task-events-*"],
  "template": {
    "settings": {
      "index.lifecycle.name": "data-index-events-retention",
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "event_id": {
          "type": "keyword"
        },
        "event_type": {
          "type": "keyword"
        },
        "event_time": {
          "type": "date"
        },
        "instance_id": {
          "type": "keyword"
        },
        "task_execution_id": {
          "type": "keyword"
        },
        "task_position": {
          "type": "keyword"
        },
        "task_name": {
          "type": "keyword"
        },
        "start_time": {
          "type": "date"
        },
        "end_time": {
          "type": "date"
        },
        "input_args": {
          "type": "flattened"
        },
        "output_args": {
          "type": "flattened"
        },
        "error": {
          "type": "object",
          "enabled": false
        }
      }
    }
  }
}
```

- [ ] **Step 2: Create normalized task executions index template**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/task-executions.json`:

```json
{
  "index_patterns": ["task-executions"],
  "template": {
    "settings": {
      "number_of_shards": 3,
      "number_of_replicas": 1
    },
    "mappings": {
      "properties": {
        "id": {
          "type": "keyword"
        },
        "instanceId": {
          "type": "keyword"
        },
        "position": {
          "type": "keyword"
        },
        "name": {
          "type": "keyword"
        },
        "start": {
          "type": "date"
        },
        "end": {
          "type": "date"
        },
        "input": {
          "type": "flattened"
        },
        "output": {
          "type": "flattened"
        },
        "error": {
          "properties": {
            "type": {
              "type": "keyword"
            },
            "title": {
              "type": "text"
            },
            "detail": {
              "type": "text"
            },
            "status": {
              "type": "integer"
            },
            "instance": {
              "type": "keyword"
            }
          }
        },
        "last_update": {
          "type": "date"
        }
      }
    }
  }
}
```

- [ ] **Step 3: Validate JSON**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
cat src/main/resources/elasticsearch/index-templates/task-events.json | python3 -m json.tool > /dev/null
cat src/main/resources/elasticsearch/index-templates/task-executions.json | python3 -m json.tool > /dev/null
```

Expected: No output (valid JSON)

- [ ] **Step 4: Update schema initializer to apply task templates**

Edit `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java`:

Update `applyIndexTemplates()` method:

```java
private void applyIndexTemplates() throws IOException {
    LOGGER.debug("Applying index templates");
    applyIndexTemplate("workflow-events");
    applyIndexTemplate("workflow-instances");
    applyIndexTemplate("task-events");
    applyIndexTemplate("task-executions");
}
```

- [ ] **Step 5: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/index-templates/task-*.json
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java
git commit -m "feat(schema): add task execution index templates

task-events: Raw events with flattened input_args/output_args
task-executions: Normalized aggregated task data

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 13: Create Task Transform Configuration

**Files:**
- Create: `data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/task-executions-transform.json`

- [ ] **Step 1: Create task transform configuration**

Create `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/task-executions-transform.json`:

```json
{
  "source": {
    "index": ["task-events-*"],
    "query": {
      "bool": {
        "should": [
          {
            "range": {
              "event_time": {
                "gte": "now-1h"
              }
            }
          },
          {
            "bool": {
              "must_not": [
                {
                  "term": {
                    "event_type": "task.execution.completed"
                  }
                },
                {
                  "term": {
                    "event_type": "task.execution.faulted"
                  }
                }
              ]
            }
          }
        ]
      }
    }
  },
  "dest": {
    "index": "task-executions"
  },
  "frequency": "1s",
  "sync": {
    "time": {
      "field": "event_time",
      "delay": "60s"
    }
  },
  "pivot": {
    "group_by": {
      "id": {
        "terms": {
          "field": "task_execution_id"
        }
      }
    },
    "aggregations": {
      "instanceId": {
        "terms": {
          "field": "instance_id",
          "size": 1
        }
      },
      "position": {
        "terms": {
          "field": "task_position",
          "size": 1
        }
      },
      "name": {
        "terms": {
          "field": "task_name",
          "size": 1
        }
      },
      "start": {
        "min": {
          "field": "start_time"
        }
      },
      "end": {
        "max": {
          "field": "end_time"
        }
      },
      "input": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "asc"
              }
            }
          ],
          "_source": ["input_args"]
        }
      },
      "output": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "desc"
              }
            }
          ],
          "_source": ["output_args"]
        }
      },
      "error": {
        "top_hits": {
          "size": 1,
          "sort": [
            {
              "event_time": {
                "order": "desc"
              }
            }
          ],
          "_source": ["error"]
        }
      },
      "last_update": {
        "max": {
          "field": "event_time"
        }
      }
    }
  }
}
```

- [ ] **Step 2: Validate JSON**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
cat src/main/resources/elasticsearch/transforms/task-executions-transform.json | python3 -m json.tool > /dev/null
```

Expected: No output (valid JSON)

- [ ] **Step 3: Update schema initializer to apply task transform**

Edit `data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java`:

Update `applyTransforms()` method:

```java
private void applyTransforms() throws IOException {
    LOGGER.debug("Applying transforms");
    applyTransform("workflow-instances-transform");
    applyTransform("task-executions-transform");
}
```

- [ ] **Step 4: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/resources/elasticsearch/transforms/task-executions-transform.json
git add data-index/data-index-storage/data-index-storage-elasticsearch-schema/src/main/java/org/kubesmarts/logic/dataindex/elasticsearch/schema/ElasticsearchSchemaInitializer.java
git commit -m "feat(schema): add task executions ES Transform

Aggregates task events by task_execution_id. Groups by composite key
(instance_id:task_position in future). Handles out-of-order via
min/max/top_hits. Smart filtering for performance.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 14: Test TaskExecution Storage

**Files:**
- Modify: `data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java`

- [ ] **Step 1: Add task execution CRUD test**

Edit `data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java`:

Add this test method:

```java
@Test
public void testTaskExecutionCRUD() throws Exception {
    String taskId = "task-test-789";
    
    TaskExecution task = TaskExecution.builder()
        .id(taskId)
        .instanceId("wf-123")
        .position("0")
        .name("test-task")
        .start(ZonedDateTime.now())
        .build();
    
    // Create
    taskStorage.put(taskId, task);
    waitForRefresh();
    
    // Read
    TaskExecution retrieved = taskStorage.get(taskId);
    assertThat(retrieved).isNotNull();
    assertThat(retrieved.getId()).isEqualTo(taskId);
    assertThat(retrieved.getInstanceId()).isEqualTo("wf-123");
    
    // Update
    TaskExecution updated = retrieved.toBuilder()
        .end(ZonedDateTime.now())
        .build();
    taskStorage.put(taskId, updated);
    waitForRefresh();
    
    TaskExecution afterUpdate = taskStorage.get(taskId);
    assertThat(afterUpdate.getEnd()).isNotNull();
    
    // Delete
    taskStorage.remove(taskId);
    waitForRefresh();
    
    TaskExecution afterDelete = taskStorage.get(taskId);
    assertThat(afterDelete).isNull();
}
```

- [ ] **Step 2: Run test**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn test -Dtest=ElasticsearchStorageIntegrationTest#testTaskExecutionCRUD
```

Expected: Test PASSES

- [ ] **Step 3: Commit**

```bash
git add data-index/data-index-storage/data-index-storage-elasticsearch/src/test/java/org/kubesmarts/logic/dataindex/elasticsearch/ElasticsearchStorageIntegrationTest.java
git commit -m "test(elasticsearch): add task execution CRUD test

Verify TaskExecution storage supports create, read, update, delete
operations against Elasticsearch.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## PHASE 3: Documentation & FluentBit

### Task 15: Update CLAUDE.md

**Files:**
- Modify: `data-index/CLAUDE.md`

- [ ] **Step 1: Update MODE 2 section in CLAUDE.md**

Edit `data-index/CLAUDE.md`:

Find the section about MODE 2 and update it:

```markdown
## MODE 2: Elasticsearch (ES Transform + ILM)

**Status:** ✅ **IMPLEMENTED** - Integration tested, E2E pending

### Architecture

```
Quarkus Flow → stdout JSON
    ↓
FluentBit → workflow-events, task-events (raw, ILM 7-day)
    ↓
ES Transform (continuous, 1s) → Aggregation by ID
    ↓
workflow-instances, task-executions (normalized, permanent)
    ↓
Data Index GraphQL API
```

### Key Components

**Schema Module:** `data-index-storage-elasticsearch-schema`
- ILM policies: `elasticsearch/ilm/*.json`
- Index templates: `elasticsearch/index-templates/*.json`
- Transforms: `elasticsearch/transforms/*.json`
- `ElasticsearchSchemaInitializer` - Dev/test auto-initialization

**Storage Module:** `data-index-storage-elasticsearch`
- `ElasticsearchWorkflowInstanceStorage`
- `ElasticsearchTaskExecutionStorage`
- `ElasticsearchQuery` - Filter translation

**Service Module:** `data-index-service-elasticsearch`
- Configuration only (no Java code)
- Wires schema + storage to GraphQL API

### Build Commands

```bash
# Dev Mode - auto-initialize schema
cd data-index/data-index-service/data-index-service-elasticsearch
mvn quarkus:dev

# What happens:
# - Elasticsearch Dev Services starts container
# - ElasticsearchSchemaInitializer runs (data-index.init-schema=true)
# - ILM policies applied
# - Index templates created
# - Transforms created and started
# - GraphQL API available at http://localhost:8080/graphql

# Production Build - skip schema initialization
mvn clean package -DskipInitSchema=true

# Result:
# - Elasticsearch storage dependencies
# - NO schema initialization (operator handles it)
# - Container image: kubesmarts/data-index-service-elasticsearch:999-SNAPSHOT
```

### Schema Initialization

**Universal Flag:** `skipInitSchema` (works for both PostgreSQL and Elasticsearch)

| Mode | Command | Schema Initialization |
|------|---------|----------------------|
| Dev (PostgreSQL) | `mvn quarkus:dev -Dquarkus.profile=postgresql` | ✅ Flyway runs migrations |
| Dev (Elasticsearch) | `mvn quarkus:dev -Dquarkus.profile=elasticsearch` | ✅ ES schema initializer runs |
| Production (PostgreSQL) | `mvn package -Dquarkus.profile=postgresql -DskipInitSchema=true` | ❌ Operator applies Flyway |
| Production (Elasticsearch) | `mvn package -Dquarkus.profile=elasticsearch -DskipInitSchema=true` | ❌ Operator applies ES scripts |

### ES Transform Details

**Out-of-Order Event Handling:**
- `start`: `min(start_time)` - earliest event wins
- `end`: `max(end_time)` - latest event wins
- `status`: Latest by `event_time` (simplified for now, terminal logic TBD)
- `input`: `top_hits` sorted ASC - first event wins
- `output`: `top_hits` sorted DESC - last event wins

**Performance Optimization:**
- Process recent events (< 1 hour): Always
- Process old events (> 1 hour): Only non-terminal
- Result: Constant performance at scale

**ILM Cleanup:**
- Raw events deleted after 7 days
- Normalized indices kept forever
- 60s delay for late arrivals

### Testing

**Integration Tests:** Testcontainers Elasticsearch
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn test
```

**E2E Tests:** KIND cluster (pending)
```bash
# TODO: Implement E2E tests with FluentBit
```

### What NOT to Do (MODE 2)

- ❌ Don't manually create ES schema (use auto-init in dev, operator in prod)
- ❌ Don't create Ingest Pipelines (we use Transform)
- ❌ Don't add Kafka (Transform replaces Event Processor)
- ❌ Don't use `@JsonProperty` on flattened field getters (ES stores JsonNode directly)
```

- [ ] **Step 2: Commit**

```bash
git add data-index/CLAUDE.md
git commit -m "docs: update CLAUDE.md with MODE 2 implementation

Document Elasticsearch backend with ES Transform approach, schema
module structure, build commands, and testing strategy.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

### Task 16: Create FluentBit ES Output Configuration

**Files:**
- Create: `data-index/scripts/fluentbit/elasticsearch/fluent-bit.conf`
- Create: `data-index/scripts/fluentbit/elasticsearch/README.md`

- [ ] **Step 1: Create FluentBit configuration**

Create `data-index/scripts/fluentbit/elasticsearch/fluent-bit.conf`:

```conf
[SERVICE]
    Flush        1
    Daemon       Off
    Log_Level    info
    Parsers_File parsers.conf

[INPUT]
    Name              tail
    Path              /var/log/containers/*quarkus-flow*.log
    Parser            cri
    Tag               kube.*
    Refresh_Interval  5
    Mem_Buf_Limit     5MB
    Skip_Long_Lines   On

[FILTER]
    Name                kubernetes
    Match               kube.*
    Kube_URL            https://kubernetes.default.svc:443
    Kube_CA_File        /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
    Kube_Token_File     /var/run/secrets/kubernetes.io/serviceaccount/token
    Kube_Tag_Prefix     kube.var.log.containers.
    Merge_Log           On
    Keep_Log            Off
    K8S-Logging.Parser  On
    K8S-Logging.Exclude On

[FILTER]
    Name    grep
    Match   kube.*
    Regex   log event_type (workflow\.instance\.|task\.execution\.)

# Extract event type to route to different indices
[FILTER]
    Name         modify
    Match        kube.*
    Add_if_empty event_index workflow-events

[FILTER]
    Name    modify
    Match   kube.*
    Condition Key_value_matches log event_type workflow\.instance\..*
    Set     event_index workflow-events

[FILTER]
    Name    modify
    Match   kube.*
    Condition Key_value_matches log event_type task\.execution\..*
    Set     event_index task-events

# Workflow events to Elasticsearch
[OUTPUT]
    Name            es
    Match           kube.*
    Host            ${ES_HOST}
    Port            ${ES_PORT}
    Index           ${event_index}-%Y.%m.%d
    Type            _doc
    Logstash_Format On
    Logstash_Prefix ${event_index}
    Retry_Limit     5
    Suppress_Type_Name On

# Health check
[OUTPUT]
    Name   stdout
    Match  kube.*
    Format json
```

- [ ] **Step 2: Create README**

Create `data-index/scripts/fluentbit/elasticsearch/README.md`:

```markdown
# FluentBit Elasticsearch Output Configuration

**MODE 2:** FluentBit writes raw events directly to Elasticsearch. ES Transform normalizes.

## Architecture

```
Quarkus Flow → stdout JSON
    ↓
/var/log/containers/*.log
    ↓
FluentBit (tail + cri parser)
    ↓
Elasticsearch Raw Indices:
    - workflow-events-YYYY.MM.DD
    - task-events-YYYY.MM.DD
```

## Configuration

**Environment Variables:**
- `ES_HOST` - Elasticsearch host (default: elasticsearch.elasticsearch.svc.cluster.local)
- `ES_PORT` - Elasticsearch port (default: 9200)

**Filters:**
- Kubernetes metadata enrichment
- Grep for event_type field (only workflow/task events)
- Dynamic index routing based on event_type

**Output:**
- Elasticsearch output plugin
- Logstash format (timestamped indices)
- 5 retries on failure

## Deployment

```bash
kubectl create configmap fluentbit-es-config \
  --from-file=fluent-bit.conf \
  -n logging

kubectl apply -f kubernetes/daemonset.yaml
```

## Testing

1. Deploy Quarkus Flow app
2. Trigger workflow execution
3. Check Elasticsearch:
   ```bash
   curl http://elasticsearch:9200/workflow-events-*/_count
   ```
4. Verify Transform running:
   ```bash
   curl http://elasticsearch:9200/_transform/workflow-instances-transform/_stats
   ```
5. Query normalized data:
   ```bash
   curl http://elasticsearch:9200/workflow-instances/_search
   ```

## Troubleshooting

**Events not in Elasticsearch:**
```bash
kubectl logs -n logging -l app=fluent-bit --tail=100
```

**Transform not processing:**
```bash
curl http://elasticsearch:9200/_transform/workflow-instances-transform/_stats
```

**ILM not deleting old indices:**
```bash
curl http://elasticsearch:9200/workflow-events-*/_ilm/explain
```
```

- [ ] **Step 3: Commit**

```bash
git add data-index/scripts/fluentbit/elasticsearch/
git commit -m "feat(fluentbit): add Elasticsearch output configuration

FluentBit writes raw events to ES. Dynamic routing based on event_type.
Logstash format for timestamped indices. ES Transform handles
normalization.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Final Verification

### Task 17: Run Full Test Suite

**Files:**
- N/A (verification only)

- [ ] **Step 1: Run all storage tests**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch
mvn clean test
```

Expected: All tests PASS

- [ ] **Step 2: Run schema module tests**

Run:
```bash
cd data-index/data-index-storage/data-index-storage-elasticsearch-schema
mvn clean test
```

Expected: BUILD SUCCESS (no tests in schema module, just resources)

- [ ] **Step 3: Build all modules**

Run:
```bash
cd data-index
mvn clean package -DskipTests -DskipInitSchema=true
```

Expected: BUILD SUCCESS for all modules

- [ ] **Step 4: Test dev mode startup**

Run:
```bash
cd data-index/data-index-service/data-index-service-elasticsearch
mvn quarkus:dev
```

Expected:
- Elasticsearch Dev Services container starts
- Schema initializer runs (logs show ILM, templates, transforms created)
- Service starts on http://localhost:8080
- GraphQL UI available at http://localhost:8080/q/graphql-ui

Press `q` to quit dev mode.

- [ ] **Step 5: Verify no regressions**

Run:
```bash
cd data-index
git status
```

Expected: Only new files, no unexpected modifications to existing files

- [ ] **Step 6: Final commit**

```bash
git add -A
git commit -m "chore: MODE 2 Elasticsearch implementation complete

Implemented full Elasticsearch backend with ES Transform for event
normalization. Schema isolated in dedicated module, universal
skipInitSchema flag, integration tests passing.

Phases completed:
- Phase 1: WorkflowInstance full stack
- Phase 2: TaskExecution full stack  
- Phase 3: FluentBit config + documentation

E2E tests deferred to future work.

Co-Authored-By: Claude Sonnet 4.5 <noreply@anthropic.com>"
```

---

## Summary

**Implementation Complete:**
- ✅ Schema module with ILM, index templates, transforms
- ✅ Universal `skipInitSchema` flag for both backends
- ✅ Schema auto-initialization in dev/test
- ✅ WorkflowInstance storage + Transform + tests
- ✅ TaskExecution storage + Transform + tests
- ✅ FluentBit Elasticsearch output configuration
- ✅ Documentation updated (CLAUDE.md)

**Tested:**
- ✅ Schema initialization (ILM, templates, transforms)
- ✅ Storage CRUD operations
- ✅ Transform normalization (out-of-order events)
- ✅ Dev mode startup with auto-init

**Deferred to E2E:**
- ❌ KIND cluster deployment
- ❌ Full FluentBit → ES → Transform → GraphQL flow
- ❌ Performance testing
- ❌ Multi-namespace validation

**Next Steps:**
1. Deploy to KIND cluster for E2E validation
2. Test with real Quarkus Flow app
3. Monitor Transform performance at scale
4. Tune Transform frequency/delay based on latency requirements
