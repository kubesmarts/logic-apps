# ADR-0001: Migrate from FluentBit to Vector for OpenShift Alignment

**Status:** Accepted  
**Date:** 2026-09-02  
**Deciders:** KubeSmarts Engineering Team  
**Issue:** [#57](https://github.com/kubesmarts/logic-apps/issues/57)

## Context

The Data Index service (v1.0.0) currently uses FluentBit as the log collection agent for MODE 1 (PostgreSQL) and MODE 2 (Elasticsearch) deployments. FluentBit captures structured logging events from Quarkus Flow applications running in Kubernetes and forwards them to storage backends.

### Current Architecture

```
Quarkus Flow → stdout (structured JSON logs)
              ↓
         FluentBit DaemonSet (tail /var/log/containers/)
              ↓
         PostgreSQL (MODE 1) or Elasticsearch (MODE 2)
              ↓
         Normalization (triggers or transforms)
              ↓
         GraphQL API
```

### Problem Statement

Red Hat OpenShift has deprecated FluentD and is replacing it with **Vector** as the standard log collector:

1. **OpenShift 4.10+**: Vector is the default collector in OpenShift Logging Operator
2. **OpenShift 5.x**: Vector will be the only supported collector
3. **FluentBit**: Not officially supported by Red Hat OpenShift

For customers deploying Data Index on OpenShift:
- FluentBit creates a second logging stack (OpenShift uses Vector)
- Harder to troubleshoot (unfamiliar to OpenShift operators)
- Complicates OpenShift Operator certification
- Misaligned with Red Hat's strategic direction

### Technology Overview

**Vector:**
- Developed by Datadog, now open-source
- Written in Rust (vs FluentBit in C)
- Native Kubernetes integration
- VRL (Vector Remap Language) for transformations
- Part of OpenShift Logging Operator
- Active development and Red Hat support

**FluentBit:**
- CNCF project, written in C
- Lua scripting for transformations
- Widely used but not OpenShift standard
- Not Red Hat supported for OpenShift logging

### Alternatives Considered

1. **Stay with FluentBit**
   - ❌ Not aligned with OpenShift ecosystem
   - ❌ Harder operator certification
   - ❌ No Red Hat support for OpenShift deployments
   - ✅ Already implemented and working
   - ✅ Team familiarity

2. **Support both FluentBit and Vector**
   - ❌ Maintenance burden (2x configurations)
   - ❌ Documentation complexity
   - ❌ Testing overhead
   - ✅ Customer choice
   - ✅ Gradual migration path

3. **Migrate to Vector** (SELECTED)
   - ✅ OpenShift alignment
   - ✅ Better performance (30-50% better throughput)
   - ✅ Lower resource usage (20-30% less memory)
   - ✅ More powerful transformation language (VRL > Lua)
   - ✅ Native CloudEvents support
   - ✅ Red Hat support for OpenShift
   - ❌ Migration effort (~4 weeks)
   - ❌ Team learning curve

## Decision

**We will migrate from FluentBit to Vector for MODE 1 and MODE 2 deployments.**

### Migration Approach

**Phased migration:**
1. **Phase 1 (Week 1-2):** MODE 2 (Elasticsearch) migration
   - Simpler configuration (no Lua scripts)
   - Validates Vector approach
   - Lower customer impact
2. **Phase 2 (Week 3-4):** MODE 1 (PostgreSQL) migration
   - More complex (Lua → VRL conversion)
   - Learn from Phase 1 experience
3. **Phase 3 (Ongoing):** Documentation and rollout
   - Update all documentation
   - Customer migration guide
   - Deprecate FluentBit configs (keep for 1 release)

**MODE 3 (Kafka) is NOT affected** - uses Kafka ingestion directly, no log collection agent.

### Rationale

**Strategic alignment:**
- Aligns with Red Hat OpenShift standard logging infrastructure
- Easier OpenShift Operator certification process
- Better positioning for OpenShift customers
- Future-proof technology choice

**Technical benefits:**
- **Performance:** 30-50% better throughput, 20-30% lower memory usage
- **Transformations:** VRL is more powerful and concise than Lua
- **Kubernetes:** Native K8s API integration, automatic metadata enrichment
- **Reliability:** Rust memory safety, fewer crashes
- **Observability:** Better metrics, native OpenTelemetry support
- **CloudEvents:** Native CloudEvents parsing (aligns with Open Workflow 1.0.0)

**Operational benefits:**
- Single logging stack on OpenShift clusters
- Familiar to OpenShift operators
- Red Hat support available
- Better integration with OpenShift monitoring

**Cost justification:**
- One-time migration: ~$15,500 (150 hours development)
- Annual savings: ~$10,600 (resource optimization + operational efficiency)
- ROI: 1.5 years payback period
- Plus strategic benefits (certification, customer satisfaction)

### Scope

**What changes:**
- Log collection agent: FluentBit → Vector
- Configuration format: FluentBit INI → Vector YAML
- Transformation language: Lua → VRL (MODE 1 only)
- DaemonSet manifests: FluentBit → Vector
- Deployment scripts: Update for Vector
- Documentation: ~274 references to update

**What stays the same:**
- Storage backends: PostgreSQL (MODE 1), Elasticsearch (MODE 2)
- Normalization: PostgreSQL triggers (MODE 1), ES Transforms (MODE 2)
- GraphQL API: No changes
- Domain model: No changes
- Integration tests: May need updates for Vector testing

### Risk Assessment

**Overall risk:** MEDIUM (manageable with proper testing)

**Risks and mitigations:**

1. **Event loss during migration**
   - **Risk:** LOW
   - **Mitigation:** Database idempotency (triggers/transforms handle duplicates), phased rollout, monitoring

2. **VRL conversion errors** (Lua → VRL for MODE 1)
   - **Risk:** MEDIUM
   - **Mitigation:** `vector validate` command, local testing, output comparison with FluentBit

3. **PostgreSQL/Elasticsearch sink compatibility**
   - **Risk:** MEDIUM
   - **Mitigation:** Schema validation, trigger/transform testing, data integrity checks

4. **Performance regression**
   - **Risk:** LOW
   - **Mitigation:** Benchmarking, monitoring, Vector typically performs better

5. **Customer disruption**
   - **Risk:** LOW
   - **Mitigation:** Phased approach, migration guide, rollback plan, tech preview period

## Consequences

### Positive

**Technical:**
- ✅ Better performance and lower resource usage
- ✅ More powerful transformation capabilities (VRL)
- ✅ Better Kubernetes integration (native metadata enrichment)
- ✅ Native CloudEvents support (future-proof)
- ✅ Improved reliability (Rust memory safety)
- ✅ Better observability and metrics

**Strategic:**
- ✅ Aligned with Red Hat OpenShift ecosystem
- ✅ Easier OpenShift Operator certification
- ✅ Better positioning for OpenShift customers
- ✅ Red Hat support available
- ✅ Future-proof technology choice (OpenShift 5.x ready)

**Operational:**
- ✅ Single logging stack on OpenShift
- ✅ Familiar to OpenShift operators
- ✅ Reduced troubleshooting complexity
- ✅ Better integration with OpenShift monitoring

**Customer:**
- ✅ OpenShift customers get familiar tooling
- ✅ Better performance on OpenShift clusters
- ✅ Consistent with their existing logging infrastructure

### Negative

**Short-term:**
- ⚠️ Migration effort: ~4 weeks development time
- ⚠️ Team learning curve for Vector and VRL
- ⚠️ Documentation updates required (extensive)
- ⚠️ Potential for migration issues during rollout

**Ongoing:**
- ⚠️ Support two configurations temporarily (FluentBit deprecated but kept for 1 release)
- ⚠️ Customer migration burden (must update deployments)

### Neutral

- ℹ️ Configuration format changes (INI → YAML) - different but not worse
- ℹ️ Transformation language changes (Lua → VRL) - learning curve but more powerful
- ℹ️ Non-OpenShift customers must learn new tooling (but Vector has excellent docs)

## Implementation Plan

### Phase 1: MODE 2 (Elasticsearch) - Weeks 1-2

**Week 1:**
1. Create Vector configuration for Elasticsearch mode
2. Develop Vector DaemonSet manifest
3. Update deployment scripts
4. Local KIND testing

**Week 2:**
5. Integration testing
6. Performance benchmarking vs FluentBit
7. Document MODE 2 migration
8. Release as "Tech Preview"

### Phase 2: MODE 1 (PostgreSQL) - Weeks 3-4

**Week 3:**
1. Create Vector configuration for PostgreSQL mode
2. Convert flatten-event.lua → VRL transforms
3. Develop Vector DaemonSet manifest
4. Local KIND testing
5. Integration testing (extensive - Lua conversion is tricky)

**Week 4:**
6. Performance benchmarking vs FluentBit
7. Document MODE 1 migration
8. Create customer migration guide
9. Release as "Tech Preview"

### Phase 3: Documentation & Rollout - Week 5+

1. Update all FluentBit references in documentation
2. Create vector-config.adoc (replace fluentbit-config.adoc)
3. Update CLAUDE.md architecture sections
4. Customer communication (release notes)
5. Mark as "Production Ready"
6. Deprecate FluentBit configs (keep for 1 release cycle)

### Success Criteria

**Technical:**
- [ ] Vector DaemonSets deploy successfully on KIND and real clusters
- [ ] Event throughput matches or exceeds FluentBit
- [ ] PostgreSQL triggers work identically with Vector-sourced events
- [ ] Elasticsearch transforms work identically with Vector-sourced events
- [ ] Resource usage ≤ FluentBit (target: 20-30% lower)
- [ ] All integration tests pass with Vector
- [ ] Zero event loss during migration

**Documentation:**
- [ ] Complete vector-config.adoc (equivalent to fluentbit-config.adoc)
- [ ] Customer migration guide published
- [ ] All FluentBit references updated (274 in AsciiDoc)
- [ ] Troubleshooting guide updated for Vector
- [ ] CLAUDE.md architecture diagrams updated

**Operational:**
- [ ] Monitoring/alerting updated for Vector metrics
- [ ] Rollback plan tested and documented
- [ ] Customer communication materials ready
- [ ] Tech Preview → Production Ready transition plan

## Related Decisions

- **Future:** ADR for MODE 3 (Kafka) ingestion architecture (already implemented, no FluentBit)
- **Future:** ADR for OpenShift Operator certification strategy

## References

- [GitHub Issue #57](https://github.com/kubesmarts/logic-apps/issues/57) - MODE 2 migration tracking
- [Vector Documentation](https://vector.dev)
- [OpenShift Logging Operator](https://docs.openshift.com/container-platform/latest/logging/cluster-logging.html)
- [Migration Assessment Report](../docs/VECTOR_MIGRATION_ASSESSMENT.md) (internal)
- [Vector vs FluentBit Architecture](https://vector.dev/docs/about/under-the-hood/architecture/)
- [Red Hat OpenShift Logging Strategy](https://www.redhat.com/en/blog/logging-openshift-observability)

## Notes

- MODE 3 (Kafka ingestion) does NOT use FluentBit and is NOT affected by this decision
- FluentBit configurations will be maintained for 1 release cycle to allow customer migration
- Vector is compatible with all current storage backends (PostgreSQL, Elasticsearch)
- No changes required to GraphQL API, domain model, or database schemas
- Migration is purely at the ingestion layer (log collection)

## Approval

**Approved by:** KubeSmarts Engineering Team  
**Approval Date:** 2026-09-02  
**Effective Date:** 2026-09-02 (Phase 1 start)  
**Review Date:** 2026-10-01 (post-migration retrospective)
