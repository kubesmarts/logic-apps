# Data Index v1.0.0 POC Presentation Slides

**Generated:** 2026-05-20  
**Total Slides:** 15  
**Format:** PNG (1920x1080)  
**Target Audience:** Backend engineers + technical leadership  
**Duration:** 30-45 minutes

---

## Slide Index

### Introduction & Context (Slides 1-3)

**Slide 1: Title**
- Data Index v1.0.0 POC
- Migration from OpenShift Serverless Logic
- MODE 1 (PostgreSQL) and MODE 2 (Elasticsearch) badges

**Slide 2: Migration Context**
- Old: OpenShift Serverless Logic (SonataFlow, Kafka, Event Processor)
- New: Quarkus Flow + Data Index v1.0 (structured logs, FluentBit)
- Key improvements: Simpler, Faster, Cloud-native, Standards-aligned

**Slide 3: Event Flow Architecture**
- Overall architecture diagram
- Quarkus Flow → FluentBit → Storage Backend → GraphQL API
- Shows MODE 1 and MODE 2 storage options

---

### MODE 1: PostgreSQL + Triggers (Slides 4-5)

**Slide 4: MODE 1 Architecture**
- PostgreSQL trigger-based normalization
- FluentBit → Raw Tables → Triggers → Normalized Tables → GraphQL API
- Key characteristics: < 1ms normalization, ACID transactions
- When to choose MODE 1: < 50K workflows/day, ACID guarantees

**Slide 5: MODE 1 Trigger Logic & Idempotency**
- BEFORE INSERT trigger code (UPSERT with COALESCE)
- Out-of-order event handling diagram
- Idempotency rules: Immutable fields (first wins), Terminal fields (last non-null wins)

---

### MODE 2: Elasticsearch + Transforms (Slides 6-7)

**Slide 6: MODE 2 Architecture**
- Elasticsearch transform-based normalization
- FluentBit → Raw Indices → Transforms → Normalized Indices → GraphQL API
- Key characteristics: ~1s normalization, eventual consistency, horizontal scaling
- When to choose MODE 2: > 50K workflows/day, full-text search needed

**Slide 7: MODE 2 Transform Logic**
- Elasticsearch Transform configuration (Painless script)
- Smart filtering query optimization (recent events + active workflows)
- Idempotency rules in Painless
- Transform performance: constant processing time regardless of workflow count

---

### Comparison & Demo (Slides 8-10)

**Slide 8: MODE 1 vs MODE 2 Comparison**
- Visual comparison diagram
- Feature comparison table
- Same GraphQL API, different storage backends

**Slide 9: Demo Environment Setup**
- KIND cluster deployment scripts
- MODE 1 components: PostgreSQL + FluentBit + Data Index + Workflow App
- MODE 2 components: Elasticsearch + FluentBit + Data Index + Workflow App

**Slide 10: Demo Commands**
- Verify components running
- Trigger workflow execution
- Watch events flow (FluentBit logs)
- Query via GraphQL
- Verify data normalization

---

### Capacity & Tuning (Slides 11-13)

**Slide 11: FluentBit Scalability**
- DaemonSet architecture diagram (one pod per node)
- Horizontal scaling benefits
- Batch processing, filtering, backpressure
- Answer: Scales horizontally with cluster nodes

**Slide 12: Log Replay**
- Log replay scenario diagram
- Replaying old logs from /var/log/containers/
- Idempotency ensures safe replay
- Answer: Yes, UPSERT (MODE 1) and aggregation (MODE 2) prevent duplicates

**Slide 13: Reliability**
- MODE 1 reliability table: ACID, trigger failures, monitoring, testing
- MODE 2 reliability table: Eventual consistency, transform failures, metrics, testing
- Mitigation strategies for both modes
- Answer: Both reliable with proper monitoring (MODE 1 stronger consistency, MODE 2 better observability)

---

### Decision Framework & Productization (Slides 14-15)

**Slide 14: Architectural Decision Framework**
- Choose MODE 1 if: < 50K workflows/day, ACID required, simpler operations
- Choose MODE 2 if: > 50K workflows/day, full-text search, complex aggregations
- Decision factors table: Scale threshold, consistency, operational complexity, migration path

**Slide 15: Red Hat Productization**
- FluentBit vs Fluentd (EFK Stack) comparison diagram
- Feature comparison table
- Recommendation: Start with FluentBit (lighter), migrate to Fluentd only if EFK integration needed
- Red Hat EFK benefit: Single logging pipeline for all cluster logs

---

## File Structure

```
presentation/
├── output/                  # Generated PNG slides
│   ├── slide-01-title.png
│   ├── slide-02-migration.png
│   ├── slide-03-event-flow.png
│   ├── slide-04-mode1-arch.png
│   ├── slide-05-mode1-trigger.png
│   ├── slide-06-mode2-arch.png
│   ├── slide-07-mode2-transform.png
│   ├── slide-08-comparison.png
│   ├── slide-09-demo-setup.png
│   ├── slide-10-demo-commands.png
│   ├── slide-11-fluentbit.png
│   ├── slide-12-log-replay.png
│   ├── slide-13-reliability.png
│   ├── slide-14-decision.png
│   └── slide-15-redhat.png
├── templates/               # HTML slide templates
│   ├── slide-01-title.html
│   ├── slide-02-migration.html
│   ├── ... (15 total)
│   └── slide-15-redhat.html
├── diagrams/                # SVG diagrams
│   ├── event-flow.svg
│   ├── mode1-architecture.svg
│   ├── mode1-idempotency.svg
│   ├── mode2-architecture.svg
│   ├── mode-comparison.svg
│   ├── fluentbit-daemonset.svg
│   ├── log-replay-scenario.svg
│   └── redhat-productization.svg
├── styles/                  # CSS styling
│   └── slides.css
├── generate-slides.js       # Puppeteer slide generator
├── package.json             # Node.js project config
└── README.md                # Usage instructions
```

---

## Regenerating Slides

To regenerate all PNG slides:

```bash
cd presentation
npm install
npm run generate
```

Output files will be in `presentation/output/`.

---

## Presentation Flow (30-45 min)

### Introduction (5 min)
- Slides 1-3: Context, migration rationale, overall architecture

### Technical Deep Dive (15-20 min)
- Slides 4-7: MODE 1 architecture & logic, MODE 2 architecture & logic
- Slide 8: Feature comparison

### Live Demo (10-15 min)
- Slides 9-10: Demo setup and commands
- Live demonstration on KIND cluster

### Capacity & Tuning Discussion (5-10 min)
- Slides 11-13: FluentBit scalability, log replay, reliability

### Decision Making (5-10 min)
- Slide 14: Architectural decision framework
- Slide 15: Red Hat productization strategy
- Team discussion and Q&A

---

## Color Scheme

- **MODE 1 (PostgreSQL):** Blue (#1976d2)
- **MODE 2 (Elasticsearch):** Orange (#f57c00)
- **FluentBit:** Orange (#f57c00)
- **GraphQL API:** Green (#2e7d32)
- **Red Hat:** Red (#c00)
- **Warnings/Notes:** Yellow (#fbc02d)

---

## Key Messages

1. **Simpler Architecture:** No Kafka, no Event Processor service (removed in Phase 1)
2. **Dual-Mode Support:** PostgreSQL (triggers) OR Elasticsearch (transforms)
3. **Same GraphQL API:** Identical interface regardless of storage backend
4. **Production Ready:** Both modes fully implemented, tested, and documented
5. **Idempotent Design:** Safe replay of old logs, handles out-of-order events
6. **Horizontal Scaling:** FluentBit DaemonSet pattern scales with cluster nodes
7. **Flexible Productization:** FluentBit (current) or Fluentd (Red Hat EFK)
