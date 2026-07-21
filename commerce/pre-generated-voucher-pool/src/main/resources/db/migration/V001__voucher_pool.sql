CREATE TABLE voucher_pool_campaigns (
    tenant_id VARCHAR(64) NOT NULL,
    campaign_id UUID NOT NULL,
    state VARCHAR(24) NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    ends_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp() + interval '365 days',
    per_user_limit INTEGER NOT NULL DEFAULT 1 CHECK (per_user_limit > 0),
    reservation_ttl_seconds BIGINT NOT NULL DEFAULT 300 CHECK (reservation_ttl_seconds > 0),
    allocation_ttl_seconds BIGINT NOT NULL DEFAULT 3600 CHECK (allocation_ttl_seconds > 0),
    replacement_allowance INTEGER NOT NULL DEFAULT 1 CHECK (replacement_allowance BETWEEN 0 AND 1),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT pk_voucher_pool_campaigns PRIMARY KEY (tenant_id, campaign_id),
    CHECK (state IN ('DRAFT','ACTIVE','PAUSED','REVOKING','REVOKED')),
    CHECK (starts_at < ends_at),
    CHECK (created_at <= updated_at)
);

CREATE TABLE voucher_pool_batches (
    tenant_id VARCHAR(64) NOT NULL,
    batch_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    state VARCHAR(32) NOT NULL,
    source_kind VARCHAR(16) NOT NULL,
    provenance_digest BYTEA NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    activates_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    next_source_ordinal BIGINT NOT NULL DEFAULT 0 CHECK (next_source_ordinal >= 0),
    expected_count BIGINT NOT NULL CHECK (expected_count >= 0),
    accepted_count BIGINT NOT NULL DEFAULT 0 CHECK (accepted_count >= 0),
    rejected_count BIGINT NOT NULL DEFAULT 0 CHECK (rejected_count >= 0),
    checkpoint_digest BYTEA,
    last_failure_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT pk_voucher_pool_batches PRIMARY KEY (tenant_id, batch_id),
    CONSTRAINT uq_voucher_pool_batch_identity UNIQUE (tenant_id, batch_id, campaign_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES voucher_pool_campaigns(tenant_id, campaign_id),
    CHECK (source_kind IN ('IMPORTED','GENERATED')),
    CHECK (state IN ('STAGING','ACTIVE','PAUSED','REVOKING','EXPIRING','REVOKED','EXPIRED','FAILED_RETRYABLE','FAILED_TERMINAL')),
    CHECK ((state IN ('FAILED_RETRYABLE','FAILED_TERMINAL'))=(last_failure_code IS NOT NULL)),
    CHECK (accepted_count + rejected_count <= next_source_ordinal),
    CHECK (next_source_ordinal <= expected_count),
    CHECK (expires_at IS NULL OR activates_at < expires_at),
    CHECK (created_at <= updated_at)
);
CREATE INDEX ix_voucher_pool_batch_campaign_state ON voucher_pool_batches(tenant_id,campaign_id,state,activates_at,batch_id);

CREATE TABLE voucher_pool_entries (
    tenant_id VARCHAR(64) NOT NULL,
    entry_id UUID NOT NULL,
    campaign_id UUID NOT NULL,
    batch_id UUID NOT NULL,
    source_ordinal BIGINT NOT NULL CHECK (source_ordinal >= 0),
    state VARCHAR(24) NOT NULL,
    stable_dedup_digest BYTEA NOT NULL,
    verification_digest BYTEA,
    verification_key_version INTEGER CHECK (verification_key_version > 0),
    code_ciphertext BYTEA,
    code_nonce BYTEA,
    wrapped_dek BYTEA,
    wrap_nonce BYTEA,
    kek_version VARCHAR(64),
    reservation_id UUID,
    allocation_id UUID,
    user_digest BYTEA,
    reserved_at TIMESTAMPTZ,
    reservation_expires_at TIMESTAMPTZ,
    allocated_at TIMESTAMPTZ,
    allocation_expires_at TIMESTAMPTZ,
    revealed_at TIMESTAMPTZ,
    redeemed_at TIMESTAMPTZ,
    allocation_policy_version BIGINT CHECK (allocation_policy_version IS NULL OR allocation_policy_version > 0),
    terminal_reason VARCHAR(64),
    entitlement_root_id UUID,
    replacement_count INTEGER NOT NULL DEFAULT 0 CHECK (replacement_count BETWEEN 0 AND 1),
    quarantined_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT pk_voucher_pool_entries PRIMARY KEY (tenant_id, entry_id),
    CONSTRAINT uq_voucher_pool_entry_identity UNIQUE (tenant_id, entry_id, campaign_id, batch_id),
    FOREIGN KEY (tenant_id, campaign_id) REFERENCES voucher_pool_campaigns(tenant_id, campaign_id),
    FOREIGN KEY (tenant_id, batch_id, campaign_id) REFERENCES voucher_pool_batches(tenant_id, batch_id, campaign_id),
    CONSTRAINT uq_voucher_pool_entry_batch_ordinal UNIQUE (tenant_id, batch_id, source_ordinal),
    CONSTRAINT uq_voucher_pool_entry_stable_dedup UNIQUE (tenant_id, stable_dedup_digest),
    CHECK (state IN ('AVAILABLE','RESERVED','ALLOCATED','REDEEMED','RELEASED','REVOKED','EXPIRED')),
    CONSTRAINT voucher_pool_entry_verification_pair CHECK (
      (verification_digest IS NULL) = (verification_key_version IS NULL)
    ),
    CONSTRAINT voucher_pool_entry_allocation_context CHECK (
      allocation_id IS NULL OR (verification_digest IS NOT NULL AND reservation_id IS NOT NULL
        AND user_digest IS NOT NULL AND reserved_at IS NOT NULL AND reservation_expires_at IS NOT NULL
        AND allocated_at IS NOT NULL AND allocation_expires_at IS NOT NULL
        AND allocation_policy_version IS NOT NULL AND entitlement_root_id IS NOT NULL)
    ),
    CONSTRAINT voucher_pool_entry_cipher_contract CHECK (
      (revealed_at IS NULL AND code_ciphertext IS NOT NULL AND code_nonce IS NOT NULL
        AND wrapped_dek IS NOT NULL AND wrap_nonce IS NOT NULL AND kek_version IS NOT NULL)
      OR (revealed_at IS NOT NULL AND code_ciphertext IS NULL AND code_nonce IS NULL
        AND wrapped_dek IS NULL AND wrap_nonce IS NULL AND kek_version IS NULL)
    ),
    CHECK (state <> 'AVAILABLE' OR (reservation_id IS NULL AND allocation_id IS NULL AND user_digest IS NULL
      AND reserved_at IS NULL AND reservation_expires_at IS NULL AND allocated_at IS NULL
      AND allocation_expires_at IS NULL AND revealed_at IS NULL AND redeemed_at IS NULL
      AND allocation_policy_version IS NULL AND entitlement_root_id IS NULL AND replacement_count=0
      AND terminal_reason IS NULL)),
    CHECK (state <> 'RESERVED' OR (reservation_id IS NOT NULL AND allocation_id IS NULL AND user_digest IS NOT NULL
      AND reserved_at IS NOT NULL AND reservation_expires_at IS NOT NULL AND allocated_at IS NULL
      AND allocation_expires_at IS NULL AND revealed_at IS NULL AND redeemed_at IS NULL
      AND allocation_policy_version IS NULL AND entitlement_root_id IS NULL AND replacement_count=0
      AND terminal_reason IS NULL)),
    CHECK (state NOT IN ('ALLOCATED','REDEEMED','RELEASED') OR (reservation_id IS NOT NULL
      AND allocation_id IS NOT NULL AND user_digest IS NOT NULL AND reserved_at IS NOT NULL
      AND reservation_expires_at IS NOT NULL AND allocated_at IS NOT NULL AND allocation_expires_at IS NOT NULL
      AND allocation_policy_version IS NOT NULL AND entitlement_root_id IS NOT NULL)),
    CHECK (state <> 'REDEEMED' OR (revealed_at IS NOT NULL AND redeemed_at IS NOT NULL)),
    CHECK (state NOT IN ('REDEEMED','RELEASED','REVOKED','EXPIRED') OR terminal_reason IS NOT NULL),
    CHECK (reserved_at IS NULL OR reservation_expires_at > reserved_at),
    CHECK (allocated_at IS NULL OR allocation_expires_at > allocated_at),
    CHECK (redeemed_at IS NULL OR (allocated_at IS NOT NULL AND redeemed_at >= allocated_at)),
    CHECK (created_at <= updated_at)
);
CREATE UNIQUE INDEX uq_voucher_pool_entry_code_nonce ON voucher_pool_entries(tenant_id,code_nonce) WHERE code_nonce IS NOT NULL;
CREATE UNIQUE INDEX uq_voucher_pool_entry_wrap_nonce ON voucher_pool_entries(tenant_id,wrap_nonce) WHERE wrap_nonce IS NOT NULL;
CREATE INDEX ix_voucher_pool_available ON voucher_pool_entries(tenant_id,campaign_id,batch_id,source_ordinal,entry_id) WHERE state='AVAILABLE' AND quarantined_at IS NULL;
CREATE INDEX ix_voucher_pool_worker_available ON voucher_pool_entries(tenant_id,batch_id,source_ordinal,entry_id) WHERE state='AVAILABLE' AND quarantined_at IS NULL;
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
    entitlement_root_id UUID,
    replacement_ordinal INTEGER NOT NULL DEFAULT 0 CHECK (replacement_ordinal BETWEEN 0 AND 1),
    policy_version BIGINT NOT NULL CHECK (policy_version > 0),
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id, reservation_id),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES voucher_pool_campaigns(tenant_id,campaign_id),
    FOREIGN KEY (tenant_id,batch_id,campaign_id) REFERENCES voucher_pool_batches(tenant_id,batch_id,campaign_id),
    FOREIGN KEY (tenant_id,entry_id,campaign_id,batch_id)
      REFERENCES voucher_pool_entries(tenant_id,entry_id,campaign_id,batch_id),
    UNIQUE (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest),
    CHECK ((replacement_ordinal=0 AND entitlement_root_id IS NULL)
        OR (replacement_ordinal=1 AND entitlement_root_id IS NOT NULL)),
    CHECK (state IN ('ACTIVE','ALLOCATED','EXPIRED','RELEASED','REVOKED'))
);
CREATE UNIQUE INDEX uq_voucher_pool_reservation_active_entry
    ON voucher_pool_reservations(tenant_id,entry_id) WHERE state='ACTIVE';
CREATE UNIQUE INDEX uq_voucher_pool_replacement_reservation
    ON voucher_pool_reservations(tenant_id,campaign_id,user_digest,entitlement_root_id,replacement_ordinal)
    WHERE entitlement_root_id IS NOT NULL;
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
    UNIQUE (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest),
    FOREIGN KEY (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest)
      REFERENCES voucher_pool_reservations(tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES voucher_pool_campaigns(tenant_id,campaign_id),
    FOREIGN KEY (tenant_id,batch_id,campaign_id) REFERENCES voucher_pool_batches(tenant_id,batch_id,campaign_id),
    FOREIGN KEY (tenant_id,entry_id,campaign_id,batch_id)
      REFERENCES voucher_pool_entries(tenant_id,entry_id,campaign_id,batch_id),
    UNIQUE (tenant_id,campaign_id,user_digest,entitlement_root_id,replacement_ordinal)
);
CREATE UNIQUE INDEX uq_voucher_pool_allocation_entry ON voucher_pool_allocations(tenant_id,entry_id);
CREATE INDEX ix_voucher_pool_allocation_cursor ON voucher_pool_allocations(tenant_id,allocation_expires_at,allocation_id);

CREATE FUNCTION voucher_pool_require_original_entitlement() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.replacement_ordinal = 0 AND EXISTS (
            SELECT 1 FROM voucher_pool_allocations replacement
            WHERE replacement.tenant_id = OLD.tenant_id
              AND replacement.campaign_id = OLD.campaign_id
              AND replacement.user_digest = OLD.user_digest
              AND replacement.entitlement_root_id = OLD.entitlement_root_id
              AND replacement.replacement_ordinal = 1
        ) THEN
            RAISE EXCEPTION 'original allocation with a replacement cannot be deleted'
                USING ERRCODE = '23503';
        END IF;
        RETURN OLD;
    END IF;

    IF TG_OP = 'UPDATE'
       AND OLD.replacement_ordinal = 0
       AND (NEW.tenant_id, NEW.campaign_id, NEW.user_digest, NEW.entitlement_root_id, NEW.replacement_ordinal)
           IS DISTINCT FROM
           (OLD.tenant_id, OLD.campaign_id, OLD.user_digest, OLD.entitlement_root_id, OLD.replacement_ordinal)
       AND EXISTS (
           SELECT 1 FROM voucher_pool_allocations replacement
           WHERE replacement.tenant_id = OLD.tenant_id
             AND replacement.campaign_id = OLD.campaign_id
             AND replacement.user_digest = OLD.user_digest
             AND replacement.entitlement_root_id = OLD.entitlement_root_id
             AND replacement.replacement_ordinal = 1
       ) THEN
        RAISE EXCEPTION 'original allocation with a replacement cannot change entitlement lineage'
            USING ERRCODE = '23503';
    END IF;

    IF NEW.replacement_ordinal = 1 THEN
        PERFORM 1 FROM voucher_pool_allocations original
        WHERE original.tenant_id = NEW.tenant_id
          AND original.campaign_id = NEW.campaign_id
          AND original.user_digest = NEW.user_digest
          AND original.entitlement_root_id = NEW.entitlement_root_id
          AND original.replacement_ordinal = 0
        FOR KEY SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'replacement allocation requires its original entitlement'
                USING ERRCODE = '23503';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER voucher_pool_allocation_entitlement_chain
BEFORE INSERT OR UPDATE OF tenant_id,campaign_id,entitlement_root_id,replacement_ordinal,user_digest
ON voucher_pool_allocations
FOR EACH ROW EXECUTE FUNCTION voucher_pool_require_original_entitlement();
CREATE TRIGGER voucher_pool_allocation_entitlement_delete
BEFORE DELETE ON voucher_pool_allocations
FOR EACH ROW EXECUTE FUNCTION voucher_pool_require_original_entitlement();

ALTER TABLE voucher_pool_entries
    ADD FOREIGN KEY (tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest)
    REFERENCES voucher_pool_reservations(tenant_id,reservation_id,campaign_id,batch_id,entry_id,user_digest)
    DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE voucher_pool_entries
    ADD FOREIGN KEY (tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest)
    REFERENCES voucher_pool_allocations(tenant_id,allocation_id,reservation_id,campaign_id,batch_id,entry_id,user_digest)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE voucher_pool_code_dedup (
    tenant_id VARCHAR(64) NOT NULL,
    stable_dedup_digest BYTEA NOT NULL,
    first_campaign_id UUID NOT NULL,
    first_batch_id UUID NOT NULL,
    first_entry_id UUID NOT NULL,
    key_version INTEGER NOT NULL CHECK (key_version > 0),
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    CONSTRAINT pk_voucher_pool_code_dedup PRIMARY KEY (tenant_id,stable_dedup_digest)
);
CREATE UNIQUE INDEX uq_voucher_pool_dedup ON voucher_pool_code_dedup(tenant_id,stable_dedup_digest);

CREATE FUNCTION voucher_pool_reject_code_dedup_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'voucher_pool_code_dedup is tenant-lifetime immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER voucher_pool_code_dedup_immutable
BEFORE UPDATE OR DELETE ON voucher_pool_code_dedup
FOR EACH ROW EXECUTE FUNCTION voucher_pool_reject_code_dedup_mutation();

CREATE TABLE voucher_pool_http_idempotency (
    tenant_id VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL, scoped_key_digest BYTEA NOT NULL,
    fingerprint BYTEA NOT NULL, status VARCHAR(24) NOT NULL, owner_token_digest BYTEA,
    lease_until TIMESTAMPTZ, command_deadline TIMESTAMPTZ NOT NULL, descriptor JSONB,
    expires_at TIMESTAMPTZ NOT NULL, revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    PRIMARY KEY (tenant_id,operation,scoped_key_digest),
    CHECK (status IN ('OWNED','COMPLETED','RETRYABLE_FAILED')),
    CHECK ((owner_token_digest IS NULL)=(lease_until IS NULL)),
    CHECK ((status='OWNED')=(owner_token_digest IS NOT NULL))
);
CREATE INDEX ix_voucher_pool_idempotency_lease ON voucher_pool_http_idempotency(status,lease_until,tenant_id,operation);
CREATE INDEX ix_voucher_pool_idempotency_cleanup ON voucher_pool_http_idempotency(status,expires_at,tenant_id,operation);

CREATE TABLE voucher_pool_command_tombstones (
    tenant_id VARCHAR(64) NOT NULL, operation VARCHAR(64) NOT NULL, key_version INTEGER NOT NULL CHECK(key_version>0),
    scoped_key_digest BYTEA NOT NULL, fingerprint BYTEA NOT NULL, effect_id UUID, terminal_code VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (tenant_id,operation,scoped_key_digest), CHECK ((effect_id IS NULL) <> (terminal_code IS NULL))
);

CREATE FUNCTION voucher_pool_reject_command_tombstone_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'voucher_pool_command_tombstones is tenant-lifetime immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER voucher_pool_command_tombstones_tenant_lifetime_immutable
BEFORE UPDATE OR DELETE ON voucher_pool_command_tombstones
FOR EACH ROW EXECUTE FUNCTION voucher_pool_reject_command_tombstone_mutation();

CREATE TABLE voucher_pool_audits (
    id BIGSERIAL PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL, campaign_id UUID NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL, aggregate_id UUID NOT NULL, revision BIGINT NOT NULL CHECK(revision>=0),
    policy_version BIGINT NOT NULL CHECK(policy_version>0), actor_type VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL, correlation_digest BYTEA, request_digest BYTEA,
    before_count BIGINT, after_count BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (tenant_id,aggregate_type,aggregate_id,revision),
    FOREIGN KEY (tenant_id,campaign_id) REFERENCES voucher_pool_campaigns(tenant_id,campaign_id),
    CHECK (actor_type IN ('CUSTOMER','OPERATOR','WORKER','SYSTEM')),
    CHECK ((before_count IS NULL)=(after_count IS NULL)),
    CHECK (before_count IS NULL OR (before_count>=0 AND after_count>=0))
);
CREATE INDEX ix_voucher_pool_audit_cursor ON voucher_pool_audits(tenant_id,campaign_id,id);

CREATE FUNCTION voucher_pool_reject_audit_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'voucher_pool_audits is append-only' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER voucher_pool_audits_append_only
BEFORE UPDATE OR DELETE ON voucher_pool_audits
FOR EACH ROW EXECUTE FUNCTION voucher_pool_reject_audit_mutation();

CREATE TABLE voucher_pool_reconciliation_inbox (
    tenant_id VARCHAR(64) NOT NULL, event_id UUID NOT NULL, payload_digest BYTEA NOT NULL,
    status VARCHAR(24) NOT NULL, attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL, claim_owner VARCHAR(128), claim_until TIMESTAMPTZ,
    terminal_outcome VARCHAR(64), revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    PRIMARY KEY (tenant_id,event_id), CHECK ((claim_owner IS NULL)=(claim_until IS NULL)),
    CHECK (status IN ('PENDING','CLAIMED','APPLIED','IGNORED','FAILED_RETRYABLE','FAILED_TERMINAL')),
    CHECK ((status='CLAIMED')=(claim_owner IS NOT NULL))
);
CREATE INDEX ix_voucher_pool_reconciliation_cursor ON voucher_pool_reconciliation_inbox(status,next_attempt_at,tenant_id,event_id);
CREATE INDEX ix_voucher_pool_reconciliation_claim ON voucher_pool_reconciliation_inbox(claim_until,tenant_id,event_id) WHERE claim_owner IS NOT NULL;

CREATE TABLE voucher_pool_quarantines (
    tenant_id VARCHAR(64) NOT NULL, entry_id UUID NOT NULL, source_state VARCHAR(24) NOT NULL,
    source_revision BIGINT NOT NULL CHECK(source_revision>=0), reason_code VARCHAR(64) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(), resolved_at TIMESTAMPTZ,
    resolution VARCHAR(64), PRIMARY KEY (tenant_id,entry_id),
    FOREIGN KEY (tenant_id,entry_id) REFERENCES voucher_pool_entries(tenant_id,entry_id),
    CHECK (source_state IN ('AVAILABLE','RESERVED','ALLOCATED','REDEEMED','RELEASED','REVOKED','EXPIRED')),
    CHECK (reason_code IN ('UNKNOWN_KEY_VERSION','INVALID_CIPHERTEXT','INVALID_TAG','DIGEST_MISMATCH')),
    CHECK (resolution IS NULL OR resolution IN ('CLEARED','REVOKED','TERMINAL_PRESERVED')),
    CHECK ((resolved_at IS NULL AND resolution IS NULL) OR (resolved_at IS NOT NULL AND resolution IS NOT NULL))
);
CREATE INDEX ix_voucher_pool_quarantine_active ON voucher_pool_quarantines(detected_at,tenant_id,entry_id) WHERE resolved_at IS NULL;

CREATE TABLE voucher_pool_worker_claims (
    tenant_id VARCHAR(64) NOT NULL, worker_type VARCHAR(32) NOT NULL, scope_id UUID NOT NULL,
    owner_id VARCHAR(128), claim_until TIMESTAMPTZ, cursor BIGINT NOT NULL DEFAULT 0 CHECK(cursor>=0),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK(attempt>=0),
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT transaction_timestamp(),
    checkpoint BIGINT NOT NULL DEFAULT 0 CHECK(checkpoint>=0), poison_reason VARCHAR(64),
    revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0), PRIMARY KEY(tenant_id,worker_type,scope_id),
    CHECK (worker_type IN ('RESERVATION_EXPIRY','ALLOCATION_EXPIRY','BATCH_REVOKE','BATCH_EXPIRY','RECONCILIATION','PURGE')),
    CHECK ((owner_id IS NULL)=(claim_until IS NULL)),
    CHECK (poison_reason IS NULL OR attempt > 0)
);
CREATE INDEX ix_voucher_pool_worker_active_claim ON voucher_pool_worker_claims(worker_type,claim_until,tenant_id,scope_id);
CREATE INDEX ix_voucher_pool_worker_cursor ON voucher_pool_worker_claims(worker_type,next_attempt_at,checkpoint,tenant_id,scope_id);

CREATE TABLE voucher_pool_pool_depth (
    tenant_id VARCHAR(64) NOT NULL, batch_id UUID NOT NULL, state VARCHAR(24) NOT NULL,
    entry_count BIGINT NOT NULL DEFAULT 0 CHECK(entry_count>=0), revision BIGINT NOT NULL DEFAULT 0 CHECK(revision>=0),
    PRIMARY KEY(tenant_id,batch_id,state), FOREIGN KEY(tenant_id,batch_id) REFERENCES voucher_pool_batches(tenant_id,batch_id),
    CHECK (state IN ('AVAILABLE','RESERVED','ALLOCATED','REDEEMED','RELEASED','REVOKED','EXPIRED'))
);
CREATE INDEX ix_voucher_pool_depth_campaign ON voucher_pool_pool_depth(tenant_id,batch_id,state) INCLUDE(entry_count);

CREATE FUNCTION voucher_pool_touch_updated_at() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at = GREATEST(OLD.updated_at, NEW.created_at, transaction_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER voucher_pool_campaign_touch_updated_at
BEFORE UPDATE ON voucher_pool_campaigns
FOR EACH ROW EXECUTE FUNCTION voucher_pool_touch_updated_at();
CREATE TRIGGER voucher_pool_batch_touch_updated_at
BEFORE UPDATE ON voucher_pool_batches
FOR EACH ROW EXECUTE FUNCTION voucher_pool_touch_updated_at();
CREATE TRIGGER voucher_pool_entry_touch_updated_at
BEFORE UPDATE ON voucher_pool_entries
FOR EACH ROW EXECUTE FUNCTION voucher_pool_touch_updated_at();
