-- Roster owns operational teacher, student, class, and membership data.
-- Account remains responsible only for login identity, role, and credentials.

CREATE TABLE student_profiles (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID,
    alias VARCHAR(80) NOT NULL,
    grade SMALLINT,
    account_linked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_student_profiles_account
        FOREIGN KEY (account_id)
        REFERENCES accounts (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_student_profiles_account UNIQUE (account_id),
    CONSTRAINT ck_student_profiles_alias
        CHECK (
            alias = btrim(alias)
            AND char_length(alias) BETWEEN 1 AND 80
        ),
    CONSTRAINT ck_student_profiles_grade
        CHECK (grade IS NULL OR grade BETWEEN 1 AND 3),
    CONSTRAINT ck_student_profiles_account_link
        CHECK (
            (account_id IS NULL AND account_linked_at IS NULL)
            OR (account_id IS NOT NULL AND account_linked_at IS NOT NULL)
        ),
    CONSTRAINT ck_student_profiles_updated_at
        CHECK (updated_at >= created_at)
);

CREATE TABLE class_groups (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_class_groups_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_class_groups_id_teacher UNIQUE (id, teacher_id),
    CONSTRAINT ck_class_groups_name
        CHECK (
            name = btrim(name)
            AND char_length(name) BETWEEN 1 AND 100
        ),
    CONSTRAINT ck_class_groups_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_class_groups_updated_at
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_class_groups_teacher_status
    ON class_groups (teacher_id, status);

-- Relationship rows are historical records. Ending one changes only its status and
-- end time; a later relationship is inserted as a new row.
CREATE TABLE teacher_student_relationships (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_teacher_student_relationships_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_teacher_student_relationships_student
        FOREIGN KEY (student_id)
        REFERENCES student_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_teacher_student_relationships_status
        CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_teacher_student_relationships_time
        CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_teacher_student_relationships_status_time
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        )
);

-- Application pre-checks can provide a friendly message, but only this partial
-- unique index closes the race between concurrent requests.
CREATE UNIQUE INDEX uq_teacher_student_relationships_active_student
    ON teacher_student_relationships (student_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_teacher_student_relationships_teacher_status
    ON teacher_student_relationships (teacher_id, status);

CREATE TABLE class_enrollments (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    class_group_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    enrolled_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_class_enrollments_class_teacher
        FOREIGN KEY (class_group_id, teacher_id)
        REFERENCES class_groups (id, teacher_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_class_enrollments_student
        FOREIGN KEY (student_id)
        REFERENCES student_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_class_enrollments_status
        CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_class_enrollments_time
        CHECK (ended_at IS NULL OR ended_at >= enrolled_at),
    CONSTRAINT ck_class_enrollments_status_time
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_class_enrollments_active_student
    ON class_enrollments (student_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_class_enrollments_class_status
    ON class_enrollments (class_group_id, status);

-- A class enrollment must use the same teacher as the student's active teacher
-- relationship. The trigger complements the scalar FKs without coupling JPA
-- aggregates through an object graph.
CREATE FUNCTION check_active_teacher_for_enrollment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' AND NOT EXISTS (
        SELECT 1
        FROM teacher_student_relationships relationship
        WHERE relationship.teacher_id = NEW.teacher_id
          AND relationship.student_id = NEW.student_id
          AND relationship.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'active enrollment requires an active relationship with the class teacher'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_class_enrollments_active_teacher
    BEFORE INSERT OR UPDATE OF class_group_id, teacher_id, student_id, status
    ON class_enrollments
    FOR EACH ROW
    EXECUTE FUNCTION check_active_teacher_for_enrollment();

-- The Detection aggregate keeps a UUID reference. This FK gives that scalar
-- reference database-level meaning without a cross-context JPA association.
-- Existing environments must not silently reinterpret orphan teacher IDs. Stop
-- with a targeted message so operators can inspect and reconcile those rows.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM detection_runs run
        LEFT JOIN teacher_profiles teacher ON teacher.id = run.teacher_id
        WHERE teacher.id IS NULL
    ) THEN
        RAISE EXCEPTION
            'Cannot add Detection teacher FK: orphan detection_runs.teacher_id exists';
    END IF;
END;
$$;

ALTER TABLE detection_runs
    ADD CONSTRAINT fk_detection_runs_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher_profiles (id)
        ON DELETE RESTRICT;

COMMENT ON COLUMN detection_runs.teacher_id IS
    'Roster TeacherProfile identifier and tenant boundary; kept as a scalar UUID in Detection.';
