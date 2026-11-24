#!/usr/bin/env bash

# ============================================================================
# Full E2E Test - MODE 1 (PostgreSQL + Vector)
# ============================================================================
#
# Complete workflow:
# 1. Delete existing KIND cluster
# 2. Create fresh cluster and build/load images
# 3. Deploy MODE 1 with Helm
# 4. Verify Vector tails a second workflow namespace
# 5. Run Java E2E tests
# 6. Cleanup
#
# ============================================================================

set -euo pipefail

# Colors for output
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

# Script directory
readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
readonly HELM_CHART_DIR="${PROJECT_ROOT}/data-index/helm/data-index"

# Configuration
readonly CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
readonly NAMESPACE="data-index"
readonly RELEASE_NAME="data-index"
readonly MODE="mode1"
readonly SECOND_WORKFLOW_NAMESPACE="workflows-b"
readonly RUN_WORKFLOWS="${RUN_WORKFLOWS:-0}"  # run each test workflow N times after deploy
readonly SKIP_TESTS="${SKIP_TESTS:-false}"

# ============================================================================
# Helper Functions
# ============================================================================

log_info() {
    echo -e "${BLUE}[INFO]${NC} $*"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $*"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $*"
}

log_step() {
    echo ""
    echo -e "${BLUE}==>${NC} $*"
}

cleanup_cluster() {
    log_step "Cleaning up KIND cluster"
    if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
        kind delete cluster --name "${CLUSTER_NAME}"
        log_success "Cluster deleted"
    else
        log_info "No cluster to delete"
    fi
}

# Deploys a second workflow-test-app outside the chart's `workflows` namespace
# and checks that its events reach Data Index.
verify_second_namespace() {
    log_info "Deploying workflow-test-app to ${SECOND_WORKFLOW_NAMESPACE}..."
    kubectl create namespace "${SECOND_WORKFLOW_NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -
    helm template "${RELEASE_NAME}" "${HELM_CHART_DIR}" \
        --values "${HELM_CHART_DIR}/values-mode1.yaml" \
        --set workflowApp.service.type=ClusterIP \
        --show-only templates/workflow-test-app.yaml \
        | sed "s/namespace: workflows$/namespace: ${SECOND_WORKFLOW_NAMESPACE}/" \
        | kubectl apply -f -
    kubectl wait --namespace "${SECOND_WORKFLOW_NAMESPACE}" \
        --for=condition=ready pod \
        -l app=workflow-test-app \
        --timeout=180s

    kubectl port-forward --namespace "${SECOND_WORKFLOW_NAMESPACE}" svc/workflow-test-app 30083:8080 >/dev/null 2>&1 &
    local pf_pid=$!

    local instance_id=""
    for _ in $(seq 1 15); do
        instance_id=$(curl -sf -X POST http://localhost:30083/test-workflows/hello-world \
            -H 'Content-Type: application/json' -d '{"message": "multi-namespace"}' | jq -r '.id' || true)
        [[ -n "${instance_id}" ]] && break
        sleep 2
    done
    kill "${pf_pid}" 2>/dev/null || true

    if [[ -z "${instance_id}" ]]; then
        log_error "Could not run hello-world in ${SECOND_WORKFLOW_NAMESPACE}"
        exit 1
    fi

    log_info "Waiting for instance ${instance_id} in GraphQL..."
    local query="{\"query\": \"{ getWorkflowInstance(id: \\\"${instance_id}\\\") { id } }\"}"
    for _ in $(seq 1 30); do
        if curl -sf http://localhost:30080/graphql -H 'Content-Type: application/json' -d "${query}" \
            | jq -e '.data.getWorkflowInstance.id' >/dev/null; then
            log_success "Events from ${SECOND_WORKFLOW_NAMESPACE} reached Data Index"
            return
        fi
        sleep 2
    done

    log_error "Instance ${instance_id} from ${SECOND_WORKFLOW_NAMESPACE} never reached Data Index"
    exit 1
}

# ============================================================================
# Main Test Flow
# ============================================================================

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║  MODE 1 Full E2E Test - PostgreSQL + Vector + JDBC Polling    ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    # 1. Delete existing cluster
    log_step "Step 1: Delete existing KIND cluster"
    cleanup_cluster

    # 2. Create cluster and build images (MODE 1 only)
    log_step "Step 2: Create cluster and build/load images (MODE 1 only)"
    export MODE
    bash "${SCRIPT_DIR}/common-setup.sh"

    # 3. Deploy MODE 1 with Helm
    log_step "Step 3: Deploy MODE 1 with Helm"

    log_info "Installing Helm chart (MODE 1)..."
    helm upgrade --install "${RELEASE_NAME}" "${HELM_CHART_DIR}" \
        --namespace "${NAMESPACE}" \
        --create-namespace \
        --values "${HELM_CHART_DIR}/values-mode1.yaml" \
        --set "vector.workflowNamespaces={workflows,${SECOND_WORKFLOW_NAMESPACE}}" \
        --wait \
        --timeout 10m

    log_success "Helm chart installed"

    # 4. Wait for pods to be ready
    log_step "Step 4: Waiting for all pods to be ready"

    log_info "Waiting for PostgreSQL..."
    kubectl wait --namespace postgresql \
        --for=condition=ready pod/postgresql-0 \
        --timeout=120s

    log_info "Waiting for Data Index Service..."
    kubectl wait --namespace "${NAMESPACE}" \
        --for=condition=ready pod \
        -l app=data-index-service \
        --timeout=180s

    log_info "Waiting for Vector..."
    kubectl wait --namespace logging \
        --for=condition=ready pod \
        -l app=vector \
        --timeout=120s

    log_info "Waiting for Workflow Test App..."
    kubectl wait --namespace workflows \
        --for=condition=ready pod \
        -l app=workflow-test-app \
        --timeout=120s

    log_success "All pods ready"

    # 5. Display pod status
    log_step "Step 5: Component Status"
    echo ""
    kubectl get pods --all-namespaces | grep -E "(NAMESPACE|postgresql|data-index|vector|workflow)" || true

    # 6. Verify infrastructure ready
    log_step "Step 6: Verify Infrastructure Ready"
    export POSTGRES_SCHEMA="logicapps"
    bash "${SCRIPT_DIR}/verify-infrastructure.sh" mode1

    # 7. Verify a second workflow namespace
    log_step "Step 7: Verify second workflow namespace (${SECOND_WORKFLOW_NAMESPACE})"
    verify_second_namespace

    if [[ "${RUN_WORKFLOWS}" -gt 0 ]]; then
        log_step "Running test workflows"
        bash "${SCRIPT_DIR}/run-workflows.sh" "${RUN_WORKFLOWS}"
    fi

    if [[ "${SKIP_TESTS}" == "true" ]]; then
        log_info "Skipping Java E2E tests (SKIP_TESTS=true)"
        log_info "GraphQL API: http://localhost:30080/graphql"
        log_info "To cleanup: kind delete cluster --name ${CLUSTER_NAME}"
        return
    fi

    # 8. Run Java E2E tests
    log_step "Step 8: Running Java E2E Tests"

    log_info "Navigating to E2E tests module..."
    cd "${PROJECT_ROOT}/data-index/data-index-e2e-tests"

    log_info "Running MODE 1 E2E tests..."
    mvn clean test -De2e.skip=false \
        -De2e.mode=mode1 \
        -De2e.graphql.url=http://localhost:30080/graphql \
        -De2e.workflow.url=http://localhost:30082

    # 9. Success summary
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    ✅ MODE 1 E2E TEST PASSED                   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    log_info "Architecture verified:"
    echo "  Quarkus Flow → stdout → /var/log/containers/"
    echo "            ↓ (Vector kubernetes_logs → postgres sink)"
    echo "  PostgreSQL staging tables (typed columns)"
    echo "            ↓ (data-index-service polls, idempotent UPSERT)"
    echo "  PostgreSQL normalized tables"
    echo "            ↓ (JPA/Hibernate)"
    echo "  GraphQL API (SmallRye GraphQL)"

    echo ""
    log_info "Access Information:"
    echo "  GraphQL UI:    http://localhost:30080/q/graphql-ui"
    echo "  GraphQL API:   http://localhost:30080/graphql"
    echo "  Workflow App:  http://localhost:30082/q/dev"
    echo "  PostgreSQL:    kubectl port-forward -n postgresql svc/postgresql 5432:5432"

    echo ""
    log_info "Cluster Info:"
    echo "  Cluster:       ${CLUSTER_NAME}"
    echo "  Helm Release:  ${RELEASE_NAME}"
    echo "  Namespace:     ${NAMESPACE}"

    echo ""
    log_success "MODE 1 full E2E test completed successfully!"
    echo ""
    log_info "To cleanup: kind delete cluster --name ${CLUSTER_NAME}"
    echo ""
}

# Run main function
main "$@"
