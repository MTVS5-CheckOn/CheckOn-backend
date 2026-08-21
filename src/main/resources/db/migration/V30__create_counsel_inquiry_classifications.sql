-- Local mirror of POST /v1/classify's prediction plus any teacher correction
-- from POST /v1/confirmations. This is not optional bookkeeping: classify is
-- cached by (tenant_id, inquiry_ref) on the AI side, so calling it again
-- after a correction returns the stale pre-correction prediction. The
-- corrected_* columns are the only place the "current" value survives.
CREATE TABLE counsel_inquiry_classifications (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    tenant_alias VARCHAR(40) NOT NULL,
    inquiry_ref VARCHAR(200) NOT NULL,
    predicted_topic VARCHAR(30) NOT NULL,
    predicted_sentiment VARCHAR(20) NOT NULL,
    predicted_urgency VARCHAR(20) NOT NULL,
    confidence_topic NUMERIC(4, 3) NOT NULL,
    confidence_sentiment NUMERIC(4, 3) NOT NULL,
    confidence_urgency NUMERIC(4, 3) NOT NULL,
    classified BOOLEAN NOT NULL,
    fallback_reason VARCHAR(30),
    ai_execution_id VARCHAR(80),
    classified_at TIMESTAMPTZ NOT NULL,
    corrected_topic VARCHAR(30),
    corrected_sentiment VARCHAR(20),
    corrected_urgency VARCHAR(20),
    confirmation_action VARCHAR(20),
    confirmed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_counsel_inquiry_classification_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_counsel_inquiry_classification_inquiry
        UNIQUE (teacher_id, inquiry_ref),
    CONSTRAINT ck_counsel_inquiry_classification_topic
        CHECK (predicted_topic IN ('grade', 'schedule', 'counsel_request', 'etc')),
    CONSTRAINT ck_counsel_inquiry_classification_sentiment
        CHECK (predicted_sentiment IN ('normal', 'complaint')),
    CONSTRAINT ck_counsel_inquiry_classification_urgency
        CHECK (predicted_urgency IN ('immediate', 'normal')),
    CONSTRAINT ck_counsel_inquiry_classification_confidence CHECK (
        confidence_topic BETWEEN 0 AND 1
        AND confidence_sentiment BETWEEN 0 AND 1
        AND confidence_urgency BETWEEN 0 AND 1
    ),
    CONSTRAINT ck_counsel_inquiry_classification_fallback_reason
        CHECK (fallback_reason IS NULL OR fallback_reason IN ('parse_exhausted', 'tripwire_blocked')),
    CONSTRAINT ck_counsel_inquiry_classification_corrected_topic
        CHECK (corrected_topic IS NULL OR corrected_topic IN ('grade', 'schedule', 'counsel_request', 'etc')),
    CONSTRAINT ck_counsel_inquiry_classification_corrected_sentiment
        CHECK (corrected_sentiment IS NULL OR corrected_sentiment IN ('normal', 'complaint')),
    CONSTRAINT ck_counsel_inquiry_classification_corrected_urgency
        CHECK (corrected_urgency IS NULL OR corrected_urgency IN ('immediate', 'normal')),
    CONSTRAINT ck_counsel_inquiry_classification_confirmation_action
        CHECK (confirmation_action IS NULL OR confirmation_action IN ('confirmed', 'corrected')),
    CONSTRAINT ck_counsel_inquiry_classification_confirmed_pair CHECK (
        (confirmation_action IS NULL AND confirmed_at IS NULL)
        OR (confirmation_action IS NOT NULL AND confirmed_at IS NOT NULL)
    )
);

ALTER TABLE counsel_inquiry_classifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE counsel_inquiry_classifications FORCE ROW LEVEL SECURITY;

CREATE POLICY counsel_inquiry_classifications_select ON counsel_inquiry_classifications
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiry_classifications_insert ON counsel_inquiry_classifications
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiry_classifications_update ON counsel_inquiry_classifications
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_inquiry_classifications_delete ON counsel_inquiry_classifications
    FOR DELETE USING (false);

COMMENT ON TABLE counsel_inquiry_classifications IS
    'Local mirror of classify predictions and teacher corrections; classify itself is cached upstream by (tenant_id, inquiry_ref) and will not re-serve a correction.';
