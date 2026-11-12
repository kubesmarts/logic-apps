INSERT INTO workflow_instances (id, created_at, updated_at, last_event_time)
VALUES (?, NOW(), NOW(), ?)
ON CONFLICT (id) DO NOTHING
