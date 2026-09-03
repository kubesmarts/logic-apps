#!/usr/bin/env bash
#
# Quick Vector MODE 2 Test
#
# Tests Vector deployment by replacing FluentBit in existing MODE 2 environment
#
# Prerequisites:
#   - Existing MODE 2 environment (run test-mode2-e2e.sh first or at least through step 5)
#   - Elasticsearch running
#   - Data Index deployed
#
# Usage:
#   ./test-vector-mode2.sh

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

log_step "Vector MODE 2 Quick Test"
echo ""

# Check prerequisites
log_info "Checking prerequisites..."

if ! kubectl get namespace elasticsearch &>/dev/null; then
    log_error "Elasticsearch namespace not found. Run test-mode2-e2e.sh first."
    exit 1
fi

if ! kubectl get deployment -n data-index data-index-service &>/dev/null; then
    log_error "Data Index not deployed. Run test-mode2-e2e.sh first."
    exit 1
fi

log_info "✓ Prerequisites met"
echo ""

# Step 1: Remove FluentBit if it exists
log_step "Removing FluentBit (if exists)..."
kubectl delete daemonset -n logging workflows-fluent-bit-mode2 --ignore-not-found=true
kubectl delete configmap -n logging workflows-fluent-bit-mode2-config --ignore-not-found=true
log_info "✓ FluentBit removed"
echo ""

# Step 2: Deploy Vector
log_step "Deploying Vector..."
"${SCRIPT_DIR}/deploy-vector-mode2.sh"
echo ""

# Step 3: Wait for Vector pods
log_step "Waiting for Vector pods..."
kubectl wait --namespace logging \
    --for=condition=ready pod \
    --selector=app=workflows-vector-mode2 \
    --timeout=60s
log_info "✓ Vector ready"
echo ""

# Step 4: Check Vector logs
log_step "Checking Vector logs..."
POD=$(kubectl get pod -n logging -l app=workflows-vector-mode2 -o jsonpath='{.items[0].metadata.name}')
log_info "Vector pod: ${POD}"
echo ""
log_info "Recent logs:"
kubectl logs -n logging "${POD}" --tail=20
echo ""

# Step 5: Deploy workflow app
log_step "Deploying workflow test app..."
if kubectl get deployment -n workflows workflow-test-app &>/dev/null; then
    log_info "Workflow app already deployed, restarting..."
    kubectl rollout restart deployment/workflow-test-app -n workflows
else
    "${SCRIPT_DIR}/deploy-workflow-app.sh"
fi

kubectl wait --namespace workflows \
    --for=condition=available deployment/workflow-test-app \
    --timeout=120s
log_info "✓ Workflow app ready"
echo ""

# Step 6: Wait for events
log_step "Waiting for workflow events in Elasticsearch..."
sleep 10

for i in {1..30}; do
    COUNT=$(curl -s -X GET "http://localhost:30920/workflow-events-*/_count" | grep -o '"count":[0-9]*' | cut -d':' -f2 || echo "0")

    if [ "${COUNT}" -gt "0" ]; then
        log_info "✓ Found ${COUNT} events in workflow-events indices"
        break
    fi

    if [ $i -eq 30 ]; then
        log_error "No events found after 30 attempts"
        log_info "Debugging information:"
        echo ""
        log_info "Vector logs:"
        kubectl logs -n logging -l app=workflows-vector-mode2 --tail=50
        echo ""
        log_info "Workflow app logs:"
        kubectl logs -n workflows -l app=workflow-test-app --tail=50
        exit 1
    fi

    log_info "Attempt $i/30: No events yet, waiting..."
    sleep 2
done
echo ""

# Step 7: Check normalized data
log_step "Waiting for ES Transform to normalize data..."
sleep 5

for i in {1..20}; do
    NORMALIZED=$(curl -s -X GET "http://localhost:30920/workflow-instances/_count" | grep -o '"count":[0-9]*' | cut -d':' -f2 || echo "0")

    if [ "${NORMALIZED}" -gt "0" ]; then
        log_info "✓ Found ${NORMALIZED} normalized workflow instances"
        break
    fi

    if [ $i -eq 20 ]; then
        log_warn "No normalized data yet (transforms may need more time)"
    fi

    log_info "Attempt $i/20: Waiting for transform..."
    sleep 2
done
echo ""

# Step 8: Test GraphQL API
log_step "Testing GraphQL API..."
RESPONSE=$(curl -s -X POST http://localhost:30080/graphql \
    -H "Content-Type: application/json" \
    -d '{
        "query": "query { getWorkflowInstances { id name status } }"
    }')

if echo "${RESPONSE}" | grep -q '"id"'; then
    log_info "✓ GraphQL API working"
    echo "${RESPONSE}" | jq '.' 2>/dev/null || echo "${RESPONSE}"
else
    log_error "GraphQL API returned no data"
    echo "${RESPONSE}"
fi
echo ""

# Summary
log_step "Test Summary"
echo ""
log_info "Vector DaemonSet: $(kubectl get daemonset -n logging workflows-vector-mode2 -o jsonpath='{.status.numberReady}')/$(kubectl get daemonset -n logging workflows-vector-mode2 -o jsonpath='{.status.desiredNumberScheduled}') pods ready"
log_info "Raw events: $(curl -s -X GET "http://localhost:30920/workflow-events-*/_count" | grep -o '"count":[0-9]*' | cut -d':' -f2)"
log_info "Normalized: $(curl -s -X GET "http://localhost:30920/workflow-instances/_count" | grep -o '"count":[0-9]*' | cut -d':' -f2)"
echo ""
log_info "Vector metrics available at:"
log_info "  kubectl port-forward -n logging daemonset/workflows-vector-mode2 9598:9598"
log_info "  curl http://localhost:9598/metrics"
echo ""
log_info "✓ Vector MODE 2 test complete!"
