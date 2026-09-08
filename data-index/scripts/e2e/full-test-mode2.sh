#!/usr/bin/env bash

# ============================================================================
# Full E2E Test - MODE 2 (Elasticsearch + Vector)
# ============================================================================

set -euo pipefail

# Colors
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m'

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
readonly HELM_CHART_DIR="${PROJECT_ROOT}/data-index/helm/data-index"
readonly CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
readonly NAMESPACE="default"
readonly RELEASE_NAME="data-index"
readonly MODE="mode2"

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step() { echo ""; echo -e "${BLUE}==>${NC} $*"; }

cleanup_cluster() {
    log_step "Cleaning up KIND cluster"
    if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
        kind delete cluster --name "${CLUSTER_NAME}"
        log_success "Cluster deleted"
    fi
}

main() {
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║  MODE 2 Full E2E Test - Elasticsearch + Vector + Transforms   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""

    log_step "Step 1: Delete existing KIND cluster"
    cleanup_cluster

    log_step "Step 2: Create cluster and build/load images (MODE 2 only)"
    export MODE
    bash "${SCRIPT_DIR}/common-setup.sh"

    log_step "Step 3: Deploy MODE 2 with Helm"
    helm upgrade --install "${RELEASE_NAME}" "${HELM_CHART_DIR}" \
        --namespace "${NAMESPACE}" \
        --create-namespace \
        --values "${HELM_CHART_DIR}/values-mode2.yaml" \
        --wait \
        --timeout 10m

    log_step "Step 4: Waiting for all pods to be ready"
    kubectl wait --namespace elasticsearch --for=condition=ready pod/elasticsearch-0 --timeout=180s
    kubectl wait --namespace "${NAMESPACE}" --for=condition=ready pod -l app=data-index-service --timeout=180s
    kubectl wait --namespace logging --for=condition=ready pod -l app=vector --timeout=120s
    kubectl wait --namespace workflows --for=condition=ready pod -l app=workflow-test-app --timeout=120s
    log_success "All pods ready"

    log_step "Step 5: Component Status"
    kubectl get pods --all-namespaces | grep -E "(NAMESPACE|elasticsearch|data-index|vector|workflow)" || true

    log_step "Step 6: Running Java E2E Tests"
    cd "${PROJECT_ROOT}/data-index/data-index-e2e-tests"
    mvn clean test -De2e.skip=false -De2e.mode=mode2

    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                    ✅ MODE 2 E2E TEST PASSED                   ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    log_success "MODE 2 full E2E test completed successfully!"
}

main "$@"
