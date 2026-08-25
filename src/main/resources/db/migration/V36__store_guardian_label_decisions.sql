ALTER TABLE guardian_label_suggestions
    ADD CONSTRAINT uq_guardian_label_suggestions_identity UNIQUE (id, teacher_id, parent_id);

CREATE TABLE guardian_labels (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    axis VARCHAR(20) NOT NULL,
    value VARCHAR(20) NOT NULL,
    source_suggestion_id TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_guardian_labels_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_labels_parent
        FOREIGN KEY (parent_id) REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_guardian_labels_source CHECK (btrim(source_suggestion_id) <> ''),
    CONSTRAINT ck_guardian_labels_axis CHECK (axis IN ('comm', 'sensitivity', 'interest', 'frequency')),
    CONSTRAINT ck_guardian_labels_axis_value CHECK (
        (axis = 'comm' AND value IN ('data', 'narrative'))
        OR (axis = 'sensitivity' AND value IN ('anxious', 'direct'))
        OR (axis = 'interest' AND value IN ('grade', 'attitude', 'admission'))
        OR (axis = 'frequency' AND value IN ('frequent', 'monthly'))
    ),
    CONSTRAINT uq_guardian_labels_teacher_parent_axis UNIQUE (teacher_id, parent_id, axis)
);

CREATE TABLE guardian_label_decisions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    suggestion_row_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    suggestion_id TEXT NOT NULL,
    axis VARCHAR(20) NOT NULL,
    action VARCHAR(20) NOT NULL,
    decided_value VARCHAR(20),
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_guardian_label_decisions_suggestion
        FOREIGN KEY (suggestion_row_id, teacher_id, parent_id)
        REFERENCES guardian_label_suggestions (id, teacher_id, parent_id) ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_label_decisions_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_label_decisions_parent
        FOREIGN KEY (parent_id) REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_guardian_label_decisions_id CHECK (btrim(suggestion_id) <> ''),
    CONSTRAINT ck_guardian_label_decisions_axis CHECK (axis IN ('comm', 'sensitivity', 'interest', 'frequency')),
    CONSTRAINT ck_guardian_label_decisions_action CHECK (action IN ('confirmed', 'corrected', 'rejected')),
    CONSTRAINT ck_guardian_label_decisions_value CHECK (
        (action = 'rejected' AND decided_value IS NULL)
        OR (action IN ('confirmed', 'corrected') AND (
            (axis = 'comm' AND decided_value IN ('data', 'narrative'))
            OR (axis = 'sensitivity' AND decided_value IN ('anxious', 'direct'))
            OR (axis = 'interest' AND decided_value IN ('grade', 'attitude', 'admission'))
            OR (axis = 'frequency' AND decided_value IN ('frequent', 'monthly'))
        ))
    ),
    CONSTRAINT uq_guardian_label_decisions_suggestion UNIQUE (suggestion_row_id)
);

ALTER TABLE guardian_labels ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardian_labels FORCE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_decisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_decisions FORCE ROW LEVEL SECURITY;

CREATE POLICY guardian_labels_teacher_select ON guardian_labels
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_labels_teacher_insert ON guardian_labels
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_labels_teacher_update ON guardian_labels
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_labels_teacher_delete ON guardian_labels
    FOR DELETE USING (false);

CREATE POLICY guardian_label_decisions_teacher_select ON guardian_label_decisions
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_decisions_teacher_insert ON guardian_label_decisions
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_decisions_teacher_update ON guardian_label_decisions
    FOR UPDATE USING (false);
CREATE POLICY guardian_label_decisions_teacher_delete ON guardian_label_decisions
    FOR DELETE USING (false);

COMMENT ON TABLE guardian_labels IS
    'Backend source of truth for the current teacher-scoped labels of one real parent.';
COMMENT ON TABLE guardian_label_decisions IS
    'Immutable teacher decisions over original AI label suggestions.';
