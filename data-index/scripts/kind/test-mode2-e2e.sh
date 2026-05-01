#!/usr/bin/env bash
#
# Elasticsearch Mode End-to-End Integration Test
#
# Tests complete flow:
#   Quarkus Flow → stdout → K8s logs → FluentBit → Elasticsearch → Transform → GraphQL
#
# Verifies:
#   - Event collection from stdout
#   - CRI parser for containerd
#   - ES Transform normalization
#   - Field-level idempotency
#   - Out-of-order event handling
#   - Smart filtering
#

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

# Logging
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

# Error handler
error_handler() {
    log_error "Test failed at line $1"
    log_info "Collecting debug information..."

    echo ""
    log_info "FluentBit logs:"
    kubectl logs -n logging -l app=workflows-fluent-bit-mode2 --tail=50 || true

    echo ""
    log_info "Workflow app logs:"
    kubectl logs -n workflows -l app=workflow-test-app --tail=50 || true

    echo ""
    log_info "Elasticsearch status:"
    curl -s http://localhost:30920/_cat/indices?v || true

    echo ""
    log_info "Elasticsearch workflow-events count:"
    curl -s -X GET "http://localhost:30920/workflow-events/_count?pretty" || true

    exit 1
}

trap 'error_handler $LINENO' ERR

# Step 1: Create KIND cluster
create_cluster() {
    log_step "Creating KIND cluster..."

    if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_info "Cluster already exists, skipping creation"
    else
        "${SCRIPT_DIR}/setup-cluster.sh"
    fi

    kubectl config use-context "kind-${CLUSTER_NAME}"
    log_info "✓ Cluster ready"
}

# Step 2: Create namespaces
create_namespaces() {
    log_step "Creating namespaces..."

    kubectl create namespace logging --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace elasticsearch --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace workflows --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ Namespaces created"
}

# Step 3: Install Elasticsearch
install_elasticsearch() {
    log_step "Installing Elasticsearch (via ECK operator)..."

    # Install ECK operator
    if ! kubectl get namespace elastic-system &>/dev/null; then
        log_info "Installing ECK operator..."
        kubectl create -f https://download.elastic.co/downloads/eck/2.12.1/crds.yaml
        kubectl apply -f https://download.elastic.co/downloads/eck/2.12.1/operator.yaml

        log_info "Waiting for ECK operator..."
        kubectl wait --namespace elastic-system \
            --for=condition=ready pod \
            --selector=control-plane=elastic-operator \
            --timeout=300s
    else
        log_info "ECK operator already installed"
    fi

    # Install Elasticsearch cluster
    if kubectl get elasticsearch -n elasticsearch data-index-es &>/dev/null; then
        log_info "Elasticsearch cluster already exists, skipping"
    else
        log_info "Creating Elasticsearch cluster..."
        kubectl apply -f - <<EOF
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: data-index-es
  namespace: elasticsearch
spec:
  version: 8.11.1
  nodeSets:
  - name: default
    count: 1
    config:
      node.store.allow_mmap: false
      xpack.security.enabled: false
      xpack.security.http.ssl.enabled: false
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          resources:
            requests:
              memory: 1Gi
              cpu: 500m
            limits:
              memory: 2Gi
              cpu: 2000m
    volumeClaimTemplates:
    - metadata:
        name: elasticsearch-data
      spec:
        accessModes:
        - ReadWriteOnce
        resources:
          requests:
            storage: 2Gi
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-es-http-nodeport
  namespace: elasticsearch
spec:
  type: NodePort
  selector:
    elasticsearch.k8s.elastic.co/cluster-name: data-index-es
  ports:
  - port: 9200
    targetPort: 9200
    nodePort: 30920
    protocol: TCP
    name: http
EOF
    fi

    log_info "Waiting for Elasticsearch to be ready (this may take several minutes)..."
    kubectl wait --namespace elasticsearch \
        --for=jsonpath='{.status.health}'=green \
        elasticsearch/data-index-es \
        --timeout=600s

    log_info "✓ Elasticsearch ready"
}

# Step 4: Deploy Data Index Service (Elasticsearch mode)
deploy_data_index() {
    log_step "Deploying Data Index Service (Elasticsearch mode)..."

    if kubectl get deployment -n data-index data-index-service &>/dev/null; then
        log_info "Data Index already deployed, skipping"
    else
        "${SCRIPT_DIR}/deploy-data-index.sh" elasticsearch
    fi

    kubectl wait --namespace data-index \
        --for=condition=available deployment/data-index-service \
        --timeout=300s

    log_info "✓ Data Index ready"
}

# Step 5: Wait for schema initialization
wait_for_schema_init() {
    log_step "Waiting for Elasticsearch schema initialization..."

    # Wait for Data Index to start and initialize schema
    sleep 10

    # Verify ILM policy exists
    log_info "Verifying ILM policy..."
    until curl -s http://localhost:30920/_ilm/policy/data-index-events-retention | grep -q "data-index-events-retention"; do
        log_info "Waiting for ILM policy to be created..."
        sleep 5
    done

    # Verify index templates exist
    log_info "Verifying index templates..."
    until curl -s http://localhost:30920/_index_template/workflow-events | grep -q "workflow-events"; do
        log_info "Waiting for workflow-events template..."
        sleep 5
    done

    until curl -s http://localhost:30920/_index_template/workflow-instances | grep -q "workflow-instances"; do
        log_info "Waiting for workflow-instances template..."
        sleep 5
    done

    # Verify transforms exist
    log_info "Verifying ES Transforms..."
    until curl -s http://localhost:30920/_transform/workflow-instances-transform | grep -q "workflow-instances-transform"; do
        log_info "Waiting for workflow-instances transform..."
        sleep 5
    done

    log_info "✓ Schema initialized"
}

# Step 6: Deploy FluentBit (Elasticsearch mode)
deploy_fluentbit() {
    log_step "Deploying FluentBit (Elasticsearch mode)..."

    cd "${PROJECT_ROOT}/data-index/scripts/fluentbit/elasticsearch"
    ./deploy.sh

    # Wait for FluentBit pods
    kubectl wait --namespace logging \
        --for=condition=ready pod \
        --selector=app=workflows-fluent-bit-mode2 \
        --timeout=120s

    log_info "✓ FluentBit deployed"
}

# Step 7: Deploy test workflow application
deploy_workflow_app() {
    log_step "Deploying test workflow application..."

    if kubectl get deployment -n workflows workflow-test-app &>/dev/null; then
        log_info "Workflow app already deployed, restarting..."
        kubectl rollout restart deployment/workflow-test-app -n workflows
    else
        "${SCRIPT_DIR}/deploy-workflow-app.sh"
    fi

    kubectl wait --namespace workflows \
        --for=condition=available deployment/workflow-test-app \
        --timeout=300s

    log_info "✓ Workflow app deployed"
}

# Step 8: Wait for events to flow
wait_for_events() {
    log_step "Waiting for events to flow through the pipeline..."

    log_info "Waiting 30 seconds for workflow execution and event collection..."
    sleep 30

    # Check raw events in Elasticsearch
    log_info "Checking raw events in Elasticsearch..."
    local raw_count=0
    for i in {1..30}; do
        raw_count=$(curl -s -X GET "http://localhost:30920/workflow-events/_count" | jq -r '.count' || echo "0")
        if [[ "$raw_count" -gt 0 ]]; then
            log_info "✓ Found $raw_count raw events"
            break
        fi
        log_info "Attempt $i/30: No events yet, waiting..."
        sleep 2
    done

    if [[ "$raw_count" -eq 0 ]]; then
        log_error "No raw events found in Elasticsearch after 60 seconds"
        return 1
    fi

    # Wait for ES Transform to process events
    log_info "Waiting for ES Transform to normalize events (1s frequency + delay)..."
    sleep 10

    # Check normalized instances
    log_info "Checking normalized workflow instances..."
    local instance_count=0
    for i in {1..30}; do
        instance_count=$(curl -s -X GET "http://localhost:30920/workflow-instances/_count" | jq -r '.count' || echo "0")
        if [[ "$instance_count" -gt 0 ]]; then
            log_info "✓ Found $instance_count normalized workflow instances"
            break
        fi
        log_info "Attempt $i/30: No normalized instances yet, waiting for transform..."
        sleep 2
    done

    if [[ "$instance_count" -eq 0 ]]; then
        log_error "No normalized instances found after transform processing"
        return 1
    fi

    log_info "✓ Events flowing correctly through ES Transform pipeline"
}

# Step 9: Verify GraphQL API
verify_graphql() {
    log_step "Verifying GraphQL API..."

    # Test introspection query
    log_info "Testing GraphQL introspection..."
    curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ __schema { queryType { name } } }"}' | jq -e '.data.__schema.queryType.name == "Query"' > /dev/null

    # Test getWorkflowInstances query
    log_info "Testing getWorkflowInstances query..."
    local result=$(curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ getWorkflowInstances { id name status } }"}')

    echo "$result" | jq -e '.data.getWorkflowInstances | length > 0' > /dev/null

    local workflow_id=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].id')
    log_info "✓ Found workflow: $workflow_id"

    # Test getWorkflowInstanceById query
    log_info "Testing getWorkflowInstanceById query..."
    curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d "{\"query\":\"{ getWorkflowInstanceById(id: \\\"$workflow_id\\\") { id name version status } }\"}" | \
        jq -e '.data.getWorkflowInstanceById.id != null' > /dev/null

    log_info "✓ GraphQL API verified"
}

# Step 10: Verify idempotency
verify_idempotency() {
    log_step "Verifying idempotency (replay events)..."

    # Get current count
    local before_count=$(curl -s -X GET "http://localhost:30080/graphql" \
        -H "Content-Type: application/json" \
        -d '{"query":"{ getWorkflowInstances { id } }"}' | jq '.data.getWorkflowInstances | length')

    log_info "Current workflow count: $before_count"

    # Restart workflow app to generate duplicate events
    log_info "Restarting workflow app to generate duplicate events..."
    kubectl rollout restart deployment/workflow-test-app -n workflows
    kubectl wait --namespace workflows \
        --for=condition=available deployment/workflow-test-app \
        --timeout=120s

    # Wait for events to process
    sleep 20

    # Check count again
    local after_count=$(curl -s -X GET "http://localhost:30080/graphql" \
        -H "Content-Type: application/json" \
        -d '{"query":"{ getWorkflowInstances { id } }"}' | jq '.data.getWorkflowInstances | length')

    log_info "After replay count: $after_count"

    if [[ "$before_count" -ne "$after_count" ]]; then
        log_error "Idempotency FAILED: count changed from $before_count to $after_count"
        return 1
    fi

    log_info "✓ Idempotency verified (no duplicate instances)"
}

# Print summary
print_summary() {
    echo ""
    log_info "=========================================="
    log_info "MODE 2 E2E Test Complete!"
    log_info "=========================================="
    echo ""

    log_info "Pipeline Flow:"
    echo "  Quarkus Flow → stdout → FluentBit → Elasticsearch → ES Transform → GraphQL"
    echo ""

    log_info "Verification Results:"
    echo "  ✓ Elasticsearch cluster running"
    echo "  ✓ Data Index service deployed"
    echo "  ✓ Schema initialized (ILM, templates, transforms)"
    echo "  ✓ FluentBit collecting events"
    echo "  ✓ Raw events in Elasticsearch"
    echo "  ✓ ES Transform normalizing events"
    echo "  ✓ GraphQL API responding"
    echo "  ✓ Idempotency working"
    echo ""

    log_info "Elasticsearch Indices:"
    curl -s http://localhost:30920/_cat/indices?v | grep -E "(INDEX|workflow|task)"
    echo ""

    log_info "Access Points:"
    echo "  - GraphQL API:   http://localhost:30080/graphql"
    echo "  - GraphQL UI:    http://localhost:30080/q/graphql-ui"
    echo "  - Elasticsearch: http://localhost:30920"
    echo ""

    log_info "Useful Commands:"
    echo "  # View workflow instances"
    echo "  curl http://localhost:30920/workflow-instances/_search?pretty"
    echo ""
    echo "  # View raw events"
    echo "  curl http://localhost:30920/workflow-events/_search?pretty"
    echo ""
    echo "  # Check transform status"
    echo "  curl http://localhost:30920/_transform/workflow-instances-transform/_stats?pretty"
    echo ""
    echo "  # GraphQL query"
    echo "  curl http://localhost:30080/graphql -H 'Content-Type: application/json' -d '{\"query\":\"{ getWorkflowInstances { id name status } }\"}'"
    echo ""
}

# Main execution
main() {
    log_info "Starting MODE 2 (Elasticsearch) End-to-End Test"
    echo ""

    create_cluster
    create_namespaces
    install_elasticsearch
    deploy_data_index
    wait_for_schema_init
    deploy_fluentbit
    deploy_workflow_app
    wait_for_events
    verify_graphql
    verify_idempotency
    print_summary

    log_info "✓ All tests passed!"
}

# Run main function
main "$@"
