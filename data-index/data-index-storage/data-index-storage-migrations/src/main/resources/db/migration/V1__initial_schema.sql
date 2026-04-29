-- ============================================================================
-- Data Index v1.1.0 - FluentBit + Trigger-based Normalization with Idempotency
-- ============================================================================
--
-- This schema includes:
-- - Raw staging tables (FluentBit pgsql plugin fixed schema)
-- - Normalized tables with idempotency fields
-- - Trigger functions with field-level idempotency logic
-- - Out-of-order event handling
-- - Event replay safety
--
-- ============================================================================

-- ============================================================================
-- RAW STAGING TABLES (FluentBit pgsql plugin fixed schema)
-- ============================================================================

CREATE TABLE IF NOT EXISTS workflow_events_raw (
  tag TEXT,
  time TIMESTAMP WITH TIME ZONE,
  data JSONB
);

CREATE INDEX IF NOT EXISTS idx_workflow_events_raw_time ON workflow_events_raw (time DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_events_raw_tag ON workflow_events_raw (tag);

CREATE TABLE IF NOT EXISTS task_events_raw (
  tag TEXT,
  time TIMESTAMP WITH TIME ZONE,
  data JSONB
);

CREATE INDEX IF NOT EXISTS idx_task_events_raw_time ON task_events_raw (time DESC);
CREATE INDEX IF NOT EXISTS idx_task_events_raw_tag ON task_events_raw (tag);

-- ============================================================================
-- NORMALIZED TABLES (GraphQL API queries these)
-- ============================================================================

CREATE TABLE IF NOT EXISTS workflow_instances (
  id VARCHAR(255) PRIMARY KEY,
  namespace VARCHAR(255),
  name VARCHAR(255),
  version VARCHAR(255),
  status VARCHAR(50),
  start TIMESTAMP WITH TIME ZONE,
  "end" TIMESTAMP WITH TIME ZONE,
  last_update TIMESTAMP WITH TIME ZONE,
  last_event_time TIMESTAMP WITH TIME ZONE,  -- Idempotency: track event timestamp
  input JSONB,
  output JSONB,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_namespace_name ON workflow_instances (namespace, name);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_status ON workflow_instances (status);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_start ON workflow_instances (start DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_last_event_time ON workflow_instances (last_event_time DESC);

CREATE TABLE IF NOT EXISTS task_instances (
  task_execution_id VARCHAR(255) PRIMARY KEY,
  instance_id VARCHAR(255) NOT NULL,
  task_name VARCHAR(255),
  task_position VARCHAR(255),
  status VARCHAR(50),
  start TIMESTAMP WITH TIME ZONE,
  "end" TIMESTAMP WITH TIME ZONE,
  last_event_time TIMESTAMP WITH TIME ZONE,  -- Idempotency: track event timestamp
  input JSONB,
  output JSONB,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  CONSTRAINT fk_task_instance_workflow FOREIGN KEY (instance_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_task_instances_instance_id ON task_instances (instance_id);
CREATE INDEX IF NOT EXISTS idx_task_instances_status ON task_instances (status);
CREATE INDEX IF NOT EXISTS idx_task_instances_last_event_time ON task_instances (last_event_time DESC);

-- ============================================================================
-- TRIGGER FUNCTIONS (Extract from JSONB and normalize with idempotency)
-- ============================================================================

-- Function to normalize workflow events with field-level idempotency
CREATE OR REPLACE FUNCTION normalize_workflow_event()
RETURNS TRIGGER AS $$
DECLARE
  event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  -- Extract event timestamp from JSONB data
  -- Quarkus Flow uses 'timestamp' field (epoch-seconds format)
  event_timestamp := to_timestamp((NEW.data->>'timestamp')::numeric);

  -- Upsert with field-level idempotency logic
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
    error_instance,
    last_event_time,
    created_at,
    updated_at
  ) VALUES (
    NEW.data->>'instanceId',
    NEW.data->>'workflowNamespace',
    NEW.data->>'workflowName',
    NEW.data->>'workflowVersion',
    NEW.data->>'status',
    to_timestamp((NEW.data->>'startTime')::numeric),
    to_timestamp((NEW.data->>'endTime')::numeric),
    to_timestamp((NEW.data->>'lastUpdateTime')::numeric),
    NEW.data->'input',
    NEW.data->'output',
    NEW.data->'error'->>'type',
    NEW.data->'error'->>'title',
    NEW.data->'error'->>'detail',
    (NEW.data->'error'->>'status')::integer,
    NEW.data->'error'->>'instance',
    event_timestamp,
    NEW.time,
    NEW.time
  )
  ON CONFLICT (id) DO UPDATE SET
    -- Status: Use event timestamp to determine which status wins
    -- If incoming event is newer, use its status; otherwise keep existing
    status = CASE
      WHEN event_timestamp > workflow_instances.last_event_time
      THEN EXCLUDED.status
      ELSE workflow_instances.status
    END,

    -- Immutable fields: First event wins (never overwrite if already set)
    -- These are set by workflow.started event and should never change
    namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
    name = COALESCE(workflow_instances.name, EXCLUDED.name),
    version = COALESCE(workflow_instances.version, EXCLUDED.version),
    start = COALESCE(workflow_instances.start, EXCLUDED.start),
    input = COALESCE(workflow_instances.input, EXCLUDED.input),

    -- Terminal fields: Preserve if already set (completion data)
    -- Once a workflow completes/faults, these fields should not be cleared
    "end" = COALESCE(EXCLUDED."end", workflow_instances."end"),
    output = COALESCE(EXCLUDED.output, workflow_instances.output),
    error_type = COALESCE(EXCLUDED.error_type, workflow_instances.error_type),
    error_title = COALESCE(EXCLUDED.error_title, workflow_instances.error_title),
    error_detail = COALESCE(EXCLUDED.error_detail, workflow_instances.error_detail),
    error_status = COALESCE(EXCLUDED.error_status, workflow_instances.error_status),
    error_instance = COALESCE(EXCLUDED.error_instance, workflow_instances.error_instance),

    -- last_update: Always take newer value
    last_update = GREATEST(
      COALESCE(EXCLUDED.last_update, workflow_instances.last_update),
      COALESCE(workflow_instances.last_update, EXCLUDED.last_update)
    ),

    -- Timestamp tracking: Keep latest event timestamp
    last_event_time = GREATEST(event_timestamp, workflow_instances.last_event_time),

    -- Audit: Always update
    updated_at = NEW.time;

  -- Return NEW to keep the raw event in staging table
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Function to normalize task events with UPDATE-first pattern
--
-- Pattern:
--   1. Try UPDATE by (instance_id, task_position) WHERE "end" IS NULL
--   2. If no rows updated, INSERT new row
--
-- The "end" IS NULL condition is critical:
--   - Prevents updating completed tasks (supports loop iterations)
--   - Only updates in-progress tasks (task.started → task.completed for same execution)
--   - Allows same position to execute multiple times (different rows)
CREATE OR REPLACE FUNCTION normalize_task_event()
RETURNS TRIGGER AS $$
DECLARE
  event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  -- Extract event timestamp from JSONB data
  event_timestamp := to_timestamp((NEW.data->>'timestamp')::numeric);

  -- First ensure workflow instance exists (handle out-of-order events)
  -- Task events might arrive before workflow events
  INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
  VALUES (NEW.data->>'instanceId', NEW.time, NEW.time, event_timestamp)
  ON CONFLICT (id) DO NOTHING;

  -- Try UPDATE first - match on (instance_id, task_position)
  -- CRITICAL: Only update if "end" IS NULL (task not yet completed)
  -- This prevents updating completed tasks, enabling loop iterations
  UPDATE task_instances SET
    -- Status: Use event timestamp to determine winner
    status = CASE
      WHEN event_timestamp > last_event_time
      THEN NEW.data->>'status'
      ELSE status
    END,

    -- Immutable fields: Keep first non-null (don't overwrite)
    task_name = COALESCE(task_name, NEW.data->>'taskName'),
    task_position = COALESCE(task_position, NEW.data->>'taskPosition'),
    start = COALESCE(start, to_timestamp((NEW.data->>'startTime')::numeric)),
    input = COALESCE(input, NEW.data->'input'),

    -- Terminal fields: Keep latest non-null (preserve completion data)
    "end" = COALESCE(to_timestamp((NEW.data->>'endTime')::numeric), "end"),
    output = COALESCE(NEW.data->'output', output),
    error_type = COALESCE(NEW.data->'error'->>'type', error_type),
    error_title = COALESCE(NEW.data->'error'->>'title', error_title),
    error_detail = COALESCE(NEW.data->'error'->>'detail', error_detail),
    error_status = COALESCE((NEW.data->'error'->>'status')::integer, error_status),
    error_instance = COALESCE(NEW.data->'error'->>'instance', error_instance),

    -- Timestamp tracking: Keep latest event timestamp
    last_event_time = GREATEST(event_timestamp, last_event_time),

    -- Audit: Always update
    updated_at = NEW.time

  WHERE instance_id = NEW.data->>'instanceId'
    AND task_position = NEW.data->>'taskPosition'
    AND "end" IS NULL;  -- CRITICAL: Only update non-terminal tasks

  -- If UPDATE didn't match any rows (NOT FOUND), INSERT new row
  IF NOT FOUND THEN
    INSERT INTO task_instances (
      task_execution_id,
      instance_id,
      task_name,
      task_position,
      status,
      start,
      "end",
      input,
      output,
      error_type,
      error_title,
      error_detail,
      error_status,
      error_instance,
      last_event_time,
      created_at,
      updated_at
    ) VALUES (
      NEW.data->>'taskExecutionId',
      NEW.data->>'instanceId',
      NEW.data->>'taskName',
      NEW.data->>'taskPosition',
      NEW.data->>'status',
      to_timestamp((NEW.data->>'startTime')::numeric),
      to_timestamp((NEW.data->>'endTime')::numeric),
      NEW.data->'input',
      NEW.data->'output',
      NEW.data->'error'->>'type',
      NEW.data->'error'->>'title',
      NEW.data->'error'->>'detail',
      (NEW.data->'error'->>'status')::integer,
      NEW.data->'error'->>'instance',
      event_timestamp,
      NEW.time,
      NEW.time
    );
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- TRIGGERS (Auto-normalize on INSERT)
-- ============================================================================

CREATE TRIGGER normalize_workflow_events
  BEFORE INSERT ON workflow_events_raw
  FOR EACH ROW
  EXECUTE FUNCTION normalize_workflow_event();

CREATE TRIGGER normalize_task_events
  BEFORE INSERT ON task_events_raw
  FOR EACH ROW
  EXECUTE FUNCTION normalize_task_event();
