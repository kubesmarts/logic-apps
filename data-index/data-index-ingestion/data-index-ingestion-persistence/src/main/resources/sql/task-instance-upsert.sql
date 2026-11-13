INSERT INTO task_instances (
  instance_id, task_name, task, status,
  started_at, ended_at, input, output,
  error_type, error_title, error_detail, error_status, error_instance,
  last_event_time, created_at, updated_at
) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, NOW(), NOW())
ON CONFLICT (instance_id, task) DO UPDATE SET
  instance_id = COALESCE(EXCLUDED.instance_id, task_instances.instance_id),
  task_name = COALESCE(EXCLUDED.task_name, task_instances.task_name),
  task = COALESCE(EXCLUDED.task, task_instances.task),
  status = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN EXCLUDED.status
    ELSE task_instances.status
  END,
  started_at = COALESCE(task_instances.started_at, EXCLUDED.started_at),
  input = COALESCE(task_instances.input, EXCLUDED.input),
  ended_at = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.ended_at, task_instances.ended_at)
    ELSE task_instances.ended_at
  END,
  output = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.output, task_instances.output)
    ELSE task_instances.output
  END,
  error_type = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_type, task_instances.error_type)
    ELSE task_instances.error_type
  END,
  error_title = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_title, task_instances.error_title)
    ELSE task_instances.error_title
  END,
  error_detail = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_detail, task_instances.error_detail)
    ELSE task_instances.error_detail
  END,
  error_status = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_status, task_instances.error_status)
    ELSE task_instances.error_status
  END,
  error_instance = CASE
    WHEN EXCLUDED.last_event_time >= task_instances.last_event_time
    THEN COALESCE(EXCLUDED.error_instance, task_instances.error_instance)
    ELSE task_instances.error_instance
  END,
  last_event_time = GREATEST(EXCLUDED.last_event_time, task_instances.last_event_time),
  updated_at = NOW()
