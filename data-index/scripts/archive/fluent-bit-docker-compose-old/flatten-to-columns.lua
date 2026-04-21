-- ============================================================================
-- FluentBit Lua Filter - Flatten Quarkus Flow Events to Event Table Columns
-- ============================================================================
--
-- Purpose: Transform nested Quarkus Flow JSON events into flat structure
--          suitable for direct insertion into event tables.
--
-- Input (Quarkus Flow structured logging):
--   {
--     "eventType": "io.serverlessworkflow.workflow.started.v1",
--     "timestamp": "2026-04-17T10:00:00Z",
--     "instanceId": "abc-123",
--     "workflowNamespace": "production",
--     "workflowName": "order-processing",
--     "workflowVersion": "1.0.0",
--     "status": "RUNNING",
--     "startTime": "2026-04-17T10:00:00Z",
--     "input": {"orderId": "12345", "amount": 100.50}
--   }
--
-- Output (flattened for event table):
--   {
--     "event_type": "started",
--     "event_time": "2026-04-17T10:00:00Z",
--     "instance_id": "abc-123",
--     "workflow_namespace": "production",
--     "workflow_name": "order-processing",
--     "workflow_version": "1.0.0",
--     "status": "RUNNING",
--     "start_time": "2026-04-17T10:00:00Z",
--     "input_data": {"orderId": "12345", "amount": 100.50}
--   }
--
-- FluentBit automatically maps these fields to table columns.
-- ============================================================================

-- ============================================================================
-- Workflow Event Flattening
-- ============================================================================
function flatten_workflow_event(tag, timestamp, record)
    local eventType = record["eventType"]

    if not eventType then
        -- Not a Quarkus Flow event, skip
        return -1, 0, 0
    end

    -- Only process workflow events
    if not string.find(eventType, "%.workflow%.") then
        return -1, 0, 0
    end

    local flatRecord = {}

    -- ========================================================================
    -- Event Metadata
    -- ========================================================================
    flatRecord["event_type"] = extract_event_subtype(eventType)
    flatRecord["event_time"] = record["timestamp"]

    -- ========================================================================
    -- Workflow Identity (always present)
    -- ========================================================================
    flatRecord["instance_id"] = record["instanceId"]

    -- ========================================================================
    -- Fields from 'started' event
    -- ========================================================================
    if record["workflowNamespace"] then
        flatRecord["workflow_namespace"] = record["workflowNamespace"]
    end

    if record["workflowName"] then
        flatRecord["workflow_name"] = record["workflowName"]
    end

    if record["workflowVersion"] then
        flatRecord["workflow_version"] = record["workflowVersion"]
    end

    if record["startTime"] then
        flatRecord["start_time"] = record["startTime"]
    end

    if record["input"] then
        -- Keep complex data as JSON (FluentBit will store as JSONB)
        flatRecord["input_data"] = record["input"]
    end

    -- ========================================================================
    -- Fields from 'completed' event
    -- ========================================================================
    if record["endTime"] then
        flatRecord["end_time"] = record["endTime"]
    end

    if record["output"] then
        flatRecord["output_data"] = record["output"]
    end

    -- ========================================================================
    -- Fields from 'faulted' event
    -- ========================================================================
    -- Error object: {"type": "...", "title": "...", "detail": "...", "status": 500}
    if record["error"] then
        local error = record["error"]

        if error["type"] then
            flatRecord["error_type"] = error["type"]
        end

        if error["title"] then
            flatRecord["error_title"] = error["title"]
        end

        if error["detail"] then
            flatRecord["error_detail"] = error["detail"]
        end

        if error["status"] then
            flatRecord["error_status"] = error["status"]
        end
    end

    -- ========================================================================
    -- Common Fields
    -- ========================================================================
    if record["status"] then
        flatRecord["status"] = record["status"]
    end

    -- Return: code (1=modified), timestamp, modified record
    return 1, timestamp, flatRecord
end

-- ============================================================================
-- Task Event Flattening
-- ============================================================================
function flatten_task_event(tag, timestamp, record)
    local eventType = record["eventType"]

    if not eventType then
        return -1, 0, 0
    end

    -- Only process task events
    if not string.find(eventType, "%.task%.") then
        return -1, 0, 0
    end

    local flatRecord = {}

    -- ========================================================================
    -- Event Metadata
    -- ========================================================================
    flatRecord["event_type"] = extract_event_subtype(eventType)
    flatRecord["event_time"] = record["timestamp"]

    -- ========================================================================
    -- Task Identity
    -- ========================================================================
    flatRecord["instance_id"] = record["instanceId"]
    flatRecord["task_execution_id"] = record["taskExecutionId"]

    if record["taskPosition"] then
        flatRecord["task_position"] = record["taskPosition"]
    end

    if record["taskName"] then
        flatRecord["task_name"] = record["taskName"]
    end

    -- ========================================================================
    -- Fields from 'started' event
    -- ========================================================================
    if record["startTime"] then
        flatRecord["start_time"] = record["startTime"]
    end

    if record["input"] then
        flatRecord["input_args"] = record["input"]
    end

    -- ========================================================================
    -- Fields from 'completed' event
    -- ========================================================================
    if record["endTime"] then
        flatRecord["end_time"] = record["endTime"]
    end

    if record["output"] then
        flatRecord["output_args"] = record["output"]
    end

    -- ========================================================================
    -- Fields from 'faulted' event
    -- ========================================================================
    if record["error"] then
        local error = record["error"]

        -- For tasks, just store the error title as the error message
        if error["title"] then
            flatRecord["error_message"] = error["title"]
        elseif error["detail"] then
            flatRecord["error_message"] = error["detail"]
        end
    end

    return 1, timestamp, flatRecord
end

-- ============================================================================
-- Helper: Extract Event Subtype
-- ============================================================================
-- Extract subtype from full event type:
-- "io.serverlessworkflow.workflow.started.v1" → "started"
-- "io.serverlessworkflow.task.completed.v1" → "completed"
-- "io.serverlessworkflow.workflow.faulted.v1" → "faulted"
-- ============================================================================
function extract_event_subtype(eventType)
    if string.find(eventType, "%.started%.") then
        return "started"
    elseif string.find(eventType, "%.completed%.") then
        return "completed"
    elseif string.find(eventType, "%.faulted%.") then
        return "faulted"
    elseif string.find(eventType, "%.status%.changed%.") then
        return "status_changed"
    else
        return "unknown"
    end
end

-- ============================================================================
-- Usage Notes
-- ============================================================================
--
-- FluentBit Configuration:
--
-- [FILTER]
--     Name    lua
--     Match   workflow.instance.*
--     Script  flatten-to-columns.lua
--     Call    flatten_workflow_event
--
-- [FILTER]
--     Name    lua
--     Match   workflow.task.*
--     Script  flatten-to-columns.lua
--     Call    flatten_task_event
--
-- [OUTPUT]
--     Name            pgsql
--     Match           workflow.instance.*
--     Host            localhost
--     Port            5432
--     Database        dataindex
--     Table           workflow_instance_events
--     # FluentBit automatically maps flatRecord fields to table columns
--
-- [OUTPUT]
--     Name            pgsql
--     Match           workflow.task.*
--     Host            localhost
--     Port            5432
--     Database        dataindex
--     Table           task_execution_events
--
-- ============================================================================
--
-- Testing:
--
-- Use sample Quarkus Flow events from integration tests:
-- - data-index-integration-tests/target/quarkus-flow-events.log
--
-- Test command:
-- fluent-bit -c fluent-bit-test.conf
--
-- ============================================================================
