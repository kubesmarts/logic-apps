-- PostgreSQL Schema for Data Index v1.0.0
-- Read-only query service with v0.8 backward compatibility
--
-- Architecture: Quarkus Flow → JSON logs → FluentBit → PostgreSQL (event tables + triggers) → Data Index (queries)
--
-- Table Naming:
-- - Primary tables use v0.8 names for JPA compatibility: processes, definitions, nodes, jobs
-- - Compatibility views provide v1.0.0 terminology: workflow_instances, task_executions, workflow_definitions
--
-- Tables: 11 main tables + 8 collection tables = 19 total
-- Views: 3 compatibility views (v1.0.0 terminology)
--
-- BPMN Features Removed: milestones, UserTask (tasks, comments, attachments)
--
-- Last Updated: 2026-04-14

-- =============================================================================
-- CORE TABLES
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Table: definitions
-- Purpose: Workflow/Process definitions (immutable metadata)
-- Populated by: FluentBit from workflow.definition.registered events
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions (
    id VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    name VARCHAR(255),
    description TEXT,
    type VARCHAR(50),
    source BYTEA,                    -- Workflow source code (YAML/JSON)
    endpoint VARCHAR(500),            -- Runtime service endpoint
    metadata JSONB,                   -- Additional metadata (annotations, labels, etc.)

    PRIMARY KEY (id, version)
);

COMMENT ON TABLE definitions IS 'Workflow/Process definitions - immutable metadata registered by workflow runtime';
COMMENT ON COLUMN definitions.id IS 'Process/Workflow ID (unique name)';
COMMENT ON COLUMN definitions.version IS 'Semantic version (e.g., 1.0, 2.1)';
COMMENT ON COLUMN definitions.source IS 'Original workflow definition (YAML/JSON bytes)';
COMMENT ON COLUMN definitions.endpoint IS 'Runtime service base URL for this workflow';
COMMENT ON COLUMN definitions.metadata IS 'JSONB metadata: annotations, labels, custom fields';

-- -----------------------------------------------------------------------------
-- Table: definitions_roles
-- Purpose: RBAC roles authorized to access this workflow definition
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions_roles (
    process_id VARCHAR(255) NOT NULL,
    process_version VARCHAR(50) NOT NULL,
    role VARCHAR(255) NOT NULL,

    PRIMARY KEY (process_id, process_version, role),
    CONSTRAINT fk_definitions_roles_definitions
        FOREIGN KEY (process_id, process_version)
        REFERENCES definitions(id, version)
        ON DELETE CASCADE
);

COMMENT ON TABLE definitions_roles IS 'RBAC roles authorized for workflow definition access';

-- -----------------------------------------------------------------------------
-- Table: definitions_addons
-- Purpose: Quarkus extensions/addons enabled for this workflow
-- Examples: jobs-management, prometheus-monitoring, process-management
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions_addons (
    process_id VARCHAR(255) NOT NULL,
    process_version VARCHAR(50) NOT NULL,
    addon VARCHAR(255) NOT NULL,

    PRIMARY KEY (process_id, process_version, addon),
    CONSTRAINT fk_definitions_addons_definitions
        FOREIGN KEY (process_id, process_version)
        REFERENCES definitions(id, version)
        ON DELETE CASCADE
);

COMMENT ON TABLE definitions_addons IS 'Quarkus extensions enabled for this workflow (e.g., jobs-management, monitoring)';

-- -----------------------------------------------------------------------------
-- Table: definitions_annotations
-- Purpose: Kubernetes-style annotations (key=value metadata)
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions_annotations (
    process_id VARCHAR(255) NOT NULL,
    process_version VARCHAR(50) NOT NULL,
    annotation VARCHAR(500) NOT NULL,

    PRIMARY KEY (process_id, process_version, annotation),
    CONSTRAINT fk_definitions_annotations
        FOREIGN KEY (process_id, process_version)
        REFERENCES definitions(id, version)
        ON DELETE CASCADE
);

COMMENT ON TABLE definitions_annotations IS 'Kubernetes-style annotations for workflow definitions';

-- -----------------------------------------------------------------------------
-- Table: definitions_nodes
-- Purpose: Node definitions within a workflow (static metadata from workflow definition)
-- Examples: StartNode, EndNode, ActionNode, SubflowNode, etc.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions_nodes (
    id VARCHAR(255) NOT NULL,
    process_id VARCHAR(255) NOT NULL,
    process_version VARCHAR(50) NOT NULL,
    name VARCHAR(255),
    uniqueId VARCHAR(255),           -- Unique node identifier within the workflow
    type VARCHAR(100),               -- Node type: StartNode, EndNode, ActionNode, etc.

    PRIMARY KEY (id, process_id, process_version),
    CONSTRAINT fk_definitions_nodes_definitions
        FOREIGN KEY (process_id, process_version)
        REFERENCES definitions(id, version)
        ON DELETE CASCADE
);

COMMENT ON TABLE definitions_nodes IS 'Node definitions within workflow - static metadata from workflow definition';
COMMENT ON COLUMN definitions_nodes.uniqueId IS 'Unique node identifier within the workflow definition';
COMMENT ON COLUMN definitions_nodes.type IS 'Node type: StartNode, EndNode, ActionNode, SubflowNode, etc.';

-- -----------------------------------------------------------------------------
-- Table: definitions_nodes_metadata
-- Purpose: Key-value metadata for definition nodes
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS definitions_nodes_metadata (
    node_id VARCHAR(255) NOT NULL,
    process_id VARCHAR(255) NOT NULL,
    process_version VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,      -- Metadata key
    meta_value VARCHAR(1000),        -- Metadata value

    PRIMARY KEY (node_id, process_id, process_version, name),
    CONSTRAINT fk_definitions_nodes_metadata_definitions_nodes
        FOREIGN KEY (node_id, process_id, process_version)
        REFERENCES definitions_nodes(id, process_id, process_version)
        ON DELETE CASCADE
);

COMMENT ON TABLE definitions_nodes_metadata IS 'Key-value metadata for workflow definition nodes';

-- -----------------------------------------------------------------------------
-- Table: processes
-- Purpose: Workflow/Process instances (runtime state)
-- Populated by: FluentBit from workflow.instance.* events
-- Updated by: PostgreSQL triggers on workflow event tables
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processes (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    processId VARCHAR(255) NOT NULL,
    version VARCHAR(50),
    processName VARCHAR(255),
    state INTEGER NOT NULL,          -- 0=PENDING, 1=ACTIVE, 2=COMPLETED, 3=ABORTED, 4=SUSPENDED, 5=ERROR
    businessKey VARCHAR(255),
    endpoint VARCHAR(500),            -- Runtime service endpoint for this instance
    startTime TIMESTAMP WITH TIME ZONE,
    endTime TIMESTAMP WITH TIME ZONE,
    lastUpdateTime TIMESTAMP WITH TIME ZONE,

    -- Process hierarchy (v0.8 terminology for sub-workflows)
    rootProcessInstanceId VARCHAR(255),
    rootProcessId VARCHAR(255),
    parentProcessInstanceId VARCHAR(255),

    -- Audit fields
    createdBy VARCHAR(255),
    updatedBy VARCHAR(255),

    -- SLA
    slaDueDate TIMESTAMP WITH TIME ZONE,

    -- CloudEvent correlation
    cloudEventId VARCHAR(255),
    cloudEventSource VARCHAR(500),

    -- Variables (JSONB for queryability)
    variables JSONB,

    -- Foreign key to definition
    CONSTRAINT fk_processes_definitions
        FOREIGN KEY (processId, version)
        REFERENCES definitions(id, version)
);

COMMENT ON TABLE processes IS 'Workflow/Process instances - runtime state materialized from event stream';
COMMENT ON COLUMN processes.id IS 'Unique instance ID (UUID)';
COMMENT ON COLUMN processes.processId IS 'Reference to definitions.id';
COMMENT ON COLUMN processes.state IS 'Instance state: 0=PENDING, 1=ACTIVE, 2=COMPLETED, 3=ABORTED, 4=SUSPENDED, 5=ERROR';
COMMENT ON COLUMN processes.businessKey IS 'User-defined business identifier (e.g., order-123)';
COMMENT ON COLUMN processes.endpoint IS 'Runtime endpoint URL for mutations (abort, retry, etc.)';
COMMENT ON COLUMN processes.rootProcessInstanceId IS 'Top-level parent instance ID (for sub-workflows)';
COMMENT ON COLUMN processes.parentProcessInstanceId IS 'Direct parent instance ID (for sub-workflows)';
COMMENT ON COLUMN processes.variables IS 'Current workflow variables as JSONB (queryable)';

-- -----------------------------------------------------------------------------
-- Table: processes_roles
-- Purpose: RBAC roles for process instance access control
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processes_roles (
    process_id VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,

    PRIMARY KEY (process_id, role),
    CONSTRAINT fk_processes_roles_processes
        FOREIGN KEY (process_id)
        REFERENCES processes(id)
        ON DELETE CASCADE
);

COMMENT ON TABLE processes_roles IS 'RBAC roles authorized for process instance access';

-- -----------------------------------------------------------------------------
-- Table: processes_addons
-- Purpose: Addons enabled for this process instance
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processes_addons (
    process_id VARCHAR(255) NOT NULL,
    addon VARCHAR(255) NOT NULL,

    PRIMARY KEY (process_id, addon),
    CONSTRAINT fk_processes_addons_processes
        FOREIGN KEY (process_id)
        REFERENCES processes(id)
        ON DELETE CASCADE
);

COMMENT ON TABLE processes_addons IS 'Quarkus addons enabled for this process instance';

-- -----------------------------------------------------------------------------
-- Table: nodes
-- Purpose: Node/Task instances (execution steps within a process)
-- Populated by: FluentBit from workflow.node.* events
-- v0.8 terminology: "nodes" (v1.0.0 equivalent: "task executions")
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nodes (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    name VARCHAR(255),
    nodeId VARCHAR(255),             -- Definition node ID (from workflow definition)
    type VARCHAR(100),               -- Node type: StartNode, EndNode, ActionNode, SubflowNode, etc.
    definitionId VARCHAR(255),       -- Workflow definition node reference
    enter TIMESTAMP WITH TIME ZONE,  -- Entry timestamp
    exit TIMESTAMP WITH TIME ZONE,   -- Exit timestamp (NULL if still running)
    slaDueDate TIMESTAMP WITH TIME ZONE,
    retrigger BOOLEAN,               -- Can this node be retriggered?
    errorMessage TEXT,               -- Error message if node failed
    cancelType VARCHAR(50),          -- ABORTED, SKIPPED, OBSOLETE, etc.

    -- Foreign key to parent process instance
    processInstanceId VARCHAR(255) NOT NULL,
    CONSTRAINT fk_nodes_process
        FOREIGN KEY (processInstanceId)
        REFERENCES processes(id)
        ON DELETE CASCADE,

    -- Input/Output arguments (JSONB)
    inputArgs JSONB,
    outputArgs JSONB
);

COMMENT ON TABLE nodes IS 'Node/Task instances - execution steps within a workflow instance';
COMMENT ON COLUMN nodes.id IS 'Unique node instance ID (UUID)';
COMMENT ON COLUMN nodes.nodeId IS 'Node ID from workflow definition';
COMMENT ON COLUMN nodes.type IS 'Node type: StartNode, EndNode, ActionNode, SubflowNode, etc.';
COMMENT ON COLUMN nodes.definitionId IS 'Reference to workflow definition node';
COMMENT ON COLUMN nodes.cancelType IS 'Cancellation reason: ABORTED, SKIPPED, OBSOLETE';
COMMENT ON COLUMN nodes.inputArgs IS 'Node input arguments as JSONB';
COMMENT ON COLUMN nodes.outputArgs IS 'Node output arguments as JSONB';

-- -----------------------------------------------------------------------------
-- Table: jobs
-- Purpose: Scheduled jobs (timers, async tasks)
-- Populated by: FluentBit from workflow.job.* events
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS jobs (
    id VARCHAR(255) NOT NULL PRIMARY KEY,
    processId VARCHAR(255),
    processInstanceId VARCHAR(255),
    nodeInstanceId VARCHAR(255),
    rootProcessId VARCHAR(255),
    rootProcessInstanceId VARCHAR(255),
    expirationTime TIMESTAMP WITH TIME ZONE,  -- When job should execute
    priority INTEGER,
    callbackEndpoint VARCHAR(500),            -- Endpoint to call when job fires
    repeatInterval BIGINT,                    -- Milliseconds between repeats (NULL = one-time)
    repeatLimit INTEGER,                      -- Max repeats (-1 = infinite)
    scheduledId VARCHAR(255),                 -- External scheduler ID (e.g., Quartz)
    retries INTEGER,                          -- Remaining retry attempts
    status VARCHAR(50),                       -- SCHEDULED, EXECUTED, RETRY, CANCELED, ERROR
    lastUpdate TIMESTAMP WITH TIME ZONE,
    executionCounter INTEGER,                 -- Number of times executed
    endpoint VARCHAR(500),                    -- Runtime service endpoint
    exceptionMessage TEXT,
    exceptionDetails TEXT
);

COMMENT ON TABLE jobs IS 'Scheduled jobs and timers for workflow instances';
COMMENT ON COLUMN jobs.id IS 'Unique job ID (UUID)';
COMMENT ON COLUMN jobs.expirationTime IS 'When job should fire (trigger time)';
COMMENT ON COLUMN jobs.callbackEndpoint IS 'HTTP endpoint to invoke when job fires';
COMMENT ON COLUMN jobs.repeatInterval IS 'Milliseconds between repeats (NULL = one-time job)';
COMMENT ON COLUMN jobs.repeatLimit IS 'Max repetitions (-1 = infinite)';
COMMENT ON COLUMN jobs.status IS 'Job state: SCHEDULED, EXECUTED, RETRY, CANCELED, ERROR';

-- =============================================================================
-- NOTE: BPMN Legacy Tables REMOVED
-- =============================================================================
-- The following BPMN-specific features are NOT used in Serverless Workflow 1.0.0
-- and have been removed from Data Index v1.0.0 schema:
--
-- Removed tables:
-- - milestones (MilestoneEntity) - BPMN milestones
-- - tasks (UserTaskInstanceEntity) - BPMN human tasks
-- - tasks_admin_groups, tasks_admin_users, tasks_excluded_users
-- - tasks_potential_groups, tasks_potential_users
-- - comments (CommentEntity) - User task comments
-- - attachments (AttachmentEntity) - User task attachments
--
-- GraphQL API: Milestone and UserTaskInstance queries will be removed in Phase 3.
-- =============================================================================

-- =============================================================================
-- INDEXES FOR QUERY PERFORMANCE
-- =============================================================================

-- Process instances - most queried table
CREATE INDEX idx_processes_processId ON processes(processId);
CREATE INDEX idx_processes_state ON processes(state);
CREATE INDEX idx_processes_startTime ON processes(startTime);
CREATE INDEX idx_processes_endTime ON processes(endTime);
CREATE INDEX idx_processes_businessKey ON processes(businessKey);
CREATE INDEX idx_processes_rootProcessInstanceId ON processes(rootProcessInstanceId);
CREATE INDEX idx_processes_parentProcessInstanceId ON processes(parentProcessInstanceId);

-- JSONB variable queries (GIN index for efficient JSON path queries)
CREATE INDEX idx_processes_variables ON processes USING GIN (variables);

-- Node instances
CREATE INDEX idx_nodes_processInstanceId ON nodes(processInstanceId);
CREATE INDEX idx_nodes_nodeId ON nodes(nodeId);
CREATE INDEX idx_nodes_type ON nodes(type);
CREATE INDEX idx_nodes_enter ON nodes(enter);
CREATE INDEX idx_nodes_exit ON nodes(exit);

-- Jobs
CREATE INDEX idx_jobs_processId ON jobs(processId);
CREATE INDEX idx_jobs_processInstanceId ON jobs(processInstanceId);
CREATE INDEX idx_jobs_status ON jobs(status);
CREATE INDEX idx_jobs_expirationTime ON jobs(expirationTime);

-- Definitions
CREATE INDEX idx_definitions_name ON definitions(name);
CREATE INDEX idx_definitions_type ON definitions(type);

-- Definition nodes
CREATE INDEX idx_definitions_nodes_processId ON definitions_nodes(process_id, process_version);
CREATE INDEX idx_definitions_nodes_type ON definitions_nodes(type);

-- =============================================================================
-- v0.8 COMPATIBILITY VIEWS
-- =============================================================================
-- These views provide v0.8 GraphQL API compatibility while the underlying
-- tables use v0.8 naming. In the future, when we migrate to v1.0.0 table names
-- (workflow_instances, task_executions), these views will map to those tables.
--
-- For Phase 1-2: Views are simple aliases (tables already use v0.8 names)
-- For Phase 3+: Views will map v1.0.0 tables to v0.8 GraphQL schema
-- =============================================================================

-- Currently, tables use v0.8 names directly, so views are 1:1 aliases
-- This allows GraphQL resolvers to query either v0.8 or v1.0.0 names

CREATE OR REPLACE VIEW workflow_instances AS
SELECT
    id,
    processId AS workflowId,
    version,
    processName AS workflowName,
    state,
    businessKey,
    endpoint,
    startTime,
    endTime,
    lastUpdateTime,
    rootProcessInstanceId AS rootWorkflowInstanceId,
    rootProcessId AS rootWorkflowId,
    parentProcessInstanceId AS parentWorkflowInstanceId,
    createdBy,
    updatedBy,
    slaDueDate,
    cloudEventId,
    cloudEventSource,
    variables
FROM processes;

COMMENT ON VIEW workflow_instances IS 'v1.0.0 view - maps v0.8 processes table to v1.0.0 terminology';

CREATE OR REPLACE VIEW task_executions AS
SELECT
    id,
    name,
    nodeId AS taskId,
    type,
    definitionId,
    enter AS startTime,
    exit AS endTime,
    slaDueDate,
    retrigger,
    errorMessage,
    cancelType,
    processInstanceId AS workflowInstanceId,
    inputArgs,
    outputArgs
FROM nodes;

COMMENT ON VIEW task_executions IS 'v1.0.0 view - maps v0.8 nodes table to v1.0.0 terminology (task executions)';

CREATE OR REPLACE VIEW workflow_definitions AS
SELECT
    id AS workflowId,
    version,
    name,
    description,
    type,
    source,
    endpoint,
    metadata
FROM definitions;

COMMENT ON VIEW workflow_definitions IS 'v1.0.0 view - maps v0.8 definitions table to v1.0.0 terminology';

-- =============================================================================
-- GRANTS (adjust based on deployment security model)
-- =============================================================================
-- Data Index service user: read-only access
-- FluentBit/trigger user: read-write access to event tables (not shown here)
-- =============================================================================

-- Example grants (uncomment and customize for your environment):
-- GRANT SELECT ON ALL TABLES IN SCHEMA public TO dataindex_readonly;
-- GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO dataindex_readonly;
