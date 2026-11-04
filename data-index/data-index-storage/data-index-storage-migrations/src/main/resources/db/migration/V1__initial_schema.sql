-- ============================================================================
-- Data Index v2.0.0 - JDBC Batch Normalization with Idempotency
-- ============================================================================
--
-- This schema includes:
-- - Staging tables (typed columns, populated by Vector; append-only inbox)
-- - Normalized tables with idempotency fields (GraphQL API queries these;
--   populated by a batch JDBC reader - see data-index-ingestion-jdbc-batch-*,
--   reusing the same idempotent UPSERT persistence as MODE 3's Kafka path -
--   NOT by triggers)
-- - Dead-letter tables for staging rows that fail normalization
--
-- ============================================================================

-- ============================================================================
-- STAGING TABLES (typed columns; Vector inserts one row per event)
-- ============================================================================
--
-- Append-only inbox, not a keyed table: no ON CONFLICT on insert, idempotency
-- happens downstream in the normalized-table UPSERT. A row is deleted as soon
-- as the JDBC batch reader has successfully normalized it - no retention
-- window, the table stays near-empty.

-- Vector's native `postgres` sink always inserts via
-- `jsonb_populate_recordset(null::<table>, ...)`, which builds each row from
-- the table's full row type - any column missing from the VRL-produced JSON
-- (id included) becomes an explicit NULL, bypassing the BIGSERIAL column's
-- own DEFAULT entirely. The BEFORE INSERT trigger below fills `id` whenever
-- it comes in NULL, regardless of whether the column was omitted or set
-- explicitly, so the JDBC batch reader can select/delete by `id` like any
-- normal PK. `inserted_at` must still be supplied by VRL (`now()`), not left
-- to its DEFAULT, since the same bypass applies to it.
CREATE TABLE IF NOT EXISTS workflow_events_raw (
  id BIGSERIAL PRIMARY KEY,
  instance_id VARCHAR(255) NOT NULL,
  namespace VARCHAR(255),
  name VARCHAR(255),
  version VARCHAR(255),
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  last_update TIMESTAMP WITH TIME ZONE,
  input TEXT,
  output TEXT,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  event_timestamp TIMESTAMP WITH TIME ZONE,
  inserted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_events_raw_instance_id ON workflow_events_raw (instance_id);
CREATE INDEX IF NOT EXISTS idx_workflow_events_raw_inserted_at ON workflow_events_raw (inserted_at);

CREATE OR REPLACE FUNCTION set_workflow_events_raw_id() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.id IS NULL THEN
    NEW.id := nextval('workflow_events_raw_id_seq');
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_workflow_events_raw_id
  BEFORE INSERT ON workflow_events_raw
  FOR EACH ROW
  EXECUTE FUNCTION set_workflow_events_raw_id();

CREATE TABLE IF NOT EXISTS task_events_raw (
  id BIGSERIAL PRIMARY KEY,
  instance_id VARCHAR(255) NOT NULL,
  task_name VARCHAR(255),
  task VARCHAR(255) NOT NULL,
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  input TEXT,
  output TEXT,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  event_timestamp TIMESTAMP WITH TIME ZONE,
  inserted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_task_events_raw_instance_id ON task_events_raw (instance_id);
CREATE INDEX IF NOT EXISTS idx_task_events_raw_inserted_at ON task_events_raw (inserted_at);

CREATE OR REPLACE FUNCTION set_task_events_raw_id() RETURNS TRIGGER AS $$
BEGIN
  IF NEW.id IS NULL THEN
    NEW.id := nextval('task_events_raw_id_seq');
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_set_task_events_raw_id
  BEFORE INSERT ON task_events_raw
  FOR EACH ROW
  EXECUTE FUNCTION set_task_events_raw_id();

-- ============================================================================
-- DEAD-LETTER TABLES
-- ============================================================================
--
-- A staging row that fails normalization (not the self-healing
-- task-before-workflow case - that's handled by TaskPersistence's existing
-- placeholder-insert fallback) moves here instead of being retried in place,
-- so one bad row can never block "ORDER BY inserted_at LIMIT :batchSize" from
-- making progress on every row behind it. No original_id column - the
-- source row is deleted in the same transaction that inserts here, so
-- there's nothing left to reference back to.

CREATE TABLE IF NOT EXISTS workflow_events_dlq (
  id BIGSERIAL PRIMARY KEY,
  instance_id VARCHAR(255),
  namespace VARCHAR(255),
  name VARCHAR(255),
  version VARCHAR(255),
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  last_update TIMESTAMP WITH TIME ZONE,
  input TEXT,
  output TEXT,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  event_timestamp TIMESTAMP WITH TIME ZONE,
  failure_reason TEXT NOT NULL,
  failed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_events_dlq_instance_id ON workflow_events_dlq (instance_id);

CREATE TABLE IF NOT EXISTS task_events_dlq (
  id BIGSERIAL PRIMARY KEY,
  instance_id VARCHAR(255),
  task_name VARCHAR(255),
  task VARCHAR(255),
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  input TEXT,
  output TEXT,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  event_timestamp TIMESTAMP WITH TIME ZONE,
  failure_reason TEXT NOT NULL,
  failed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_task_events_dlq_instance_id ON task_events_dlq (instance_id);

-- ============================================================================
-- NORMALIZED TABLES (GraphQL API queries these)
-- ============================================================================

CREATE TABLE IF NOT EXISTS workflow_instances (
  id VARCHAR(255) PRIMARY KEY,
  namespace VARCHAR(255),
  name VARCHAR(255),
  version VARCHAR(255),
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  last_update TIMESTAMP WITH TIME ZONE,
  last_event_time TIMESTAMP WITH TIME ZONE,  -- Idempotency: track event timestamp
  input JSONB,
  output JSONB,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_workflow_instances_namespace_name ON workflow_instances (namespace, name);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_status ON workflow_instances (status);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_startedAt ON workflow_instances (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_workflow_instances_last_event_time ON workflow_instances (last_event_time DESC);

CREATE TABLE IF NOT EXISTS task_instances (
  instance_id VARCHAR(255) NOT NULL,
  task_name VARCHAR(255),
  task VARCHAR(255) NOT NULL,
  status VARCHAR(50),
  started_at TIMESTAMP WITH TIME ZONE,
  ended_at TIMESTAMP WITH TIME ZONE,
  last_event_time TIMESTAMP WITH TIME ZONE,  -- Idempotency: track event timestamp
  input JSONB,
  output JSONB,
  error_type VARCHAR(255),
  error_title VARCHAR(255),
  error_detail TEXT,
  error_status INTEGER,
  error_instance VARCHAR(255),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  -- Composite primary key: (instance_id, task) uniquely identifies a task
  -- TaskExecution.id is derived from instanceId + task (no column needed)
  PRIMARY KEY (instance_id, task),
  CONSTRAINT fk_task_instance_workflow FOREIGN KEY (instance_id) REFERENCES workflow_instances(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_task_instances_instance_id ON task_instances (instance_id);
CREATE INDEX IF NOT EXISTS idx_task_instances_status ON task_instances (status);
CREATE INDEX IF NOT EXISTS idx_task_instances_last_event_time ON task_instances (last_event_time DESC);
