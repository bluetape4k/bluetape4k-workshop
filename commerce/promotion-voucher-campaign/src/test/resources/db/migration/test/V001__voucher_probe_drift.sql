CREATE TABLE voucher_migration_probe (
    id BIGINT PRIMARY KEY,
    drift_marker TEXT NOT NULL DEFAULT 'drift'
);
