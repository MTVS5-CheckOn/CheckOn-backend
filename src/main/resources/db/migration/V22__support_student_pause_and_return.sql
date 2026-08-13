ALTER TABLE teacher_student_relationships
    DROP CONSTRAINT ck_teacher_student_relationships_status,
    DROP CONSTRAINT ck_teacher_student_relationships_status_time;

ALTER TABLE teacher_student_relationships
    ADD CONSTRAINT ck_teacher_student_relationships_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    ADD CONSTRAINT ck_teacher_student_relationships_status_time
        CHECK (
            (status IN ('ACTIVE', 'PAUSED') AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        );

DROP INDEX uq_teacher_student_relationships_active_student;
CREATE UNIQUE INDEX uq_teacher_student_relationships_current_student
    ON teacher_student_relationships (student_id)
    WHERE status IN ('ACTIVE', 'PAUSED');

ALTER TABLE class_enrollments
    DROP CONSTRAINT ck_class_enrollments_status,
    DROP CONSTRAINT ck_class_enrollments_status_time;

ALTER TABLE class_enrollments
    ADD CONSTRAINT ck_class_enrollments_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')),
    ADD CONSTRAINT ck_class_enrollments_status_time
        CHECK (
            (status IN ('ACTIVE', 'PAUSED') AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        );

DROP INDEX uq_class_enrollments_active_student;
CREATE UNIQUE INDEX uq_class_enrollments_current_student
    ON class_enrollments (student_id)
    WHERE status IN ('ACTIVE', 'PAUSED');

COMMENT ON COLUMN teacher_student_relationships.status IS
    'ACTIVE participates in current operations, PAUSED reserves the teacher relationship, and ENDED is final.';
COMMENT ON COLUMN class_enrollments.status IS
    'ACTIVE participates in current class operations, PAUSED reserves the class membership, and ENDED is final.';
