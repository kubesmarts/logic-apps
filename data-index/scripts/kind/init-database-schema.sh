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
RED='\033[0;31m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_step() {
    echo -e "${BLUE}[STEP]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Get script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Configuration
POSTGRES_NAMESPACE="${POSTGRES_NAMESPACE:-postgresql}"
POSTGRES_POD="${POSTGRES_POD:-postgresql-0}"
POSTGRES_USER="${POSTGRES_USER:-dataindex}"
POSTGRES_PASSWORD="${POSTGRES_PASSWORD:-dataindex123}"
POSTGRES_DB="${POSTGRES_DB:-dataindex}"

# Path to migration SQL
MIGRATION_SQL="${PROJECT_ROOT}/data-index-storage/data-index-storage-migrations/src/main/resources/db/migration/V1__initial_schema.sql"

log_info "Initializing Data Index database schema in KIND cluster"
echo ""

# Check if migration SQL exists
if [[ ! -f "$MIGRATION_SQL" ]]; then
    log_error "Migration SQL not found: $MIGRATION_SQL"
    exit 1
fi

log_step "Copying schema SQL to PostgreSQL pod..."
kubectl cp "$MIGRATION_SQL" \
    "${POSTGRES_NAMESPACE}/${POSTGRES_POD}:/tmp/schema.sql"

log_step "Executing schema creation..."
kubectl exec -n "$POSTGRES_NAMESPACE" "$POSTGRES_POD" -- \
    env PGPASSWORD="$POSTGRES_PASSWORD" \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -f /tmp/schema.sql

log_step "Verifying tables created..."
TABLES=$(kubectl exec -n "$POSTGRES_NAMESPACE" "$POSTGRES_POD" -- \
    env PGPASSWORD="$POSTGRES_PASSWORD" \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    -t -c "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE';")

TABLES=$(echo "$TABLES" | tr -d '[:space:]')

echo ""
log_info "=========================================="
log_info "Database Schema Initialized!"
log_info "=========================================="
echo ""
log_info "Tables created: $TABLES"
echo ""
log_info "Verify schema:"
echo "  kubectl exec -n $POSTGRES_NAMESPACE $POSTGRES_POD -- \\"
echo "    env PGPASSWORD=$POSTGRES_PASSWORD psql -U $POSTGRES_USER -d $POSTGRES_DB -c '\\dt'"
echo ""
