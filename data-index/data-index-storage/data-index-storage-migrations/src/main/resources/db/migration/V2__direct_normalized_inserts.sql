-- ============================================================================
-- Direct normalized-table inserts (replaces V1's raw staging tables)
-- ============================================================================
--
-- Vector now inserts directly into workflow_instances/task_instances as
-- {raw_event: <json>} rows. Triggers self-target these same tables via
-- INSERT ... ON CONFLICT DO UPDATE, guarded by pg_trigger_depth() so the
-- nested self-insert doesn't recurse. raw_event holds only the latest event
-- per row (no full history).
-- ============================================================================

ALTER TABLE workflow_instances ADD COLUMN raw_event JSONB;
ALTER TABLE task_instances ADD COLUMN raw_event JSONB;

DROP TRIGGER IF EXISTS normalize_workflow_events ON workflow_events_raw;
DROP TRIGGER IF EXISTS normalize_task_events ON task_events_raw;
DROP FUNCTION IF EXISTS normalize_workflow_event();
DROP FUNCTION IF EXISTS normalize_task_event();

CREATE OR REPLACE FUNCTION normalize_workflow_instance()
RETURNS TRIGGER AS $$
DECLARE
  v_id VARCHAR(255);
  v_namespace VARCHAR(255);
  v_name VARCHAR(255);
  v_version VARCHAR(255);
  v_status VARCHAR(50);
  v_started_at TIMESTAMP WITH TIME ZONE;
  v_ended_at TIMESTAMP WITH TIME ZONE;
  v_last_update TIMESTAMP WITH TIME ZONE;
  v_input JSONB;
  v_output JSONB;
  v_error_type VARCHAR(255);
  v_error_title VARCHAR(255);
  v_error_detail TEXT;
  v_error_status INTEGER;
  v_error_instance VARCHAR(255);
  v_event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  -- Nested call from our own INSERT below; let it proceed as-is.
  IF pg_trigger_depth() > 1 THEN
    RETURN NEW;
  END IF;

  -- Placeholder/JPA-inserted rows have no raw_event; pass through untouched.
  IF NEW.raw_event IS NULL THEN
    RETURN NEW;
  END IF;

  v_id := NEW.raw_event->>'instanceId';
  v_namespace := NEW.raw_event->>'workflowNamespace';
  v_name := NEW.raw_event->>'workflowName';
  v_version := NEW.raw_event->>'workflowVersion';
  v_status := NEW.raw_event->>'status';
  v_started_at := to_timestamp((NEW.raw_event->>'startTime')::numeric);
  v_ended_at := to_timestamp((NEW.raw_event->>'endTime')::numeric);
  v_last_update := to_timestamp((NEW.raw_event->>'lastUpdateTime')::numeric);
  v_input := NEW.raw_event->'input';
  v_output := NEW.raw_event->'output';
  v_error_type := NEW.raw_event->'error'->>'type';
  v_error_title := NEW.raw_event->'error'->>'title';
  v_error_detail := NEW.raw_event->'error'->>'detail';
  v_error_status := (NEW.raw_event->'error'->>'status')::integer;
  v_error_instance := NEW.raw_event->'error'->>'instance';
  v_event_timestamp := to_timestamp((NEW.raw_event->>'timestamp')::numeric);

  INSERT INTO workflow_instances (
    id, namespace, name, version, status, started_at, ended_at, last_update,
    input, output, error_type, error_title, error_detail, error_status, error_instance,
    last_event_time, raw_event, created_at, updated_at
  ) VALUES (
    v_id, v_namespace, v_name, v_version, v_status, v_started_at, v_ended_at, v_last_update,
    v_input, v_output, v_error_type, v_error_title, v_error_detail, v_error_status, v_error_instance,
    v_event_timestamp, NEW.raw_event, now(), now()
  )
  ON CONFLICT (id) DO UPDATE SET
    -- Status/timestamps: newer event wins. Immutable fields: first write
    -- wins. Terminal fields: latest non-null wins, never cleared.
    status = CASE
      WHEN v_event_timestamp > workflow_instances.last_event_time
      THEN v_status
      ELSE workflow_instances.status
    END,
    namespace = COALESCE(workflow_instances.namespace, v_namespace),
    name = COALESCE(workflow_instances.name, v_name),
    version = COALESCE(workflow_instances.version, v_version),
    started_at = COALESCE(workflow_instances.started_at, v_started_at),
    input = COALESCE(workflow_instances.input, v_input),
    ended_at = COALESCE(v_ended_at, workflow_instances.ended_at),
    output = COALESCE(v_output, workflow_instances.output),
    error_type = COALESCE(v_error_type, workflow_instances.error_type),
    error_title = COALESCE(v_error_title, workflow_instances.error_title),
    error_detail = COALESCE(v_error_detail, workflow_instances.error_detail),
    error_status = COALESCE(v_error_status, workflow_instances.error_status),
    error_instance = COALESCE(v_error_instance, workflow_instances.error_instance),
    last_update = GREATEST(v_last_update, workflow_instances.last_update),
    last_event_time = GREATEST(v_event_timestamp, workflow_instances.last_event_time),
    raw_event = NEW.raw_event,
    updated_at = now();

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER normalize_workflow_instances
  BEFORE INSERT ON workflow_instances
  FOR EACH ROW
  EXECUTE FUNCTION normalize_workflow_instance();

CREATE OR REPLACE FUNCTION normalize_task_instance()
RETURNS TRIGGER AS $$
DECLARE
  v_instance_id VARCHAR(255);
  v_task VARCHAR(255);
  v_task_name VARCHAR(255);
  v_status VARCHAR(50);
  v_started_at TIMESTAMP WITH TIME ZONE;
  v_ended_at TIMESTAMP WITH TIME ZONE;
  v_input JSONB;
  v_output JSONB;
  v_error_type VARCHAR(255);
  v_error_title VARCHAR(255);
  v_error_detail TEXT;
  v_error_status INTEGER;
  v_error_instance VARCHAR(255);
  v_event_timestamp TIMESTAMP WITH TIME ZONE;
BEGIN
  IF pg_trigger_depth() > 1 THEN
    RETURN NEW;
  END IF;

  IF NEW.raw_event IS NULL THEN
    RETURN NEW;
  END IF;

  v_instance_id := NEW.raw_event->>'instanceId';
  v_task := NEW.raw_event->>'taskPosition';
  v_task_name := NEW.raw_event->>'taskName';
  v_status := NEW.raw_event->>'status';
  v_started_at := to_timestamp((NEW.raw_event->>'startTime')::numeric);
  v_ended_at := to_timestamp((NEW.raw_event->>'endTime')::numeric);
  v_input := NEW.raw_event->'input';
  v_output := NEW.raw_event->'output';
  v_error_type := NEW.raw_event->'error'->>'type';
  v_error_title := NEW.raw_event->'error'->>'title';
  v_error_detail := NEW.raw_event->'error'->>'detail';
  v_error_status := (NEW.raw_event->'error'->>'status')::integer;
  v_error_instance := NEW.raw_event->'error'->>'instance';
  v_event_timestamp := to_timestamp((NEW.raw_event->>'timestamp')::numeric);

  -- Backfill the parent workflow row if this task event arrived first.
  IF NOT EXISTS (SELECT 1 FROM workflow_instances WHERE id = v_instance_id) THEN
    INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
    VALUES (v_instance_id, now(), now(), v_event_timestamp)
    ON CONFLICT (id) DO NOTHING;
  END IF;

  INSERT INTO task_instances (
    instance_id, task, task_name, status, started_at, ended_at,
    input, output, error_type, error_title, error_detail, error_status, error_instance,
    last_event_time, raw_event, created_at, updated_at
  ) VALUES (
    v_instance_id, v_task, v_task_name, v_status, v_started_at, v_ended_at,
    v_input, v_output, v_error_type, v_error_title, v_error_detail, v_error_status, v_error_instance,
    v_event_timestamp, NEW.raw_event, now(), now()
  )
  ON CONFLICT (instance_id, task) DO UPDATE SET
    task_name = COALESCE(task_instances.task_name, v_task_name),
    status = CASE
      WHEN v_event_timestamp >= task_instances.last_event_time
      THEN v_status
      ELSE task_instances.status
    END,
    started_at = COALESCE(task_instances.started_at, v_started_at),
    ended_at = COALESCE(v_ended_at, task_instances.ended_at),
    input = COALESCE(task_instances.input, v_input),
    output = COALESCE(v_output, task_instances.output),
    error_type = COALESCE(v_error_type, task_instances.error_type),
    error_title = COALESCE(v_error_title, task_instances.error_title),
    error_detail = COALESCE(v_error_detail, task_instances.error_detail),
    error_status = COALESCE(v_error_status, task_instances.error_status),
    error_instance = COALESCE(v_error_instance, task_instances.error_instance),
    last_event_time = GREATEST(v_event_timestamp, task_instances.last_event_time),
    raw_event = NEW.raw_event,
    updated_at = now();

  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER normalize_task_instances
  BEFORE INSERT ON task_instances
  FOR EACH ROW
  EXECUTE FUNCTION normalize_task_instance();

DROP TABLE IF EXISTS workflow_events_raw;
DROP TABLE IF EXISTS task_events_raw;
