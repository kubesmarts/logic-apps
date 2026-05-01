#!/usr/bin/env bash
# ============================================================================
# Deploy FluentBit MODE 2 (Elasticsearch)
# ============================================================================
#
# Purpose: Deploy FluentBit DaemonSet for Elasticsearch output
#
# Usage:
#   ./deploy.sh [regenerate|delete]
#
# Options:
#   regenerate - Regenerate ConfigMap from source files before deploying
#   delete     - Delete FluentBit resources
#   (none)     - Deploy with existing ConfigMap
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

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FLUENTBIT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Parse command
COMMAND="${1:-deploy}"

case "$COMMAND" in
    delete)
        info "Deleting FluentBit MODE 2 resources..."
        kubectl delete -f "$SCRIPT_DIR/kubernetes/daemonset.yaml" --ignore-not-found=true
        kubectl delete -f "$SCRIPT_DIR/kubernetes/configmap.yaml" --ignore-not-found=true
        info "✓ FluentBit MODE 2 deleted"
        ;;

    regenerate)
        step "Regenerating ConfigMap from source files..."
        cd "$FLUENTBIT_ROOT"
        ./generate-configmap.sh elasticsearch elasticsearch/kubernetes/configmap.yaml

        step "Applying ConfigMap..."
        kubectl apply -f "$SCRIPT_DIR/kubernetes/configmap.yaml"

        step "Applying DaemonSet..."
        kubectl apply -f "$SCRIPT_DIR/kubernetes/daemonset.yaml"

        info "✓ FluentBit MODE 2 deployed (ConfigMap regenerated)"
        ;;

    deploy)
        step "Creating logging namespace (if not exists)..."
        kubectl create namespace logging --dry-run=client -o yaml | kubectl apply -f -

        step "Applying ConfigMap..."
        kubectl apply -f "$SCRIPT_DIR/kubernetes/configmap.yaml"

        step "Applying DaemonSet..."
        kubectl apply -f "$SCRIPT_DIR/kubernetes/daemonset.yaml"

        info "✓ FluentBit MODE 2 deployed"
        ;;

    *)
        error "Unknown command: $COMMAND"
        error "Usage: $0 [regenerate|delete]"
        exit 1
        ;;
esac

# Show status
if [[ "$COMMAND" != "delete" ]]; then
    echo ""
    step "Checking deployment status..."
    kubectl get pods -n logging -l app=workflows-fluent-bit-mode2

    echo ""
    info "To view logs:"
    info "  kubectl logs -n logging -l app=workflows-fluent-bit-mode2 --tail=50 -f"
    echo ""
    info "To check Elasticsearch connectivity:"
    info "  kubectl logs -n logging -l app=workflows-fluent-bit-mode2 | grep -i elasticsearch"
fi
