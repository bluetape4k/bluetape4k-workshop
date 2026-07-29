CREATE TABLE IF NOT EXISTS voucher_campaigns (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    state VARCHAR(24) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    capacity INTEGER NOT NULL,
    allocated_count INTEGER NOT NULL DEFAULT 0,
    per_user_limit INTEGER NOT NULL,
    redemption_ttl_seconds BIGINT NOT NULL,
    policy_version BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_voucher_campaign_public UNIQUE (tenant_id, campaign_id),
    CONSTRAINT uq_voucher_campaign_tenant_row UNIQUE (tenant_id, id),
    CONSTRAINT ck_voucher_campaign_capacity CHECK (allocated_count BETWEEN 0 AND capacity),
    CONSTRAINT ck_voucher_campaign_policy CHECK (
        capacity > 0 AND per_user_limit > 0 AND redemption_ttl_seconds > 0 AND starts_at < ends_at
    )
);

CREATE INDEX IF NOT EXISTS ix_voucher_campaign_state_end
    ON voucher_campaigns (tenant_id, state, ends_at);

CREATE TABLE IF NOT EXISTS voucher_claims (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    campaign_row_id BIGINT NOT NULL,
    campaign_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    user_digest CHAR(64) NOT NULL,
    state VARCHAR(32) NOT NULL,
    review_kind VARCHAR(24),
    pending_from_state VARCHAR(32),
    capacity_reserved BOOLEAN NOT NULL,
    allocation_policy_version BIGINT NOT NULL,
    code_verifier BYTEA,
    generation_key_version INTEGER,
    verification_key_version INTEGER,
    expires_at TIMESTAMPTZ,
    redemption_reference_digest CHAR(64),
    revision BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_voucher_claim_campaign
        FOREIGN KEY (tenant_id, campaign_row_id) REFERENCES voucher_campaigns (tenant_id, id),
    CONSTRAINT uq_voucher_claim_public UNIQUE (tenant_id, claim_id),
    CONSTRAINT uq_voucher_claim_allocation UNIQUE (tenant_id, allocation_id),
    CONSTRAINT uq_voucher_claim_verifier UNIQUE (tenant_id, code_verifier),
    CONSTRAINT uq_voucher_claim_redemption UNIQUE (tenant_id, redemption_reference_digest),
    CONSTRAINT uq_voucher_claim_tenant_row UNIQUE (tenant_id, id),
    CONSTRAINT ck_voucher_claim_verifier_length CHECK (
        code_verifier IS NULL OR octet_length(code_verifier) = 32
    ),
    CONSTRAINT ck_voucher_claim_review_context CHECK (
        (state = 'REVIEW_REQUIRED' AND review_kind IS NOT NULL AND pending_from_state IS NOT NULL)
        OR (state <> 'REVIEW_REQUIRED' AND review_kind IS NULL AND pending_from_state IS NULL)
    ),
    CONSTRAINT ck_voucher_claim_code_material CHECK (
        (state IN ('ALLOCATED', 'REDEEMED', 'RELEASED', 'EXPIRED', 'REVOKED')
            AND code_verifier IS NOT NULL
            AND generation_key_version IS NOT NULL
            AND verification_key_version IS NOT NULL)
        OR state IN ('ELIGIBLE', 'REVIEW_REQUIRED', 'REJECTED')
    )
);

CREATE INDEX IF NOT EXISTS ix_voucher_claim_user_state
    ON voucher_claims (tenant_id, campaign_id, user_digest, state);

CREATE INDEX IF NOT EXISTS ix_voucher_claim_expiry
    ON voucher_claims (state, expires_at, id)
    WHERE state IN ('ALLOCATED', 'REVIEW_REQUIRED');

CREATE TABLE IF NOT EXISTS voucher_reviews (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    claim_row_id BIGINT NOT NULL,
    claim_id UUID NOT NULL,
    kind VARCHAR(24) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'OPEN',
    reason_code VARCHAR(64) NOT NULL,
    signal_summary VARCHAR(256) NOT NULL,
    reviewer_actor_digest CHAR(64),
    expected_claim_revision BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT fk_voucher_review_claim
        FOREIGN KEY (tenant_id, claim_row_id) REFERENCES voucher_claims (tenant_id, id),
    CONSTRAINT uq_voucher_review_revision UNIQUE (tenant_id, claim_id, kind, revision)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_review_open
    ON voucher_reviews (tenant_id, claim_id, kind)
    WHERE status = 'OPEN';

CREATE INDEX IF NOT EXISTS ix_voucher_review_queue
    ON voucher_reviews (tenant_id, status, created_at, id);

CREATE TABLE IF NOT EXISTS voucher_audits (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    correlation_digest CHAR(64),
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_voucher_audit_revision UNIQUE (tenant_id, aggregate_type, aggregate_id, revision)
);

CREATE INDEX IF NOT EXISTS ix_voucher_audit_cursor
    ON voucher_audits (tenant_id, campaign_id, revision, id);

CREATE TABLE IF NOT EXISTS campaign_event_inbox (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    observed_sequence BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    attempt INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claim_owner VARCHAR(128),
    claim_until TIMESTAMPTZ,
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_voucher_inbox_event UNIQUE (tenant_id, event_id),
    CONSTRAINT ck_voucher_inbox_attempt CHECK (attempt >= 0)
);

CREATE INDEX IF NOT EXISTS ix_voucher_inbox_work
    ON campaign_event_inbox (status, next_attempt_at, id);

CREATE TABLE IF NOT EXISTS voucher_http_idempotency (
    id BIGSERIAL PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    principal_digest CHAR(43) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    resource_id VARCHAR(128) NOT NULL,
    key_digest CHAR(43) NOT NULL,
    request_fingerprint CHAR(43) NOT NULL,
    status VARCHAR(24) NOT NULL,
    owner_token_digest CHAR(43),
    lease_until TIMESTAMPTZ,
    command_deadline TIMESTAMPTZ NOT NULL,
    response_kind VARCHAR(48),
    response_status INTEGER,
    response_headers VARCHAR(512),
    aggregate_id UUID,
    allocation_id UUID,
    aggregate_revision BIGINT,
    generation_key_version INTEGER,
    verification_key_version INTEGER,
    expires_at TIMESTAMPTZ NOT NULL,
    created_by VARCHAR(128) NOT NULL DEFAULT 'system',
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_by VARCHAR(128),
    updated_at TIMESTAMPTZ,
    CONSTRAINT uq_voucher_idempotency_scope UNIQUE (
        tenant_id, principal_digest, operation, resource_id, key_digest
    )
);

CREATE INDEX IF NOT EXISTS ix_voucher_idempotency_cleanup
    ON voucher_http_idempotency (status, expires_at, id);
