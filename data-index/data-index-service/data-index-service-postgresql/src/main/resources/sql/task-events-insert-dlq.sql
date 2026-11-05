INSERT INTO task_events_dlq (
  instance_id, task_name, task, status, started_at, ended_at,
  input, output, error_type, error_title, error_detail, error_status,
  error_instance, event_timestamp, failure_reason
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
