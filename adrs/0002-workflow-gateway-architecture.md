# ADR 0002: Workflow Gateway Architecture

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
- Scale horizontally (multiple workflow gateway pods)
- Support both cookie-aware (browsers) and stateless clients (CLI tools)
- Single domain/API for entire workflow platform
- Minimal runtime changes (leverage existing infrastructure)

## Decision

We will implement a **Workflow Gateway** as a unified HTTP gateway that routes requests appropriately based on operation type.

### 1. Architecture

**Location:** New Go service in `logic-operator` repository  
**Structure:** Separate Go module at `workflow-gateway/`  
**Deployment:** Managed by logic-operator, deployed as Deployment + Service + Ingress

```
logic-operator/
├── operator/           # Operator (separate go.mod)
├── workflow-gateway/   # Workflow Gateway (separate go.mod)
└── api/               # Shared CRD types (separate go.mod)
```

**Key principle:** Workflow Gateway is a **thin routing layer** that unifies access to:
- Runtime ingresses (for workflow execution and operations)
- Data-index service (for queries)
- CRD discovery (for available workflows/runtimes)

**Two-level routing:**

The gateway performs **two levels of routing** for workflow operations:

1. **Runtime-level routing** (gateway responsibility):
   - **Question:** Which runtime ingress should I call? (hello-runtime vs order-runtime)
   - **How:** Extract workflow ID from URL path → look up in LogicFlowRuntime CRDs
   - **Example:** URL `/v1/demo/hello-world/1.0.0/instances/instance-123/suspend`
     - Extract: demo/hello-world/1.0.0
     - CRD lookup: demo/hello-world/1.0.0 → https://hello-runtime-demo.apps.cluster.example.com

2. **Pod-level routing** (ingress responsibility):
   - **Question:** Which pod should handle this request within hello-runtime? (Pod-A vs Pod-B)
   - **How:** Sticky session cookie (route=abc123) from X-Flow-Route header
   - **Example:** Cookie: route=abc123 → hello-runtime ingress routes to Pod-B

**Gateway is stateless:**
- Workflow ID is in the URL (client knows which workflow they're operating on)
- Runtime mapping is from LogicFlowRuntime CRDs (watched at startup)
- No data-index query needed for runtime lookup
- X-Flow-Route header contains only the sticky cookie value (abc123) for pod-level routing

**Critical:** Runtime mapping resolves to **ingress/Route URLs**, NOT Kubernetes Service DNS names:
- ✅ Correct: `https://hello-runtime-demo.apps.cluster.example.com` (ingress URL with TLS, traffic management)
- ❌ Wrong: `hello-runtime.demo.svc.cluster.local` (Service DNS - bypasses ingress, violates "always preserve ingress" requirement)
- LogicFlowRuntime CRD must contain the ingress URL (operator populates this when creating ingress/Route)

### 2. Request Routing Strategy by Type

**Note on API Endpoints:** The specific API endpoint paths and HTTP methods shown below are **not final** and will be revisited during implementation. The workflow gateway will **mimic the endpoints exposed by the Quarkus Flow Runner** (runtime), acting as a transparent proxy/gateway. The critical design element here is the **routing navigation strategy** (how requests are routed to data-index vs runtime, and how sticky sessions work), not the exact URL structure or HTTP verbs.

**Key design decision:** Operations that call the runner (suspend/cancel/resume) **include the workflow ID in the URL path** (namespace/name/version) so the gateway can determine which runtime to forward to without querying data-index or caching state. Version is optional (runner defaults to latest).

Workflow Gateway routes requests differently based on operation type:

#### Type 0: CRD Discovery → **Kubernetes API**

**Operations:** `GET /v1/definitions` - List available workflow definitions

```
Client → GET /v1/definitions

Workflow Gateway:
  → Query Kubernetes API for LogicFlowDefinition CRDs
  → Return list of available workflows (namespace, name, version)
  → Example: [
      { "namespace": "demo", "name": "hello-world", "version": "1.0.0" },
      { "namespace": "prod", "name": "order-processing", "version": "2.0.0" }
    ]

No routing complexity, direct K8s API query.
```

**Rationale:** Workflow Gateway watches CRDs for runtime/definition discovery. Exposing this to clients enables workflow catalog browsing.

#### Type 1: Query Operations → **Data-Index**

**Operations:**
- `GET /v1/instances/{id}` - Get single instance (GraphQL facade)
- `GET /v1/instances?filter=...&page=...` - List/filter instances (GraphQL facade, pagination required)
- `GET /v1/status/{instanceId}` - Short view: id, status, error details if failed

```
Example: Get single instance
Client → GET /v1/instances/instance-123

Workflow Gateway:
  → Forward to data-index GraphQL API
  → query { getWorkflowInstance(id: "instance-123") { ... } }
  → Return response to client

Example: Get instance status (short view)
Client → GET /v1/status/instance-123

Workflow Gateway:
  → Forward to data-index GraphQL API
  → query { getWorkflowInstance(id: "instance-123") { id, status, error { type title detail status instance }, workflowApplicationId } }
  → Return: { "id": "instance-123", "status": "FAULTED", "error": {...}, "workflowApplicationId": "flow-pool-member-01" }

Example: List instances with filter
Client → GET /v1/instances?status=RUNNING&page=1&size=20

Workflow Gateway:
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

Workflow Gateway:
  1. Look up runtime for workflow definition demo/hello-world/1.0.0
     → Gateway watches LogicFlowDefinition and LogicFlowRuntime CRDs
     → Mapping: demo/hello-world/1.0.0 → http://https://hello-runtime-demo.apps.cluster.example.com
  
  2. Forward to runtime ingress (no X-Flow-Route header - instance doesn't exist yet)
     → POST http://https://hello-runtime-demo.apps.cluster.example.com/v1/demo/hello-world/1.0.0
  
Runtime Ingress (hello-runtime):
  → Round-robin to Pod-B (no cookie, so ingress load balances)
  → Sets sticky session cookie: Set-Cookie: route=abc123
  
Pod-B (Quarkus Flow):
  → Creates instance-01HQXYZ (random ULID)
  → Acquires lease: flow-pool-member-01
  → Returns: { "id": "instance-01HQXYZ" } + Set-Cookie: route=abc123
  
Workflow Gateway:
  → Extract Set-Cookie from runtime response
  → Convert cookie to header: X-Flow-Route: abc123
  → Return: { "id": "instance-01HQXYZ" }
           X-Flow-Route: abc123

Client responsibility:
  → Store workflow ID and X-Flow-Route per instance:
     - Workflow ID: demo/hello-world/1.0.0 (from execution request)
     - Routing hint: X-Flow-Route: abc123 (from response header)
  → Include both in future operations:
     - URL path: /v1/demo/hello-world/1.0.0/instances/instance-01HQXYZ/suspend
     - Header (optional): X-Flow-Route: abc123
```

**No session storage:** Workflow Gateway is stateless. Client manages routing hints (sticky cookie values).

**Important distinction:**
- **Runtime-level routing:** Gateway determines which runtime ingress to call (hello-runtime vs order-runtime)
  - Based on: Workflow ID in URL path (namespace/name/version) → CRD lookup
  - Example: URL contains `/demo/hello-world/1.0.0/` → gateway looks up in LogicFlowRuntime CRDs → hello-runtime
  - **No data-index query needed** - workflow ID is in the request path
- **Pod-level routing:** Ingress determines which pod to route to (Pod-A vs Pod-B within hello-runtime)
  - Based on: Sticky session cookie (route=abc123) from X-Flow-Route header
  - Each runtime ingress has its own sticky cookies (hello-runtime's cookies are independent of order-runtime's cookies)

#### Type 3: Instance Operations → **Runtime (Sticky Routing)**

**Operations:**
- `POST /v1/{namespace}/{name}/{version}/instances/{instanceId}/suspend` - Suspend running instance
- `DELETE /v1/{namespace}/{name}/{version}/instances/{instanceId}/cancel` - Cancel running instance
- `POST /v1/{namespace}/{name}/{version}/instances/{instanceId}/resume` - Resume suspended instance

**Note:** Version can be omitted (e.g., `/v1/{namespace}/{name}/instances/{instanceId}/suspend`) - runner will use latest version.

These operations must route to the specific pod holding the instance's lease.

**Why include workflow path:** Gateway needs namespace/name/version to look up which runtime ingress to call (hello-runtime vs order-runtime) from LogicFlowRuntime CRDs. This keeps the gateway stateless - no need to cache or query data-index for runtime lookup.

**Two-layer routing strategy:**

**Runtime Selection (Built into URL):**

The workflow path in the URL tells the gateway **which runtime ingress** to forward to:

```
POST /v1/demo/hello-world/1.0.0/instances/instance-123/suspend
     └─────┬──────┘ └───┬────┘ └──┬──┘
       namespace      name     version (optional)
```

**Gateway's runtime lookup:**
1. Extract workflow ID from URL: `demo/hello-world/1.0.0`
2. Look up in LogicFlowRuntime CRDs (watched at startup)
   - Mapping: `(namespace, name, version) → runtime ingress URL`
   - Example: `demo/hello-world/1.0.0 → http://https://hello-runtime-demo.apps.cluster.example.com`
3. Forward to that runtime (X-Flow-Route header is only for pod-level routing)

**No data-index query needed** - workflow ID is in the URL, runtime mapping is from CRDs.

**Layer 1: X-Flow-Route Header (Fast Path - Optional)**
```
Client → POST /v1/demo/hello-world/1.0.0/instances/instance-123/suspend
         X-Flow-Route: abc123 (optional - client stored it from /exec response)

Workflow Gateway:
  Step 1: Look up runtime from URL path
    → Extract: demo/hello-world/1.0.0
    → CRD mapping: demo/hello-world/1.0.0 → http://https://hello-runtime-demo.apps.cluster.example.com

  Step 2: Check if X-Flow-Route header present
    If header missing:
      → Validate instance state first (see Validation section below)
      → If terminated: Return 405 Method Not Allowed
      → If active: Proceed to Layer 2 (retry pattern)
    
    If header present:
      → Proceed to Step 3
  
  Step 3: Forward to runtime ingress, convert header to cookie
    POST http://https://hello-runtime-demo.apps.cluster.example.com/demo/hello-world/1.0.0/instances/instance-123/suspend
    Cookie: route=abc123 (from X-Flow-Route header)
    
    Note: The cookie "abc123" is only for pod-level routing within hello-runtime
  
  Runtime Ingress:
    → Sees cookie → Routes to Pod-B (sticky session)
  
  If 200 (Success):
    Pod-B:
      → Has lease for instance-123 ✓
      → Suspends workflow
      → Returns 200 + Set-Cookie: route=abc123
    
    Workflow Gateway → Client:
      → Extract Set-Cookie from runtime response
      → Convert to X-Flow-Route header
      → Return: 200 + X-Flow-Route: abc123
  
  If 404 (Ambiguous - pod died OR instance terminated):
    → Query data-index to disambiguate
    → Go to Validation step (below)

Fast path (80-90% of requests): Client has valid routing hint.
```

**Validation: Check Instance State (Before Retries or After 404)**
```
Triggered when:
  - X-Flow-Route header missing (before Layer 2), OR
  - Runtime returns 404 with routing hint (after Layer 1)

Query data-index:
    query { 
      getWorkflowInstance(id: "instance-123") { 
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
    → DON'T RETRY (instance never existed, all retries will 404)

This validation prevents wasted retries for terminated or non-existent instances.
```

**Layer 2: Retry Pattern (Fallback for Active Instances)**
```
Triggered ONLY when validation confirms instance is active (RUNNING/WAITING/SUSPENDED/PENDING):
  - X-Flow-Route header missing (client never got hint or lost it), OR
  - Layer 1 returns 404 with routing hint

Workflow Gateway:
  1. Determine target runtime from URL path (same as Layer 1 Step 1)
     → Extract: demo/hello-world/1.0.0
     → CRD mapping: demo/hello-world/1.0.0 → http://https://hello-runtime-demo.apps.cluster.example.com
  
  2. Retry pattern within that runtime
     numReplicas = LogicFlowRuntime.spec.replicas (e.g., 3)
     maxRetries = numReplicas * 2 (configurable multiplier, default: 2)
     
     For attempt = 1 to maxRetries:
       POST http://https://hello-runtime-demo.apps.cluster.example.com/demo/hello-world/1.0.0/instances/instance-123/suspend
       (no cookie - ingress round-robins to different pods within hello-runtime)
    
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
Workflow Gateway forwards to runtime (round-robin, no cookie).
Runtime creates instance, returns Set-Cookie.
Workflow Gateway returns X-Flow-Route to client.
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
4. Workflow Gateway queries data-index → finds status=COMPLETED
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

Workflow Gateway → Proxies to data-index:
  query { 
    getWorkflowInstance(id: "instance-123") { 
      id
      status
      startedAt
      endedAt
      inputData    # Public field (input is internal JsonNode)
      outputData   # Public field (output is internal JsonNode)
      error {
        type
        title
        detail
        status
        instance
      }
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
  getWorkflowInstance(id: "instance-123") {
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

**OPTIONAL:** For observability only (routing works without this field - see "Optional Enhancement" section)

**MODE 1 (PostgreSQL + Triggers + JPA):**

1. **Database schema:**
```sql
ALTER TABLE workflow_instances ADD COLUMN workflow_application_id VARCHAR(255);
```

2. **Trigger (normalize_workflow_event function):**
```sql
-- Extract workflowApplicationId from JSONB event data
workflow_application_id = NEW.data->>'workflowApplicationId'
```

3. **JPA Entity (WorkflowInstanceEntity.java):**
```java
@Entity
@Table(name = "workflow_instances")
public class WorkflowInstanceEntity {
    @Column(name = "workflow_application_id")
    private String workflowApplicationId;
    
    // getter/setter
}
```

4. **MapStruct Mapper (WorkflowInstanceEntityMapper.java):**
```java
@Mapper
public interface WorkflowInstanceEntityMapper {
    @Mapping(source = "workflowApplicationId", target = "workflowApplicationId")
    WorkflowInstance toDomain(WorkflowInstanceEntity entity);
    
    @Mapping(source = "workflowApplicationId", target = "workflowApplicationId")
    WorkflowInstanceEntity toEntity(WorkflowInstance domain);
}
```

**MODE 2 (Elasticsearch + Transforms):**

1. **Index template (workflow-instances-template.json):**
```json
{
  "mappings": {
    "properties": {
      "workflowApplicationId": { "type": "keyword" }
    }
  }
}
```

2. **Raw event field (workflow-events index):**
**OPTIONAL:** If Quarkus Flow includes `workflowApplicationId` field in event payload, transform will extract it. Otherwise field remains null (routing still works).

3. **Transform (workflow-instances-transform.json):**
Add new aggregation to `pivot.aggregations` section (immutable field - first non-null value wins):
```json
{
  "pivot": {
    "aggregations": {
      "workflowApplicationId": {
        "scripted_metric": {
          "init_script": "state.value = null; state.ts = 'ZZZZ'",
          "map_script": "if (params._source.workflowApplicationId != null) { String ts = params._source.eventTime != null ? params._source.eventTime : String.valueOf(params._source.timestamp); if (ts != null && ts.compareTo(state.ts) < 0) { state.value = params._source.workflowApplicationId; state.ts = ts } }",
          "combine_script": "return state",
          "reduce_script": "def earliest = ['value': null, 'ts': 'ZZZZ']; for (s in states) { if (s != null && s.get('ts') != null && s.ts.compareTo(earliest.ts) < 0) { earliest = s } } return earliest.value"
        }
      }
    }
  }
}
```

**Field semantics:** First non-null value wins (earliest event timestamp)
- Immutable: Once set by earliest event, does not change even if later events have different values
- Timestamp tracking: Uses `eventTime` or `timestamp` to select earliest event
- Same pattern as `input` field (first wins), unlike `output`/`error` (last wins)
- Handles out-of-order events correctly via timestamp comparison

4. **Mapper (WorkflowInstanceMapper.java):**
```java
@ApplicationScoped
public class WorkflowInstanceMapper {
    public WorkflowInstance fromDocument(Map<String, Object> document) {
        // ...
        instance.setWorkflowApplicationId((String) document.get("workflowApplicationId"));
        return instance;
    }
}
```

**MODE 3 (Kafka + CloudEvents + JPA):**

1. **Database schema:** Same as MODE 1 (shared normalized schema)

2. **CloudEvent mapper (Mapper.java in kafka-service):**
```java
// Extract from CloudEvent data
WorkflowInstanceEvent event = ...;
instance.setWorkflowApplicationId(event.getWorkflowApplicationId());
```

3. **JPA Entity:** Same as MODE 1 (shared entities)

4. **MapStruct Mapper:** Same as MODE 1 (shared mapper)

**Domain Model (shared across all modes):**

```java
// WorkflowInstance.java (data-index-model)
public class WorkflowInstance {
    private String workflowApplicationId;  // NEW - identifies which application instance processed this workflow
    
    public String getWorkflowApplicationId() { return workflowApplicationId; }
    public void setWorkflowApplicationId(String workflowApplicationId) { 
        this.workflowApplicationId = workflowApplicationId; 
    }
    // ... existing fields
}
```

**Rationale for storing workflow_application_id (OPTIONAL - observability only):**
- Query instances by workflow application ID for debugging
- Understand which application instance/pod processed which workflows
- Correlate pod restarts with workflow failures
- **NOT required for routing** - validation only needs status field (COMPLETED/RUNNING/etc)
- **NOT required for workflow gateway** - sticky sessions handle routing
- **Field source:** Quarkus Flow durable-kubernetes lease name (e.g., "flow-pool-member-01")
  - Requires Quarkus Flow to expose via public API contract (see "Future Work" section)
  - "Lease" is Quarkus Flow internal terminology, not exposed to end users

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
- Deploy workflow gateway Deployment (3-5 replicas typical)
- Create Service and Ingress (public entry point)
- Configure sticky sessions on runtime ingresses (prerequisite)

**Runtime discovery:**
- Workflow Gateway watches LogicFlowRuntime and LogicFlowDefinition CRDs
- Operator doesn't need to maintain routing table
- Ingress sticky sessions handle pod-level routing

**Simplified responsibilities:**
- No ConfigMap routing table needed (ingress handles routing)
- No pod annotation watching needed
- No session store deployment needed (workflow gateway is stateless)
- Just deploy workflow gateway and ensure runtime ingresses have sticky sessions

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
- Client provides X-Flow-Route, workflow gateway forwards as Cookie to ingress
- Retry pattern recovers from stale hints (pod failures) automatically

**Fault tolerance:** Two-layer routing (fast path with header, retry fallback for pod failures).

### 7. Security & Authentication

**STATUS:** Decision deferred to implementation phase.

The workflow gateway is the single platform entry point that exposes:
- Workflow execution and state-changing operations (suspend, cancel, resume)
- Data access (query workflow instances)
- Workflow discovery

**Without authentication:**
- ❌ Unauthorized workflow execution and state changes
- ❌ No audit trail
- ❌ No multi-tenancy support

#### Authentication Options (To Be Decided)

**Option A: API Key Authentication**
- Simple shared key validation
- Good for: Internal platforms, trusted clients
- Limitation: No per-user identity

**Option B: OpenID Connect / OAuth2**
- JWT tokens from identity provider (Keycloak, Entra ID, etc.)
- Per-user identity, fine-grained RBAC, corporate SSO integration
- Good for: Multi-tenant environments, enterprise deployments
- Limitation: Requires external identity provider

**Option C: Kubernetes ServiceAccount Tokens**
- Native Kubernetes authentication
- Good for: Kubernetes-native clients
- Limitation: External clients (web UI, CLI) need different mechanism

#### Integration with Quarkus Flow Runner

**Critical consideration:** Quarkus Flow Runner already has its own authorization mechanism and roles defined.

**Analysis required before implementation:**
1. **Inventory Runner's existing authz:** Document current roles, permissions, endpoints protected
2. **Credential propagation:** Should workflow gateway forward credentials to runtime or handle auth independently?
3. **Dual-auth scenarios:** If both coordinate and propagate credentials, how do roles/permissions align?
4. **Role mapping:** How do workflow gateway roles (if any) map to Runner roles?
5. **Backwards compatibility:** Can Runner continue to work with/without workflow gateway?

**Impact scenarios:**

- **Gateway-only auth:** Workflow Gateway authenticates, Runtime trusts workflow gateway
  - Simpler integration (Runtime doesn't validate tokens)
  - Runtime loses visibility into actual user (logs show "workflow-gateway" as caller)
  
- **Credential propagation:** Workflow Gateway validates and forwards credentials to Runtime
  - Runtime validates again (defense in depth)
  - Runtime knows actual user for audit/logging
  - Requires workflow gateway roles to align with Runner roles

**Decision points:**
- Authentication strategy (A/B/C above)
- Authorization model (gateway-only vs credential propagation)
- Role mapping strategy (if propagating credentials)

**To be resolved before Phase 2 implementation.**

---

### 8. Horizontal Scaling

**Workflow Gateway pods:**
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

**Bottleneck:** None - workflow gateway is stateless request forwarding

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
5. **Fully stateless** - Workflow Gateway has zero external dependencies (no Redis)
6. **Preserves traffic management** - **Always** routes through ingress (canary, split, TLS intact)
7. **Simple client contract** - Store X-Flow-Route per instance ID, include on operations
8. **Clean separation** - Queries → data-index, operations → runtime (right tool for job)
9. **Fault tolerance** - Retry pattern recovers from pod failures automatically
10. **Industry standard** - Header-based routing used by gRPC, Envoy, HAProxy, K8s API

### Negative

1. **Client responsibility** - Clients must store X-Flow-Route per instance (map: instanceId → routeHash)
2. **Retry overhead** - Stale/missing hints trigger retry pattern (10-20% of requests)
3. **Data-index changes (optional)** - Schema CAN store workflow_application_id for observability (routing works without it)
4. **Network hops** - Client → Workflow Gateway → Ingress → Pod (acceptable overhead)
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
- Higher overall complexity than workflow gateway

**Decision:** Thin gateway layer, runtimes focus on execution

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

## Optional Enhancement: Workflow Application ID Observability

**NOT A BLOCKER:** The workflow gateway routing works without this field. This is an **optional observability enhancement** for debugging and monitoring.

**Nice-to-have:** Quarkus Flow could expose workflow application ID (lease name) in two places:

### 1. Execution Response (`POST /v1/{namespace}/{name}/{version}`)

**Current behavior:** Runner returns instance ID only
```json
{
  "id": "instance-123"
}
```

**Required:** Include workflow application ID in response
```json
{
  "id": "instance-123",
  "workflowApplicationId": "flow-pool-member-01"
}
```

**Why:** Enables Workflow Gateway to set X-Flow-Route header immediately after execution (optional optimization - retry pattern works without it)

### 2. Lifecycle Events (CloudEvents)

**Current behavior:** Events contain workflow/task data, no runtime/lease information

**Required:** Add `workflowApplicationId` field to all lifecycle event payloads
```json
{
  "specversion": "1.0",
  "type": "io.serverlessworkflow.workflow.started.v1",
  "data": {
    "name": "instance-123",
    "workflowApplicationId": "flow-pool-member-01",  // NEW FIELD
    "definition": { ... },
    "startedAt": "..."
  }
}
```

**Why:** Enables data-index to store workflow_application_id for observability (NOT required for validation - status field is sufficient)

**Field source:** Quarkus Flow durable-kubernetes lease system
- Lease name (e.g., `flow-pool-member-01`) identifies which pod/application instance owns the workflow
- This is internal to Quarkus Flow but must be exposed in the public API contract

**GitHub Issue:** To be created in `quarkiverse/quarkus-flow` repository

---

## Implementation Plan

**Phase 1: Prerequisites (logic-platform)**
- **EXISTING:** GraphQL queries for instance status validation (getWorkflowInstance with status field)
  - Already implemented in WorkflowInstanceGraphQLApi.java
  - Returns status, error fields needed for terminated instance detection
- **OPTIONAL:** Add `workflow_application_id` column to data-index schema (MODE 1, MODE 2, MODE 3) for observability
  - Can be added later when Quarkus Flow exposes the field
  - Routing works without this (uses status field only)

**Phase 2: Workflow Gateway Scaffold (logic-operator)**
- Create workflow-gateway Go module (separate from operator)
- HTTP server with health/ready endpoints
- CRD discovery: LogicFlowRuntime, LogicFlowDefinition
  - Watch CRDs at startup to build mapping: (namespace, name, version) → ingress URL
  - LogicFlowRuntime must contain ingress URL (not Service DNS)
- Basic routing: forward to runtime ingress or data-index
- **Authentication/Authorization:** Decision deferred to implementation phase (see "Security & Authentication" section)
  - Must decide: API Key, OIDC, or Kubernetes ServiceAccount
  - Must analyze: Integration with Quarkus Flow Runner's existing authz
  - Must decide: Gateway-only auth vs credential propagation
  - Implementation includes: Auth middleware, 401/403 responses, integration tests

**Phase 3: Runtime Selection Logic**
- Extract workflow ID from URL path (namespace/name/version)
- Look up runtime ingress from CRD mapping
- Forward requests to correct runtime ingress (not Service DNS)
- Handle version-optional URLs (default to latest)

**Phase 4: Header-Based Routing Implementation**
- Extract X-Flow-Route from request headers (optional - graceful if missing)
- Convert X-Flow-Route to Cookie for ingress forwarding
- Extract Set-Cookie from runtime responses
- Convert Set-Cookie to X-Flow-Route for client responses
- Implement retry pattern: try with header first, fallback to round-robin retries

**Phase 5: Validation After 404**
- Detect 404 from Layer 1 (ambiguous: wrong pod OR terminated instance)
- Query data-index to check instance status (using existing getWorkflowInstance)
- If terminated (COMPLETED/FAULTED/CANCELLED): Return 405, don't retry
- If active (RUNNING/WAITING/SUSPENDED/PENDING): Proceed to retry pattern
- If not found: Return 404

**Phase 6: Retry Pattern Implementation**
- Triggered after validation confirms instance is active (OR header missing)
- Retry up to maxRetries (default: replicas * 2) with no cookie (round-robin)
- Return updated X-Flow-Route to client on success
- Return 503 if all retries fail

**Phase 7: Operator Integration**
- Deploy workflow gateway Deployment (managed by operator)
- Operator populates LogicFlowRuntime CRD with ingress URL (not Service DNS)
- Ensure runtime Routes (OpenShift) or Ingresses (K8s) have sticky sessions enabled
- Create Service and Ingress for workflow gateway (public entry point)
- E2E tests (execute → query → suspend → fast-completing workflow → pod failure recovery)

**Phase 8: Production Hardening**
- Metrics: Prometheus (request count, latency, retry rate, validation overhead, 405 rate)
- Distributed tracing: OpenTelemetry (trace request across workflow gateway → ingress → runtime)
- Logging: Structured logs with correlation IDs
- Load testing: Verify horizontal scaling (fully stateless) and pod failure recovery
- Security hardening: Authentication/authorization testing per implementation decisions

---

## Future Work (Optional Enhancements)

### Workflow Application ID Observability

**Goal:** Enable querying and filtering workflow instances by which runtime pod processed them.

**Requires:** Quarkus Flow changes (see "Optional Enhancement" section above)
- Add `workflowApplicationId` to execution response
- Add `workflowApplicationId` to lifecycle events

**Benefits:**
- Query all instances processed by a specific pod: `getWorkflowInstances(workflowApplicationId: "flow-pool-member-01")`
- Debugging: Correlate pod restarts with workflow failures
- Capacity planning: Analyze workflow distribution across pods

**Implementation (after Quarkus Flow changes):**

**Step 1: Quarkus Flow Changes (upstream)**
1. Open issue in quarkiverse/quarkus-flow repository
2. Add `workflowApplicationId` to execution response JSON
3. Add `workflowApplicationId` to all lifecycle event CloudEvent data payloads
4. Release new Quarkus Flow version

**Step 2: Data-Index Changes (logic-platform)**

**MODE 1 (PostgreSQL):**
1. Database migration: `ALTER TABLE workflow_instances ADD COLUMN workflow_application_id VARCHAR(255);`
2. Trigger update: Extract `workflowApplicationId` from JSONB event data
3. JPA entity: Add `workflowApplicationId` field to `WorkflowInstanceEntity`
4. Mapper: Add mapping in `WorkflowInstanceEntityMapper`
5. Domain model: Add field to `WorkflowInstance` (shared)
6. GraphQL: Add `workflowApplicationId: String` to GraphQL schema
7. GraphQL filter: Add `workflowApplicationId: StringFilter` for queries

**MODE 2 (Elasticsearch):**
1. Index template: Add `workflowApplicationId` field (keyword type) to `workflow-instances-template.json`
2. Transform: Add `workflowApplicationId` scripted_metric aggregation to `workflow-instances-transform.json` pivot.aggregations
   - Field semantics: Immutable (first non-null value wins, same as name/version/namespace)
   - Map script: `if (params._source.workflowApplicationId != null) { state.value = params._source.workflowApplicationId }`
   - Reduce script: Return first non-null value from states
3. Raw events: Ensure `workflowApplicationId` field exists in workflow-events documents (from Quarkus Flow)
4. Mapper: Update `WorkflowInstanceMapper.fromDocument()` to extract field from Map
5. Domain model: Add field to `WorkflowInstance` (shared)
6. GraphQL: Add `workflowApplicationId: String` to GraphQL schema
7. GraphQL filter: Add `workflowApplicationId: StringFilter` for queries

**MODE 3 (Kafka):**
1. Database schema: Same as MODE 1 (shared normalized schema)
2. CloudEvent mapper: Extract `workflowApplicationId` from CloudEvent data in `Mapper.java`
3. JPA entity: Same as MODE 1 (shared entities)
4. Mapper: Same as MODE 1 (shared mapper)
5. Domain model: Add field to `WorkflowInstance` (shared)
6. GraphQL: Add `workflowApplicationId: String` to GraphQL schema
7. GraphQL filter: Add `workflowApplicationId: StringFilter` for queries

**Step 3: Workflow Gateway Enhancement (logic-operator)**
1. Parse `workflowApplicationId` from execution response
2. Set `X-Flow-Route` header immediately (optimization - skip first retry)
3. Update documentation with new header behavior

**Timeline:** Post-MVP, depends on Quarkus Flow release cycle

---

## Open Questions

1. **Header name:** Use `X-Flow-Route` or standardize on a different name?
2. **Retry multiplier:** Default `replicas * 2` sufficient, or make it higher/configurable?
3. **Multi-cluster:** Future support for workflows across clusters? (federation)
4. **Rate limiting:** At workflow gateway level or rely on ingress?
5. **Retry delay:** Add delay between retry attempts, or fire immediately?

## References

- Quarkus Flow Lease System: https://docs.quarkiverse.io/quarkus-flow/dev/concepts-durable-workflow-k8s.html
- OpenShift Routes Documentation: https://docs.redhat.com/en/documentation/openshift_container_platform/4.7/html/networking/configuring-routes
- OpenShift Route Sticky Sessions: https://dzone.com/articles/session-stickiness-in-openshift
- Nginx Ingress Sticky Sessions: https://kubernetes.github.io/ingress-nginx/user-guide/nginx-configuration/annotations/#session-affinity
- logic-operator repository: https://github.com/kubesmarts/logic-operator
- logic-apps repository: https://github.com/kubesmarts/logic-apps

## Notes

This ADR represents the final simplified workflow gateway design after thorough discussion of routing strategies and alternatives.

**Key design decisions:**
1. **Header-based routing** - X-Flow-Route header (API-to-API pattern, not browser cookies)
2. **Fully stateless** - No Redis, no session store, workflow gateway is pure request forwarding
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
- Considered Redis session store → rejected (adds stateful dependency, complexity, single point of failure)
- Switched to header-based routing + 428 on missing → realized pods die, hints go stale
- Final: Header-based routing + retry pattern + client-managed hints (fault tolerance, fully stateless)

**Next steps:**
1. Create workflow gateway issues in logic-operator repository
2. Complete data-index schema changes (workflow_application_id column)
3. Implement workflow gateway with header-based routing + validation + retry
4. E2E testing with real workflows (including fast-completing scenarios)
