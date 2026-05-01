#!/usr/bin/env bash
# ============================================================================
# Validate FluentBit Configuration
# ============================================================================
#
# Purpose: Validate FluentBit configuration syntax using dry-run mode
#
# Usage:
#   ./validate.sh
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

step "Validating FluentBit configuration files..."

# Check if files exist
if [ ! -f "$SCRIPT_DIR/fluent-bit.conf" ]; then
    error "fluent-bit.conf not found"
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/parsers.conf" ]; then
    error "parsers.conf not found"
    exit 1
fi

info "✓ Configuration files exist"

# Validate FluentBit syntax using Docker
step "Validating syntax with FluentBit dry-run..."

# Check if Docker is available
if ! command -v docker &> /dev/null; then
    warn "Docker not available - skipping syntax validation"
    warn "Install Docker to validate FluentBit configuration syntax"
    exit 0
fi

# Create temporary directory with configs
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT

cp "$SCRIPT_DIR/fluent-bit.conf" "$TMP_DIR/"
cp "$SCRIPT_DIR/parsers.conf" "$TMP_DIR/"

# Set dummy environment variables for validation
cat > "$TMP_DIR/env.conf" <<EOF
# Dummy environment variables for validation
@SET WORKFLOW_NAMESPACE=workflows
@SET ELASTICSEARCH_HOST=localhost
@SET ELASTICSEARCH_PORT=9200
@SET ELASTICSEARCH_TLS=Off
@SET ELASTICSEARCH_TLS_VERIFY=Off
EOF

# Prepend env vars to main config
cat "$TMP_DIR/env.conf" "$SCRIPT_DIR/fluent-bit.conf" > "$TMP_DIR/fluent-bit-with-env.conf"

# Run FluentBit in dry-run mode
if docker run --rm \
    -v "$TMP_DIR:/fluent-bit/etc" \
    fluent/fluent-bit:3.0 \
    /fluent-bit/bin/fluent-bit \
    -c /fluent-bit/etc/fluent-bit-with-env.conf \
    --dry-run \
    2>&1 | tee /tmp/fluent-bit-validation.log; then

    # Check for errors in output
    if grep -qi "error" /tmp/fluent-bit-validation.log; then
        error "FluentBit configuration has errors:"
        grep -i "error" /tmp/fluent-bit-validation.log
        exit 1
    fi

    info "✓ FluentBit configuration syntax is valid"
else
    error "FluentBit validation failed"
    exit 1
fi

# Validate Kubernetes manifests
step "Validating Kubernetes manifests..."

if command -v kubectl &> /dev/null; then
    # Validate ConfigMap
    if kubectl apply --dry-run=client -f "$SCRIPT_DIR/kubernetes/configmap.yaml" > /dev/null 2>&1; then
        info "✓ ConfigMap is valid"
    else
        error "ConfigMap validation failed"
        exit 1
    fi

    # Validate DaemonSet
    if kubectl apply --dry-run=client -f "$SCRIPT_DIR/kubernetes/daemonset.yaml" > /dev/null 2>&1; then
        info "✓ DaemonSet is valid"
    else
        error "DaemonSet validation failed"
        exit 1
    fi
else
    warn "kubectl not available - skipping Kubernetes manifest validation"
fi

echo ""
info "✓ All validations passed!"
info ""
info "Configuration is ready for deployment:"
info "  ./deploy.sh"
