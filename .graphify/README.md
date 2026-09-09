# Knowledge Graph

This directory contains a complete knowledge graph of the Data Index codebase, generated using [graphify](https://github.com/safishamsi/graphify).

## What's in here

- **graph.html** (2.7MB) - Interactive visualization - open in any browser
- **graph.json** (3.6MB) - Raw graph data (nodes, edges, communities)
- **GRAPH_REPORT.md** (23KB) - Analysis report with god nodes and surprising connections
- **manifest.json** (74KB) - File tracking for incremental updates
- **cache/** (7MB) - Extraction cache for fast `--update` runs
- **cost.json** - Token usage tracking

## Quick start

**Browse the graph:**
```bash
open .graphify/graph.html
# or
open graphify-out/graph.html
```

**Query the graph:**
```bash
graphify query "How do MODE 1, MODE 2, and MODE 3 converge on the same GraphQL API?"
graphify query "What are all the storage implementations?"
graphify path "WorkflowInstance" "PostgreSQL"
graphify explain "AttributeFilter"
```

**Update after code changes:**
```bash
graphify --update
```

## Graph statistics

- **Nodes:** 1,743
  - Code entities: 1,504 (from AST extraction)
  - Documentation: 240 (from semantic extraction)
- **Edges:** 3,949 relationships
- **Communities:** 129 clusters of related functionality
- **Cost:** 576,976 input tokens (~$0.07 with Gemini Flash)

## God nodes (most connected)

1. `WorkflowInstance` (77 edges) - Central domain model
2. `TaskExecution` (75 edges) - Task execution abstraction
3. `AttributeFilter` (59 edges) - Query filter interface
4. `WorkflowInstanceEntity` (55 edges) - JPA/PostgreSQL entity
5. `TaskInstanceEntity` (54 edges) - JPA/PostgreSQL entity

## Auto-updates

This graph is automatically updated by GitHub Actions on every push to `main`:
- Workflow: `.github/workflows/update-knowledge-graph.yml`
- Uses `graphify --update` for incremental updates (fast, only processes changed files)
- Commits changes back to the repository with `[skip ci]`

## Regenerating manually

If you need to rebuild from scratch:

```bash
# Full rebuild
rm -rf .graphify/
graphify .

# Or keep the cache and just regenerate outputs
graphify --update --force
```

## Learn more

- [graphify documentation](https://github.com/safishamsi/graphify)
- [CLAUDE.md](../CLAUDE.md) - See "Knowledge Graph (graphify)" section
