ALTER TABLE job_requests
    ADD COLUMN state VARCHAR(16) NOT NULL DEFAULT 'TERMINAL',
    ADD COLUMN generation BIGINT NOT NULL DEFAULT 1 CHECK (generation > 0),
    ADD COLUMN owner_token UUID,
    ADD COLUMN owner_lease_expires_at TIMESTAMPTZ,
    ADD COLUMN response_status INTEGER,
    ADD COLUMN response_body BYTEA,
    ADD COLUMN response_content_type VARCHAR(128),
    ADD COLUMN response_headers JSONB,
    ADD COLUMN terminal_at TIMESTAMPTZ,
    ADD COLUMN retained_until TIMESTAMPTZ,
    ADD COLUMN abandoned_until TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE job_requests
    ADD CONSTRAINT ck_job_requests_state
        CHECK (state IN ('IN_FLIGHT', 'TERMINAL', 'ABANDONED')),
    ADD CONSTRAINT ck_job_requests_owner_fields
        CHECK (state <> 'IN_FLIGHT' OR (owner_token IS NOT NULL AND owner_lease_expires_at IS NOT NULL)),
    ADD CONSTRAINT ck_job_requests_abandoned_until
        CHECK (state <> 'ABANDONED' OR abandoned_until IS NOT NULL),
    ADD CONSTRAINT ck_job_requests_response_status
        CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    ADD CONSTRAINT ck_job_requests_response_body
        CHECK (response_body IS NULL OR octet_length(response_body) <= 65536),
    ADD CONSTRAINT ck_job_requests_response_content_type
        CHECK (
            response_content_type IS NULL
            OR response_content_type IN ('application/json', 'application/problem+json')
        );

CREATE TABLE job_request_waiters (
    tenant_id VARCHAR(64) NOT NULL,
    submitter_hash CHAR(64) NOT NULL,
    key_hash CHAR(64) NOT NULL,
    generation BIGINT NOT NULL CHECK (generation > 0),
    waiter_token UUID NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, submitter_hash, key_hash, generation, waiter_token),
    FOREIGN KEY (tenant_id, submitter_hash, key_hash)
        REFERENCES job_requests(tenant_id, submitter_hash, key_hash)
        ON DELETE CASCADE
);

CREATE INDEX ix_job_request_waiters_admission
    ON job_request_waiters(tenant_id, submitter_hash, key_hash, generation, expires_at);

CREATE INDEX ix_job_request_waiters_expiry
    ON job_request_waiters(expires_at);

CREATE INDEX ix_job_requests_terminal_retention
    ON job_requests(state, retained_until);

CREATE INDEX ix_job_requests_abandoned_until
    ON job_requests(state, abandoned_until);
