ALTER TABLE detection_assignment_week_summaries
    ADD COLUMN id UUID NOT NULL DEFAULT uuidv7(),
    DROP CONSTRAINT pk_detection_assignment_week_summaries,
    ADD CONSTRAINT pk_detection_assignment_week_summaries PRIMARY KEY (id),
    ADD CONSTRAINT uq_detection_assignment_week_summaries_student_week
        UNIQUE (teacher_id, student_id, week_start);

ALTER TABLE detection_student_status_history
    DROP CONSTRAINT ck_detection_student_status_history_values,
    ADD CONSTRAINT ck_detection_student_status_history_values
        CHECK (
            from_status IN ('enrolled', 'paused', 'returned')
            AND to_status IN ('enrolled', 'paused', 'returned')
            AND from_status <> to_status
        );

CREATE INDEX idx_detection_assignment_summary_batch_window
    ON detection_assignment_week_summaries (teacher_id, week_start DESC, student_id);

CREATE INDEX idx_detection_status_history_return_window
    ON detection_student_status_history (
        teacher_id, to_status, occurred_at DESC, id
    );

COMMENT ON TABLE detection_assignment_week_summaries IS
    'Authoritative KST weekly assignment projection. Explicit zero means no assignments; unknown weeks remain absent.';
COMMENT ON COLUMN detection_assignment_week_summaries.id IS
    'Opaque source row identifier exposed as assignment_window record_id.';
COMMENT ON TABLE detection_student_status_history IS
    'Authoritative enrolled, paused, and returned transitions. Return care uses to_status=returned in the analysis week.';
