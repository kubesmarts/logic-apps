# User Guide: Deploying Quarkus Flow Apps with Data Index

This guide shows how to deploy Quarkus Flow applications to Kubernetes using Quarkus native tooling and configure them to send execution events to Data Index for monitoring and querying.

---

## Prerequisites

Before deploying your Quarkus Flow application, ensure:

1. **Kubernetes cluster** - Any Kubernetes cluster (KIND, minikube, GKE, EKS, AKS, etc.)
2. **kubectl** - Configured to access your cluster
3. **PostgreSQL** - Running and accessible from your cluster
4. **Data Index service** - Deployed and connected to PostgreSQL
5. **FluentBit DaemonSet** - Deployed to collect logs from workflow pods (see [FluentBit Configuration](#fluentbit-configuration))

---

## Part 1: Configure Your Quarkus Flow Application

### Step 1: Add Required Dependencies

Add the Quarkus Kubernetes extension to your `pom.xml`:

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

### Step 2: Configure Application Properties

Create three profile-specific property files:

#### `src/main/resources/application.properties` (Common configuration)

```properties
# Application metadata
quarkus.application.name=my-workflow-app

# Quarkus Flow structured logging (REQUIRED for Data Index integration)
quarkus.flow.structured-logging.enabled=true
quarkus.flow.structured-logging.events=workflow.*
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.flow.structured-logging.include-task-payloads=false
```

**Property Reference:**

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.flow.structured-logging.enabled` | `false` | Enable JSON event logging to stdout |
| `quarkus.flow.structured-logging.events` | - | Event pattern (use `workflow.*` for all events) |
| `quarkus.flow.structured-logging.include-workflow-payloads` | `true` | Include workflow input/output in events |
| `quarkus.flow.structured-logging.include-task-payloads` | `false` | Include task input/output (can be verbose) |

#### `src/main/resources/application-kubernetes.properties` (Kubernetes deployment configuration)

```properties
# Kubernetes deployment settings
quarkus.kubernetes.deployment-target=kubernetes
quarkus.kubernetes.namespace=workflows

# CRITICAL: Deploy to 'workflows' namespace for FluentBit to collect logs
# If you use a different namespace, update FluentBit filter pattern
# See: https://github.com/kubesmarts/logic-apps/blob/main/data-index/scripts/fluentbit/mode1-postgresql-polling/fluent-bit.conf

# Container image configuration
quarkus.container-image.group=your-org
quarkus.container-image.name=${quarkus.application.name}
quarkus.container-image.tag=1.0.0
quarkus.container-image.build=true
quarkus.container-image.push=false

# Kubernetes resource configuration
quarkus.kubernetes.replicas=1
quarkus.kubernetes.resources.requests.memory=256Mi
quarkus.kubernetes.resources.requests.cpu=100m
quarkus.kubernetes.resources.limits.memory=512Mi
quarkus.kubernetes.resources.limits.cpu=500m

# Service configuration
quarkus.kubernetes.service-type=ClusterIP

# Health probes
quarkus.kubernetes.liveness-probe.http-action-path=/q/health/live
quarkus.kubernetes.liveness-probe.initial-delay=30s
quarkus.kubernetes.liveness-probe.period=10s
quarkus.kubernetes.readiness-probe.http-action-path=/q/health/ready
quarkus.kubernetes.readiness-probe.initial-delay=20s
quarkus.kubernetes.readiness-probe.period=5s

# Runtime profile override - CRITICAL
# Use 'prod' profile at runtime, not 'kubernetes' profile
# This prevents kubernetes build-time settings from affecting runtime behavior
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod
```

**Property Reference:**

| Property | Description |
|----------|-------------|
| `quarkus.kubernetes.namespace` | **CRITICAL**: Must be `workflows` for default FluentBit config |
| `quarkus.kubernetes.env.vars.QUARKUS_PROFILE` | **CRITICAL**: Set to `prod` to use production runtime config |
| `quarkus.container-image.group` | Your container registry organization/group |
| `quarkus.container-image.tag` | Image version tag |
| `quarkus.kubernetes.resources.*` | Pod resource requests and limits |

See [Quarkus Kubernetes Extension Guide](https://quarkus.io/guides/deploying-to-kubernetes) for all available properties.

#### `src/main/resources/application-prod.properties` (Production runtime configuration)

```properties
# Production runtime settings
quarkus.log.level=INFO
quarkus.log.category."io.quarkiverse.flow".level=DEBUG

# HTTP configuration
quarkus.http.port=8080

# Add your production-specific settings here
# (database connections, external services, etc.)
```

### Step 3: Verify Event Output Locally

Run your application locally:

```bash
mvn quarkus:dev
```

Trigger a workflow and check the logs for JSON events:

```bash
# You should see lines like this:
{"eventType":"io.serverlessworkflow.workflow.started.v1","instanceId":"...","workflowName":"...","timestamp":...}
{"eventType":"io.serverlessworkflow.task.started.v1","instanceId":"...","taskPosition":"do/0","timestamp":...}
{"eventType":"io.serverlessworkflow.task.completed.v1","instanceId":"...","taskPosition":"do/0","timestamp":...}
{"eventType":"io.serverlessworkflow.workflow.completed.v1","instanceId":"...","timestamp":...}
```

If you see these JSON events, you're ready to deploy!

---

## Part 2: Build and Deploy Using Quarkus Kubernetes Extension

### Step 1: Build and Generate Kubernetes Manifests

```bash
# Build container image and generate Kubernetes YAML
mvn package -Dquarkus.profile=kubernetes

# This generates:
# - Container image: your-org/my-workflow-app:1.0.0
# - target/kubernetes/kubernetes.yml - Generated deployment manifest
```

**What happens:**
1. Quarkus builds the container image using Jib
2. Generates Kubernetes Deployment, Service, and other resources
3. Manifests are in `target/kubernetes/kubernetes.yml`

### Step 2: Review Generated Manifest (Optional)

Check the generated deployment:

```bash
cat target/kubernetes/kubernetes.yml
```

**Verify these critical settings:**
- ✅ `namespace: workflows` (for FluentBit)
- ✅ `QUARKUS_PROFILE: prod` (runtime profile)
- ✅ Resource requests/limits are appropriate
- ✅ Health probes are configured

### Step 3: Load Image to Cluster (KIND/minikube only)

For local clusters, load the image:

```bash
# KIND
kind load docker-image your-org/my-workflow-app:1.0.0 --name your-cluster-name

# Minikube
eval $(minikube docker-env)
mvn package -Dquarkus.profile=kubernetes
```

For cloud clusters (GKE, EKS, AKS), push to registry:

```bash
# Enable push in application-kubernetes.properties
# quarkus.container-image.push=true
# quarkus.container-image.registry=gcr.io  # or docker.io, quay.io, etc.

mvn package -Dquarkus.profile=kubernetes
```

### Step 4: Deploy to Kubernetes

```bash
# Apply the generated manifest
kubectl apply -f target/kubernetes/kubernetes.yml

# Verify pod is running
kubectl get pods -n workflows

# Check logs to verify JSON events
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType
```

---

## Part 2.5: FluentBit Configuration

### Default Configuration

Data Index uses FluentBit to collect workflow event logs. The default configuration:

- **Collects logs from**: Pods in `workflows` namespace
- **Filter pattern**: `/var/log/containers/*_workflows_*.log`
- **Reference**: [FluentBit Configuration](https://github.com/kubesmarts/logic-apps/blob/main/data-index/scripts/fluentbit/mode1-postgresql-polling/fluent-bit.conf)

### Using a Different Namespace

If you deploy to a namespace other than `workflows`, update the FluentBit filter:

**Option 1: Update FluentBit ConfigMap**

Edit the FluentBit configuration:

```bash
kubectl edit configmap -n logging workflows-fluent-bit-mode1-config
```

Change the tail path:

```ini
[INPUT]
    Name              tail
    Path              /var/log/containers/*_YOUR_NAMESPACE_*.log  # Change this
    Parser            cri
    Tag               kube.*
    Refresh_Interval  5
    Mem_Buf_Limit     5MB
    Skip_Long_Lines   On
```

Restart FluentBit pods:

```bash
kubectl delete pods -n logging -l app=workflows-fluent-bit-mode1
```

**Option 2: Deploy Separate FluentBit Instance**

For multi-namespace deployments, deploy a separate FluentBit DaemonSet per namespace. See [FluentBit MODE 1 Documentation](MODE1_HANDOFF.md#fluentbit-configuration).

### FluentBit Configuration Reference

| Setting | Default | Description |
|---------|---------|-------------|
| Path | `/var/log/containers/*_workflows_*.log` | Log file pattern to tail |
| Parser | `cri` | Kubernetes CRI log parser |
| Tag | `kube.*` | Tag for routing |
| Refresh_Interval | `5` seconds | How often to check for new files |

**Full configuration**: https://github.com/kubesmarts/logic-apps/blob/main/data-index/scripts/fluentbit/mode1-postgresql-polling/fluent-bit.conf

---

## Part 3: Verify Data Index Integration

### Step 1: Execute a Workflow

Trigger a workflow execution:

```bash
# Port-forward to your app
kubectl port-forward -n workflows svc/my-workflow-app 8080:8080 &

# Execute a workflow
curl -X POST http://localhost:8080/your-workflow-namespace/your-workflow-name/start \
  -H "Content-Type: application/json" \
  -d '{"input": "data"}'
```

### Step 2: Wait for Event Propagation

Events flow through this pipeline:
```
Workflow Pod → stdout → Kubernetes logs → FluentBit → PostgreSQL → Data Index
```

Wait 5-10 seconds for events to propagate.

### Step 3: Query Data Index GraphQL API

```bash
# Get workflow instances
curl -s http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances { id name status taskExecutions { taskPosition status } } }"}' \
  | jq .
```

**Expected output:**
```json
{
  "data": {
    "getWorkflowInstances": [
      {
        "id": "01KQ7KH8WW0M22WE194CN09S3N",
        "name": "your-workflow-name",
        "status": "COMPLETED",
        "taskExecutions": [
          {
            "taskPosition": "do/0",
            "status": "COMPLETED"
          }
        ]
      }
    ]
  }
}
```

---

## Troubleshooting

### Events Not Appearing in Data Index

**1. Check FluentBit is collecting logs:**

```bash
# Check FluentBit pods are running
kubectl get pods -n logging -l app=workflows-fluent-bit-mode1

# Check FluentBit logs for your workflow events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep "your-workflow-name"
```

**Expected**: You should see JSON events with `_flow_event: true`

**2. Check PostgreSQL raw tables:**

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT COUNT(*) FROM workflow_events_raw;"

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT data->>'workflowName', data->>'eventType' FROM workflow_events_raw LIMIT 5;"
```

**Expected**: Raw events should be in the database

**3. Check normalized tables:**

```bash
kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT id, name, status FROM workflow_instances;"

kubectl exec -n postgresql postgresql-0 -- \
  env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
  -c "SELECT instance_id, task_position, status FROM task_instances;"
```

**Expected**: Triggers should normalize raw events into query tables

**4. Check Data Index service:**

```bash
# Check Data Index pod is running
kubectl get pods -n data-index

# Check Data Index logs
kubectl logs -n data-index -l app=data-index-service
```

### Common Issues

| Problem | Cause | Solution |
|---------|-------|----------|
| No events in FluentBit | Wrong namespace | Deploy to `workflows` namespace or update FluentBit filter (see [FluentBit Configuration](#fluentbit-configuration)) |
| No events in FluentBit | Structured logging disabled | Verify `quarkus.flow.structured-logging.enabled=true` in `application.properties` |
| No events in FluentBit | Wrong Quarkus profile | Check pod env: `kubectl get pod -n workflows POD_NAME -o yaml \| grep QUARKUS_PROFILE` (should be `prod`) |
| Events in raw tables but not normalized | Trigger error | Check PostgreSQL logs for trigger errors |
| GraphQL returns empty | Data Index not connected | Check Data Index database connection settings |
| Image not found | Image not loaded to cluster | For KIND: `kind load docker-image your-org/app:1.0.0` |
| Deployment uses wrong namespace | Missing kubernetes profile | Build with: `mvn package -Dquarkus.profile=kubernetes` |

### Viewing Raw Events

To debug event structure:

```bash
# View workflow pod logs with JSON events
kubectl logs -n workflows -l app=my-workflow-app | grep eventType | jq .

# View FluentBit processed events
kubectl logs -n logging -l app=workflows-fluent-bit-mode1 | grep _flow_event | jq .
```

---

## Configuration Reference

### Application Properties Summary

#### Required Properties (application.properties)

| Property | Value | Description |
|----------|-------|-------------|
| `quarkus.flow.structured-logging.enabled` | `true` | Enable JSON event logging |
| `quarkus.flow.structured-logging.events` | `workflow.*` | Which events to emit |

#### Kubernetes Deployment Properties (application-kubernetes.properties)

| Property | Example Value | Description |
|----------|---------------|-------------|
| `quarkus.kubernetes.namespace` | `workflows` | **CRITICAL**: Target namespace (must match FluentBit filter) |
| `quarkus.kubernetes.env.vars.QUARKUS_PROFILE` | `prod` | **CRITICAL**: Runtime profile (prevents kubernetes profile at runtime) |
| `quarkus.container-image.group` | `your-org` | Container registry organization |
| `quarkus.container-image.tag` | `1.0.0` | Image version |
| `quarkus.kubernetes.resources.requests.memory` | `256Mi` | Memory request |
| `quarkus.kubernetes.resources.limits.memory` | `512Mi` | Memory limit |

#### Optional Properties

| Property | Default | Description |
|----------|---------|-------------|
| `quarkus.flow.structured-logging.include-workflow-payloads` | `true` | Include workflow input/output |
| `quarkus.flow.structured-logging.include-task-payloads` | `false` | Include task input/output |
| `quarkus.kubernetes.service-type` | `ClusterIP` | Service type |
| `quarkus.container-image.push` | `false` | Push image to registry |

**Complete property reference**: 
- [Quarkus Flow Properties](https://docs.quarkiverse.io/quarkus-flow/dev/index.html)
- [Quarkus Kubernetes Extension](https://quarkus.io/guides/deploying-to-kubernetes)
- [Quarkus Container Image](https://quarkus.io/guides/container-image)

---

## Next Steps

1. **Production deployment**: Configure resource limits, replicas, and monitoring
2. **Event retention**: Configure PostgreSQL retention policies for raw events
3. **Monitoring**: Set up alerts on Data Index GraphQL metrics
4. **Security**: Configure authentication for GraphQL API

For advanced configuration, see:
- [Data Index Architecture](../ARCHITECTURE-SUMMARY.md)
- [FluentBit Configuration](MODE1_HANDOFF.md)
- [GraphQL API Reference](../development/GRAPHQL_API.md)
