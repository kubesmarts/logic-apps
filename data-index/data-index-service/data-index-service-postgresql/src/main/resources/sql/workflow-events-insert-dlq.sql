INSERT INTO workflow_events_dlq (
  instance_id, namespace, name, version, status, started_at, ended_at,
  last_update, input, output, error_type, error_title, error_detail, error_status,
  error_instance, event_timestamp, failure_reason
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
