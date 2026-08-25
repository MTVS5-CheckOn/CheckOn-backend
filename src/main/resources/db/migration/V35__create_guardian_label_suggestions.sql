CREATE TABLE ai_parent_label_aliases (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    alias TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_ai_parent_label_aliases_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_ai_parent_label_aliases_parent
        FOREIGN KEY (parent_id) REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_ai_parent_label_aliases_teacher_parent UNIQUE (teacher_id, parent_id),
    CONSTRAINT uq_ai_parent_label_aliases_alias UNIQUE (alias),
    CONSTRAINT ck_ai_parent_label_aliases_non_blank CHECK (btrim(alias) <> '')
);

CREATE TABLE guardian_label_suggestion_requests (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    guardian_ref TEXT NOT NULL,
    history_count SMALLINT NOT NULL,
    latest_record_id TEXT NOT NULL,
    history JSONB NOT NULL,
    ai_execution_id TEXT,
    ai_versions JSONB,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_guardian_label_requests_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_label_requests_parent
        FOREIGN KEY (parent_id) REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_guardian_label_requests_ref CHECK (btrim(guardian_ref) <> ''),
    CONSTRAINT ck_guardian_label_requests_history_count CHECK (history_count BETWEEN 5 AND 10),
    CONSTRAINT ck_guardian_label_requests_latest_record CHECK (btrim(latest_record_id) <> ''),
    CONSTRAINT ck_guardian_label_requests_history_array CHECK (jsonb_typeof(history) = 'array'),
    CONSTRAINT uq_guardian_label_requests_identity UNIQUE (id, teacher_id, parent_id),
    CONSTRAINT uq_guardian_label_requests_history_version
        UNIQUE (teacher_id, parent_id, history_count, latest_record_id)
);

CREATE TABLE guardian_label_suggestions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    request_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    suggestion_id TEXT NOT NULL,
    axis VARCHAR(20) NOT NULL,
    value VARCHAR(20) NOT NULL,
    confidence NUMERIC,
    evidence_quotes JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_guardian_label_suggestions_request
        FOREIGN KEY (request_id, teacher_id, parent_id)
        REFERENCES guardian_label_suggestion_requests (id, teacher_id, parent_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_label_suggestions_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_guardian_label_suggestions_parent
        FOREIGN KEY (parent_id) REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_guardian_label_suggestions_id CHECK (btrim(suggestion_id) <> ''),
    CONSTRAINT ck_guardian_label_suggestions_axis CHECK (axis IN ('comm', 'sensitivity', 'interest', 'frequency')),
    CONSTRAINT ck_guardian_label_suggestions_axis_value CHECK (
        (axis = 'comm' AND value IN ('data', 'narrative'))
        OR (axis = 'sensitivity' AND value IN ('anxious', 'direct'))
        OR (axis = 'interest' AND value IN ('grade', 'attitude', 'admission'))
        OR (axis = 'frequency' AND value IN ('frequent', 'monthly'))
    ),
    CONSTRAINT ck_guardian_label_suggestions_evidence CHECK (jsonb_typeof(evidence_quotes) = 'array'),
    CONSTRAINT uq_guardian_label_suggestions_request_id UNIQUE (request_id, suggestion_id),
    CONSTRAINT uq_guardian_label_suggestions_request_axis UNIQUE (request_id, axis)
);

CREATE INDEX idx_guardian_label_requests_parent_created
    ON guardian_label_suggestion_requests (teacher_id, parent_id, created_at DESC);

ALTER TABLE ai_parent_label_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_parent_label_aliases FORCE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_suggestion_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_suggestion_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_suggestions ENABLE ROW LEVEL SECURITY;
ALTER TABLE guardian_label_suggestions FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_parent_label_aliases_teacher_select ON ai_parent_label_aliases
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_parent_label_aliases_teacher_insert ON ai_parent_label_aliases
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_parent_label_aliases_teacher_update ON ai_parent_label_aliases
    FOR UPDATE USING (false);
CREATE POLICY ai_parent_label_aliases_teacher_delete ON ai_parent_label_aliases
    FOR DELETE USING (false);

CREATE POLICY guardian_label_requests_teacher_select ON guardian_label_suggestion_requests
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_requests_teacher_insert ON guardian_label_suggestion_requests
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_requests_teacher_update ON guardian_label_suggestion_requests
    FOR UPDATE USING (false);
CREATE POLICY guardian_label_requests_teacher_delete ON guardian_label_suggestion_requests
    FOR DELETE USING (false);

CREATE POLICY guardian_label_suggestions_teacher_select ON guardian_label_suggestions
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_suggestions_teacher_insert ON guardian_label_suggestions
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY guardian_label_suggestions_teacher_update ON guardian_label_suggestions
    FOR UPDATE USING (false);
CREATE POLICY guardian_label_suggestions_teacher_delete ON guardian_label_suggestions
    FOR DELETE USING (false);

COMMENT ON TABLE ai_parent_label_aliases IS
    'Stable opaque AI guardian_ref scoped to one teacher and one real parent.';
COMMENT ON TABLE guardian_label_suggestion_requests IS
    'Backend-owned history-version cache for synchronous guardian label suggestions.';
COMMENT ON TABLE guardian_label_suggestions IS
    'Original AI label suggestions and evidence; never promoted without a teacher decision.';
