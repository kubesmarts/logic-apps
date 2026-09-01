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
# Deploy the Kafka ingestion service to a KIND cluster (MODE 3).
#
# This service consumes CloudEvents from the flow-lifecycle-out Kafka topic and
# normalizes them directly into PostgreSQL (workflow_instances, task_instances)
# via JDBC UPSERT.  It complements the postgresql-backed data-index-service
# (query side) — together they form the full MODE 3 stack.
#
# Prerequisites:
#   - KIND cluster running (setup-cluster.sh)
#   - PostgreSQL running in the postgresql namespace with schema initialized
#   - Kafka running in the kafka namespace (kafka.yaml)
#   - data-index-service deployed in postgresql mode (deploy-data-index.sh kafka)
#
# Usage:
#   ./deploy-kafka-ingestion.sh
#
# Environment variables:
#   CLUSTER_NAME   - KIND cluster name (default: data-index-test)
#   IMAGE_TAG      - image tag (default: 999-SNAPSHOT)
#   SKIP_BUILD     - set to "true" to skip Maven build and image load

set -euo pipefail

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Configuration
CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"
IMAGE_TAG="${IMAGE_TAG:-999-SNAPSHOT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"

IMAGE_NAME="kubesmarts/data-index-ingestion-kafka-service"
KAFKA_BOOTSTRAP="kafka.kafka.svc.cluster.local:9092"
PG_JDBC_URL="jdbc:postgresql://postgresql.postgresql.svc.cluster.local:5432/dataindex"
PG_USER="dataindex"
PG_PASSWORD="dataindex123"

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step() { echo -e "${BLUE}[STEP]${NC} $1"; }

check_prerequisites() {
    log_info "Checking prerequisites..."

    if ! kind get clusters 2>/dev/null | grep -q "^${CLUSTER_NAME}$"; then
        log_error "Cluster '${CLUSTER_NAME}' not found. Run setup-cluster.sh first."
        exit 1
    fi
    kubectl config use-context "kind-${CLUSTER_NAME}" &>/dev/null

    if ! kubectl get namespace postgresql &>/dev/null; then
        log_error "PostgreSQL namespace not found. Run: MODE=kafka ./install-dependencies.sh"
        exit 1
    fi

    if ! kubectl get namespace kafka &>/dev/null; then
        log_error "Kafka namespace not found. Run: MODE=kafka ./install-dependencies.sh"
        exit 1
    fi

    log_info "✓ Prerequisites verified"
}

build_and_load() {
    if [[ "${SKIP_BUILD:-false}" == "true" ]]; then
        log_info "Skipping build (SKIP_BUILD=true)"
        return
    fi

    log_step "Building data-index-ingestion-kafka-service..."
    cd "${PROJECT_ROOT}"
    mvn clean package \
        -pl data-index/data-index-ingestion/data-index-ingestion-kafka-service -am \
        -Dquarkus.container-image.build=true \
        -DskipTests -q

    log_info "✓ Image built: ${IMAGE_NAME}:${IMAGE_TAG}"

    log_step "Loading image into KIND cluster..."
    kind load docker-image "${IMAGE_NAME}:${IMAGE_TAG}" --name "${CLUSTER_NAME}"
    log_info "✓ Image loaded"
}

create_kafka_topics() {
    log_step "Creating Kafka topics..."

    # Create topics before deploying consumer to avoid health check failures
    kubectl exec -n kafka kafka-0 -- \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create --if-not-exists \
        --topic flow-lifecycle-out \
        --partitions 1 --replication-factor 1 || true

    kubectl exec -n kafka kafka-0 -- \
        /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create --if-not-exists \
        --topic data-index-events-dlq \
        --partitions 1 --replication-factor 1 || true

    log_info "✓ Kafka topics created"
}

deploy_ingestion_service() {
    log_step "Deploying data-index-ingestion-kafka-service..."

    # Secret for PostgreSQL password
    kubectl create secret generic kafka-ingestion-secret \
        --namespace data-index \
        --from-literal=QUARKUS_DATASOURCE_PASSWORD="${PG_PASSWORD}" \
        --dry-run=client -o yaml | kubectl apply -f -

    kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: data-index-ingestion-kafka-service
  namespace: data-index
  labels:
    app: data-index-ingestion-kafka-service
    mode: kafka
spec:
  replicas: 1
  selector:
    matchLabels:
      app: data-index-ingestion-kafka-service
  template:
    metadata:
      labels:
        app: data-index-ingestion-kafka-service
        mode: kafka
    spec:
      containers:
        - name: ingestion
          image: ${IMAGE_NAME}:${IMAGE_TAG}
          imagePullPolicy: Never
          ports:
            - containerPort: 8080
              name: http
              protocol: TCP
          env:
            # Kafka bootstrap — in-cluster address
            - name: KAFKA_BOOTSTRAP_SERVERS
              value: "${KAFKA_BOOTSTRAP}"
            # SmallRye Reactive Messaging connector bootstrap (overrides application.properties)
            - name: MP_MESSAGING_CONNECTOR_SMALLRYE_KAFKA_BOOTSTRAP_SERVERS
              value: "${KAFKA_BOOTSTRAP}"
            # PostgreSQL datasource
            - name: QUARKUS_DATASOURCE_JDBC_URL
              value: "${PG_JDBC_URL}"
            - name: QUARKUS_DATASOURCE_USERNAME
              value: "${PG_USER}"
            - name: QUARKUS_DATASOURCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: kafka-ingestion-secret
                  key: QUARKUS_DATASOURCE_PASSWORD
            - name: QUARKUS_HTTP_PORT
              value: "8080"
            - name: QUARKUS_LOG_LEVEL
              value: "INFO"
            - name: QUARKUS_LOG_CATEGORY_ORG_KUBESMARTS_LOGIC_LEVEL
              value: "DEBUG"
            # Disable Flyway migrations at startup (migrations are run manually by deploy script)
            - name: QUARKUS_FLYWAY_MIGRATE_AT_START
              value: "false"
          resources:
            requests:
              memory: "256Mi"
              cpu: "100m"
            limits:
              memory: "512Mi"
              cpu: "500m"
          livenessProbe:
            httpGet:
              path: /q/health/live
              port: 8080
            initialDelaySeconds: 75
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 3
          readinessProbe:
            httpGet:
              path: /q/health/ready
              port: 8080
            initialDelaySeconds: 75
            periodSeconds: 5
            timeoutSeconds: 5
            failureThreshold: 3
---
apiVersion: v1
kind: Service
metadata:
  name: data-index-ingestion-kafka-service
  namespace: data-index
  labels:
    app: data-index-ingestion-kafka-service
spec:
  type: ClusterIP
  selector:
    app: data-index-ingestion-kafka-service
  ports:
    - port: 8080
      targetPort: 8080
      name: http
      protocol: TCP
EOF

    log_info "✓ Deployment and Service applied"
}

wait_for_ready() {
    log_step "Waiting for ingestion service to be ready..."

    kubectl wait --namespace data-index \
        --for=condition=available deployment/data-index-ingestion-kafka-service \
        --timeout=300s

    kubectl wait --namespace data-index \
        --for=condition=ready pod \
        --selector=app=data-index-ingestion-kafka-service \
        --timeout=300s

    log_info "✓ Ingestion service is ready"
}

print_info() {
    echo ""
    log_info "=========================================="
    log_info "Kafka Ingestion Service Deployed!"
    log_info "=========================================="
    echo ""
    log_info "Image:  ${IMAGE_NAME}:${IMAGE_TAG}"
    log_info "Topic:  flow-lifecycle-out  (auto-created)"
    log_info "Group:  data-index-ingestion"
    echo ""
    log_info "Useful commands:"
    echo "  # View ingestion logs"
    echo "  kubectl logs -n data-index -l app=data-index-ingestion-kafka-service -f"
    echo ""
    echo "  # Health check"
    echo "  kubectl port-forward -n data-index svc/data-index-ingestion-kafka-service 8090:8080"
    echo "  curl http://localhost:8090/q/health"
    echo ""
    echo "  # Consumer group lag"
    echo "  kubectl exec -n kafka kafka-0 -- \\"
    echo "    /opt/bitnami/kafka/bin/kafka-consumer-groups.sh \\"
    echo "    --bootstrap-server localhost:9092 \\"
    echo "    --describe --group data-index-ingestion"
    echo ""
}

main() {
    log_info "Deploying Kafka Ingestion Service (MODE 3)"
    echo ""

    check_prerequisites
    build_and_load
    create_kafka_topics
    deploy_ingestion_service
    wait_for_ready
    print_info

    log_info "✓ Deployment complete!"
}

main "$@"
