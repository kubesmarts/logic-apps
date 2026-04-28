# Data Index Documentation

**⚠️ This directory contains legacy and technical reference documents.**

## User Documentation

For user-facing documentation, see:

**📚 [Antora Documentation](../data-index-docs/)** (build with `mvn clean package`)

Build and view:

```bash
cd data-index/data-index-docs
mvn clean package
open target/generated-docs/index.html
```

## Contents of This Directory

### Technical Reference

* **jsonnode-scalar-analysis.md** - Technical analysis of JSON field exposure in GraphQL
* **MULTI_TENANT_FLUENTBIT.md** - Advanced multi-tenant FluentBit configuration patterns

### Implementation Plans

* **deployment/MODE2_IMPLEMENTATION_PLAN.md** - Elasticsearch storage backend implementation plan (in progress)

## Migration Status

✅ **Migrated to Antora:**
- Architecture documentation → `data-index-docs/modules/ROOT/pages/architecture/`
- Deployment guides → `data-index-docs/modules/ROOT/pages/deployment/`
- Developer guides → `data-index-docs/modules/ROOT/pages/developers/`
- Operations guides → `data-index-docs/modules/ROOT/pages/operations/`
- API documentation → `data-index-docs/modules/ROOT/pages/api/`

❌ **Removed:**
- All internal development phase references
- Kafka-based architecture documentation (deprecated)
- Duplicate content from old documentation structure

## Contributing

When adding documentation:

1. **User-facing documentation** → Add to `data-index-docs/modules/ROOT/pages/`
2. **Technical decisions** → Add to this directory as `.md` files
3. **Implementation plans** → Add to `deployment/` subdirectory
