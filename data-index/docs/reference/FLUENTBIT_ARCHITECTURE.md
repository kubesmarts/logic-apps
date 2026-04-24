# FluentBit Architecture for MODE 1 - Scaling Patterns

## Current Implementation: hostPath Pattern

### Architecture

```
Node's /tmp directory (shared via hostPath)
     ↑               ↑
     │ write         │ read
     │               │
Workflow Pod    FluentBit Pod (DaemonSet)
```

### Limitations
- ❌ Only works when pods are on the same node
- ❌ Not suitable for multi-node clusters with many workflow pods
- ❌ /tmp is global - potential name collisions

### When to Use
✅ Single-node development/testing
✅ Simple proof-of-concept
✅ Dedicated workflow nodes with node affinity

---

## Production Pattern 1: Kubernetes Container Logs (Recommended)

### Architecture

```
Workflow Pods (anywhere in cluster)
     ↓ write to stdout/stderr
Kubernetes Log Driver
     ↓ writes to
/var/log/pods/namespace_podname_uid/container/N.log
     ↓ symlinked from
/var/log/containers/podname_namespace_container-id.log
     ↓ tailed by
FluentBit DaemonSet (1 pod per node)
     ↓ filters by labels/annotations
PostgreSQL / Elasticsearch / etc
```

### FluentBit Configuration

```conf
[INPUT]
    Name              tail
    Path              /var/log/containers/*workflow*.log
    Parser            docker
    Tag               kube.*
    Refresh_Interval  5
    Mem_Buf_Limit     5MB
    Skip_Long_Lines   On
    DB                /var/log/flb_kube.db

[FILTER]
    Name                kubernetes
    Match               kube.*
    Kube_URL            https://kubernetes.default.svc:443
    Kube_CA_File        /var/run/secrets/kubernetes.io/serviceaccount/ca.crt
    Kube_Token_File     /var/run/secrets/kubernetes.io/serviceaccount/token
    Kube_Tag_Prefix     kube.var.log.containers.
    Merge_Log           On
    Keep_Log            Off
    K8S-Logging.Parser  On
    K8S-Logging.Exclude On

# Filter by pod labels
[FILTER]
    Name    grep
    Match   kube.*
    Regex   $kubernetes['labels']['app'] ^workflow-test-app$

# Or filter by namespace
[FILTER]
    Name    grep
    Match   kube.*
    Regex   $kubernetes['namespace_name'] ^workflows$
```

### Required Volume Mounts

```yaml
volumeMounts:
  - name: varlog
    mountPath: /var/log
    readOnly: true
  - name: varlibdockercontainers
    mountPath: /var/lib/docker/containers
    readOnly: true

volumes:
  - name: varlog
    hostPath:
      path: /var/log
  - name: varlibdockercontainers
    hostPath:
      path: /var/lib/docker/containers
```

### Scaling Behavior
- **1 node → 1 FluentBit pod** (automatically)
- **10 nodes → 10 FluentBit pods** (DaemonSet handles this)
- **100 workflow pods across 10 nodes** → Each FluentBit processes ~10 pods worth of logs

### Filtering Strategies

#### By Pod Label
```yaml
# Workflow pod deployment
metadata:
  labels:
    app: workflow-app
    flow.quarkiverse.io/structured-logging: "enabled"
```

```conf
# FluentBit filter
[FILTER]
    Name    grep
    Match   kube.*
    Regex   $kubernetes['labels']['flow.quarkiverse.io/structured-logging'] ^enabled$
```

#### By Namespace
```conf
[FILTER]
    Name    grep
    Match   kube.*
    Regex   $kubernetes['namespace_name'] ^(workflows|workflow-prod|workflow-staging)$
```

#### By Container Name Pattern
```conf
[INPUT]
    Name    tail
    Path    /var/log/containers/*workflow*_workflows_*.log
```

---

## Production Pattern 2: Sidecar Pattern

### Architecture

```
┌─────────────────────────────────┐
│ Pod: workflow-app-xyz           │
│                                 │
│  ┌───────────────────────────┐  │
│  │ Container: workflow-app   │  │
│  │   writes to /tmp/events   │  │
│  └───────────┬───────────────┘  │
│              │                  │
│              ▼                  │
│  Shared Volume (emptyDir)       │
│              │                  │
│              ▼                  │
│  ┌───────────────────────────┐  │
│  │ Container: fluent-bit     │  │
│  │   reads /tmp/events       │  │
│  │   sends to PostgreSQL     │  │
│  └───────────────────────────┘  │
└─────────────────────────────────┘
```

### Pod Spec Example

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: workflow-app
spec:
  containers:
  - name: workflow-app
    image: kubesmarts/workflow-test-app:999-SNAPSHOT
    volumeMounts:
    - name: event-logs
      mountPath: /tmp
  
  - name: fluent-bit
    image: fluent/fluent-bit:3.0
    volumeMounts:
    - name: event-logs
      mountPath: /tmp
      readOnly: true
    - name: fluent-bit-config
      mountPath: /fluent-bit/etc/
    env:
    - name: POSTGRES_HOST
      value: postgresql.postgresql.svc.cluster.local
    # ... other env vars
  
  volumes:
  - name: event-logs
    emptyDir: {}
  - name: fluent-bit-config
    configMap:
      name: fluent-bit-sidecar-config
```

### Scaling Behavior
- **100 workflow pods → 100 FluentBit sidecars**
- Each FluentBit only processes its own pod's logs
- More resource usage (1 FluentBit per pod)
- Perfect isolation

### When to Use Sidecar
✅ Need guaranteed delivery per pod
✅ Different FluentBit configs per workflow type
✅ High-security environments (no shared volumes)
✅ Already using Istio/service mesh (sidecar pattern familiar)

---

## Production Pattern 3: Hybrid (Node Affinity + hostPath)

### Architecture

```
Dedicated Workflow Nodes (labeled)
     ↓
Workflow Pods (nodeSelector: workflow-nodes)
     ↓ write to /tmp/flow-events (hostPath)
FluentBit DaemonSet (nodeSelector: workflow-nodes)
     ↓ read from /tmp/flow-events
PostgreSQL
```

### Configuration

#### Label Nodes
```bash
kubectl label nodes worker-1 worker-2 worker-3 \
  workload-type=workflow-execution
```

#### Workflow Deployment
```yaml
spec:
  nodeSelector:
    workload-type: workflow-execution
  volumes:
  - name: flow-events
    hostPath:
      path: /tmp/flow-events
      type: DirectoryOrCreate
```

#### FluentBit DaemonSet
```yaml
spec:
  template:
    spec:
      nodeSelector:
        workload-type: workflow-execution
      volumes:
      - name: host-flow-events
        hostPath:
          path: /tmp/flow-events
          type: Directory
```

### Scaling Behavior
- **3 dedicated workflow nodes → 3 FluentBit pods**
- All workflow pods must run on these 3 nodes
- Controlled, predictable scaling

---

## Comparison Matrix

| Pattern | Pods/Node | Isolation | Setup Complexity | Resource Usage | Best For |
|---------|-----------|-----------|------------------|----------------|----------|
| **hostPath /tmp** | N/A | Low | Low | Low | Dev/testing single node |
| **Kubernetes Logs** | 1 FluentBit per node | Medium | Medium | Low | Production multi-pod |
| **Sidecar** | 1 FluentBit per pod | High | High | High | Critical workflows, isolation |
| **Hybrid (Node Affinity)** | 1 FluentBit per workflow node | Medium | Medium | Medium | Dedicated workflow clusters |

---

## Recommended Migration Path

### Phase 1: Current (Working)
- hostPath + /tmp
- Single node cluster
- ✅ Good for development

### Phase 2: Multi-Node Testing
- Switch to Kubernetes container logs pattern
- Use label filtering: `flow.quarkiverse.io/structured-logging: enabled`
- FluentBit DaemonSet auto-scales with nodes

### Phase 3: Production
- Kubernetes logs pattern with namespace filtering
- Node affinity for workflow-heavy nodes (optional)
- Consider sidecar for critical workflows only

---

## Example: 100 Workflow Pods Across 10 Nodes

### Using Kubernetes Logs Pattern

```
Node 1: 10 workflow pods → 1 FluentBit pod → PostgreSQL
Node 2: 10 workflow pods → 1 FluentBit pod → PostgreSQL
Node 3: 10 workflow pods → 1 FluentBit pod → PostgreSQL
...
Node 10: 10 workflow pods → 1 FluentBit pod → PostgreSQL
```

**Total:** 100 workflow pods + 10 FluentBit pods = 110 pods

### Using Sidecar Pattern

```
Pod 1: workflow-app + fluent-bit sidecar → PostgreSQL
Pod 2: workflow-app + fluent-bit sidecar → PostgreSQL
...
Pod 100: workflow-app + fluent-bit sidecar → PostgreSQL
```

**Total:** 200 containers (100 workflow + 100 FluentBit sidecars)

---

## Next Steps

To migrate from hostPath to Kubernetes logs pattern:

1. Update `fluent-bit.conf`:
   - Change INPUT path to `/var/log/containers/*workflow*.log`
   - Add Kubernetes filter
   - Add grep filter for namespace/labels

2. Update DaemonSet volumes:
   - Add `/var/log` mount
   - Add `/var/lib/docker/containers` mount

3. Label workflow pods:
   ```yaml
   labels:
     flow.structured-logging: "enabled"
   ```

4. Test on multi-node cluster

See `mode1-kubernetes-logs/` for complete configuration example.
