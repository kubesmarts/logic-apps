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

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
MODE="${1:-}"
IMAGE_TAG="${IMAGE_TAG:-999-SNAPSHOT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Logging functions
log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

# Print usage
usage() {
    echo "Usage: $0 <MODE>"
    echo ""
    echo "Deploy Data Index to KIND cluster in specified mode"
    echo ""
    echo "Modes:"
    echo "  postgresql           - Mode 1: FluentBit → PostgreSQL (triggers) → Query tables"
    echo "  elasticsearch        - Mode 2: FluentBit → Elasticsearch → Transform → Query indices"
    echo ""
    echo "Legacy mode names (deprecated but still supported):"
    echo "  postgresql-polling   - Alias for 'postgresql'"
    echo ""
    echo "Examples:"
    echo "  $0 postgresql"
    echo "  IMAGE_TAG=1.0.0 $0 elasticsearch"
    exit 1
}

# Validate mode
validate_mode() {
    # Normalize legacy mode names
    case "$MODE" in
        postgresql-polling)
            log_warn "Mode 'postgresql-polling' is deprecated, use 'postgresql'"
            MODE="postgresql"
            ;;
        kafka-postgresql)
            log_error "Mode 'kafka-postgresql' is no longer supported (MODE 3 removed)"
            exit 1
            ;;
    esac

    case "$MODE" in
        postgresql|elasticsearch)
            log_info "Deployment mode: $MODE"
            ;;
        *)
            log_error "Invalid mode: $MODE"
            usage
            ;;
    esac
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed"
        exit 1
    fi

    if ! command -v docker &> /dev/null; then
        log_error "docker is not installed"
        exit 1
    fi

    # Check cluster exists
    if ! kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_error "Cluster '${CLUSTER_NAME}' does not exist. Run setup-cluster.sh first"
        exit 1
    fi

    # Set context
    kubectl config use-context "kind-${CLUSTER_NAME}" &> /dev/null
    log_info "✓ Using cluster: ${CLUSTER_NAME}"

    # Check if dependencies are installed based on mode
    case "$MODE" in
        postgresql)
            if ! kubectl get namespace postgresql &> /dev/null; then
                log_error "PostgreSQL not installed. Run: MODE=postgresql ./install-dependencies.sh"
                exit 1
            fi
            ;;
        elasticsearch)
            if ! kubectl get namespace elasticsearch &> /dev/null; then
                log_error "Elasticsearch not installed. Run: MODE=elasticsearch ./install-dependencies.sh"
                exit 1
            fi
            ;;
    esac

    log_info "✓ Dependencies verified"
}

# Build data-index service image
build_image() {
    log_step "Building data-index-service image..."

    cd "${PROJECT_ROOT}"

    # Build with Maven using profile-based approach
    log_info "Building with Maven (profile: ${MODE})..."
    mvn clean package -pl data-index-service -am \
        -Dquarkus.profile=${MODE} \
        -Dquarkus.container-image.build=true \
        -DskipFlyway=true \
        -DskipTests -q

    log_info "✓ Container image built (without Flyway): kubesmarts/data-index-service:${IMAGE_TAG}"

    # Load image into KIND cluster
    log_info "Loading image into KIND cluster..."
    kind load docker-image kubesmarts/data-index-service:${IMAGE_TAG} \
        --name ${CLUSTER_NAME}

    log_info "✓ Image loaded to KIND cluster"
}

# Initialize database schema
init_database_schema() {
    log_step "Initializing PostgreSQL database schema..."

    local SCHEMA_FILE="${PROJECT_ROOT}/scripts/schema.sql"

    if [[ ! -f "$SCHEMA_FILE" ]]; then
        log_warn "Schema file not found: $SCHEMA_FILE"
        return
    fi

    # Copy schema to PostgreSQL pod
    kubectl cp "$SCHEMA_FILE" postgresql/postgresql-0:/tmp/schema.sql

    # Execute schema
    kubectl exec -n postgresql postgresql-0 -- \
        psql -U dataindex -d dataindex -f /tmp/schema.sql

    log_info "✓ Database schema initialized"
}

# Create namespace for data-index
create_namespace() {
    log_step "Creating data-index namespace..."
    kubectl create namespace data-index --dry-run=client -o yaml | kubectl apply -f -
    log_info "✓ Namespace created"
}

# Create ConfigMap for data-index configuration
create_configmap() {
    log_step "Creating data-index ConfigMap..."

    local BACKEND="POSTGRESQL"
    local DATASOURCE_URL="jdbc:postgresql://postgresql.postgresql.svc.cluster.local:5432/dataindex"

    if [[ "$MODE" == "elasticsearch" ]]; then
        BACKEND="ELASTICSEARCH"
    fi

    kubectl create configmap data-index-config \
        --namespace data-index \
        --from-literal=QUARKUS_DATASOURCE_JDBC_URL="$DATASOURCE_URL" \
        --from-literal=QUARKUS_DATASOURCE_USERNAME=dataindex \
        --from-literal=QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION=update \
        --from-literal=DATA_INDEX_STORAGE_BACKEND="$BACKEND" \
        --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ ConfigMap created (backend: $BACKEND)"
}

# Create Secret for credentials
create_secret() {
    log_step "Creating data-index Secret..."

    kubectl create secret generic data-index-secret \
        --namespace data-index \
        --from-literal=QUARKUS_DATASOURCE_PASSWORD=dataindex123 \
        --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ Secret created"
}

# Deploy data-index service
deploy_service() {
    log_step "Deploying data-index-service..."

    kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-service
  namespace: data-index
  labels:
    app: data-index-service
    mode: ${MODE}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: data-index-service
  template:
    metadata:
      labels:
        app: data-index-service
        mode: ${MODE}
    spec:
      containers:
      - name: data-index-service
        image: kubesmarts/data-index-service:${IMAGE_TAG}
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
          name: http
          protocol: TCP
        env:
        - name: QUARKUS_DATASOURCE_JDBC_URL
          valueFrom:
            configMapKeyRef:
              name: data-index-config
              key: QUARKUS_DATASOURCE_JDBC_URL
        - name: QUARKUS_DATASOURCE_USERNAME
          valueFrom:
            configMapKeyRef:
              name: data-index-config
              key: QUARKUS_DATASOURCE_USERNAME
        - name: QUARKUS_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: data-index-secret
              key: QUARKUS_DATASOURCE_PASSWORD
        - name: QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION
          valueFrom:
            configMapKeyRef:
              name: data-index-config
              key: QUARKUS_HIBERNATE_ORM_DATABASE_GENERATION
        - name: DATA_INDEX_STORAGE_BACKEND
          valueFrom:
            configMapKeyRef:
              name: data-index-config
              key: DATA_INDEX_STORAGE_BACKEND
        - name: QUARKUS_HTTP_PORT
          value: "8080"
        - name: QUARKUS_LOG_LEVEL
          value: "INFO"
        - name: QUARKUS_LOG_CATEGORY_ORG_KUBESMARTS_LOGIC_LEVEL
          value: "DEBUG"
        resources:
          requests:
            memory: "512Mi"
            cpu: "250m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 8080
          initialDelaySeconds: 20
          periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-service
  namespace: data-index
  labels:
    app: data-index-service
spec:
  type: NodePort
  selector:
    app: data-index-service
  ports:
  - port: 8080
    targetPort: 8080
    nodePort: 30080
    protocol: TCP
    name: http
EOF

    log_info "✓ Deployment and Service created"
}

# Wait for deployment to be ready
wait_for_ready() {
    log_step "Waiting for data-index-service to be ready..."

    kubectl wait --namespace data-index \
        --for=condition=available deployment/data-index-service \
        --timeout=300s

    kubectl wait --namespace data-index \
        --for=condition=ready pod \
        --selector=app=data-index-service \
        --timeout=300s

    log_info "✓ data-index-service is ready"
}

# Print deployment info
print_info() {
    echo ""
    log_info "=========================================="
    log_info "Data Index Deployment Complete!"
    log_info "=========================================="
    echo ""
    log_info "Mode: $MODE"
    log_info "Image: kubesmarts/data-index-service:${IMAGE_TAG}"
    echo ""
    log_info "Endpoints:"
    echo "  - GraphQL API:   http://localhost:30080/graphql"
    echo "  - GraphQL UI:    http://localhost:30080/q/graphql-ui"
    echo "  - Health:        http://localhost:30080/q/health"
    echo "  - Metrics:       http://localhost:30080/q/metrics"
    echo ""

    case "$MODE" in
        postgresql)
            echo "  - PostgreSQL:    postgresql://dataindex:dataindex123@localhost:30432/dataindex"
            ;;
        elasticsearch)
            echo "  - Elasticsearch: http://localhost:30920"
            ;;
    esac

    echo ""
    log_info "Test GraphQL API:"
    echo '  curl http://localhost:30080/graphql -H "Content-Type: application/json" -d '"'"'{"query":"{ __schema { queryType { name } } }"}'"'"
    echo ""
    log_info "View pods:"
    echo "  kubectl get pods -n data-index"
    echo ""
    log_info "View logs:"
    echo "  kubectl logs -n data-index -l app=data-index-service -f"
    echo ""
}

# Main execution
main() {
    if [[ -z "$MODE" ]]; then
        usage
    fi

    log_info "Deploying Data Index to KIND cluster"
    echo ""

    validate_mode
    check_prerequisites

    # Skip image build if SKIP_IMAGE_BUILD is set (e.g., in CI where images are pre-built)
    if [[ "${SKIP_IMAGE_BUILD:-false}" != "true" ]]; then
        build_image
    else
        log_info "Skipping image build (SKIP_IMAGE_BUILD=true)"
    fi

    # Initialize database for PostgreSQL mode (skip if already initialized in CI)
    if [[ "$MODE" == "postgresql" ]]; then
        if [[ "${SKIP_DB_INIT:-false}" != "true" ]]; then
            init_database_schema
        else
            log_info "Skipping database initialization (SKIP_DB_INIT=true)"
        fi
    fi

    create_namespace
    create_configmap
    create_secret
    deploy_service
    wait_for_ready
    print_info

    log_info "✓ Deployment complete!"
}

# Run main function
main "$@"
