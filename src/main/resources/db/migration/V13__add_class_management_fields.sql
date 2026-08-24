-- SCREEN-CLASS-001/002: preserve V6~V12 rows while adding the fields and
-- transition guards needed by the class-management screen.
ALTER TABLE class_groups
    ADD COLUMN subject VARCHAR(100),
    ADD COLUMN memo VARCHAR(1000),
    ADD CONSTRAINT ck_class_groups_subject
        CHECK (
            subject IS NULL
            OR (
                subject = btrim(subject)
                AND char_length(subject) BETWEEN 1 AND 100
            )
        ),
    ADD CONSTRAINT ck_class_groups_memo
        CHECK (memo IS NULL OR char_length(memo) <= 1000);

COMMENT ON COLUMN class_groups.subject IS
    'Required by new class writes; nullable only for rows created before V13 until a teacher edits them.';
COMMENT ON COLUMN class_groups.memo IS
    'Optional teacher-owned class memo with an application and DB limit of 1000 characters.';

-- Do not silently rewrite inconsistent historical data. An operator must
-- inspect it before this migration can safely enforce the archive invariant.
DO $$
DECLARE
    teacher RECORD;
BEGIN
    -- V7 FORCE RLS also applies to a non-BYPASSRLS table owner. Inspect each
    -- tenant through the same transaction-local context rather than assuming
    -- that the Flyway role can perform an unrestricted global SELECT.
    PERFORM set_config('checkon.current_teacher_id', '', true);
    FOR teacher IN SELECT id FROM teacher_profiles LOOP
        PERFORM set_config(
            'checkon.current_teacher_id', teacher.id::text, true
        );
        IF EXISTS (
            SELECT 1
            FROM class_groups class_group
            JOIN class_enrollments enrollment
              ON enrollment.class_group_id = class_group.id
             AND enrollment.teacher_id = class_group.teacher_id
            WHERE class_group.teacher_id = teacher.id
              AND class_group.status = 'ARCHIVED'
              AND enrollment.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION
                'Cannot enforce class archive invariant for teacher %: an archived class has an active enrollment',
                teacher.id;
        END IF;
    END LOOP;
    PERFORM set_config('checkon.current_teacher_id', '', true);
END;
$$;

-- Existing rows may keep a null subject, but direct new writes must obey the
-- same required-subject contract as POST/PATCH. Updating an unrelated field on
-- a legacy row remains possible; once a subject exists it cannot be cleared.
CREATE FUNCTION enforce_class_subject_for_new_writes()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.subject IS NULL)
       OR (TG_OP = 'UPDATE' AND OLD.subject IS NOT NULL AND NEW.subject IS NULL) THEN
        RAISE EXCEPTION
            'class subject is required for new writes'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_class_groups_subject_required
    BEFORE INSERT OR UPDATE OF subject
    ON class_groups
    FOR EACH ROW
    EXECUTE FUNCTION enforce_class_subject_for_new_writes();

-- The class row is the serialization point shared by a future enrollment flow
-- and archive. FOR SHARE allows concurrent enrollments but conflicts with the
-- NO KEY UPDATE/UPDATE row lock used by a status change. The waiter then
-- observes the committed ACTIVE/ARCHIVED state before an ACTIVE enrollment is
-- accepted.
CREATE OR REPLACE FUNCTION check_active_teacher_for_enrollment()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'ACTIVE' THEN
        PERFORM 1
        FROM class_groups class_group
        WHERE class_group.id = NEW.class_group_id
          AND class_group.teacher_id = NEW.teacher_id
          AND class_group.status = 'ACTIVE'
		FOR SHARE;

        IF NOT FOUND THEN
            RAISE EXCEPTION
                'active enrollment requires an active class'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
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
    END IF;
    RETURN NEW;
END;
$$;

CREATE FUNCTION enforce_class_archive_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION
            'archived class cannot transition back to active'
            USING ERRCODE = '23514';
    END IF;

    IF OLD.status = 'ACTIVE' AND NEW.status = 'ARCHIVED' AND EXISTS (
        SELECT 1
        FROM class_enrollments enrollment
        WHERE enrollment.class_group_id = NEW.id
          AND enrollment.teacher_id = NEW.teacher_id
          AND enrollment.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION
            'active enrollments must end before class archive'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_class_groups_archive_transition
    BEFORE UPDATE OF status
    ON class_groups
    FOR EACH ROW
    EXECUTE FUNCTION enforce_class_archive_transition();

-- SCREEN-CLASS-001 defines deletion as archive. Keep the policy name so
-- operational policy-count checks remain stable, but make hard delete
-- impossible for the restricted runtime role even with a valid tenant context.
DROP POLICY class_groups_teacher_delete ON class_groups;
CREATE POLICY class_groups_teacher_delete ON class_groups
    FOR DELETE USING (false);
