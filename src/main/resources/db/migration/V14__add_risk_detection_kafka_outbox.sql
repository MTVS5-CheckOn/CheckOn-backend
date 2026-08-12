-- Kafka has no HTTP status code. Preserve the legacy HTTP value when it exists,
-- but allow a successfully consumed Kafka completion event to leave it NULL.
ALTER TABLE detection_request_attempts
    DROP CONSTRAINT ck_detection_request_attempts_status_columns;

ALTER TABLE detection_request_attempts
    ADD CONSTRAINT ck_detection_request_attempts_status_columns
        CHECK (
            (
                status = 'REQUESTED'
                AND completed_at IS NULL
                AND http_status IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'SUCCEEDED'
                AND completed_at IS NOT NULL
                AND (http_status IS NULL OR http_status BETWEEN 200 AND 299)
                AND error_code IS NULL
            )
            OR (
                status = 'FAILED'
                AND completed_at IS NOT NULL
                AND (http_status IS NULL OR http_status BETWEEN 400 AND 599)
                AND error_code IS NOT NULL
            )
        );

-- This is an opaque routing key. It is deliberately separate from the student
-- alias table and never contains a teacher UUID in its external value.
CREATE TABLE ai_tenant_aliases (
    teacher_id UUID PRIMARY KEY,
    alias VARCHAR(40) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ai_tenant_aliases_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_ai_tenant_aliases_alias
        CHECK (alias ~ '^tn_[0-9a-f]{32}$')
);

-- A system-owned delivery queue. It has no HTTP endpoint and carries only the
-- already-pseudonymized AI request snapshot. The worker needs cross-tenant
-- access, so tenant RLS is enforced before queue insertion, not by this table.
CREATE TABLE kafka_outbox_events (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    detection_run_id UUID NOT NULL,
    detection_attempt_id UUID NOT NULL,
    topic VARCHAR(200) NOT NULL,
    message_key VARCHAR(120) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    locked_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_kafka_outbox_events_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kafka_outbox_events_run
        FOREIGN KEY (detection_run_id) REFERENCES detection_runs (id) ON DELETE RESTRICT,
    CONSTRAINT fk_kafka_outbox_events_attempt
        FOREIGN KEY (detection_attempt_id) REFERENCES detection_request_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_kafka_outbox_events_topic CHECK (topic = btrim(topic) AND char_length(topic) BETWEEN 1 AND 200),
    CONSTRAINT ck_kafka_outbox_events_message_key CHECK (message_key = btrim(message_key) AND char_length(message_key) BETWEEN 1 AND 120),
    CONSTRAINT ck_kafka_outbox_events_payload CHECK (btrim(payload) <> ''),
    CONSTRAINT ck_kafka_outbox_events_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_kafka_outbox_events_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_kafka_outbox_events_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX idx_kafka_outbox_events_publishable
    ON kafka_outbox_events (status, next_attempt_at, created_at);

CREATE INDEX idx_kafka_outbox_events_teacher
    ON kafka_outbox_events (teacher_id, created_at);

-- At-least-once Kafka delivery is expected. This inbox uniquely records each
-- received event_id only after its domain transition is persisted.
CREATE TABLE kafka_inbox_events (
    event_id UUID PRIMARY KEY,
    topic VARCHAR(200) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT ck_kafka_inbox_events_topic
        CHECK (topic = btrim(topic) AND char_length(topic) BETWEEN 1 AND 200)
);

COMMENT ON TABLE kafka_outbox_events IS
    'System-owned transactional outbox for pseudonymized Backend-to-AI Kafka requests.';
COMMENT ON TABLE kafka_inbox_events IS
    'System-owned idempotency ledger for AI-to-Backend Kafka result events.';
