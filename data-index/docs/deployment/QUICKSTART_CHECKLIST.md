# Quick Start Checklist: Deploy Quarkus Flow App with Data Index

Follow these steps to get your Quarkus Flow application sending events to Data Index using Quarkus native tooling.

---

## 🚀 TL;DR for KIND (Local Development)

**After configuration (steps 1-5 below):**

```bash
# Deploy everything in ONE command!
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes

# Quarkus automatically builds, loads to KIND, and deploys.
# No manual 'kind load' or 'kubectl apply' needed!
```

**Full setup below** ⬇️

---

## ✅ Pre-Deployment Checklist

- [ ] Kubernetes cluster is running
- [ ] PostgreSQL is deployed and accessible
- [ ] Data Index service is deployed
- [ ] FluentBit DaemonSet is deployed (collecting from `workflows` namespace)

---

## ✅ Application Configuration (One-Time Setup)

### 1. Add dependencies to `pom.xml`:

```xml
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-kubernetes</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-container-image-jib</artifactId>
</dependency>
```

### 2. Create `application.properties`:

```properties
quarkus.application.name=my-workflow-app
quarkus.flow.structured-logging.enabled=true
quarkus.flow.structured-logging.events=workflow.*
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.flow.structured-logging.include-task-payloads=false
```

### 3a. For KIND (Local Development) - `application-kubernetes.properties`:

```properties
# KIND automatic image loading (no manual 'kind load' needed!)
quarkus.kubernetes.deployment-target=kind
quarkus.kind.cluster-name=data-index-test

# CRITICAL: namespace must be 'workflows' for default FluentBit config
quarkus.kubernetes.namespace=workflows

# Container image
quarkus.container-image.group=local
quarkus.container-image.name=${quarkus.application.name}
quarkus.container-image.tag=dev
quarkus.container-image.build=true

# CRITICAL: Use 'prod' profile at runtime
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod

# Resources (lower for local dev)
quarkus.kubernetes.resources.requests.memory=128Mi
quarkus.kubernetes.resources.limits.memory=256Mi
```

### 3b. For Cloud (GKE/EKS/AKS) - `application-kubernetes.properties`:

```properties
# Cloud deployment
quarkus.kubernetes.deployment-target=kubernetes
quarkus.container-image.registry=gcr.io
quarkus.container-image.group=your-gcp-project
quarkus.container-image.push=true

# CRITICAL: namespace must be 'workflows' for default FluentBit config
quarkus.kubernetes.namespace=workflows

# Container image
quarkus.container-image.name=${quarkus.application.name}
quarkus.container-image.tag=1.0.0
quarkus.container-image.build=true

# CRITICAL: Use 'prod' profile at runtime
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod

# Resources
quarkus.kubernetes.resources.requests.memory=256Mi
quarkus.kubernetes.resources.limits.memory=512Mi
```

### 4. Create `application-prod.properties`:

```properties
quarkus.log.level=INFO
quarkus.http.port=8080
```

### 5. Test locally:

```bash
mvn quarkus:dev
# Trigger workflow, check logs for JSON events like:
# {"eventType":"io.serverlessworkflow.workflow.started.v1",...}
```

---

## ✅ Build and Deploy

### 6a. KIND (Local Development) - One Command! ⚡

```bash
# Build, load to KIND, and deploy - all in one step!
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
```

**Quarkus automatically:**
1. Builds container image with Jib
2. Loads image to KIND cluster (no manual `kind load` needed!)
3. Generates `target/kubernetes/kubernetes.yml`
4. Deploys to cluster (`kubectl apply`)

**Done!** Skip to step 9 for verification.

### 6b. Cloud Deployment - Two Commands

```bash
# 1. Build, push to registry, and deploy
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes

# 2. Verify
kubectl get pods -n workflows
```

---

## ✅ Verification

### 9. Check deployment:

```bash
# Verify pod is running
kubectl get pods -n workflows

# Check QUARKUS_PROFILE is 'prod'
kubectl get pod -n workflows POD_NAME -o yaml | grep QUARKUS_PROFILE

# Check logs for JSON events
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType
```

### 10. Execute workflow:

```bash
kubectl port-forward -n workflows svc/my-workflow-app 8080:8080 &
curl -X POST http://localhost:8080/namespace/workflow-name/start -d '{}'
```

### 11. Wait 10 seconds, then query Data Index:

```bash
curl -s http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status taskExecutions { taskPosition status } } }"}' \
  | jq .
```

### 12. Expected output:

```json
{
  "data": {
    "getWorkflowInstances": [
      {
        "id": "...",
        "name": "your-workflow-name",
        "status": "COMPLETED",
        "taskExecutions": [...]
      }
    ]
  }
}
```

---

## 🔧 Troubleshooting Quick Checks

```bash
# 1. Verify generated manifest has correct namespace
grep "namespace:" target/kubernetes/kubernetes.yml
# Expected: namespace: workflows

# 2. Verify QUARKUS_PROFILE is prod
grep "QUARKUS_PROFILE" target/kubernetes/kubernetes.yml
# Expected: value: prod

# 3. Check app logs for JSON events
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType

# 4. Check FluentBit collected events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "your-workflow-name"

# 5. Check PostgreSQL raw events
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT COUNT(*) FROM workflow_events_raw;"

# 6. Check normalized data
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT id, name, status FROM workflow_instances;"
```

---

## 📚 Common Mistakes

❌ **Building without kubernetes profile** - Use `mvn package -Dquarkus.profile=kubernetes`  
❌ **Wrong namespace in properties** - Must be `workflows` (or update FluentBit config)  
❌ **Missing QUARKUS_PROFILE=prod** - Add to `application-kubernetes.properties`  
❌ **Not loading image to KIND** - Use `kind load docker-image ...`  
❌ **Not waiting for propagation** - Events take 5-10 seconds  

✅ **Correct setup** - Three property files + kubernetes profile + wait 10 seconds

---

## 📖 Using Different Namespace?

If you can't use `workflows` namespace, update FluentBit filter:

```bash
kubectl edit configmap -n logging workflows-fluent-bit-mode1-config
# Change: Path /var/log/containers/*_workflows_*.log
# To:     Path /var/log/containers/*_YOUR_NAMESPACE_*.log

kubectl delete pods -n logging -l app=workflows-fluent-bit-mode1
```

**Reference**: https://github.com/kubesmarts/logic-apps/blob/main/data-index/scripts/fluentbit/mode1-postgresql-polling/fluent-bit.conf

---

## 📋 Property Files Reference

| File | Purpose | Key Settings |
|------|---------|--------------|
| `application.properties` | Common config | `quarkus.flow.structured-logging.*` |
| `application-kubernetes.properties` | Build-time Kubernetes config | `quarkus.kubernetes.namespace=workflows`<br/>`quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod` |
| `application-prod.properties` | Runtime production config | `quarkus.log.level=INFO` |

**Why separate files?**
- Kubernetes profile used at **build time** (generates deployment YAML)
- Prod profile used at **runtime** (application behavior)
- This prevents build settings from affecting runtime behavior

---

For detailed explanation, see [USER_GUIDE_QUARKUS_FLOW_APPS.md](./USER_GUIDE_QUARKUS_FLOW_APPS.md)
