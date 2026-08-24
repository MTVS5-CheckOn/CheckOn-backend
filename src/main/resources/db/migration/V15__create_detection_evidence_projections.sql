-- AI needs citable proof for absence-based signals. These tables own only the
-- backend projections/history, never an AI result or a student real name.

CREATE TABLE detection_assignment_week_summaries (
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    week_start DATE NOT NULL,
    expected_count INTEGER NOT NULL,
    submitted_count INTEGER NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_detection_assignment_week_summaries
        PRIMARY KEY (teacher_id, student_id, week_start),
    CONSTRAINT fk_detection_assignment_week_summaries_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_detection_assignment_week_summaries_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_detection_assignment_week_summaries_week_start
        CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    CONSTRAINT ck_detection_assignment_week_summaries_counts
        CHECK (
            expected_count >= 0
            AND submitted_count >= 0
            AND submitted_count <= expected_count
        )
);

CREATE TABLE detection_student_status_history (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    from_status VARCHAR(20) NOT NULL,
    to_status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_detection_student_status_history_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_detection_student_status_history_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_detection_student_status_history_values
        CHECK (
            from_status IN ('enrolled', 'paused', 'returned', 'ended')
            AND to_status IN ('enrolled', 'paused', 'returned', 'ended')
            AND from_status <> to_status
        )
);

CREATE INDEX idx_detection_student_status_history_teacher_occurred
    ON detection_student_status_history (teacher_id, occurred_at, id);

ALTER TABLE detection_assignment_week_summaries ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_assignment_week_summaries FORCE ROW LEVEL SECURITY;
ALTER TABLE detection_student_status_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_student_status_history FORCE ROW LEVEL SECURITY;

CREATE POLICY detection_assignment_week_summaries_teacher_select
    ON detection_assignment_week_summaries
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_assignment_week_summaries_teacher_insert
    ON detection_assignment_week_summaries
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_assignment_week_summaries_teacher_update
    ON detection_assignment_week_summaries
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_assignment_week_summaries_teacher_delete
    ON detection_assignment_week_summaries
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

CREATE POLICY detection_student_status_history_teacher_select
    ON detection_student_status_history
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_student_status_history_teacher_insert
    ON detection_student_status_history
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_student_status_history_teacher_update
    ON detection_student_status_history
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_student_status_history_teacher_delete
    ON detection_student_status_history
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

COMMENT ON TABLE detection_assignment_week_summaries IS
    'Teacher-scoped assignment projection. Missing rows become explicit 0/0 evidence only until an assignment producer is implemented.';
COMMENT ON TABLE detection_student_status_history IS
    'Teacher-scoped student status transition history for AI return-care evidence.';
