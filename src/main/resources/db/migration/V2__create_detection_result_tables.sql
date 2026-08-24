CREATE TABLE detection_signal_results (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    detection_run_id UUID NOT NULL,
    external_signal_id VARCHAR(120) NOT NULL,
    student_ref VARCHAR(80) NOT NULL,
    class_ref VARCHAR(80) NOT NULL,
    rule_id VARCHAR(20) NOT NULL,
    signal_type VARCHAR(40) NOT NULL,
    display_label VARCHAR(100) NOT NULL,
    score NUMERIC(18, 16) NOT NULL,
    rank INTEGER NOT NULL,
    lifecycle VARCHAR(20) NOT NULL,
    brief_text TEXT NOT NULL,
    gate_passed BOOLEAN NOT NULL,
    fallback_used BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_detection_signal_results_run
        FOREIGN KEY (detection_run_id)
        REFERENCES detection_runs (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_detection_signal_results_run_external_signal
        UNIQUE (detection_run_id, external_signal_id),
    CONSTRAINT ck_detection_signal_results_score
        CHECK (score >= 0 AND score <= 1),
    CONSTRAINT ck_detection_signal_results_rank
        CHECK (rank >= 1),
    CONSTRAINT ck_detection_signal_results_lifecycle
        CHECK (lifecycle IN ('NEW', 'ONGOING', 'FOLLOW_UP')),
    CONSTRAINT ck_detection_signal_results_text
        CHECK (
            btrim(external_signal_id) <> ''
            AND btrim(student_ref) <> ''
            AND btrim(class_ref) <> ''
            AND btrim(rule_id) <> ''
            AND btrim(signal_type) <> ''
            AND btrim(display_label) <> ''
            AND btrim(brief_text) <> ''
        )
);

CREATE INDEX idx_detection_signal_results_run_rank
    ON detection_signal_results (detection_run_id, class_ref, rank);

CREATE INDEX idx_detection_signal_results_student
    ON detection_signal_results (detection_run_id, student_ref);

CREATE TABLE detection_result_evidence (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    detection_signal_result_id UUID NOT NULL,
    source_hint VARCHAR(80) NOT NULL,
    record_id VARCHAR(120) NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_detection_result_evidence_signal
        FOREIGN KEY (detection_signal_result_id)
        REFERENCES detection_signal_results (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_detection_result_evidence_record
        UNIQUE (detection_signal_result_id, record_id),
    CONSTRAINT ck_detection_result_evidence_text
        CHECK (
            btrim(source_hint) <> ''
            AND btrim(record_id) <> ''
            AND btrim(summary) <> ''
        )
);

CREATE INDEX idx_detection_result_evidence_signal
    ON detection_result_evidence (detection_signal_result_id, created_at);

COMMENT ON COLUMN detection_result_evidence.source_hint IS
    'Untrusted AI source hint for diagnostics only; never use it to choose a table.';
COMMENT ON TABLE detection_signal_results IS
    'Append-only AI signal history. Alert lifecycle projection is stored separately.';
