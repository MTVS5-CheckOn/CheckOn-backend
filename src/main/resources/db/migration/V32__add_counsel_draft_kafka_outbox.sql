-- counsel switches from a synchronous POST to Kafka-notified completion
-- (AI-A 2026-08-20 "counsel Kafka 결정 철회" — the 8/19 "no Kafka for
-- counsel" guidance was wrong; 04_api_contract.md already confirmed Kafka
-- completion notification on 7/15). job_id is now minted by the backend at
-- request time and returned in the 202 immediately; the AI's own job_id
-- (needed for the REST GET/refine calls) only becomes known once the
-- adapter's completion event arrives.
ALTER TABLE counsel_draft_jobs
    ADD COLUMN ai_job_id VARCHAR(80),
    ADD COLUMN request_hash VARCHAR(71);

CREATE UNIQUE INDEX uq_counsel_draft_job_ai_job_id
    ON counsel_draft_jobs (teacher_id, ai_job_id)
    WHERE ai_job_id IS NOT NULL;

COMMENT ON COLUMN counsel_draft_jobs.job_id IS
    'Backend-minted opaque id, returned to the frontend immediately in the 202 — not the AI job_id.';
COMMENT ON COLUMN counsel_draft_jobs.ai_job_id IS
    'The AI''s own job_id, known only once the Kafka completion/failure event arrives. NULL while the draft is still in flight — GET must not call the AI REST endpoint until this is set.';
COMMENT ON COLUMN counsel_draft_jobs.request_hash IS
    'sha256 of the outgoing CounselDraftCreateRequest, so a replayed Idempotency-Key with a different body can be rejected with IDEMPOTENCY_CONFLICT — the AI no longer sees the request synchronously to do this check itself.';

-- System-owned transactional outbox for counsel draft requests, mirroring
-- kafka_outbox_events (risk-detection) but keyed to a single job row instead
-- of a run/attempt pair — counsel has no multi-attempt run concept in v1.
CREATE TABLE counsel_draft_kafka_outbox_events (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    counsel_draft_job_id UUID NOT NULL,
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

    CONSTRAINT fk_counsel_draft_kafka_outbox_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_counsel_draft_kafka_outbox_job
        FOREIGN KEY (counsel_draft_job_id) REFERENCES counsel_draft_jobs (id) ON DELETE RESTRICT,
    CONSTRAINT ck_counsel_draft_kafka_outbox_topic
        CHECK (topic = btrim(topic) AND char_length(topic) BETWEEN 1 AND 200),
    CONSTRAINT ck_counsel_draft_kafka_outbox_message_key
        CHECK (message_key = btrim(message_key) AND char_length(message_key) BETWEEN 1 AND 120),
    CONSTRAINT ck_counsel_draft_kafka_outbox_payload CHECK (btrim(payload) <> ''),
    CONSTRAINT ck_counsel_draft_kafka_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_counsel_draft_kafka_outbox_attempts CHECK (publish_attempts >= 0),
    CONSTRAINT ck_counsel_draft_kafka_outbox_published CHECK (
        (status = 'PUBLISHED' AND published_at IS NOT NULL)
        OR (status <> 'PUBLISHED' AND published_at IS NULL)
    )
);

CREATE INDEX idx_counsel_draft_kafka_outbox_publishable
    ON counsel_draft_kafka_outbox_events (status, next_attempt_at, created_at);

CREATE INDEX idx_counsel_draft_kafka_outbox_teacher
    ON counsel_draft_kafka_outbox_events (teacher_id, created_at);

COMMENT ON TABLE counsel_draft_kafka_outbox_events IS
    'System-owned transactional outbox for counsel draft Kafka requests (checkon.counsel-draft.requested.v1).';
