#!/usr/bin/env bash
#
# Deploy Vector DaemonSet for MODE 2 (Elasticsearch)
#
# Deploys Vector log collector that:
#   - Tails Kubernetes logs from workflow namespace
#   - Parses JSON events
#   - Routes to Elasticsearch raw indices
#   - Relies on ES Transforms for normalization
#
# Usage:
#   ./deploy-vector-mode2.sh
#
# Environment variables:
#   WORKFLOW_NAMESPACE - Namespace to tail logs from (default: workflows)
#   ELASTICSEARCH_HOST - Elasticsearch host (default: data-index-es-http.elasticsearch.svc.cluster.local)
#   ELASTICSEARCH_PORT - Elasticsearch port (default: 9200)

set -euo pipefail

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info() { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }

# Configuration
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
VECTOR_CONFIG="${PROJECT_ROOT}/data-index/collectors/vector/mode2-elasticsearch/vector.yaml"

# Namespace and service account
NAMESPACE="logging"
SERVICE_ACCOUNT="workflows-vector-mode2"

# Environment defaults
WORKFLOW_NAMESPACE="${WORKFLOW_NAMESPACE:-workflows}"
ELASTICSEARCH_HOST="${ELASTICSEARCH_HOST:-data-index-es-http.elasticsearch.svc.cluster.local}"
ELASTICSEARCH_PORT="${ELASTICSEARCH_PORT:-9200}"

log_info "Deploying Vector for MODE 2 (Elasticsearch)..."
log_info "Config: ${VECTOR_CONFIG}"
log_info "Workflow namespace: ${WORKFLOW_NAMESPACE}"
log_info "Elasticsearch: ${ELASTICSEARCH_HOST}:${ELASTICSEARCH_PORT}"

# Verify Vector config exists
if [ ! -f "${VECTOR_CONFIG}" ]; then
    echo "ERROR: Vector config not found: ${VECTOR_CONFIG}"
    exit 1
fi

# Create namespace if it doesn't exist
kubectl create namespace "${NAMESPACE}" --dry-run=client -o yaml | kubectl apply -f -

# Create ConfigMap from Vector config
log_info "Creating Vector ConfigMap..."
kubectl create configmap "${SERVICE_ACCOUNT}-config" \
    --from-file=vector.yaml="${VECTOR_CONFIG}" \
    --namespace="${NAMESPACE}" \
    --dry-run=client -o yaml | kubectl apply -f -

# Deploy Vector DaemonSet with RBAC
log_info "Deploying Vector DaemonSet..."
kubectl apply -f - <<EOF
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${NAMESPACE}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: ${SERVICE_ACCOUNT}
rules:
  - apiGroups: [""]
    resources: ["pods", "namespaces", "nodes"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: ${SERVICE_ACCOUNT}
roleRef:
  apiGroup: rbac.authorization.k8s.io
  kind: ClusterRole
  name: ${SERVICE_ACCOUNT}
subjects:
  - kind: ServiceAccount
    name: ${SERVICE_ACCOUNT}
    namespace: ${NAMESPACE}
---
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: ${SERVICE_ACCOUNT}
  namespace: ${NAMESPACE}
  labels:
    app: ${SERVICE_ACCOUNT}
spec:
  selector:
    matchLabels:
      app: ${SERVICE_ACCOUNT}
  template:
    metadata:
      labels:
        app: ${SERVICE_ACCOUNT}
    spec:
      serviceAccountName: ${SERVICE_ACCOUNT}
      containers:
        - name: vector
          image: timberio/vector:0.41.1-distroless-libc
          env:
            - name: NODE_NAME
              valueFrom:
                fieldRef:
                  fieldPath: spec.nodeName
            - name: WORKFLOW_NAMESPACE
              value: "${WORKFLOW_NAMESPACE}"
            - name: ELASTICSEARCH_HOST
              value: "${ELASTICSEARCH_HOST}"
            - name: ELASTICSEARCH_PORT
              value: "${ELASTICSEARCH_PORT}"
            # Debug events - TESTING ONLY
            # Enables stdout logging of all events (generates ~43GB/day at 1000 events/sec)
            # Remove this in production or set to "false"
            - name: DEBUG_EVENTS
              value: "true"
          volumeMounts:
            - name: config
              mountPath: /etc/vector
              readOnly: true
            - name: varlog
              mountPath: /var/log
              readOnly: true
            - name: varlibdockercontainers
              mountPath: /var/lib/docker/containers
              readOnly: true
            - name: data
              mountPath: /tmp/vector
          resources:
            requests:
              cpu: 100m
              memory: 128Mi
            limits:
              cpu: 500m
              memory: 256Mi
      volumes:
        - name: config
          configMap:
            name: ${SERVICE_ACCOUNT}-config
        - name: varlog
          hostPath:
            path: /var/log
        - name: varlibdockercontainers
          hostPath:
            path: /var/lib/docker/containers
        - name: data
          emptyDir: {}
      tolerations:
        - effect: NoSchedule
          key: node-role.kubernetes.io/master
          operator: Exists
        - effect: NoSchedule
          key: node-role.kubernetes.io/control-plane
          operator: Exists
EOF

log_info "✓ Vector deployed"
log_info ""
log_info "Verify deployment:"
log_info "  kubectl get daemonset -n ${NAMESPACE} ${SERVICE_ACCOUNT}"
log_info "  kubectl logs -n ${NAMESPACE} -l app=${SERVICE_ACCOUNT}"
log_info ""
log_info "Check metrics:"
log_info "  kubectl port-forward -n ${NAMESPACE} daemonset/${SERVICE_ACCOUNT} 9598:9598"
log_info "  curl http://localhost:9598/metrics"
