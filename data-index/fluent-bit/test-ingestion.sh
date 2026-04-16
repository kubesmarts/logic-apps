#!/bin/bash

# Test FluentBit ingestion with sample Quarkus Flow events
#
# Usage:
#   ./test-ingestion.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
LOG_FILE="${LOG_DIR}/quarkus-flow.log"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Data Index FluentBit Test ===${NC}"
echo

# Step 1: Create logs directory
echo -e "${YELLOW}Step 1: Creating logs directory...${NC}"
mkdir -p "${LOG_DIR}"
echo "Created: ${LOG_DIR}"
echo

# Step 2: Copy sample events to log file
echo -e "${YELLOW}Step 2: Copying sample events to log file...${NC}"
cp "${SCRIPT_DIR}/sample-events.jsonl" "${LOG_FILE}"
echo "Created: ${LOG_FILE}"
echo "Event count: $(wc -l < "${LOG_FILE}") events"
echo

# Step 3: Start Docker Compose
echo -e "${YELLOW}Step 3: Starting PostgreSQL + FluentBit...${NC}"
cd "${SCRIPT_DIR}"
docker-compose up -d

# Wait for services to be healthy
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker-compose exec -T postgres pg_isready -U postgres > /dev/null 2>&1; then
        echo -e "${GREEN}PostgreSQL ready${NC}"
        break
    fi
    sleep 1
done
echo

# Step 4: Wait for FluentBit to process events
echo -e "${YELLOW}Step 4: Waiting for FluentBit to process events...${NC}"
sleep 5
echo

# Step 5: Verify ingestion
echo -e "${YELLOW}Step 5: Verifying data ingestion...${NC}"
echo

echo "Workflow instances:"
docker-compose exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT id, name, status, start FROM workflow_instances ORDER BY start;"
echo

echo "Task executions:"
docker-compose exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT id, task_name, task_position, enter, error_message FROM task_executions ORDER BY enter;"
echo

# Step 6: Show FluentBit logs
echo -e "${YELLOW}Step 6: FluentBit logs (last 20 lines):${NC}"
docker-compose logs --tail=20 fluent-bit
echo

echo -e "${GREEN}=== Test Complete ===${NC}"
echo
echo "To stop services:"
echo "  docker-compose down"
echo
echo "To view live FluentBit logs:"
echo "  docker-compose logs -f fluent-bit"
echo
echo "To query PostgreSQL:"
echo "  docker-compose exec postgres psql -U postgres -d dataindex"
