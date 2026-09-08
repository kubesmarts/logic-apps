#!/usr/bin/env bash

# ============================================================================
# Full E2E Test - MODE 1 (PostgreSQL + FluentBit)
# ============================================================================
#
# Complete workflow:
# 1. Delete existing KIND cluster
# 2. Create fresh cluster and build/load images
# 3. Deploy MODE 1 with Helm
# 4. Run Java E2E tests
# 5. Cleanup
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
readonly NAMESPACE="default"
readonly RELEASE_NAME="data-index"
readonly MODE="mode1"

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

# ============================================================================
# Main Test Flow
# ============================================================================

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║  MODE 1 Full E2E Test - PostgreSQL + FluentBit + Triggers     ║"
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

    log_info "Waiting for FluentBit..."
    kubectl wait --namespace logging \
        --for=condition=ready pod \
        -l app=fluentbit \
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
    kubectl get pods --all-namespaces | grep -E "(NAMESPACE|postgresql|data-index|fluentbit|workflow)" || true

    # 6. Verify infrastructure ready
    log_step "Step 6: Verify Infrastructure Ready"
    bash "${SCRIPT_DIR}/verify-infrastructure.sh" mode1

    # 7. Run Java E2E tests
    log_step "Step 7: Running Java E2E Tests"

    log_info "Navigating to E2E tests module..."
    cd "${PROJECT_ROOT}/data-index/data-index-e2e-tests"

    log_info "Running MODE 1 E2E tests..."
    mvn clean test -De2e.skip=false \
        -De2e.mode=mode1 \
        -De2e.graphql.url=http://localhost:30080/graphql \
        -De2e.workflow.url=http://localhost:30082

    # 7. Success summary
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    ✅ MODE 1 E2E TEST PASSED                   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    log_info "Architecture verified:"
    echo "  Quarkus Flow → /tmp/quarkus-flow-events.log"
    echo "            ↓ (FluentBit tail)"
    echo "  PostgreSQL raw tables (JSONB)"
    echo "            ↓ (BEFORE INSERT triggers)"
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
