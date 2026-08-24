ALTER TABLE detection_runs
    ADD COLUMN ai_versions_payload TEXT,
    ADD COLUMN response_stats_payload TEXT;

ALTER TABLE detection_runs
    ADD CONSTRAINT ck_detection_runs_ai_versions_payload
        CHECK (
            ai_versions_payload IS NULL
            OR btrim(ai_versions_payload) <> ''
        ),
    ADD CONSTRAINT ck_detection_runs_response_stats_payload
        CHECK (
            response_stats_payload IS NULL
            OR btrim(response_stats_payload) <> ''
        );

COMMENT ON COLUMN detection_runs.ai_versions_payload IS
    'Exact AI response meta.versions JSON used for reproducibility.';
COMMENT ON COLUMN detection_runs.response_stats_payload IS
    'Exact AI response data.stats JSON used for operation diagnostics.';
