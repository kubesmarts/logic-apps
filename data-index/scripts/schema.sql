-- Data Index v1.0.0 Database Schema
-- Event-driven design for Quarkus Flow structured logging ingestion
--
-- Tables:
--   - workflow_instances: Workflow instance executions
--   - task_executions: Task execution instances
--
-- Design Principle: Every column maps directly to Quarkus Flow events

-- ============================================================
-- TABLE: workflow_instances
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
    error_instance VARCHAR(255)
);

-- Indexes for common queries
CREATE INDEX IF NOT EXISTS idx_workflow_instances_namespace_name
    ON workflow_instances(namespace, name);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_status
    ON workflow_instances(status);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_start
    ON workflow_instances(start DESC);

-- ============================================================
-- TABLE: task_executions
-- ============================================================
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
-- COMMENTS (Documentation)
-- ============================================================

-- workflow_instances table
COMMENT ON TABLE workflow_instances IS
    'Workflow instance executions ingested from Quarkus Flow structured logging events';

COMMENT ON COLUMN workflow_instances.id IS
    'Workflow instance ID (instanceId from events)';
COMMENT ON COLUMN workflow_instances.namespace IS
    'Workflow namespace (workflowNamespace from events)';
COMMENT ON COLUMN workflow_instances.name IS
    'Workflow name (workflowName from events)';
COMMENT ON COLUMN workflow_instances.version IS
    'Workflow version (workflowVersion from events)';
COMMENT ON COLUMN workflow_instances.status IS
    'Instance status: RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED';
COMMENT ON COLUMN workflow_instances.start IS
    'Start time from workflow.instance.started event';
COMMENT ON COLUMN workflow_instances."end" IS
    'End time from workflow.instance.completed/faulted event';
COMMENT ON COLUMN workflow_instances.last_update IS
    'Last update time from workflow.instance.status.changed event';
COMMENT ON COLUMN workflow_instances.input IS
    'Workflow input data from workflow.instance.started event';
COMMENT ON COLUMN workflow_instances.output IS
    'Workflow output data from workflow.instance.completed event';
COMMENT ON COLUMN workflow_instances.error_type IS
    'Error type from workflow.instance.faulted event (system, business, timeout, communication)';
COMMENT ON COLUMN workflow_instances.error_title IS
    'Error title from workflow.instance.faulted event';
COMMENT ON COLUMN workflow_instances.error_detail IS
    'Error detail from workflow.instance.faulted event';
COMMENT ON COLUMN workflow_instances.error_status IS
    'Error HTTP status code from workflow.instance.faulted event';
COMMENT ON COLUMN workflow_instances.error_instance IS
    'Error instance ID from workflow.instance.faulted event';

-- task_executions table
COMMENT ON TABLE task_executions IS
    'Task execution instances ingested from Quarkus Flow structured logging events';

COMMENT ON COLUMN task_executions.id IS
    'Task execution ID (taskExecutionId from events)';
COMMENT ON COLUMN task_executions.workflow_instance_id IS
    'Foreign key to workflow_instances.id';
COMMENT ON COLUMN task_executions.task_name IS
    'Task name from workflow.task.started event';
COMMENT ON COLUMN task_executions.task_position IS
    'Task position as JSONPointer (e.g., /do/0, /fork/branches/0/do/1)';
COMMENT ON COLUMN task_executions.enter IS
    'Task start time from workflow.task.started event';
COMMENT ON COLUMN task_executions.exit IS
    'Task end time from workflow.task.completed/faulted event';
COMMENT ON COLUMN task_executions.error_message IS
    'Error message from workflow.task.faulted event';
COMMENT ON COLUMN task_executions.input_args IS
    'Task input arguments from workflow.task.started event';
COMMENT ON COLUMN task_executions.output_args IS
    'Task output arguments from workflow.task.completed event';
