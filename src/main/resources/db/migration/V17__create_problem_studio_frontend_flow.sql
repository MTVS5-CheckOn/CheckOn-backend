-- Frontend problem studio Step 1..4: per-area targets, projected review items,
-- teacher selections, saved sets, and student publication records.

ALTER TABLE problem_generation_requests
    ADD COLUMN projection_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN projection_error_code VARCHAR(80),
    ADD CONSTRAINT ck_problem_generation_requests_projection_status
        CHECK (projection_status IN ('PENDING', 'PROJECTED', 'PARTIAL', 'UNSUPPORTED')),
    ADD CONSTRAINT ck_problem_generation_requests_projection_error
        CHECK (
            (projection_status IN ('PENDING', 'PROJECTED') AND projection_error_code IS NULL)
            OR projection_status IN ('PARTIAL', 'UNSUPPORTED')
        );

CREATE TABLE problem_generation_request_targets (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    area_tag VARCHAR(80) NOT NULL,
    type_tag VARCHAR(20) NOT NULL,
    requested_count INTEGER NOT NULL,
    CONSTRAINT fk_problem_generation_request_targets_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_request_targets_ordinal
        UNIQUE (problem_request_id, ordinal),
    CONSTRAINT uq_problem_generation_request_targets_pair
        UNIQUE (problem_request_id, area_tag, type_tag),
    CONSTRAINT ck_problem_generation_request_targets_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_problem_generation_request_targets_area CHECK (
        area_tag = btrim(area_tag) AND char_length(area_tag) BETWEEN 1 AND 80
    ),
    CONSTRAINT ck_problem_generation_request_targets_type CHECK (
        type_tag IN ('FACT', 'INFER', 'CRITIC', 'CONCEPT')
    ),
    CONSTRAINT ck_problem_generation_request_targets_count
        CHECK (requested_count BETWEEN 1 AND 20)
);

CREATE INDEX idx_problem_generation_request_targets_request
    ON problem_generation_request_targets (teacher_id, problem_request_id, ordinal);

CREATE TABLE problem_generation_items (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    external_item_id VARCHAR(120),
    ordinal INTEGER NOT NULL,
    stem TEXT NOT NULL,
    passage TEXT,
    correct_answer_text TEXT,
    explanation TEXT,
    source_basis TEXT,
    validation_status VARCHAR(30) NOT NULL,
    validation_message TEXT,
    selected BOOLEAN NOT NULL DEFAULT FALSE,
    raw_payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_items_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_items_id_teacher_request
        UNIQUE (id, teacher_id, problem_request_id),
    CONSTRAINT uq_problem_generation_items_ordinal
        UNIQUE (problem_request_id, ordinal),
    CONSTRAINT ck_problem_generation_items_external_id CHECK (
        external_item_id IS NULL OR (
            external_item_id = btrim(external_item_id)
            AND char_length(external_item_id) BETWEEN 1 AND 120
        )
    ),
    CONSTRAINT ck_problem_generation_items_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_problem_generation_items_stem CHECK (btrim(stem) <> ''),
    CONSTRAINT ck_problem_generation_items_validation CHECK (
        validation_status IN ('PASSED', 'REVIEW_REQUIRED', 'UNVERIFIABLE', 'EXCLUDED')
    ),
    CONSTRAINT ck_problem_generation_items_time CHECK (updated_at >= created_at)
);

CREATE UNIQUE INDEX uq_problem_generation_items_external
    ON problem_generation_items (problem_request_id, external_item_id)
    WHERE external_item_id IS NOT NULL;
CREATE INDEX idx_problem_generation_items_request
    ON problem_generation_items (teacher_id, problem_request_id, ordinal);

CREATE TABLE problem_generation_item_options (
    item_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    position INTEGER NOT NULL,
    content TEXT NOT NULL,
    PRIMARY KEY (item_id, position),
    CONSTRAINT fk_problem_generation_item_options_item_teacher_request
        FOREIGN KEY (item_id, teacher_id, problem_request_id)
        REFERENCES problem_generation_items (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_problem_generation_item_options_position CHECK (position >= 1),
    CONSTRAINT ck_problem_generation_item_options_content CHECK (btrim(content) <> '')
);

CREATE TABLE saved_problem_sets (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_saved_problem_sets_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_saved_problem_sets_request UNIQUE (problem_request_id),
    CONSTRAINT uq_saved_problem_sets_id_teacher_request
        UNIQUE (id, teacher_id, problem_request_id),
    CONSTRAINT ck_saved_problem_sets_status CHECK (status IN ('SAVED', 'PUBLISHED')),
    CONSTRAINT ck_saved_problem_sets_publication CHECK (
        (status = 'SAVED' AND published_at IS NULL)
        OR (status = 'PUBLISHED' AND published_at IS NOT NULL)
    ),
    CONSTRAINT ck_saved_problem_sets_time CHECK (
        updated_at >= saved_at AND (published_at IS NULL OR published_at >= saved_at)
    )
);

CREATE TABLE saved_problem_set_items (
    problem_set_id UUID NOT NULL,
    item_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    item_snapshot JSONB NOT NULL,
    PRIMARY KEY (problem_set_id, item_id),
    CONSTRAINT fk_saved_problem_set_items_set_teacher_request
        FOREIGN KEY (problem_set_id, teacher_id, problem_request_id)
        REFERENCES saved_problem_sets (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_saved_problem_set_items_item_teacher_request
        FOREIGN KEY (item_id, teacher_id, problem_request_id)
        REFERENCES problem_generation_items (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_saved_problem_set_items_ordinal UNIQUE (problem_set_id, ordinal),
    CONSTRAINT ck_saved_problem_set_items_ordinal CHECK (ordinal >= 1)
);

CREATE TABLE problem_assignments (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    problem_set_id UUID NOT NULL,
    student_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_assignments_set_teacher_request
        FOREIGN KEY (problem_set_id, teacher_id, problem_request_id)
        REFERENCES saved_problem_sets (id, teacher_id, problem_request_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_problem_assignments_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_assignments_request UNIQUE (problem_request_id),
    CONSTRAINT ck_problem_assignments_status CHECK (status = 'PUBLISHED')
);

CREATE FUNCTION validate_problem_assignment_target()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM problem_generation_requests request
        WHERE request.id = NEW.problem_request_id
          AND request.teacher_id = NEW.teacher_id
          AND request.target_kind = 'STUDENT'
          AND request.student_id = NEW.student_id
    ) THEN
        RAISE EXCEPTION 'problem assignment must target the request student'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_problem_assignments_target
    BEFORE INSERT OR UPDATE OF teacher_id, problem_request_id, student_id
    ON problem_assignments
    FOR EACH ROW EXECUTE FUNCTION validate_problem_assignment_target();

ALTER TABLE problem_generation_request_targets ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_request_targets FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_items FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_item_options ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_item_options FORCE ROW LEVEL SECURITY;
ALTER TABLE saved_problem_sets ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_problem_sets FORCE ROW LEVEL SECURITY;
ALTER TABLE saved_problem_set_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE saved_problem_set_items FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY problem_generation_request_targets_select ON problem_generation_request_targets
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_request_targets_insert ON problem_generation_request_targets
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_request_targets_update ON problem_generation_request_targets
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_request_targets_delete ON problem_generation_request_targets
    FOR DELETE USING (false);

CREATE POLICY problem_generation_items_select ON problem_generation_items
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_items_insert ON problem_generation_items
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_items_update ON problem_generation_items
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_items_delete ON problem_generation_items
    FOR DELETE USING (false);

CREATE POLICY problem_generation_item_options_select ON problem_generation_item_options
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_item_options_insert ON problem_generation_item_options
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_item_options_update ON problem_generation_item_options
    FOR UPDATE USING (false) WITH CHECK (false);
CREATE POLICY problem_generation_item_options_delete ON problem_generation_item_options
    FOR DELETE USING (false);

CREATE POLICY saved_problem_sets_select ON saved_problem_sets
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY saved_problem_sets_insert ON saved_problem_sets
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY saved_problem_sets_update ON saved_problem_sets
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY saved_problem_sets_delete ON saved_problem_sets
    FOR DELETE USING (false);

CREATE POLICY saved_problem_set_items_select ON saved_problem_set_items
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY saved_problem_set_items_insert ON saved_problem_set_items
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY saved_problem_set_items_update ON saved_problem_set_items
    FOR UPDATE USING (false) WITH CHECK (false);
CREATE POLICY saved_problem_set_items_delete ON saved_problem_set_items
    FOR DELETE USING (false);

CREATE POLICY problem_assignments_select ON problem_assignments
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_assignments_insert ON problem_assignments
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_assignments_update ON problem_assignments
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_assignments_delete ON problem_assignments
    FOR DELETE USING (false);

COMMENT ON TABLE problem_generation_request_targets IS
    'Frontend-selected area and type counts; generation_targets is the AI transport projection.';
COMMENT ON TABLE problem_generation_items IS
    'Backend read model projected from raw AI JSON; AI validation is not teacher publication.';
COMMENT ON TABLE saved_problem_sets IS
    'Idempotent teacher-owned snapshot of the selected generated items.';
COMMENT ON TABLE problem_assignments IS
    'Teacher publication record. Student consumption and submission are separate future contracts.';
