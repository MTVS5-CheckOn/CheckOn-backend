-- Problem generation AI contract (2026-08-23): terminal reference events,
-- slot detail events, five-area source requests, revisions, and misconception feedback.

ALTER TABLE problem_generation_request_targets
    ADD COLUMN skill_node_id VARCHAR(120),
    ADD COLUMN source_payload JSONB,
    ADD CONSTRAINT ck_problem_generation_target_skill_node CHECK (
        skill_node_id IS NULL OR (
            skill_node_id = btrim(skill_node_id)
            AND skill_node_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,119}$'
        )
    );

ALTER TABLE problem_generation_executions
    ADD COLUMN worker_phase VARCHAR(20) NOT NULL DEFAULT 'queued',
    ADD COLUMN domain_status VARCHAR(30),
    ADD COLUMN unstarted_count INTEGER,
    ADD COLUMN expected_slot_count INTEGER,
    ADD COLUMN received_slot_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN terminal_received_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_problem_generation_worker_phase CHECK (
        worker_phase IN ('queued', 'leased', 'running', 'paused', 'succeeded', 'failed', 'cancelled')
    ),
    ADD CONSTRAINT ck_problem_generation_domain_status CHECK (
        domain_status IS NULL OR domain_status IN (
            'generated', 'partial_success', 'failed', 'rejected_insufficient'
        )
    ),
    ADD CONSTRAINT ck_problem_generation_unstarted_count CHECK (
        unstarted_count IS NULL OR (
            unstarted_count >= 0
            AND requested_count IS NOT NULL
            AND unstarted_count <= requested_count
        )
    ),
    ADD CONSTRAINT ck_problem_generation_slot_counts CHECK (
        received_slot_count >= 0
        AND (expected_slot_count IS NULL OR expected_slot_count BETWEEN 0 AND 20)
        AND (expected_slot_count IS NULL OR received_slot_count <= expected_slot_count)
    ),
    ADD CONSTRAINT ck_problem_generation_terminal_reference CHECK (
        (worker_phase IN ('succeeded', 'failed', 'cancelled') AND terminal_received_at IS NOT NULL)
        OR (worker_phase NOT IN ('succeeded', 'failed', 'cancelled') AND terminal_received_at IS NULL)
    );

UPDATE problem_generation_executions
SET status = 'RUNNING',
    worker_phase = 'running',
    completed_at = NULL,
    error_code = NULL
WHERE status = 'TIMED_OUT';

UPDATE problem_generation_executions
SET worker_phase = CASE status
        WHEN 'QUEUED' THEN 'queued'
        WHEN 'DISPATCHED' THEN 'queued'
        WHEN 'RUNNING' THEN 'running'
        WHEN 'SUCCEEDED' THEN 'succeeded'
        WHEN 'FAILED' THEN 'failed'
        WHEN 'CANCELLED' THEN 'cancelled'
        WHEN 'DELIVERY_FAILED' THEN 'failed'
        WHEN 'REJECTED_INSUFFICIENT' THEN 'failed'
        ELSE worker_phase
    END,
    domain_status = CASE status
        WHEN 'SUCCEEDED' THEN CASE LOWER(COALESCE(ai_result_status, 'generated'))
            WHEN 'generated' THEN 'generated'
            WHEN 'partial_success' THEN 'partial_success'
            WHEN 'failed' THEN 'failed'
            WHEN 'rejected_insufficient' THEN 'rejected_insufficient'
            ELSE 'generated'
        END
        WHEN 'FAILED' THEN 'failed'
        WHEN 'DELIVERY_FAILED' THEN 'failed'
        WHEN 'REJECTED_INSUFFICIENT' THEN 'rejected_insufficient'
        ELSE domain_status
    END,
    expected_slot_count = requested_count,
    terminal_received_at = CASE
        WHEN status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'DELIVERY_FAILED', 'REJECTED_INSUFFICIENT')
            THEN COALESCE(completed_at, updated_at)
        ELSE NULL
    END;

ALTER TABLE problem_generation_executions
    DROP CONSTRAINT ck_problem_generation_executions_status,
    ADD CONSTRAINT ck_problem_generation_executions_status CHECK (status IN (
        'QUEUED', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED',
        'CANCELLED', 'DELIVERY_FAILED', 'REJECTED_INSUFFICIENT'
    )),
    DROP CONSTRAINT ck_problem_generation_executions_terminal_time,
    ADD CONSTRAINT ck_problem_generation_executions_terminal_time CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'DELIVERY_FAILED', 'REJECTED_INSUFFICIENT')
            AND completed_at IS NOT NULL)
        OR (status IN ('QUEUED', 'DISPATCHED', 'RUNNING') AND completed_at IS NULL)
    );

ALTER TABLE problem_generation_items
    ADD COLUMN skill_node_id VARCHAR(120),
    ADD COLUMN area_tag VARCHAR(40),
    ADD COLUMN type_tag VARCHAR(20),
    ADD COLUMN correct_no INTEGER,
    ADD CONSTRAINT ck_problem_generation_item_skill_node CHECK (
        skill_node_id IS NULL OR (
            skill_node_id = btrim(skill_node_id)
            AND skill_node_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,119}$'
        )
    ),
    ADD CONSTRAINT ck_problem_generation_item_correct_no CHECK (
        correct_no IS NULL OR correct_no BETWEEN 1 AND 5
    ),
    ADD CONSTRAINT ck_problem_generation_item_area CHECK (
        area_tag IS NULL OR area_tag IN ('language','reading','literature','speech_writing','media')
    ),
    ADD CONSTRAINT ck_problem_generation_item_type CHECK (
        type_tag IS NULL OR type_tag IN ('fact','infer','critic','concept')
    );

ALTER TABLE problem_generation_item_options
    ADD COLUMN why_wrong TEXT,
    ADD COLUMN misconception_tag VARCHAR(120),
    ADD CONSTRAINT ck_problem_generation_option_misconception CHECK (
        misconception_tag IS NULL OR (
            misconception_tag = btrim(misconception_tag)
            AND char_length(misconception_tag) BETWEEN 1 AND 120
        )
    );

DROP POLICY problem_generation_item_options_update ON problem_generation_item_options;
DROP POLICY problem_generation_item_options_delete ON problem_generation_item_options;
CREATE POLICY problem_generation_item_options_update ON problem_generation_item_options
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_item_options_delete ON problem_generation_item_options
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

ALTER TABLE problem_generation_slots
    ADD COLUMN ai_status VARCHAR(40),
    ADD COLUMN available_actions JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN revision_payload JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT ck_problem_generation_slot_ai_status CHECK (
        ai_status IS NULL OR ai_status IN (
            'verified', 'needs_review', 'verification_unavailable', 'dropped'
        )
    ),
    ADD CONSTRAINT uq_problem_generation_slot_event_identity
        UNIQUE (problem_execution_id, slot_index, teacher_id, problem_request_id);

DROP INDEX uq_problem_generation_outbox_child_event;
CREATE UNIQUE INDEX uq_problem_generation_outbox_child_request
    ON problem_generation_outbox (problem_execution_id, event_type)
    WHERE problem_execution_id IS NOT NULL
      AND event_type = 'problem_generation.requested';

CREATE TABLE problem_generation_revision_requests (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    problem_execution_id UUID NOT NULL,
    slot_index INTEGER NOT NULL,
    base_revision_no INTEGER NOT NULL,
    instruction TEXT NOT NULL,
    client_idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(71) NOT NULL,
    status VARCHAR(20) NOT NULL,
    ai_execution_id VARCHAR(120),
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_problem_revision_slot
        FOREIGN KEY (problem_execution_id, slot_index, teacher_id, problem_request_id)
        REFERENCES problem_generation_slots (
            problem_execution_id, slot_index, teacher_id, problem_request_id
        ) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_revision_client_key
        UNIQUE (teacher_id, client_idempotency_key),
    CONSTRAINT uq_problem_revision_base
        UNIQUE (problem_execution_id, slot_index, base_revision_no),
    CONSTRAINT ck_problem_revision_slot CHECK (slot_index >= 0),
    CONSTRAINT ck_problem_revision_base CHECK (base_revision_no >= 0),
    CONSTRAINT ck_problem_revision_instruction CHECK (btrim(instruction) <> ''),
    CONSTRAINT ck_problem_revision_client_key CHECK (
        client_idempotency_key = btrim(client_idempotency_key)
        AND char_length(client_idempotency_key) BETWEEN 8 AND 200
    ),
    CONSTRAINT ck_problem_revision_hash CHECK (
        request_hash ~ '^sha256:[0-9a-f]{64}$'
    ),
    CONSTRAINT ck_problem_revision_status CHECK (
        status IN ('PENDING', 'DISPATCHED', 'SUCCEEDED', 'FAILED', 'CONFLICT')
    ),
    CONSTRAINT ck_problem_revision_completion CHECK (
        (status IN ('SUCCEEDED', 'FAILED', 'CONFLICT') AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'DISPATCHED') AND completed_at IS NULL)
    ),
    CONSTRAINT ck_problem_revision_time CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE INDEX idx_problem_revision_slot
    ON problem_generation_revision_requests (
        teacher_id, problem_execution_id, slot_index, created_at DESC
    );

ALTER TABLE problem_generation_outbox
    ADD COLUMN revision_request_id UUID,
    ADD CONSTRAINT fk_problem_generation_outbox_revision
        FOREIGN KEY (revision_request_id)
        REFERENCES problem_generation_revision_requests (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_problem_generation_outbox_revision
    ON problem_generation_outbox (revision_request_id)
    WHERE revision_request_id IS NOT NULL;

ALTER TABLE problem_generation_revision_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_revision_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY problem_generation_revision_requests_select
    ON problem_generation_revision_requests FOR SELECT
    USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_revision_requests_insert
    ON problem_generation_revision_requests FOR INSERT
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_revision_requests_update
    ON problem_generation_revision_requests FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_revision_requests_delete
    ON problem_generation_revision_requests FOR DELETE USING (false);

ALTER TABLE problem_assignments
    ADD CONSTRAINT uq_problem_assignment_response_identity
        UNIQUE (id, teacher_id, student_id, problem_set_id);

CREATE TABLE problem_assignment_responses (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    student_id UUID NOT NULL,
    problem_set_id UUID NOT NULL,
    item_id UUID NOT NULL,
    chosen_no INTEGER NOT NULL,
    correct_no INTEGER NOT NULL,
    correct BOOLEAN NOT NULL,
    area_tag VARCHAR(40) NOT NULL,
    type_tag VARCHAR(20) NOT NULL,
    skill_node_id VARCHAR(120) NOT NULL,
    misconception_tag VARCHAR(120),
    responded_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_response_assignment
        FOREIGN KEY (assignment_id, teacher_id, student_id, problem_set_id)
        REFERENCES problem_assignments (id, teacher_id, student_id, problem_set_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_problem_response_saved_item
        FOREIGN KEY (problem_set_id, item_id)
        REFERENCES saved_problem_set_items (problem_set_id, item_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_problem_response_item UNIQUE (assignment_id, item_id),
    CONSTRAINT ck_problem_response_choices CHECK (
        chosen_no BETWEEN 1 AND 5 AND correct_no BETWEEN 1 AND 5
    ),
    CONSTRAINT ck_problem_response_correct CHECK (correct = (chosen_no = correct_no)),
    CONSTRAINT ck_problem_response_area CHECK (
        area_tag IN ('language','reading','literature','speech_writing','media')
    ),
    CONSTRAINT ck_problem_response_type CHECK (type_tag IN ('fact','infer','critic','concept')),
    CONSTRAINT ck_problem_response_skill_node CHECK (
        skill_node_id = btrim(skill_node_id)
        AND skill_node_id ~ '^[a-zA-Z0-9][a-zA-Z0-9._:-]{0,119}$'
    ),
    CONSTRAINT ck_problem_response_misconception CHECK (
        (correct AND misconception_tag IS NULL)
        OR (NOT correct AND misconception_tag IS NOT NULL
            AND misconception_tag = btrim(misconception_tag)
            AND char_length(misconception_tag) BETWEEN 1 AND 120)
    )
);

CREATE INDEX idx_problem_response_diagnosis
    ON problem_assignment_responses (teacher_id, student_id, responded_at, id);

ALTER TABLE problem_assignment_responses ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_assignment_responses FORCE ROW LEVEL SECURITY;
CREATE POLICY problem_assignment_responses_select
    ON problem_assignment_responses FOR SELECT
    USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_assignment_responses_insert
    ON problem_assignment_responses FOR INSERT
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_assignment_responses_update
    ON problem_assignment_responses FOR UPDATE USING (false) WITH CHECK (false);
CREATE POLICY problem_assignment_responses_delete
    ON problem_assignment_responses FOR DELETE USING (false);

COMMENT ON COLUMN problem_generation_executions.worker_phase IS
    'AI worker phase; independent from the generated set domain status.';
COMMENT ON COLUMN problem_generation_executions.domain_status IS
    'AI problem set domain status; a succeeded worker may still be partial_success.';
COMMENT ON TABLE problem_generation_revision_requests IS
    'Backend-owned idempotent ai_refine request ledger and Kafka request source.';
COMMENT ON TABLE problem_assignment_responses IS
    'Backend-graded 1-based response facts used by the next diagnosis snapshot.';
