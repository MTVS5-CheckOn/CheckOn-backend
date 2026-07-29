CREATE TABLE detection_runs (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    analysis_date DATE NOT NULL,
    week_start DATE NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    snapshot_hash VARCHAR(71) NOT NULL,
    snapshot_payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PREPARED',
    prepared_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    requested_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    ai_execution_id VARCHAR(120),
    error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_detection_runs_teacher_analysis_date
        UNIQUE (teacher_id, analysis_date),
    CONSTRAINT uq_detection_runs_idempotency_key
        UNIQUE (idempotency_key),
    CONSTRAINT ck_detection_runs_week_start_monday
        CHECK (EXTRACT(ISODOW FROM week_start) = 1),
    CONSTRAINT ck_detection_runs_snapshot_hash
        CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_detection_runs_snapshot_payload
        CHECK (btrim(snapshot_payload) <> ''),
    CONSTRAINT ck_detection_runs_status
        CHECK (status IN ('PREPARED', 'REQUESTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_detection_runs_request_time
        CHECK (requested_at IS NULL OR requested_at >= prepared_at),
    CONSTRAINT ck_detection_runs_completion_time
        CHECK (
            completed_at IS NULL
            OR (requested_at IS NOT NULL AND completed_at >= requested_at)
        ),
    CONSTRAINT ck_detection_runs_status_columns
        CHECK (
            (
                status = 'PREPARED'
                AND requested_at IS NULL
                AND completed_at IS NULL
                AND ai_execution_id IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'REQUESTED'
                AND requested_at IS NOT NULL
                AND completed_at IS NULL
                AND ai_execution_id IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'SUCCEEDED'
                AND requested_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND ai_execution_id IS NOT NULL
                AND error_code IS NULL
            )
            OR (
                status = 'FAILED'
                AND requested_at IS NOT NULL
                AND completed_at IS NOT NULL
                AND ai_execution_id IS NULL
                AND error_code IS NOT NULL
            )
        )
);

CREATE INDEX idx_detection_runs_teacher_status_date
    ON detection_runs (teacher_id, status, analysis_date DESC);

CREATE TABLE detection_request_attempts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    detection_run_id UUID NOT NULL,
    request_id VARCHAR(120) NOT NULL,
    attempt_number INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    requested_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    http_status INTEGER,
    error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT fk_detection_request_attempts_run
        FOREIGN KEY (detection_run_id)
        REFERENCES detection_runs (id)
        ON DELETE RESTRICT,
    CONSTRAINT uq_detection_request_attempts_request_id
        UNIQUE (request_id),
    CONSTRAINT uq_detection_request_attempts_run_number
        UNIQUE (detection_run_id, attempt_number),
    CONSTRAINT ck_detection_request_attempts_number
        CHECK (attempt_number >= 1),
    CONSTRAINT ck_detection_request_attempts_status
        CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_detection_request_attempts_completion_time
        CHECK (completed_at IS NULL OR completed_at >= requested_at),
    CONSTRAINT ck_detection_request_attempts_status_columns
        CHECK (
            (
                status = 'REQUESTED'
                AND completed_at IS NULL
                AND http_status IS NULL
                AND error_code IS NULL
            )
            OR (
                status = 'SUCCEEDED'
                AND completed_at IS NOT NULL
                AND http_status BETWEEN 200 AND 299
                AND error_code IS NULL
            )
            OR (
                status = 'FAILED'
                AND completed_at IS NOT NULL
                AND (http_status IS NULL OR http_status BETWEEN 400 AND 599)
                AND error_code IS NOT NULL
            )
        )
);

CREATE INDEX idx_detection_request_attempts_run_status
    ON detection_request_attempts (detection_run_id, status, attempt_number);

COMMENT ON COLUMN detection_runs.teacher_id IS
    'Tenant boundary. Add FK after the roster teacher table is introduced.';
COMMENT ON COLUMN detection_runs.snapshot_payload IS
    'Exact pseudonymized JSON request body reused for idempotent retries.';
