CREATE TABLE voucher_campaigns (
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
