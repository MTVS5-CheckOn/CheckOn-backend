-- A teacher profile is the tenant identifier used by Roster and Detection.
-- Application queries still carry teacher_id explicitly; these policies are the
-- final defense against a missing WHERE clause, an unsafe repository call, or
-- direct SQL executed by the restricted runtime role.

CREATE FUNCTION current_checkon_teacher_id()
RETURNS UUID
LANGUAGE SQL
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(
        current_setting('checkon.current_teacher_id', true),
        ''
    )::UUID
$$;

COMMENT ON FUNCTION current_checkon_teacher_id() IS
    'Returns the transaction-local TeacherProfile ID. Missing context returns NULL so RLS denies every tenant row.';

-- FORCE is required because migrations may make the application role the table
-- owner. It closes the owner bypass, although PostgreSQL superusers and roles
-- with BYPASSRLS must still never be used as the runtime application role.
ALTER TABLE class_groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE class_groups FORCE ROW LEVEL SECURITY;
ALTER TABLE teacher_student_relationships ENABLE ROW LEVEL SECURITY;
ALTER TABLE teacher_student_relationships FORCE ROW LEVEL SECURITY;
ALTER TABLE class_enrollments ENABLE ROW LEVEL SECURITY;
ALTER TABLE class_enrollments FORCE ROW LEVEL SECURITY;
ALTER TABLE detection_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_runs FORCE ROW LEVEL SECURITY;
ALTER TABLE detection_request_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_request_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE detection_signal_results ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_signal_results FORCE ROW LEVEL SECURITY;
ALTER TABLE detection_result_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE detection_result_evidence FORCE ROW LEVEL SECURITY;

CREATE POLICY class_groups_teacher_select ON class_groups
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_groups_teacher_insert ON class_groups
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_groups_teacher_update ON class_groups
    FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_groups_teacher_delete ON class_groups
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

CREATE POLICY teacher_student_relationships_teacher_select
    ON teacher_student_relationships
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY teacher_student_relationships_teacher_insert
    ON teacher_student_relationships
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY teacher_student_relationships_teacher_update
    ON teacher_student_relationships
    FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY teacher_student_relationships_teacher_delete
    ON teacher_student_relationships
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

-- class_enrollments intentionally carries teacher_id as a denormalized ownership
-- key. Its composite FK to class_groups proves that it matches the parent class.
CREATE POLICY class_enrollments_teacher_select ON class_enrollments
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_enrollments_teacher_insert ON class_enrollments
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_enrollments_teacher_update ON class_enrollments
    FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY class_enrollments_teacher_delete ON class_enrollments
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

CREATE POLICY detection_runs_teacher_select ON detection_runs
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_runs_teacher_insert ON detection_runs
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_runs_teacher_update ON detection_runs
    FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY detection_runs_teacher_delete ON detection_runs
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

-- Detection child rows inherit their tenant through the immutable parent path.
-- The EXISTS checks prevent an attacker who knows a child UUID from crossing
-- into another teacher's run.
CREATE POLICY detection_request_attempts_teacher_select
    ON detection_request_attempts
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_request_attempts.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_request_attempts_teacher_insert
    ON detection_request_attempts
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_request_attempts.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_request_attempts_teacher_update
    ON detection_request_attempts
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_request_attempts.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_request_attempts.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_request_attempts_teacher_delete
    ON detection_request_attempts
    FOR DELETE USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_request_attempts.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );

CREATE POLICY detection_signal_results_teacher_select
    ON detection_signal_results
    FOR SELECT USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_signal_results.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_signal_results_teacher_insert
    ON detection_signal_results
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_signal_results.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_signal_results_teacher_update
    ON detection_signal_results
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_signal_results.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_signal_results.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_signal_results_teacher_delete
    ON detection_signal_results
    FOR DELETE USING (
        EXISTS (
            SELECT 1 FROM detection_runs run
            WHERE run.id = detection_signal_results.detection_run_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );

CREATE POLICY detection_result_evidence_teacher_select
    ON detection_result_evidence
    FOR SELECT USING (
        EXISTS (
            SELECT 1
            FROM detection_signal_results signal
            JOIN detection_runs run ON run.id = signal.detection_run_id
            WHERE signal.id = detection_result_evidence.detection_signal_result_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_result_evidence_teacher_insert
    ON detection_result_evidence
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1
            FROM detection_signal_results signal
            JOIN detection_runs run ON run.id = signal.detection_run_id
            WHERE signal.id = detection_result_evidence.detection_signal_result_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_result_evidence_teacher_update
    ON detection_result_evidence
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1
            FROM detection_signal_results signal
            JOIN detection_runs run ON run.id = signal.detection_run_id
            WHERE signal.id = detection_result_evidence.detection_signal_result_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM detection_signal_results signal
            JOIN detection_runs run ON run.id = signal.detection_run_id
            WHERE signal.id = detection_result_evidence.detection_signal_result_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY detection_result_evidence_teacher_delete
    ON detection_result_evidence
    FOR DELETE USING (
        EXISTS (
            SELECT 1
            FROM detection_signal_results signal
            JOIN detection_runs run ON run.id = signal.detection_run_id
            WHERE signal.id = detection_result_evidence.detection_signal_result_id
              AND run.teacher_id = current_checkon_teacher_id()
        )
    );

COMMENT ON TABLE class_groups IS
    'Teacher-owned roster data protected by explicit teacher-scoped queries and PostgreSQL RLS.';
COMMENT ON TABLE teacher_student_relationships IS
    'Teacher-owned relationship history; RLS blocks cross-teacher IDOR and direct SQL.';
COMMENT ON TABLE class_enrollments IS
    'Teacher ownership follows teacher_id and its composite class_groups foreign key.';
COMMENT ON TABLE detection_runs IS
    'Root tenant boundary for Detection; child RLS policies follow this run.';
COMMENT ON TABLE detection_request_attempts IS
    'Tenant ownership is inherited from detection_runs through detection_run_id.';
COMMENT ON TABLE detection_signal_results IS
    'Tenant ownership is inherited from detection_runs through detection_run_id.';
COMMENT ON TABLE detection_result_evidence IS
    'Tenant ownership is inherited through detection_signal_results to detection_runs.';
