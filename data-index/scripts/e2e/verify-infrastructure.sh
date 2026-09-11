#!/usr/bin/env bash
# ============================================================================
# Infrastructure Verification - Common checks for all modes
# ============================================================================

set -euo pipefail

# Colors
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly BLUE='\033[0;34m'
readonly YELLOW='\033[1;33m'
readonly NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[✓]${NC} $*"; }
log_error() { echo -e "${RED}[✗]${NC} $*"; }
log_warn() { echo -e "${YELLOW}[!]${NC} $*"; }

# Wait for condition with timeout (works on both Linux and macOS)
wait_for() {
    local timeout=$1
    shift
    local end_time=$((SECONDS + timeout))

    while [ $SECONDS -lt $end_time ]; do
        if "$@" 2>/dev/null; then
            return 0
        fi
        sleep 2
    done

    log_error "Timeout after ${timeout}s waiting for: $*"
    return 1
}

# ============================================================================
# MODE 1: PostgreSQL + Vector + Triggers
# ============================================================================

verify_mode1_infrastructure() {
    log_info "Verifying MODE 1 infrastructure..."

    # 0. Vector log collector running
    log_info "Checking Vector DaemonSet..."
    if kubectl rollout status daemonset/vector -n logging --timeout=60s > /dev/null 2>&1; then
        log_success "Vector is running"
    else
        log_error "Vector DaemonSet is not fully rolled out (missing/pending/unready pods on some node)"
        return 1
    fi

    # 1. Check Data Index GraphQL endpoint
    log_info "Checking Data Index GraphQL endpoint..."
    wait_for 120 curl -sf http://localhost:30080/q/health/ready
    log_success "Data Index service is ready"

    # 2. Check PostgreSQL is accepting connections
    log_info "Checking PostgreSQL connectivity..."
    kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -c "SELECT 1" > /dev/null
    log_success "PostgreSQL is accepting connections"

    # 3. Verify schema initialized (tables exist)
    log_info "Checking database schema..."
    TABLES=$(kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -t -c "
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = 'public'
        AND table_name IN ('workflow_instances', 'task_instances', 'workflow_events_raw', 'task_events_raw')
    ")
    if [ "$TABLES" -eq 4 ]; then
        log_success "Database schema initialized (4 tables found)"
    else
        log_error "Database schema incomplete (expected 4 tables, found $TABLES)"
        return 1
    fi

    # 4. Verify triggers exist
    log_info "Checking database triggers..."
    TRIGGERS=$(kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -t -c "
        SELECT COUNT(*) FROM information_schema.triggers
        WHERE event_object_table IN ('workflow_events_raw', 'task_events_raw')
    ")
    if [ "$TRIGGERS" -ge 2 ]; then
        log_success "Database triggers created ($TRIGGERS triggers found)"
    else
        log_warn "Database triggers may be missing (found $TRIGGERS triggers)"
    fi

    log_success "MODE 1 infrastructure verification complete"
}

# ============================================================================
# MODE 2: Elasticsearch + Vector + Transforms
# ============================================================================

verify_mode2_infrastructure() {
    log_info "Verifying MODE 2 infrastructure..."

    # 1. Check Data Index GraphQL endpoint
    log_info "Checking Data Index GraphQL endpoint..."
    wait_for 120 curl -sf http://localhost:30080/q/health/ready
    log_success "Data Index service is ready"

    # 2. Check Elasticsearch cluster health
    log_info "Checking Elasticsearch cluster health..."
    CLUSTER_STATUS=$(curl -s http://localhost:30920/_cluster/health | jq -r '.status')
    if [[ "$CLUSTER_STATUS" == "green" || "$CLUSTER_STATUS" == "yellow" ]]; then
        log_success "Elasticsearch cluster status: $CLUSTER_STATUS"
    else
        log_error "Elasticsearch cluster unhealthy: $CLUSTER_STATUS"
        return 1
    fi

    # 3. Check index templates exist (created during schema initialization)
    log_info "Checking Elasticsearch index templates..."
    TEMPLATES=$(curl -s http://localhost:30920/_index_template | jq -r '.index_templates[].name' | grep -c "workflow" || echo "0")
    if [ "$TEMPLATES" -ge 2 ]; then
        log_success "Index templates created ($TEMPLATES templates found)"
    else
        log_error "Index templates missing (expected at least 2, found $TEMPLATES)"
        return 1
    fi

    # 4. Check transforms are running
    log_info "Checking Elasticsearch transforms..."
    WF_TRANSFORM_STATE=$(curl -s http://localhost:30920/_transform/workflow-instances-transform/_stats | jq -r '.transforms[0].state')
    TASK_TRANSFORM_STATE=$(curl -s http://localhost:30920/_transform/task-executions-transform/_stats | jq -r '.transforms[0].state')

    if [[ "$WF_TRANSFORM_STATE" == "started" || "$WF_TRANSFORM_STATE" == "indexing" ]]; then
        log_success "workflow-instances-transform: $WF_TRANSFORM_STATE"
    else
        log_error "workflow-instances-transform not running: $WF_TRANSFORM_STATE"
        return 1
    fi

    if [[ "$TASK_TRANSFORM_STATE" == "started" || "$TASK_TRANSFORM_STATE" == "indexing" ]]; then
        log_success "task-executions-transform: $TASK_TRANSFORM_STATE"
    else
        log_error "task-executions-transform not running: $TASK_TRANSFORM_STATE"
        return 1
    fi

    log_success "MODE 2 infrastructure verification complete"
}

# ============================================================================
# MODE 3: Kafka + CloudEvents + Processors
# ============================================================================

verify_mode3_infrastructure() {
    log_info "Verifying MODE 3 infrastructure..."

    # 1. Check Data Index GraphQL endpoint
    log_info "Checking Data Index GraphQL endpoint..."
    wait_for 120 curl -sf http://localhost:30080/q/health/ready
    log_success "Data Index service is ready"

    # 2. Check Data Index Ingestion service
    log_info "Checking Data Index Ingestion service..."
    wait_for 120 curl -sf http://localhost:30081/q/health/ready
    log_success "Data Index Ingestion service is ready"

    # 3. Check PostgreSQL is accepting connections
    log_info "Checking PostgreSQL connectivity..."
    kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -c "SELECT 1" > /dev/null
    log_success "PostgreSQL is accepting connections"

    # 4. Verify schema initialized (tables exist)
    log_info "Checking database schema..."
    TABLES=$(kubectl exec -n postgresql postgresql-0 -- psql -U dataindex -d dataindex -t -c "
        SELECT COUNT(*) FROM information_schema.tables
        WHERE table_schema = 'public'
        AND table_name IN ('workflow_instances', 'task_instances')
    ")
    if [ "$TABLES" -eq 2 ]; then
        log_success "Database schema initialized (2 tables found)"
    else
        log_error "Database schema incomplete (expected 2 tables, found $TABLES)"
        return 1
    fi

    # 5. Check Kafka pod is running
    # Note: Kafka connectivity is already verified by the Ingestion service health check above
    # which shows Kafka channels (data-index-events, data-index-events-dlq) as [OK]
    log_info "Checking Kafka pod status..."
    KAFKA_STATUS=$(kubectl get pod -n kafka kafka-0 -o jsonpath='{.status.phase}' 2>/dev/null || echo "NotFound")
    if [ "$KAFKA_STATUS" = "Running" ]; then
        log_success "Kafka pod is running"
    else
        log_error "Kafka pod is not running (status: $KAFKA_STATUS)"
        return 1
    fi

    log_success "MODE 3 infrastructure verification complete"
}

# ============================================================================
# Main Entry Point
# ============================================================================

MODE="${1:-}"

case "$MODE" in
    mode1)
        verify_mode1_infrastructure
        ;;
    mode2)
        verify_mode2_infrastructure
        ;;
    mode3)
        verify_mode3_infrastructure
        ;;
    *)
        log_error "Usage: $0 <mode1|mode2|mode3>"
        exit 1
        ;;
esac
