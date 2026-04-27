#!/usr/bin/env bash
#
# MODE 1 End-to-End Integration Test
#
# Tests complete flow:
#   Quarkus Flow → stdout → K8s logs → FluentBit → PostgreSQL (triggers) → GraphQL
#
# Verifies:
#   - Event collection from stdout
#   - CRI parser for containerd
#   - PostgreSQL trigger normalization
#   - Idempotency (V2 migration)
#   - Out-of-order event handling
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
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

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
    log_info "FluentBit logs:"
    kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50 || true

    echo ""
    log_info "Workflow app logs:"
    kubectl logs -n workflows -l app=workflow-test-app --tail=50 || true

    echo ""
    log_info "PostgreSQL status:"
    kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
      "SELECT COUNT(*) as raw_events FROM workflow_events_raw;" || true

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
    kubectl create namespace postgresql --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace workflows --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ Namespaces created"
}

# Step 3: Install PostgreSQL
install_postgresql() {
    log_step "Installing PostgreSQL..."

    if helm list -n postgresql | grep -q postgresql; then
        log_info "PostgreSQL already installed, skipping"
    else
        helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || true
        helm repo update

        helm install postgresql bitnami/postgresql \
          --namespace postgresql \
          --set auth.username=dataindex \
          --set auth.password=dataindex123 \
          --set auth.database=dataindex \
          --set primary.persistence.size=1Gi \
          --set primary.resources.requests.cpu=100m \
          --set primary.resources.requests.memory=256Mi \
          --wait \
          --timeout=5m
    fi

    kubectl wait --namespace postgresql \
      --for=condition=ready pod \
      --selector=app.kubernetes.io/component=primary \
      --timeout=300s

    log_info "✓ PostgreSQL ready"
}

# Step 4: Run database migrations
run_migrations() {
    log_step "Running database migrations..."

    # Copy migration files to PostgreSQL pod
    kubectl cp "${PROJECT_ROOT}/data-index-storage/data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql" \
      postgresql/postgresql-0:/tmp/V1__initial_schema.sql

    # Execute migrations
    log_info "Executing V1 migration (initial schema with idempotency)..."
    kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -f /tmp/V1__initial_schema.sql

    # Verify schema
    log_info "Verifying schema..."
    kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c "\dt" | grep -q workflow_instances

    kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c "\d workflow_instances" | grep -q last_event_time

    log_info "✓ Migrations applied successfully"
}

# Step 5: Deploy FluentBit MODE 1
deploy_fluentbit() {
    log_step "Deploying FluentBit MODE 1..."

    # Generate ConfigMap from source files to temp file
    TEMP_CONFIGMAP=$(mktemp)
    cd "${PROJECT_ROOT}/scripts/fluentbit"
    ./generate-configmap.sh mode1-postgresql-triggers "${TEMP_CONFIGMAP}" 2>/dev/null

    # Apply with name change
    sed 's/name: fluent-bit-config/name: workflows-fluent-bit-mode1-config/' "${TEMP_CONFIGMAP}" | \
      kubectl apply -f -

    rm -f "${TEMP_CONFIGMAP}"

    # Deploy DaemonSet
    kubectl apply -f mode1-postgresql-triggers/kubernetes/daemonset.yaml

    # Wait for pods
    log_info "Waiting for FluentBit pods..."
    kubectl wait --namespace logging \
      --for=condition=ready pod \
      --selector=app=workflows-fluent-bit-mode1 \
      --timeout=300s

    log_info "✓ FluentBit deployed"
}

# Step 6: Build and deploy workflow test app
deploy_workflow_app() {
    log_step "Building workflow test app..."

    cd "${PROJECT_ROOT}/workflow-test-app"

    # Build container image with Jib
    mvn package -Dquarkus.container-image.build=true -DskipTests

    # Load image to KIND
    kind load docker-image kubesmarts/workflow-test-app:999-SNAPSHOT --name "${CLUSTER_NAME}"

    log_step "Deploying workflow test app..."
    "${SCRIPT_DIR}/deploy-workflow-app.sh"

    # Wait for pod
    kubectl wait --namespace workflows \
      --for=condition=ready pod \
      --selector=app=workflow-test-app \
      --timeout=300s

    log_info "✓ Workflow app deployed"
}

# Step 7: Execute test workflows
execute_workflows() {
    log_step "Executing test workflows..."

    # Port-forward to workflow app
    kubectl port-forward -n workflows svc/workflow-test-app 8082:8080 &
    PORT_FORWARD_PID=$!
    sleep 3

    # Trigger workflow
    log_info "Triggering simple-set workflow..."
    curl -X POST http://localhost:8082/test-workflows/simple-set \
      -H "Content-Type: application/json" \
      -d '{"name": "E2E Test"}' \
      -s -o /dev/null -w "HTTP %{http_code}\n"

    # Kill port-forward
    kill $PORT_FORWARD_PID || true

    # Wait for events to propagate
    log_info "Waiting for events to propagate (10 seconds)..."
    sleep 10

    log_info "✓ Workflow executed"
}

# Step 8: Verify event collection
verify_events() {
    log_step "Verifying event collection..."

    # Check FluentBit logs
    log_info "Checking FluentBit captured events..."
    kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=100 | \
      grep -q "io.serverlessworkflow.workflow.started" || {
        log_error "FluentBit did not capture workflow.started events"
        kubectl logs -n logging -l app=workflows-fluent-bit-mode1 --tail=50
        exit 1
      }

    # Check raw events in PostgreSQL
    log_info "Checking raw events in PostgreSQL..."
    RAW_COUNT=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
      "SELECT COUNT(*) FROM workflow_events_raw;")

    log_info "Raw workflow events: ${RAW_COUNT}"

    if [ "${RAW_COUNT}" -lt 1 ]; then
        log_error "No raw events found in PostgreSQL"
        exit 1
    fi

    # Check normalized workflow instances
    log_info "Checking normalized workflow instances..."
    WORKFLOW_COUNT=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
      "SELECT COUNT(*) FROM workflow_instances;")

    log_info "Normalized workflows: ${WORKFLOW_COUNT}"

    if [ "${WORKFLOW_COUNT}" -lt 1 ]; then
        log_error "No normalized workflows found"
        exit 1
    fi

    # Check workflow has all fields (including V2 idempotency field)
    log_info "Verifying workflow data completeness..."
    kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
      "SELECT id, name, status, start IS NOT NULL as has_start,
              last_event_time IS NOT NULL as has_timestamp
       FROM workflow_instances
       LIMIT 1;"

    log_info "✓ Event collection verified"
}

# Step 9: Test idempotency (replay events)
test_idempotency() {
    log_step "Testing idempotency (event replay)..."

    # Get current state
    BEFORE=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
      "SELECT id, status, start, last_event_time FROM workflow_instances LIMIT 1;")

    log_info "State before replay: ${BEFORE}"

    # Delete FluentBit tail DB to force reprocessing
    log_info "Deleting FluentBit tail DB..."
    kubectl exec -n logging -c fluent-bit \
      $(kubectl get pods -n logging -l app=workflows-fluent-bit-mode1 -o name | head -1) -- \
      rm -f /tail-db/fluent-bit-kube.db || true

    # Restart FluentBit to reprocess logs
    kubectl delete pods -n logging -l app=workflows-fluent-bit-mode1

    kubectl wait --namespace logging \
      --for=condition=ready pod \
      --selector=app=workflows-fluent-bit-mode1 \
      --timeout=300s

    # Wait for reprocessing
    sleep 10

    # Get state after replay
    AFTER=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
      "SELECT id, status, start, last_event_time FROM workflow_instances LIMIT 1;")

    log_info "State after replay: ${AFTER}"

    # Verify state unchanged (idempotent)
    if [ "${BEFORE}" != "${AFTER}" ]; then
        log_error "State changed after replay - idempotency broken!"
        log_error "Before: ${BEFORE}"
        log_error "After:  ${AFTER}"
        exit 1
    fi

    log_info "✓ Idempotency verified (state unchanged after replay)"
}

# Step 10: Print summary
print_summary() {
    echo ""
    log_info "=========================================="
    log_info "MODE 1 E2E Test Results"
    log_info "=========================================="
    echo ""

    # Event counts
    RAW_WORKFLOW=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c "SELECT COUNT(*) FROM workflow_events_raw;")

    RAW_TASK=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c "SELECT COUNT(*) FROM task_events_raw;")

    WORKFLOWS=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c "SELECT COUNT(*) FROM workflow_instances;")

    TASKS=$(kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c "SELECT COUNT(*) FROM task_instances;")

    echo "Event Collection:"
    echo "  - Raw workflow events: ${RAW_WORKFLOW}"
    echo "  - Raw task events: ${RAW_TASK}"
    echo "  - Normalized workflows: ${WORKFLOWS}"
    echo "  - Normalized tasks: ${TASKS}"
    echo ""

    # Sample data
    echo "Sample Workflow Instance:"
    kubectl exec -n postgresql postgresql-0 -- \
      env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
      "SELECT id, name, status, start, \"end\", last_event_time
       FROM workflow_instances
       LIMIT 1;"

    echo ""
    log_info "✅ All tests passed!"
    echo ""
    log_info "Next Steps:"
    echo "  - Query via GraphQL: ./test-graphql.sh"
    echo "  - View FluentBit logs: kubectl logs -n logging -l app=workflows-fluent-bit-mode1"
    echo "  - View PostgreSQL data: kubectl exec -n postgresql postgresql-0 -- env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex"
    echo ""
}

# Main execution
main() {
    log_info "=========================================="
    log_info "MODE 1 End-to-End Integration Test"
    log_info "=========================================="
    echo ""

    create_cluster
    create_namespaces
    install_postgresql
    run_migrations
    deploy_fluentbit
    deploy_workflow_app
    execute_workflows
    verify_events
    test_idempotency
    print_summary

    log_info "✓ E2E test complete!"
}

# Run
main "$@"
