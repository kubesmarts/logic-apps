-- ============================================================================
-- Direct normalized-table inserts (replaces V1's raw staging tables)
-- ============================================================================
--
-- Vector now extracts and types each field in VRL (data-index/collectors/vector/
-- mode1-postgresql/vector.yaml) and inserts workflow_instances/task_instances
-- directly, one named column per field - Vector's postgres sink maps JSON keys
-- to columns via jsonb_populate_recordset, so no custom sink logic is needed
-- beyond the row shape VRL builds.
--
-- Triggers self-target these same tables via INSERT ... ON CONFLICT DO UPDATE,
-- guarded by pg_trigger_depth() so the nested self-insert doesn't recurse. They
-- read NEW.<column> directly instead of parsing JSON.
--
-- from_event distinguishes a Vector event (needs the merge/upsert below) from
-- a direct writer like a JPA test fixture (should insert plain, untouched).
-- This isn't optional: a BEFORE INSERT trigger that RETURN NULLs to cancel the
-- original statement makes Postgres report 0 rows affected for THAT statement,
-- even though the nested INSERT below succeeds - Hibernate's optimistic-lock
-- row-count check then fails on every single em.persist(), not just conflicts.
-- Entities never need to set this column; it defaults to false.
-- ============================================================================

DROP TRIGGER IF EXISTS normalize_workflow_events ON workflow_events_raw;
DROP TRIGGER IF EXISTS normalize_task_events ON task_events_raw;
DROP FUNCTION IF EXISTS normalize_workflow_event();
DROP FUNCTION IF EXISTS normalize_task_event();

ALTER TABLE workflow_instances ADD COLUMN from_event BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE task_instances ADD COLUMN from_event BOOLEAN NOT NULL DEFAULT false;

CREATE OR REPLACE FUNCTION normalize_workflow_instance()
RETURNS TRIGGER AS $$
BEGIN
  -- Nested call from our own INSERT below; let it proceed as-is.
  IF pg_trigger_depth() > 1 THEN
    RETURN NEW;
  END IF;

  -- Direct writer (JPA, etc.): insert as given, no merge.
  IF NOT NEW.from_event THEN
    RETURN NEW;
  END IF;

  INSERT INTO workflow_instances (
    id, namespace, name, version, status, started_at, ended_at, last_update,
    input, output, error_type, error_title, error_detail, error_status, error_instance,
    last_event_time, from_event, created_at, updated_at
  ) VALUES (
    NEW.id, NEW.namespace, NEW.name, NEW.version, NEW.status, NEW.started_at, NEW.ended_at, NEW.last_update,
    NEW.input, NEW.output, NEW.error_type, NEW.error_title, NEW.error_detail, NEW.error_status, NEW.error_instance,
    NEW.last_event_time, true, now(), now()
  )
  ON CONFLICT (id) DO UPDATE SET
    -- Status/timestamps: newer event wins. Immutable fields: first write
    -- wins. Terminal fields: latest non-null wins, never cleared.
    status = CASE
      WHEN NEW.last_event_time >= workflow_instances.last_event_time
      THEN NEW.status
      ELSE workflow_instances.status
    END,
    namespace = COALESCE(workflow_instances.namespace, NEW.namespace),
    name = COALESCE(workflow_instances.name, NEW.name),
    version = COALESCE(workflow_instances.version, NEW.version),
    started_at = COALESCE(workflow_instances.started_at, NEW.started_at),
    input = COALESCE(workflow_instances.input, NEW.input),
    ended_at = COALESCE(NEW.ended_at, workflow_instances.ended_at),
    output = COALESCE(NEW.output, workflow_instances.output),
    error_type = COALESCE(NEW.error_type, workflow_instances.error_type),
    error_title = COALESCE(NEW.error_title, workflow_instances.error_title),
    error_detail = COALESCE(NEW.error_detail, workflow_instances.error_detail),
    error_status = COALESCE(NEW.error_status, workflow_instances.error_status),
    error_instance = COALESCE(NEW.error_instance, workflow_instances.error_instance),
    last_update = GREATEST(NEW.last_update, workflow_instances.last_update),
    last_event_time = GREATEST(NEW.last_event_time, workflow_instances.last_event_time),
    -- The row may have been created first by the task backfill below
    -- (from_event defaults false there); a real event landing on it now
    -- means it's event-sourced from here on.
    from_event = true,
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
BEGIN
  IF pg_trigger_depth() > 1 THEN
    RETURN NEW;
  END IF;

  -- Direct writer (JPA, etc.): insert as given, no merge.
  IF NOT NEW.from_event THEN
    RETURN NEW;
  END IF;

  -- Backfill the parent workflow row if this task event arrived first.
  IF NOT EXISTS (SELECT 1 FROM workflow_instances WHERE id = NEW.instance_id) THEN
    INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
    VALUES (NEW.instance_id, now(), now(), NEW.last_event_time)
    ON CONFLICT (id) DO NOTHING;
  END IF;

  INSERT INTO task_instances (
    instance_id, task, task_name, status, started_at, ended_at,
    input, output, error_type, error_title, error_detail, error_status, error_instance,
    last_event_time, from_event, created_at, updated_at
  ) VALUES (
    NEW.instance_id, NEW.task, NEW.task_name, NEW.status, NEW.started_at, NEW.ended_at,
    NEW.input, NEW.output, NEW.error_type, NEW.error_title, NEW.error_detail, NEW.error_status, NEW.error_instance,
    NEW.last_event_time, true, now(), now()
  )
  ON CONFLICT (instance_id, task) DO UPDATE SET
    task_name = COALESCE(task_instances.task_name, NEW.task_name),
    status = CASE
      WHEN NEW.last_event_time >= task_instances.last_event_time
      THEN NEW.status
      ELSE task_instances.status
    END,
    started_at = COALESCE(task_instances.started_at, NEW.started_at),
    ended_at = COALESCE(NEW.ended_at, task_instances.ended_at),
    input = COALESCE(task_instances.input, NEW.input),
    output = COALESCE(NEW.output, task_instances.output),
    error_type = COALESCE(NEW.error_type, task_instances.error_type),
    error_title = COALESCE(NEW.error_title, task_instances.error_title),
    error_detail = COALESCE(NEW.error_detail, task_instances.error_detail),
    error_status = COALESCE(NEW.error_status, task_instances.error_status),
    error_instance = COALESCE(NEW.error_instance, task_instances.error_instance),
    last_event_time = GREATEST(NEW.last_event_time, task_instances.last_event_time),
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
