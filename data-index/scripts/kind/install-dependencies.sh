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
MODE="${MODE:-postgresql}"  # postgresql, elasticsearch

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

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed"
        exit 1
    fi

    if ! command -v helm &> /dev/null; then
        log_error "Helm is not installed. Please install from: https://helm.sh/docs/intro/install/"
        exit 1
    fi
    log_info "✓ Helm $(helm version --short 2>/dev/null)"

    # Check cluster exists
    if ! kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_error "Cluster '${CLUSTER_NAME}' does not exist. Run setup-cluster.sh first"
        exit 1
    fi

    # Set context
    kubectl config use-context "kind-${CLUSTER_NAME}" &> /dev/null
    log_info "✓ Using cluster: ${CLUSTER_NAME}"
}

# Create namespaces
create_namespaces() {
    log_step "Creating namespaces..."

    kubectl create namespace logging --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace postgresql --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace elasticsearch --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace workflows --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ Namespaces created"
}

# Install PostgreSQL
install_postgresql() {
    log_step "Installing PostgreSQL..."

    # Add Bitnami Helm repository
    helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || true
    helm repo update

    # Install PostgreSQL
    helm upgrade --install postgresql bitnami/postgresql \
      --namespace postgresql \
      --set auth.username=dataindex \
      --set auth.password=dataindex123 \
      --set auth.database=dataindex \
      --set primary.persistence.size=1Gi \
      --set primary.resources.requests.cpu=100m \
      --set primary.resources.requests.memory=256Mi \
      --set primary.resources.limits.cpu=1000m \
      --set primary.resources.limits.memory=1Gi \
      --set primary.service.type=NodePort \
      --set primary.service.nodePorts.postgresql=30432 \
      --wait \
      --timeout=5m

    log_info "Waiting for PostgreSQL to be ready..."
    kubectl wait --namespace postgresql \
      --for=condition=ready pod \
      --selector=app.kubernetes.io/component=primary \
      --timeout=300s

    log_info "✓ PostgreSQL installed"
    log_info "  Connection: postgresql://dataindex:dataindex123@localhost:30432/dataindex"
}

# Install Elasticsearch (ECK Operator)
install_elasticsearch_operator() {
    log_step "Installing Elastic Cloud on Kubernetes (ECK) Operator..."

    kubectl create -f https://download.elastic.co/downloads/eck/2.12.1/crds.yaml || true
    kubectl apply -f https://download.elastic.co/downloads/eck/2.12.1/operator.yaml

    log_info "Waiting for ECK operator to be ready..."
    kubectl wait --namespace elastic-system \
      --for=condition=ready pod \
      --selector=control-plane=elastic-operator \
      --timeout=300s

    log_info "✓ ECK operator installed"
}

# Install Elasticsearch cluster
install_elasticsearch() {
    install_elasticsearch_operator

    log_step "Installing Elasticsearch cluster..."

    # Create Elasticsearch cluster
    kubectl apply -f - <<EOF
apiVersion: elasticsearch.k8s.elastic.co/v1
kind: Elasticsearch
metadata:
  name: data-index-es
  namespace: elasticsearch
spec:
  version: 8.12.2
  nodeSets:
  - name: default
    count: 1
    config:
      node.store.allow_mmap: false
      xpack.security.enabled: false
      xpack.security.http.ssl.enabled: false
    podTemplate:
      spec:
        containers:
        - name: elasticsearch
          resources:
            requests:
              memory: 1Gi
              cpu: 500m
            limits:
              memory: 2Gi
              cpu: 2000m
    volumeClaimTemplates:
    - metadata:
        name: elasticsearch-data
      spec:
        accessModes:
        - ReadWriteOnce
        resources:
          requests:
            storage: 2Gi
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-es-http-nodeport
  namespace: elasticsearch
spec:
  type: NodePort
  selector:
    elasticsearch.k8s.elastic.co/cluster-name: data-index-es
  ports:
  - port: 9200
    targetPort: 9200
    nodePort: 30920
    protocol: TCP
    name: http
EOF

    log_info "Waiting for Elasticsearch to be ready (this may take several minutes)..."
    kubectl wait --namespace elasticsearch \
      --for=condition=ready elasticsearch/data-index-es \
      --timeout=600s

    log_info "✓ Elasticsearch installed"
    log_info "  URL: http://localhost:30920"
}

# Print summary
print_summary() {
    echo ""
    log_info "=========================================="
    log_info "Dependencies Installation Complete!"
    log_info "=========================================="
    echo ""

    log_info "Installed Components:"

    if [[ "$MODE" == "postgresql" ]]; then
        echo "  - PostgreSQL: $(kubectl get pods -n postgresql -l app.kubernetes.io/component=primary -o json | jq -r '.items[0].status.phase' 2>/dev/null || echo 'N/A')"
    fi

    if [[ "$MODE" == "elasticsearch" ]]; then
        echo "  - Elasticsearch: $(kubectl get elasticsearch -n elasticsearch data-index-es -o json | jq -r '.status.health' 2>/dev/null || echo 'N/A')"
    fi

    echo ""
    log_info "Next Steps:"
    echo "  - MODE 1 (PostgreSQL): Deploy FluentBit with MODE 1 config (see test-mode1-e2e.sh)"
    echo "  - MODE 2 (Elasticsearch): Deploy FluentBit with MODE 2 config (not yet implemented)"
    echo ""
}

# Main execution
main() {
    log_info "Installing dependencies for Data Index (MODE: ${MODE})"
    echo ""

    check_prerequisites
    create_namespaces

    case "$MODE" in
        postgresql)
            install_postgresql
            ;;
        elasticsearch)
            install_elasticsearch
            ;;
        *)
            log_error "Invalid MODE: ${MODE}. Valid options: postgresql, elasticsearch"
            exit 1
            ;;
    esac

    print_summary

    log_info "✓ Installation complete!"
}

# Run main function
main "$@"
