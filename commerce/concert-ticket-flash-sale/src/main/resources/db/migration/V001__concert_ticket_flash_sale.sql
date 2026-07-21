CREATE TABLE ticket_sales (
    sale_id UUID PRIMARY KEY,
    state VARCHAR(24) NOT NULL CHECK (state IN ('draft', 'scheduled', 'open', 'suspended', 'closed')),
    current_policy_version BIGINT NOT NULL,
    opens_at TIMESTAMPTZ NOT NULL,
    closes_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (current_policy_version > 0 AND opens_at < closes_at)
);

CREATE TABLE ticket_sale_policy_versions (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    policy_version BIGINT NOT NULL,
    per_user_limit INTEGER NOT NULL,
    max_quantity INTEGER NOT NULL,
    hold_seconds BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sale_id, policy_version),
    CHECK (policy_version > 0 AND per_user_limit > 0 AND max_quantity > 0 AND hold_seconds > 0)
);

CREATE TABLE ticket_inventory (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    grade VARCHAR(32) NOT NULL,
    total_quantity INTEGER NOT NULL,
    held_quantity INTEGER NOT NULL DEFAULT 0,
    sold_quantity INTEGER NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (sale_id, grade),
    CHECK (
        total_quantity >= 0 AND held_quantity >= 0 AND sold_quantity >= 0
        AND held_quantity + sold_quantity <= total_quantity
    )
);

CREATE TABLE ticket_identity_subjects (
    subject_id UUID PRIMARY KEY,
    identity_kind VARCHAR(8) NOT NULL CHECK (identity_kind IN ('USER', 'IP')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    anonymized_at TIMESTAMPTZ
);

CREATE TABLE ticket_identity_aliases (
    id BIGSERIAL PRIMARY KEY,
    identity_kind VARCHAR(8) NOT NULL CHECK (identity_kind IN ('USER', 'IP')),
    key_version INTEGER NOT NULL,
    digest BYTEA NOT NULL,
    subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (identity_kind, key_version, digest),
    CHECK (key_version > 0 AND octet_length(digest) = 32)
);

CREATE TABLE ticket_waiting_room_entries (
    id BIGSERIAL PRIMARY KEY,
    entry_id UUID NOT NULL UNIQUE,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    user_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    state VARCHAR(24) NOT NULL DEFAULT 'waiting' CHECK (state IN ('waiting', 'granted', 'expired', 'cancelled')),
    sequence BIGINT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sale_id, user_subject_id),
    UNIQUE (sale_id, sequence)
);

CREATE INDEX ticket_waiting_claim_idx
    ON ticket_waiting_room_entries(sale_id, state, sequence, id);

CREATE TABLE ticket_admission_grants (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    grant_nonce UUID NOT NULL,
    buyer_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    policy_version BIGINT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_attempt_id UUID,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sale_id, grant_nonce),
    CHECK ((consumed_attempt_id IS NULL) = (consumed_at IS NULL))
);

CREATE INDEX ticket_admission_expiry_idx
    ON ticket_admission_grants(sale_id, expires_at, id) WHERE consumed_at IS NULL;

CREATE TABLE ticket_buyer_sale_states (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    user_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    policy_version BIGINT NOT NULL,
    purchased_quantity INTEGER NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    UNIQUE (sale_id, user_subject_id),
    CHECK (purchased_quantity >= 0)
);

CREATE TABLE ticket_purchase_attempts (
    attempt_id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    user_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    ip_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    grade VARCHAR(32) NOT NULL,
    quantity INTEGER NOT NULL,
    policy_version BIGINT NOT NULL,
    state VARCHAR(40) NOT NULL CHECK (state IN (
        'inventory_held', 'payment_authorizing', 'reconciliation_required', 'cancellation_requested',
        'approved', 'declined', 'cancelled', 'expired', 'refund_pending', 'refunded', 'refund_quarantined'
    )),
    hold_deadline TIMESTAMPTZ NOT NULL,
    authorization_operation_id UUID NOT NULL UNIQUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sale_id, grade) REFERENCES ticket_inventory(sale_id, grade),
    FOREIGN KEY (sale_id, policy_version) REFERENCES ticket_sale_policy_versions(sale_id, policy_version),
    CHECK (quantity > 0)
);

CREATE INDEX ticket_purchase_due_idx
    ON ticket_purchase_attempts(state, hold_deadline, attempt_id);

ALTER TABLE ticket_admission_grants
    ADD CONSTRAINT ticket_admission_consumed_attempt_fk
    FOREIGN KEY (consumed_attempt_id) REFERENCES ticket_purchase_attempts(attempt_id);

CREATE TABLE ticket_active_identity_guards (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    identity_kind VARCHAR(8) NOT NULL CHECK (identity_kind IN ('USER', 'IP')),
    identity_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    active_attempt_id UUID NOT NULL REFERENCES ticket_purchase_attempts(attempt_id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (sale_id, identity_kind, identity_subject_id)
);

CREATE INDEX ticket_active_guard_attempt_idx
    ON ticket_active_identity_guards(active_attempt_id);

CREATE TABLE ticket_orders (
    order_id UUID PRIMARY KEY,
    attempt_id UUID NOT NULL UNIQUE REFERENCES ticket_purchase_attempts(attempt_id),
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    grade VARCHAR(32) NOT NULL,
    quantity INTEGER NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('paid', 'refund_pending', 'refunded', 'refund_quarantined')),
    ticket_disposition VARCHAR(24) NOT NULL CHECK (ticket_disposition IN ('pending', 'never_issued', 'issued', 'revoked')),
    authorization_operation_id UUID NOT NULL UNIQUE,
    refund_operation_id UUID UNIQUE,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (sale_id, grade) REFERENCES ticket_inventory(sale_id, grade),
    CHECK (quantity > 0)
);

CREATE TABLE ticket_payment_operations (
    id BIGSERIAL PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    operation_id UUID NOT NULL,
    attempt_id UUID NOT NULL REFERENCES ticket_purchase_attempts(attempt_id),
    order_id UUID REFERENCES ticket_orders(order_id),
    operation_kind VARCHAR(16) NOT NULL CHECK (operation_kind IN ('authorize', 'refund')),
    status VARCHAR(32) NOT NULL CHECK (status IN ('pending', 'claimed', 'unknown', 'approved', 'declined', 'succeeded', 'failed')),
    next_reconcile_at TIMESTAMPTZ,
    claim_token UUID,
    claim_revision BIGINT NOT NULL DEFAULT 0,
    claim_until TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider, operation_id)
);

CREATE INDEX ticket_reconcile_due_idx
    ON ticket_payment_operations(status, next_reconcile_at, id);

CREATE TABLE ticket_tickets (
    id BIGSERIAL PRIMARY KEY,
    order_id UUID NOT NULL UNIQUE REFERENCES ticket_orders(order_id),
    external_ticket_digest BYTEA,
    state VARCHAR(24) NOT NULL CHECK (state IN ('issue_pending', 'issue_retry', 'issued', 'revoke_pending', 'revoked', 'quarantined')),
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (external_ticket_digest IS NULL OR octet_length(external_ticket_digest) = 32)
);

CREATE TABLE ticket_effect_operations (
    id BIGSERIAL PRIMARY KEY,
    effect_kind VARCHAR(16) NOT NULL CHECK (effect_kind IN ('issue', 'refund', 'revoke')),
    operation_id UUID NOT NULL,
    order_id UUID NOT NULL REFERENCES ticket_orders(order_id),
    status VARCHAR(24) NOT NULL CHECK (status IN ('pending', 'claimed', 'succeeded', 'retry', 'quarantined')),
    claim_token UUID,
    claim_revision BIGINT NOT NULL DEFAULT 0,
    claim_until TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (effect_kind, operation_id)
);

CREATE INDEX ticket_effect_due_idx
    ON ticket_effect_operations(status, claim_until, id);

CREATE TABLE ticket_effect_receipts (
    id BIGSERIAL PRIMARY KEY,
    consumer_name VARCHAR(80) NOT NULL,
    operation_id UUID NOT NULL,
    payload_digest BYTEA NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (consumer_name, operation_id),
    CHECK (octet_length(payload_digest) = 32)
);

CREATE TABLE ticket_http_idempotency (
    id BIGSERIAL PRIMARY KEY,
    principal_subject_id UUID NOT NULL REFERENCES ticket_identity_subjects(subject_id),
    http_method VARCHAR(8) NOT NULL,
    canonical_route VARCHAR(128) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    operation VARCHAR(64) NOT NULL,
    idempotency_key_digest BYTEA NOT NULL,
    request_fingerprint BYTEA NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('in_progress', 'completed', 'failed')),
    attempt_id UUID REFERENCES ticket_purchase_attempts(attempt_id),
    response_status INTEGER,
    response_body BYTEA,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (
        principal_subject_id, http_method, canonical_route, resource_id, operation, idempotency_key_digest
    ),
    CHECK (octet_length(idempotency_key_digest) = 32 AND octet_length(request_fingerprint) = 32)
);

CREATE INDEX ticket_idempotency_cleanup_idx
    ON ticket_http_idempotency(status, expires_at, id);

CREATE TABLE ticket_audits (
    id BIGSERIAL PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES ticket_sales(sale_id),
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    actor_subject_digest BYTEA,
    reason_code VARCHAR(64) NOT NULL,
    policy_version BIGINT NOT NULL,
    correlation_digest BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (aggregate_type, aggregate_id, revision),
    CHECK (actor_subject_digest IS NULL OR octet_length(actor_subject_digest) = 32),
    CHECK (correlation_digest IS NULL OR octet_length(correlation_digest) = 32)
);

CREATE INDEX ticket_audit_cursor_idx
    ON ticket_audits(sale_id, id);
