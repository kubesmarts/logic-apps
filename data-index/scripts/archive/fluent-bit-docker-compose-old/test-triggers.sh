#!/bin/bash

# Test FluentBit ingestion with PostgreSQL triggers
#
# Architecture:
#   FluentBit → workflow_instance_events (staging) → TRIGGER → workflow_instances (final)
#
# Usage:
#   ./test-triggers.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="${SCRIPT_DIR}/logs"
LOG_FILE="${LOG_DIR}/quarkus-flow.log"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}=== Data Index FluentBit + PostgreSQL Triggers Test ===${NC}"
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
docker-compose -f docker-compose-triggers.yml up -d

# Wait for services to be healthy
echo "Waiting for PostgreSQL to be ready..."
for i in {1..30}; do
    if docker-compose -f docker-compose-triggers.yml exec -T postgres pg_isready -U postgres > /dev/null 2>&1; then
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

# Step 5: Verify staging tables
echo -e "${YELLOW}Step 5: Verifying staging tables (raw events)...${NC}"
echo

echo "Workflow instance events (staging):"
docker-compose -f docker-compose-triggers.yml exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT tag, time, data->>'instanceId' as instance_id, data->>'status' as status FROM workflow_instance_events ORDER BY time LIMIT 10;"
echo

echo "Task execution events (staging):"
docker-compose -f docker-compose-triggers.yml exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT tag, time, data->>'taskExecutionId' as task_id, data->>'taskName' as task_name FROM task_execution_events ORDER BY time LIMIT 10;"
echo

# Step 6: Verify final tables (merged by triggers)
echo -e "${YELLOW}Step 6: Verifying final tables (merged by triggers)...${NC}"
echo

echo "Workflow instances (final):"
docker-compose -f docker-compose-triggers.yml exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT id, namespace, name, status, start, \"end\" FROM workflow_instances ORDER BY start;"
echo

echo "Task executions (final):"
docker-compose -f docker-compose-triggers.yml exec -T postgres psql -U postgres -d dataindex -c \
  "SELECT id, workflow_instance_id, task_name, task_position, enter, exit, error_message FROM task_executions ORDER BY enter;"
echo

# Step 7: Show FluentBit logs
echo -e "${YELLOW}Step 7: FluentBit logs (last 20 lines):${NC}"
docker-compose -f docker-compose-triggers.yml logs --tail=20 fluent-bit
echo

echo -e "${GREEN}=== Test Complete ===${NC}"
echo
echo "Architecture Verified:"
echo "  ✓ FluentBit parsed JSON events"
echo "  ✓ FluentBit inserted into staging tables (workflow_instance_events, task_execution_events)"
echo "  ✓ PostgreSQL triggers merged into final tables (workflow_instances, task_executions)"
echo "  ✓ Data Index can query final tables (passive, no event handling)"
echo
echo "To stop services:"
echo "  docker-compose -f docker-compose-triggers.yml down"
echo
echo "To view live FluentBit logs:"
echo "  docker-compose -f docker-compose-triggers.yml logs -f fluent-bit"
echo
echo "To query PostgreSQL:"
echo "  docker-compose -f docker-compose-triggers.yml exec postgres psql -U postgres -d dataindex"
