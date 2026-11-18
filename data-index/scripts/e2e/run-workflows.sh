#!/usr/bin/env bash

# ============================================================================
# Run the workflow-test-app workflows to generate real lifecycle events
# ============================================================================
#
# Usage: run-workflows.sh <count>
#
# Runs simple-set, hello-world and failing-workflow <count> times each.
# Set WORKFLOW_URL to target another workflow-test-app (default: KIND NodePort).
#
# ============================================================================

set -euo pipefail

readonly BLUE='\033[0;34m'
readonly GREEN='\033[0;32m'
readonly NC='\033[0m'

readonly COUNT="${1:-1}"
readonly WORKFLOW_URL="${WORKFLOW_URL:-http://localhost:30082}"

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $*"; }

run() {
    local workflow="$1" input="$2"
    local status
    status=$(curl -s -o /dev/null -w '%{http_code}' -X POST "${WORKFLOW_URL}/test-workflows/${workflow}" \
        -H 'Content-Type: application/json' -d "${input}")
    log_info "  ${workflow} → HTTP ${status}"
}

log_info "Running each test workflow ${COUNT} time(s) against ${WORKFLOW_URL}"
for i in $(seq 1 "${COUNT}"); do
    input="{\"message\": \"run-${i}\"}"
    run simple-set "${input}"
    run hello-world "${input}"
    # Fails on purpose: expect an HTTP error and a FAULTED workflow.
    run failing-workflow "${input}"
done
log_success "Triggered $((COUNT * 3)) workflow(s)"
