-- Data Index v1.0.0 Database Schema with Event Staging + Triggers
-- Event-driven design for Quarkus Flow structured logging ingestion
--
-- Architecture:
--   FluentBit → workflow_instance_events (staging) → TRIGGER → workflow_instances (final)
--   FluentBit → task_execution_events (staging) → TRIGGER → task_executions (final)
--
-- Benefits:
--   - FluentBit owns event pipeline (retries, buffering, failures)
--   - PostgreSQL owns merge logic (handles out-of-order events)
--   - Data Index is passive (query-only, no event handling)

-- ============================================================
-- FINAL TABLES: workflow_instances, task_executions
-- ============================================================

CREATE TABLE IF NOT EXISTS workflow_instances (
    -- Identity
    id VARCHAR(255) PRIMARY KEY,

    -- Workflow identification (from events)
    namespace VARCHAR(255),
    name VARCHAR(255),
    version VARCHAR(255),

    -- Status & lifecycle
    status VARCHAR(50),
    start TIMESTAMP WITH TIME ZONE,
    "end" TIMESTAMP WITH TIME ZONE,
    last_update TIMESTAMP WITH TIME ZONE,

    -- Data (JSONB)
    input JSONB,
    output JSONB,

    -- Error information (embedded)
    error_type VARCHAR(255),
    error_title VARCHAR(255),
    error_detail TEXT,
    error_status INTEGER,
    error_instance VARCHAR(255),

    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_workflow_instances_namespace_name
    ON workflow_instances(namespace, name);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_status
    ON workflow_instances(status);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_start
    ON workflow_instances(start DESC);

CREATE TABLE IF NOT EXISTS task_executions (
    -- Identity
    id VARCHAR(255) PRIMARY KEY,

    -- Foreign key to workflow instance
    workflow_instance_id VARCHAR(255) NOT NULL,

    -- Task identification
    task_name VARCHAR(255),
    task_position VARCHAR(255),

    -- Lifecycle
    enter TIMESTAMP WITH TIME ZONE,
    exit TIMESTAMP WITH TIME ZONE,

    -- Error
    error_message TEXT,

    -- Data (JSONB)
    input_args JSONB,
    output_args JSONB,

    -- Metadata
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),

    -- Foreign key constraint
    CONSTRAINT fk_workflow_instance
        FOREIGN KEY (workflow_instance_id)
        REFERENCES workflow_instances(id)
        ON DELETE CASCADE
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_task_executions_workflow_instance
    ON task_executions(workflow_instance_id);

CREATE INDEX IF NOT EXISTS idx_task_executions_position
    ON task_executions(task_position);

CREATE INDEX IF NOT EXISTS idx_task_executions_enter
    ON task_executions(enter DESC);

-- ============================================================
-- STAGING TABLES: Raw events from FluentBit
-- ============================================================

CREATE TABLE IF NOT EXISTS workflow_instance_events (
    event_id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    received_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    event_type VARCHAR(255),
    event_data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_workflow_instance_events_received
    ON workflow_instance_events(received_at DESC);

CREATE TABLE IF NOT EXISTS task_execution_events (
    event_id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    received_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    event_type VARCHAR(255),
    event_data JSONB NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_task_execution_events_received
    ON task_execution_events(received_at DESC);

-- ============================================================
-- TRIGGER FUNCTION: Merge workflow instance events
-- ============================================================

CREATE OR REPLACE FUNCTION merge_workflow_instance_event()
RETURNS TRIGGER AS $$
BEGIN
    -- Insert or update workflow instance based on event data
    INSERT INTO workflow_instances (
        id,
        namespace,
        name,
        version,
        status,
        start,
        "end",
        last_update,
        input,
        output,
        error_type,
        error_title,
        error_detail,
        error_status,
        error_instance
    )
    VALUES (
        NEW.event_data->>'instanceId',
        NEW.event_data->>'workflowNamespace',
        NEW.event_data->>'workflowName',
        NEW.event_data->>'workflowVersion',
        NEW.event_data->>'status',
        (NEW.event_data->>'startTime')::TIMESTAMP WITH TIME ZONE,
        (NEW.event_data->>'endTime')::TIMESTAMP WITH TIME ZONE,
        (NEW.event_data->>'lastUpdateTime')::TIMESTAMP WITH TIME ZONE,
        NEW.event_data->'input',
        NEW.event_data->'output',
        NEW.event_data->'error'->>'type',
        NEW.event_data->'error'->>'title',
        NEW.event_data->'error'->>'detail',
        (NEW.event_data->'error'->>'status')::INTEGER,
        NEW.event_data->'error'->>'instance'
    )
    ON CONFLICT (id) DO UPDATE SET
        -- Identity fields: only fill if missing (they don't change)
        namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
        name = COALESCE(workflow_instances.name, EXCLUDED.name),
        version = COALESCE(workflow_instances.version, EXCLUDED.version),
        start = COALESCE(workflow_instances.start, EXCLUDED.start),
        input = COALESCE(workflow_instances.input, EXCLUDED.input),

        -- Status fields: always update if new event provides them
        -- (handles out-of-order: if completed arrives first, then started won't overwrite)
        status = COALESCE(EXCLUDED.status, workflow_instances.status),
        "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
        last_update = COALESCE(EXCLUDED.last_update, workflow_instances.last_update),
        output = COALESCE(EXCLUDED.output, workflow_instances.output),

        -- Error fields: always update if new event provides them
        error_type = COALESCE(EXCLUDED.error_type, workflow_instances.error_type),
        error_title = COALESCE(EXCLUDED.error_title, workflow_instances.error_title),
        error_detail = COALESCE(EXCLUDED.error_detail, workflow_instances.error_detail),
        error_status = COALESCE(EXCLUDED.error_status, workflow_instances.error_status),
        error_instance = COALESCE(EXCLUDED.error_instance, workflow_instances.error_instance),

        -- Metadata
        updated_at = NOW();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger on workflow_instance_events
DROP TRIGGER IF EXISTS workflow_instance_event_trigger ON workflow_instance_events;

CREATE TRIGGER workflow_instance_event_trigger
AFTER INSERT ON workflow_instance_events
FOR EACH ROW EXECUTE FUNCTION merge_workflow_instance_event();

-- ============================================================
-- TRIGGER FUNCTION: Merge task execution events
-- ============================================================

CREATE OR REPLACE FUNCTION merge_task_execution_event()
RETURNS TRIGGER AS $$
BEGIN
    -- Insert or update task execution based on event data
    INSERT INTO task_executions (
        id,
        workflow_instance_id,
        task_name,
        task_position,
        enter,
        exit,
        error_message,
        input_args,
        output_args
    )
    VALUES (
        NEW.event_data->>'taskExecutionId',
        NEW.event_data->>'instanceId',
        NEW.event_data->>'taskName',
        NEW.event_data->>'taskPosition',
        (NEW.event_data->>'startTime')::TIMESTAMP WITH TIME ZONE,
        (NEW.event_data->>'endTime')::TIMESTAMP WITH TIME ZONE,
        NEW.event_data->'error'->>'title',
        NEW.event_data->'input',
        NEW.event_data->'output'
    )
    ON CONFLICT (id) DO UPDATE SET
        -- Task identity fields: only fill if missing
        workflow_instance_id = COALESCE(task_executions.workflow_instance_id, EXCLUDED.workflow_instance_id),
        task_name = COALESCE(task_executions.task_name, EXCLUDED.task_name),
        task_position = COALESCE(task_executions.task_position, EXCLUDED.task_position),
        enter = COALESCE(task_executions.enter, EXCLUDED.enter),
        input_args = COALESCE(task_executions.input_args, EXCLUDED.input_args),

        -- Completion fields: always update if provided
        exit = COALESCE(EXCLUDED.exit, task_executions.exit),
        output_args = COALESCE(EXCLUDED.output_args, task_executions.output_args),
        error_message = COALESCE(EXCLUDED.error_message, task_executions.error_message),

        -- Metadata
        updated_at = NOW();

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Create trigger on task_execution_events
DROP TRIGGER IF EXISTS task_execution_event_trigger ON task_execution_events;

CREATE TRIGGER task_execution_event_trigger
AFTER INSERT ON task_execution_events
FOR EACH ROW EXECUTE FUNCTION merge_task_execution_event();

-- ============================================================
-- COMMENTS (Documentation)
-- ============================================================

-- Staging tables
COMMENT ON TABLE workflow_instance_events IS
    'Staging table for raw workflow instance events from FluentBit. Trigger merges into workflow_instances.';

COMMENT ON TABLE task_execution_events IS
    'Staging table for raw task execution events from FluentBit. Trigger merges into task_executions.';

COMMENT ON COLUMN workflow_instance_events.event_data IS
    'Complete Quarkus Flow event as JSONB (eventType, instanceId, workflowName, status, etc.)';

COMMENT ON COLUMN task_execution_events.event_data IS
    'Complete Quarkus Flow event as JSONB (eventType, taskExecutionId, taskName, taskPosition, etc.)';

-- Final tables (same as before)
COMMENT ON TABLE workflow_instances IS
    'Workflow instance executions (merged from workflow_instance_events via trigger)';

COMMENT ON TABLE task_executions IS
    'Task execution instances (merged from task_execution_events via trigger)';
