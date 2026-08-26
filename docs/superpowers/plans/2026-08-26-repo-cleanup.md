# Repository Cleanup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Clean up the repository by removing outdated content, unused code, and renaming "Serverless Workflow" to "Open Workflow" throughout the codebase.

**Architecture:** This is a comprehensive cleanup across documentation, code comments, and configuration files. Changes are primarily textual with no functional code changes.

**Tech Stack:** 
- Documentation: Markdown, AsciiDoc (Antora)
- Code: Java
- Configuration: YAML, Properties files

**Spec:** Self-contained cleanup based on current repository state.

## Global Constraints

- All changes must preserve existing functionality
- Update documentation immediately after code changes
- Keep CLAUDE.md as the authoritative guide
- Maintain consistency between CLAUDE.md and actual implementation
- No breaking API changes

---

## Task 1: Rename "Serverless Workflow" to "Open Workflow"

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `data-index/README.md`
- Modify: `data-index/data-index-docs/modules/ROOT/pages/index.adoc`
- Modify: `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApi.java`
- Modify: `data-index/data-index-model/README.md`
- Modify: `data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model/*.java` (5 files)
- Modify: `data-index/scripts/fluentbit/README.md`
- Modify: `docs/superpowers/plans/*.md` (4 files)
- Modify: `docs/superpowers/specs/*.md` (2 files)

**Interfaces:**
- Consumes: N/A (text-only changes)
- Produces: Updated terminology throughout repository

- [ ] **Step 1: Update root-level documentation**

Replace all occurrences of "Serverless Workflow" with "Open Workflow" in root README.md:

```bash
# Preview changes
grep -n "Serverless Workflow" README.md

# Apply changes
sed -i.bak 's/Serverless Workflow/Open Workflow/g' README.md
```

- [ ] **Step 2: Update CLAUDE.md**

Replace all occurrences in CLAUDE.md (lines 2, 28):

```bash
# Preview changes
grep -n "Serverless Workflow" CLAUDE.md

# Apply changes (2 occurrences)
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' CLAUDE.md
sed -i.bak 's/Serverless Workflow (SW 1.0.0)/Open Workflow (OW 1.0.0)/g' CLAUDE.md
```

- [ ] **Step 3: Update data-index README.md**

Replace all occurrences (lines 21, 58, 143, 512):

```bash
cd data-index
# Preview
grep -n "Serverless Workflow\|serverlessworkflow.io" README.md

# Apply changes
sed -i.bak 's/Serverless Workflow/Open Workflow/g' README.md
sed -i.bak 's/serverlessworkflow.io/openworkflow.io/g' README.md
```

- [ ] **Step 4: Update Antora documentation index**

Replace in `data-index/data-index-docs/modules/ROOT/pages/index.adoc`:

```bash
cd data-index/data-index-docs/modules/ROOT/pages
# Preview
grep -n "Serverless Workflow" index.adoc

# Apply
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' index.adoc
```

- [ ] **Step 5: Update Java code comments**

Update JavaDoc in model classes:

```bash
cd data-index/data-index-model/src/main/java/org/kubesmarts/logic/dataindex/model
# Preview
grep -r "Serverless Workflow" .

# Files to update:
# - TaskExecution.java
# - WorkflowInstanceStatus.java
# - Error.java (2 occurrences)
# - Workflow.java (3 occurrences)

# For each file:
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' Error.java
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' TaskExecution.java
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' WorkflowInstanceStatus.java
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' Workflow.java
```

- [ ] **Step 6: Update GraphQL API JavaDoc**

Update `data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApi.java`:

```bash
cd data-index/data-index-service/data-index-service-core/src/main/java/org/kubesmarts/logic/dataindex/graphql
# Preview
grep -n "Serverless Workflow" WorkflowInstanceGraphQLApi.java

# Apply
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' WorkflowInstanceGraphQLApi.java
```

- [ ] **Step 7: Update README files**

Update data-index module READMEs:

```bash
cd data-index
# Preview
grep -r "Serverless Workflow" */README.md

# Update data-index-model/README.md
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' data-index-model/README.md

# Update data-index-service/README.md
sed -i.bak 's/Serverless Workflow/Open Workflow/g' data-index-service/README.md

# Update scripts/fluentbit/README.md
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' scripts/fluentbit/README.md
```

- [ ] **Step 8: Update superpowers documentation**

Update planning and spec documents:

```bash
cd docs/superpowers
# Preview
grep -r "Serverless Workflow" plans/ specs/

# Update plans
sed -i.bak 's/Serverless Workflow/Open Workflow/g' plans/2026-05-20-data-index-presentation-slides.md
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' plans/2026-04-28-task-error-structure.md

# Update specs
sed -i.bak 's/Serverless Workflow 1.0.0/Open Workflow 1.0.0/g' specs/2026-04-28-task-error-structure-design.md
sed -i.bak 's/Serverless Workflow/Open Workflow/g' specs/2026-05-20-data-index-poc-presentation-design.md
```

- [ ] **Step 9: Clean up backup files**

Remove all .bak files created by sed:

```bash
find . -name "*.bak" -type f -delete
```

- [ ] **Step 10: Verify changes**

Search for any remaining "Serverless Workflow" references:

```bash
# Should return 0 or only node_modules references
grep -r "Serverless Workflow" --include="*.java" --include="*.md" --include="*.adoc" . 2>/dev/null | grep -v node_modules | wc -l
```

Expected: 0 results outside node_modules

- [ ] **Step 11: Commit terminology update**

```bash
git add -A
git commit -m "chore: rename Serverless Workflow to Open Workflow throughout codebase

- Update all documentation (README, CLAUDE.md, Antora docs)
- Update Java code comments and JavaDoc
- Update superpowers plans and specs
- Maintain consistency with Open Workflow 1.0.0 branding"
```

---

## Task 2: Remove Outdated data-index/README.md Content

**Files:**
- Modify: `data-index/README.md`

**Interfaces:**
- Consumes: Current data-index/README.md with v0.8 references, TBD items
- Produces: Streamlined README pointing to authoritative documentation

- [ ] **Step 1: Read current README**

Review the file to identify outdated sections:

```bash
cd data-index
cat README.md | grep -E "v0.8|TBD|TODO|Phase|Status.*Planned"
```

Expected issues:
- References to v0.8 migration
- "TBD" and "TODO" markers
- Outdated architecture descriptions
- Duplicate information with CLAUDE.md

- [ ] **Step 2: Create streamlined README**

Replace with concise version pointing to authoritative docs:

```markdown
# Data Index v1.0.0

**Query service for Open Workflow 1.0.0 execution data.**

**Status**: Production Ready (MODE 1, MODE 2 & MODE 3)

## Overview

Data Index provides a GraphQL API for querying workflow execution data from Quarkus Flow applications.

**Deployment Modes:**
- **MODE 1** (PostgreSQL + FluentBit + Triggers) - Production ready
- **MODE 2** (Elasticsearch + FluentBit + Transforms) - Production ready
- **MODE 3** (Kafka + SmallRye Reactive Messaging) - Production ready

## Quick Start

```bash
# Start with PostgreSQL backend (MODE 1)
cd data-index-service
mvn quarkus:dev -Dquarkus.profile=postgresql

# GraphQL UI: http://localhost:8080/graphql-ui
# Documentation: http://localhost:8080/docs
```

## Documentation

📚 **[Complete Documentation](data-index-docs/)** - Build with `mvn clean package`

**Key Resources:**
- [Getting Started](data-index-docs/modules/ROOT/pages/getting-started.adoc)
- [Architecture Overview](data-index-docs/modules/ROOT/pages/architecture/overview.adoc)
- [Deployment Guides](data-index-docs/modules/ROOT/pages/deployment/)
- [GraphQL API](data-index-docs/modules/ROOT/pages/api/)

For AI assistants working on this codebase, see [CLAUDE.md](../CLAUDE.md) for comprehensive guidelines.

## Project Structure

```
data-index/
├── data-index-docs/              # User-facing Antora documentation
├── data-index-model/             # Domain model
├── data-index-storage/           # Storage implementations (PostgreSQL, Elasticsearch)
├── data-index-service/           # Quarkus GraphQL service
├── data-index-ingestion/         # MODE 3 Kafka ingestion
├── data-index-integration-tests/ # E2E tests
├── workflow-test-app/            # Test workflow application
└── scripts/                      # Deployment scripts (KIND, FluentBit, Kafka)
```

## Build

```bash
mvn clean install
```

Requires Java 17 and Maven 3.9.11+.

## Configuration

See [Configuration Guide](data-index-docs/modules/ROOT/pages/developers/configuration.adoc) for all configuration options.

## License

Apache License 2.0
```

- [ ] **Step 3: Write new README**

Replace the content:

```bash
cd data-index
# Backup current version
cp README.md README.md.old

# Write new version (use Write tool with content above)
```

- [ ] **Step 4: Verify documentation links**

Check that referenced documentation files exist:

```bash
ls data-index-docs/modules/ROOT/pages/getting-started.adoc
ls data-index-docs/modules/ROOT/pages/architecture/overview.adoc
ls data-index-docs/modules/ROOT/pages/deployment/overview.adoc
ls data-index-docs/modules/ROOT/pages/api/graphql-overview.adoc
```

- [ ] **Step 5: Remove old README backup**

```bash
rm README.md.old
```

- [ ] **Step 6: Commit simplified README**

```bash
git add README.md
git commit -m "docs: simplify data-index README and remove outdated content

- Remove v0.8 migration references
- Remove TBD/TODO markers
- Remove duplicate architecture documentation
- Point to authoritative Antora documentation
- Align with production-ready status"
```

---

## Task 3: Remove/Update Outdated Deployment Documentation

**Files:**
- Modify: `data-index/docs/deployment/MODE2_IMPLEMENTATION_PLAN.md`
- Modify: `data-index/docs/README.md`

**Interfaces:**
- Consumes: Outdated implementation plan marked as "Planned"
- Produces: Accurate documentation reflecting completed implementation

- [ ] **Step 1: Check MODE2_IMPLEMENTATION_PLAN.md status**

Review file header:

```bash
cd data-index/docs/deployment
head -10 MODE2_IMPLEMENTATION_PLAN.md
```

Expected: Shows "Status: 📋 Planned" and "Target Date: TBD" (outdated - MODE 2 is complete)

- [ ] **Step 2: Decide on file disposition**

Options:
1. Delete (preferred) - MODE 2 is implemented, plan is historical
2. Move to archive
3. Update with "Status: ✅ Complete"

Decision: **Delete** - CLAUDE.md and Antora docs are authoritative

- [ ] **Step 3: Remove outdated implementation plan**

```bash
cd data-index/docs/deployment
git rm MODE2_IMPLEMENTATION_PLAN.md
```

- [ ] **Step 4: Update docs/README.md references**

Check if docs/README.md references the deleted file:

```bash
cd data-index/docs
grep -n "MODE2_IMPLEMENTATION_PLAN" README.md
```

If found, remove references.

- [ ] **Step 5: Verify deployment docs directory**

List remaining files:

```bash
ls -la data-index/docs/deployment/
```

Expected:
- MODE2_E2E_TESTING.md (keep - testing reference)
- MODE3_KAFKA_INGESTION.md (keep - MODE 3 reference)

- [ ] **Step 6: Commit cleanup**

```bash
git add -A
git commit -m "docs: remove outdated MODE2_IMPLEMENTATION_PLAN.md

MODE 2 (Elasticsearch) has been implemented and is production-ready.
Implementation plan is no longer needed - CLAUDE.md and Antora docs
are the authoritative references."
```

---

## Task 4: Remove Unused Code (Already Deleted Files)

**Files:**
- Review: Current git status
- Commit: Staged deletions

**Interfaces:**
- Consumes: Git staged deletions from branch `chore/remove-unused-code`
- Produces: Clean commit with all unused code removed

- [ ] **Step 1: Review staged deletions**

Check current git status:

```bash
git status --short | grep "^D "
```

Expected deleted files:
- JsonNodeScalar.java (unused GraphQL scalar)
- EventRepository.java (old event processor)
- KafkaEventProcessor.java (old event processor)
- TRANSFORM_FIELD_MAPPING_ISSUE.md (resolved issue)
- security-commons/* (entire module - unused)
- persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/schema/* (unused schema classes)
- persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/proto/* (unused proto classes)
- persistence-commons/persistence-commons-api/src/main/java/org/kie/kogito/persistence/api/factory/Constants.java (unused)

- [ ] **Step 2: Review staged moves**

Check files that were moved (package reorganization):

```bash
git status --short | grep "^R "
```

Expected: Many files moved from old package structure to new organized structure (e.g., `org.kubesmarts.logic.dataindex.elasticsearch` → `org.kubesmarts.logic.dataindex.storage.elasticsearch`)

- [ ] **Step 3: Verify no broken imports**

Build the project to ensure all imports are valid after package moves:

```bash
mvn clean compile -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Run tests to verify functionality**

Verify that code changes don't break existing functionality:

```bash
# MODE 1 (PostgreSQL) tests
mvn test -Dquarkus.profile=postgresql -pl data-index-integration-tests/data-index-integration-tests-postgresql

# MODE 2 (Elasticsearch) tests
mvn test -Dquarkus.profile=elasticsearch -pl data-index-storage/data-index-storage-elasticsearch
```

Expected: All tests pass

- [ ] **Step 5: Review modified files**

Check that modified files still make sense:

```bash
git diff --cached data-index/data-index-integration-tests/data-index-integration-tests-postgresql/src/test/java/org/kubesmarts/logic/dataindex/graphql/WorkflowInstanceGraphQLApiTest.java
```

Expected: Import updates due to package reorganization

- [ ] **Step 6: Commit unused code removal**

```bash
git commit -m "chore: remove unused code and reorganize package structure

Deleted:
- JsonNodeScalar.java - unused custom GraphQL scalar
- EventRepository.java, KafkaEventProcessor.java - old event processor (removed in Phase 1)
- TRANSFORM_FIELD_MAPPING_ISSUE.md - resolved issue documentation
- security-commons/ - unused security module
- persistence-commons schema/proto/factory classes - unused Kogito legacy

Moved:
- Reorganize packages for better structure
  - elasticsearch storage: org.kubesmarts.logic.dataindex.elasticsearch → storage.elasticsearch
  - postgresql storage: org.kubesmarts.logic.dataindex.storage → storage.jpa
  - common storage: org.kubesmarts.logic.dataindex.api → storage.common.api

No functional changes - all tests pass."
```

---

## Task 5: Update Root README for Consistency

**Files:**
- Modify: `/Users/ricferna/dev/github/kubesmarts/logic-apps/README.md`

**Interfaces:**
- Consumes: Current root README
- Produces: Aligned README with correct project name

- [ ] **Step 1: Read current root README**

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
cat README.md
```

Expected:
```
# KubeSmarts Logic Apps

Data Index and Jobs Service for Serverless Workflow 1.0.0 on OpenShift Serverless Logic.
```

- [ ] **Step 2: Update README with Open Workflow terminology**

Replace with:

```markdown
# KubeSmarts Logic Apps

Data Index and Jobs Service for Open Workflow 1.0.0.

## Modules

- **data-index/** - Query service for workflow execution data (MODE 1, MODE 2, MODE 3)
- **persistence-commons/** - Shared persistence interfaces

## Build

```bash
mvn clean install
```

Requires Java 17 and Maven 3.9.11+.

## Documentation

- [Data Index Documentation](data-index/data-index-docs/) - User-facing documentation
- [CLAUDE.md](CLAUDE.md) - AI assistant guidelines for this codebase

## License

Apache License 2.0
```

- [ ] **Step 3: Write updated README**

```bash
# Content above via Write tool
```

- [ ] **Step 4: Verify**

```bash
cat README.md
```

- [ ] **Step 5: Commit root README update**

```bash
git add README.md
git commit -m "docs: update root README for consistency

- Use Open Workflow terminology
- Add module structure overview
- Point to authoritative documentation
- Remove outdated OpenShift Serverless Logic reference"
```

---

## Task 6: Final Verification and Cleanup

**Files:**
- Review: All changes across all tasks

**Interfaces:**
- Consumes: All previous task commits
- Produces: Clean, verified codebase ready for merge

- [ ] **Step 1: Run full build**

Build entire project:

```bash
cd /Users/ricferna/dev/github/kubesmarts/logic-apps
mvn clean install -DskipTests
```

Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests**

```bash
# Run all tests
mvn test
```

Expected: All tests pass (or same failures as before cleanup - verify no new test failures)

- [ ] **Step 3: Search for remaining issues**

Check for any remaining problematic references:

```bash
# Check for "Serverless Workflow" (should be 0 outside node_modules)
grep -r "Serverless Workflow" --include="*.java" --include="*.md" --include="*.adoc" . 2>/dev/null | grep -v node_modules | wc -l

# Check for "TBD" or "TODO" in documentation (review any findings)
grep -r "TBD\|TODO" --include="*.md" data-index/docs/ data-index/README.md README.md CLAUDE.md 2>/dev/null | grep -v node_modules

# Check for v0.8 references
grep -r "v0\.8" --include="*.md" . 2>/dev/null | grep -v node_modules
```

- [ ] **Step 4: Review commit history**

Check that all commits are clean and well-described:

```bash
git log --oneline origin/main..HEAD
```

Expected: 5-6 commits with clear, descriptive messages

- [ ] **Step 5: Generate cleanup summary**

Create summary of changes:

```bash
# Count files changed
git diff --stat origin/main..HEAD

# List deleted files
git log --diff-filter=D --summary origin/main..HEAD | grep delete

# List renamed files
git log --diff-filter=R --summary origin/main..HEAD | grep rename
```

- [ ] **Step 6: Update CLAUDE.md if needed**

If any cleanup changes affect CLAUDE.md guidance, update:

```bash
# Check if CLAUDE.md needs updates based on cleanup
# Review sections:
# - "What NOT to Do" - add any new anti-patterns discovered
# - "Key Files Reference" - update if file paths changed
# - "Troubleshooting" - add any new issues discovered
```

- [ ] **Step 7: Final commit (if CLAUDE.md updated)**

```bash
git add CLAUDE.md
git commit -m "docs: update CLAUDE.md to reflect cleanup changes"
```

---

## Post-Task Summary

**Completed:**
1. ✅ Renamed "Serverless Workflow" → "Open Workflow" throughout codebase (~26+ occurrences)
2. ✅ Simplified data-index/README.md to point to authoritative documentation
3. ✅ Removed outdated MODE2_IMPLEMENTATION_PLAN.md
4. ✅ Committed removal of unused code and package reorganization
5. ✅ Updated root README for consistency
6. ✅ Verified full build and tests

**Verification:**
- All tests pass
- No "Serverless Workflow" references outside node_modules
- Documentation is consistent and points to authoritative sources
- Package structure is cleaner and better organized

**Next Steps:**
1. Create PR for merge into main
2. Update any external documentation that references the old terminology
3. Consider updating presentation materials in `docs/presentations/` (future task)
