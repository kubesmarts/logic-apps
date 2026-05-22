# MODE 3 Presentation Slides and E2E Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 3 MODE 3 slides to the Data Index presentation and run e2e tests on KIND cluster for today's presentation

**Architecture:** Add slides 16-18 (MODE 3 architecture, three-way comparison, updated decision framework) to existing 15-slide presentation, then deploy MODE 1 to KIND and verify with GraphQL queries

**Tech Stack:** HTML5, SVG, Puppeteer (slide generation), KIND, Kubernetes, PostgreSQL, FluentBit, GraphQL

---

## Part 1: Presentation Updates

### Task 1: Create MODE 3 Architecture Diagram SVG

**Files:**
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/diagrams/mode3-architecture.svg`

- [ ] **Step 1: Create MODE 3 architecture SVG diagram**

```svg
<svg viewBox="0 0 900 550" xmlns="http://www.w3.org/2000/svg">
  <!-- Kafka Topics -->
  <rect x="350" y="20" width="200" height="60" rx="8" fill="#f3e5f5" stroke="#7b1fa2" stroke-width="2"/>
  <text x="450" y="45" text-anchor="middle" font-weight="bold" font-size="14" font-family="Arial">Kafka Topics</text>
  <text x="450" y="65" text-anchor="middle" font-size="11" fill="#666" font-family="Arial">CloudEvents Format</text>

  <defs>
    <marker id="arrow-m3" markerWidth="10" markerHeight="10" refX="9" refY="3" orient="auto">
      <polygon points="0 0, 10 3, 0 6" fill="#333" />
    </marker>
  </defs>

  <!-- Arrow -->
  <path d="M 450 80 L 450 120" stroke="#333" stroke-width="2" marker-end="url(#arrow-m3)" fill="none"/>
  <text x="480" y="105" font-size="11" fill="#999" font-family="Arial">workflow-events, task-events</text>

  <!-- Kafka Listener Service -->
  <rect x="300" y="120" width="300" height="80" rx="8" fill="#f3e5f5" stroke="#7b1fa2" stroke-width="2"/>
  <text x="450" y="145" text-anchor="middle" font-weight="bold" font-size="15" font-family="Arial">Kafka Listener Service</text>
  <text x="450" y="165" text-anchor="middle" font-size="11" fill="#666" font-family="Arial">Java/Quarkus Consumer</text>
  <text x="450" y="182" text-anchor="middle" font-size="11" fill="#666" font-family="Arial">Parse CloudEvents + Normalize</text>

  <!-- Arrow -->
  <path d="M 450 200 L 450 240" stroke="#333" stroke-width="2" marker-end="url(#arrow-m3)" fill="none"/>
  <text x="490" y="225" font-size="11" fill="#999" font-family="Arial">JDBC UPSERT</text>

  <!-- PostgreSQL Normalized Tables (no raw tables) -->
  <rect x="100" y="240" width="700" height="120" rx="8" fill="#e8f5e9" stroke="#2e7d32" stroke-width="2"/>
  <text x="450" y="265" text-anchor="middle" font-weight="bold" font-size="15" font-family="Arial">PostgreSQL Normalized Tables (Direct Write)</text>

  <rect x="130" y="280" width="300" height="65" rx="4" fill="white" stroke="#666" stroke-width="1"/>
  <text x="280" y="298" text-anchor="middle" font-size="12" font-family="monospace" font-weight="bold">workflow_instances</text>
  <text x="280" y="315" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">id, name, version, status</text>
  <text x="280" y="329" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">start, end, input, output</text>
  <text x="280" y="343" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">error (type, title, detail)</text>

  <rect x="470" y="280" width="300" height="65" rx="4" fill="white" stroke="#666" stroke-width="1"/>
  <text x="620" y="298" text-anchor="middle" font-size="12" font-family="monospace" font-weight="bold">task_instances</text>
  <text x="620" y="315" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">id, workflow_id, name, status</text>
  <text x="620" y="329" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">start, end, input, output</text>
  <text x="620" y="343" text-anchor="middle" font-size="9" fill="#666" font-family="Arial">error (type, title, detail)</text>

  <!-- Note box for "No triggers, No raw tables" -->
  <rect x="130" y="370" width="640" height="30" rx="4" fill="#f3e5f5" stroke="#7b1fa2" stroke-width="1"/>
  <text x="450" y="390" text-anchor="middle" font-size="11" fill="#7b1fa2" font-family="Arial">⚡ No triggers, No raw tables - Direct UPSERT with idempotency logic in Java</text>

  <!-- Arrow -->
  <path d="M 450 400 L 450 440" stroke="#333" stroke-width="2" marker-end="url(#arrow-m3)" fill="none"/>
  <text x="490" y="425" font-size="11" fill="#999" font-family="Arial">JPA/Hibernate</text>

  <!-- GraphQL API -->
  <rect x="350" y="440" width="200" height="50" rx="8" fill="#e3f2fd" stroke="#1976d2" stroke-width="2"/>
  <text x="450" y="465" text-anchor="middle" font-weight="bold" font-size="14" font-family="Arial">GraphQL API</text>
  <text x="450" y="482" text-anchor="middle" font-size="11" fill="#666" font-family="Arial">SmallRye GraphQL</text>

  <!-- Security badge -->
  <rect x="680" y="120" width="180" height="80" rx="8" fill="#fff9c4" stroke="#fbc02d" stroke-width="2"/>
  <text x="770" y="145" text-anchor="middle" font-weight="bold" font-size="12" fill="#f57c00" font-family="Arial">🔒 Security</text>
  <text x="770" y="162" text-anchor="middle" font-size="10" fill="#666" font-family="Arial">Kafka Encryption</text>
  <text x="770" y="177" text-anchor="middle" font-size="10" fill="#666" font-family="Arial">SASL/SSL Auth</text>
  <text x="770" y="192" text-anchor="middle" font-size="10" fill="#666" font-family="Arial">No log files</text>
</svg>
```

- [ ] **Step 2: Verify SVG renders correctly**

Open in browser: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/diagrams/mode3-architecture.svg`

Expected: Purple/violet color scheme, Kafka → Listener → PostgreSQL → GraphQL flow, security badge visible

- [ ] **Step 3: Commit diagram**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git add presentations/2026-05-20-data-index-poc/diagrams/mode3-architecture.svg
git commit -m "feat(presentation): add MODE 3 architecture diagram

- Kafka topics → Listener service → PostgreSQL (direct write)
- Purple/violet color scheme (#7b1fa2)
- Security badge highlighting Kafka encryption
- No raw tables or triggers (direct UPSERT)

Related to #23"
```

---

### Task 2: Update CSS for MODE 3 Badge Styling

**Files:**
- Modify: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/styles/slides.css:189-216`

- [ ] **Step 1: Add MODE 3 badge and box styles to CSS**

Add after line 196 (after `.badge-mode2`):

```css
.badge-mode3 {
  border: 3px solid #7b1fa2;
}

.box-purple {
  background: #f3e5f5;
  border: 3px solid #7b1fa2;
}

.badge-mode3 .badge-title {
  color: #7b1fa2;
}
```

- [ ] **Step 2: Verify CSS is valid**

Check syntax with: `cat /Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/styles/slides.css | grep -A 3 "badge-mode3"`

Expected: 3 new CSS rules for MODE 3 purple styling

- [ ] **Step 3: Commit CSS changes**

```bash
git add presentations/2026-05-20-data-index-poc/styles/slides.css
git commit -m "feat(presentation): add MODE 3 purple styling to CSS

- .badge-mode3 with purple border (#7b1fa2)
- .box-purple for MODE 3 content boxes
- Consistent with MODE 1 (blue) and MODE 2 (orange)

Related to #23"
```

---

### Task 3: Create Slide 16 - MODE 3 Architecture

**Files:**
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-16-mode3-arch.html`

- [ ] **Step 1: Create HTML template for Slide 16**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=1920, initial-scale=1.0">
  <title>MODE 3 Architecture</title>
  <link rel="stylesheet" href="../styles/slides.css">
</head>
<body>
  <div class="slide">
    <h2>MODE 3: Kafka + Direct Write (PostgreSQL)</h2>
    <p class="subtitle">Enterprise event ingestion with Kafka security</p>

    <div class="diagram-container">
      <object data="../diagrams/mode3-architecture.svg" type="image/svg+xml" style="width: 950px; height: 550px;"></object>
    </div>

    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 30px; margin-top: 10px;">
      <div>
        <h4 style="font-size: 24px; margin-bottom: 10px;">Key Characteristics:</h4>
        <ul style="font-size: 18px; line-height: 1.6;">
          <li>&lt; 10ms normalization</li>
          <li>At-least-once delivery</li>
          <li>Kafka encryption + auth</li>
          <li>Horizontal scaling</li>
        </ul>
      </div>
      <div>
        <h4 style="font-size: 24px; margin-bottom: 10px;">When to Choose MODE 3:</h4>
        <ul style="font-size: 18px; line-height: 1.6;">
          <li>Existing Kafka infrastructure</li>
          <li>Sensitive data (PII, credit cards)</li>
          <li>Kafka offset replay needed</li>
          <li>Direct write control preferred</li>
        </ul>
      </div>
    </div>
  </div>
</body>
</html>
```

- [ ] **Step 2: Verify HTML renders correctly**

Open in browser: `file:///Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-16-mode3-arch.html`

Expected: MODE 3 title, architecture diagram, two-column layout with characteristics and use cases

- [ ] **Step 3: Commit slide template**

```bash
git add presentations/2026-05-20-data-index-poc/templates/slide-16-mode3-arch.html
git commit -m "feat(presentation): add Slide 16 - MODE 3 Architecture

- Kafka → Listener Service → PostgreSQL flow
- Key characteristics: < 10ms, at-least-once, Kafka security
- When to choose: Existing Kafka, sensitive data, replay needs

Related to #23"
```

---

### Task 4: Create Slide 17 - Three-Way Comparison

**Files:**
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-17-three-way-comparison.html`

- [ ] **Step 1: Create HTML template for Slide 17**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=1920, initial-scale=1.0">
  <title>MODE 1 vs MODE 2 vs MODE 3 Comparison</title>
  <link rel="stylesheet" href="../styles/slides.css">
</head>
<body>
  <div class="slide">
    <h2>Storage Backend Comparison</h2>
    <p class="subtitle">Choose based on infrastructure and requirements</p>

    <div style="margin-top: 40px;">
      <table style="width: 100%; border-collapse: collapse; font-size: 15px;">
        <thead>
          <tr style="background: #f5f5f5; border-bottom: 3px solid #333;">
            <th style="padding: 12px; text-align: left; font-size: 17px;">Feature</th>
            <th style="padding: 12px; text-align: center; font-size: 17px; color: #1976d2;">MODE 1<br/><span style="font-size: 13px; font-weight: normal;">PostgreSQL</span></th>
            <th style="padding: 12px; text-align: center; font-size: 17px; color: #f57c00;">MODE 2<br/><span style="font-size: 13px; font-weight: normal;">Elasticsearch</span></th>
            <th style="padding: 12px; text-align: center; font-size: 17px; color: #7b1fa2;">MODE 3<br/><span style="font-size: 13px; font-weight: normal;">Kafka</span></th>
          </tr>
        </thead>
        <tbody>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Ingestion</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">FluentBit → Raw tables</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">FluentBit → Raw indices</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Kafka → Listener Service</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Normalization</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">PG Triggers (&lt; 1ms)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">ES Transforms (~1s)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Java Service (&lt; 10ms)</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Consistency</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">ACID transactions</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Eventual consistency</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">At-least-once delivery</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Scale Target</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">&lt; 50K workflows/day</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">&gt; 50K workflows/day</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">50K+ workflows/day</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Infrastructure</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">PostgreSQL only</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Elasticsearch cluster</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Kafka + PostgreSQL</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Security</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Log-based (standard)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Log-based (standard)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Kafka encryption + auth</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Replay</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Log file (idempotent)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Log file (idempotent)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Kafka offset replay</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd; background: #e8f5e9;">
            <td style="padding: 10px; font-weight: bold;">GraphQL API</td>
            <td style="padding: 10px; text-align: center; font-size: 16px; font-weight: bold; color: #2e7d32;" colspan="3">✅ Identical</td>
          </tr>
          <tr style="border-bottom: 1px solid #ddd;">
            <td style="padding: 10px; font-weight: bold;">Ops Complexity</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Low (triggers auto)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Medium (transform monitoring)</td>
            <td style="padding: 10px; text-align: center; font-size: 14px;">Medium (Kafka + service)</td>
          </tr>
          <tr style="background: #fffde7;">
            <td style="padding: 10px; font-weight: bold;">Best For</td>
            <td style="padding: 10px; text-align: center; font-size: 13px;">Simple deployments<br/>ACID required</td>
            <td style="padding: 10px; text-align: center; font-size: 13px;">Large scale<br/>Full-text search</td>
            <td style="padding: 10px; text-align: center; font-size: 13px;">Existing Kafka<br/>Sensitive data</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</body>
</html>
```

- [ ] **Step 2: Verify HTML renders correctly**

Open in browser: `file:///Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-17-three-way-comparison.html`

Expected: Three-column table with MODE 1 (blue), MODE 2 (orange), MODE 3 (purple) headers, GraphQL row highlighted green

- [ ] **Step 3: Commit slide template**

```bash
git add presentations/2026-05-20-data-index-poc/templates/slide-17-three-way-comparison.html
git commit -m "feat(presentation): add Slide 17 - Three-way comparison

- MODE 1 vs MODE 2 vs MODE 3 comparison table
- Color-coded headers (blue, orange, purple)
- GraphQL API row highlighted (identical across modes)
- Best For row shows key differentiators

Related to #23"
```

---

### Task 5: Create Slide 18 - Updated Decision Framework

**Files:**
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-18-decision-updated.html`

- [ ] **Step 1: Create HTML template for Slide 18**

```html
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=1920, initial-scale=1.0">
  <title>Architectural Decision Framework</title>
  <link rel="stylesheet" href="../styles/slides.css">
</head>
<body>
  <div class="slide">
    <h2>Choosing Your Storage Backend</h2>
    <p class="subtitle">Infrastructure-driven decision framework</p>

    <div style="margin-top: 30px;">
      <!-- Decision Tree -->
      <div style="background: #f5f5f5; padding: 30px; border-radius: 12px; margin-bottom: 30px;">
        <h3 style="font-size: 28px; margin-bottom: 20px; text-align: center;">Decision Tree</h3>
        <div style="font-size: 20px; line-height: 2.0; max-width: 800px; margin: 0 auto;">
          <div style="text-align: center; font-weight: bold; margin-bottom: 15px;">Start Here ↓</div>
          <div style="margin-left: 60px;">
            <div style="margin-bottom: 10px;">❓ <strong>Do you have existing Kafka infrastructure?</strong></div>
            <div style="margin-left: 40px; margin-bottom: 10px;">
              ✅ YES → <span style="color: #7b1fa2; font-weight: bold;">MODE 3 (Kafka + PostgreSQL)</span>
            </div>
            <div style="margin-left: 40px; margin-bottom: 10px;">❌ NO ↓</div>
            <div style="margin-left: 60px; margin-bottom: 10px;">
              ❓ <strong>Do you need full-text search or &gt; 50K workflows/day?</strong>
            </div>
            <div style="margin-left: 100px; margin-bottom: 10px;">
              ✅ YES → <span style="color: #f57c00; font-weight: bold;">MODE 2 (Elasticsearch + Transforms)</span>
            </div>
            <div style="margin-left: 100px; margin-bottom: 10px;">❌ NO ↓</div>
            <div style="margin-left: 140px;">
              → <span style="color: #1976d2; font-weight: bold;">MODE 1 (PostgreSQL + Triggers)</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Decision Factors Table -->
      <div>
        <h3 style="font-size: 24px; margin-bottom: 15px;">Decision Factors</h3>
        <table style="width: 100%; border-collapse: collapse; font-size: 15px;">
          <thead>
            <tr style="background: #f5f5f5; border-bottom: 2px solid #333;">
              <th style="padding: 10px; text-align: left;">Factor</th>
              <th style="padding: 10px; text-align: center; color: #1976d2;">MODE 1</th>
              <th style="padding: 10px; text-align: center; color: #f57c00;">MODE 2</th>
              <th style="padding: 10px; text-align: center; color: #7b1fa2;">MODE 3</th>
            </tr>
          </thead>
          <tbody>
            <tr style="border-bottom: 1px solid #ddd;">
              <td style="padding: 8px; font-weight: bold;">Primary Driver</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Simplicity, ACID</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Search, aggregations</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Security, existing Kafka</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
              <td style="padding: 8px; font-weight: bold;">Infrastructure</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">PostgreSQL</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Elasticsearch</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Kafka + PostgreSQL</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
              <td style="padding: 8px; font-weight: bold;">Operational Effort</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Low</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Medium</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Medium</td>
            </tr>
            <tr style="border-bottom: 1px solid #ddd;">
              <td style="padding: 8px; font-weight: bold;">Data Security</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Log-based (standard)</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Log-based (standard)</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">Kafka encryption</td>
            </tr>
            <tr>
              <td style="padding: 8px; font-weight: bold;">Migration Path</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">→ MODE 2 (scale)<br/>→ MODE 3 (security)</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">—</td>
              <td style="padding: 8px; text-align: center; font-size: 14px;">—</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- Key Recommendation Box -->
      <div class="box-green" style="margin-top: 25px; padding: 20px;">
        <h4 style="font-size: 22px; margin-bottom: 10px; color: #2e7d32;">Key Recommendations</h4>
        <ul style="font-size: 17px; line-height: 1.8;">
          <li>All three modes share <strong>identical GraphQL API</strong></li>
          <li>Choose based on <strong>infrastructure and requirements</strong>, not features</li>
          <li><strong>MODE 1 is default</strong>; MODE 2 for scale; MODE 3 for enterprise security</li>
        </ul>
      </div>
    </div>
  </div>
</body>
</html>
```

- [ ] **Step 2: Verify HTML renders correctly**

Open in browser: `file:///Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/templates/slide-18-decision-updated.html`

Expected: Decision tree flowchart, decision factors table, green recommendation box at bottom

- [ ] **Step 3: Commit slide template**

```bash
git add presentations/2026-05-20-data-index-poc/templates/slide-18-decision-updated.html
git commit -m "feat(presentation): add Slide 18 - Updated decision framework

- Decision tree: Kafka first, then search/scale, default MODE 1
- Decision factors table comparing all three modes
- Key recommendations box (identical GraphQL API)

Related to #23"
```

---

### Task 6: Update generate-slides.js

**Files:**
- Modify: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/generate-slides.js:5-21`

- [ ] **Step 1: Insert new slides and renumber existing slides 9-15 to 19-25**

Replace lines 5-21 with:

```javascript
const slides = [
  { name: 'slide-01-title', file: 'templates/slide-01-title.html' },
  { name: 'slide-02-migration', file: 'templates/slide-02-migration.html' },
  { name: 'slide-03-event-flow', file: 'templates/slide-03-event-flow.html' },
  { name: 'slide-04-mode1-arch', file: 'templates/slide-04-mode1-arch.html' },
  { name: 'slide-05-mode1-trigger', file: 'templates/slide-05-mode1-trigger.html' },
  { name: 'slide-06-mode2-arch', file: 'templates/slide-06-mode2-arch.html' },
  { name: 'slide-07-mode2-transform', file: 'templates/slide-07-mode2-transform.html' },
  { name: 'slide-08-comparison', file: 'templates/slide-08-comparison.html' },
  { name: 'slide-16-mode3-arch', file: 'templates/slide-16-mode3-arch.html' },
  { name: 'slide-17-three-way-comparison', file: 'templates/slide-17-three-way-comparison.html' },
  { name: 'slide-18-decision-updated', file: 'templates/slide-18-decision-updated.html' },
  { name: 'slide-19-demo-setup', file: 'templates/slide-09-demo-setup.html' },
  { name: 'slide-20-demo-commands', file: 'templates/slide-10-demo-commands.html' },
  { name: 'slide-21-fluentbit', file: 'templates/slide-11-fluentbit.html' },
  { name: 'slide-22-log-replay', file: 'templates/slide-12-log-replay.html' },
  { name: 'slide-23-reliability', file: 'templates/slide-13-reliability.html' },
  { name: 'slide-24-decision', file: 'templates/slide-14-decision.html' },
  { name: 'slide-25-redhat', file: 'templates/slide-15-redhat.html' }
];
```

- [ ] **Step 2: Verify JavaScript syntax**

Run: `node -c /Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/generate-slides.js`

Expected: No output (syntax valid)

- [ ] **Step 3: Commit generator script changes**

```bash
git add presentations/2026-05-20-data-index-poc/generate-slides.js
git commit -m "feat(presentation): update slide generator for MODE 3 slides

- Insert slides 16-18 after slide 8
- Renumber existing slides 9-15 to 19-25
- Total slides: 18 (was 15)

Related to #23"
```

---

### Task 7: Update SLIDES.md Documentation

**Files:**
- Modify: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/SLIDES.md:1-7`
- Modify: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/SLIDES.md:62-82`

- [ ] **Step 1: Update header metadata**

Replace lines 1-7 with:

```markdown
# Data Index v1.0.0 POC Presentation Slides

**Generated:** 2026-05-22  
**Total Slides:** 18  
**Format:** PNG (1920x1080)  
**Target Audience:** Backend engineers + technical leadership  
**Duration:** 35-50 minutes
```

- [ ] **Step 2: Add MODE 3 slides section and renumber demo section**

Replace lines 62-82 with:

```markdown
---

### MODE 3: Kafka + Direct Write (Slides 16-18)

**Slide 16: MODE 3 Architecture**
- Kafka-based event ingestion architecture
- Kafka Topics → Listener Service → PostgreSQL (direct write, no triggers/raw tables)
- Key characteristics: < 10ms normalization, at-least-once delivery, Kafka encryption
- When to choose MODE 3: Existing Kafka, sensitive data, offset replay

**Slide 17: MODE 1 vs MODE 2 vs MODE 3 Comparison**
- Three-way comparison table
- All dimensions: ingestion, normalization, consistency, scale, security, replay
- GraphQL API identical across all modes (highlighted row)
- Best For row: Simple/ACID (MODE 1), Scale/Search (MODE 2), Kafka/Security (MODE 3)

**Slide 18: Architectural Decision Framework (Updated)**
- Decision tree: Kafka first, then search/scale, default MODE 1
- Decision factors table: primary driver, infrastructure, operational effort, security
- Key recommendations: identical GraphQL API, choose by infrastructure, MODE 1 default

---

### Demo (Slides 19-20)

**Slide 19: Demo Environment Setup** (was Slide 9)
- KIND cluster deployment scripts
- MODE 1 components: PostgreSQL + FluentBit + Data Index + Workflow App
- MODE 2 components: Elasticsearch + FluentBit + Data Index + Workflow App

**Slide 20: Demo Commands** (was Slide 10)
- Verify components running
- Trigger workflow execution
- Watch events flow (FluentBit logs)
- Query via GraphQL
- Verify data normalization
```

- [ ] **Step 3: Update capacity section slide numbers**

Replace lines 84-105 (Capacity & Tuning section header) with:

```markdown
---

### Capacity & Tuning (Slides 21-23)

**Slide 21: FluentBit Scalability** (was Slide 11)
```

Continue pattern for slides 22-23 (old 12-13).

- [ ] **Step 4: Update decision framework section slide numbers**

Replace lines 107-120 (Decision Framework & Productization section) with:

```markdown
---

### Decision Framework & Productization (Slides 24-25)

**Slide 24: Architectural Decision Framework** (was Slide 14)
- Choose MODE 1 if: < 50K workflows/day, ACID required, simpler operations
- Choose MODE 2 if: > 50K workflows/day, full-text search, complex aggregations
- Decision factors table: Scale threshold, consistency, operational complexity, migration path
- NOTE: This is the old two-way decision framework; Slide 18 is the updated three-way version

**Slide 25: Red Hat Productization** (was Slide 15)
```

- [ ] **Step 5: Update file structure section**

Replace slide filenames in lines 122-142 to include slides 16-25.

- [ ] **Step 6: Update presentation flow section**

Replace lines 179-200 (Presentation Flow) with:

```markdown
## Presentation Flow (35-50 min)

### Introduction (5 min)
- Slides 1-3: Context, migration rationale, overall architecture

### Technical Deep Dive (20-25 min)
- Slides 4-5: MODE 1 architecture & logic
- Slides 6-7: MODE 2 architecture & logic
- Slide 8: MODE 1 vs MODE 2 comparison (two-way)
- Slides 16-17: MODE 3 architecture & three-way comparison
- Slide 18: Updated decision framework (three-way)

### Live Demo (10-15 min)
- Slides 19-20: Demo setup and commands
- Live demonstration on KIND cluster (MODE 1)

### Capacity & Tuning Discussion (5-10 min)
- Slides 21-23: FluentBit scalability, log replay, reliability

### Decision Making (5-10 min)
- Slide 24: Two-way decision framework (legacy)
- Slide 25: Red Hat productization strategy
- Team discussion and Q&A
```

- [ ] **Step 7: Update key messages**

Add to line 214 (after existing key messages):

```markdown
8. **Three Storage Modes:** PostgreSQL (default), Elasticsearch (scale), Kafka (enterprise security)
9. **Infrastructure-Driven Choice:** Existing Kafka → MODE 3, else MODE 2 for scale, else MODE 1
```

- [ ] **Step 8: Commit documentation changes**

```bash
git add presentations/2026-05-20-data-index-poc/SLIDES.md
git commit -m "docs(presentation): update SLIDES.md for MODE 3 content

- Total slides: 18 (was 15)
- Add MODE 3 section (slides 16-18)
- Renumber demo and capacity sections (19-25)
- Update presentation flow (35-50 min duration)

Related to #23"
```

---

### Task 8: Generate Slide PNGs

**Files:**
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-16-mode3-arch.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-17-three-way-comparison.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-18-decision-updated.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-19-demo-setup.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-20-demo-commands.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-21-fluentbit.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-22-log-replay.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-23-reliability.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-24-decision.png`
- Create: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-25-redhat.png`

- [ ] **Step 1: Clean old output directory**

```bash
rm -f /Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-*.png
```

Expected: All old PNG files removed

- [ ] **Step 2: Generate all slides**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc
npm run generate
```

Expected output:
```
Starting slide generation...

Generating slide-01-title...
✓ slide-01-title.png
...
Generating slide-16-mode3-arch...
✓ slide-16-mode3-arch.png
Generating slide-17-three-way-comparison...
✓ slide-17-three-way-comparison.png
Generating slide-18-decision-updated...
✓ slide-18-decision-updated.png
...
✓ All slides generated successfully!
Output: presentation/output/
```

- [ ] **Step 3: Verify new slides exist**

```bash
ls -lh /Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/slide-{16,17,18}*.png
```

Expected: 3 PNG files (slide-16-mode3-arch.png, slide-17-three-way-comparison.png, slide-18-decision-updated.png), each 200-400 KB

- [ ] **Step 4: Commit generated slides**

```bash
git add presentations/2026-05-20-data-index-poc/output/*.png
git commit -m "feat(presentation): generate MODE 3 slides and renumbered slides

- New slides: 16 (MODE 3 arch), 17 (three-way comparison), 18 (decision)
- Renumbered slides: 19-25 (previously 9-15)
- Total presentation: 18 slides (1920x1080 PNG)

Related to #23"
```

---

## Part 2: E2E Tests on KIND

### Task 9: Create Git Branch for Work

**Files:**
- None (git operation)

- [ ] **Step 1: Create and checkout new branch**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
git checkout -b presentation/mode3-slides-e2e-tests
```

Expected: `Switched to a new branch 'presentation/mode3-slides-e2e-tests'`

- [ ] **Step 2: Verify branch created**

```bash
git branch --show-current
```

Expected: `presentation/mode3-slides-e2e-tests`

- [ ] **Step 3: Push branch to remote**

```bash
git push -u origin presentation/mode3-slides-e2e-tests
```

Expected: Branch pushed to remote with tracking set

---

### Task 10: Setup KIND Cluster

**Files:**
- None (infrastructure setup)

- [ ] **Step 1: Check if KIND cluster exists**

```bash
kind get clusters
```

Expected: List of existing clusters (may be empty) or `No kind clusters found.`

- [ ] **Step 2: Delete existing cluster if present**

```bash
kind delete cluster --name data-index-demo 2>/dev/null || true
```

Expected: Cluster deleted or no-op if not present

- [ ] **Step 3: Create new KIND cluster**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/kind
./setup-cluster.sh
```

Expected output:
```
Creating kind cluster...
✓ Control plane node ready
✓ Worker nodes ready
✓ Cluster created successfully
```

- [ ] **Step 4: Verify cluster is running**

```bash
kubectl cluster-info --context kind-data-index-demo
```

Expected:
```
Kubernetes control plane is running at https://127.0.0.1:...
CoreDNS is running at https://127.0.0.1:.../api/v1/namespaces/kube-system/services/kube-dns:dns/proxy
```

---

### Task 11: Deploy PostgreSQL and Data Index Service (MODE 1)

**Files:**
- None (deployment operations)

- [ ] **Step 1: Install PostgreSQL with Helm**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/kind
MODE=postgresql ./install-dependencies.sh
```

Expected output:
```
Installing PostgreSQL...
✓ PostgreSQL Helm chart installed
✓ PostgreSQL pod running
✓ Database initialized
```

- [ ] **Step 2: Wait for PostgreSQL to be ready**

```bash
kubectl wait --for=condition=ready pod -l app.kubernetes.io/name=postgresql -n data-index --timeout=300s
```

Expected: `pod/postgresql-0 condition met`

- [ ] **Step 3: Deploy Data Index service**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/kind
./deploy-data-index.sh postgresql
```

Expected output:
```
Building data-index container image...
✓ Image built: kubesmarts/data-index-service:999-SNAPSHOT
Loading image to KIND cluster...
✓ Image loaded
Applying Kubernetes manifests...
✓ Namespace created
✓ ConfigMap created
✓ Deployment created
✓ Service created
```

- [ ] **Step 4: Wait for Data Index to be ready**

```bash
kubectl wait --for=condition=ready pod -l app=data-index -n data-index --timeout=300s
```

Expected: `pod/data-index-... condition met`

- [ ] **Step 5: Verify Data Index is healthy**

```bash
kubectl port-forward -n data-index svc/data-index 8080:8080 &
sleep 3
curl -f http://localhost:8080/q/health/ready
```

Expected: `{"status":"UP",...}`

---

### Task 12: Deploy FluentBit DaemonSet (MODE 1)

**Files:**
- None (deployment operations)

- [ ] **Step 1: Generate FluentBit ConfigMap**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/fluentbit/mode1-postgresql-triggers
./generate-configmap.sh
```

Expected output:
```
Generating FluentBit ConfigMap...
✓ ConfigMap generated: kubernetes/configmap.yaml
```

- [ ] **Step 2: Apply FluentBit ConfigMap**

```bash
kubectl apply -f /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/fluentbit/mode1-postgresql-triggers/kubernetes/configmap.yaml
```

Expected: `configmap/workflows-fluent-bit-config created`

- [ ] **Step 3: Deploy FluentBit DaemonSet**

```bash
kubectl apply -f /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/fluentbit/mode1-postgresql-triggers/kubernetes/daemonset.yaml
```

Expected: `daemonset.apps/workflows-fluent-bit-mode1 created`

- [ ] **Step 4: Verify FluentBit pods are running**

```bash
kubectl wait --for=condition=ready pod -l app=workflows-fluent-bit-mode1 -n logging --timeout=120s
```

Expected: `pod/workflows-fluent-bit-mode1-... condition met` (one per node)

---

### Task 13: Deploy Test Workflow Application

**Files:**
- None (deployment operations)

- [ ] **Step 1: Deploy test workflow app**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps/data-index/scripts/kind
./deploy-workflow-app.sh
```

Expected output:
```
Building workflow-app container image...
✓ Image built
Loading image to KIND cluster...
✓ Image loaded
Deploying workflow app...
✓ Deployment created
✓ Service created
```

- [ ] **Step 2: Wait for workflow app to be ready**

```bash
kubectl wait --for=condition=ready pod -l app=workflow-app -n workflows --timeout=120s
```

Expected: `pod/workflow-app-... condition met`

- [ ] **Step 3: Trigger test workflow execution**

```bash
kubectl port-forward -n workflows svc/workflow-app 8081:8080 &
sleep 3
curl -X POST http://localhost:8081/workflows/test-workflow \
  -H "Content-Type: application/json" \
  -d '{"orderId": "12345", "customerId": "customer-1"}'
```

Expected: `{"id":"...", "status":"RUNNING"}`

- [ ] **Step 4: Wait for events to be processed**

```bash
sleep 5
```

Expected: FluentBit ingests logs, triggers normalize, data appears in PostgreSQL

---

### Task 14: Verify E2E Flow with GraphQL Queries

**Files:**
- None (verification)

- [ ] **Step 1: Query workflow instances via GraphQL**

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ getWorkflowInstances { id name status start } }"
  }'
```

Expected:
```json
{
  "data": {
    "getWorkflowInstances": [
      {
        "id": "...",
        "name": "test-workflow",
        "status": "COMPLETED",
        "start": "2026-05-22T..."
      }
    ]
  }
}
```

- [ ] **Step 2: Query task executions for workflow**

```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ getWorkflowInstances { id taskExecutions { id name status } } }"
  }'
```

Expected: Workflow with nested task executions (at least 1 task)

- [ ] **Step 3: Verify data normalization (check PostgreSQL directly)**

```bash
kubectl exec -it -n data-index postgresql-0 -- psql -U postgres -d dataindex -c "SELECT id, name, status, start FROM workflow_instances;"
```

Expected: Table with workflow instance data matching GraphQL query

- [ ] **Step 4: Check FluentBit logs for ingestion**

```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50 | grep -i "workflow\|task"
```

Expected: Log lines showing FluentBit processing workflow/task events

---

### Task 15: Leave Cluster Ready for Presentation

**Files:**
- None (verification)

- [ ] **Step 1: Stop port-forward processes**

```bash
pkill -f "kubectl port-forward" || true
```

Expected: All port-forward processes killed

- [ ] **Step 2: Verify all components are healthy**

```bash
kubectl get pods -A | grep -E "data-index|postgresql|fluent-bit|workflow-app"
```

Expected: All pods in `Running` state, `READY` column shows all containers ready (e.g., `1/1`)

- [ ] **Step 3: Create quick reference for presentation**

```bash
cat > /Users/ricferna/dev/github/kubesmarts/logic-apps/PRESENTATION_QUICK_START.md <<'EOF'
# Data Index Presentation - Quick Start

**Cluster:** KIND `data-index-demo`  
**Date:** 2026-05-22

## Components Running

- PostgreSQL (data-index namespace)
- Data Index Service (data-index namespace, MODE 1)
- FluentBit DaemonSet (logging namespace, MODE 1)
- Workflow App (workflows namespace)

## Demo Commands

### 1. Port-forward Data Index
```bash
kubectl port-forward -n data-index svc/data-index 8080:8080 &
```

### 2. Trigger workflow
```bash
kubectl port-forward -n workflows svc/workflow-app 8081:8080 &
curl -X POST http://localhost:8081/workflows/test-workflow \
  -H "Content-Type: application/json" \
  -d '{"orderId": "99999", "customerId": "demo-customer"}'
```

### 3. Query via GraphQL
```bash
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ getWorkflowInstances { id name status start taskExecutions { id name status } } }"}'
```

### 4. Watch FluentBit logs
```bash
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 -f
```

### 5. Check PostgreSQL
```bash
kubectl exec -it -n data-index postgresql-0 -- psql -U postgres -d dataindex
\dt
SELECT * FROM workflow_instances;
```

## Slides

Presentation slides: `/Users/ricferna/dev/github/kubesmarts/logic-apps/presentations/2026-05-20-data-index-poc/output/`

Total slides: 18 (MODE 1, MODE 2, MODE 3 architecture + comparison)

## Cleanup (after presentation)

```bash
kind delete cluster --name data-index-demo
```
EOF
```

Expected: Quick reference file created

- [ ] **Step 4: Verify quick reference file**

```bash
cat /Users/ricferna/dev/github/kubesmarts/logic-apps/PRESENTATION_QUICK_START.md | head -20
```

Expected: Quick reference content visible

- [ ] **Step 5: Final commit**

```bash
git add PRESENTATION_QUICK_START.md
git commit -m "docs: add presentation quick start guide

- KIND cluster setup and running
- MODE 1 e2e tests passing
- GraphQL queries verified
- FluentBit ingestion working
- Demo commands ready for presentation

Related to #23"
```

---

## Execution Summary

**Part 1: Presentation Updates (Tasks 1-8)**
- 3 new slides (MODE 3 architecture, three-way comparison, decision framework)
- Updated slide generator and documentation
- Total slides: 18 (was 15)
- Duration: 35-50 minutes (was 30-45)

**Part 2: E2E Tests on KIND (Tasks 9-15)**
- KIND cluster created and configured
- MODE 1 deployed: PostgreSQL + FluentBit + Data Index + Workflow App
- E2E flow verified: Workflow execution → FluentBit → PostgreSQL triggers → GraphQL API
- Quick reference guide created for presentation demo

**Deliverables:**
- ✅ MODE 3 slides added to presentation
- ✅ E2E tests running on KIND
- ✅ Cluster ready for live demo
- ✅ Quick start guide for presentation

**Branch:** `presentation/mode3-slides-e2e-tests`

**Related Issue:** #23 - Implement Kafka-based event ingestion service for PostgreSQL backend (MODE 3)
