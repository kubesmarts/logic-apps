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

You have two options for adding Kubernetes deployment dependencies:

#### Option A: Direct Dependencies (Always Available)

Add to your `pom.xml` `<dependencies>` section:

```xml
<!-- Kubernetes deployment -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-kubernetes</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-container-image-jib</artifactId>
</dependency>

<!-- Health checks (for liveness/readiness probes) -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-health</artifactId>
</dependency>
```

**Use this approach when**: You always want Kubernetes support available.

#### Option B: Maven Profile (Conditional, Recommended)

Keep deployment dependencies separate using a Maven profile:

```xml
<profiles>
  <profile>
    <id>kind</id>
    <activation>
      <property>
        <name>quarkus.profile</name>
        <value>kubernetes</value>
      </property>
    </activation>
    <dependencies>
      <!-- Kubernetes deployment -->
      <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-kubernetes</artifactId>
      </dependency>
      <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-container-image-jib</artifactId>
      </dependency>
      
      <!-- Health checks (for liveness/readiness probes) -->
      <dependency>
        <groupId>io.quarkus</groupId>
        <artifactId>quarkus-smallrye-health</artifactId>
      </dependency>
    </dependencies>
  </profile>
</profiles>
```

**Use this approach when**:
- You want to keep deployment dependencies separate from runtime dependencies
- You build for Kubernetes conditionally (not every build)
- You want to minimize dependencies in local dev mode

**How it works**:
```bash
# Profile automatically activates when you build with kubernetes profile
mvn package -Dquarkus.profile=kubernetes
# The <activation> property matches, so dependencies are included

# In dev mode or regular builds, profile is NOT active
mvn quarkus:dev
# Kubernetes dependencies not loaded - faster startup
```

**Benefits**:
- ✅ **Cleaner dependency management**: Deployment deps separated from runtime deps
- ✅ **Faster local dev**: `quarkus:dev` doesn't load Kubernetes/Jib extensions
- ✅ **Conditional inclusion**: Dependencies only active when deploying
- ✅ **Auto-activation**: No need for `-P kind`, activates automatically
- ✅ **Smaller runtime**: Production runtime doesn't include build tools

**Comparison**:

| Approach | Dev Mode Startup | Kubernetes Deps in Runtime | When to Use |
|----------|------------------|----------------------------|-------------|
| Direct | Slower (loads K8s extensions) | Yes (included in uber-jar) | Always deploying to K8s |
| Profile | **Faster** (no K8s extensions) | **No** (build-time only) | **Recommended** |

### Step 2: Configure Application Properties

Create three profile-specific property files:

#### `src/main/resources/application.properties` (Common configuration)

```properties
#
# Application metadata
#
quarkus.application.name=my-workflow-app

#
# Quarkus Flow - Structured Logging Configuration
#
# REQUIRED for Data Index integration
# Events are written to stdout as raw JSON, captured by Kubernetes logs,
# then collected by FluentBit DaemonSet from /var/log/containers/*.log
#

# Enable structured logging
quarkus.flow.structured-logging.enabled=true

# Event filtering - capture all workflow and task events
quarkus.flow.structured-logging.events=workflow.*

# Payload inclusion
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.flow.structured-logging.include-task-payloads=false

# Timestamp format - use epoch-seconds for PostgreSQL compatibility
# FluentBit pgsql output expects Unix epoch for TIMESTAMP WITH TIME ZONE columns
quarkus.flow.structured-logging.timestamp-format=epoch-seconds

# Structured logging level
quarkus.flow.structured-logging.log-level=INFO

#
# Console handler for structured events (stdout only)
# CRITICAL: Route structured events to separate handler to output raw JSON
#
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled=true
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".format=%s%n

# Route structured logging to console handler ONLY
# CRITICAL: Use specific package 'io.quarkiverse.flow.structuredlogging' (not 'io.quarkiverse.flow')
# Setting DEBUG on whole 'io.quarkiverse.flow' will cause issues
quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS_CONSOLE
quarkus.log.category."io.quarkiverse.flow.structuredlogging".use-parent-handlers=false
quarkus.log.category."io.quarkiverse.flow.structuredlogging".level=INFO

# Health checks (requires quarkus-smallrye-health dependency)
quarkus.smallrye-health.ui.enabled=true
```

**Dependency Requirements:**

These properties require the following dependencies (add via direct dependencies or Maven profile):

```xml
<!-- Required for structured logging -->
<dependency>
  <groupId>io.quarkiverse.flow</groupId>
  <artifactId>quarkus-flow</artifactId>
</dependency>

<!-- Required for health checks (liveness/readiness probes) -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-smallrye-health</artifactId>
</dependency>
```

**Property Reference:**

| Property | Required | Default | Description |
|----------|----------|---------|-------------|
| `quarkus.flow.structured-logging.enabled` | **YES** | `false` | Enable JSON event logging |
| `quarkus.flow.structured-logging.events` | **YES** | - | Event pattern (use `workflow.*` for all events) |
| `quarkus.flow.structured-logging.timestamp-format` | **YES** | - | Use `epoch-seconds` for PostgreSQL compatibility |
| `quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled` | **YES** | - | Create console handler for raw JSON output |
| `quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".format` | **YES** | - | Use `%s%n` for raw output (no formatting) |
| `quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers` | **YES** | - | Route to `FLOW_EVENTS_CONSOLE` only |
| `quarkus.log.category."io.quarkiverse.flow.structuredlogging".use-parent-handlers` | **YES** | `true` | Set to `false` to prevent duplicate output |
| `quarkus.flow.structured-logging.include-workflow-payloads` | No | `true` | Include workflow input/output |
| `quarkus.flow.structured-logging.include-task-payloads` | No | `false` | Include task input/output (verbose) |

**CRITICAL Notes:**
- ⚠️ Use `io.quarkiverse.flow.structuredlogging` (not `io.quarkiverse.flow`) for log category
- ⚠️ Setting DEBUG on `io.quarkiverse.flow` will cause problems - be specific!
- ⚠️ Use `epoch-seconds` timestamp format for PostgreSQL compatibility
- ⚠️ Route to separate console handler to output raw JSON (not formatted logs)

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
#
# Production runtime settings
#
# Add your production-specific settings here:
# - Database connections
# - External service URLs
# - Production-specific timeouts
# - Security settings
# etc.

# NOTE: Do NOT set log level for 'io.quarkiverse.flow' here
# Structured logging configuration is in application.properties
# Setting DEBUG on 'io.quarkiverse.flow' will cause issues
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

### Step 3: Deploy to Kubernetes

#### Option A: Local Development (KIND) - Automatic Image Loading ⚡

**Quarkus can automatically load images to KIND!** No manual `kind load` needed.

Add KIND-specific properties to `application-kubernetes.properties`:

```properties
# KIND automatic image loading
quarkus.kubernetes.deployment-target=kind
quarkus.kind.cluster-name=data-index-test
```

Then deploy in one command:

```bash
# Build, load to KIND, and deploy - all in one step!
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes

# Quarkus automatically:
# 1. Builds container image with Jib
# 2. Loads image to KIND cluster
# 3. Generates kubernetes.yml
# 4. Applies manifest to cluster
```

**That's it!** No manual image loading needed.

Verify deployment:

```bash
# Check pod is running
kubectl get pods -n workflows

# Check logs for JSON events
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType
```

**Complete `application-kubernetes.properties` for KIND:**

```properties
# KIND deployment configuration
quarkus.kubernetes.deployment-target=kind
quarkus.kind.cluster-name=data-index-test
quarkus.kubernetes.namespace=workflows

# Container image
quarkus.container-image.group=local
quarkus.container-image.name=${quarkus.application.name}
quarkus.container-image.tag=dev
quarkus.container-image.build=true

# Runtime profile
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod

# Resources (lower for local dev)
quarkus.kubernetes.resources.requests.memory=128Mi
quarkus.kubernetes.resources.limits.memory=256Mi
```

#### Option B: Minikube - Use Docker Daemon

For minikube, use the cluster's Docker daemon:

```bash
# Point to minikube Docker daemon
eval $(minikube docker-env)

# Build and deploy
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
```

#### Option C: Cloud Clusters (GKE, EKS, AKS) - Push to Registry

For cloud clusters, enable registry push in `application-kubernetes.properties`:

```properties
# Cloud deployment
quarkus.kubernetes.deployment-target=kubernetes
quarkus.container-image.registry=gcr.io
quarkus.container-image.group=your-gcp-project
quarkus.container-image.push=true
```

Then deploy:

```bash
# Build, push to registry, and deploy
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
```

---

## Part 2A: Local Development with KIND (Fastest Path) ⚡

For local development, Quarkus provides the fastest deployment experience with KIND.

### Complete KIND Workflow

**1. Configure `application-kubernetes.properties` for KIND:**

```properties
# KIND-specific settings
quarkus.kubernetes.deployment-target=kind
quarkus.kind.cluster-name=data-index-test

# Standard settings
quarkus.kubernetes.namespace=workflows
quarkus.container-image.group=local
quarkus.container-image.name=${quarkus.application.name}
quarkus.container-image.tag=dev
quarkus.container-image.build=true
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod

# Lower resources for local dev
quarkus.kubernetes.resources.requests.memory=128Mi
quarkus.kubernetes.resources.limits.memory=256Mi
```

**2. Deploy with one command:**

```bash
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
```

**What happens:**
1. ✅ Builds container image (`local/my-workflow-app:dev`)
2. ✅ Automatically loads image to KIND cluster (no manual `kind load`!)
3. ✅ Generates Kubernetes manifests
4. ✅ Deploys to `workflows` namespace

**3. Verify:**

```bash
kubectl get pods -n workflows
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType
```

**4. Make code changes and redeploy:**

```bash
# Just run the same command again!
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
```

Quarkus handles everything - no manual image loading, no separate kubectl commands.

### KIND vs Manual Deployment

| Step | Manual Approach | KIND Approach |
|------|----------------|---------------|
| Build image | `mvn package` | Automatic |
| Load to cluster | `kind load docker-image ...` | **Automatic** ✅ |
| Generate manifest | Automatic | Automatic |
| Deploy | `kubectl apply -f ...` | **Automatic** ✅ |
| **Total commands** | 3 commands | **1 command** ✅ |

### Complete Example: Local Developer Workflow

```bash
# Start with Data Index already running in KIND
# (see test-mode1-e2e.sh for setup)

# 1. Create your Quarkus Flow project
mvn io.quarkus:quarkus-maven-plugin:create \
  -DprojectGroupId=com.example \
  -DprojectArtifactId=my-workflows \
  -Dextensions=quarkus-flow

cd my-workflows

# 2. Add Kubernetes dependencies via Maven profile
# Edit pom.xml and add this <profiles> section before </project>:
#
# <profiles>
#   <profile>
#     <id>kind</id>
#     <activation>
#       <property>
#         <name>quarkus.profile</name>
#         <value>kubernetes</value>
#       </property>
#     </activation>
#     <dependencies>
#       <dependency>
#         <groupId>io.quarkus</groupId>
#         <artifactId>quarkus-kubernetes</artifactId>
#       </dependency>
#       <dependency>
#         <groupId>io.quarkus</groupId>
#         <artifactId>quarkus-container-image-jib</artifactId>
#       </dependency>
#       <dependency>
#         <groupId>io.quarkus</groupId>
#         <artifactId>quarkus-smallrye-health</artifactId>
#       </dependency>
#     </dependencies>
#   </profile>
# </profiles>

# 3. Create your workflow classes
# ... (create workflow classes, etc.)

# 3. Configure for KIND
cat > src/main/resources/application.properties <<EOF
quarkus.application.name=my-workflows

# Quarkus Flow structured logging (REQUIRED)
quarkus.flow.structured-logging.enabled=true
quarkus.flow.structured-logging.events=workflow.*
quarkus.flow.structured-logging.include-workflow-payloads=true
quarkus.flow.structured-logging.include-task-payloads=false
quarkus.flow.structured-logging.timestamp-format=epoch-seconds
quarkus.flow.structured-logging.log-level=INFO

# Console handler for raw JSON output (REQUIRED)
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled=true
quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".format=%s%n

# Route structured logging to console (REQUIRED)
quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers=FLOW_EVENTS_CONSOLE
quarkus.log.category."io.quarkiverse.flow.structuredlogging".use-parent-handlers=false
quarkus.log.category."io.quarkiverse.flow.structuredlogging".level=INFO

# Health checks
quarkus.smallrye-health.ui.enabled=true
EOF

cat > src/main/resources/application-kubernetes.properties <<EOF
quarkus.kubernetes.deployment-target=kind
quarkus.kind.cluster-name=data-index-test
quarkus.kubernetes.namespace=workflows
quarkus.container-image.group=local
quarkus.container-image.name=\${quarkus.application.name}
quarkus.container-image.tag=dev
quarkus.container-image.build=true
quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod
quarkus.kubernetes.resources.requests.memory=128Mi
quarkus.kubernetes.resources.limits.memory=256Mi
EOF

cat > src/main/resources/application-prod.properties <<EOF
# Production runtime settings
# Add your production-specific configuration here
EOF

# 4. Deploy to KIND in one command!
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes

# 5. Test your workflow
kubectl port-forward -n workflows svc/my-workflows 8080:8080 &
curl -X POST http://localhost:8080/test/my-workflow/start -d '{"test":"data"}'

# 6. Query Data Index (wait 10 seconds first)
sleep 10
curl -s http://localhost:30080/graphql \
  -H "Content-Type: application/json" \
  -d '{"query":"{ getWorkflowInstances(limit: 1) { id name status taskExecutions { taskPosition status } } }"}' \
  | jq .

# 7. Make changes and redeploy
# Edit your code...
mvn clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.profile=kubernetes
# Done! New version is deployed.
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
kubectl logs -n workflows -l app.kubernetes.io/name=my-workflow-app | grep eventType | jq .

# Expected output (raw JSON, not formatted):
# {"eventType":"io.serverlessworkflow.workflow.started.v1","instanceId":"...","timestamp":1777298129.919818,...}

# If you see formatted logs instead of raw JSON, check:
# 1. Console handler format: should be %s%n (not %d{HH:mm:ss} %-5p ...)
# 2. use-parent-handlers: should be false
# 3. Log category: should be 'io.quarkiverse.flow.structuredlogging' (not 'io.quarkiverse.flow')

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
| `quarkus.flow.structured-logging.timestamp-format` | `epoch-seconds` | **CRITICAL**: PostgreSQL compatibility |
| `quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".enabled` | `true` | Create console handler for raw JSON |
| `quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".format` | `%s%n` | Raw output format (no formatting) |
| `quarkus.log.category."io.quarkiverse.flow.structuredlogging".handlers` | `FLOW_EVENTS_CONSOLE` | Route to JSON console handler |
| `quarkus.log.category."io.quarkiverse.flow.structuredlogging".use-parent-handlers` | `false` | Prevent duplicate output |
| `quarkus.log.category."io.quarkiverse.flow.structuredlogging".level` | `INFO` | **CRITICAL**: Use specific package, not `io.quarkiverse.flow` |

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

### ⚠️ Common Configuration Mistakes

| ❌ **WRONG** | ✅ **CORRECT** | Why |
|-------------|---------------|-----|
| `quarkus.log.category."io.quarkiverse.flow".level=DEBUG` | `quarkus.log.category."io.quarkiverse.flow.structuredlogging".level=INFO` | Setting DEBUG on whole package causes issues |
| Missing `timestamp-format=epoch-seconds` | `quarkus.flow.structured-logging.timestamp-format=epoch-seconds` | PostgreSQL expects Unix epoch timestamps |
| Missing console handler | `quarkus.log.handler.console."FLOW_EVENTS_CONSOLE".*` | Structured events need raw JSON output |
| `use-parent-handlers=true` | `use-parent-handlers=false` | Prevents duplicate/formatted output |
| Wrong namespace | `quarkus.kubernetes.namespace=workflows` | FluentBit collects from `workflows` namespace |
| Missing `QUARKUS_PROFILE=prod` | `quarkus.kubernetes.env.vars.QUARKUS_PROFILE=prod` | Prevents kubernetes profile at runtime |

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
