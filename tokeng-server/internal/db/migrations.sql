-- Schema & Extensions
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE SCHEMA IF NOT EXISTS tokeng;

-- Users table
CREATE TABLE IF NOT EXISTS tokeng.users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email CITEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- Token instances (devices / credentials pool)
CREATE TABLE IF NOT EXISTS tokeng.token_instances (
    instance_id TEXT NOT NULL,
    user_id UUID NOT NULL REFERENCES tokeng.users(id) ON DELETE CASCADE,
    email CITEXT NOT NULL,
    master_token TEXT NOT NULL,
    aas_token TEXT,
    sid TEXT,
    lsid TEXT,
    android_id TEXT,
    gsf_id TEXT,
    security_token TEXT,
    device_name VARCHAR(128),
    device_model VARCHAR(128),
    device_brand VARCHAR(128),
    device_fingerprint VARCHAR(256),
    device_sdk INTEGER,
    account_status TEXT NOT NULL DEFAULT 'ACTIVE',
    last_validated_at TIMESTAMPTZ,
    last_validation_result VARCHAR(256),
    signed_out_reason VARCHAR(256),
    deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, instance_id)
);

-- High-concurrency indices
CREATE INDEX IF NOT EXISTS idx_instances_user_updated ON tokeng.token_instances (user_id, updated_at);
CREATE INDEX IF NOT EXISTS idx_instances_email ON tokeng.token_instances (email);
CREATE INDEX IF NOT EXISTS idx_instances_status ON tokeng.token_instances (account_status);
