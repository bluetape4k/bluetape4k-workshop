CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE voucher_pool_campaigns (
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    state VARCHAR(24) NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, campaign_id),
    CHECK (state IN ('DRAFT','ACTIVE','PAUSED','REVOKING','REVOKED'))
);

CREATE TABLE voucher_pool_batches (
    tenant_id VARCHAR(64) NOT NULL,
    batch_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    activates_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    import_cursor BIGINT NOT NULL DEFAULT 0 CHECK (import_cursor >= 0),
    PRIMARY KEY (tenant_id, batch_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES voucher_pool_campaigns(tenant_id, campaign_id),
    CHECK (state IN ('STAGING','ACTIVE','PAUSED','REVOKING','EXPIRING','REVOKED','EXPIRED','FAILED_RETRYABLE','FAILED_TERMINAL')),
    CHECK (expires_at IS NULL OR activates_at < expires_at)
);
CREATE INDEX ix_voucher_pool_batch_campaign_state ON voucher_pool_batches(tenant_id,campaign_id,state,activates_at,batch_id);

CREATE TABLE voucher_pool_entries (
    tenant_id VARCHAR(64) NOT NULL,
    entry_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    source_ordinal BIGINT NOT NULL CHECK (source_ordinal >= 0),
    state VARCHAR(24) NOT NULL,
    reservation_id UUID,
    allocation_id UUID,
    user_digest BYTEA,
    reservation_expires_at TIMESTAMPTZ,
    allocation_expires_at TIMESTAMPTZ,
    code_ciphertext BYTEA,
    wrapped_dek BYTEA,
    nonce BYTEA,
    key_version INTEGER NOT NULL DEFAULT 1 CHECK (key_version > 0),
    verification_key_version INTEGER,
    revealed_at TIMESTAMPTZ,
    quarantined_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, entry_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES voucher_pool_campaigns(tenant_id, campaign_id),
    FOREIGN KEY (tenant_id, batch_id) REFERENCES voucher_pool_batches(tenant_id, batch_id),
    UNIQUE (tenant_id, batch_id, source_ordinal),
    UNIQUE (tenant_id, nonce),
    CHECK (state IN ('AVAILABLE','RESERVED','ALLOCATED','REDEEMED','RELEASED','REVOKED','EXPIRED')),
    CONSTRAINT voucher_pool_entry_cipher_contract CHECK (
      (revealed_at IS NULL AND code_ciphertext IS NOT NULL AND wrapped_dek IS NOT NULL AND nonce IS NOT NULL)
      OR (revealed_at IS NOT NULL AND code_ciphertext IS NULL AND wrapped_dek IS NULL)
    ),
    CHECK (state <> 'AVAILABLE' OR (reservation_id IS NULL AND allocation_id IS NULL AND user_digest IS NULL AND reservation_expires_at IS NULL AND allocation_expires_at IS NULL)),
    CHECK (state <> 'RESERVED' OR (reservation_id IS NOT NULL AND user_digest IS NOT NULL AND reservation_expires_at IS NOT NULL)),
    CHECK (state NOT IN ('ALLOCATED','REDEEMED') OR (allocation_id IS NOT NULL AND user_digest IS NOT NULL))
);
CREATE INDEX ix_voucher_pool_available ON voucher_pool_entries(tenant_id,campaign_id,batch_id,source_ordinal,entry_id) WHERE state='AVAILABLE' AND quarantined_at IS NULL;
CREATE INDEX ix_voucher_pool_reservation_expiry ON voucher_pool_entries(state,reservation_expires_at,entry_id) WHERE state='RESERVED';
CREATE INDEX ix_voucher_pool_allocation_expiry ON voucher_pool_entries(state,allocation_expires_at,entry_id) WHERE state='ALLOCATED';
CREATE INDEX ix_voucher_pool_revocation ON voucher_pool_entries(tenant_id,batch_id,state,entry_id) WHERE state IN ('AVAILABLE','RESERVED','ALLOCATED');

CREATE TABLE voucher_pool_reservations (
    tenant_id VARCHAR(64) NOT NULL,
    reservation_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    user_digest BYTEA NOT NULL,
    idempotency_owner_digest BYTEA NOT NULL,
    state VARCHAR(24) NOT NULL,
    reservation_expires_at TIMESTAMPTZ NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, reservation_id),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES voucher_pool_campaigns(tenant_id,campaign_id),
    FOREIGN KEY (tenant_id,batch_id) REFERENCES voucher_pool_batches(tenant_id,batch_id),
    FOREIGN KEY (tenant_id,entry_id) REFERENCES voucher_pool_entries(tenant_id,entry_id),
    CHECK (state IN ('ACTIVE','ALLOCATED','EXPIRED','RELEASED','REVOKED'))
);
CREATE UNIQUE INDEX uq_voucher_pool_reservation_entry ON voucher_pool_reservations(tenant_id,entry_id);
CREATE INDEX ix_voucher_pool_reservation_cursor ON voucher_pool_reservations(tenant_id,state,reservation_expires_at,reservation_id);

CREATE TABLE voucher_pool_user_limits (
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    user_digest BYTEA NOT NULL,
    active_reservations INTEGER NOT NULL DEFAULT 0 CHECK (active_reservations >= 0),
    active_allocations INTEGER NOT NULL DEFAULT 0 CHECK (active_allocations >= 0),
    lifetime_consumed INTEGER NOT NULL DEFAULT 0 CHECK (lifetime_consumed >= 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id,campaign_id,user_digest),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES voucher_pool_campaigns(tenant_id,campaign_id)
);

CREATE TABLE voucher_pool_allocations (
    tenant_id VARCHAR(64) NOT NULL,
    allocation_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    entry_id UUID NOT NULL,
    user_digest BYTEA NOT NULL,
    entitlement_root_id UUID NOT NULL,
    replacement_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (replacement_ordinal BETWEEN 0 AND 1),
    allocation_expires_at TIMESTAMPTZ NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id,allocation_id),
    FOREIGN KEY (tenant_id,reservation_id) REFERENCES voucher_pool_reservations(tenant_id,reservation_id),
    FOREIGN KEY (tenant_id,entry_id) REFERENCES voucher_pool_entries(tenant_id,entry_id),
    UNIQUE (tenant_id,campaign_id,user_digest,entitlement_root_id,replacement_ordinal)
);
CREATE UNIQUE INDEX uq_voucher_pool_allocation_entry ON voucher_pool_allocations(tenant_id,entry_id);
CREATE INDEX ix_voucher_pool_allocation_cursor ON voucher_pool_allocations(tenant_id,allocation_expires_at,allocation_id);

CREATE TABLE voucher_pool_code_dedup (
    tenant_id VARCHAR(64) NOT NULL,
    stable_dedup_digest BYTEA NOT NULL,
    first_campaign_id UUID NOT NULL,
    first_batch_id UUID NOT NULL,
    first_entry_id UUID NOT NULL,
    key_version INTEGER NOT NULL CHECK (key_version > 0),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id,stable_dedup_digest)
);
CREATE UNIQUE INDEX uq_voucher_pool_dedup ON voucher_pool_code_dedup(tenant_id,stable_dedup_digest);

CREATE TABLE voucher_pool_http_idempotency (
    tenant_id VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL, scoped_key_digest BYTEA NOT NULL,
    fingerprint BYTEA NOT NULL, status VARCHAR(24) NOT NULL, owner_token_digest BYTEA,
    lease_until TIMESTAMPTZ, command_deadline TIMESTAMPTZ NOT NULL, descriptor JSONB,
    expires_at TIMESTAMPTZ NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id,operation,scoped_key_digest)
);
CREATE INDEX ix_voucher_pool_idempotency_lease ON voucher_pool_http_idempotency(status,lease_until,tenant_id,operation);

CREATE TABLE voucher_pool_command_tombstones (
    tenant_id VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL, key_version INTEGER NOT NULL CHECK(key_version>0),
    scoped_key_digest BYTEA NOT NULL, fingerprint BYTEA NOT NULL, effect_id UUID, terminal_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id,operation,scoped_key_digest), CHECK ((effect_id IS NULL) <> (terminal_code IS NULL))
);

CREATE TABLE voucher_pool_audits (
    id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, campaign_id UUID NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL, aggregate_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>=0),
    policy_version BIGINT NOT NULL CHECK(policy_version>0), actor_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL, correlation_digest BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,aggregate_type,aggregate_id,revision)
);
CREATE INDEX ix_voucher_pool_audit_cursor ON voucher_pool_audits(tenant_id,campaign_id,id);

CREATE TABLE voucher_pool_reconciliation_inbox (
    tenant_id VARCHAR(64) NOT NULL, event_id UUID NOT NULL, payload_digest BYTEA NOT NULL,
    status VARCHAR(24) NOT NULL, attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL, claim_owner VARCHAR(128), claim_until TIMESTAMPTZ,
    terminal_outcome VARCHAR(64), revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    PRIMARY KEY (tenant_id,event_id), CHECK ((claim_owner IS NULL)=(claim_until IS NULL))
);
CREATE INDEX ix_voucher_pool_reconciliation_cursor ON voucher_pool_reconciliation_inbox(status,next_attempt_at,tenant_id,event_id);

CREATE TABLE voucher_pool_quarantines (
    tenant_id VARCHAR(64) NOT NULL, entry_id UUID NOT NULL, source_state VARCHAR(24) NOT NULL,
    source_revision BIGINT NOT NULL CHECK(source_revision>=0), reason_code VARCHAR(64) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(), resolved_at TIMESTAMPTZ,
    resolution VARCHAR(64), PRIMARY KEY (tenant_id,entry_id),
    FOREIGN KEY (tenant_id,entry_id) REFERENCES voucher_pool_entries(tenant_id,entry_id),
    CHECK ((resolved_at IS NULL AND resolution IS NULL) OR (resolved_at IS NOT NULL AND resolution IS NOT NULL))
);

CREATE TABLE voucher_pool_worker_claims (
    tenant_id VARCHAR(64) NOT NULL, worker_type VARCHAR(32) NOT NULL, scope_id UUID NOT NULL,
    owner_id VARCHAR(128) NOT NULL, claim_until TIMESTAMPTZ NOT NULL, cursor BIGINT NOT NULL DEFAULT 0 CHECK(cursor>=0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), PRIMARY KEY(tenant_id,worker_type,scope_id)
);
CREATE INDEX ix_voucher_pool_worker_active_claim ON voucher_pool_worker_claims(worker_type,claim_until,tenant_id,scope_id);

CREATE TABLE voucher_pool_pool_depth (
    tenant_id VARCHAR(64) NOT NULL, batch_id UUID NOT NULL, state VARCHAR(24) NOT NULL,
    entry_count BIGINT NOT NULL DEFAULT 0 CHECK(entry_count>=0), revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    PRIMARY KEY(tenant_id,batch_id,state), FOREIGN KEY(tenant_id,batch_id) REFERENCES voucher_pool_batches(tenant_id,batch_id)
);
CREATE INDEX ix_voucher_pool_depth_campaign ON voucher_pool_pool_depth(tenant_id,batch_id,state) INCLUDE(entry_count);
