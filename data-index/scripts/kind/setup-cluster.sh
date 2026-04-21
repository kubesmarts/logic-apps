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
NC='\033[0m' # No Color

# Configuration
CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
KUBECONFIG_PATH="${KUBECONFIG:-$HOME/.kube/config}"

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

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."

    # Check KIND
    if ! command -v kind &> /dev/null; then
        log_error "KIND is not installed. Please install from: https://kind.sigs.k8s.io/docs/user/quick-start/#installation"
        exit 1
    fi
    log_info "✓ KIND $(kind version | head -1)"

    # Check kubectl
    if ! command -v kubectl &> /dev/null; then
        log_error "kubectl is not installed. Please install from: https://kubernetes.io/docs/tasks/tools/"
        exit 1
    fi
    log_info "✓ kubectl $(kubectl version --client --short 2>/dev/null | head -1)"

    # Check Docker
    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed. Please install Docker Desktop or Docker Engine"
        exit 1
    fi

    if ! docker info &> /dev/null; then
        log_error "Docker is not running. Please start Docker"
        exit 1
    fi
    log_info "✓ Docker $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
}

# Check if cluster exists
cluster_exists() {
    kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"
}

# Delete existing cluster
delete_cluster() {
    if cluster_exists; then
        log_warn "Cluster '${CLUSTER_NAME}' already exists. Deleting..."
        kind delete cluster --name "${CLUSTER_NAME}"
        log_info "✓ Cluster deleted"
    fi
}

# Create KIND cluster
create_cluster() {
    log_info "Creating KIND cluster '${CLUSTER_NAME}'..."

    # KIND cluster configuration
    # Using single control-plane node with workloads enabled for simplicity and reliability
    cat <<EOF | kind create cluster --name "${CLUSTER_NAME}" --config=-
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
  # Single control-plane node (can run workloads)
  - role: control-plane
    kubeadmConfigPatches:
    - |
      kind: InitConfiguration
      nodeRegistration:
        kubeletExtraArgs:
          node-labels: "ingress-ready=true"
    extraPortMappings:
    # HTTP ingress
    - containerPort: 80
      hostPort: 8080
      protocol: TCP
    # HTTPS ingress
    - containerPort: 443
      hostPort: 8443
      protocol: TCP
    # GraphQL API (data-index-service)
    - containerPort: 30080
      hostPort: 30080
      protocol: TCP
    # PostgreSQL (for local access)
    - containerPort: 30432
      hostPort: 30432
      protocol: TCP
    # Kafka (for local access)
    - containerPort: 30092
      hostPort: 30092
      protocol: TCP
    # Elasticsearch (for local access)
    - containerPort: 30920
      hostPort: 30920
      protocol: TCP
EOF

    log_info "✓ Cluster created"
}

# Configure kubectl context
configure_kubectl() {
    log_info "Configuring kubectl context..."

    kubectl cluster-info --context "kind-${CLUSTER_NAME}"
    kubectl config use-context "kind-${CLUSTER_NAME}"

    log_info "✓ kubectl configured"
}

# Install NGINX Ingress Controller
install_ingress() {
    log_info "Installing NGINX Ingress Controller..."

    kubectl apply -f https://raw.githubusercontent.com/kubernetes/ingress-nginx/main/deploy/static/provider/kind/deploy.yaml

    log_info "Waiting for ingress controller to be ready..."
    kubectl wait --namespace ingress-nginx \
      --for=condition=ready pod \
      --selector=app.kubernetes.io/component=controller \
      --timeout=300s

    log_info "✓ Ingress controller installed"
}

# Print cluster info
print_cluster_info() {
    echo ""
    log_info "=========================================="
    log_info "KIND Cluster Setup Complete!"
    log_info "=========================================="
    echo ""
    log_info "Cluster Name: ${CLUSTER_NAME}"
    log_info "Context: kind-${CLUSTER_NAME}"
    echo ""
    log_info "Nodes:"
    kubectl get nodes -o wide
    echo ""
    log_info "Port Mappings:"
    echo "  - HTTP Ingress:      http://localhost:8080"
    echo "  - HTTPS Ingress:     https://localhost:8443"
    echo "  - GraphQL API:       http://localhost:30080/graphql"
    echo "  - PostgreSQL:        localhost:30432"
    echo "  - Kafka:             localhost:30092"
    echo "  - Elasticsearch:     http://localhost:30920"
    echo ""
    log_info "Next Steps:"
    echo "  1. Install dependencies: ./install-dependencies.sh"
    echo "  2. Deploy data-index: ./deploy-data-index.sh <mode>"
    echo ""
    log_info "To delete cluster: kind delete cluster --name ${CLUSTER_NAME}"
    echo ""
}

# Main execution
main() {
    log_info "Starting KIND cluster setup for Data Index integration testing"
    echo ""

    check_prerequisites

    # Ask before deleting existing cluster
    if cluster_exists; then
        read -p "Cluster '${CLUSTER_NAME}' already exists. Delete and recreate? (y/N) " -n 1 -r
        echo
        if [[ $REPLY =~ ^[Yy]$ ]]; then
            delete_cluster
        else
            log_info "Using existing cluster '${CLUSTER_NAME}'"
            configure_kubectl
            print_cluster_info
            exit 0
        fi
    fi

    create_cluster
    configure_kubectl
    install_ingress
    print_cluster_info

    log_info "✓ Setup complete!"
}

# Run main function
main "$@"
