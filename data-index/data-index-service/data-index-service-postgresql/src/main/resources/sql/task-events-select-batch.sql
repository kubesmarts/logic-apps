SELECT id, instance_id, task_name, task, status, started_at, ended_at,
       input, output, error_type, error_title, error_detail,
       error_status, error_instance, event_timestamp
FROM task_events_raw
ORDER BY inserted_at
LIMIT ?
FOR UPDATE SKIP LOCKED
