--
-- Data Index v1.0.0 Test Data
-- Inserts sample workflow instances and task executions for testing GraphQL API
--

-- Clean up existing test data
DELETE FROM task_executions;
DELETE FROM workflow_instances;

-- Successful workflow instance (COMPLETED)
INSERT INTO workflow_instances (
    id, namespace, name, version, status,
    start, "end", last_update,
    input, output,
    error_type, error_title, error_detail, error_status, error_instance,
    created_at, updated_at
) VALUES (
    'wf-success-001',
    'default',
    'order-processing',
    '1.0.0',
    'COMPLETED',
    '2026-04-16 10:00:00+00',
    '2026-04-16 10:00:45+00',
    '2026-04-16 10:00:45+00',
    '{"orderId": "ORD-12345", "customerId": "CUST-001", "amount": 99.99}'::jsonb,
    '{"orderId": "ORD-12345", "status": "confirmed", "confirmationNumber": "CONF-98765"}'::jsonb,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NOW(),
    NOW()
);

-- Task 1: Validate order (successful)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-001-validate',
    'wf-success-001',
    'validateOrder',
    '/do/0',
    '2026-04-16 10:00:05+00',
    '2026-04-16 10:00:10+00',
    NULL,
    '{"orderId": "ORD-12345", "amount": 99.99}'::jsonb,
    '{"valid": true, "validationCode": "OK"}'::jsonb
);

-- Task 2: Process payment (successful)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-002-payment',
    'wf-success-001',
    'processPayment',
    '/do/1',
    '2026-04-16 10:00:10+00',
    '2026-04-16 10:00:25+00',
    NULL,
    '{"orderId": "ORD-12345", "amount": 99.99, "paymentMethod": "credit_card"}'::jsonb,
    '{"transactionId": "TXN-55555", "status": "approved"}'::jsonb
);

-- Task 3: Send confirmation (successful)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-003-confirm',
    'wf-success-001',
    'sendConfirmation',
    '/do/2',
    '2026-04-16 10:00:25+00',
    '2026-04-16 10:00:30+00',
    NULL,
    '{"orderId": "ORD-12345", "customerId": "CUST-001", "email": "customer@example.com"}'::jsonb,
    '{"emailSent": true, "confirmationNumber": "CONF-98765"}'::jsonb
);

-- Failed workflow instance (FAULTED)
INSERT INTO workflow_instances (
    id, namespace, name, version, status,
    start, "end", last_update,
    input, output,
    error_type, error_title, error_detail, error_status, error_instance,
    created_at, updated_at
) VALUES (
    'wf-failed-002',
    'default',
    'order-processing',
    '1.0.0',
    'FAULTED',
    '2026-04-16 10:05:00+00',
    '2026-04-16 10:05:20+00',
    '2026-04-16 10:05:20+00',
    '{"orderId": "ORD-67890", "customerId": "CUST-002", "amount": 250.00}'::jsonb,
    NULL,
    'https://serverlessworkflow.io/spec/1.0.0/errors/communication',
    'Payment Service Unavailable',
    'Failed to connect to payment gateway after 3 retry attempts',
    503,
    'wf-failed-002/processPayment',
    NOW(),
    NOW()
);

-- Task 1: Validate order (successful)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-004-validate',
    'wf-failed-002',
    'validateOrder',
    '/do/0',
    '2026-04-16 10:05:05+00',
    '2026-04-16 10:05:08+00',
    NULL,
    '{"orderId": "ORD-67890", "amount": 250.00}'::jsonb,
    '{"valid": true, "validationCode": "OK"}'::jsonb
);

-- Task 2: Process payment (FAILED)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-005-payment',
    'wf-failed-002',
    'processPayment',
    '/do/1',
    '2026-04-16 10:05:08+00',
    '2026-04-16 10:05:20+00',
    'Connection timeout: payment gateway did not respond within 5000ms',
    '{"orderId": "ORD-67890", "amount": 250.00, "paymentMethod": "credit_card"}'::jsonb,
    NULL
);

-- Running workflow instance (RUNNING)
INSERT INTO workflow_instances (
    id, namespace, name, version, status,
    start, "end", last_update,
    input, output,
    error_type, error_title, error_detail, error_status, error_instance,
    created_at, updated_at
) VALUES (
    'wf-running-003',
    'production',
    'inventory-sync',
    '2.1.0',
    'RUNNING',
    '2026-04-16 10:10:00+00',
    NULL,
    '2026-04-16 10:10:15+00',
    '{"warehouseId": "WH-EAST-01", "products": ["SKU-001", "SKU-002", "SKU-003"]}'::jsonb,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NOW(),
    NOW()
);

-- Task 1: Fetch inventory (completed)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-006-fetch',
    'wf-running-003',
    'fetchInventory',
    '/do/0',
    '2026-04-16 10:10:02+00',
    '2026-04-16 10:10:08+00',
    NULL,
    '{"warehouseId": "WH-EAST-01"}'::jsonb,
    '{"inventory": [{"sku": "SKU-001", "quantity": 100}, {"sku": "SKU-002", "quantity": 50}]}'::jsonb
);

-- Task 2: Update database (in progress - no exit time)
INSERT INTO task_executions (
    id, workflow_instance_id, task_name, task_position,
    enter, exit, error_message,
    input_args, output_args
) VALUES (
    'task-007-update',
    'wf-running-003',
    'updateDatabase',
    '/do/1',
    '2026-04-16 10:10:08+00',
    NULL,
    NULL,
    '{"inventory": [{"sku": "SKU-001", "quantity": 100}]}'::jsonb,
    NULL
);

-- Cancelled workflow instance
INSERT INTO workflow_instances (
    id, namespace, name, version, status,
    start, "end", last_update,
    input, output,
    error_type, error_title, error_detail, error_status, error_instance,
    created_at, updated_at
) VALUES (
    'wf-cancelled-004',
    'staging',
    'data-migration',
    '1.0.0',
    'CANCELLED',
    '2026-04-16 09:00:00+00',
    '2026-04-16 09:15:30+00',
    '2026-04-16 09:15:30+00',
    '{"sourceDb": "mysql-prod", "targetDb": "postgres-staging", "tables": ["users", "orders"]}'::jsonb,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NULL,
    NOW(),
    NOW()
);

-- Verification queries
SELECT 'Workflow Instances:' AS info;
SELECT id, namespace, name, status, start, "end" FROM workflow_instances ORDER BY start DESC;

SELECT '' AS separator;
SELECT 'Task Executions:' AS info;
SELECT id, workflow_instance_id, task_name, task_position, enter, exit, error_message FROM task_executions ORDER BY enter;

SELECT '' AS separator;
SELECT 'Summary:' AS info;
SELECT
    status,
    COUNT(*) as count
FROM workflow_instances
GROUP BY status;
