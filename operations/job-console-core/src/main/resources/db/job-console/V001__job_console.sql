CREATE TABLE job_requests (
    tenant_id VARCHAR(64) NOT NULL,
    submitter_hash CHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    request_fingerprint CHAR(64) NOT NULL,
    job_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, submitter_hash, key_hash)
);

CREATE TABLE jobs (
    job_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    submitter_hash CHAR(64) NOT NULL,
    job_type VARCHAR(48) NOT NULL,
    work_units INTEGER NOT NULL CHECK (work_units > 0),
    failure_mode VARCHAR(32) NOT NULL,
    enqueue_sequence BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    queue_version BIGINT NOT NULL DEFAULT 1,
    lease_token UUID,
    lease_expires_at TIMESTAMPTZ,
    progress INTEGER NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
    retry_budget INTEGER NOT NULL DEFAULT 2 CHECK (retry_budget >= 0),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    version BIGINT NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id, enqueue_sequence)
);

CREATE UNIQUE INDEX uq_job_active_per_tenant
ON jobs (tenant_id)
WHERE state IN ('running', 'cancel_requested');

CREATE INDEX ix_job_tenant_queue
ON jobs (tenant_id, state, enqueue_sequence)
INCLUDE (job_id, progress, queue_version, updated_at);

CREATE INDEX ix_job_expired_lease
ON jobs (lease_expires_at, tenant_id)
WHERE state IN ('running', 'cancel_requested');

CREATE TABLE job_checkpoints (
    job_id UUID PRIMARY KEY REFERENCES jobs(job_id) ON DELETE CASCADE,
    lease_token UUID NOT NULL,
    completed_chunk BIGINT NOT NULL,
    resume_cursor VARCHAR(256),
    progress INTEGER NOT NULL CHECK (progress BETWEEN 0 AND 100),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE job_attempts (
    job_id UUID NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    attempt INTEGER NOT NULL,
    lease_token UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at TIMESTAMPTZ,
    failure_class VARCHAR(64),
    PRIMARY KEY (job_id, attempt)
);

CREATE TABLE job_outbox (
    event_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    event_type VARCHAR(48) NOT NULL,
    resource_version BIGINT NOT NULL,
    queue_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    claim_token UUID,
    claim_expires_at TIMESTAMPTZ
);

CREATE INDEX ix_job_outbox_pending
ON job_outbox (occurred_at, event_id)
WHERE published_at IS NULL;

CREATE TABLE job_history (
    history_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(job_id) ON DELETE CASCADE,
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    resource_version BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    UNIQUE (job_id, resource_version, to_state)
);

CREATE TABLE job_duration_samples (
    sample_id BIGSERIAL PRIMARY KEY,
    job_type VARCHAR(48) NOT NULL,
    duration_millis BIGINT NOT NULL CHECK (duration_millis > 0),
    completed_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ix_job_duration_samples
ON job_duration_samples (job_type, completed_at DESC, sample_id DESC);
