-- ============================================================================
-- V2: Align field names with Open Workflow Specification
-- ============================================================================
--
-- Open Workflow 1.0.0 lifecycle events use these field names:
-- - Workflow timing: 'startedAt', 'completedAt', 'faultedAt', etc.
-- - Task timing: 'startedAt', 'completedAt', 'faultedAt', etc.
-- - Task reference: 'task' (JSON Pointer like '/do/1/initialize')
-- - Task/workflow data: 'input', 'output' (already aligned)
--
-- We normalize terminal timestamps into generic 'endedAt' column:
-- - status='COMPLETED' → endedAt stores completedAt timestamp
-- - status='FAULTED' → endedAt stores faultedAt timestamp
-- - status='CANCELLED' → endedAt stores cancelledAt timestamp
--
-- This is the industry standard pattern (generic timestamp + status indicator)
-- used by Kubernetes, CI/CD systems, and most workflow engines.
--
-- References:
-- - https://github.com/open-workflow-specification/specification/blob/main/dsl.md#lifecycle-events
-- - https://github.com/open-workflow-specification/specification/blob/main/dsl-reference.md#lifecycle-events
-- ============================================================================

-- ============================================================================
-- WORKFLOW INSTANCES: Rename timing columns
-- ============================================================================

-- Workflow instances: Rename 'start' to 'startedAt'
ALTER TABLE workflow_instances RENAME COLUMN "start" TO "startedAt";

-- Workflow instances: Rename 'end' to 'endedAt'
ALTER TABLE workflow_instances RENAME COLUMN "end" TO "endedAt";

-- ============================================================================
-- TASK INSTANCES: Rename timing and task columns
-- ============================================================================

-- Drop existing primary key constraint (will recreate with new column name)
ALTER TABLE task_instances DROP CONSTRAINT task_instances_pkey;

-- Rename columns
ALTER TABLE task_instances RENAME COLUMN "start" TO "startedAt";
ALTER TABLE task_instances RENAME COLUMN "end" TO "endedAt";
ALTER TABLE task_instances RENAME COLUMN task_position TO task;

-- Recreate primary key with new column name
ALTER TABLE task_instances ADD PRIMARY KEY (instance_id, task);

-- ============================================================================
-- UPDATE TRIGGER FUNCTIONS: Use new column names
-- ============================================================================

-- Update workflow event normalization trigger
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
    "startedAt",     -- Changed from 'start'
    "endedAt",       -- Changed from 'end'
    last_update,
    input,
    output,
    error_type,
    error_title,
    error_detail,
    error_status,
    error_instance,
    last_event_time
  )
  VALUES (
    NEW.data->>'id',
    NEW.data->>'namespace',
    NEW.data->>'name',
    NEW.data->>'version',
    NEW.data->>'status',
    -- Extract 'startedAt' timestamp (workflow.started event)
    -- Quarkus Flow uses 'startTime' field (epoch-seconds format)
    CASE WHEN NEW.data->>'startTime' IS NOT NULL
      THEN to_timestamp((NEW.data->>'startTime')::numeric)
      ELSE NULL
    END,
    -- Extract 'endedAt' timestamp (workflow.completed/faulted/cancelled events)
    -- Quarkus Flow uses 'endTime' field (epoch-seconds format)
    CASE WHEN NEW.data->>'endTime' IS NOT NULL
      THEN to_timestamp((NEW.data->>'endTime')::numeric)
      ELSE NULL
    END,
    -- Extract last_update timestamp
    CASE WHEN NEW.data->>'lastUpdate' IS NOT NULL
      THEN to_timestamp((NEW.data->>'lastUpdate')::numeric)
      ELSE NULL
    END,
    NEW.data->'input',
    NEW.data->'output',
    NEW.data->'error'->>'type',
    NEW.data->'error'->>'title',
    NEW.data->'error'->>'detail',
    (NEW.data->'error'->>'status')::integer,
    NEW.data->'error'->>'instance',
    event_timestamp
  )
  ON CONFLICT (id) DO UPDATE SET
    -- Status: Use newer event's status
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
    "startedAt" = COALESCE(workflow_instances."startedAt", EXCLUDED."startedAt"),  -- Changed from 'start'
    input = COALESCE(workflow_instances.input, EXCLUDED.input),

    -- Terminal fields: Preserve if already set (completion data)
    -- Once a workflow completes/faults/cancels, these fields should not be cleared
    "endedAt" = COALESCE(EXCLUDED."endedAt", workflow_instances."endedAt"),  -- Changed from 'end'
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

-- Update task event normalization trigger
CREATE OR REPLACE FUNCTION normalize_task_event()
RETURNS TRIGGER AS $$
DECLARE
  event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  -- Extract event timestamp from JSONB data
  event_timestamp := to_timestamp((NEW.data->>'timestamp')::numeric);

  -- Fast path:
  -- Try to normalize the task event directly. In the common case, the workflow
  -- instance already exists because workflow events have already created it.
  BEGIN
    INSERT INTO task_instances (
      instance_id,
      task_name,
      task,           -- Changed from 'task_position'
      status,
      "startedAt",    -- Changed from 'start'
      "endedAt",      -- Changed from 'end'
      input,
      output,
      error_type,
      error_title,
      error_detail,
      error_status,
      error_instance,
      last_event_time
    )
    VALUES (
      NEW.data->>'instanceId',
      NEW.data->>'taskName',
      NEW.data->>'taskPosition',  -- Quarkus Flow uses 'taskPosition', we store in 'task' column
      NEW.data->>'status',
      -- Extract 'startedAt' timestamp (task.started event)
      CASE WHEN NEW.data->>'startTime' IS NOT NULL
        THEN to_timestamp((NEW.data->>'startTime')::numeric)
        ELSE NULL
      END,
      -- Extract 'endedAt' timestamp (task.completed/faulted/cancelled events)
      CASE WHEN NEW.data->>'endTime' IS NOT NULL
        THEN to_timestamp((NEW.data->>'endTime')::numeric)
        ELSE NULL
      END,
      NEW.data->'input',
      NEW.data->'output',
      NEW.data->'error'->>'type',
      NEW.data->'error'->>'title',
      NEW.data->'error'->>'detail',
      (NEW.data->'error'->>'status')::integer,
      NEW.data->'error'->>'instance',
      event_timestamp
    )
    ON CONFLICT (instance_id, task) DO UPDATE SET  -- Changed from 'task_position'
      -- Immutable fields: First event wins
      task_name = COALESCE(task_instances.task_name, EXCLUDED.task_name),
      "startedAt" = COALESCE(task_instances."startedAt", EXCLUDED."startedAt"),  -- Changed from 'start'
      input = COALESCE(task_instances.input, EXCLUDED.input),

      -- Terminal fields: Last non-null wins
      "endedAt" = COALESCE(EXCLUDED."endedAt", task_instances."endedAt"),  -- Changed from 'end'
      output = COALESCE(EXCLUDED.output, task_instances.output),
      error_type = COALESCE(EXCLUDED.error_type, task_instances.error_type),
      error_title = COALESCE(EXCLUDED.error_title, task_instances.error_title),
      error_detail = COALESCE(EXCLUDED.error_detail, task_instances.error_detail),
      error_status = COALESCE(EXCLUDED.error_status, task_instances.error_status),
      error_instance = COALESCE(EXCLUDED.error_instance, task_instances.error_instance),

      -- Status: Terminal states take precedence
      status = CASE
        WHEN EXCLUDED.status IN ('COMPLETED', 'FAULTED', 'CANCELLED') THEN EXCLUDED.status
        WHEN task_instances.status IN ('COMPLETED', 'FAULTED', 'CANCELLED') THEN task_instances.status
        ELSE EXCLUDED.status
      END,

      -- Timestamp tracking: Keep latest event timestamp
      last_event_time = GREATEST(event_timestamp, task_instances.last_event_time),

      -- Audit: Always update
      updated_at = NEW.time;

  EXCEPTION
    -- Slow path: Workflow instance doesn't exist yet (rare but possible)
    -- Create a placeholder workflow instance and retry the task insert
    WHEN foreign_key_violation THEN
      -- Create placeholder workflow with minimal data
      INSERT INTO workflow_instances (
        id,
        namespace,
        name,
        version,
        status,
        last_event_time
      )
      VALUES (
        NEW.data->>'instanceId',
        'unknown',  -- Placeholder - will be updated by workflow.started event
        'unknown',  -- Placeholder
        '0.0.0',    -- Placeholder
        'RUNNING',  -- Reasonable default
        event_timestamp
      )
      ON CONFLICT (id) DO NOTHING;  -- Race: another task already created it

      -- Retry task insert (workflow now exists)
      INSERT INTO task_instances (
        instance_id,
        task_name,
        task,           -- Changed from 'task_position'
        status,
        "startedAt",    -- Changed from 'start'
        "endedAt",      -- Changed from 'end'
        input,
        output,
        error_type,
        error_title,
        error_detail,
        error_status,
        error_instance,
        last_event_time
      )
      VALUES (
        NEW.data->>'instanceId',
        NEW.data->>'taskName',
        NEW.data->>'taskPosition',
        NEW.data->>'status',
        CASE WHEN NEW.data->>'startTime' IS NOT NULL
          THEN to_timestamp((NEW.data->>'startTime')::numeric)
          ELSE NULL
        END,
        CASE WHEN NEW.data->>'endTime' IS NOT NULL
          THEN to_timestamp((NEW.data->>'endTime')::numeric)
          ELSE NULL
        END,
        NEW.data->'input',
        NEW.data->'output',
        NEW.data->'error'->>'type',
        NEW.data->'error'->>'title',
        NEW.data->'error'->>'detail',
        (NEW.data->'error'->>'status')::integer,
        NEW.data->'error'->>'instance',
        event_timestamp
      )
      ON CONFLICT (instance_id, task) DO UPDATE SET  -- Changed from 'task_position'
        task_name = COALESCE(task_instances.task_name, EXCLUDED.task_name),
        "startedAt" = COALESCE(task_instances."startedAt", EXCLUDED."startedAt"),  -- Changed from 'start'
        input = COALESCE(task_instances.input, EXCLUDED.input),
        "endedAt" = COALESCE(EXCLUDED."endedAt", task_instances."endedAt"),  -- Changed from 'end'
        output = COALESCE(EXCLUDED.output, task_instances.output),
        error_type = COALESCE(EXCLUDED.error_type, task_instances.error_type),
        error_title = COALESCE(EXCLUDED.error_title, task_instances.error_title),
        error_detail = COALESCE(EXCLUDED.error_detail, task_instances.error_detail),
        error_status = COALESCE(EXCLUDED.error_status, task_instances.error_status),
        error_instance = COALESCE(EXCLUDED.error_instance, task_instances.error_instance),
        status = CASE
          WHEN EXCLUDED.status IN ('COMPLETED', 'FAULTED', 'CANCELLED') THEN EXCLUDED.status
          WHEN task_instances.status IN ('COMPLETED', 'FAULTED', 'CANCELLED') THEN task_instances.status
          ELSE EXCLUDED.status
        END,
        last_event_time = GREATEST(event_timestamp, task_instances.last_event_time),
        updated_at = NEW.time;
  END;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;
