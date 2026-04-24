# Multi-Tenant FluentBit: Avoiding Conflicts

## The Problem

**Scenario:** Multiple teams deploying their own FluentBit DaemonSets

```
Team A: Deploys FluentBit for their app logs
Team B: Deploys FluentBit for workflow events (us!)
Team C: Deploys Fluentd for metrics
Platform Team: Runs centralized FluentBit for all logs
```

**What can conflict?**

---

## Conflict Points

### 1. ClusterRole / ClusterRoleBinding (Cluster-Wide)

**Current setup:**
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: fluent-bit  # ❌ CONFLICT: Global name!
```

**Problem:**
- ClusterRole is **cluster-wide** (not namespaced)
- If Team A and Team B both create `ClusterRole/fluent-bit`, the second one **overwrites** the first
- Last one wins, previous one gets deleted

**Solution: Add unique prefix**
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: workflow-events-fluent-bit  # ✅ Unique name
  namespace: workflows  # Note: ClusterRole doesn't use namespace, but document it
```

### 2. DaemonSet Names (Namespace-Scoped)

**Current setup:**
```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: fluent-bit
  namespace: logging  # Each team in different namespace = OK
```

**Problem:**
- If in **same namespace**, names conflict
- Different namespaces = No conflict

**Solution: Use different namespaces**
```
Team A: namespace: team-a-logging
Team B: namespace: workflows-logging  
Platform: namespace: platform-logging
```

### 3. Node Resources (Actual Conflict!)

**The real problem:**

```
Node 1 Resources:
  Total: 4 CPU, 8GB RAM
  
  Platform FluentBit:  100m CPU, 128Mi RAM
  Team A FluentBit:    100m CPU, 128Mi RAM  
  Team B FluentBit:    100m CPU, 128Mi RAM
  ─────────────────────────────────────────
  Total DaemonSets:    300m CPU, 384Mi RAM  ❌
```

**Every node runs ALL DaemonSets!**
- 3 DaemonSets = 3× resource usage per node
- Can cause resource exhaustion

### 4. Port Conflicts

**If multiple FluentBit DaemonSets try to bind the same host ports:**

```yaml
# DaemonSet A
ports:
- name: http
  containerPort: 2020
  hostPort: 2020  # ❌ CONFLICT!

# DaemonSet B  
ports:
- name: metrics
  containerPort: 2020
  hostPort: 2020  # ❌ Can't bind same host port!
```

**Solution: Don't use hostPort, or use different ports**
```yaml
# Workflows FluentBit
ports:
- name: http
  containerPort: 2020
  # No hostPort = OK (use ClusterIP service)
```

### 5. Volume Mount Conflicts

**Multiple DaemonSets reading same files:**

```yaml
# Platform FluentBit
volumeMounts:
- name: varlog
  mountPath: /var/log
  readOnly: true  # ✅ OK - all read-only

# Team B FluentBit
volumeMounts:
- name: varlog
  mountPath: /var/log
  readOnly: true  # ✅ OK - all read-only
```

**This is usually fine IF all are read-only.**

**But if trying to write to the same location:**
```yaml
# DaemonSet A
volumeMounts:
- name: host-tmp
  mountPath: /tmp  # ❌ Both writing to host /tmp

# DaemonSet B
volumeMounts:
- name: host-tmp
  mountPath: /tmp  # ❌ File name collisions possible!
```

---

## Complete Solution: Namespace Isolation

### Architecture

```
┌──────────────────────────────────────────────────────────────┐
│ Kubernetes Cluster                                           │
│                                                              │
│  Namespace: platform-logging                                 │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ DaemonSet: platform-fluent-bit                         │ │
│  │ ClusterRole: platform-fluent-bit                       │ │
│  │ ServiceAccount: platform-fluent-bit                    │ │
│  │                                                        │ │
│  │ Purpose: Collect ALL pod stdout/stderr → Elasticsearch│ │
│  │ NodeSelector: (all nodes)                             │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  Namespace: workflows-logging                                │
│  ┌────────────────────────────────────────────────────────┐ │
│  │ DaemonSet: workflows-fluent-bit                        │ │
│  │ ClusterRole: workflows-fluent-bit                      │ │
│  │ ServiceAccount: workflows-fluent-bit                   │ │
│  │                                                        │ │
│  │ Purpose: Collect workflow events from /tmp → PostgreSQL│ │
│  │ NodeSelector: workload-type=workflow                  │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### Updated DaemonSet

```yaml
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: workflows-fluent-bit  # Prefixed
  namespace: workflows-logging
  labels:
    app: workflows-fluent-bit

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: workflows-fluent-bit  # ✅ Unique cluster-wide name
  labels:
    app: workflows-fluent-bit
rules:
  - apiGroups: [""]
    resources:
      - namespaces
      - pods
      - pods/logs
    verbs: ["get", "list", "watch"]

---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: workflows-fluent-bit  # ✅ Unique cluster-wide name
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: workflows-fluent-bit  # Match above
subjects:
  - kind: ServiceAccount
    name: workflows-fluent-bit  # Match above
    namespace: workflows-logging

---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: workflows-fluent-bit
  namespace: workflows-logging  # ✅ Separate namespace
  labels:
    app: workflows-fluent-bit
spec:
  selector:
    matchLabels:
      app: workflows-fluent-bit
  template:
    metadata:
      labels:
        app: workflows-fluent-bit
    spec:
      serviceAccountName: workflows-fluent-bit
      
      # ✅ Run ONLY on workflow nodes (avoid resource waste)
      nodeSelector:
        workload-type: workflow
      
      containers:
      - name: fluent-bit
        image: fluent/fluent-bit:3.0
        ports:
        - name: http
          containerPort: 2020
          protocol: TCP
          # ✅ NO hostPort (avoid conflicts)
        
        volumeMounts:
        - name: config
          mountPath: /fluent-bit/etc/
        - name: host-tmp
          mountPath: /tmp
          readOnly: true
        - name: tail-db
          mountPath: /tail-db
        
        resources:
          requests:
            cpu: 100m
            memory: 128Mi
          limits:
            cpu: 500m
            memory: 512Mi
      
      volumes:
      - name: config
        configMap:
          name: workflows-fluent-bit-config
      - name: host-tmp
        hostPath:
          path: /tmp  # OK if read-only
          type: Directory
      - name: tail-db
        emptyDir: {}

---
apiVersion: v1
kind: Service
metadata:
  name: workflows-fluent-bit
  namespace: workflows-logging  # ✅ Namespace-scoped, no conflict
  labels:
    app: workflows-fluent-bit
spec:
  type: ClusterIP
  selector:
    app: workflows-fluent-bit
  ports:
  - name: http
    port: 2020
    targetPort: 2020
    protocol: TCP
```

---

## Resource Management Strategy

### Option 1: Node Selectors (Recommended)

Label nodes by workload type:

```bash
# Platform logging runs everywhere
kubectl label nodes node-1 node-2 node-3 node-4 \
  logging-platform=enabled

# Workflow logging only on workflow nodes
kubectl label nodes node-5 node-6 node-7 \
  workload-type=workflow
```

**DaemonSet node selectors:**

```yaml
# Platform FluentBit - runs on all nodes
spec:
  template:
    spec:
      nodeSelector:
        logging-platform: enabled

# Workflows FluentBit - runs ONLY on workflow nodes
spec:
  template:
    spec:
      nodeSelector:
        workload-type: workflow
```

**Result:**
```
Node 1-4: Platform FluentBit only     (100m CPU, 128Mi RAM)
Node 5-7: Platform + Workflows FB    (200m CPU, 256Mi RAM)
```

### Option 2: Tolerations

Allow certain DaemonSets on tainted nodes:

```bash
# Taint workflow nodes
kubectl taint nodes node-5 node-6 node-7 \
  workload=workflow:NoSchedule
```

```yaml
# Platform FluentBit - skips tainted nodes
spec:
  template:
    spec:
      # No tolerations = won't run on tainted nodes

# Workflows FluentBit - tolerates workflow taint
spec:
  template:
    spec:
      tolerations:
      - key: workload
        operator: Equal
        value: workflow
        effect: NoSchedule
      
      nodeSelector:
        workload-type: workflow
```

### Option 3: Sidecar Instead of DaemonSet

**Completely avoid DaemonSet conflicts:**

```yaml
# No DaemonSet at all!
# Each workflow pod gets its own FluentBit sidecar

apiVersion: apps/v1
kind: Deployment
metadata:
  name: workflow-app
spec:
  template:
    spec:
      containers:
      - name: workflow-app
        # ...
      - name: fluent-bit-sidecar
        # ...
```

**Pros:**
- ✅ No cluster-wide resource conflicts
- ✅ No ClusterRole conflicts
- ✅ Independent scaling

**Cons:**
- ❌ More total resources (1 FB per pod vs 1 per node)

---

## Detection: Check for Conflicts

### List All DaemonSets

```bash
kubectl get daemonsets --all-namespaces

# Output:
NAMESPACE           NAME                  DESIRED   CURRENT   READY
kube-system         kube-proxy            3         3         3
platform-logging    fluent-bit            3         3         3
workflows-logging   workflows-fluent-bit  2         2         2
```

### List All ClusterRoles

```bash
kubectl get clusterroles | grep fluent

# Output:
platform-fluent-bit
workflows-fluent-bit
team-a-fluent-bit
```

### Check Node Resource Usage

```bash
kubectl top nodes

# Output:
NAME     CPU    MEMORY
node-1   45%    60%    # Platform FB only
node-2   45%    60%    # Platform FB only
node-5   65%    75%    # Platform + Workflows FB
node-6   65%    75%    # Platform + Workflows FB
```

### Check for Port Conflicts

```bash
# SSH into node
ssh node-5

# Check what's listening on 2020
netstat -tuln | grep 2020

# If multiple processes, you have a conflict
```

---

## Best Practices

### 1. Always Prefix Resource Names

```yaml
# ❌ Generic
name: fluent-bit

# ✅ Specific
name: workflows-fluent-bit
```

### 2. Use Dedicated Namespaces

```yaml
# ✅ Clear ownership
namespace: workflows-logging  # Not just "logging"
```

### 3. Document Resource Claims

```yaml
metadata:
  annotations:
    description: "FluentBit for Quarkus Flow workflow events"
    owner: "workflows-team"
    purpose: "structured-logging-to-postgresql"
```

### 4. Use NodeSelectors to Reduce Overlap

```yaml
nodeSelector:
  workload-type: workflow  # Only run where needed
```

### 5. Set Appropriate Resource Limits

```yaml
resources:
  requests:
    cpu: 100m      # What you need
    memory: 128Mi
  limits:
    cpu: 500m      # Burst capacity
    memory: 512Mi  # Hard limit
```

### 6. For Production: Sidecar Pattern

If your organization has 10+ teams all running their own log collectors:

**Switch to sidecars to avoid:**
- Cluster-wide permission conflicts
- Resource multiplication per node
- Complex node selector management

---

## Migration: DaemonSet → Sidecar

If you discover too many DaemonSets causing resource contention:

```bash
# 1. Deploy workflow app with sidecar
kubectl apply -f workflow-app-sidecar.yaml

# 2. Scale down DaemonSet (but keep for reference)
kubectl scale daemonset workflows-fluent-bit -n workflows-logging --replicas=0

# 3. Verify sidecars working
kubectl get pods -n workflows
# Should see 2/2 containers per pod

# 4. Delete DaemonSet once confident
kubectl delete daemonset workflows-fluent-bit -n workflows-logging
kubectl delete clusterrole workflows-fluent-bit
kubectl delete clusterrolebinding workflows-fluent-bit
```

---

## Summary

**Can multiple DaemonSets coexist?** 
✅ **Yes, with proper naming and namespacing**

**Key Points:**
1. **ClusterRole/ClusterRoleBinding** → Use unique prefixed names
2. **DaemonSet** → Different namespaces = no conflict
3. **Node resources** → Use nodeSelectors to limit where DaemonSets run
4. **Ports** → Don't use hostPort, or use different ports
5. **Volumes** → Read-only mounts = safe to share

**Recommended:**
- **Development:** DaemonSet with node selectors
- **Production (many teams):** Sidecar pattern to avoid conflicts entirely
