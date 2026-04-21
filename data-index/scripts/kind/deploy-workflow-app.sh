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
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

IMAGE_TAG="${IMAGE_TAG:-999-SNAPSHOT}"
CLUSTER_NAME="${CLUSTER_NAME:-data-index-test}"

log_info "Deploying workflow test application to KIND"

# Create namespace
log_step "Creating workflows namespace..."
kubectl create namespace workflows --dry-run=client -o yaml | kubectl apply -f -

# Deploy workflow application
log_step "Deploying workflow application..."
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: workflow-test-app
  namespace: workflows
  labels:
    app: workflow-test-app
spec:
  replicas: 1
  selector:
    matchLabels:
      app: workflow-test-app
  template:
    metadata:
      labels:
        app: workflow-test-app
    spec:
      containers:
      - name: workflow-app
        image: kubesmarts/workflow-test-app:${IMAGE_TAG}
        imagePullPolicy: Never
        ports:
        - containerPort: 8080
          name: http
        env:
        - name: QUARKUS_HTTP_PORT
          value: "8080"
        - name: QUARKUS_LOG_LEVEL
          value: "INFO"
        - name: QUARKUS_LOG_CATEGORY_IO_QUARKIVERSE_FLOW_LEVEL
          value: "DEBUG"
        # Configure Quarkus Flow structured logging
        - name: QUARKUS_FLOW_STRUCTURED_LOGGING_ENABLED
          value: "true"
        - name: QUARKUS_FLOW_STRUCTURED_LOGGING_EVENTS
          value: "workflow.*,task.*"
        - name: QUARKUS_FLOW_STRUCTURED_LOGGING_INCLUDE_WORKFLOW_PAYLOADS
          value: "true"
        - name: QUARKUS_FLOW_STRUCTURED_LOGGING_INCLUDE_TASK_PAYLOADS
          value: "true"
        # Log to stdout for FluentBit to capture
        - name: QUARKUS_LOG_HANDLER_FILE_FLOW_EVENTS_ENABLE
          value: "true"
        - name: QUARKUS_LOG_HANDLER_FILE_FLOW_EVENTS_PATH
          value: "/var/log/quarkus-flow/events.log"
        - name: QUARKUS_LOG_CONSOLE_JSON
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
  name: workflow-test-app
  namespace: workflows
  labels:
    app: workflow-test-app
spec:
  type: NodePort
  selector:
    app: workflow-test-app
  ports:
  - port: 8080
    targetPort: 8080
    nodePort: 30082
    protocol: TCP
    name: http
EOF

log_step "Waiting for deployment to be ready..."
kubectl wait --namespace workflows \
  --for=condition=available deployment/workflow-test-app \
  --timeout=180s

log_step "Waiting for pod to be ready..."
kubectl wait --namespace workflows \
  --for=condition=ready pod \
  --selector=app=workflow-test-app \
  --timeout=180s

echo ""
log_info "=========================================="
log_info "Workflow Application Deployed!"
log_info "=========================================="
echo ""
log_info "Endpoints:"
echo "  - HTTP API:      http://localhost:30082"
echo "  - Health:        http://localhost:30082/q/health"
echo "  - Dev UI:        http://localhost:30082/q/dev"
echo ""
log_info "Available Workflows:"
echo "  - test:simple-set"
echo "  - test:hello-world"
echo "  - test:hello-world-fail"
echo "  - test:test-http-success"
echo ""
log_info "Test workflow execution:"
echo '  curl -X POST http://localhost:30082/test/simple-set/start'
echo ""
log_info "View logs:"
echo "  kubectl logs -n workflows -l app=workflow-test-app -f"
echo ""
log_info "View structured events (JSON):"
echo '  kubectl logs -n workflows -l app=workflow-test-app | grep "eventType"'
echo ""
