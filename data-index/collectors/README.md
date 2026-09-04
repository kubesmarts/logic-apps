# Data Index Collectors

**Dual-purpose module:** Go module (for operator) + Maven module (for tests)

This directory provides production-ready configurations for collecting Quarkus Flow structured logs and forwarding them to Data Index storage backends.

**Go Module:** `github.com/kubesmarts/logic-apps/data-index/collectors`  
**Maven Artifact:** `org.kubesmarts.logic.apps:data-index-collectors` (test-only, not deployed)

## Dual Purpose

This directory serves two purposes:

1. **Go Module** (via `go.mod`) - Configs consumed by logic-operator
2. **Maven Module** (via `pom.xml`) - Tests validate configs work correctly

Both share the same `vector/` configuration files - single source of truth!

## Contents

```
collectors/
├── go.mod                    # Go module definition
├── vector/                   # Vector configurations (production-ready)
│   ├── mode1-postgresql/     # MODE 1: PostgreSQL + Triggers
│   │   ├── vector.yaml
│   │   └── daemonset.yaml
│   └── mode2-elasticsearch/  # MODE 2: Elasticsearch + Transforms
│       ├── vector.yaml
│       └── daemonset.yaml
└── fluentbit/                # FluentBit (deprecated, kept for 1 release)
```

## Consumed By

### logic-operator (Kubernetes Operator)

The `logic-operator` consumes these configs as a Go module dependency and uses them as reference/templates for deploying Data Index.

**Operator integration pattern:**

#### 1. Add Dependency

**logic-operator/go.mod:**
```go
module github.com/kubesmarts/logic-operator

go 1.22

require (
    github.com/kubesmarts/logic-apps/data-index/collectors v1.0.0
    // ... other dependencies
)
```

#### 2. Sync Configs in Makefile

**logic-operator/Makefile:**
```makefile
.PHONY: generate
generate: controller-gen sync-configs
	$(CONTROLLER_GEN) object:headerFile="hack/boilerplate.go.txt" paths="./..."

.PHONY: sync-configs
sync-configs:
	@echo "Syncing data-index configs..."
	@mkdir -p internal/configs
	@MODPATH=$$(go list -m -f '{{.Dir}}' github.com/kubesmarts/logic-apps/data-index/collectors); \
	rm -rf internal/configs/vector; \
	cp -r $$MODPATH/vector internal/configs/
	@echo "✓ Configs synced"
```

#### 3. Add to .gitignore

**logic-operator/.gitignore:**
```gitignore
# Generated configs (synced from dependencies)
internal/configs/
```

#### 4. Embed Vector Config (NOT DaemonSet!)

**logic-operator/controllers/dataindex_controller.go:**
```go
package controllers

import (
    _ "embed"
    appsv1 "k8s.io/api/apps/v1"
    corev1 "k8s.io/api/core/v1"
)

// Embed ONLY the Vector configuration
//go:embed internal/configs/vector/mode2-elasticsearch/vector.yaml
var vectorElasticsearchConfigTemplate string

func (r *DataIndexReconciler) reconcileVectorCollector(ctx context.Context, cr *v1alpha1.DataIndex) error {
    // 1. Template Vector config with CR values
    vectorConfig := r.templateVectorConfig(vectorElasticsearchConfigTemplate, cr)
    
    // 2. Create ConfigMap with Vector config
    configMap := &corev1.ConfigMap{
        ObjectMeta: metav1.ObjectMeta{
            Name:      "data-index-vector-config",
            Namespace: cr.Namespace,
        },
        Data: map[string]string{
            "vector.yaml": vectorConfig,
        },
    }
    
    // 3. Build DaemonSet PROGRAMMATICALLY (don't embed!)
    daemonSet := r.buildVectorDaemonSet(cr)
    
    // 4. Apply to cluster
    return r.applyResources(ctx, configMap, daemonSet)
}

func (r *DataIndexReconciler) buildVectorDaemonSet(cr *v1alpha1.DataIndex) *appsv1.DaemonSet {
    // Operator builds DaemonSet programmatically based on CR
    // See examples/mode2-elasticsearch/daemonset.yaml for reference structure
    return &appsv1.DaemonSet{
        // ... construct DaemonSet object
    }
}
```

**IMPORTANT:** 
- ✅ **Embed:** `vector.yaml` (configs for templating)
- ❌ **Don't embed:** DaemonSet (operator builds programmatically)
- 📄 **Reference:** `examples/mode2-elasticsearch/daemonset.yaml` shows what a working DaemonSet looks like

#### 5. Developer Workflow

```bash
# Clone operator repo
git clone github.com/kubesmarts/logic-operator
cd logic-operator

# Download dependencies and generate configs
make generate

# Build operator
make build

# Run tests
make test
```

#### 6. Updating Data Index Version

```bash
# Update dependency version
go get github.com/kubesmarts/logic-apps/data-index/collectors@v1.1.0

# Regenerate configs
make generate

# Verify changes
git diff internal/configs/  # Not committed, just for review

# Test with new configs
make test
```

#### 7. CI/CD Integration

**logic-operator/.github/workflows/ci.yml:**
```yaml
name: CI

on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - uses: actions/setup-go@v5
        with:
          go-version: '1.22'
          cache: true
      
      - name: Download dependencies
        run: go mod download
      
      - name: Generate manifests and configs
        run: make generate manifests
      
      - name: Test
        run: make test
      
      - name: Build
        run: make build
```

## Responsibility Model

### data-index Guarantees

✅ **Configs work correctly** - Logs are ingested and reach storage  
✅ **Configs are tested** - Integration tests validate functionality  
✅ **Configs are documented** - Clear usage and customization guide  

### logic-operator Customizes

🔧 **Environment-specific settings** - Namespaces, resources, limits  
🔧 **Secrets management** - Database credentials, Elasticsearch auth  
🔧 **Infrastructure deployment** - DaemonSet, ConfigMap, RBAC  

**Analogy:**
- data-index = "Here's a working car engine (configs)"
- logic-operator = "I'll install it, connect fuel, cooling, resources"

## Testing (Maven Module)

This directory is also a **Maven module** with integration tests.

### Running Tests

```bash
# From collectors/ directory
mvn test                    # Fast: Syntax validation only
mvn verify                  # Slower: Full E2E tests (requires Docker)

# Or from data-index parent
cd data-index
mvn test -pl collectors
```

### What Gets Tested

**VectorConfigValidationIT:**
- ✅ Syntactic validation (`vector validate --config-yaml`)
- ✅ Config structure assertions (sources, sinks, API)
- ✅ YAML parsing validation

**Future tests:**
- ✅ Deployment validation (Testcontainers + KIND)
- ✅ E2E validation (events reach storage)

### Test Structure

```
collectors/
├── pom.xml                   # Maven module definition
├── src/test/java/            # Integration tests
│   └── .../VectorConfigValidationIT.java
└── vector/                   # Configs tested by Maven, consumed by Go
```

Tests guarantee configs work **before** logic-operator consumes them.

## Manual Deployment

For development/testing without the operator:

```bash
# Download configs from GitHub release
curl -L https://github.com/kubesmarts/logic-apps/releases/download/v1.0.0/data-index-collectors-vector-1.0.0-configs.tar.gz | tar -xz

# Or use Go module cache
MODPATH=$(go list -m -f '{{.Dir}}' github.com/kubesmarts/logic-apps/data-index/collectors)
cd $MODPATH

# Deploy to Kubernetes
kubectl apply -f vector/mode2-elasticsearch/kubernetes/
```

## Versioning

**Git tags control versions:**

```bash
# Tag the release
git tag v1.0.0
git push origin v1.0.0

# Go module available at:
# github.com/kubesmarts/logic-apps/data-index/collectors@v1.0.0
```

**Semantic versioning:**
- **Major** (v2.0.0): Breaking config changes (schema, required fields)
- **Minor** (v1.1.0): New features (new deployment modes, optional fields)
- **Patch** (v1.0.1): Bug fixes (config corrections, documentation updates)

## Architecture Decision

See: `../../adrs/0001-migrate-to-vector-for-openshift-alignment.md`

**Why Vector?**
- Red Hat OpenShift 4.10+ uses Vector as standard log collector
- Better performance (30-50% throughput, 20-30% less memory)
- Native Kubernetes integration
- VRL (Vector Remap Language) more powerful than Lua

## Support

- **Issues:** https://github.com/kubesmarts/logic-apps/issues
- **Documentation:** See `data-index/data-index-docs/`
- **Operator Integration:** See above sections

## License

Apache License 2.0 - See LICENSE file in repository root.
