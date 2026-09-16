# ============================================================================
# Data Index Makefile
# ============================================================================
#
# Unified interface for building, testing, and deploying Data Index.
#
# Usage:
#   make test-mode1    # Full MODE 1 E2E test
#   make test-mode2    # Full MODE 2 E2E test
#   make test-mode3    # Full MODE 3 E2E test
#   make clean         # Delete KIND cluster
#
# ============================================================================

.PHONY: help clean setup test-mode1 test-mode2 test-mode3 deploy-mode1 deploy-mode2 deploy-mode3 sync-vector-configs check-vector-configs

# Configuration
CLUSTER_NAME := data-index-test
HELM_CHART := helm/data-index
KIND_CONFIG := $(HELM_CHART)/kind-cluster.yaml

# Vector config: collector source (source of truth) -> byte-identical Helm copy
VECTOR_MODES := mode1-postgresql mode2-elasticsearch
COLLECTOR_VECTOR_DIR := collectors/vector
HELM_VECTOR_DIR := $(HELM_CHART)/configs/vector

# Image configuration
# Local builds use 'kubesmarts/' prefix (for KIND cluster)
# CI/CD uses 'quay.io/kubesmarts/' (for remote registry)
IMAGE_REGISTRY_LOCAL := kubesmarts
IMAGE_REGISTRY_REMOTE := quay.io/kubesmarts
IMAGE_TAG := 999-SNAPSHOT

# Service directories and image names
PG_SERVICE_DIR := data-index-service/data-index-service-postgresql
PG_IMAGE_NAME := $(IMAGE_REGISTRY_LOCAL)/data-index-service:$(IMAGE_TAG)-postgresql

ES_SERVICE_DIR := data-index-service/data-index-service-elasticsearch
ES_IMAGE_NAME := $(IMAGE_REGISTRY_LOCAL)/data-index-service:$(IMAGE_TAG)-elasticsearch

KAFKA_SERVICE_DIR := data-index-ingestion/data-index-ingestion-kafka-service
KAFKA_IMAGE_NAME := $(IMAGE_REGISTRY_LOCAL)/data-index-ingestion:$(IMAGE_TAG)-kafka

# Colors
BLUE := \033[0;34m
GREEN := \033[0;32m
RED := \033[0;31m
NC := \033[0m

help: ## Show this help message
	@echo "$(BLUE)╔════════════════════════════════════════════════════════════════╗$(NC)"
	@echo "$(BLUE)║  Data Index - E2E Testing                                      ║$(NC)"
	@echo "$(BLUE)╚════════════════════════════════════════════════════════════════╝$(NC)"
	@echo ""
	@echo "$(GREEN)Main Commands (what you typically run):$(NC)"
	@echo "  $(GREEN)make test-mode1$(NC)   Full MODE 1 E2E (PostgreSQL + Vector + Triggers)"
	@echo "  $(GREEN)make test-mode2$(NC)   Full MODE 2 E2E (Elasticsearch + Vector + Transforms)"
	@echo "  $(GREEN)make test-mode3$(NC)   Full MODE 3 E2E (Kafka + CloudEvents + Processors)"
	@echo "  $(GREEN)make clean$(NC)        Delete KIND cluster"
	@echo ""
	@echo "$(GREEN)Quick Test (reuse existing cluster, skip rebuild):$(NC)"
	@echo "  make quick-mode1    Deploy + test MODE 1 (no rebuild)"
	@echo "  make quick-mode2    Deploy + test MODE 2 (no rebuild)"
	@echo "  make quick-mode3    Deploy + test MODE 3 (no rebuild)"
	@echo ""
	@echo "$(GREEN)Advanced (for debugging):$(NC)"
	@echo "  make setup-mode1    Just setup cluster + build MODE 1 images"
	@echo "  make setup-mode2    Just setup cluster + build MODE 2 images"
	@echo "  make setup-mode3    Just setup cluster + build MODE 3 images"
	@echo "  make deploy-mode1   Just deploy MODE 1 (cluster must exist)"
	@echo "  make deploy-mode2   Just deploy MODE 2 (cluster must exist)"
	@echo "  make deploy-mode3   Just deploy MODE 3 (cluster must exist)"
	@echo "  make e2e-mode1      Just run tests (MODE 1 must be deployed)"
	@echo "  make e2e-mode2      Just run tests (MODE 2 must be deployed)"
	@echo "  make e2e-mode3      Just run tests (MODE 3 must be deployed)"
	@echo "  make status         Show pod status"
	@echo "  make logs-*         Show logs (data-index, ingestion, vector, fluentbit)"
	@echo "  make graphql-ui     Open GraphQL UI in browser"
	@echo "  make check-vector-configs  Verify the Helm Vector configs are symlinks to collectors/vector/*"
	@echo ""
	@echo "$(GREEN)Shortcuts:$(NC)"
	@echo "  make m1             Alias for test-mode1"
	@echo "  make m2             Alias for test-mode2"
	@echo "  make m3             Alias for test-mode3"
	@echo ""
	@echo "$(GREEN)Image Building:$(NC)"
	@echo "  make build-image-postgresql  Build PostgreSQL data-index-service image"
	@echo "  make build-image-es          Build Elasticsearch data-index-service image"
	@echo "  make build-image-kafka       Build Kafka ingestion service image"
	@echo ""

# ============================================================================
# Image Building
# ============================================================================

build-image-postgresql: ## Build PostgreSQL data-index-service Docker image
	@echo "$(BLUE)==> Building PostgreSQL data-index-service image$(NC)"
	@echo "$(BLUE)    Step 1/2: Maven package$(NC)"
	@cd .. && mvn clean package -DskipTests \
		-pl data-index/$(PG_SERVICE_DIR) -am
	@echo "$(BLUE)    Step 2/2: Docker build$(NC)"
	@docker build \
		-f $(PG_SERVICE_DIR)/src/main/docker/Dockerfile.jvm \
		-t $(PG_IMAGE_NAME) \
		$(PG_SERVICE_DIR)
	@echo "$(GREEN)✓ Image built: $(PG_IMAGE_NAME)$(NC)"
	@docker images $(PG_IMAGE_NAME)

build-image-es: ## Build Elasticsearch data-index-service Docker image
	@echo "$(BLUE)==> Building Elasticsearch data-index-service image$(NC)"
	@echo "$(BLUE)    Step 1/2: Maven package$(NC)"
	@cd .. && mvn clean package -DskipTests \
		-pl data-index/$(ES_SERVICE_DIR) -am
	@echo "$(BLUE)    Step 2/2: Docker build$(NC)"
	@docker build \
		-f $(ES_SERVICE_DIR)/src/main/docker/Dockerfile.jvm \
		-t $(ES_IMAGE_NAME) \
		$(ES_SERVICE_DIR)
	@echo "$(GREEN)✓ Image built: $(ES_IMAGE_NAME)$(NC)"
	@docker images $(ES_IMAGE_NAME)

build-image-kafka: ## Build Kafka ingestion service Docker image
	@echo "$(BLUE)==> Building Kafka ingestion service image$(NC)"
	@echo "$(BLUE)    Step 1/2: Maven package$(NC)"
	@cd .. && mvn clean package -DskipTests \
		-pl data-index/$(KAFKA_SERVICE_DIR) -am
	@echo "$(BLUE)    Step 2/2: Docker build$(NC)"
	@docker build \
		-f $(KAFKA_SERVICE_DIR)/src/main/docker/Dockerfile.jvm \
		-t $(KAFKA_IMAGE_NAME) \
		$(KAFKA_SERVICE_DIR)
	@echo "$(GREEN)✓ Image built: $(KAFKA_IMAGE_NAME)$(NC)"
	@docker images $(KAFKA_IMAGE_NAME)

# ============================================================================
# Test Targets
# ============================================================================

clean: ## Delete KIND cluster
	@echo "$(BLUE)==> Deleting KIND cluster$(NC)"
	@kind delete cluster --name $(CLUSTER_NAME) 2>/dev/null || echo "No cluster to delete"
	@echo "$(GREEN)✓ Cluster deleted$(NC)"

check-vector-configs: ## Fail if any Helm Vector config isn't a symlink to its collector source
	@rc=0; for m in $(VECTOR_MODES); do \
		f="$(HELM_VECTOR_DIR)/vector-$$m.yaml"; \
		if [ ! -L "$$f" ]; then \
			echo "$(RED)✗ $$f is not a symlink$(NC)"; rc=1; \
		elif [ ! -e "$$f" ]; then \
			echo "$(RED)✗ $$f is a broken symlink$(NC)"; rc=1; \
		fi; \
	done; \
	if [ $$rc -eq 0 ]; then echo "$(GREEN)✓ Vector configs are symlinked to collectors/vector$(NC)"; fi; \
	exit $$rc

setup: ## Create cluster and build/load ALL images
	@echo "$(BLUE)==> Setting up test environment (all images)$(NC)"
	@MODE=all bash scripts/e2e/common-setup.sh
	@echo "$(GREEN)✓ Setup complete$(NC)"

setup-mode1: ## Create cluster and build/load MODE 1 images only
	@echo "$(BLUE)==> Setting up test environment (MODE 1 only)$(NC)"
	@MODE=mode1 bash scripts/e2e/common-setup.sh
	@echo "$(GREEN)✓ MODE 1 setup complete$(NC)"

setup-mode2: ## Create cluster and build/load MODE 2 images only
	@echo "$(BLUE)==> Setting up test environment (MODE 2 only)$(NC)"
	@MODE=mode2 bash scripts/e2e/common-setup.sh
	@echo "$(GREEN)✓ MODE 2 setup complete$(NC)"

setup-mode3: ## Create cluster and build/load MODE 3 images only
	@echo "$(BLUE)==> Setting up test environment (MODE 3 only)$(NC)"
	@MODE=mode3 bash scripts/e2e/common-setup.sh
	@echo "$(GREEN)✓ MODE 3 setup complete$(NC)"

test-mode1: ## Full MODE 1 E2E test (clean slate - builds MODE 1 images only)
	@bash scripts/e2e/full-test-mode1.sh

test-mode2: ## Full MODE 2 E2E test (clean slate - builds MODE 2 images only)
	@bash scripts/e2e/full-test-mode2.sh

test-mode3: ## Full MODE 3 E2E test (clean slate - builds MODE 3 images only)
	@bash scripts/e2e/full-test-mode3.sh

quick-mode1: deploy-mode1 e2e-mode1  ## Quick MODE 1 test (deploy + test, no rebuild)
quick-mode2: deploy-mode2 e2e-mode2  ## Quick MODE 2 test (deploy + test, no rebuild)
quick-mode3: deploy-mode3 e2e-mode3  ## Quick MODE 3 test (deploy + test, no rebuild)

e2e-mode1: ## Run MODE 1 E2E tests only (assumes deployed)
	@cd data-index-e2e-tests && mvn test -De2e.skip=false -De2e.mode=mode1

e2e-mode2: ## Run MODE 2 E2E tests only (assumes deployed)
	@cd data-index-e2e-tests && mvn test -De2e.skip=false -De2e.mode=mode2

e2e-mode3: ## Run MODE 3 E2E tests only (assumes deployed)
	@cd data-index-e2e-tests && mvn test -De2e.skip=false -De2e.mode=mode3

deploy-mode1: ## Deploy MODE 1 to existing cluster
	@echo "$(BLUE)==> Deploying MODE 1$(NC)"
	@helm upgrade --install data-index $(HELM_CHART) \
		--namespace default \
		--create-namespace \
		--values $(HELM_CHART)/values-mode1.yaml \
		--wait \
		--timeout 10m
	@echo "$(GREEN)✓ MODE 1 deployed$(NC)"

deploy-mode2: ## Deploy MODE 2 to existing cluster
	@echo "$(BLUE)==> Deploying MODE 2$(NC)"
	@helm upgrade --install data-index $(HELM_CHART) \
		--namespace default \
		--create-namespace \
		--values $(HELM_CHART)/values-mode2.yaml \
		--wait \
		--timeout 10m
	@echo "$(GREEN)✓ MODE 2 deployed$(NC)"

deploy-mode3: ## Deploy MODE 3 to existing cluster
	@echo "$(BLUE)==> Deploying MODE 3$(NC)"
	@helm upgrade --install data-index $(HELM_CHART) \
		--namespace default \
		--create-namespace \
		--values $(HELM_CHART)/values-mode3.yaml \
		--wait \
		--timeout 10m
	@echo "$(GREEN)✓ MODE 3 deployed$(NC)"

status: ## Show deployment status
	@echo "$(BLUE)==> Deployment Status$(NC)"
	@kubectl get pods --all-namespaces | grep -E "(NAMESPACE|data-index|postgresql|elasticsearch|kafka|fluentbit|vector|workflow)"

logs-data-index: ## Show Data Index service logs
	@kubectl logs -n default -l app=data-index-service --tail=50

logs-ingestion: ## Show Data Index Ingestion logs (MODE 3)
	@kubectl logs -n default -l app=data-index-ingestion --tail=50

logs-vector: ## Show Vector logs (MODE 1 & MODE 2)
	@kubectl logs -n logging -l app=vector --tail=50

port-forward: ## Port forward GraphQL API to localhost:8080
	@echo "$(BLUE)==> Port forwarding GraphQL API to localhost:8080$(NC)"
	@kubectl port-forward -n default svc/data-index-service 8080:8080

graphql-ui: ## Open GraphQL UI in browser
	@open http://localhost:30080/q/graphql-ui || xdg-open http://localhost:30080/q/graphql-ui

uninstall: ## Uninstall Helm release
	@echo "$(BLUE)==> Uninstalling Helm release$(NC)"
	@helm uninstall data-index --namespace default 2>/dev/null || echo "No release to uninstall"
	@echo "$(GREEN)✓ Uninstalled$(NC)"

# Quick aliases
m1: test-mode1  ## Alias for test-mode1
m2: test-mode2  ## Alias for test-mode2
m3: test-mode3  ## Alias for test-mode3
