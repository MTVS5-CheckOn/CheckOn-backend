-- CheckOn owns the learning-record source of truth. The external AI receives
-- only the typed, pseudonymized projection built from these rows.
CREATE TABLE learning_records (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    class_group_id UUID,
    record_type VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    source_type VARCHAR(80) NOT NULL,
    external_record_ref VARCHAR(255),
    correct BOOLEAN,
    duration_sec INTEGER,
    passage_word_count INTEGER,
    area_tag VARCHAR(80),
    subject_track VARCHAR(80),
    type_tag VARCHAR(80),
    item_format VARCHAR(80),
    assignment_title_text VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_learning_records_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_records_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_learning_records_class_teacher
        FOREIGN KEY (class_group_id, teacher_id)
        REFERENCES class_groups (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT ck_learning_records_type CHECK (record_type IN ('SOLVE', 'SUBMIT')),
    CONSTRAINT ck_learning_records_source CHECK (
        source_type = btrim(source_type) AND char_length(source_type) BETWEEN 1 AND 80
    ),
    CONSTRAINT ck_learning_records_external_ref CHECK (
        external_record_ref IS NULL OR (
            external_record_ref = btrim(external_record_ref)
            AND char_length(external_record_ref) BETWEEN 1 AND 255
        )
    ),
    CONSTRAINT ck_learning_records_numbers CHECK (
        (duration_sec IS NULL OR duration_sec >= 0)
        AND (passage_word_count IS NULL OR passage_word_count >= 0)
    ),
    CONSTRAINT ck_learning_records_updated_at CHECK (updated_at >= created_at)
);

CREATE INDEX idx_learning_records_teacher_occurred
    ON learning_records (teacher_id, occurred_at, id);
CREATE INDEX idx_learning_records_teacher_student_occurred
    ON learning_records (teacher_id, student_id, occurred_at, id);

-- external_record_ref is deliberately neither unique nor an idempotency key.
-- The upstream policy is unresolved, so equal non-null values remain valid.
COMMENT ON COLUMN learning_records.external_record_ref IS
    'Nullable upstream reference; duplicates are allowed and no uniqueness policy is implied.';

CREATE TABLE ai_student_aliases (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    alias VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ai_student_aliases_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_student_aliases_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_ai_student_aliases_teacher_student UNIQUE (teacher_id, student_id),
    CONSTRAINT uq_ai_student_aliases_alias UNIQUE (alias),
    CONSTRAINT ck_ai_student_aliases_format CHECK (alias ~ '^st_[0-9a-f]{32}$')
);

ALTER TABLE learning_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE learning_records FORCE ROW LEVEL SECURITY;
ALTER TABLE ai_student_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_student_aliases FORCE ROW LEVEL SECURITY;

CREATE POLICY learning_records_teacher_select ON learning_records
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY learning_records_teacher_insert ON learning_records
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY learning_records_teacher_update ON learning_records
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY learning_records_teacher_delete ON learning_records
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

CREATE POLICY ai_student_aliases_teacher_select ON ai_student_aliases
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_student_aliases_teacher_insert ON ai_student_aliases
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_student_aliases_teacher_update ON ai_student_aliases
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_student_aliases_teacher_delete ON ai_student_aliases
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

COMMENT ON TABLE learning_records IS
    'Backend-owned learning source records protected by teacher-scoped repositories and RLS.';
COMMENT ON TABLE ai_student_aliases IS
    'Teacher-scoped opaque AI identifiers, distinct from mutable roster display aliases.';
