-- ============================================================================
-- Migration: Fix task_instances composite key for proper event normalization
-- ============================================================================
--
-- Issue: Quarkus Flow generates different taskExecutionId for each event
--        (started, completed, faulted) for the same task execution.
--        Current PK on task_execution_id causes duplicate rows instead of updates.
--
-- Solution: Use composite key (instance_id, task_position) which uniquely
--           identifies a task execution within a workflow.
--
-- Impact: Ensures one row per task execution, with UPDATEs on subsequent events.
--
-- ============================================================================

-- Drop existing primary key constraint
ALTER TABLE task_instances DROP CONSTRAINT IF EXISTS task_instances_pkey;

-- Add composite primary key on (instance_id, task_position)
-- This uniquely identifies a task execution within a workflow
ALTER TABLE task_instances ADD PRIMARY KEY (instance_id, task_position);

-- Keep task_execution_id as a regular column (still needed for GraphQL API)
-- Add index on task_execution_id for queries by ID
CREATE INDEX IF NOT EXISTS idx_task_instances_task_execution_id ON task_instances(task_execution_id);

-- ============================================================================
-- Update trigger function to use composite key for conflict resolution
-- ============================================================================

CREATE OR REPLACE FUNCTION normalize_task_event()
RETURNS TRIGGER AS $$
DECLARE
  event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  -- Extract timestamp from event data (epoch seconds with nanosecond precision)
  event_timestamp := to_timestamp((NEW.data->>'timestamp')::numeric);

  -- UPSERT task instance
  -- Use composite key (instance_id, task_position) for conflict resolution
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
  )
  VALUES (
    NEW.data->>'taskExecutionId',
    NEW.data->>'instanceId',
    NEW.data->>'taskName',
    NEW.data->>'taskPosition',
    NEW.data->>'status',
    CASE
      WHEN NEW.data ? 'startTime'
      THEN to_timestamp((NEW.data->>'startTime')::numeric)
      ELSE NULL
    END,
    CASE
      WHEN NEW.data ? 'endTime'
      THEN to_timestamp((NEW.data->>'endTime')::numeric)
      ELSE NULL
    END,
    NEW.data->'input',
    NEW.data->'output',
    NEW.data->'error'->>'type',
    NEW.data->'error'->>'title',
    NEW.data->'error'->>'detail',
    CASE
      WHEN NEW.data->'error' ? 'status'
      THEN (NEW.data->'error'->>'status')::integer
      ELSE NULL
    END,
    NEW.data->'error'->>'instance',
    event_timestamp,
    NEW.time,
    NEW.time
  )
  ON CONFLICT (instance_id, task_position) DO UPDATE SET
    -- Update task_execution_id to the latest (even though it changes)
    task_execution_id = EXCLUDED.task_execution_id,

    -- Keep first non-null values (immutable fields)
    task_name = COALESCE(task_instances.task_name, EXCLUDED.task_name),
    instance_id = COALESCE(task_instances.instance_id, EXCLUDED.instance_id),
    task_position = COALESCE(task_instances.task_position, EXCLUDED.task_position),

    -- Update status based on event timestamp (latest wins)
    status = CASE
      WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
      THEN EXCLUDED.status
      ELSE task_instances.status
    END,

    -- Keep first start time, update end time with latest non-null value
    start = COALESCE(task_instances.start, EXCLUDED.start),
    "end" = COALESCE(EXCLUDED."end", task_instances."end"),

    -- Keep first input, update output with latest non-null value
    input = COALESCE(task_instances.input, EXCLUDED.input),
    output = COALESCE(EXCLUDED.output, task_instances.output),

    -- Update error fields with latest non-null values
    error_type = COALESCE(EXCLUDED.error_type, task_instances.error_type),
    error_title = COALESCE(EXCLUDED.error_title, task_instances.error_title),
    error_detail = COALESCE(EXCLUDED.error_detail, task_instances.error_detail),
    error_status = COALESCE(EXCLUDED.error_status, task_instances.error_status),
    error_instance = COALESCE(EXCLUDED.error_instance, task_instances.error_instance),

    -- Track latest event time and update timestamp
    last_event_time = GREATEST(EXCLUDED.last_event_time, task_instances.last_event_time),
    updated_at = EXCLUDED.updated_at;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ============================================================================
-- Cleanup: Remove duplicate task instances (keep latest by last_event_time)
-- ============================================================================

-- Delete duplicate task instances, keeping only the one with latest event
DELETE FROM task_instances t1
USING task_instances t2
WHERE t1.instance_id = t2.instance_id
  AND t1.task_position = t2.task_position
  AND t1.last_event_time < t2.last_event_time;

-- ============================================================================
-- Verification: Check for remaining duplicates
-- ============================================================================

DO $$
DECLARE
  duplicate_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT instance_id, task_position, COUNT(*) as cnt
    FROM task_instances
    GROUP BY instance_id, task_position
    HAVING COUNT(*) > 1
  ) duplicates;

  IF duplicate_count > 0 THEN
    RAISE WARNING 'Found % duplicate task instances after cleanup', duplicate_count;
  ELSE
    RAISE NOTICE 'No duplicate task instances found - migration successful';
  END IF;
END $$;
