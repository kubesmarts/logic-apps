SELECT id, instance_id, namespace, name, version, workflow_application_id, status, started_at, ended_at,
       last_update, input, output, error_type, error_title, error_detail,
       error_status, error_instance, event_timestamp
FROM workflow_events_raw
ORDER BY inserted_at
LIMIT ?
FOR UPDATE SKIP LOCKED
