-- Create triggers on FluentBit-managed staging tables
--
-- This script is run AFTER FluentBit has created the staging tables
-- (workflow_instance_events, task_execution_events)
--
-- FluentBit creates tables with this structure:
--   - tag VARCHAR
--   - time TIMESTAMP
--   - data JSONB

-- Create trigger on workflow_instance_events (if table exists)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables
               WHERE table_schema = 'public'
               AND table_name = 'workflow_instance_events') THEN

        DROP TRIGGER IF EXISTS workflow_instance_event_trigger ON workflow_instance_events;

        CREATE TRIGGER workflow_instance_event_trigger
        AFTER INSERT ON workflow_instance_events
        FOR EACH ROW EXECUTE FUNCTION merge_workflow_instance_event();

        RAISE NOTICE 'Trigger created on workflow_instance_events';
    ELSE
        RAISE NOTICE 'Table workflow_instance_events does not exist yet (FluentBit will create it)';
    END IF;
END $$;

-- Create trigger on task_execution_events (if table exists)
DO $$
BEGIN
    IF EXISTS (SELECT FROM information_schema.tables
               WHERE table_schema = 'public'
               AND table_name = 'task_execution_events') THEN

        DROP TRIGGER IF EXISTS task_execution_event_trigger ON task_execution_events;

        CREATE TRIGGER task_execution_event_trigger
        AFTER INSERT ON task_execution_events
        FOR EACH ROW EXECUTE FUNCTION merge_task_execution_event();

        RAISE NOTICE 'Trigger created on task_execution_events';
    ELSE
        RAISE NOTICE 'Table task_execution_events does not exist yet (FluentBit will create it)';
    END IF;
END $$;
