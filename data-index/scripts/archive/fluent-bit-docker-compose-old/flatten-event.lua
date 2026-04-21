-- Flatten nested JSON fields in Quarkus Flow events
-- Purpose: FluentBit's ${field} syntax doesn't support nested JSON (e.g., ${error.type})
--          This script flattens error.* and input/output fields for PostgreSQL UPSERT

function flatten_event(tag, timestamp, record)
    local new_record = record

    -- Flatten error object: error.type → error_type, error.title → error_title, etc.
    if new_record["error"] ~= nil and type(new_record["error"]) == "table" then
        if new_record["error"]["type"] ~= nil then
            new_record["error_type"] = new_record["error"]["type"]
        end
        if new_record["error"]["title"] ~= nil then
            new_record["error_title"] = new_record["error"]["title"]
        end
        if new_record["error"]["detail"] ~= nil then
            new_record["error_detail"] = new_record["error"]["detail"]
        end
        if new_record["error"]["status"] ~= nil then
            new_record["error_status"] = new_record["error"]["status"]
        end
        if new_record["error"]["instance"] ~= nil then
            new_record["error_instance"] = new_record["error"]["instance"]
        end
    end

    -- Convert input/output JSON objects to strings for JSONB casting
    -- PostgreSQL expects: '{"key": "value"}'::jsonb
    if new_record["input"] ~= nil and type(new_record["input"]) == "table" then
        new_record["input_json"] = cb_print(new_record["input"])
    end

    if new_record["output"] ~= nil and type(new_record["output"]) == "table" then
        new_record["output_json"] = cb_print(new_record["output"])
    end

    -- Return: code=2 (keep record), timestamp, modified record
    return 2, timestamp, new_record
end
