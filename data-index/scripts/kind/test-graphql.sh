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

# Test counter
TESTS_RUN=0
TESTS_PASSED=0
TESTS_FAILED=0

# Test function
test_query() {
    local description="$1"
    local query="$2"
    local expected_pattern="$3"

    TESTS_RUN=$((TESTS_RUN + 1))
    log_step "Test $TESTS_RUN: $description"

    local response
    response=$(curl -s http://localhost:30080/graphql \
        -H "Content-Type: application/json" \
        -d "{\"query\":\"$query\"}")

    if echo "$response" | grep -q "$expected_pattern"; then
        log_info "✓ PASS"
        TESTS_PASSED=$((TESTS_PASSED + 1))
        return 0
    else
        log_error "✗ FAIL"
        log_error "Expected pattern: $expected_pattern"
        log_error "Got: $response"
        TESTS_FAILED=$((TESTS_FAILED + 1))
        return 1
    fi
}

# Print test summary
print_summary() {
    echo ""
    echo "========================================"
    echo "Test Summary"
    echo "========================================"
    echo "Tests run:    $TESTS_RUN"
    echo "Tests passed: $TESTS_PASSED"
    echo "Tests failed: $TESTS_FAILED"
    echo ""

    if [ $TESTS_FAILED -eq 0 ]; then
        log_info "All tests passed! ✓"
        return 0
    else
        log_error "Some tests failed!"
        return 1
    fi
}

# Main test execution
main() {
    log_info "Testing Data Index GraphQL API in KIND cluster"
    echo ""

    # Test 1: Health check
    log_step "Test 1: Health endpoint"
    if curl -sf http://localhost:30080/q/health > /dev/null; then
        log_info "✓ Health endpoint responding"
        TESTS_RUN=$((TESTS_RUN + 1))
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        log_error "✗ Health endpoint not responding"
        TESTS_RUN=$((TESTS_RUN + 1))
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    echo ""

    # Test 2: GraphQL schema introspection
    test_query "GraphQL schema introspection" \
        "{ __schema { queryType { name } } }" \
        '"queryType".*"name".*"Query"'
    echo ""

    # Test 3: List workflow instances (empty)
    test_query "List workflow instances (empty database)" \
        "query { getWorkflowInstances { id name } }" \
        '"getWorkflowInstances".*\[\]'
    echo ""

    # Test 4: List task executions (empty)
    test_query "List task executions (empty database)" \
        "query { getTaskExecutions { id taskName } }" \
        '"getTaskExecutions".*\[\]'
    echo ""

    # Test 5: Get non-existent workflow instance
    test_query "Get non-existent workflow instance" \
        "query { getWorkflowInstance(id: \\\"test-123\\\") { id } }" \
        '"getWorkflowInstance".*null'
    echo ""

    # Test 6: Insert test data
    log_step "Test 6: Insert test workflow instance data"
    kubectl exec -n postgresql postgresql-0 -- bash -c "PGPASSWORD=dataindex123 psql -U dataindex -d dataindex" <<'EOF'
INSERT INTO workflow_instances (
    id, namespace, name, version, status, start, last_update,
    input, output
) VALUES (
    'test-instance-001',
    'test',
    'hello-world',
    '1.0.0',
    'COMPLETED',
    NOW() - INTERVAL '5 minutes',
    NOW(),
    '{"greeting": "Hello"}'::jsonb,
    '{"message": "Hello from Quarkus Flow!"}'::jsonb
) ON CONFLICT (id) DO NOTHING;

INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit
) VALUES (
    'test-task-001',
    'test-instance-001',
    'setMessage',
    'do/0',
    NOW() - INTERVAL '5 minutes',
    NOW() - INTERVAL '4 minutes'
) ON CONFLICT (id) DO NOTHING;
EOF

    if [ $? -eq 0 ]; then
        log_info "✓ Test data inserted"
        TESTS_RUN=$((TESTS_RUN + 1))
        TESTS_PASSED=$((TESTS_PASSED + 1))
    else
        log_error "✗ Failed to insert test data"
        TESTS_RUN=$((TESTS_RUN + 1))
        TESTS_FAILED=$((TESTS_FAILED + 1))
    fi
    echo ""

    # Wait for data to be visible
    sleep 2

    # Test 7: Query workflow instance by ID
    test_query "Get workflow instance by ID" \
        "query { getWorkflowInstance(id: \\\"test-instance-001\\\") { id name namespace version status } }" \
        '"id".*"test-instance-001"'
    echo ""

    # Test 8: List workflow instances (with data)
    test_query "List workflow instances (with data)" \
        "query { getWorkflowInstances { id name namespace status } }" \
        '"test-instance-001"'
    echo ""

    # Test 9: Query task executions by workflow instance
    test_query "Get task executions by workflow instance" \
        "query { getTaskExecutionsByWorkflowInstance(workflowInstanceId: \\\"test-instance-001\\\") { id taskName } }" \
        '"test-task-001"'
    echo ""

    # Test 10: Filter workflow instances by status
    test_query "Filter workflow instances by status" \
        "query { getWorkflowInstances(filter: { status: COMPLETED }) { id status } }" \
        '"status".*"COMPLETED"'
    echo ""

    # Test 11: Sort workflow instances by name
    test_query "Sort workflow instances by name ascending" \
        "query { getWorkflowInstances(orderBy: { name: ASC }) { id name } }" \
        '"name".*"hello-world"'
    echo ""

    # Test 12: Pagination - limit results
    test_query "Pagination - limit results" \
        "query { getWorkflowInstances(limit: 10) { id } }" \
        '"id".*"test-instance-001"'
    echo ""

    print_summary
}

# Run main function
main "$@"
