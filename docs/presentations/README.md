# Presentations

This directory contains presentation materials for Data Index POC and related topics.

## Structure

Each presentation is organized in a dated subdirectory:

```
presentations/
├── YYYY-MM-DD-{topic}/
│   ├── templates/           # HTML slide templates
│   ├── diagrams/            # SVG diagrams
│   ├── styles/              # CSS styling
│   ├── output/              # Generated PNG slides
│   ├── generate-slides.js   # Slide generator script
│   ├── package.json         # Node.js dependencies
│   ├── README.md            # Usage instructions
│   └── SLIDES.md            # Slide index and notes
```

## Available Presentations

### 2026-05-20-data-index-poc

**Topic:** Data Index v1.0.0 POC Meeting  
**Slides:** 15  
**Format:** PNG (1920x1080)  
**Duration:** 30-45 minutes  
**Audience:** Backend engineers + technical leadership

Covers:
- Migration from OpenShift Serverless Logic to Quarkus Flow
- MODE 1 (PostgreSQL + Triggers) architecture
- MODE 2 (Elasticsearch + Transforms) architecture
- Live demo on KIND cluster
- Capacity & tuning discussion (FluentBit, log replay, reliability)
- Architectural decision framework
- Red Hat productization strategy

**Generate slides:**
```bash
cd presentations/2026-05-20-data-index-poc
npm install
npm run generate
```

---

## Creating New Presentations

1. Create a new dated directory:
   ```bash
   mkdir presentations/YYYY-MM-DD-{topic}
   ```

2. Copy the structure from an existing presentation or start fresh

3. Update this README with the new presentation details
