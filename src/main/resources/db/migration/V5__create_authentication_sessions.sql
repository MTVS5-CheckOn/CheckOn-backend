-- 애플리케이션은 세션 상태 전이를 먼저 검증하고, 이 테이블의 제약은
-- 동시 요청이나 직접 SQL도 잘못된 상태 조합을 저장하지 못하게 최종 방어한다.
CREATE TABLE authentication_sessions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    refresh_token_hash VARCHAR(71) NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    rotation_count INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT fk_authentication_sessions_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_authentication_sessions_refresh_token_hash
        UNIQUE (refresh_token_hash),
    -- 원문 토큰이 실수로 저장되는 것을 형식 수준에서도 차단한다.
    CONSTRAINT ck_authentication_sessions_refresh_token_hash
        CHECK (refresh_token_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_authentication_sessions_status
        CHECK (status IN ('ACTIVE', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_authentication_sessions_expiration
        CHECK (expires_at > issued_at),
    CONSTRAINT ck_authentication_sessions_last_used_at
        CHECK (last_used_at IS NULL OR last_used_at >= issued_at),
    CONSTRAINT ck_authentication_sessions_rotation_count
        CHECK (rotation_count >= 0),
    CONSTRAINT ck_authentication_sessions_revocation
        CHECK (
            (status = 'REVOKED' AND revoked_at IS NOT NULL)
            OR (status IN ('ACTIVE', 'EXPIRED') AND revoked_at IS NULL)
        ),
    CONSTRAINT ck_authentication_sessions_revoked_at
        CHECK (revoked_at IS NULL OR revoked_at >= issued_at)
);

CREATE INDEX idx_authentication_sessions_account_status
    ON authentication_sessions (account_id, status, expires_at);

COMMENT ON COLUMN authentication_sessions.refresh_token_hash IS
    'SHA-256 digest used for lookup. The opaque refresh token is returned once and never stored.';
COMMENT ON COLUMN authentication_sessions.rotation_count IS
    'Monotonic count incremented whenever the current refresh token is rotated.';
