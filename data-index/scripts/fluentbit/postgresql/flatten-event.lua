-- Flatten nested JSON fields in Quarkus Flow events
-- Purpose: Map Quarkus Flow event fields to PostgreSQL schema and preserve
--          eventType for rewrite_tag routing.
--
-- Timestamp format: Requires quarkus-flow 0.9.0+ with epoch format
--   Configure: quarkus.flow.structured-logging.timestamp-format=epoch-seconds
--   Output: 1776897995.238552 (epoch seconds with nanosecond precision)
--   Required for: FluentBit pgsql plugin TIMESTAMP WITH TIME ZONE columns

function flatten_event(tag, timestamp, record)
    -- Create new record with only fields that match PostgreSQL staging tables
    -- This prevents FluentBit pgsql plugin from trying to insert unknown columns
    local new_record = {}

    -- IMPORTANT: Preserve eventType for rewrite_tag routing
    if record["eventType"] ~= nil then
        new_record["eventType"] = record["eventType"]
    end

    -- ========================================================================
    -- Common fields (both workflow and task events)
    -- ========================================================================

    if record["instanceId"] ~= nil then
        new_record["instance_id"] = record["instanceId"]
    end

    if record["status"] ~= nil then
        new_record["status"] = record["status"]
    end

    if record["startTime"] ~= nil then
        new_record["start"] = record["startTime"]
    end

    if record["endTime"] ~= nil then
        new_record["end"] = record["endTime"]
    end

    -- ========================================================================
    -- Workflow-specific fields (workflow_events table)
    -- ========================================================================

    -- Also map to 'id' for backward compatibility with workflow_events
    if record["instanceId"] ~= nil then
        new_record["id"] = record["instanceId"]
    end

    if record["workflowNamespace"] ~= nil then
        new_record["namespace"] = record["workflowNamespace"]
    end

    if record["workflowName"] ~= nil then
        new_record["name"] = record["workflowName"]
    end

    if record["workflowVersion"] ~= nil then
        new_record["version"] = record["workflowVersion"]
    end

    if record["lastUpdateTime"] ~= nil then
        new_record["last_update"] = record["lastUpdateTime"]
    end

    -- Preserve JSONB fields (input, output) - workflow events only
    if record["input"] ~= nil then
        new_record["input"] = record["input"]
    end

    if record["output"] ~= nil then
        new_record["output"] = record["output"]
    end

    -- Flatten error object - workflow events
    if record["error"] ~= nil and type(record["error"]) == "table" then
        if record["error"]["type"] ~= nil then
            new_record["error_type"] = record["error"]["type"]
        end
        if record["error"]["title"] ~= nil then
            new_record["error_title"] = record["error"]["title"]
        end
        if record["error"]["detail"] ~= nil then
            new_record["error_detail"] = record["error"]["detail"]
        end
        if record["error"]["status"] ~= nil then
            new_record["error_status"] = record["error"]["status"]
        end
        if record["error"]["instance"] ~= nil then
            new_record["error_instance"] = record["error"]["instance"]
        end
    end

    -- ========================================================================
    -- Task-specific fields (task_events table)
    -- ========================================================================

    if record["taskExecutionId"] ~= nil then
        new_record["task_execution_id"] = record["taskExecutionId"]
    end

    if record["taskName"] ~= nil then
        new_record["task_name"] = record["taskName"]
    end

    if record["taskPosition"] ~= nil then
        new_record["task_position"] = record["taskPosition"]
    end

    -- Task payloads (JSONB) - quarkus.flow.structured-logging.include-task-payloads=true
    -- Task events now include input/output like workflow events
    if record["input"] ~= nil then
        new_record["input"] = record["input"]
    end

    if record["output"] ~= nil then
        new_record["output"] = record["output"]
    end

    -- Return: code=2 (keep record), timestamp, modified record
    return 2, timestamp, new_record
end
