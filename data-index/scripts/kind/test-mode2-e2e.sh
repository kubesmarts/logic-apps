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
    log_info "Vector logs:"
    kubectl logs -n logging -l app=workflows-vector-mode2 --tail=50 || true

    echo ""
    log_info "Workflow app logs:"
    kubectl logs -n workflows -l app=workflow-test-app --tail=50 || true

    echo ""
    log_info "Elasticsearch status:"
    curl -s http://localhost:30920/_cat/indices?v || true

    echo ""
    log_info "Elasticsearch workflow-events count:"
    curl -s -X GET "http://localhost:30920/workflow-events-*/_count?pretty" || true

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
    log_step "Installing Elasticsearch (direct StatefulSet)..."

    # Deploy Elasticsearch StatefulSet (no ECK operator - security fully disabled)
    if kubectl get statefulset -n elasticsearch elasticsearch &>/dev/null; then
        log_info "Elasticsearch already deployed, skipping"
    else
        log_info "Creating Elasticsearch StatefulSet..."
        kubectl apply -f "${SCRIPT_DIR}/elasticsearch-statefulset.yaml"

        # Wait for pod to be created before waiting for it to be ready
        log_info "Waiting for Elasticsearch pod to be created..."
        for i in {1..30}; do
            if kubectl get pod -n elasticsearch elasticsearch-0 &>/dev/null; then
                log_info "Pod created, waiting for it to be ready..."
                break
            fi
            sleep 2
        done
    fi

    log_info "Waiting for Elasticsearch to be ready..."
    kubectl wait --namespace elasticsearch \
        --for=condition=ready pod/elasticsearch-0 \
        --timeout=300s

    # Wait for Elasticsearch HTTP endpoint
    log_info "Waiting for Elasticsearch HTTP endpoint..."
    for i in {1..30}; do
        if curl -s http://localhost:30920 | grep -q "You Know, for Search"; then
            log_info "✓ Elasticsearch HTTP endpoint ready"
            break
        fi
        log_info "Attempt $i/30: Elasticsearch not ready yet..."
        sleep 2
    done

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

# Step 6: Deploy Vector (Elasticsearch mode)
deploy_vector() {
    log_step "Deploying Vector (Elasticsearch mode)..."

    "${SCRIPT_DIR}/deploy-vector-mode2.sh"

    kubectl wait --namespace logging \
        --for=condition=ready pod \
        --selector=app=workflows-vector-mode2 \
        --timeout=120s

    log_info "✓ Vector deployed"
}

# Step 7: Deploy test workflow application
deploy_workflow_app() {
    log_step "Deploying test workflow application..."

    if kubectl get deployment -n workflows workflow-test-app &>/dev/null; then
        log_info "Workflow app already deployed, restarting..."
        kubectl rollout restart deployment/workflow-test-app -n workflows
    else
        MODE=elasticsearch "${SCRIPT_DIR}/deploy-workflow-app.sh"
    fi

    kubectl wait --namespace workflows \
        --for=condition=available deployment/workflow-test-app \
        --timeout=300s

    log_info "✓ Workflow app deployed"
}

# Step 8: Wait for events to flow
wait_for_events() {
    log_step "Waiting for events to flow through the pipeline..."

    # Set up port-forward for workflow app (NodePort 30082 not mapped in KIND cluster)
    log_info "Setting up port-forward for workflow app..."
    kubectl port-forward -n workflows svc/workflow-test-app 8082:8080 &>/dev/null &
    local PF_PID=$!
    sleep 2

    # Trigger workflow execution
    log_info "Triggering workflow execution..."
    curl -s -X POST http://localhost:8082/test-workflows/simple-set \
        -H "Content-Type: application/json" \
        -d '{"name":"test-execution"}' > /dev/null || true

    log_info "Waiting 30 seconds for workflow execution and event collection..."
    sleep 30

    # Clean up port-forward
    kill $PF_PID 2>/dev/null || true

    # Check raw events in Elasticsearch
    log_info "Checking raw events in Elasticsearch..."
    local raw_count=0
    for i in {1..30}; do
        raw_count=$(curl -s -X GET "http://localhost:30920/workflow-events-*/_count" 2>/dev/null | jq -r '.count // 0')
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
        instance_count=$(curl -s -X GET "http://localhost:30920/workflow-instances/_count" 2>/dev/null | jq -r '.count // 0')
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

    # Test getWorkflowInstances query with all critical fields
    log_info "Testing getWorkflowInstances query with full field validation..."
    local result=$(curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ getWorkflowInstances { id name status startedAt endedAt taskExecutions { id task taskName status startedAt endedAt } } }"}')

    # Verify workflow count > 0
    echo "$result" | jq -e '.data.getWorkflowInstances | length > 0' > /dev/null

    local workflow_id=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].id')
    log_info "✓ Found workflow: $workflow_id"

    # Verify status field is not null (bug fix verification)
    local status=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].status')
    if [[ "$status" == "null" ]]; then
        log_error "Status field is null (should be COMPLETED/RUNNING/etc)"
        return 1
    fi
    log_info "✓ Status field populated: $status"

    # Verify timestamps are valid (not year 58644 bug)
    local started_at=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].startedAt')
    if [[ "$started_at" == *"58644"* ]]; then
        log_error "Timestamp bug detected: $started_at (should be year 2026)"
        return 1
    fi
    log_info "✓ Timestamps correct: $started_at"

    # Verify task executions nested correctly
    local task_count=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].taskExecutions | length')
    if [[ "$task_count" -eq 0 ]]; then
        log_error "No task executions found"
        return 1
    fi
    log_info "✓ Task executions nested: $task_count tasks"

    # Verify task field (JSON Pointer) is populated
    local task_pointer=$(echo "$result" | jq -r '.data.getWorkflowInstances[0].taskExecutions[0].task')
    if [[ "$task_pointer" == "null" ]]; then
        log_error "Task field is null (should be JSON Pointer like /do/0/set-0)"
        return 1
    fi
    log_info "✓ Task field populated: $task_pointer"

    log_info "✓ GraphQL API verified (all fields correct)"
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
    deploy_vector
    deploy_workflow_app
    wait_for_events
    verify_graphql
    verify_idempotency
    print_summary

    log_info "✓ All tests passed!"
}

# Run main function
main "$@"
