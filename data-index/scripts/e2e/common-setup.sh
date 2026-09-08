#!/usr/bin/env bash

# ============================================================================
# Common E2E Test Setup
# ============================================================================
#
# Creates KIND cluster and builds required Docker images for testing.
# Shared by all MODE test scripts (MODE 1, MODE 2, MODE 3).
#
# ============================================================================

set -euo pipefail

# Colors for output
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly NC='\033[0m' # No Color

# Configuration
readonly CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
# Script is at data-index/scripts/e2e/, so ../../.. gets us to repo root
readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

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

# ============================================================================
# Main Setup
# ============================================================================

main() {
    log_step "Common E2E Test Setup"

    # 1. Check prerequisites
    log_step "Checking prerequisites"

    for cmd in kind docker kubectl helm; do
        if ! command -v "$cmd" &>/dev/null; then
            log_error "$cmd is not installed"
            exit 1
        fi
        log_info "✓ $cmd"
    done

    # 2. Create or verify KIND cluster
    log_step "Setting up KIND cluster: ${CLUSTER_NAME}"

    if kind get clusters | grep -q "^${CLUSTER_NAME}$"; then
        log_info "Cluster already exists: ${CLUSTER_NAME}"
    else
        log_info "Creating cluster: ${CLUSTER_NAME}"

        # Use KIND cluster configuration from Helm chart
        local KIND_CONFIG="${PROJECT_ROOT}/data-index/helm/data-index/kind-cluster.yaml"

        if [[ ! -f "${KIND_CONFIG}" ]]; then
            log_error "KIND cluster config not found: ${KIND_CONFIG}"
            exit 1
        fi

        kind create cluster --name "${CLUSTER_NAME}" --config="${KIND_CONFIG}"
        log_success "Cluster created"
    fi

    # 3. Build and load Docker images (mode-aware)
    log_step "Building and loading Docker images"

    # Determine which mode (check env var MODE, default to all)
    local BUILD_MODE="${MODE:-all}"
    log_info "Build mode: ${BUILD_MODE}"

    # Build from data-index reactor root to resolve inter-module dependencies
    # Use -pl (project list) and -am (also make dependencies) to build only what's needed

    # Build PostgreSQL variant (MODE 1 and MODE 3)
    if [[ "${BUILD_MODE}" == "all" || "${BUILD_MODE}" == "mode1" || "${BUILD_MODE}" == "mode3" ]]; then
        log_info "Building data-index-service-postgresql + workflow-test-app (PostgreSQL reactor)..."

        # Build both service and workflow-test-app together in reactor
        # This ensures workflow-test-app can resolve its test dependencies
        if [[ "${BUILD_MODE}" == "mode3" ]]; then
            log_info "  → Using Kafka profile for workflow-test-app (MODE 3)"
            (cd "${PROJECT_ROOT}/data-index" && \
                mvn clean package -DskipTests \
                -pl data-index-service/data-index-service-postgresql,workflow-test-app \
                -am \
                -Pkafka \
                -Dquarkus.container-image.build=true \
                -Dquarkus.container-image.tag=999-SNAPSHOT)
        else
            (cd "${PROJECT_ROOT}/data-index" && \
                mvn clean package -DskipTests \
                -pl data-index-service/data-index-service-postgresql,workflow-test-app \
                -am \
                -Dquarkus.container-image.build=true \
                -Dquarkus.container-image.tag=999-SNAPSHOT)
        fi

        kind load docker-image kubesmarts/data-index-service-postgresql:999-SNAPSHOT --name "${CLUSTER_NAME}"
        kind load docker-image kubesmarts/workflow-test-app:999-SNAPSHOT --name "${CLUSTER_NAME}"
    fi

    # Build Elasticsearch variant (MODE 2 only)
    if [[ "${BUILD_MODE}" == "all" || "${BUILD_MODE}" == "mode2" ]]; then
        log_info "Building data-index-service-elasticsearch + workflow-test-app (Elasticsearch reactor)..."

        (cd "${PROJECT_ROOT}/data-index" && \
            mvn clean package -DskipTests \
            -pl data-index-service/data-index-service-elasticsearch,workflow-test-app \
            -am \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.tag=999-SNAPSHOT)

        kind load docker-image kubesmarts/data-index-service-elasticsearch:999-SNAPSHOT --name "${CLUSTER_NAME}"
        kind load docker-image kubesmarts/workflow-test-app:999-SNAPSHOT --name "${CLUSTER_NAME}"
    fi

    # Build Kafka Ingestion Service (MODE 3 only)
    if [[ "${BUILD_MODE}" == "all" || "${BUILD_MODE}" == "mode3" ]]; then
        log_info "Building data-index-ingestion-kafka-service..."

        (cd "${PROJECT_ROOT}/data-index" && \
            mvn clean package -DskipTests \
            -pl data-index-ingestion/data-index-ingestion-kafka-service \
            -am \
            -Dquarkus.container-image.build=true \
            -Dquarkus.container-image.tag=999-SNAPSHOT)

        kind load docker-image kubesmarts/data-index-ingestion-kafka-service:999-SNAPSHOT --name "${CLUSTER_NAME}"
    fi

    log_success "Images built and loaded for: ${BUILD_MODE}"

    # 4. Display cluster info
    log_step "Cluster Information"

    echo ""
    log_info "Cluster name: ${CLUSTER_NAME}"
    log_info "Kubectl context:"
    kubectl config current-context

    echo ""
    log_info "Loaded images:"
    docker exec "${CLUSTER_NAME}-control-plane" crictl images | grep -E "(kubesmarts|REPOSITORY)" || true

    echo ""
    log_success "✓ Setup complete! Ready to run MODE-specific tests."
    echo ""
    log_info "Next steps:"
    echo "  - MODE 1: bash scripts/e2e/full-test-mode1.sh"
    echo "  - MODE 2: bash scripts/e2e/full-test-mode2.sh"
    echo "  - MODE 3: bash scripts/e2e/full-test-mode3.sh"
    echo ""
}

# Run main function
main "$@"
