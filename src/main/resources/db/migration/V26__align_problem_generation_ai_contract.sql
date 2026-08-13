CREATE TABLE problem_diagnosis_snapshots (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    student_ref VARCHAR(35) NOT NULL,
    status VARCHAR(30) NOT NULL,
    status_reason TEXT,
    snapshot_hash VARCHAR(71),
    taxonomy_version VARCHAR(80),
    graph_version VARCHAR(80),
    config_version VARCHAR(80),
    request_payload JSONB NOT NULL,
    response_payload JSONB,
    diagnosed_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_diagnosis_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_problem_diagnosis_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_diagnosis_identity
        UNIQUE (id, teacher_id, student_id),
    CONSTRAINT ck_problem_diagnosis_student_ref
        CHECK (student_ref ~ '^st_[0-9a-f]{32}$'),
    CONSTRAINT ck_problem_diagnosis_status
        CHECK (status IN ('GENERATED', 'REJECTED_INSUFFICIENT', 'UNAVAILABLE')),
    CONSTRAINT ck_problem_diagnosis_snapshot_hash
        CHECK (snapshot_hash IS NULL OR snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_problem_diagnosis_generated_contract CHECK (
        (status = 'GENERATED'
            AND snapshot_hash IS NOT NULL
            AND taxonomy_version IS NOT NULL
            AND graph_version IS NOT NULL
            AND config_version IS NOT NULL
            AND response_payload IS NOT NULL)
        OR status <> 'GENERATED'
    )
);

CREATE INDEX idx_problem_diagnosis_student
    ON problem_diagnosis_snapshots (teacher_id, student_id, diagnosed_at DESC, id DESC);

ALTER TABLE problem_diagnosis_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_diagnosis_snapshots FORCE ROW LEVEL SECURITY;

CREATE POLICY problem_diagnosis_snapshots_select ON problem_diagnosis_snapshots
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_diagnosis_snapshots_insert ON problem_diagnosis_snapshots
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_diagnosis_snapshots_update ON problem_diagnosis_snapshots
    FOR UPDATE USING (false);
CREATE POLICY problem_diagnosis_snapshots_delete ON problem_diagnosis_snapshots
    FOR DELETE USING (false);

ALTER TABLE problem_generation_requests
    ADD COLUMN diagnosis_id UUID,
    ADD CONSTRAINT fk_problem_generation_request_diagnosis
        FOREIGN KEY (diagnosis_id, teacher_id, student_id)
        REFERENCES problem_diagnosis_snapshots (id, teacher_id, student_id)
        ON DELETE RESTRICT;

ALTER TABLE problem_generation_executions
    ADD CONSTRAINT uq_problem_generation_execution_identity
        UNIQUE (id, teacher_id, problem_request_id),
    ADD COLUMN requested_count INTEGER,
    ADD COLUMN processed_count INTEGER,
    ADD COLUMN status_counts JSONB,
    ADD CONSTRAINT ck_problem_generation_execution_counts CHECK (
        (requested_count IS NULL OR requested_count BETWEEN 1 AND 20)
        AND (processed_count IS NULL OR processed_count BETWEEN 0 AND requested_count)
    );

CREATE TABLE problem_generation_slots (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    problem_execution_id UUID NOT NULL,
    slot_index INTEGER NOT NULL,
    item_id UUID,
    external_item_id VARCHAR(120),
    status VARCHAR(30) NOT NULL,
    current_revision_no INTEGER NOT NULL,
    review_reason TEXT,
    failure_reason VARCHAR(120),
    failure_detail JSONB,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_slot_execution
        FOREIGN KEY (problem_execution_id, teacher_id, problem_request_id)
        REFERENCES problem_generation_executions (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_problem_generation_slot_item
        FOREIGN KEY (item_id, teacher_id, problem_request_id)
        REFERENCES problem_generation_items (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_slot_index
        UNIQUE (problem_execution_id, slot_index),
    CONSTRAINT ck_problem_generation_slot_index CHECK (slot_index >= 0),
    CONSTRAINT ck_problem_generation_slot_revision CHECK (current_revision_no >= 0),
    CONSTRAINT ck_problem_generation_slot_status CHECK (
        status IN ('PASSED', 'REVIEW_REQUIRED', 'UNVERIFIABLE', 'EXCLUDED')
    ),
    CONSTRAINT ck_problem_generation_slot_external_id CHECK (
        external_item_id IS NULL OR (
            external_item_id = btrim(external_item_id)
            AND char_length(external_item_id) BETWEEN 1 AND 120
        )
    ),
    CONSTRAINT ck_problem_generation_slot_item_state CHECK (
        (status = 'EXCLUDED' AND item_id IS NULL)
        OR (status <> 'EXCLUDED' AND item_id IS NOT NULL)
    ),
    CONSTRAINT ck_problem_generation_slot_time CHECK (updated_at >= created_at)
);

CREATE INDEX idx_problem_generation_slots_request
    ON problem_generation_slots (teacher_id, problem_request_id, slot_index);

ALTER TABLE problem_generation_slots ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_slots FORCE ROW LEVEL SECURITY;

CREATE POLICY problem_generation_slots_select ON problem_generation_slots
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_slots_insert ON problem_generation_slots
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_slots_update ON problem_generation_slots
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_slots_delete ON problem_generation_slots
    FOR DELETE USING (false);

COMMENT ON TABLE problem_diagnosis_snapshots IS
    'Immutable AI diagnosis provenance selected by a later Problem Studio request.';
COMMENT ON TABLE problem_generation_slots IS
    'AI set slot projection; dropped slots remain rows with a null item reference.';
