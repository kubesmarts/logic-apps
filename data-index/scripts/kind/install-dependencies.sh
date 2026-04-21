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
# WITHOUT WARRANTIES OR CONDITIONS OF the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
# express or implied. See the License for the specific language
# governing permissions and limitations under the License.
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
MODE="${MODE:-all}"  # all, postgresql, kafka, elasticsearch

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

    kubectl create namespace data-index --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace fluent-bit --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace postgresql --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace kafka --dry-run=client -o yaml | kubectl apply -f -
    kubectl create namespace elasticsearch --dry-run=client -o yaml | kubectl apply -f -

    log_info "✓ Namespaces created"
}

# Install FluentBit
install_fluentbit() {
    log_step "Installing Fluent Bit..."

    # Add Fluent Helm repository
    helm repo add fluent https://fluent.github.io/helm-charts 2>/dev/null || true
    helm repo update

    # Install Fluent Bit
    helm upgrade --install fluent-bit fluent/fluent-bit \
      --namespace fluent-bit \
      --set image.repository=fluent/fluent-bit \
      --set image.tag=3.0 \
      --set resources.requests.cpu=100m \
      --set resources.requests.memory=128Mi \
      --set resources.limits.cpu=500m \
      --set resources.limits.memory=512Mi \
      --wait \
      --timeout=5m

    log_info "✓ Fluent Bit installed"
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

# Install Strimzi Kafka Operator
install_kafka_operator() {
    log_step "Installing Strimzi Kafka Operator..."

    # Install Strimzi operator
    kubectl create -f 'https://strimzi.io/install/latest?namespace=kafka' -n kafka || true

    log_info "Waiting for Strimzi operator to be ready..."
    kubectl wait --namespace kafka \
      --for=condition=ready pod \
      --selector=name=strimzi-cluster-operator \
      --timeout=300s

    log_info "✓ Strimzi operator installed"
}

# Install Kafka cluster
install_kafka() {
    install_kafka_operator

    log_step "Installing Kafka cluster..."

    # Create Kafka cluster
    kubectl apply -f - <<EOF
apiVersion: kafka.strimzi.io/v1beta2
kind: Kafka
metadata:
  name: data-index-kafka
  namespace: kafka
spec:
  kafka:
    version: 3.7.0
    replicas: 1
    listeners:
      - name: plain
        port: 9092
        type: internal
        tls: false
      - name: external
        port: 9094
        type: nodeport
        tls: false
        configuration:
          bootstrap:
            nodePort: 30092
    config:
      offsets.topic.replication.factor: 1
      transaction.state.log.replication.factor: 1
      transaction.state.log.min.isr: 1
      default.replication.factor: 1
      min.insync.replicas: 1
    storage:
      type: jbod
      volumes:
      - id: 0
        type: persistent-claim
        size: 1Gi
        deleteClaim: false
    resources:
      requests:
        memory: 512Mi
        cpu: 200m
      limits:
        memory: 1Gi
        cpu: 1000m
  zookeeper:
    replicas: 1
    storage:
      type: persistent-claim
      size: 1Gi
      deleteClaim: false
    resources:
      requests:
        memory: 256Mi
        cpu: 100m
      limits:
        memory: 512Mi
        cpu: 500m
  entityOperator:
    topicOperator:
      resources:
        requests:
          memory: 128Mi
          cpu: 50m
        limits:
          memory: 256Mi
          cpu: 200m
    userOperator:
      resources:
        requests:
          memory: 128Mi
          cpu: 50m
        limits:
          memory: 256Mi
          cpu: 200m
EOF

    log_info "Waiting for Kafka cluster to be ready (this may take several minutes)..."
    kubectl wait --namespace kafka \
      --for=condition=ready kafka/data-index-kafka \
      --timeout=600s

    # Create topics
    kubectl apply -f - <<EOF
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: workflow-instance-events
  namespace: kafka
  labels:
    strimzi.io/cluster: data-index-kafka
spec:
  partitions: 3
  replicas: 1
  config:
    retention.ms: 86400000
    segment.bytes: 1073741824
---
apiVersion: kafka.strimzi.io/v1beta2
kind: KafkaTopic
metadata:
  name: task-execution-events
  namespace: kafka
  labels:
    strimzi.io/cluster: data-index-kafka
spec:
  partitions: 3
  replicas: 1
  config:
    retention.ms: 86400000
    segment.bytes: 1073741824
EOF

    log_info "✓ Kafka cluster installed"
    log_info "  Bootstrap: localhost:30092"
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
    echo "  - Fluent Bit: $(kubectl get pods -n fluent-bit -o json | jq -r '.items[0].status.phase')"

    if [[ "$MODE" == "all" ]] || [[ "$MODE" == "postgresql" ]] || [[ "$MODE" == "kafka" ]]; then
        echo "  - PostgreSQL: $(kubectl get pods -n postgresql -l app.kubernetes.io/component=primary -o json | jq -r '.items[0].status.phase')"
    fi

    if [[ "$MODE" == "all" ]] || [[ "$MODE" == "kafka" ]]; then
        echo "  - Kafka: $(kubectl get kafka -n kafka data-index-kafka -o json | jq -r '.status.conditions[] | select(.type=="Ready") | .status')"
    fi

    if [[ "$MODE" == "all" ]] || [[ "$MODE" == "elasticsearch" ]]; then
        echo "  - Elasticsearch: $(kubectl get elasticsearch -n elasticsearch data-index-es -o json | jq -r '.status.health')"
    fi

    echo ""
    log_info "Next Steps:"
    echo "  1. Deploy data-index: ./deploy-data-index.sh <mode>"
    echo "     Modes: postgresql-polling | kafka-postgresql | elasticsearch"
    echo ""
}

# Main execution
main() {
    log_info "Installing dependencies for Data Index (MODE: ${MODE})"
    echo ""

    check_prerequisites
    create_namespaces
    install_fluentbit

    case "$MODE" in
        all)
            install_postgresql
            install_kafka
            install_elasticsearch
            ;;
        postgresql)
            install_postgresql
            ;;
        kafka)
            install_postgresql
            install_kafka
            ;;
        elasticsearch)
            install_elasticsearch
            ;;
        *)
            log_error "Invalid MODE: ${MODE}. Valid options: all, postgresql, kafka, elasticsearch"
            exit 1
            ;;
    esac

    print_summary

    log_info "✓ Installation complete!"
}

# Run main function
main "$@"
