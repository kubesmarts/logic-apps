# ADR 0002: Coordination API Architecture

**Status:** Proposed  
**Date:** 2026-09-14  
**Authors:** Ricardo Zanini  
**Deciders:** KubeSmarts Team

## Context

Users deploying workflow runtimes with logic-operator need a unified API to:
1. Execute workflows across multiple runtime instances in the cluster
2. Query workflow execution status and results from data-index
3. Perform workflow operations (suspend, cancel, resume) on running instances
4. Discover available workflows and runtimes
5. Access the entire platform through a single domain/API

Currently, users must:
- Call individual runtime ingresses directly for execution and operations
- Call data-index separately for queries
- Know which runtime hosts which workflow definition
- Track which pod holds the lease for each workflow instance

This creates operational complexity and prevents a cohesive platform experience where users can interact with workflows through one unified API.

## Problem Statement

Workflow instances in Quarkus Flow have **stateful ownership** via a lease system:
- Each runtime pod acquires a lease (e.g., `flow-pool-member-01`)
- Workflow instances are bound to the pod holding their lease
- **Instance operations** (suspend, cancel, resume) must route to the lease-holding pod
- Instance IDs are randomly generated (ULID) at creation time
- Pods restart and re-acquire leases, maintaining continuity

**Routing challenges:**
1. **Initial execution:** No instance ID exists yet - which pod creates the instance?
2. **Subsequent operations:** Must route to the specific pod holding the instance's lease
3. **Pod restarts:** Lease transfers to new pod, routing must adapt
4. **Multi-client access:** Different clients performing operations on the same instance
5. **Terminated instances:** How to handle operations on completed/cancelled workflows?

**Additional requirements:**
- **Always preserve runtime ingress** - Never bypass ingress (traffic management, TLS, observability)
- Scale horizontally (multiple coordination API pods)
- Support both cookie-aware (browsers) and stateless clients (CLI tools)
- Single domain/API for entire workflow platform
- Minimal runtime changes (leverage existing infrastructure)

## Decision

We will implement a **Coordination API** as a unified HTTP gateway that routes requests appropriately based on operation type.

### 1. Architecture

**Location:** New Go service in `logic-operator` repository  
**Structure:** Separate Go module at `coordination-api/`  
**Deployment:** Managed by logic-operator, deployed as Deployment + Service + Ingress

```
logic-operator/
├── operator/           # Operator (separate go.mod)
├── coordination-api/   # Coordination API (separate go.mod)
└── api/               # Shared CRD types (separate go.mod)
```

**Key principle:** Coordination API is a **thin routing layer** that unifies access to:
- Runtime ingresses (for workflow execution and operations)
- Data-index service (for queries)
- CRD discovery (for available workflows/runtimes)

### 2. Request Routing Strategy by Type

Coordination API routes requests differently based on operation type:

#### Type 1: Query Operations → **Data-Index**

**Operations:** `GET /instances/{id}`, `GET /instances` (list/filter)

```
Client → GET /api/v1/instances/instance-123

Coordination API:
  → Forward to data-index GraphQL API
  → Data-index has all instance data (status, history, output)
  → Return response to client

No routing complexity, no cookies needed.
```

**Rationale:** Data-index aggregates all workflow instance data from events. Querying runtime pods is unnecessary and inefficient.

#### Type 2: Workflow Execution → **Runtime (Round-Robin)**

**Operations:** `POST /workflows/{ns}/{name}/{version}/execute`

```
Client → POST /api/v1/workflows/demo/hello/1.0/execute

Coordination API:
  1. Discover runtime for workflow (CRD watch)
  2. Forward to runtime ingress (no cookie)
  
Runtime Ingress:
  → Round-robin to Pod-B
  → Sets sticky session cookie: Set-Cookie: route=abc123
  
Pod-B (Quarkus Flow):
  → Creates instance-01HQXYZ (random ULID)
  → Acquires lease: flow-pool-member-01
  → Returns: { "id": "instance-01HQXYZ" }
  
Coordination API:
  → Extract ingress cookie from response
  → Store in Redis cookie jar:
     session:instance-01HQXYZ → {
       "ingressCookie": "route=abc123",
       "runtimeNamespace": "demo",
       "runtimeName": "hello-runtime"
     }
     TTL: user-configurable (default 1 hour)
  
  → Pass Set-Cookie to client:
     Set-Cookie: route=abc123
  
  → Return: { "id": "instance-01HQXYZ" }

Client stores cookie (automatic for browsers, manual for CLI).
```

**Ingress cookie only:** No custom lease cookie needed. Runtime doesn't need changes.

#### Type 3: Instance Operations → **Runtime (Sticky Routing)**

**Operations:** `POST /instances/{id}/suspend`, `POST /instances/{id}/cancel`, `POST /instances/{id}/resume`

These operations must route to the specific pod holding the instance's lease.

**Two-layer routing strategy:**

**Layer 1: X-Flow-Route Header (Required)**
```
Client → POST /api/v1/instances/instance-123/suspend
         X-Flow-Route: abc123 (client stored it from execute response)

Coordination API:
  Step 1: Validate instance state (data-index)
    query { workflowInstance(id: "instance-123") { status } }
    
    If status IN (COMPLETED, FAULTED, CANCELLED):
      → Return 405 Method Not Allowed:
        {
          "error": "Cannot suspend terminated instance",
          "instance": { "id": "instance-123", "status": "COMPLETED", ... }
        }
    
    If status IN (PENDING, RUNNING, WAITING, SUSPENDED):
      → Proceed to routing
  
  Step 2: Extract X-Flow-Route header
    If header missing:
      → Return 428 Precondition Required:
        {
          "error": "X-Flow-Route header required for instance operations",
          "instance_id": "instance-123"
        }
  
  Step 3: Forward to runtime ingress, convert header to cookie
    POST http://runtime-ingress/instances/instance-123/suspend
    Cookie: route=abc123 (from X-Flow-Route header)
  
  Runtime Ingress:
    → Sees cookie → Routes to Pod-B (sticky session)
  
  Pod-B:
    → Has lease for instance-123 ✓
    → Suspends workflow
    → Returns 200 + Set-Cookie: route=abc123
  
  Coordination API → Client:
    → Extract Set-Cookie from runtime response
    → Convert to X-Flow-Route header
    → Return: 200 + X-Flow-Route: abc123

Client responsibility: Store X-Flow-Route per instance ID (map: instanceId → routeHash)
```

**Layer 2: Coordination API Cache (Future - Phase 2+)**
```
If X-Flow-Route header missing:
  → Check internal cache: cache.get(instanceId)
  → If found: Use cached route (forward as Cookie)
  → If miss: Return 428 Precondition Required

Cache populated from runtime responses:
  - Runtime returns Set-Cookie: route=xyz
  - Coordination API extracts and caches: cache.set(instanceId, "xyz")
  - TTL: Configurable (default: 1 hour)

Benefit: Graceful degradation for clients that lose routing hints
Status: Not implemented in Phase 1 (return 428 if header missing)
```

**Exception: Workflow execution (new instances)**
```
POST /definitions/{namespace}/{name}/{version}/execute

No X-Flow-Route required (instance doesn't exist yet).
Coordination API forwards to runtime (round-robin, no cookie).
Runtime creates instance, returns Set-Cookie.
Coordination API returns X-Flow-Route to client.
Client stores for future operations on this instance ID.
```

**Critical principle:** **NEVER bypass ingress**. Always route through runtime ingress URL to preserve traffic management, TLS, and observability.

### 3. Data-Index Integration

**Primary use: Query operations**

All `GET /instances/*` requests route to data-index GraphQL API:

```graphql
query {
  workflowInstance(id: "instance-123") {
    id
    status
    startedAt
    endedAt
    inputData    # Public field (input is internal JsonNode)
    outputData   # Public field (output is internal JsonNode)
    error
    leaseId      # NEW: Add to GraphQL schema
    runtimeName  # NEW: Add to GraphQL schema
  }
}
```

**Secondary use: Operation validation**

Before forwarding operations (suspend/cancel/resume), query data-index to validate instance state:

```graphql
query {
  workflowInstance(id: "instance-123") {
    id
    status
  }
}
```

If status is terminal (COMPLETED, FAULTED, CANCELLED), return `405 Method Not Allowed` with instance data instead of routing to runtime.

**Schema changes needed (data-index):**

```sql
-- MODE 1 & MODE 3 (PostgreSQL)
ALTER TABLE workflow_instances ADD COLUMN lease_id VARCHAR(255);
ALTER TABLE workflow_instances ADD COLUMN runtime_name VARCHAR(255);

-- Update trigger to extract from events (MODE 1)
-- Update Kafka mapper to extract from events (MODE 3)
```

```json
// MODE 2 (Elasticsearch)
// Update index template: workflow-instances-template.json
{
  "mappings": {
    "properties": {
      "leaseId": { "type": "keyword" },
      "runtimeName": { "type": "keyword" }
    }
  }
}

// Update transform: workflow-instances-transform.json
{
  "source": {
    "ctx.leaseId = ctx._source.leaseId",
    "ctx.runtimeName = ctx._source.runtimeName"
  }
}
```

**GraphQL schema changes needed:**

```java
// WorkflowInstance.java (data-index-model)
public class WorkflowInstance {
    private String leaseId;      // NEW
    private String runtimeName;  // NEW
    // ... existing fields
}
```

**Rationale for storing lease_id/runtime_name:**
- Observability: Query instances by lease or runtime
- Debugging: Understand which pod/runtime processed which instances
- Not used for routing (ingress cookie handles that)

### 4. Runtime Changes Required

**None!** 

Runtime doesn't need changes. Ingress provides sticky session cookies (`Set-Cookie: route=...`), which is all we need.

**Prerequisite:** Runtime ingress must have sticky sessions enabled.

Operator must configure LogicFlowRuntime ingress with:

```yaml
# Nginx Ingress example
metadata:
  annotations:
    nginx.ingress.kubernetes.io/affinity: "cookie"
    nginx.ingress.kubernetes.io/session-cookie-name: "route"
```

Without sticky session configuration, Layers 1 and 2 lose their routing guarantee and fallback to Layer 3 (retry pattern).

**Originally considered (not needed):**
- ❌ Add `X-Flow-Lease-ID` response header - Not needed, ingress cookie sufficient
- ❌ Add custom `Set-Cookie: lease=...` - Not needed, ingress cookie sufficient

**Minimal changes principle:** Leverage existing infrastructure (ingress sticky sessions) rather than building custom mechanisms.

### 5. Operator Responsibilities

**Deployment:**
- Deploy coordination API Deployment (3-5 replicas typical)
- Create Service and Ingress (public entry point)
- Configure sticky sessions on runtime ingresses (prerequisite)

**Runtime discovery:**
- Coordination API watches LogicFlowRuntime and LogicFlowDefinition CRDs
- Operator doesn't need to maintain routing table
- Ingress sticky sessions handle pod-level routing

**Simplified responsibilities:**
- No ConfigMap routing table needed (ingress handles routing)
- No pod annotation watching needed
- No session store deployment needed (coordination API is stateless)
- Just deploy coordination API and ensure runtime ingresses have sticky sessions

### 6. Traffic Management Preserved

**Absolute requirement:** **ALWAYS** route through runtime ingress.

**Why this matters:**
- Traffic splitting (90% v1.0, 10% v2.0 canary)
- A/B testing
- TLS termination
- Rate limiting
- Observability (ingress metrics, tracing)
- Authorization (ingress-level auth)

**Implementation:**
- All three routing layers (client cookies, Redis cookies, retry pattern) route to ingress URL
- NEVER direct pod calls (even in fallback)
- Retry pattern hits ingress multiple times, letting ingress round-robin

**Trade-off:** Retry pattern costs N requests (N = num replicas), but preserves all ingress features. This is acceptable because:
- Operations (suspend/cancel/resume) are low throughput
- Query operations route to data-index (no retry needed)
- Execution operations only happen once per instance (cookie set for future)

### 7. Horizontal Scaling

**Coordination API pods:**
- **Fully stateless** (no session store required)
- Share CRD discovery (K8s API watch)
- Load balanced via corporate LB or K8s Service

**Scaling characteristics:**
- Scale to N pods (typical: 3-5)
- Each pod can handle any request
- No leader election needed
- No pod-to-pod communication
- No shared data store dependency

**Performance:**
- Header-based routing: No external lookups (fast)
- Client manages routing hints (instanceId → routeHash map)
- 428 response if header missing (fail fast, no retries)

**Bottleneck:** None - coordination API is pure request forwarding

**Future optimization (Phase 2+):**
- Optional internal cache (instanceId → routeHash)
- Fallback if client loses routing hint
- Still stateless (cache miss = 428, not data loss)

## Consequences

### Positive

1. **Single API entry point** - Users call one domain for execution, operations, and queries
2. **Cluster-wide discovery** - All runtimes/workflows visible through one API
3. **Correct routing** - Ingress sticky sessions ensure requests hit correct pod
4. **Minimal runtime changes** - No changes needed, leverage existing ingress cookies
5. **Fully stateless** - Coordination API has zero external dependencies (no Redis)
6. **Preserves traffic management** - **Always** routes through ingress (canary, split, TLS intact)
7. **Simple client contract** - Store X-Flow-Route per instance ID, include on operations
8. **Clean separation** - Queries → data-index, operations → runtime (right tool for job)
9. **Fail fast** - 428 if header missing (no retry overhead, client knows what's wrong)
10. **Industry standard** - Header-based routing used by gRPC, Envoy, HAProxy, K8s API

### Negative

1. **Client responsibility** - Clients must store X-Flow-Route per instance (map: instanceId → routeHash)
2. **No graceful degradation** - Missing header = 428 (Phase 1), future cache adds fallback (Phase 2+)
3. **Data-index changes** - Schema must store lease_id and runtime_name (observability)
4. **Network hops** - Client → Coordination API → Ingress → Pod (acceptable overhead)
5. **Validation overhead** - Operations query data-index before forwarding (< 10ms)

### Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Client loses routing hint** | Return 428, client re-executes (future: optional cache as fallback) |
| **Operations on terminated instances** | Data-index validation returns 405 + instance data (not 404) |
| **Ingress bypass temptation** | Architectural principle enforced: NEVER bypass ingress |
| **Header not standard** | Use X-Flow-Route (custom but explicit), document in API contract |

## Alternatives Considered

### Alternative 1: Query Runtime for Status (Not Data-Index)

**Approach:** `GET /instances/{id}` routes to runtime pod

**Rejected because:**
- Data-index already aggregates all instance data
- Unnecessary routing complexity
- Runtime may not have historical data (only active instances)
- Data-index is designed for queries (indexes, filters, aggregations)

**Decision:** Queries → data-index, operations → runtime (clean separation)

### Alternative 2: Direct Pod Routing (Bypass Ingress)

**Approach:** Use routing table to call pods directly

**Rejected because:**
- Loses traffic management (canary, A/B testing, traffic split)
- Bypasses TLS termination
- Loses ingress observability (metrics, tracing)
- Violates core requirement: preserve ingress

**Decision:** ALWAYS route through ingress, even in retry pattern

### Alternative 3: Peer-to-Peer Runtime Routing

**Approach:** Runtimes forward requests to each other

**Rejected because:**
- Adds dual responsibility to runtime (execute + route)
- Still needs lookup mechanism (DB or cache)
- Harder to debug (trace across pods)
- Higher overall complexity than coordination API

**Decision:** Thin coordination layer, runtimes focus on execution

### Alternative 4: Replicate Nginx Cookie Algorithm

**Approach:** Generate ingress sticky cookie ourselves (MD5 of pod IP)

**Rejected because:**
- Fragile (tied to nginx implementation details)
- Breaks if nginx changes hash function
- Different ingress controllers use different algorithms
- Not portable (Traefik, HAProxy, Istio all different)

**Decision:** Use ingress cookies as-is, don't replicate

### Alternative 5: Cookie-Based Routing (Browser Pattern)

**Approach:** Use Set-Cookie + Cookie for routing (like browser sessions)

**Rejected because:**
- Multiple instances overwrite single cookie (last runtime wins)
- Client working with instance A and B ends up with B's cookie only
- Request for A includes B's cookie → routes to wrong pod → 404
- Browsers have one cookie per domain, not per resource

**Decision:** Header-based routing (X-Flow-Route) allows per-instance hints

### Alternative 6: Hash-Based Routing

**Approach:** Ingress hashes instance ID to route to same pod

**Rejected because:**
- Instance ID created AFTER pod selection (chicken-egg problem)
- Can't hash what doesn't exist yet
- Would require client-provided instance IDs (API breaking change)
- Quarkus Flow generates random ULIDs

**Decision:** Sticky sessions (set cookie on first request)

## Implementation Plan

**Phase 1: Foundation (logic-platform - GitHub issues #67-#72)**
- Add `lease_id` and `runtime_name` columns to data-index schema (MODE 1, MODE 2, MODE 3)
- Update triggers (MODE 1) and transforms (MODE 2) to extract from events
- Add GraphQL queries for instance status and validation
- No runtime changes needed ✓

**Phase 2: Coordination API Scaffold (logic-operator)**
- Create coordination-api Go module (separate from operator)
- HTTP server with health/ready endpoints
- CRD discovery: LogicFlowRuntime, LogicFlowDefinition
- Basic routing: forward to runtime ingress or data-index

**Phase 3: Header-Based Routing Implementation**
- Extract X-Flow-Route from request headers
- Convert X-Flow-Route to Cookie for ingress forwarding
- Extract Set-Cookie from runtime responses
- Convert Set-Cookie to X-Flow-Route for client responses
- Return 428 Precondition Required if header missing (operations only)

**Phase 4: Request Type Routing**
- Query routing: Forward GET requests to data-index GraphQL
- Execute routing: Forward POST to runtime ingress, no header required (round-robin)
- Operation routing: Require X-Flow-Route header, validate with data-index first
- Operation validation: Query data-index before forwarding, return 405 if terminated

**Phase 5: Operator Integration**
- Deploy coordination API Deployment (managed by operator)
- Ensure runtime ingresses have sticky sessions enabled (prerequisite)
- Create Service and Ingress for coordination API (public entry point)
- E2E tests (execute → query → suspend workflow)

**Phase 6: Production Hardening**
- Metrics: Prometheus (request count, latency, 428 rate, validation overhead)
- Distributed tracing: OpenTelemetry (trace request across coordination → ingress → runtime)
- Logging: Structured logs with correlation IDs
- Load testing: Verify horizontal scaling (fully stateless)

**Phase 7 (Future): Optional Cache Fallback**
- In-memory cache (instanceId → routeHash)
- Fallback if X-Flow-Route header missing
- TTL-based expiration (configurable, default 1hr)
- Graceful degradation (cache miss = 428, not error)

## Open Questions

1. **Header name:** Use `X-Flow-Route` or standardize on a different name?
2. **Cache implementation (Phase 7):** In-memory per-pod or shared (Redis/Valkey)?
3. **Cache TTL (Phase 7):** User-configurable per LogicFlowRuntime? Global default (1hr)?
4. **Multi-cluster:** Future support for workflows across clusters? (federation)
5. **Authentication:** Coordination API edge auth, runtime auth, or both?
6. **Rate limiting:** At coordination API level or rely on ingress?

## References

- Quarkus Flow Lease System: https://docs.quarkiverse.io/quarkus-flow/dev/concepts-durable-workflow-k8s.html
- Nginx Ingress Sticky Sessions: https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#session-affinity
- logic-operator repository: https://github.com/kubesmarts/logic-operator
- logic-apps repository: https://github.com/kubesmarts/logic-apps

## Notes

This ADR represents the simplified coordination API design after thorough discussion of routing strategies and alternatives.

**Key simplifications from initial design:**
1. **No custom lease cookie** - Only ingress sticky session cookie (runtime unchanged)
2. **Queries route to data-index** - Not runtime (clean separation of concerns)
3. **Always use ingress** - NEVER bypass, even in retry pattern (traffic management preserved)
4. **Client manages cookies** - Primary storage, Redis is fallback (reduced Redis load)
5. **Validation before operations** - Check data-index for terminated instances (better UX)

**Redis choice rationale:**
- Industry standard for API gateway session storage (Kong, Tyk, AWS, Azure all use Redis/similar)
- Fast fallback for stateless clients (< 1ms lookup)
- Client cookies handle 80-90% of traffic (Redis only for fallback)
- Alternatives evaluated: signed cookies (size limits), PostgreSQL (slower), etcd (not for app data)

**Next steps:**
1. Create coordination API issues in logic-operator repository
2. Complete data-index schema changes (lease_id, runtime_name columns)
3. Implement coordination API with three-layer routing strategy
4. E2E testing with real workflows
