# Data Index POC Presentation Design

**Date:** 2026-05-20  
**Author:** Ricardo Zanini  
**Purpose:** Technical presentation for team meeting to share Data Index implementation with PostgreSQL and Elasticsearch backends

---

## Overview

This specification documents the design of a 15-slide technical presentation covering the Data Index v1.0.0 POC implementation. The presentation is targeted at backend engineers and technical leadership, with a 30-45 minute time allocation including live demo and Q&A.

## Audience

- **Primary:** Backend engineers (familiar with Kubernetes, databases)
- **Secondary:** Technical leadership (focused on architecture, scalability decisions)
- **Technical level:** Deep - includes implementation details, code samples, architecture diagrams

## Presentation Goals

1. Present the actual design of Data Index v1.0.0
2. Demonstrate Data Index running on KIND with workflows feeding runtime data
3. Show GraphQL queries against the backend
4. Facilitate architectural decision-making discussion
5. Address capacity/tuning concerns:
   - FluentBit handling massive event history
   - Replaying old logs from /var/log/kubernetes
   - Reliability of PostgreSQL triggers and Elasticsearch transforms

## Delivery Format

- **Medium:** PNG slide images with visual diagrams (SVG-based)
- **Demo:** Live demonstration on local KIND cluster
- **Interaction:** Open Q&A and architecture discussion

---

## Slide Structure

### Section 1: Opening & Context (Slides 1-3)

**Slide 1: Title Slide**
- Title: "Data Index v1.0.0 POC"
- Subtitle: "Read-Only Query Service for Open Workflows"
- Migration context: "OpenShift Serverless Logic → Quarkus Flow"
- Two mode badges: MODE 1 (PostgreSQL + Triggers), MODE 2 (Elasticsearch + Transforms)
- Date and organization footer

**Slide 2: Migration Context & Architectural Shift**
- Two-column comparison:
  - **Left (Old):** OpenShift Serverless Logic
    - SonataFlow (BPMN-based)
    - Kafka/Knative + CloudEvents
    - Event processor service
    - Multiple storage options (PostgreSQL/MongoDB/Infinispan)
    - GraphQL (ProcessInstances)
  - **Right (New):** Quarkus Flow + Data Index v1.0
    - Quarkus Flow (SW 1.0 spec)
    - Structured logs → FluentBit
    - DB triggers/transforms (no service!)
    - PostgreSQL OR Elasticsearch only
    - GraphQL (WorkflowInstances)
- Key improvements:
  - Simpler: No Kafka, no event processor service
  - Faster: Real-time normalization
  - Cloud-native: Kubernetes log infrastructure
  - Standards-aligned: SW 1.0.0 domain model

**Slide 3: High-Level Event Flow (Visual)**
- SVG diagram showing:
  1. Quarkus Flow App (executes workflows, JSON events → stdout)
  2. ↓ Kubernetes captures logs
  3. FluentBit DaemonSet (tails /var/log/containers/, filters JSON)
  4. ↓ Sends events to storage
  5. Storage Backend (PostgreSQL OR Elasticsearch)
  6. ↓ Normalized data
  7. GraphQL API (WorkflowInstances, TaskExecutions)
  8. ↓ User queries
- End-to-end latency annotation: ~5-10 seconds

### Section 2: MODE 1 - PostgreSQL Architecture (Slides 4-5)

**Slide 4: MODE 1 - PostgreSQL + Triggers Architecture (Visual)**
- SVG data flow diagram:
  1. FluentBit (batch INSERT)
  2. ↓
  3. PostgreSQL Raw Tables (workflow_events_raw, task_events_raw with JSONB)
  4. ↓ BEFORE INSERT triggers (< 1ms)
  5. PostgreSQL Normalized Tables (workflow_instances, task_instances)
  6. ↓ JPA/Hibernate
  7. GraphQL API
- Key characteristics callout box:
  - Normalization: < 1ms (synchronous triggers)
  - Consistency: ACID transactions
  - Scaling: Vertical only
  - JSONB storage for input/output
- When to choose MODE 1:
  - Standard relational queries sufficient
  - ACID guarantees important
  - Existing PostgreSQL infrastructure
  - Simpler operations
  - < 50K workflows/day

**Slide 5: MODE 1 - Trigger Logic & Idempotency (Visual + Code)**
- PostgreSQL trigger function code sample:
  ```sql
  CREATE FUNCTION normalize_workflow_event() ...
  ON CONFLICT (id) DO UPDATE SET
    -- Immutable fields: first value wins
    start = COALESCE(existing.start, new.start),
    -- Terminal fields: last non-null wins
    end = COALESCE(new.end, existing.end),
    -- Status: terminal states take precedence
    status = CASE WHEN existing.status IN ('COMPLETED', 'FAULTED', 'CANCELLED') ...
  ```
- Field-level idempotency rules:
  - Immutable (first wins): start, input, name, version
  - Terminal (last non-null wins): end, output, error
  - Status: terminal states > RUNNING > CREATED
- SVG diagram: Out-of-order event handling
  - Event 2 (completed) arrives first → DB has end but not start
  - Event 1 (started) arrives second → Trigger fills in start, keeps COMPLETED status
  - Final state: correct despite out-of-order

### Section 3: MODE 2 - Elasticsearch Architecture (Slides 6-7)

**Slide 6: MODE 2 - Elasticsearch + Transforms Architecture (Visual)**
- SVG data flow diagram:
  1. FluentBit (batch INDEX)
  2. ↓
  3. Elasticsearch Raw Indices (workflow-events-*, task-events-* daily indices, 30d ILM)
  4. ↓ Continuous transforms (1s frequency)
  5. Elasticsearch Normalized Indices (workflow-instances, task-executions, permanent)
  6. ↓ ES Java Client
  7. GraphQL API
- Key characteristics callout box:
  - Normalization: ~1s (asynchronous transforms)
  - Consistency: Eventual (~1s delay)
  - Scaling: Horizontal (ES cluster)
  - Nested object storage for input/output
  - Auto-retention via ILM
- When to choose MODE 2:
  - Full-text search needed
  - Complex aggregations required
  - > 50K workflows/day
  - Team has ES expertise
  - Horizontal scaling required

**Slide 7: MODE 2 - Transform Logic & Smart Filtering**
- Transform configuration sample (JSON):
  ```json
  {
    "source": {
      "query": {
        "bool": {
          "should": [
            {"range": {"@timestamp": {"gte": "now-1h"}}},
            {"bool": {"must_not": {"terms": {"status": ["COMPLETED", "FAULTED", "CANCELLED"]}}}}
          ]
        }
      }
    },
    "frequency": "1s"
  }
  ```
- Smart filtering strategy:
  - Recent events (< 1 hour): always process
  - Old events: only if NOT in terminal state
  - Performance: constant processing time (Day 1: 1K events, Day 365: still ~1K events)
  - Configurable time window (default 1h, 2-4h for high-throughput)
- Field-level idempotency (Painless script):
  - Immutable: `ctx.start = ctx.start ?: params.start`
  - Terminal: `ctx.end = params.end ?: ctx.end`
  - Status: terminal states take precedence

### Section 4: Comparison & Demo (Slides 8-10)

**Slide 8: MODE 1 vs MODE 2 Comparison (Visual + Table)**
- Visual comparison with colored circles:
  - Normalization speed: MODE 1 (< 1ms, green) vs MODE 2 (~1s, orange)
  - Consistency: MODE 1 (ACID, green) vs MODE 2 (Eventual, orange)
  - Throughput: MODE 1 (< 50K/day, orange) vs MODE 2 (100K+/day, green)
  - Scaling: MODE 1 (Vertical, red) vs MODE 2 (Horizontal, green)
  - Ops complexity: MODE 1 (Medium, green) vs MODE 2 (Higher, orange)
  - Full-text search: MODE 1 (Limited, orange) vs MODE 2 (Advanced, green)
- Detailed comparison table (11 rows):
  - Normalization speed, consistency, throughput, search, scaling, JSON queries, analytics, deployment complexity, operational familiarity, data retention
  - Identical GraphQL API row (highlighted)
- Decision guide:
  - Choose MODE 1 if: starting out, team knows PostgreSQL, ACID matters, < 50K/day
  - Choose MODE 2 if: need search, analytics required, high throughput, ES expertise

**Slide 9: Demo Setup & Architecture View**
- KIND cluster architecture:
  ```
  workflows namespace: sample-workflow-app
  logging namespace: fluent-bit-mode1 OR fluent-bit-mode2
  data-index namespace: postgresql/elasticsearch + data-index-service
  ```
- Demo flow options (side-by-side):
  - Option A (MODE 1): Show PostgreSQL, FluentBit config, trigger workflow, raw tables, normalized tables, GraphQL
  - Option B (MODE 2): Show Elasticsearch, FluentBit config, trigger workflow, raw indices, transform, GraphQL
- Demo tip: Have both deployed to quickly switch

**Slide 10: Demo Commands & GraphQL Queries**
- Command blocks for each demo step:
  1. Verify cluster: `kubectl get pods` in all namespaces
  2. Trigger workflow: `curl -X POST http://localhost:30000/greeting`
  3. MODE 1: PostgreSQL queries (`psql`, `SELECT * FROM workflow_events_raw`, `SELECT * FROM workflow_instances`)
  4. MODE 2: Elasticsearch queries (`curl http://localhost:9200/workflow-events-*/_count`, transform stats, normalized data)
  5. GraphQL query:
     ```graphql
     {
       WorkflowInstances {
         id name status start
         taskExecutions { id name status }
       }
     }
     ```
- Success criteria: Event → raw → normalized → GraphQL in < 10 seconds

### Section 5: Capacity & Tuning (Slides 11-13)

**Slide 11: FluentBit Scalability & Throughput (Visual)**
- SVG diagram: FluentBit DaemonSet architecture
  - Node 1: workflow-app-1, workflow-app-2 → /var/log/containers/ → FluentBit pod (~1-2K events/sec)
  - Node 2: workflow-app-3, workflow-app-4 → /var/log/containers/ → FluentBit pod (~1-2K events/sec)
  - Node N: ... (ellipsis indicating more nodes)
  - All FluentBit pods → Storage Backend
- Horizontal scaling callout: 10 nodes = 10 FluentBit pods = 10-20K events/sec
- Throughput characteristics table:
  - Events/sec per pod: MODE 1 (~1-2K), MODE 2 (~5-10K)
  - Scaling: Both per-node (DaemonSet)
  - Backpressure: Filesystem buffer (configurable)
  - Memory: ~50-100 MB per pod
- Bottleneck analysis:
  - MODE 1: PostgreSQL write throughput
  - MODE 2: Elasticsearch indexing throughput
  - FluentBit: Rarely the bottleneck
- Configuration tuning knobs (fluent-bit.conf sample)
- **Answer:** FluentBit scales horizontally with nodes; backend is the constraint, not FluentBit

**Slide 12: Log Replay Capability (Visual)**
- Current state: FluentBit `Read_from_Head = false` (tail only)
- Replay Scenario 1: Re-ingest specific log files
  - Stop FluentBit DaemonSet
  - Extract logs: `kubectl cp kube-node-1:/var/log/containers/...`
  - Modify config: `Read_from_Head = true`, `Exit_On_Eof = true`
  - Run FluentBit as Job (not DaemonSet)
- Replay Scenario 2: Reprocess raw events
  - MODE 1: Truncate normalized tables, re-insert from raw tables (triggers re-fire)
  - MODE 2: Delete normalized indices, restart transforms (re-process raw events)
- SVG diagram: Log replay scenario
  - Step 1: Extract 7 days of logs (500GB)
  - Step 2: Configure FluentBit Job
  - Step 3: Re-ingest + normalize (~50K events/min, ~2 hours)
  - Result: All events re-ingested correctly
- Idempotency guarantees: Re-ingesting same events is SAFE (no duplicates, out-of-order handled)
- **Answer:** YES, replay possible via log file replay OR raw event reprocessing

**Slide 13: Reliability & Failure Modes**
- PostgreSQL Trigger Reliability table:
  - Scenario: Trigger fails during INSERT → Behavior: Transaction rollback → Recovery: FluentBit retries
  - Scenario: PostgreSQL pod crashes → Behavior: FluentBit buffers to filesystem → Recovery: Events flushed when recovers
  - Scenario: Trigger has bug → Behavior: All INSERTs fail → Recovery: Fix trigger SQL, FluentBit retries
  - Scenario: Network partition → Behavior: FluentBit buffers to disk → Recovery: Events flushed when network recovers
- MODE 1 reliability note: Triggers are transactional (both raw and normalized, or neither)
- Elasticsearch Transform Reliability table:
  - Scenario: Transform fails → Behavior: Raw stored, checkpoint, retry → Recovery: Automatic retry (1s frequency)
  - Scenario: ES cluster disruption → Behavior: FluentBit buffers → Recovery: Transform catches up
  - Scenario: Transform has bug → Behavior: Transform stops, raw accumulates → Recovery: Fix config, restart, processes backlog
  - Scenario: Network partition → Behavior: FluentBit buffers, transform pauses → Recovery: Catches up when recovers
- MODE 2 reliability note: Eventually consistent (raw always stored first, transform catches up)
- Monitoring commands:
  - MODE 1: Compare row counts (raw vs normalized)
  - MODE 2: Transform stats API (documents_processed, index_failures, state)
  - FluentBit: Log errors
- **Answer:** Both reliable with different characteristics (MODE 1: synchronous/transactional, MODE 2: asynchronous/self-healing)

### Section 6: Decision & Productization (Slides 14-15)

**Slide 14: Architecture Decision Points & Discussion**
- Decision 1: Which storage backend?
  - Recommend MODE 1 if: uncertainty about scale, PostgreSQL expertise, < 50K/day, ACID required, simpler ops
  - Recommend MODE 2 if: search needed, analytics planned, > 50K/day, ES expertise, horizontal scaling
  - Migration path: Can switch later (same GraphQL API)
- Decision 2: Deployment strategy
  - Phase 1: MODE 1 for initial rollout (de-risk)
  - Phase 2: Evaluate usage, decide migration
  - Hybrid option: Run both during migration
- Decision 3: Namespace strategy table
  - Workflow apps: `workflows` (app team control)
  - FluentBit: `logging` or `kube-system` (platform/SRE control)
  - Data Index: `data-index` (service isolation)
- Open questions for discussion:
  1. Scale expectations (throughput)?
  2. Search requirements?
  3. Team expertise (ES capacity)?
  4. Multi-tenancy needs?
  5. Retention policy?
  6. Migration timeline?
  7. Observability needs?
- Next steps:
  - Immediate: Agree on storage backend
  - This week: Define deployment topology
  - Next week: Pilot in staging
  - 2 weeks: Load testing
  - 1 month: Production rollout decision
- Questions & Discussion callout

**Slide 15: Red Hat Productization Strategy**
- Two-column comparison:
  - **POC Implementation (current):**
    - FluentBit (lightweight, custom DaemonSet)
    - Custom ConfigMap
    - Community support (not Red Hat productized)
    - Trade-off: Faster/lighter but custom support required
  - **Red Hat Productization Path:**
    - Fluentd (Red Hat supported)
    - OpenShift Logging Operator
    - ClusterLogging CR / ClusterLogForwarder CR
    - Full Red Hat support (EFK stack)
    - Benefit: Integrated, supported, enterprise-ready
- SVG diagram: Two architecture options
  - Option 1 (Recommended): OpenShift Logging Operator → Fluentd → PostgreSQL OR Elasticsearch (EFK)
  - Option 2 (POC only): Custom FluentBit DaemonSet → PostgreSQL OR Elasticsearch
- Migration path table:
  - POC: FluentBit (custom), PostgreSQL/ES, Community support
  - Productization: Fluentd (OpenShift Logging), PostgreSQL/ES (EFK), Red Hat support
  - Migration effort: Low (same format, operator-managed)
- Red Hat Elasticsearch (EFK Stack) integration:
  - OpenShift Logging Operator deploys ES cluster, Fluentd, Kibana
  - ClusterLogForwarder configures event forwarding
  - Elasticsearch Operator manages cluster lifecycle
  - Data Index MODE 2 uses same ES cluster
  - Optional Kibana for visualization
- Recommendation:
  1. POC: Continue with FluentBit (fast iteration)
  2. Beta: Migrate to OpenShift Logging (Fluentd)
  3. GA: Full Operator integration
  4. MODE 2 + EFK: Leverage Red Hat Elasticsearch
- Open question: Productize with Fluentd (supported) or work to productize FluentBit (lighter/faster)?
  - Trade-off: Fluentd full support but higher resource usage (200-400MB vs 50-100MB per pod)

---

## Visual Design Approach

### Diagrams (SVG)
- **Event flow:** Top-to-bottom flow showing components with colored boxes, arrows, annotations
- **MODE 1 flow:** Database-centric with trigger timing emphasis
- **MODE 2 flow:** Transform-centric with smart filtering emphasis
- **FluentBit DaemonSet:** Multi-node view showing horizontal scaling
- **Idempotency:** Timeline-based visualization showing out-of-order event handling
- **Comparison:** Side-by-side with colored circles (green/orange/red) for quick visual scanning
- **Replay scenario:** Step-by-step process flow with progress bar
- **Productization:** Architecture comparison with OpenShift integration

### Color Coding
- **MODE 1 (PostgreSQL):** Blue (#1976d2)
- **MODE 2 (Elasticsearch):** Orange (#f57c00)
- **FluentBit:** Orange (#f57c00)
- **GraphQL API:** Green (#2e7d32)
- **Success/Positive:** Green (#4caf50)
- **Warning/Trade-off:** Orange (#ff9800)
- **Critical/Negative:** Red (#f44336)
- **Info boxes:** Light backgrounds with colored left borders

### Typography
- **Headers:** Bold, clear hierarchy (h2 > h3 > h4)
- **Code blocks:** Monospace font, light gray background
- **Tables:** Bordered, alternating row backgrounds for readability
- **Annotations:** Smaller font, gray color for secondary information

---

## Implementation Notes

### Live Demo Preparation
- **Pre-requisites:**
  - KIND cluster running with both MODE 1 and MODE 2 deployed
  - Sample workflow application deployed
  - Terminal windows pre-positioned:
    - kubectl commands
    - PostgreSQL psql (MODE 1)
    - Elasticsearch curl commands (MODE 2)
    - Browser with GraphQL UI
- **Backup plan:**
  - Screenshots of all demo steps in case live demo fails
  - Pre-recorded terminal session as fallback

### Q&A Preparation
- **Expected questions:**
  - "Why not use Kafka?" (Answer: Simpler, log-based is cloud-native, easier ops)
  - "What about data loss?" (Answer: FluentBit filesystem buffering, backend idempotency)
  - "Can we migrate between modes?" (Answer: Yes, same GraphQL API, export/import)
  - "Performance numbers?" (Answer: Slides 11-13 cover capacity)
  - "Red Hat support?" (Answer: Slide 15 covers Fluentd productization path)

### Slide Generation
- Convert HTML mockups to PNG images (1920x1080 resolution)
- Ensure all SVG diagrams render correctly
- Test readability on projected screen (not just laptop)
- Print speaker notes with slide thumbnails

---

## Success Criteria

1. **Understanding:** Team understands the two architecture modes and trade-offs
2. **Demo:** Live demo successfully shows event flow end-to-end
3. **Decision:** Team agrees on storage backend choice (MODE 1 or MODE 2)
4. **Confidence:** Capacity/tuning concerns addressed with concrete answers
5. **Next steps:** Clear action items and timeline agreed upon

---

## Document History

- **2026-05-20:** Initial design spec created
