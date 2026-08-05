CREATE TABLE student_personal_information (
    student_id UUID PRIMARY KEY,
    real_name VARCHAR(100) NOT NULL,
    updated_by_account_id UUID NOT NULL,
    updated_by_role VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_student_personal_information_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_student_personal_information_updated_by_account
        FOREIGN KEY (updated_by_account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_student_personal_information_real_name CHECK (
        real_name = btrim(real_name)
        AND char_length(real_name) BETWEEN 1 AND 100
    ),
    CONSTRAINT ck_student_personal_information_updated_by_role
        CHECK (updated_by_role = 'TEACHER'),
    CONSTRAINT ck_student_personal_information_updated_at
        CHECK (updated_at >= created_at)
);

ALTER TABLE student_personal_information ENABLE ROW LEVEL SECURITY;
ALTER TABLE student_personal_information FORCE ROW LEVEL SECURITY;

CREATE POLICY student_personal_information_teacher_select
    ON student_personal_information FOR SELECT USING (
        EXISTS (
            SELECT 1
            FROM teacher_student_relationships relationship
            WHERE relationship.student_id = student_personal_information.student_id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    );

CREATE POLICY student_personal_information_teacher_insert
    ON student_personal_information FOR INSERT WITH CHECK (
        updated_by_role = 'TEACHER'
        AND EXISTS (
            SELECT 1
            FROM teacher_profiles teacher
            WHERE teacher.id = current_checkon_teacher_id()
              AND teacher.account_id = updated_by_account_id
        )
        AND EXISTS (
            SELECT 1
            FROM teacher_student_relationships relationship
            WHERE relationship.student_id = student_personal_information.student_id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    );

CREATE POLICY student_personal_information_teacher_update
    ON student_personal_information FOR UPDATE USING (
        EXISTS (
            SELECT 1
            FROM teacher_student_relationships relationship
            WHERE relationship.student_id = student_personal_information.student_id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    ) WITH CHECK (
        updated_by_role = 'TEACHER'
        AND EXISTS (
            SELECT 1
            FROM teacher_profiles teacher
            WHERE teacher.id = current_checkon_teacher_id()
              AND teacher.account_id = updated_by_account_id
        )
        AND EXISTS (
            SELECT 1
            FROM teacher_student_relationships relationship
            WHERE relationship.student_id = student_personal_information.student_id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    );

CREATE POLICY student_personal_information_teacher_delete
    ON student_personal_information FOR DELETE USING (
        EXISTS (
            SELECT 1
            FROM teacher_student_relationships relationship
            WHERE relationship.student_id = student_personal_information.student_id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    );

COMMENT ON TABLE student_personal_information IS
    'Globally student-owned PII; teacher access exists only through the current ACTIVE relationship.';

