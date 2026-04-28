#!/usr/bin/env bash
# ============================================================================
# FluentBit Deployment Script
# ============================================================================
#
# Purpose: Deploy FluentBit DaemonSet to Kubernetes cluster
#
# Usage:
#   ./deploy-fluentbit.sh <mode>
#
# Example:
#   ./deploy-fluentbit.sh mode1-postgresql-triggers
#
# ============================================================================

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Helper functions
info() { echo -e "${GREEN}[INFO]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*" >&2; }
step() { echo -e "${BLUE}[STEP]${NC} $*"; }

# Validate arguments
if [ $# -lt 1 ]; then
    error "Usage: $0 <mode>"
    error ""
    error "Available modes:"
    error "  mode1-postgresql-triggers  - PostgreSQL mode (production ready)"
    error "  mode2-elasticsearch        - Elasticsearch mode (planned)"
    exit 1
fi

MODE="$1"
MODE_DIR="${MODE}"

# Validate mode directory exists
if [ ! -d "$MODE_DIR" ]; then
    error "Mode directory not found: $MODE_DIR"
    exit 1
fi

info "==========================================  "
info "Deploying FluentBit - ${MODE}"
info "=========================================="
info ""

# Create logging namespace if it doesn't exist
step "Creating logging namespace..."
kubectl create namespace logging --dry-run=client -o yaml | kubectl apply -f -

# Generate ConfigMap
step "Generating ConfigMap from configuration files..."
./generate-configmap.sh "$MODE_DIR" "${MODE_DIR}/kubernetes/configmap.yaml"

# Apply ConfigMap
step "Applying ConfigMap..."
kubectl apply -f "${MODE_DIR}/kubernetes/configmap.yaml"

# Apply DaemonSet
step "Applying DaemonSet..."
if [ ! -f "${MODE_DIR}/kubernetes/daemonset.yaml" ]; then
    error "DaemonSet YAML not found: ${MODE_DIR}/kubernetes/daemonset.yaml"
    exit 1
fi
kubectl apply -f "${MODE_DIR}/kubernetes/daemonset.yaml"

# Wait for DaemonSet to be ready
step "Waiting for FluentBit DaemonSet to be ready..."
kubectl rollout status daemonset/fluent-bit -n logging --timeout=90s

# Get pod status
info ""
info "=========================================="
info "FluentBit Deployment Complete!"
info "=========================================="
info ""
info "Pod status:"
kubectl get pods -n logging -l app=fluent-bit

info ""
info "View logs:"
echo "  kubectl logs -n logging -l app=fluent-bit -f"

info ""
info "Check FluentBit metrics:"
FLUENT_BIT_POD=$(kubectl get pods -n logging -l app=fluent-bit -o jsonpath='{.items[0].metadata.name}')
echo "  kubectl port-forward -n logging ${FLUENT_BIT_POD} 2020:2020"
echo "  curl http://localhost:2020/api/v1/metrics"

info ""
info "✓ Deployment complete"
