-- The counsel AI contract requires a parent_ref alias per request, but no
-- guardian identity concept exists in this codebase yet. v1 keeps one opaque
-- guardian channel per (teacher, student) — matching the ai_student_aliases /
-- ai_class_aliases pattern — rather than modeling full guardian profiles
-- (name, phone, relation), which is out of scope for the counsel AI contract.
CREATE TABLE ai_guardian_aliases (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    alias VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ai_guardian_aliases_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_guardian_aliases_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_ai_guardian_aliases_teacher_student UNIQUE (teacher_id, student_id),
    CONSTRAINT uq_ai_guardian_aliases_alias UNIQUE (alias),
    CONSTRAINT ck_ai_guardian_aliases_format CHECK (alias ~ '^pa_[0-9a-f]{32}$')
);

ALTER TABLE ai_guardian_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_guardian_aliases FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_guardian_aliases_teacher_select ON ai_guardian_aliases
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_guardian_aliases_teacher_insert ON ai_guardian_aliases
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_guardian_aliases_teacher_update ON ai_guardian_aliases
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_guardian_aliases_teacher_delete ON ai_guardian_aliases
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

COMMENT ON TABLE ai_guardian_aliases IS
    'Teacher-scoped opaque AI identifier for a student''s guardian channel (counsel parent_ref).';
