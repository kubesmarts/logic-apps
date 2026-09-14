# ADR 0001: Coordination API Architecture

**Status:** Proposed  
**Date:** 2026-09-11  
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

**Three-layer routing strategy:**

**Layer 1: Client Cookie (Primary - Fast Path)**
```
Client → POST /api/v1/instances/instance-123/suspend
         Cookie: route=abc123 (client stored it from execute)

Coordination API:
  Step 1: Validate instance state (data-index)
    query { workflowInstance(id: "instance-123") { status } }
    
    If status IN (COMPLETED, FAULTED, CANCELLED):
      → Return 405 Method Not Allowed:
        {
          "error": "Cannot suspend terminated instance",
          "instance": { "id": "instance-123", "status": "COMPLETED", ... }
        }
    
    If status IN (RUNNING, WAITING, SUSPENDED):
      → Proceed to routing
  
  Step 2: Forward to runtime ingress with client's cookie
    POST http://runtime-ingress/instances/instance-123/suspend
    Cookie: route=abc123
  
  Runtime Ingress:
    → Sees cookie → Routes to Pod-B (sticky session)
  
  Pod-B:
    → Has lease for instance-123 ✓
    → Suspends workflow
    → Returns success

Fast path: Client has cookie (80-90% of requests).
```

**Layer 2: Redis Cookie Jar (Fallback for Stateless Clients)**
```
Client → POST /api/v1/instances/instance-123/suspend
         (No cookie - stateless CLI client)

Coordination API:
  Step 1: Validate (same as above)
  
  Step 2: No cookie from client
    → Query Redis: GET session:instance-123
    → Returns: { "ingressCookie": "route=abc123", ... }
  
  Step 3: Forward to runtime ingress with Redis cookie
    POST http://runtime-ingress/instances/instance-123/suspend
    Cookie: route=abc123 (from Redis)
  
  Runtime Ingress:
    → Routes to Pod-B
  
  Pod-B:
    → Suspends workflow
  
  Coordination API:
    → Send Set-Cookie in response (set cookie for this client)
    → Now this client has cookie for future requests

Fallback for stateless clients (10-20% of requests).
```

**Layer 3: Retry Pattern (Last Resort)**
```
Client → POST /instances/instance-123/suspend
         (No cookie, Redis miss/expired)

Coordination API:
  Step 1: Validate (same as above)
  
  Step 2: No cookies available
  
  Step 3: Retry pattern to ingress
    numReplicas = LogicFlowRuntime.spec.replicas (e.g., 3)
    
    For attempt = 1 to numReplicas:
      POST http://runtime-ingress/instances/instance-123/suspend
      (no cookie - ingress round-robins)
      
      If 200: Success!
        → Extract new cookies from response
        → Update Redis session
        → Send cookies to client
        → Return success
      
      If 404: Wrong pod, try next attempt
      
      If 500/other: Return error (don't retry)
    
    After numReplicas attempts: Return 503 (all attempts failed)

Retry pattern ensures we hit the correct pod eventually.
```

**Critical principle:** **NEVER bypass ingress**. Even in retry pattern, always route through runtime ingress URL to preserve traffic management, TLS, and observability.

### 3. Session Store: Redis/Valkey (Cookie Jar)

**Purpose:** Fallback cookie storage for stateless clients and multi-client scenarios.

**Choice:** Redis (or Red Hat supported Valkey fork)

**Schema (Simplified):**
```
Key: session:{instanceID}
Value: {
  "instanceId": "instance-01HQXYZ",
  "ingressCookie": "route=abc123",  // Only ingress sticky cookie
  "runtimeNamespace": "demo",
  "runtimeName": "hello-runtime"
}
TTL: User-configurable (default: 3600 seconds / 1 hour)
```

**No custom lease cookie:** We only store the ingress sticky session cookie. The lease system is managed by runtime pods; we don't need to expose it in cookies.

**Why Redis:**
- Fast key-value lookups (< 1ms)
- Shared across coordination API pods (horizontal scaling)
- Survives coordination API restarts
- TTL management built-in
- Battle-tested, widely deployed
- Red Hat supports Valkey (Redis fork)

**Deployment:**
- Operator deploys Redis (or users provide external Redis)
- Coordination API configured with Redis connection string
- Users configure TTL based on workflow lifecycle expectations

**Client-managed session lifecycle:**
- Clients store cookies themselves (browsers do this automatically)
- Clients know workflow lifecycle (when instance terminates)
- Clients can discard cookies when workflow completes
- Redis is fallback for stateless clients, not primary storage

**Usage pattern:**
- **Primary:** Client sends cookies (no Redis lookup - fast)
- **Fallback:** Client missing cookies (Redis lookup - acceptable latency for operations)

**Alternatives considered:**
- **Signed cookies (JWT-like):** Stateless, but 4KB cookie size limit and can't invalidate
- **PostgreSQL:** Heavier (5-10ms vs < 1ms), overkill for session data
- **etcd:** Not recommended for application data, K8s cluster load
- **In-memory per-pod:** Doesn't survive restarts, not shared across coordination API pods

**Status:** Accepted - Redis is industry standard for API gateway session storage

### 4. Data-Index Integration

**Primary use: Query operations**

All `GET /instances/*` requests route to data-index GraphQL API:

```graphql
query {
  workflowInstance(id: "instance-123") {
    id
    status
    startedAt
    endedAt
    input
    output
    error
    leaseId      # For observability/debugging
    runtimeName  # For observability
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

-- Update trigger to extract from events
```

**Rationale for storing lease_id/runtime_name:**
- Observability: Query instances by lease or runtime
- Debugging: Understand which pod/runtime processed which instances
- Not used for routing (ingress cookie handles that)

### 5. Runtime Changes Required

**None!** 

Runtime doesn't need changes. Ingress already provides sticky session cookies (`Set-Cookie: route=...`), which is all we need.

**Originally considered (not needed):**
- ❌ Add `X-Flow-Lease-ID` response header - Not needed, ingress cookie sufficient
- ❌ Add custom `Set-Cookie: lease=...` - Not needed, ingress cookie sufficient

**Minimal changes principle:** Leverage existing infrastructure (ingress sticky sessions) rather than building custom mechanisms.

### 6. Operator Responsibilities

**Deployment:**
- Deploy coordination API Deployment (3-5 replicas typical)
- Create Service and Ingress (public entry point)
- Deploy/configure Redis for cookie jar
  - Option A: Operator deploys Redis
  - Option B: User provides external Redis (connection string)

**Runtime discovery:**
- Coordination API watches LogicFlowRuntime and LogicFlowDefinition CRDs
- Operator doesn't need to maintain routing table
- Ingress sticky sessions handle pod-level routing

**Simplified responsibilities:**
- No ConfigMap routing table needed (ingress handles routing)
- No pod annotation watching needed
- Just deploy and configure coordination API + Redis

### 7. Traffic Management Preserved

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

### 8. Horizontal Scaling

**Coordination API pods:**
- Stateless (all state in Redis)
- Share Redis instance (cookie jar)
- Share CRD discovery (K8s API watch)
- Load balanced via corporate LB or K8s Service

**Scaling characteristics:**
- Scale to N pods (typical: 3-5)
- Each pod can handle any request
- No leader election needed
- No pod-to-pod communication
- Redis is only shared dependency

**Performance:**
- Client cookie path: No Redis lookup (fast)
- Redis fallback: < 1ms lookup overhead
- Retry pattern: Only for edge cases (cookie loss)

**Bottleneck:** Redis throughput (but only used for fallback, not primary path)

## Consequences

### Positive

1. **Single API entry point** - Users call one domain for execution, operations, and queries
2. **Cluster-wide discovery** - All runtimes/workflows visible through one API
3. **Correct routing** - Ingress sticky sessions + fallback ensure requests hit correct pod
4. **Minimal runtime changes** - No changes needed, leverage existing ingress cookies
5. **Horizontal scaling** - Stateless coordination API pods
6. **Preserves traffic management** - **Always** routes through ingress (canary, split, TLS intact)
7. **Multi-client support** - Cookie-aware clients fast path, stateless clients fallback
8. **Clean separation** - Queries → data-index, operations → runtime (right tool for job)
9. **Graceful degradation** - Three-layer fallback (client cookie → Redis → retry)
10. **Client controls lifecycle** - Clients manage cookies based on workflow state

### Negative

1. **External dependency** - Requires Redis/Valkey deployment
2. **Multiple routing paths** - Three-layer strategy adds complexity (mitigated by clear layering)
3. **Data-index changes** - Schema must store lease_id and runtime_name (observability)
4. **Network hops** - Client → Coordination API → Ingress → Pod (acceptable overhead)
5. **Retry overhead** - Operations without cookies may require N attempts (N = replicas)
6. **Validation overhead** - Operations query data-index before forwarding (< 10ms)

### Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Redis unavailable** | Fallback to retry pattern (no Redis needed for retry) |
| **Cookie invalidation** | Retry pattern finds correct pod, updates Redis + client |
| **Session bloat** | User-configured TTL, cleanup job, client discards on termination |
| **Operations on terminated instances** | Data-index validation returns 405 + instance data (not 404) |
| **Ingress bypass temptation** | Architectural principle enforced: NEVER bypass ingress |

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

### Alternative 5: Signed Cookies (JWT-like, Stateless)

**Approach:** Store all session data in signed cookie

**Rejected because:**
- 4KB cookie size limit
- Can't invalidate sessions server-side
- Still need fallback for cookieless clients
- Industry uses Redis for API gateway sessions

**Decision:** Redis for cookie jar, client stores cookies when possible

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

**Phase 3: Session Store (Redis Integration)**
- Redis connection and client
- Session CRUD operations (Get, Set, Delete with TTL)
- Cookie extraction from responses
- Cookie passthrough to clients

**Phase 4: Routing Implementation**
- Query routing: Forward GET requests to data-index GraphQL
- Execute routing: Forward POST to runtime ingress, extract cookies
- Operation routing: Three-layer strategy (client cookies → Redis → retry)
- Operation validation: Query data-index before forwarding

**Phase 5: Operator Integration**
- Deploy coordination API Deployment (managed by operator)
- Deploy/configure Redis
- Create Service and Ingress (public entry point)
- E2E tests (execute → query → suspend workflow)

**Phase 6: Production Hardening**
- Metrics: Prometheus (request count, latency, cache hit rate, retry count)
- Distributed tracing: OpenTelemetry (trace request across coordination → ingress → runtime)
- Logging: Structured logs with correlation IDs
- Redis HA: Sentinel or Cluster mode
- Load testing: Verify horizontal scaling

## Open Questions

1. **Redis deployment:** Operator-managed (simple) or external infrastructure (production)?
2. **Session TTL:** User-configurable per LogicFlowRuntime? Global default (1hr)?
3. **Retry count:** Always equal to replicas? Or configurable max retries?
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
