-- A student may belong to multiple teacher tenants. ACTIVE and PAUSED rows are
-- both current relationships, so uniqueness is scoped to one teacher/student
-- pair instead of globally to the student.
DROP INDEX uq_teacher_student_relationships_current_student;
CREATE UNIQUE INDEX uq_teacher_student_relationships_current_teacher_student
    ON teacher_student_relationships (teacher_id, student_id)
    WHERE status IN ('ACTIVE', 'PAUSED');

DROP INDEX uq_class_enrollments_current_student;
CREATE UNIQUE INDEX uq_class_enrollments_current_teacher_student
    ON class_enrollments (teacher_id, student_id)
    WHERE status IN ('ACTIVE', 'PAUSED');

ALTER TABLE accounts
    ADD CONSTRAINT uq_accounts_id_role UNIQUE (id, role);

CREATE TABLE parent_profiles (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    account_role VARCHAR(20) NOT NULL DEFAULT 'PARENT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_parent_profiles_parent_account
        FOREIGN KEY (account_id, account_role)
        REFERENCES accounts (id, role)
        ON DELETE RESTRICT,
    CONSTRAINT uq_parent_profiles_account UNIQUE (account_id),
    CONSTRAINT ck_parent_profiles_account_role CHECK (account_role = 'PARENT'),
    CONSTRAINT ck_parent_profiles_updated_at CHECK (updated_at >= created_at)
);

CREATE TABLE parent_teacher_relationships (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    parent_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_parent_teacher_relationships_parent
        FOREIGN KEY (parent_id)
        REFERENCES parent_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_parent_teacher_relationships_teacher
        FOREIGN KEY (teacher_id)
        REFERENCES teacher_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_parent_teacher_relationships_status
        CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_parent_teacher_relationships_time
        CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_parent_teacher_relationships_status_time
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_parent_teacher_relationships_active_pair
    ON parent_teacher_relationships (parent_id, teacher_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_parent_teacher_relationships_teacher_status
    ON parent_teacher_relationships (teacher_id, status);

CREATE TABLE parent_student_relationships (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    parent_id UUID NOT NULL,
    student_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_parent_student_relationships_parent
        FOREIGN KEY (parent_id)
        REFERENCES parent_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_parent_student_relationships_student
        FOREIGN KEY (student_id)
        REFERENCES student_profiles (id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_parent_student_relationships_status
        CHECK (status IN ('ACTIVE', 'ENDED')),
    CONSTRAINT ck_parent_student_relationships_time
        CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_parent_student_relationships_status_time
        CHECK (
            (status = 'ACTIVE' AND ended_at IS NULL)
            OR (status = 'ENDED' AND ended_at IS NOT NULL)
        )
);

CREATE UNIQUE INDEX uq_parent_student_relationships_active_student
    ON parent_student_relationships (student_id)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_parent_student_relationships_parent_status
    ON parent_student_relationships (parent_id, status);

-- Parent data is not globally readable by the runtime role. Teacher access is
-- granted only through current relationships in the transaction-local tenant.
ALTER TABLE parent_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE parent_teacher_relationships ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_teacher_relationships FORCE ROW LEVEL SECURITY;
ALTER TABLE parent_student_relationships ENABLE ROW LEVEL SECURITY;
ALTER TABLE parent_student_relationships FORCE ROW LEVEL SECURITY;

CREATE POLICY parent_profiles_teacher_select ON parent_profiles
    FOR SELECT USING (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships relationship
            WHERE relationship.parent_id = parent_profiles.id
              AND relationship.teacher_id = current_checkon_teacher_id()
              AND relationship.status = 'ACTIVE'
        )
    );

CREATE POLICY parent_teacher_relationships_teacher_select
    ON parent_teacher_relationships
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY parent_teacher_relationships_teacher_insert
    ON parent_teacher_relationships
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY parent_teacher_relationships_teacher_update
    ON parent_teacher_relationships
    FOR UPDATE
    USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY parent_teacher_relationships_teacher_delete
    ON parent_teacher_relationships
    FOR DELETE USING (teacher_id = current_checkon_teacher_id());

CREATE POLICY parent_student_relationships_teacher_select
    ON parent_student_relationships
    FOR SELECT USING (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships parent_teacher
            JOIN teacher_student_relationships teacher_student
              ON teacher_student.student_id = parent_student_relationships.student_id
             AND teacher_student.teacher_id = parent_teacher.teacher_id
             AND teacher_student.status IN ('ACTIVE', 'PAUSED')
            WHERE parent_teacher.parent_id = parent_student_relationships.parent_id
              AND parent_teacher.teacher_id = current_checkon_teacher_id()
              AND parent_teacher.status = 'ACTIVE'
        )
    );
CREATE POLICY parent_student_relationships_teacher_insert
    ON parent_student_relationships
    FOR INSERT WITH CHECK (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships parent_teacher
            JOIN teacher_student_relationships teacher_student
              ON teacher_student.student_id = parent_student_relationships.student_id
             AND teacher_student.teacher_id = parent_teacher.teacher_id
             AND teacher_student.status IN ('ACTIVE', 'PAUSED')
            WHERE parent_teacher.parent_id = parent_student_relationships.parent_id
              AND parent_teacher.teacher_id = current_checkon_teacher_id()
              AND parent_teacher.status = 'ACTIVE'
        )
    );
CREATE POLICY parent_student_relationships_teacher_update
    ON parent_student_relationships
    FOR UPDATE
    USING (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships parent_teacher
            JOIN teacher_student_relationships teacher_student
              ON teacher_student.student_id = parent_student_relationships.student_id
             AND teacher_student.teacher_id = parent_teacher.teacher_id
             AND teacher_student.status IN ('ACTIVE', 'PAUSED')
            WHERE parent_teacher.parent_id = parent_student_relationships.parent_id
              AND parent_teacher.teacher_id = current_checkon_teacher_id()
              AND parent_teacher.status = 'ACTIVE'
        )
    )
    WITH CHECK (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships parent_teacher
            JOIN teacher_student_relationships teacher_student
              ON teacher_student.student_id = parent_student_relationships.student_id
             AND teacher_student.teacher_id = parent_teacher.teacher_id
             AND teacher_student.status IN ('ACTIVE', 'PAUSED')
            WHERE parent_teacher.parent_id = parent_student_relationships.parent_id
              AND parent_teacher.teacher_id = current_checkon_teacher_id()
              AND parent_teacher.status = 'ACTIVE'
        )
    );
CREATE POLICY parent_student_relationships_teacher_delete
    ON parent_student_relationships
    FOR DELETE USING (
        EXISTS (
            SELECT 1
            FROM parent_teacher_relationships parent_teacher
            JOIN teacher_student_relationships teacher_student
              ON teacher_student.student_id = parent_student_relationships.student_id
             AND teacher_student.teacher_id = parent_teacher.teacher_id
             AND teacher_student.status IN ('ACTIVE', 'PAUSED')
            WHERE parent_teacher.parent_id = parent_student_relationships.parent_id
              AND parent_teacher.teacher_id = current_checkon_teacher_id()
              AND parent_teacher.status = 'ACTIVE'
        )
    );

COMMENT ON TABLE parent_profiles IS
    'Global parent identity profile. Teacher visibility requires an active parent-teacher relationship.';
COMMENT ON TABLE parent_teacher_relationships IS
    'Historical parent membership in teacher tenants; active uniqueness is scoped to the pair.';
COMMENT ON TABLE parent_student_relationships IS
    'Historical parent-child links; only one active parent account is allowed per student.';
