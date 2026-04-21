-- ============================================================================
-- Data Index v1.0.0 - Event Tables Schema
-- Database-Agnostic Ingestion (Transactional Outbox Pattern)
-- ============================================================================
--
-- Purpose: Append-only event tables for Quarkus Flow structured logging events.
--          FluentBit writes flattened events directly to these tables.
--          Scheduled processor merges events into final tables (workflow_instances, task_executions).
--
-- Pattern: Transactional Outbox + CQRS + Materialized View
-- - Event tables = Write Model (append-only)
-- - Final tables = Read Model (optimized for queries)
-- - Processor = Materialization handler
--
-- Key Design:
-- - Structured columns (NOT JSONB) - database agnostic
-- - processed flag - mark events after processing
-- - 30-day retention - delete old processed events
--
-- References:
-- - docs/database-agnostic-ingestion.md
-- - docs/event-processing-patterns-analysis.md
-- - docs/implementation-roadmap.md
-- ============================================================================

-- ============================================================================
-- workflow_instance_events
-- ============================================================================
--
-- Stores workflow lifecycle events emitted by Quarkus Flow:
-- - io.serverlessworkflow.workflow.started.v1
-- - io.serverlessworkflow.workflow.completed.v1
-- - io.serverlessworkflow.workflow.faulted.v1
--
-- FluentBit Lua filter flattens nested JSON into these columns:
-- - workflowNamespace → workflow_namespace
-- - input (JSONB) → input_data
-- - error.type → error_type
--
-- Out-of-order handling: Processor merges events by instance_id using
-- COALESCE logic (same as EventLogParser in integration tests).
-- ============================================================================

CREATE TABLE workflow_instance_events (
    -- ========================================================================
    -- Event Metadata
    -- ========================================================================
    event_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,  -- 'started', 'completed', 'faulted'
    event_time TIMESTAMP NOT NULL,     -- When event occurred (from Quarkus Flow)

    -- ========================================================================
    -- Workflow Identity (present in all events)
    -- ========================================================================
    instance_id VARCHAR(255) NOT NULL,

    -- ========================================================================
    -- Fields from 'started' event
    -- ========================================================================
    -- These fields are only present in workflow.started events
    -- Processor uses COALESCE: only set if currently NULL (out-of-order handling)

    workflow_namespace VARCHAR(255),   -- SW 1.0.0 namespace (e.g., "production", "test")
    workflow_name VARCHAR(255),        -- Workflow name from definition
    workflow_version VARCHAR(255),     -- Workflow version (e.g., "1.0.0")
    start_time TIMESTAMP,              -- Workflow start timestamp
    input_data JSONB,                  -- Workflow input (keep as JSONB for flexibility)

    -- ========================================================================
    -- Fields from 'completed' event
    -- ========================================================================
    -- These fields are only present in workflow.completed events
    -- Processor always updates these (completed is final state)

    end_time TIMESTAMP,                -- Workflow completion timestamp
    output_data JSONB,                 -- Workflow output (keep as JSONB)

    -- ========================================================================
    -- Fields from 'faulted' event
    -- ========================================================================
    -- These fields are only present in workflow.faulted events
    -- Error object follows SW 1.0.0 Error spec (RFC 7807 Problem Details)

    error_type VARCHAR(255),           -- Error type URI (e.g., "about:blank#communication")
    error_title VARCHAR(255),          -- Human-readable error summary
    error_detail TEXT,                 -- Detailed error description
    error_status INTEGER,              -- HTTP status code (if applicable)

    -- ========================================================================
    -- Common Fields (present in all events)
    -- ========================================================================
    status VARCHAR(50),                -- 'RUNNING', 'COMPLETED', 'FAULTED', 'CANCELLED', 'SUSPENDED'

    -- ========================================================================
    -- Processing Metadata
    -- ========================================================================
    -- Used by event processor to track which events have been merged

    processed BOOLEAN DEFAULT FALSE,   -- Has event been processed?
    processed_at TIMESTAMP,            -- When was event processed?

    -- ========================================================================
    -- Indexes
    -- ========================================================================
    CONSTRAINT idx_wi_events_instance_id UNIQUE (instance_id, event_id)
);

-- Index for finding unprocessed events (used by scheduled processor)
CREATE INDEX idx_wi_events_unprocessed ON workflow_instance_events(processed, event_time)
    WHERE processed = false;

-- Index for cleanup job (delete old processed events)
CREATE INDEX idx_wi_events_cleanup ON workflow_instance_events(processed, processed_at)
    WHERE processed = true;

-- Index for querying events by instance (debugging)
CREATE INDEX idx_wi_events_instance ON workflow_instance_events(instance_id, event_time);

-- ============================================================================
-- task_execution_events
-- ============================================================================
--
-- Stores task lifecycle events emitted by Quarkus Flow:
-- - io.serverlessworkflow.task.started.v1
-- - io.serverlessworkflow.task.completed.v1
-- - io.serverlessworkflow.task.faulted.v1
--
-- CRITICAL: Tasks are identified by taskPosition (JSONPointer), NOT taskExecutionId!
-- The SDK generates different taskExecutionId for started vs completed events.
-- Processor merges by (instance_id, task_position).
--
-- Task Position Examples:
-- - "do/0" - First task in do sequence
-- - "do/1" - Second task in do sequence
-- - "fork/branches/0/do/0" - First task in first fork branch
-- ============================================================================

CREATE TABLE task_execution_events (
    -- ========================================================================
    -- Event Metadata
    -- ========================================================================
    event_id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(255) NOT NULL,  -- 'started', 'completed', 'faulted'
    event_time TIMESTAMP NOT NULL,

    -- ========================================================================
    -- Task Identity
    -- ========================================================================
    instance_id VARCHAR(255) NOT NULL,        -- Parent workflow instance
    task_execution_id VARCHAR(255) NOT NULL,  -- Execution ID (changes between events!)
    task_position VARCHAR(255),               -- JSONPointer (e.g., "do/0", "fork/branches/0/do/1")
    task_name VARCHAR(255),                   -- Task name from workflow definition

    -- ========================================================================
    -- Fields from 'started' event
    -- ========================================================================
    start_time TIMESTAMP,              -- Task start timestamp
    input_args JSONB,                  -- Task input arguments (keep as JSONB)

    -- ========================================================================
    -- Fields from 'completed' event
    -- ========================================================================
    end_time TIMESTAMP,                -- Task completion timestamp
    output_args JSONB,                 -- Task output arguments (keep as JSONB)

    -- ========================================================================
    -- Fields from 'faulted' event
    -- ========================================================================
    error_message TEXT,                -- Error message if task failed

    -- ========================================================================
    -- Processing Metadata
    -- ========================================================================
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMP,

    -- ========================================================================
    -- Indexes
    -- ========================================================================
    CONSTRAINT idx_te_events_unique UNIQUE (instance_id, task_position, event_id)
);

-- Index for finding unprocessed events (used by scheduled processor)
CREATE INDEX idx_te_events_unprocessed ON task_execution_events(processed, event_time)
    WHERE processed = false;

-- Index for cleanup job
CREATE INDEX idx_te_events_cleanup ON task_execution_events(processed, processed_at)
    WHERE processed = true;

-- Index for querying tasks by instance (debugging)
CREATE INDEX idx_te_events_instance ON task_execution_events(instance_id, event_time);

-- Index for merging by position (CRITICAL for processor performance)
CREATE INDEX idx_te_events_position ON task_execution_events(instance_id, task_position, event_time);

-- ============================================================================
-- Event Table Size Estimation
-- ============================================================================
--
-- Assumptions:
-- - 1,000 workflows/day
-- - 6 events/workflow average (started, 2 tasks × 2 events, completed)
-- - 1 KB/event (JSON payload)
-- - 30-day retention
--
-- Calculation:
-- Events/day = 1,000 workflows × 6 events = 6,000 events
-- Storage/day = 6,000 events × 1 KB = 6 MB
-- Storage/30 days = 6 MB × 30 = 180 MB
--
-- For 10,000 workflows/day: 1.8 GB for 30 days (very manageable)
-- ============================================================================

-- ============================================================================
-- Usage Notes
-- ============================================================================
--
-- FluentBit Writes:
-- - FluentBit Lua filter flattens JSON and writes directly to these tables
-- - No UPSERT logic in FluentBit (just INSERT)
-- - FluentBit handles retries, buffering, failures
--
-- Event Processor:
-- - Scheduled job (every 5s) polls for unprocessed events
-- - Groups events by instance_id
-- - Merges into final tables using COALESCE logic (same as EventLogParser)
-- - Marks events as processed
--
-- Cleanup Job:
-- - Daily job (2 AM) deletes processed events older than retention period
-- - Configurable: data-index.event-processor.retention-days=30
--
-- Debugging:
-- - Query all events for an instance:
--   SELECT * FROM workflow_instance_events WHERE instance_id = 'xxx' ORDER BY event_time;
-- - Check unprocessed event count:
--   SELECT COUNT(*) FROM workflow_instance_events WHERE processed = false;
-- - Find events stuck in processing:
--   SELECT * FROM workflow_instance_events WHERE processed = false AND event_time < NOW() - INTERVAL '1 hour';
-- ============================================================================

-- ============================================================================
-- Database Compatibility Notes
-- ============================================================================
--
-- PostgreSQL-specific features used:
-- - BIGSERIAL (auto-increment) → MySQL: BIGINT AUTO_INCREMENT
-- - JSONB → MySQL: JSON, Oracle: CLOB + JSON constraint
-- - Partial indexes (WHERE processed = false) → MySQL: filter in query
--
-- To adapt for MySQL:
-- 1. Replace BIGSERIAL with BIGINT AUTO_INCREMENT
-- 2. Replace JSONB with JSON
-- 3. Remove partial index WHERE clause (MySQL doesn't support)
-- 4. Add regular indexes instead
--
-- To adapt for Oracle:
-- 1. Replace BIGSERIAL with NUMBER + SEQUENCE + TRIGGER
-- 2. Replace JSONB with CLOB + IS JSON constraint
-- 3. Replace BOOLEAN with NUMBER(1)
-- ============================================================================

-- ============================================================================
-- Migration from Trigger-Based Approach
-- ============================================================================
--
-- If migrating from existing trigger-based staging tables:
--
-- 1. Deploy event tables (this schema)
-- 2. Configure FluentBit to write to BOTH staging AND event tables
-- 3. Run both approaches in parallel (1-2 days)
-- 4. Compare results (should be identical)
-- 5. Enable event processor, disable triggers
-- 6. Monitor for 1 week
-- 7. Drop old staging tables and triggers
--
-- Rollback plan:
-- - Keep both tables for 2 weeks
-- - Feature flag: data-index.event-processor.enabled=false (revert to triggers)
-- ============================================================================
