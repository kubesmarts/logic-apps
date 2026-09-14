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

**Note on API Endpoints:** The specific API endpoint paths and HTTP methods shown below are **not final** and will be revisited during implementation. The coordination API will **mimic the endpoints exposed by the Quarkus Flow Runner** (runtime), acting as a transparent proxy/gateway. The critical design element here is the **routing navigation strategy** (how requests are routed to data-index vs runtime, and how sticky sessions work), not the exact URL structure or HTTP verbs.

Coordination API routes requests differently based on operation type:

#### Type 0: CRD Discovery → **Kubernetes API**

**Operations:** `GET /v1/definitions` - List available workflow definitions

```
Client → GET /v1/definitions

Coordination API:
  → Query Kubernetes API for LogicFlowDefinition CRDs
  → Return list of available workflows (namespace, name, version)
  → Example: [
      { "namespace": "demo", "name": "hello-world", "version": "1.0.0" },
      { "namespace": "prod", "name": "order-processing", "version": "2.0.0" }
    ]

No routing complexity, direct K8s API query.
```

**Rationale:** Coordination API watches CRDs for runtime/definition discovery. Exposing this to clients enables workflow catalog browsing.

#### Type 1: Query Operations → **Data-Index**

**Operations:**
- `GET /v1/instances/{id}` - Get single instance (GraphQL facade)
- `GET /v1/instances?filter=...&page=...` - List/filter instances (GraphQL facade, pagination required)
- `GET /v1/status/{instanceId}` - Short view: id, status, error details if failed

```
Example: Get single instance
Client → GET /v1/instances/instance-123

Coordination API:
  → Forward to data-index GraphQL API
  → query { workflowInstance(id: "instance-123") { ... } }
  → Return response to client

Example: Get instance status (short view)
Client → GET /v1/status/instance-123

Coordination API:
  → Forward to data-index GraphQL API
  → query { workflowInstance(id: "instance-123") { id, status, error, workflowApplicationId } }
  → Return: { "id": "instance-123", "status": "FAULTED", "error": {...}, "workflowApplicationId": "flow-pool-member-01" }

Example: List instances with filter
Client → GET /v1/instances?status=RUNNING&page=1&size=20

Coordination API:
  → Forward to data-index GraphQL API with filter/pagination
  → Return paginated results

No routing complexity, no cookies needed.
```

**Rationale:** Data-index aggregates all workflow instance data from events. Querying runtime pods is unnecessary and inefficient.

#### Type 2: Workflow Execution → **Runtime (Round-Robin)**

**Operations:** `POST /v1/{namespace}/{name}/{version}` - Execute workflow

```
Client → POST /v1/demo/hello-world/1.0.0
         Body: { "input": { "name": "Alice" } }

Coordination API:
  1. Discover runtime for workflow (CRD watch)
  2. Forward to runtime ingress (no X-Flow-Route header - instance doesn't exist yet)
  
Runtime Ingress:
  → Round-robin to Pod-B
  → Sets sticky session cookie: Set-Cookie: route=abc123
  
Pod-B (Quarkus Flow):
  → Creates instance-01HQXYZ (random ULID)
  → Acquires lease: flow-pool-member-01
  → Returns: { "id": "instance-01HQXYZ" } + Set-Cookie: route=abc123
  
Coordination API:
  → Extract Set-Cookie from runtime response
  → Convert cookie to header: X-Flow-Route: abc123
  → Return: { "id": "instance-01HQXYZ" }
           X-Flow-Route: abc123

Client responsibility:
  → Store X-Flow-Route per instance: map["instance-01HQXYZ"] = "abc123"
  → Include header on future operations (/suspend, /cancel, /resume)
```

**No session storage:** Coordination API is stateless. Client manages routing hints.

#### Type 3: Instance Operations → **Runtime (Sticky Routing)**

**Operations:**
- `POST /v1/suspend/{instanceId}` - Suspend running instance
- `DELETE /v1/cancel/{instanceId}` - Cancel running instance
- `POST /v1/resume/{instanceId}` - Resume suspended instance

These operations must route to the specific pod holding the instance's lease.

**Two-layer routing strategy:**

**Layer 1: X-Flow-Route Header (Fast Path - Optional)**
```
Client → POST /v1/suspend/instance-123
         X-Flow-Route: abc123 (optional - client stored it from /exec response)

Coordination API:
  Step 1: Check if X-Flow-Route header present
    If header missing:
      → Skip to Layer 2 (retry pattern)
    
    If header present:
      → Proceed to Step 2
  
  Step 2: Forward to runtime ingress, convert header to cookie
    POST http://runtime-ingress/suspend/instance-123
    Cookie: route=abc123 (from X-Flow-Route header)
  
  Runtime Ingress:
    → Sees cookie → Routes to Pod-B (sticky session)
  
  If 200 (Success):
    Pod-B:
      → Has lease for instance-123 ✓
      → Suspends workflow
      → Returns 200 + Set-Cookie: route=abc123
    
    Coordination API → Client:
      → Extract Set-Cookie from runtime response
      → Convert to X-Flow-Route header
      → Return: 200 + X-Flow-Route: abc123
  
  If 404 (Ambiguous - pod died OR instance terminated):
    → Query data-index to disambiguate
    → Go to Validation step (below)

Fast path (80-90% of requests): Client has valid routing hint.
```

**Validation After 404 (Disambiguate Terminated vs Wrong Pod)**
```
After 404 from runtime:
  
  Query data-index:
    query { 
      workflowInstance(id: "instance-123") { 
        id
        status 
      } 
    }
  
  If status IN (COMPLETED, FAULTED, CANCELLED):
    → Instance is terminated (not in runtime memory anymore)
    → Return 405 Method Not Allowed:
      {
        "error": "Cannot suspend terminated instance",
        "instance": { "id": "instance-123", "status": "COMPLETED" }
      }
    → DON'T RETRY (instance is gone from runtime, all retries will 404)
  
  If status IN (RUNNING, WAITING, SUSPENDED, PENDING):
    → Instance is still active (just on wrong pod)
    → Proceed to Layer 2 (retry pattern)
  
  If NOT FOUND in data-index:
    → Instance doesn't exist at all
    → Return 404 Not Found:
      {
        "error": "Workflow instance not found",
        "instance_id": "instance-123"
      }

This validation prevents wasted retries for terminated instances.
```

**Layer 2: Retry Pattern (Fallback for Active Instances)**
```
Triggered when:
  - X-Flow-Route header missing (client never got hint or lost it), OR
  - Layer 1 returns 404 AND validation confirms instance is still active

Coordination API:
  numReplicas = LogicFlowRuntime.spec.replicas (e.g., 3)
  maxRetries = numReplicas * 2 (configurable multiplier, default: 2)
  
  For attempt = 1 to maxRetries:
    POST http://runtime-ingress/suspend/instance-123
    (no cookie - ingress round-robins to different pods)
    
    If 200: Success!
      → Extract Set-Cookie from response
      → Convert to X-Flow-Route header
      → Return: 200 + X-Flow-Route: xyz (new hint)
      → Client stores routing hint for this instance
    
    If 404: Wrong pod, try next attempt
    
    If 500/other: Return error (don't retry server errors)
  
  After maxRetries attempts: Return 503 Service Unavailable
    {
      "error": "Instance not found on any runtime pod after retries",
      "instance_id": "instance-123",
      "attempts": maxRetries,
      "hint": "Instance may have moved or been terminated during retry"
    }

**Note:** This is best-effort routing. Round-robin does not guarantee N requests hit N distinct pods (load balancer may repeat, traffic splitting may add backends, ready pod count may differ from spec.replicas). The retry count is a heuristic to increase success probability, not a correctness guarantee.

Fallback (10-20% of requests): Missing hints or stale hints for active instances trigger retry.
```

**Exception: Workflow execution (new instances)**
```
POST /v1/{namespace}/{name}/{version}

No X-Flow-Route required (instance doesn't exist yet).
Coordination API forwards to runtime (round-robin, no cookie).
Runtime creates instance, returns Set-Cookie.
Coordination API returns X-Flow-Route to client.
Client stores for future operations on this instance ID.
```

**Why retry is necessary:**

Sticky session cookies (OpenShift Routes, nginx ingress) are **pod-specific**, not service-level:
- Cookie `route=abc123` maps to Pod-B (specific pod IP/ID hash)
- If Pod-B dies (crash, eviction, rolling update), cookie becomes stale
- Ingress routes to dead pod → 404
- Retry pattern finds new pod, client gets updated routing hint

**Common scenario - Fast-completing workflows:**
1. Client executes workflow → completes in 100ms → returns X-Flow-Route
2. Client immediately tries to suspend → sends X-Flow-Route header
3. Runtime returns 404 (instance completed and removed from memory)
4. Coordination API queries data-index → finds status=COMPLETED
5. Returns 405 Method Not Allowed (don't retry terminated instances)

**Graceful recovery:** 
- Missing/stale headers → retry finds correct pod
- Terminated instances → 405 response (no wasted retries)
- Client doesn't need to re-execute workflow

**Critical principle:** **NEVER bypass ingress**. Always route through runtime ingress URL to preserve traffic management, TLS, and observability.

### 3. Data-Index Integration

**Primary use: Query operations**

All `GET /instances/*` requests route to data-index GraphQL API:

```
GET /instances/instance-123

Coordination API → Proxies to data-index:
  query { 
    workflowInstance(id: "instance-123") { 
      id
      status
      startedAt
      endedAt
      inputData    # Public field (input is internal JsonNode)
      outputData   # Public field (output is internal JsonNode)
      error
      workflowApplicationId  # NEW: Add to GraphQL schema (observability)
    } 
  }

Returns:
  {
    "id": "instance-123",
    "status": "COMPLETED",
    "startedAt": "2026-09-14T10:00:00Z",
    "endedAt": "2026-09-14T10:00:01Z",
    "outputData": "{\"result\": \"success\"}",
    "workflowApplicationId": "flow-pool-member-01"
  }
```

**Benefit for clients:** Check instance status before attempting operations to avoid 405 errors.

**Secondary use: Operation validation after 404**

After runtime returns 404, query data-index to disambiguate:

```graphql
query {
  workflowInstance(id: "instance-123") {
    id
    status
  }
}
```

- **If terminated** (COMPLETED, FAULTED, CANCELLED): Return `405 Method Not Allowed`, don't retry
- **If active** (RUNNING, WAITING, SUSPENDED, PENDING): Proceed to retry pattern
- **If not found**: Return `404 Not Found`

This prevents wasted retry attempts for instances that are no longer in runtime memory.

**Schema changes needed (data-index):**

```sql
-- MODE 1 & MODE 3 (PostgreSQL)
ALTER TABLE workflow_instances ADD COLUMN workflow_application_id VARCHAR(255);

-- Update trigger to extract from events (MODE 1)
-- Update Kafka mapper to extract from events (MODE 3)
-- Note: Populated from lease ID in events (internal implementation detail)
```

```json
// MODE 2 (Elasticsearch)
// Update index template: workflow-instances-template.json
{
  "mappings": {
    "properties": {
      "workflowApplicationId": { "type": "keyword" }
    }
  }
}

// Update transform: workflow-instances-transform.json
{
  "source": {
    "ctx.workflowApplicationId = ctx._source.workflowApplicationId"
  }
}
```

**GraphQL schema changes needed:**

```java
// WorkflowInstance.java (data-index-model)
public class WorkflowInstance {
    private String workflowApplicationId;  // NEW - identifies which application instance processed this workflow
    // ... existing fields
}
```

**Rationale for storing workflow_application_id:**
- Observability: Query instances by workflow application ID
- Debugging: Understand which application instance processed which workflows
- Not used for routing (ingress cookie handles that)
- Note: Internal implementation uses lease system to populate this field, but "lease" is not exposed to users

### 4. Runtime Changes Required

**None!** 

Runtime doesn't need changes. Ingress provides sticky session cookies (`Set-Cookie: route=...`), which is all we need.

**Prerequisite:** Runtime ingress must have sticky sessions enabled.

Operator must configure LogicFlowRuntime ingress/route with sticky sessions:

```yaml
# OpenShift Route (HAProxy-based, first-class support)
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: hello-runtime
  annotations:
    haproxy.router.openshift.io/cookie_name: "route"
spec:
  to:
    kind: Service
    name: hello-runtime
  port:
    targetPort: 8080
```

```yaml
# Nginx Ingress (vanilla Kubernetes)
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: hello-runtime
  annotations:
    nginx.ingress.kubernetes.io/affinity: "cookie"
    nginx.ingress.kubernetes.io/session-cookie-name: "route"
spec:
  rules:
    - host: hello-runtime.example.com
      http:
        paths:
          - path: /
            backend:
              service:
                name: hello-runtime
                port:
                  number: 8080
```

Without sticky session configuration, the X-Flow-Route header will route to wrong pods and operations will fail (404). Sticky sessions are a prerequisite.

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
- All requests route through ingress URL (header → cookie conversion)
- NEVER direct pod calls
- Client provides X-Flow-Route, coordination API forwards as Cookie to ingress
- Retry pattern recovers from stale hints (pod failures) automatically

**Fault tolerance:** Two-layer routing (fast path with header, retry fallback for pod failures).

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
- Fast path (80-90%): Header-based routing, no external lookups
- Fallback (10-20%): Retry pattern for stale/missing hints
- Client manages routing hints (instanceId → routeHash map)

**Bottleneck:** None - coordination API is stateless request forwarding

**Retry triggers:**
- Pod died (rolling update, crash, eviction) → stale routing hint → 404 → retry
- Client lost hint (restart, cache clear) → missing header → retry
- Retry finds new pod and returns updated routing hint to client

## Consequences

### Positive

1. **Single API entry point** - Users call one domain for execution, operations, and queries
2. **Cluster-wide discovery** - All runtimes/workflows visible through one API
3. **Correct routing** - Ingress sticky sessions + retry pattern ensure requests hit correct pod
4. **Minimal runtime changes** - No changes needed, leverage existing ingress cookies
5. **Fully stateless** - Coordination API has zero external dependencies (no Redis)
6. **Preserves traffic management** - **Always** routes through ingress (canary, split, TLS intact)
7. **Simple client contract** - Store X-Flow-Route per instance ID, include on operations
8. **Clean separation** - Queries → data-index, operations → runtime (right tool for job)
9. **Fault tolerance** - Retry pattern recovers from pod failures automatically
10. **Industry standard** - Header-based routing used by gRPC, Envoy, HAProxy, K8s API

### Negative

1. **Client responsibility** - Clients must store X-Flow-Route per instance (map: instanceId → routeHash)
2. **Retry overhead** - Stale/missing hints trigger retry pattern (10-20% of requests)
3. **Data-index changes** - Schema must store workflow_application_id for observability
4. **Network hops** - Client → Coordination API → Ingress → Pod (acceptable overhead)
5. **Validation overhead** - Operations query data-index after 404 to disambiguate (< 10ms)

### Risks & Mitigations

| Risk | Mitigation |
|------|-----------|
| **Pod dies during operation** | Retry pattern finds new pod, returns updated routing hint to client |
| **Client loses routing hint** | Header optional - retry pattern recovers, returns new hint (graceful, not error) |
| **Operations on terminated instances** | Data-index validation after 404 returns 405 + instance data (prevents wasted retries) |
| **Fast-completing workflows** | Validation disambiguates: 404 from terminated instance vs wrong pod |
| **Ingress bypass temptation** | Architectural principle enforced: NEVER bypass ingress |
| **Header not standard** | Use X-Flow-Route (custom but explicit), document in API contract |
| **Retry doesn't find instance** | Return 503 after maxRetries attempts (instance truly lost or moved) |

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

**Decision:** ALWAYS route through ingress (header → cookie conversion)

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
- Add `workflow_application_id` column to data-index schema (MODE 1, MODE 2, MODE 3)
  - Note: Populated from lease information in events (internal implementation detail)
- Update triggers (MODE 1) and transforms (MODE 2) to extract workflow_application_id from events
- Update Kafka mapper (MODE 3) to extract workflow_application_id from events
- Add GraphQL queries for instance status and validation
- No runtime changes needed ✓

**Phase 2: Coordination API Scaffold (logic-operator)**
- Create coordination-api Go module (separate from operator)
- HTTP server with health/ready endpoints
- CRD discovery: LogicFlowRuntime, LogicFlowDefinition
- Basic routing: forward to runtime ingress or data-index

**Phase 3: Header-Based Routing Implementation**
- Extract X-Flow-Route from request headers (optional - graceful if missing)
- Convert X-Flow-Route to Cookie for ingress forwarding
- Extract Set-Cookie from runtime responses
- Convert Set-Cookie to X-Flow-Route for client responses
- Implement retry pattern: try with header first, fallback to round-robin retries

**Phase 4: Request Type Routing**
- Query routing: Forward GET requests to data-index GraphQL
- Execute routing: Forward POST to runtime ingress, no header required (round-robin)
- Operation routing: Two-layer strategy (header fast path → validation → retry fallback)

**Phase 5: Validation After 404**
- Detect 404 from Layer 1 (ambiguous: wrong pod OR terminated instance)
- Query data-index to check instance status
- If terminated (COMPLETED/FAULTED/CANCELLED): Return 405, don't retry
- If active (RUNNING/WAITING/SUSPENDED/PENDING): Proceed to retry pattern
- If not found: Return 404

**Phase 6: Retry Pattern Implementation**
- Triggered after validation confirms instance is active (OR header missing)
- Retry up to maxRetries (default: replicas * 2) with no cookie (round-robin)
- Return updated X-Flow-Route to client on success
- Return 503 if all retries fail

**Phase 7: Operator Integration**
- Deploy coordination API Deployment (managed by operator)
- Ensure runtime Routes (OpenShift) or Ingresses (K8s) have sticky sessions enabled
- Create Service and Ingress for coordination API (public entry point)
- E2E tests (execute → query → suspend → fast-completing workflow → pod failure recovery)

**Phase 8: Production Hardening**
- Metrics: Prometheus (request count, latency, retry rate, validation overhead, 405 rate)
- Distributed tracing: OpenTelemetry (trace request across coordination → ingress → runtime)
- Logging: Structured logs with correlation IDs
- Load testing: Verify horizontal scaling (fully stateless) and pod failure recovery

## Open Questions

1. **Header name:** Use `X-Flow-Route` or standardize on a different name?
2. **Retry multiplier:** Default `replicas * 2` sufficient, or make it higher/configurable?
3. **Multi-cluster:** Future support for workflows across clusters? (federation)
4. **Authentication:** Coordination API edge auth, runtime auth, or both?
5. **Rate limiting:** At coordination API level or rely on ingress?
6. **Retry delay:** Add delay between retry attempts, or fire immediately?

## References

- Quarkus Flow Lease System: https://docs.quarkiverse.io/quarkus-flow/dev/concepts-durable-workflow-k8s.html
- OpenShift Routes Documentation: https://docs.redhat.com/en/documentation/openshift_container_platform/4.7/html/networking/configuring-routes
- OpenShift Route Sticky Sessions: https://dzone.com/articles/session-stickiness-in-openshift
- Nginx Ingress Sticky Sessions: https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#session-affinity
- logic-operator repository: https://github.com/kubesmarts/logic-operator
- logic-apps repository: https://github.com/kubesmarts/logic-apps

## Notes

This ADR represents the final simplified coordination API design after thorough discussion of routing strategies and alternatives.

**Key design decisions:**
1. **Header-based routing** - X-Flow-Route header (API-to-API pattern, not browser cookies)
2. **Fully stateless** - No Redis, no session store, coordination API is pure request forwarding
3. **Client responsibility** - Client stores routing hints per instance (map: instanceId → routeHash)
4. **Fault tolerance** - Retry pattern recovers from pod failures automatically
5. **Queries route to data-index** - Not runtime (clean separation of concerns)
6. **Always use ingress** - NEVER bypass (traffic management preserved)
7. **Validation before operations** - Check data-index for terminated instances (better UX)

**Why retry pattern is necessary:**

Nginx sticky session cookies are **pod-specific** (route to specific pod IP/ID), not service-level:
- When pod dies (crash, eviction, rolling update), routing hint becomes stale
- Ingress routes to dead pod → 404
- Retry pattern finds new pod and returns updated routing hint to client
- **Graceful recovery:** No 428 errors, no client re-execution, just automatic retry

**Evolution from initial design:**
- Started with cookie-based routing (browser pattern) → rejected (multi-instance conflict)
- Switched to header-based routing + 428 on missing → realized pods die, hints go stale
- Final: Header-based routing + retry pattern (fault tolerance, graceful recovery)

**Next steps:**
1. Create coordination API issues in logic-operator repository
2. Complete data-index schema changes (workflow_application_id column)
3. Implement coordination API with header-based routing + validation + retry
4. E2E testing with real workflows (including fast-completing scenarios)
