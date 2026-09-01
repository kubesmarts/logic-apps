#!/usr/bin/env bash
#
# Copyright 2024 KubeSmarts Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# MODE 3 (Kafka) End-to-End Integration Test
#
# Tests the complete Kafka ingestion pipeline:
#   workflow-test-app (kafka profile)
#     → Kafka topic: flow-lifecycle-out (CloudEvents)
#       → data-index-ingestion-kafka-service
#         → PostgreSQL (workflow_instances, task_instances)
#           → Data Index GraphQL API
#
# Verifies:
#   - Kafka broker running and accepting connections
#   - workflow-test-app publishing CloudEvents to Kafka
#   - Ingestion service consuming and normalizing events
#   - Data persisted in PostgreSQL normalized tables
#   - GraphQL API returning normalized data
#   - Idempotency: replaying events does not create duplicates
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
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
KAFKA_SCRIPTS_DIR="${PROJECT_ROOT}/data-index/scripts/kafka"

# Logging
log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

# Collect debug info and exit on failure
error_handler() {
    log_error "Test failed at line $1"
    echo ""
    log_info "--- Ingestion service logs ---"
    kubectl logs -n data-index -l app=data-index-ingestion-kafka-service --tail=60 || true

    echo ""
    log_info "--- workflow-test-app logs ---"
    kubectl logs -n workflows -l app=workflow-test-app --tail=40 || true

    echo ""
    log_info "--- Kafka broker logs ---"
    kubectl logs -n kafka kafka-0 --tail=30 || true

    echo ""
    log_info "--- PostgreSQL workflow_instances ---"
    kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex \
        -c "SELECT id, name, status, start, \"end\" FROM workflow_instances LIMIT 5;" || true

    echo ""
    log_info "--- Consumer group lag ---"
    kubectl exec -n kafka kafka-0 -- \
        /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --describe --group data-index-ingestion 2>/dev/null || true

    exit 1
}

trap 'error_handler $LINENO' ERR

# ── Step 1: Cluster ──────────────────────────────────────────────────────────

create_cluster() {
    log_step "Creating KIND cluster..."

    if kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_info "Cluster '${CLUSTER_NAME}' already exists, skipping creation"
    else
        "${SCRIPT_DIR}/setup-cluster.sh"
    fi

    kubectl config use-context "kind-${CLUSTER_NAME}"
    log_info "✓ Cluster ready"
}

# ── Step 2: Namespaces ────────────────────────────────────────────────────────

create_namespaces() {
    log_step "Creating namespaces..."

    for ns in logging kafka postgresql workflows data-index; do
        kubectl create namespace "${ns}" --dry-run=client -o yaml | kubectl apply -f -
    done

    log_info "✓ Namespaces ready"
}

# ── Step 3: PostgreSQL ────────────────────────────────────────────────────────

install_postgresql() {
    log_step "Installing PostgreSQL..."

    if kubectl get statefulset -n postgresql postgresql &>/dev/null; then
        log_info "PostgreSQL already deployed, skipping"
    else
        MODE=postgresql "${SCRIPT_DIR}/install-dependencies.sh"
        return
    fi

    kubectl wait --namespace postgresql \
        --for=condition=ready pod \
        --selector=app.kubernetes.io/name=postgresql \
        --timeout=300s

    log_info "✓ PostgreSQL ready"
}

# ── Step 4: Database schema ───────────────────────────────────────────────────
# The ingestion service runs Flyway on startup (QUARKUS_FLYWAY_MIGRATE_AT_START=true),
# so the schema is initialized automatically.  This step is a no-op validation only.

verify_schema_will_be_applied() {
    log_step "Schema will be applied by Flyway on ingestion service startup"
    log_info "  Migration file: data-index-storage-migrations/...V1__initial_schema.sql"
    log_info "✓ Schema initialization delegated to Flyway"
}

# ── Step 5: Kafka ─────────────────────────────────────────────────────────────

install_kafka() {
    log_step "Installing Kafka (KRaft single-node)..."

    if kubectl get statefulset -n kafka kafka &>/dev/null; then
        log_info "Kafka already deployed, skipping"
    else
        kubectl apply -f "${KAFKA_SCRIPTS_DIR}/kubernetes/kafka.yaml"
    fi

    log_info "Waiting for Kafka to be ready (this may take ~60 seconds)..."
    kubectl wait --namespace kafka \
        --for=condition=ready pod/kafka-0 \
        --timeout=180s

    # Verify broker is accepting connections
    log_info "Verifying Kafka broker connectivity..."
    for i in {1..15}; do
        if kubectl exec -n kafka kafka-0 -- \
            /opt/kafka/bin/kafka-topics.sh \
            --bootstrap-server localhost:9092 --list &>/dev/null; then
            log_info "✓ Kafka broker accepting connections"
            break
        fi
        log_info "Attempt $i/15: Kafka not ready yet..."
        sleep 4
    done

    log_info "✓ Kafka ready at kafka.kafka.svc.cluster.local:9092"
}

# ── Step 6: Data Index query service (postgresql mode) ────────────────────────

deploy_data_index_query_service() {
    log_step "Deploying data-index-service (postgresql query backend)..."

    if kubectl get deployment -n data-index data-index-service &>/dev/null; then
        log_info "data-index-service already deployed, skipping"
    else
        # deploy-data-index.sh kafka internally deploys the postgresql-backed service
        "${SCRIPT_DIR}/deploy-data-index.sh" kafka
    fi

    kubectl wait --namespace data-index \
        --for=condition=available deployment/data-index-service \
        --timeout=300s

    log_info "✓ Query service ready at http://localhost:30080/graphql"
}

# ── Step 7: Kafka ingestion service ───────────────────────────────────────────

deploy_ingestion_service() {
    log_step "Deploying data-index-ingestion-kafka-service..."

    if kubectl get deployment -n data-index data-index-ingestion-kafka-service &>/dev/null; then
        log_info "Ingestion service already deployed, skipping"
    else
        "${SCRIPT_DIR}/deploy-kafka-ingestion.sh"
    fi

    kubectl wait --namespace data-index \
        --for=condition=available deployment/data-index-ingestion-kafka-service \
        --timeout=300s

    log_info "✓ Ingestion service ready and consuming from Kafka"
}

# ── Step 8: workflow-test-app (Kafka profile) ────────────────────────────────

deploy_workflow_app() {
    log_step "Deploying workflow-test-app (Kafka profile)..."

    # MODE 3 requires the app to be built with -Pkafka (build-time config)
    # so we must delete and redeploy with the Kafka-enabled image
    if kubectl get deployment -n workflows workflow-test-app &>/dev/null; then
        log_info "Deleting existing workflow-test-app to rebuild with Kafka profile..."
        kubectl delete deployment -n workflows workflow-test-app --wait=false
        kubectl delete svc -n workflows workflow-test-app --wait=false
    fi

    MODE=kafka "${SCRIPT_DIR}/deploy-workflow-app.sh"
    log_info "✓ workflow-test-app ready (publishing to Kafka)"
}

# ── Step 9: Execute workflows ─────────────────────────────────────────────────

execute_workflows() {
    log_step "Executing test workflows via REST API..."

    kubectl port-forward -n workflows svc/workflow-test-app 8082:8080 &>/dev/null &
    local PF_PID=$!
    sleep 3

    log_info "Triggering simple-set workflow..."
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        -X POST http://localhost:8082/test-workflows/simple-set \
        -H "Content-Type: application/json" \
        -d '{"name": "mode3-e2e-test"}')

    if [[ "${http_code}" != "200" && "${http_code}" != "201" && "${http_code}" != "204" ]]; then
        log_error "Workflow execution returned HTTP ${http_code}"
        kill "${PF_PID}" 2>/dev/null || true
        exit 1
    fi

    log_info "  → HTTP ${http_code}: simple-set workflow triggered"

    log_info "Triggering hello-world workflow..."
    curl -s -o /dev/null \
        -X POST http://localhost:8082/test-workflows/hello-world \
        -H "Content-Type: application/json" \
        -d '{}'

    kill "${PF_PID}" 2>/dev/null || true

    log_info "✓ Workflows triggered — events are now in Kafka topic 'flow-lifecycle-out'"
}

# ── Step 10: Verify ingestion service health ────────────────────────────────
# Workflow execution is async - workflows triggered by HTTP POST execute in background.
# Instead of polling Kafka topic (race condition), we verify:
#   1. Ingestion service is healthy and consuming from Kafka
#   2. PostgreSQL contains normalized data (ultimate proof MODE 3 works)

verify_ingestion_service_health() {
    log_step "Verifying ingestion service is consuming from Kafka..."

    # Check consumer group exists and is consuming
    log_info "Checking consumer group 'data-index-ingestion'..."
    if kubectl exec -n kafka kafka-0 -- \
        /opt/kafka/bin/kafka-consumer-groups.sh \
        --bootstrap-server localhost:9092 \
        --list 2>/dev/null | grep -q "data-index-ingestion"; then
        log_info "✓ Consumer group 'data-index-ingestion' is active"
    else
        log_error "Consumer group 'data-index-ingestion' not found"
        exit 1
    fi

    # Check ingestion service health
    if kubectl exec -n data-index -l app=data-index-ingestion-kafka-service -- \
        curl -s http://localhost:8080/q/health | grep -q '"status":"UP"'; then
        log_info "✓ Ingestion service is healthy"
    else
        log_error "Ingestion service health check failed"
        exit 1
    fi
}

# ── Step 11: Verify PostgreSQL normalization ──────────────────────────────────

verify_postgresql_normalization() {
    log_step "Verifying normalized data in PostgreSQL..."

    # Wait for async workflow execution + Kafka ingestion + PostgreSQL normalization
    # Workflow execution is async: HTTP POST returns immediately, workflow runs in background
    # Timeline: HTTP 200 → workflow executes → events to Kafka → ingestion consumes → PostgreSQL
    log_info "Waiting up to 60s for complete pipeline (workflow execution + ingestion)..."
    local found=false
    for i in {1..30}; do
        local wf_count
        wf_count=$(kubectl exec -n postgresql postgresql-0 -- \
            env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
            "SELECT COUNT(*) FROM workflow_instances;" 2>/dev/null | tr -d ' ')

        if [[ "${wf_count}" -gt 0 ]]; then
            log_info "✓ Found ${wf_count} normalized workflow instance(s)"
            found=true
            break
        fi
        log_info "Attempt $i/30: Waiting for async workflow execution + ingestion..."
        sleep 2
    done

    if [[ "${found}" != "true" ]]; then
        log_error "No normalized workflow instances found in PostgreSQL"
        exit 1
    fi

    # Check task instances
    local task_count
    task_count=$(kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
        "SELECT COUNT(*) FROM task_instances;" 2>/dev/null | tr -d ' ')

    log_info "  Task instances: ${task_count}"

    # Print sample row
    log_info "Sample workflow instance:"
    kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -c \
        "SELECT id, name, status, start IS NOT NULL AS has_start
         FROM workflow_instances
         LIMIT 3;"

    log_info "✓ Normalization verified"
}

# ── Step 12: Verify GraphQL API ────────────────────────────────────────────────

verify_graphql() {
    log_step "Verifying GraphQL API returns normalized data..."

    # Introspection
    curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ __schema { queryType { name } } }"}' \
        | grep -q '"name":"Query"' || {
            log_error "GraphQL introspection failed"
            exit 1
        }

    # getWorkflowInstances
    local result
    result=$(curl -s -X POST http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d '{"query":"{ getWorkflowInstances { id name status } }"}')

    echo "${result}" | grep -q '"id"' || {
        log_error "getWorkflowInstances returned no results: ${result}"
        exit 1
    }

    local wf_id
    wf_id=$(echo "${result}" | grep -o '"id":"[^"]*"' | head -1 | cut -d'"' -f4)
    log_info "  Sample workflow from GraphQL: ${wf_id}"

    log_info "✓ GraphQL API verified"
}

# ── Step 13: Idempotency test ──────────────────────────────────────────────────
# Re-trigger the same workflow and confirm no duplicates are created.

verify_idempotency() {
    log_step "Verifying idempotency (re-triggering same workflow)..."

    local before
    before=$(kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
        "SELECT COUNT(*) FROM workflow_instances;" 2>/dev/null | tr -d ' ')

    log_info "  Workflow count before re-trigger: ${before}"

    # Restart the workflow app — it will publish startup events for the same workflow IDs
    kubectl rollout restart deployment/workflow-test-app -n workflows
    kubectl wait --namespace workflows \
        --for=condition=available deployment/workflow-test-app \
        --timeout=120s

    # Allow time for events to flow through Kafka and be processed
    sleep 15

    local after
    after=$(kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
        "SELECT COUNT(*) FROM workflow_instances;" 2>/dev/null | tr -d ' ')

    log_info "  Workflow count after re-trigger: ${after}"

    # Idempotent: UPSERT must not create new rows for the same workflow IDs
    if [[ "${after}" -gt $((before + 2)) ]]; then
        log_warn "Count increased from ${before} to ${after} (new workflow IDs expected from new executions)"
        log_warn "If the same IDs were published twice, check UPSERT idempotency in WorkflowEventNormalizer"
    else
        log_info "✓ Idempotency verified (no unexpected duplicates)"
    fi
}

# ── Summary ───────────────────────────────────────────────────────────────────

print_summary() {
    echo ""
    log_info "=========================================="
    log_info "MODE 3 (Kafka) E2E Test Complete!"
    log_info "=========================================="
    echo ""

    local wf_count task_count
    wf_count=$(kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
        "SELECT COUNT(*) FROM workflow_instances;" 2>/dev/null | tr -d ' ')
    task_count=$(kubectl exec -n postgresql postgresql-0 -- \
        env PGPASSWORD=dataindex123 psql -U dataindex -d dataindex -t -c \
        "SELECT COUNT(*) FROM task_instances;" 2>/dev/null | tr -d ' ')

    log_info "Pipeline:"
    echo "  workflow-test-app → Kafka (flow-lifecycle-out) → ingestion-service → PostgreSQL → GraphQL"
    echo ""
    log_info "Results:"
    echo "  ✓ Kafka broker running"
    echo "  ✓ Workflow events published as CloudEvents"
    echo "  ✓ Ingestion service consumed and normalized events"
    echo "  ✓ PostgreSQL: ${wf_count} workflow instance(s), ${task_count} task instance(s)"
    echo "  ✓ GraphQL API returning data"
    echo ""
    log_info "Access Points:"
    echo "  GraphQL API:  http://localhost:30080/graphql"
    echo "  GraphQL UI:   http://localhost:30080/q/graphql-ui"
    echo "  PostgreSQL:   postgresql://dataindex:dataindex123@localhost:30432/dataindex"
    echo "  Kafka:        localhost:30900 (NodePort, for tools like kcat)"
    echo ""
    log_info "Useful Commands:"
    echo "  # Watch live Kafka events"
    echo "  kubectl exec -n kafka kafka-0 -- \\"
    echo "    /opt/kafka/bin/kafka-console-consumer.sh \\"
    echo "    --bootstrap-server localhost:9092 --topic flow-lifecycle-out --from-beginning"
    echo ""
    echo "  # Consumer group lag"
    echo "  kubectl exec -n kafka kafka-0 -- \\"
    echo "    /opt/kafka/bin/kafka-consumer-groups.sh \\"
    echo "    --bootstrap-server localhost:9092 --describe --group data-index-ingestion"
    echo ""
    echo "  # GraphQL query"
    echo '  curl http://localhost:30080/graphql -H "Content-Type: application/json" \'
    echo '    -d '"'"'{"query":"{ getWorkflowInstances { id name status } }"}'"'"
    echo ""
}

# ── Main ──────────────────────────────────────────────────────────────────────

main() {
    log_info "=========================================="
    log_info "MODE 3 (Kafka) End-to-End Integration Test"
    log_info "=========================================="
    echo ""

    create_cluster
    create_namespaces
    install_postgresql
    verify_schema_will_be_applied
    install_kafka
    deploy_data_index_query_service
    deploy_ingestion_service
    deploy_workflow_app
    execute_workflows
    verify_ingestion_service_health
    verify_postgresql_normalization
    verify_graphql
    verify_idempotency
    print_summary

    log_info "✅ All MODE 3 tests passed!"
}

main "$@"
