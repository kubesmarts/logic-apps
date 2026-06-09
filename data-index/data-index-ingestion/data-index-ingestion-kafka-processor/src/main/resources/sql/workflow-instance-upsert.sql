INSERT INTO workflow_instances (
  id, namespace, name, version, status, start, "end", last_update,
  input, output, error_type, error_title, error_detail, error_status, error_instance,
  last_event_time, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, NOW(), NOW())
ON CONFLICT (id) DO UPDATE SET
  status = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN EXCLUDED.status
    ELSE workflow_instances.status
  END,
  namespace = COALESCE(workflow_instances.namespace, EXCLUDED.namespace),
  name = COALESCE(workflow_instances.name, EXCLUDED.name),
  version = COALESCE(workflow_instances.version, EXCLUDED.version),
  start = COALESCE(workflow_instances.start, EXCLUDED.start),
  input = COALESCE(workflow_instances.input, EXCLUDED.input),
  "end" = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED."end", workflow_instances."end")
    ELSE workflow_instances."end"
  END,
  output = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.output, workflow_instances.output)
    ELSE workflow_instances.output
  END,
  error_type = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_type, workflow_instances.error_type)
    ELSE workflow_instances.error_type
  END,
  error_title = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_title, workflow_instances.error_title)
    ELSE workflow_instances.error_title
  END,
  error_detail = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_detail, workflow_instances.error_detail)
    ELSE workflow_instances.error_detail
  END,
  error_status = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_status, workflow_instances.error_status)
    ELSE workflow_instances.error_status
  END,
  error_instance = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_instance, workflow_instances.error_instance)
    ELSE workflow_instances.error_instance
  END,
  last_update = CASE
    WHEN EXCLUDED.last_event_time >= workflow_instances.last_event_time
    THEN COALESCE(EXCLUDED.last_update, workflow_instances.last_update)
    ELSE workflow_instances.last_update
  END,
  last_event_time = GREATEST(EXCLUDED.last_event_time, workflow_instances.last_event_time),
  updated_at = NOW()
