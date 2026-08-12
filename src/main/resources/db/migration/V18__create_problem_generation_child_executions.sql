-- One backend parent request fans out to one child execution per area/type target.
-- Legacy AI identifiers on problem_generation_requests remain temporarily for
-- compatibility with the pre-fan-out result consumer.

ALTER TABLE problem_generation_requests
    DROP CONSTRAINT ck_problem_generation_requests_status,
    ADD CONSTRAINT ck_problem_generation_requests_status CHECK (status IN (
        'QUEUED', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'PARTIAL_SUCCESS',
        'FAILED', 'CANCELLED', 'DELIVERY_FAILED'
    ));

ALTER TABLE problem_generation_request_targets
    ADD CONSTRAINT uq_problem_generation_request_targets_identity
        UNIQUE (id, teacher_id, problem_request_id);

CREATE TABLE problem_generation_executions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    request_target_id UUID NOT NULL,
    target_index INTEGER NOT NULL,
    status VARCHAR(30) NOT NULL,
    adapter_execution_id UUID,
    ai_idempotency_key VARCHAR(200) NOT NULL,
    request_snapshot_hash VARCHAR(71) NOT NULL,
    request_snapshot JSONB NOT NULL,
    ai_execution_id VARCHAR(120),
    ai_job_id VARCHAR(120),
    ai_set_id VARCHAR(120),
    ai_result_status VARCHAR(40),
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_executions_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT fk_problem_generation_executions_target_identity
        FOREIGN KEY (request_target_id, teacher_id, problem_request_id)
        REFERENCES problem_generation_request_targets (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_executions_target
        UNIQUE (problem_request_id, target_index),
    CONSTRAINT uq_problem_generation_executions_target_row
        UNIQUE (request_target_id),
    CONSTRAINT uq_problem_generation_executions_ai_idempotency
        UNIQUE (teacher_id, ai_idempotency_key),
    CONSTRAINT uq_problem_generation_executions_adapter_execution
        UNIQUE (adapter_execution_id),
    CONSTRAINT uq_problem_generation_executions_ai_job
        UNIQUE (ai_job_id),
    CONSTRAINT ck_problem_generation_executions_target_index
        CHECK (target_index >= 0),
    CONSTRAINT ck_problem_generation_executions_status CHECK (status IN (
        'QUEUED', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED',
        'CANCELLED', 'TIMED_OUT', 'DELIVERY_FAILED', 'REJECTED_INSUFFICIENT'
    )),
    CONSTRAINT ck_problem_generation_executions_idempotency
        CHECK (ai_idempotency_key = btrim(ai_idempotency_key)
            AND char_length(ai_idempotency_key) BETWEEN 1 AND 200),
    CONSTRAINT ck_problem_generation_executions_snapshot_hash
        CHECK (request_snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_problem_generation_executions_terminal_time CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMED_OUT',
                    'DELIVERY_FAILED', 'REJECTED_INSUFFICIENT') AND completed_at IS NOT NULL)
        OR
        (status IN ('QUEUED', 'DISPATCHED', 'RUNNING') AND completed_at IS NULL)
    ),
    CONSTRAINT ck_problem_generation_executions_time CHECK (
        updated_at >= created_at
        AND (dispatched_at IS NULL OR dispatched_at >= created_at)
        AND (started_at IS NULL OR started_at >= created_at)
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX idx_problem_generation_executions_parent
    ON problem_generation_executions (teacher_id, problem_request_id, target_index);
CREATE INDEX idx_problem_generation_executions_active
    ON problem_generation_executions (teacher_id, status, updated_at)
    WHERE status IN ('QUEUED', 'DISPATCHED', 'RUNNING');

ALTER TABLE problem_generation_executions ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_executions FORCE ROW LEVEL SECURITY;

CREATE POLICY problem_generation_executions_select ON problem_generation_executions
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_executions_insert ON problem_generation_executions
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_executions_update ON problem_generation_executions
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_executions_delete ON problem_generation_executions
    FOR DELETE USING (false);

COMMENT ON TABLE problem_generation_executions IS
    'Backend-owned child execution; exactly one row per problem request area/type target.';
COMMENT ON COLUMN problem_generation_executions.target_index IS
    'Zero-based index of the immutable targets array used in adapter correlation.';
COMMENT ON COLUMN problem_generation_executions.request_snapshot IS
    'Immutable child HTTP request source; adapters derive the AI request from this snapshot.';
