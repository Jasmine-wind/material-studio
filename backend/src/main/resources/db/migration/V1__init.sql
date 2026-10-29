CREATE TABLE assets (
  id VARCHAR(36) PRIMARY KEY,
  owner_id VARCHAR(16) NOT NULL,
  original_name VARCHAR(255) NOT NULL,
  mime_type VARCHAR(32) NOT NULL,
  size_bytes BIGINT NOT NULL,
  storage_key VARCHAR(80) NOT NULL UNIQUE,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE TABLE tasks (
  id VARCHAR(36) PRIMARY KEY,
  owner_id VARCHAR(16) NOT NULL,
  template_id VARCHAR(40) NOT NULL,
  status VARCHAR(16) NOT NULL,
  stage VARCHAR(24) NOT NULL,
  failed_stage VARCHAR(24),
  error_message VARCHAR(500),
  result_storage_key VARCHAR(80),
  simulate_fail_stage VARCHAR(24),
  simulate_delay_ms INT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  finished_at TIMESTAMP(6),
  CONSTRAINT task_status CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  CONSTRAINT task_stage CHECK (stage IN ('NONE','CHECK_MATERIAL','GENERATE','FINALIZE'))
);
CREATE INDEX idx_tasks_owner_created ON tasks(owner_id, created_at);
CREATE TABLE task_assets (
  task_id VARCHAR(36) NOT NULL,
  position INT NOT NULL,
  asset_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (task_id, position),
  FOREIGN KEY (task_id) REFERENCES tasks(id),
  FOREIGN KEY (asset_id) REFERENCES assets(id)
);
