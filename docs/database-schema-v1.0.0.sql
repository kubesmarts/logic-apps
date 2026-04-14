-- PostgreSQL Schema for Data Index v1.0.0 (Serverless Workflow 1.0.0)
--
-- This schema is designed to store workflow execution data written by FluentBit
-- from Quarkus Flow structured logging events (JSON format).
--
-- Design principles:
-- 1. Event-sourced: capture all state transitions via workflow_events and task_events tables
-- 2. Current state: materialized views for efficient GraphQL queries
-- 3. No UserTask support (removed from SW 1.0.0)
-- 4. FluentBit writes directly to these tables (no event processing in Data Index)

-- =============================================================================
-- Core Tables (written by FluentBit)
-- =============================================================================

-- Workflow Definitions (deployed workflows)
CREATE TABLE workflows (
    namespace VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    dsl VARCHAR(20) NOT NULL DEFAULT '1.0.0',
    title VARCHAR(500),
    summary TEXT,
    tags JSONB,
    metadata JSONB,
    source BYTEA NOT NULL,
    endpoint VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (namespace, name, version)
);

-- Workflow Instances (current state)
CREATE TABLE workflow_instances (
    instance_id VARCHAR(255) PRIMARY KEY,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_version VARCHAR(50),
    status VARCHAR(50) NOT NULL, -- RUNNING, COMPLETED, FAULTED, CANCELLED, SUSPENDED
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    last_update_time TIMESTAMP NOT NULL,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    error_details JSONB,

    -- v0.8 API compatibility fields
    endpoint VARCHAR(500),
    business_key VARCHAR(255),
    parent_instance_id VARCHAR(255),
    root_instance_id VARCHAR(255),
    created_by VARCHAR(255),
    updated_by VARCHAR(255),

    -- Audit timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (workflow_namespace, workflow_name, workflow_version)
        REFERENCES workflows(namespace, name, version) ON DELETE CASCADE
);

-- Workflow Instance Events (event sourcing - all state transitions)
CREATE TABLE workflow_events (
    id BIGSERIAL PRIMARY KEY,
    instance_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL, -- workflow.instance.{started,completed,failed,cancelled,suspended,resumed,status.changed}
    event_timestamp TIMESTAMP NOT NULL,
    workflow_namespace VARCHAR(255) NOT NULL,
    workflow_name VARCHAR(255) NOT NULL,
    workflow_version VARCHAR(50),
    status VARCHAR(50) NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    last_update_time TIMESTAMP,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    error_details JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(instance_id) ON DELETE CASCADE
);

-- Task Executions (current state)
CREATE TABLE task_executions (
    task_execution_id VARCHAR(255) PRIMARY KEY,
    instance_id VARCHAR(255) NOT NULL,
    task_name VARCHAR(255) NOT NULL,
    task_position INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL, -- RUNNING, COMPLETED, FAILED, CANCELLED, SUSPENDED
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP,
    last_update_time TIMESTAMP NOT NULL,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    error_details JSONB,
    retry_count INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(instance_id) ON DELETE CASCADE
);

-- Task Events (event sourcing - all task state transitions)
CREATE TABLE task_events (
    id BIGSERIAL PRIMARY KEY,
    task_execution_id VARCHAR(255) NOT NULL,
    instance_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(100) NOT NULL, -- workflow.task.{started,completed,failed,cancelled,suspended,resumed,retried}
    event_timestamp TIMESTAMP NOT NULL,
    task_name VARCHAR(255) NOT NULL,
    task_position INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL,
    start_time TIMESTAMP,
    end_time TIMESTAMP,
    last_update_time TIMESTAMP,
    input_data JSONB,
    output_data JSONB,
    error_message TEXT,
    error_details JSONB,
    retry_count INTEGER,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (task_execution_id) REFERENCES task_executions(task_execution_id) ON DELETE CASCADE,
    FOREIGN KEY (instance_id) REFERENCES workflow_instances(instance_id) ON DELETE CASCADE
);

-- Jobs (Timers and Scheduled Tasks)
CREATE TABLE jobs (
    job_id VARCHAR(255) PRIMARY KEY,
    workflow_instance_id VARCHAR(255),
    task_execution_id VARCHAR(255),
    job_type VARCHAR(50) NOT NULL, -- WAIT_TASK, TIMEOUT, SCHEDULED_WORKFLOW
    status VARCHAR(50) NOT NULL, -- SCHEDULED, RUNNING, EXECUTED, FAILED, CANCELLED
    expiration_time TIMESTAMP NOT NULL,
    callback_endpoint VARCHAR(500),
    repeat_interval BIGINT,
    repeat_limit INTEGER,
    execution_count INTEGER DEFAULT 0,
    priority INTEGER DEFAULT 0,
    error_message TEXT,
    last_update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (workflow_instance_id)
        REFERENCES workflow_instances(instance_id) ON DELETE CASCADE,
    FOREIGN KEY (task_execution_id)
        REFERENCES task_executions(task_execution_id) ON DELETE CASCADE
);

-- =============================================================================
-- Indexes for Query Performance
-- =============================================================================

-- Workflows indexes
CREATE INDEX idx_workflows_name ON workflows(name);
CREATE INDEX idx_workflows_namespace ON workflows(namespace);

-- Workflow Instances indexes
CREATE INDEX idx_workflow_instances_namespace_name ON workflow_instances(workflow_namespace, workflow_name);
CREATE INDEX idx_workflow_instances_status ON workflow_instances(status);
CREATE INDEX idx_workflow_instances_start_time ON workflow_instances(start_time);
CREATE INDEX idx_workflow_instances_end_time ON workflow_instances(end_time);
CREATE INDEX idx_workflow_instances_last_update ON workflow_instances(last_update_time);
CREATE INDEX idx_workflow_instances_business_key ON workflow_instances(business_key);
CREATE INDEX idx_workflow_instances_parent ON workflow_instances(parent_instance_id);
CREATE INDEX idx_workflow_instances_root ON workflow_instances(root_instance_id);

-- Workflow Events indexes
CREATE INDEX idx_workflow_events_instance_id ON workflow_events(instance_id);
CREATE INDEX idx_workflow_events_type ON workflow_events(event_type);
CREATE INDEX idx_workflow_events_timestamp ON workflow_events(event_timestamp);
CREATE INDEX idx_workflow_events_namespace_name ON workflow_events(workflow_namespace, workflow_name);

-- Task Executions indexes
CREATE INDEX idx_task_executions_instance_id ON task_executions(instance_id);
CREATE INDEX idx_task_executions_status ON task_executions(status);
CREATE INDEX idx_task_executions_start_time ON task_executions(start_time);
CREATE INDEX idx_task_executions_task_name ON task_executions(task_name);

-- Task Events indexes
CREATE INDEX idx_task_events_execution_id ON task_events(task_execution_id);
CREATE INDEX idx_task_events_instance_id ON task_events(instance_id);
CREATE INDEX idx_task_events_type ON task_events(event_type);
CREATE INDEX idx_task_events_timestamp ON task_events(event_timestamp);

-- Jobs indexes
CREATE INDEX idx_jobs_instance ON jobs(workflow_instance_id);
CREATE INDEX idx_jobs_task_execution ON jobs(task_execution_id);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_expiration_time ON jobs(expiration_time);
CREATE INDEX idx_jobs_type ON jobs(job_type);

-- =============================================================================
-- Triggers for Auto-Update Timestamps
-- =============================================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER update_workflows_updated_at
    BEFORE UPDATE ON workflows
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_workflow_instances_updated_at
    BEFORE UPDATE ON workflow_instances
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_task_executions_updated_at
    BEFORE UPDATE ON task_executions
    FOR EACH ROW
    EXECUTE FUNCTION update_updated_at_column();

-- =============================================================================
-- Views for GraphQL Queries
-- =============================================================================

-- Active Workflows (commonly queried)
CREATE VIEW active_workflows AS
SELECT * FROM workflow_instances
WHERE status IN ('RUNNING', 'SUSPENDED');

-- Completed Workflows (commonly queried)
CREATE VIEW completed_workflows AS
SELECT * FROM workflow_instances
WHERE status IN ('COMPLETED', 'FAULTED', 'CANCELLED');

-- Workflow with Task Count
CREATE VIEW workflow_instances_with_task_count AS
SELECT
    w.*,
    COUNT(t.task_execution_id) as task_count,
    COUNT(CASE WHEN t.status = 'COMPLETED' THEN 1 END) as completed_tasks,
    COUNT(CASE WHEN t.status = 'FAILED' THEN 1 END) as failed_tasks
FROM workflow_instances w
LEFT JOIN task_executions t ON w.instance_id = t.instance_id
GROUP BY w.instance_id;

-- =============================================================================
-- Triggers to Materialize Current State from Events
-- =============================================================================

-- Trigger function for workflow events -> workflow_instances
CREATE OR REPLACE FUNCTION process_workflow_event()
RETURNS TRIGGER AS $$
DECLARE
    v_endpoint VARCHAR(500);
    v_business_key VARCHAR(255);
    v_parent_instance_id VARCHAR(255);
    v_root_instance_id VARCHAR(255);
    v_created_by VARCHAR(255);
    v_updated_by VARCHAR(255);
BEGIN
    -- Extract v0.8 compatibility fields from event_details JSONB if present
    v_endpoint := NEW.error_details->>'endpoint';
    v_business_key := NEW.error_details->>'businessKey';
    v_parent_instance_id := NEW.error_details->>'parentInstanceId';
    v_root_instance_id := NEW.error_details->>'rootInstanceId';
    v_created_by := NEW.error_details->>'createdBy';
    v_updated_by := NEW.error_details->>'updatedBy';

    -- Handle workflow.instance.started - INSERT new instance
    IF NEW.event_type = 'workflow.instance.started' THEN
        INSERT INTO workflow_instances (
            instance_id, workflow_namespace, workflow_name, workflow_version,
            status, start_time, last_update_time, input_data,
            endpoint, business_key, parent_instance_id, root_instance_id, created_by
        ) VALUES (
            NEW.instance_id, NEW.workflow_namespace, NEW.workflow_name, NEW.workflow_version,
            NEW.status, NEW.start_time, NEW.last_update_time, NEW.input_data,
            v_endpoint, v_business_key, v_parent_instance_id, v_root_instance_id, v_created_by
        )
        ON CONFLICT (instance_id) DO NOTHING; -- Idempotent in case of duplicate events

    -- Handle workflow.instance.completed - UPDATE with final state
    ELSIF NEW.event_type = 'workflow.instance.completed' THEN
        UPDATE workflow_instances SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time,
            output_data = NEW.output_data,
            updated_by = v_updated_by
        WHERE instance_id = NEW.instance_id;

    -- Handle workflow.instance.failed - UPDATE with error details
    ELSIF NEW.event_type = 'workflow.instance.failed' THEN
        UPDATE workflow_instances SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time,
            error_message = NEW.error_message,
            error_details = NEW.error_details,
            updated_by = v_updated_by
        WHERE instance_id = NEW.instance_id;

    -- Handle workflow.instance.cancelled - UPDATE with cancelled state
    ELSIF NEW.event_type = 'workflow.instance.cancelled' THEN
        UPDATE workflow_instances SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time,
            updated_by = v_updated_by
        WHERE instance_id = NEW.instance_id;

    -- Handle workflow.instance.suspended/resumed/status.changed - UPDATE status
    ELSE
        UPDATE workflow_instances SET
            status = NEW.status,
            last_update_time = NEW.last_update_time,
            updated_by = v_updated_by
        WHERE instance_id = NEW.instance_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER workflow_event_trigger
    AFTER INSERT ON workflow_events
    FOR EACH ROW
    EXECUTE FUNCTION process_workflow_event();

-- Trigger function for task events -> task_executions
CREATE OR REPLACE FUNCTION process_task_event()
RETURNS TRIGGER AS $$
BEGIN
    -- Handle workflow.task.started - INSERT new task execution
    IF NEW.event_type = 'workflow.task.started' THEN
        INSERT INTO task_executions (
            task_execution_id, instance_id, task_name, task_position,
            status, start_time, last_update_time, input_data, retry_count
        ) VALUES (
            NEW.task_execution_id, NEW.instance_id, NEW.task_name, NEW.task_position,
            NEW.status, NEW.start_time, NEW.last_update_time, NEW.input_data, COALESCE(NEW.retry_count, 0)
        )
        ON CONFLICT (task_execution_id) DO NOTHING; -- Idempotent

    -- Handle workflow.task.completed - UPDATE with final state
    ELSIF NEW.event_type = 'workflow.task.completed' THEN
        UPDATE task_executions SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time,
            output_data = NEW.output_data
        WHERE task_execution_id = NEW.task_execution_id;

    -- Handle workflow.task.failed - UPDATE with error details
    ELSIF NEW.event_type = 'workflow.task.failed' THEN
        UPDATE task_executions SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time,
            error_message = NEW.error_message,
            error_details = NEW.error_details
        WHERE task_execution_id = NEW.task_execution_id;

    -- Handle workflow.task.cancelled - UPDATE with cancelled state
    ELSIF NEW.event_type = 'workflow.task.cancelled' THEN
        UPDATE task_executions SET
            status = NEW.status,
            end_time = NEW.end_time,
            last_update_time = NEW.last_update_time
        WHERE task_execution_id = NEW.task_execution_id;

    -- Handle workflow.task.retried - INCREMENT retry count
    ELSIF NEW.event_type = 'workflow.task.retried' THEN
        UPDATE task_executions SET
            retry_count = retry_count + 1,
            last_update_time = NEW.last_update_time
        WHERE task_execution_id = NEW.task_execution_id;

    -- Handle workflow.task.suspended/resumed - UPDATE status
    ELSE
        UPDATE task_executions SET
            status = NEW.status,
            last_update_time = NEW.last_update_time
        WHERE task_execution_id = NEW.task_execution_id;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER task_event_trigger
    AFTER INSERT ON task_events
    FOR EACH ROW
    EXECUTE FUNCTION process_task_event();

-- =============================================================================
-- GraphQL API v0.8 Compatibility Views
-- =============================================================================

-- Map WorkflowInstance (SW 1.0.0) → ProcessInstance (Kogito 0.8)
CREATE VIEW process_instances AS
SELECT
    instance_id AS id,
    workflow_name AS processid,
    workflow_version AS version,
    workflow_name AS processname,
    parent_instance_id AS parentprocessinstanceid,
    root_instance_id AS rootprocessinstanceid,
    workflow_name AS rootprocessid,
    -- Map WorkflowStatus to ProcessInstanceState
    CASE status
        WHEN 'PENDING' THEN 0
        WHEN 'RUNNING' THEN 1
        WHEN 'COMPLETED' THEN 2
        WHEN 'FAULTED' THEN 5
        WHEN 'CANCELLED' THEN 3
        WHEN 'SUSPENDED' THEN 4
    END AS state,
    endpoint,
    start_time AS start,
    end_time AS end,
    last_update_time AS lastupdatetime,
    business_key AS businesskey,
    created_by AS createdby,
    updated_by AS updatedby,
    input_data AS variables,
    CASE
        WHEN error_message IS NOT NULL THEN
            jsonb_build_object(
                'nodeDefinitionId', '',
                'message', error_message,
                'nodeInstanceId', ''
            )
        ELSE NULL
    END AS error
FROM workflow_instances;

-- Map TaskExecution (SW 1.0.0) → NodeInstance (Kogito 0.8)
CREATE VIEW nodes AS
SELECT
    task_execution_id AS id,
    task_name AS name,
    task_name AS type,
    start_time AS enter,
    end_time AS exit,
    task_name AS definitionid,
    task_execution_id AS nodeid,
    NULL::timestamp AS sladuedate,
    retry_count > 0 AS retrigger,
    error_message,
    NULL AS canceltype,
    input_data AS inputargs,
    output_data AS outputargs,
    instance_id AS processinstanceid
FROM task_executions;

-- Map Workflow (SW 1.0.0) → ProcessDefinition (Kogito 0.8)
CREATE VIEW definitions AS
SELECT
    CONCAT(namespace, '.', name) AS id,
    version,
    title AS name,
    'WORKFLOW' AS type,
    source,
    endpoint,
    metadata
FROM workflows;

-- =============================================================================
-- Comments
-- =============================================================================

-- Tables
COMMENT ON TABLE workflows IS 'Workflow definitions (deployed via LogicFlow CR)';
COMMENT ON TABLE workflow_instances IS 'Current state of workflow instances (materialized from events via triggers)';
COMMENT ON TABLE workflow_events IS 'Event sourcing: all workflow lifecycle events from Quarkus Flow logs (FluentBit writes here)';
COMMENT ON TABLE task_executions IS 'Current state of task executions within workflows (materialized from events via triggers)';
COMMENT ON TABLE task_events IS 'Event sourcing: all task lifecycle events from Quarkus Flow logs (FluentBit writes here)';
COMMENT ON TABLE jobs IS 'Timers and scheduled tasks (WaitTask, timeouts, scheduled workflows)';

-- Workflow Instances columns
COMMENT ON COLUMN workflow_instances.instance_id IS 'Unique workflow instance identifier (UUID from Quarkus Flow)';
COMMENT ON COLUMN workflow_instances.status IS 'RUNNING | COMPLETED | FAULTED | CANCELLED | SUSPENDED';
COMMENT ON COLUMN workflow_instances.input_data IS 'Workflow input payload (JSON, may be truncated if too large)';
COMMENT ON COLUMN workflow_instances.output_data IS 'Workflow output payload (JSON, may be truncated if too large)';
COMMENT ON COLUMN workflow_instances.endpoint IS 'REST endpoint for this workflow instance (v0.8 compatibility)';
COMMENT ON COLUMN workflow_instances.business_key IS 'User-provided business identifier (v0.8 compatibility)';
COMMENT ON COLUMN workflow_instances.parent_instance_id IS 'Parent instance if sub-workflow (v0.8 compatibility)';
COMMENT ON COLUMN workflow_instances.root_instance_id IS 'Top-level parent instance (v0.8 compatibility)';

-- Task Executions columns
COMMENT ON COLUMN task_executions.task_execution_id IS 'UUID generated from instanceId + taskPosition + timestamp';
COMMENT ON COLUMN task_executions.task_position IS 'Sequential position of task within workflow execution';
COMMENT ON COLUMN task_executions.status IS 'RUNNING | COMPLETED | FAILED | CANCELLED | SUSPENDED';

-- Jobs columns
COMMENT ON COLUMN jobs.job_type IS 'WAIT_TASK | TIMEOUT | SCHEDULED_WORKFLOW';
COMMENT ON COLUMN jobs.status IS 'SCHEDULED | RUNNING | EXECUTED | FAILED | CANCELLED';

-- Trigger functions
COMMENT ON FUNCTION process_workflow_event() IS 'Trigger function: materializes workflow_instances from workflow_events';
COMMENT ON FUNCTION process_task_event() IS 'Trigger function: materializes task_executions from task_events';

-- Views (v0.8 API compatibility)
COMMENT ON VIEW process_instances IS 'Kogito 0.8 ProcessInstance API compatibility (maps to workflow_instances)';
COMMENT ON VIEW nodes IS 'Kogito 0.8 NodeInstance API compatibility (maps to task_executions)';
COMMENT ON VIEW definitions IS 'Kogito 0.8 ProcessDefinition API compatibility (maps to workflows)';

-- =============================================================================
-- Migration Notes
-- =============================================================================

-- This schema replaces the old Kogito 0.8 schema which had:
-- - process_instances table → now workflow_instances (with v0.8 compatibility fields)
-- - user_task_instances table → REMOVED (not used in SW 1.0.0)
-- - node_instances table → now task_executions (simpler model)
-- - jobs table → kept for timers/scheduled tasks
-- - definitions table → now workflows (workflow definitions)
--
-- IMPORTANT: This is a CLEAN SLATE migration - no data migration from Kogito 0.8.
-- Users must redeploy their workflows to populate the new schema.
-- Old Data Index instances will not be migrated.
--
-- Data Flow:
-- 1. Quarkus Flow emits JSON events to stdout
-- 2. FluentBit parses JSON and INSERTs into workflow_events / task_events tables
-- 3. PostgreSQL triggers automatically maintain workflow_instances / task_executions
-- 4. Data Index queries current state tables (workflow_instances, task_executions)
-- 5. GraphQL v0.8 API compatibility via views (process_instances, nodes, definitions)
--
-- Key architectural changes:
-- - Data Index is now read-only (no event processing)
-- - FluentBit handles log processing (not Data Index)
-- - PostgreSQL triggers handle state materialization (not application code)
-- - Event sourcing via append-only event tables (workflow_events, task_events)
-- - v0.8 API compatibility via SQL views (no code changes needed in GraphQL layer)
--
-- GraphQL API Migration Path:
-- - GraphQL v0.8 queries work unchanged (query process_instances view)
-- - GraphQL v1.0.0 queries use native SW 1.0.0 terminology (query workflow_instances table)
-- - Both APIs coexist, allowing gradual migration
