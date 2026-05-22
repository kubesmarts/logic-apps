# MODE 3 Kafka Presentation Slides Design

**Date:** 2026-05-22  
**Purpose:** Add MODE 3 (Kafka-based ingestion) slides to Data Index POC presentation  
**Scope:** 3 new slides (16-18) for architecture comparison and decision framework  
**Target Audience:** Backend engineers + technical leadership  
**Presentation Date:** 2026-05-22

---

## Overview

This design documents the addition of 3 slides to the existing Data Index v1.0.0 POC presentation to introduce MODE 3 (Kafka-based event ingestion for PostgreSQL). The slides present MODE 3 as a sequential evolution from MODE 1 and MODE 2, providing architectural comparison and decision criteria without committing to an implementation timeline.

### Context

- **Existing presentation:** 15 slides covering MODE 1 (PostgreSQL + Triggers) and MODE 2 (Elasticsearch + Transforms)
- **GitHub Issue:** #23 - Implement Kafka-based event ingestion service for PostgreSQL backend (MODE 3)
- **Status:** MODE 3 is designed but not yet implemented
- **Goal:** Present MODE 3 as a viable architectural option alongside MODE 1 and MODE 2

---

## Architecture

### Presentation Structure

**Current (15 slides):**
1. Introduction & Context (Slides 1-3)
2. MODE 1: PostgreSQL + Triggers (Slides 4-5)
3. MODE 2: Elasticsearch + Transforms (Slides 6-7)
4. Comparison & Demo (Slides 8-10)
5. Capacity & Tuning (Slides 11-13)
6. Decision Framework & Productization (Slides 14-15)

**New (18 slides total):**
- Add 3 new slides after Slide 8 (Comparison)
- Insert between current comparison and demo sections
- New slides: 16 (MODE 3 Architecture), 17 (Three-way comparison), 18 (Updated decision framework)
- Renumber existing slides 9-15 to 19-25

### Narrative Flow

The presentation now tells a complete architectural evolution story:

1. **Introduction:** Context and overall architecture
2. **Deep Dive:** MODE 1 (triggers), MODE 2 (transforms), MODE 3 (Kafka)
3. **Comparison:** Three-way comparison across all dimensions
4. **Decision:** How to choose the right mode
5. **Demo:** Live demonstration (MODE 1/MODE 2)
6. **Operations:** Tuning, reliability, productization

---

## Components

### Slide 16: MODE 3 Architecture

**Purpose:** Introduce MODE 3 architecture and key characteristics

**Visual Elements:**

1. **Title:** "MODE 3: Kafka + Direct Write (PostgreSQL)" with purple/violet badge (#7b1fa2)

2. **Architecture Diagram:**
   ```
   Quarkus Flow → Kafka Topics → Kafka Listener Service → PostgreSQL → GraphQL API
   (CloudEvents)     (workflow-events,    (Java/Quarkus)       (normalized     (SmallRye)
                      task-events)                              tables only)
   ```

3. **Key Characteristics Box:**
   - Normalization: Immediate (< 10ms)
   - Consistency: At-least-once delivery
   - Security: Kafka encryption + auth
   - Infrastructure: Leverages existing Kafka

4. **When to Choose MODE 3 Box:**
   - Existing Kafka infrastructure
   - Sensitive data concerns (PII, credit cards)
   - Need replay capabilities from Kafka offsets
   - Direct write control preferred over triggers

**Technical Details:**

- **No raw tables:** Unlike MODE 1, MODE 3 writes directly to normalized tables
- **No triggers:** Normalization logic implemented in Java (ported from SQL triggers)
- **Separate deployment:** Kafka Listener Service is a standalone pod (not part of data-index-service)
- **Same GraphQL API:** Identical interface to MODE 1 and MODE 2

**Color Scheme:**
- MODE 3 primary: Purple/Violet (#7b1fa2)
- Kafka: Purple (#7b1fa2)
- Listener Service: Purple (#7b1fa2)
- PostgreSQL: Blue (#1976d2) - reuse MODE 1 color
- GraphQL API: Green (#2e7d32) - consistent across all modes

---

### Slide 17: MODE 1 vs MODE 2 vs MODE 3 Comparison

**Purpose:** Comprehensive comparison across all three storage backends

**Visual Elements:**

1. **Title:** "Storage Backend Comparison"

2. **Three-column comparison table:**

| Feature | MODE 1 (PostgreSQL) | MODE 2 (Elasticsearch) | MODE 3 (Kafka) |
|---------|---------------------|------------------------|----------------|
| **Ingestion** | FluentBit → Raw tables | FluentBit → Raw indices | Kafka → Listener Service |
| **Normalization** | PostgreSQL Triggers (< 1ms) | ES Transforms (~1s) | Java Service (< 10ms) |
| **Consistency** | ACID transactions | Eventual consistency | At-least-once delivery |
| **Scale Target** | < 50K workflows/day | > 50K workflows/day | 50K+ workflows/day |
| **Infrastructure** | PostgreSQL only | Elasticsearch cluster | Kafka + PostgreSQL |
| **Security** | Log files (plaintext) | Log files (plaintext) | Kafka encryption + auth |
| **Replay** | Log file replay (idempotent) | Log file replay (idempotent) | Kafka offset replay |
| **GraphQL API** | ✅ Identical | ✅ Identical | ✅ Identical |
| **Operational Complexity** | Low (triggers automatic) | Medium (transform monitoring) | Medium (Kafka + service) |
| **Best For** | Simple deployments, ACID required | Large scale, full-text search | Existing Kafka, sensitive data |

**Visual Enhancements:**
- Color-coded column headers (Blue for MODE 1, Orange for MODE 2, Purple for MODE 3)
- Green checkmarks for "GraphQL API" row (all identical)
- "Best For" row highlighted with border or background color
- Bold text for category headers

**Key Messages:**
- All three modes share the same GraphQL API
- Infrastructure and operational complexity are the main differentiators
- MODE 3 offers unique security benefits via Kafka encryption

---

### Slide 18: Architectural Decision Framework (Updated)

**Purpose:** Guide selection between MODE 1, MODE 2, and MODE 3 based on requirements

**Visual Elements:**

1. **Title:** "Choosing Your Storage Backend"

2. **Decision Tree:**
   ```
   Start Here
       ↓
   Do you have existing Kafka infrastructure?
       ↓ YES → MODE 3 (Kafka + PostgreSQL)
       ↓ NO
       ↓
   Do you need full-text search or > 50K workflows/day?
       ↓ YES → MODE 2 (Elasticsearch + Transforms)
       ↓ NO
       ↓
   MODE 1 (PostgreSQL + Triggers)
   ```

3. **Decision Factors Table:**

| Factor | MODE 1 | MODE 2 | MODE 3 |
|--------|--------|--------|--------|
| **Scale Threshold** | < 50K workflows/day | > 50K workflows/day | 50K+ workflows/day |
| **Primary Driver** | Simplicity, ACID | Search, aggregations | Security, existing Kafka |
| **Infrastructure** | PostgreSQL | Elasticsearch | Kafka + PostgreSQL |
| **Operational Effort** | Low | Medium | Medium |
| **Data Security** | Log-based (standard) | Log-based (standard) | Kafka encryption |
| **Migration Path** | → MODE 2 (scale)<br>→ MODE 3 (security) | — | — |

4. **Key Recommendation Box:**
   - "All three modes share identical GraphQL API"
   - "Choose based on infrastructure and requirements, not features"
   - "MODE 1 is default; MODE 2 for scale; MODE 3 for enterprise security"

**Design Rationale:**
- Decision tree leads with infrastructure (Kafka) as primary decision point
- Positions MODE 1 as the default/simple choice
- MODE 2 for scale/search needs
- MODE 3 for enterprise security and existing Kafka investments

---

## Data Flow

### How MODE 3 Differs from MODE 1 and MODE 2

**MODE 1 (PostgreSQL + Triggers):**
```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                    ↓ (FluentBit tail)
            PostgreSQL raw tables (JSONB)
                    ↓ (BEFORE INSERT triggers)
            PostgreSQL normalized tables
                    ↓ (JPA/Hibernate)
            GraphQL API (SmallRye GraphQL)
```

**MODE 2 (Elasticsearch + Transforms):**
```
Quarkus Flow → /tmp/quarkus-flow-events.log (JSON)
                    ↓ (FluentBit tail)
            Elasticsearch raw indices (workflow-events, task-events)
                    ↓ (ES Transform, continuous, 1s)
            Elasticsearch normalized indices (workflow-instances, task-executions)
                    ↓ (Elasticsearch Java Client)
            GraphQL API (SmallRye GraphQL)
```

**MODE 3 (Kafka + Direct Write):**
```
Quarkus Flow → Kafka Topics (CloudEvents)
                    ↓ (workflow-events, task-events)
            Kafka Listener Service (Java/Quarkus)
                    ↓ (Parse CloudEvents, normalize in Java)
            PostgreSQL normalized tables ONLY
                    ↓ (JDBC UPSERT)
            GraphQL API (SmallRye GraphQL)
```

**Key Differences:**
- **No intermediate storage:** MODE 3 skips raw tables/indices entirely
- **No triggers/transforms:** Normalization happens in Java code before database write
- **Direct UPSERT:** Uses JDBC `INSERT ... ON CONFLICT DO UPDATE` directly
- **CloudEvents format:** Kafka messages use CloudEvents spec (structured event envelope)

---

## Implementation Details

### Slide Generation

**File Structure:**
```
presentations/2026-05-20-data-index-poc/
├── diagrams/
│   └── mode3-architecture.svg          # New MODE 3 architecture diagram
├── templates/
│   ├── slide-16-mode3-arch.html        # New MODE 3 architecture slide
│   ├── slide-17-three-way-comparison.html  # New 3-way comparison slide
│   └── slide-18-decision-updated.html  # Updated decision framework slide
├── output/
│   ├── slide-16-mode3-arch.png         # Generated PNG
│   ├── slide-17-three-way-comparison.png
│   └── slide-18-decision-updated.png
├── SLIDES.md                            # Updated with 3 new slides
└── generate-slides.js                   # Updated to generate slides 16-18
```

**Generation Process:**
1. Create MODE 3 architecture diagram SVG (diagrams/mode3-architecture.svg)
2. Create HTML templates for 3 new slides (templates/slide-16-*.html, slide-17-*.html, slide-18-*.html)
3. Update generate-slides.js to include new slides
4. Update SLIDES.md documentation with new slide descriptions
5. Run `npm run generate` to create PNG outputs

**Renumbering:**
- Existing slides 9-15 become 19-25
- Requires updating template filenames and generate-slides.js references

---

## Testing

### Validation Checklist

**Visual Consistency:**
- [ ] MODE 3 purple color (#7b1fa2) is distinct from MODE 1 blue and MODE 2 orange
- [ ] All three slides use consistent fonts, sizing, and layout
- [ ] Diagrams match the visual style of existing slides (clean, professional)
- [ ] Tables are readable at 1920x1080 resolution

**Content Accuracy:**
- [ ] Architecture diagram matches GitHub issue #23 description
- [ ] Comparison table accurately represents all three modes
- [ ] Decision framework logic is sound (Kafka first, then search/scale)
- [ ] No contradictions with existing slides 1-15

**Technical Correctness:**
- [ ] CloudEvents format mentioned (correct Kafka message format)
- [ ] Normalization logic described as "ported from triggers" (accurate)
- [ ] Scale targets are consistent (< 50K for MODE 1, > 50K for MODE 2, 50K+ for MODE 3)
- [ ] GraphQL API described as identical across all modes (correct)

**Presentation Flow:**
- [ ] Slides 16-18 fit naturally after Slide 8 (current comparison)
- [ ] Narrative arc: MODE 1 → MODE 2 → MODE 3 → Three-way comparison → Decision
- [ ] No redundancy with existing slides
- [ ] Smooth transition to demo section (now Slide 19)

---

## Error Handling

### Potential Issues and Mitigations

**Issue 1: MODE 3 appears more complex than MODE 1/2**
- **Mitigation:** Emphasize "existing Kafka infrastructure" benefit in "When to Choose" box
- **Mitigation:** Position as "enterprise option" for security and control

**Issue 2: Audience may think MODE 3 is required**
- **Mitigation:** Decision framework clearly shows MODE 1 as default
- **Mitigation:** "Choose based on infrastructure" messaging

**Issue 3: Implementation timeline questions**
- **Mitigation:** No roadmap slide (by design)
- **Mitigation:** If asked, refer to GitHub issue #23 (week of 2026-05-26 target)

**Issue 4: Security comparison may seem unfair to MODE 1/2**
- **Mitigation:** Use neutral language: "Log-based (standard)" vs "Kafka encryption"
- **Mitigation:** Emphasize all modes are production-ready and secure

---

## Key Principles

### Design Decisions

1. **Sequential Evolution Narrative:**
   - MODE 1 (simple, triggers) → MODE 2 (scale, search) → MODE 3 (security, enterprise)
   - Tells story of architectural maturity
   - Positions MODE 3 as additive, not replacement

2. **Identical GraphQL API:**
   - Emphasized on every slide
   - Reinforces that choice is infrastructure-driven, not feature-driven

3. **No Implementation Timeline:**
   - Avoids commitment pressure
   - Keeps focus on architecture and decision criteria
   - Leaves flexibility for post-presentation prioritization

4. **Security as Primary MODE 3 Differentiator:**
   - Kafka encryption + auth
   - Addresses Walter's concern from GitHub issue
   - Appeals to enterprise customers with compliance needs

5. **Infrastructure-First Decision Framework:**
   - "Do you have Kafka?" is first question
   - Avoids customers building Kafka just for MODE 3
   - Leverages existing investments

---

## Non-Requirements

### What This Design Does NOT Include

- ❌ **Implementation timeline slide:** No roadmap or delivery dates
- ❌ **Demo section for MODE 3:** Not yet implemented, can't demo
- ❌ **CODE examples:** No CloudEvents schemas or Java code snippets
- ❌ **Kafka configuration details:** No broker settings, topic naming, partitioning
- ❌ **Operator integration:** How operator deploys MODE 3 (future work)
- ❌ **Performance benchmarks:** No comparison data (not yet built)
- ❌ **Migration guides:** How to switch between modes (future documentation)

### Rationale

MODE 3 is designed but not implemented. These slides present the architecture and decision criteria without overcommitting or creating expectations for features that don't exist yet. The focus is on education and positioning, not delivery promises.

---

## Success Criteria

**Presentation Goals Met:**
- [ ] Audience understands MODE 3 architecture at high level
- [ ] Audience can explain when MODE 3 is appropriate vs MODE 1/2
- [ ] Audience sees MODE 3 as complementary (not replacement) to other modes
- [ ] No confusion about implementation status (not yet built)
- [ ] Smooth integration into existing 15-slide presentation

**Technical Accuracy:**
- [ ] Architecture diagram matches GitHub issue #23 design
- [ ] Comparison table is factually correct
- [ ] Decision framework logic is sound
- [ ] No contradictions with existing slides or CLAUDE.md

**Visual Quality:**
- [ ] Slides match existing design language
- [ ] MODE 3 purple color is professional and distinct
- [ ] Diagrams are clear and readable
- [ ] Tables fit on slide without requiring small fonts

---

## Future Work

### Post-Presentation Enhancements

**After MODE 3 Implementation (Week of 2026-05-26):**
1. Add demo section (slides showing MODE 3 deployment and testing)
2. Add performance benchmarks (MODE 1 vs MODE 3 comparison)
3. Add CloudEvents schema examples
4. Update decision framework with real-world data

**Documentation Updates:**
1. Create `data-index/docs/deployment/MODE3_KAFKA_INGESTION.md`
2. Update architecture diagrams in `data-index-docs/`
3. Add Kafka listener service configuration guide
4. Document migration paths between modes

**Presentation Maintenance:**
1. Create versioned presentation copies (pre-MODE3, post-MODE3)
2. Archive old slides for reference
3. Update SLIDES.md with MODE 3 demo section (future)

---

## References

- **GitHub Issue:** [#23 - Implement Kafka-based event ingestion service for PostgreSQL backend (MODE 3)](https://github.com/kubesmarts/logic-apps/issues/23)
- **Existing Presentation:** `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/`
- **CLAUDE.md:** `/Users/ricferna/dev/github/kubesmarts/logic-apps/CLAUDE.md` (current architecture documentation)
- **MODE 1 Documentation:** `data-index/docs/deployment/MODE1_HANDOFF.md`
- **MODE 2 Documentation:** `data-index/docs/deployment/MODE2_HANDOFF.md`

---

## Notes

- Target presentation date: 2026-05-22 (today)
- Presentation will be delivered before MODE 3 implementation begins
- Slides serve dual purpose: education (for team) and positioning (for stakeholders)
- MODE 3 is designed for customers with existing Kafka infrastructure and security requirements
- No commitment to delivery timeline during presentation (refer to GitHub issue if pressed)
