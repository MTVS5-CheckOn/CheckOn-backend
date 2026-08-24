-- Stores the original inquiry context (student/class refs, raw text, labels,
-- facts, ...) that a counsel draft was built from. Needed to redraft with a
-- corrected topic after a teacher confirms a classify correction — the
-- backend cannot recompute this from anything AI-side, since counsel never
-- echoes the request payload back.
CREATE TABLE counsel_inquiries (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    inquiry_ref VARCHAR(200) NOT NULL,
    student_id UUID NOT NULL,
    class_id UUID NOT NULL,
    topic VARCHAR(30) NOT NULL,
    urgency VARCHAR(20) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    raw_text TEXT NOT NULL,
    labels JSONB NOT NULL,
    dismissed_suggestions JSONB NOT NULL,
    period_label VARCHAR(120) NOT NULL,
    facts JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_counsel_inquiries_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_counsel_inquiries_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_counsel_inquiries_class
        FOREIGN KEY (class_id) REFERENCES class_groups (id) ON DELETE RESTRICT,
    CONSTRAINT uq_counsel_inquiries_teacher_ref
        UNIQUE (teacher_id, inquiry_ref),
    CONSTRAINT ck_counsel_inquiries_topic
        CHECK (topic IN ('grade', 'schedule', 'counsel_request', 'etc')),
    CONSTRAINT ck_counsel_inquiries_urgency
        CHECK (urgency IN ('immediate', 'normal')),
    CONSTRAINT ck_counsel_inquiries_time CHECK (updated_at >= created_at)
);

ALTER TABLE counsel_inquiries ENABLE ROW LEVEL SECURITY;
ALTER TABLE counsel_inquiries FORCE ROW LEVEL SECURITY;

CREATE POLICY counsel_inquiries_select ON counsel_inquiries
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiries_insert ON counsel_inquiries
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiries_update ON counsel_inquiries
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiries_delete ON counsel_inquiries
    FOR DELETE USING (false);

COMMENT ON TABLE counsel_inquiries IS
    'Original inquiry context behind a counsel draft, kept so a topic correction can redraft with the same student/class/facts without the frontend resending everything.';
