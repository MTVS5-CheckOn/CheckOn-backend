ALTER TABLE detection_signal_results
    ADD COLUMN metric varchar(80),
    ADD COLUMN observed numeric,
    ADD COLUMN baseline numeric,
    ADD COLUMN sample_size integer,
    ADD CONSTRAINT ck_detection_signal_results_metric
        CHECK (metric IS NULL OR btrim(metric) <> ''),
    ADD CONSTRAINT ck_detection_signal_results_sample_size
        CHECK (sample_size IS NULL OR sample_size >= 0);

ALTER TABLE detection_result_evidence
    ADD COLUMN role varchar(20),
    ADD COLUMN observed numeric,
    ADD COLUMN sample_size integer,
    ADD COLUMN occurred_on date,
    ADD CONSTRAINT ck_detection_result_evidence_role
        CHECK (role IS NULL OR role IN ('trigger', 'baseline')),
    ADD CONSTRAINT ck_detection_result_evidence_sample_size
        CHECK (sample_size IS NULL OR sample_size >= 0);

COMMENT ON COLUMN detection_signal_results.metric IS
    'AI rule metric identifier. Nullable for backend-first rollout and R5 event signals.';
COMMENT ON COLUMN detection_signal_results.observed IS
    'Structured observed value supplied by the AI response.';
COMMENT ON COLUMN detection_signal_results.baseline IS
    'Structured baseline value. Only comparison-to-usual rules use it.';
COMMENT ON COLUMN detection_signal_results.sample_size IS
    'Optional denominator for the structured signal value.';
COMMENT ON COLUMN detection_result_evidence.role IS
    'Evidence role from the AI contract: trigger or baseline.';
COMMENT ON COLUMN detection_result_evidence.observed IS
    'Observed value belonging to this evidence record.';
COMMENT ON COLUMN detection_result_evidence.sample_size IS
    'Optional denominator belonging to this evidence record.';
COMMENT ON COLUMN detection_result_evidence.occurred_on IS
    'Business date of the referenced evidence record.';
