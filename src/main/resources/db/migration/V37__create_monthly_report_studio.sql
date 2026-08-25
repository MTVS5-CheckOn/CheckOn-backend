CREATE TABLE monthly_reports (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL REFERENCES teacher_profiles(id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES student_profiles(id) ON DELETE RESTRICT,
    class_group_id UUID,
    guardian_ref VARCHAR(40) NOT NULL,
    report_month DATE NOT NULL,
    report_kind VARCHAR(20) NOT NULL DEFAULT 'MONTHLY',
    client_idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(71) NOT NULL,
    source_payload JSONB NOT NULL,
    request_status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
    ai_status VARCHAR(30),
    ai_payload JSONB,
    block_count INTEGER,
    error_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    CONSTRAINT fk_monthly_reports_class_teacher FOREIGN KEY (class_group_id, teacher_id)
        REFERENCES class_groups(id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_monthly_reports_client_key UNIQUE (teacher_id, client_idempotency_key),
    CONSTRAINT uq_monthly_reports_id_teacher UNIQUE (id, teacher_id),
    CONSTRAINT ck_monthly_reports_month CHECK (report_month = date_trunc('month', report_month)::date),
    CONSTRAINT ck_monthly_reports_kind CHECK (report_kind IN ('MONTHLY', 'ON_DEMAND')),
    CONSTRAINT ck_monthly_reports_request_status CHECK (request_status IN ('REQUESTED','SUCCEEDED','FAILED')),
    CONSTRAINT ck_monthly_reports_ai_status CHECK (ai_status IS NULL OR ai_status IN ('ready','rejected_insufficient','template_only')),
    CONSTRAINT ck_monthly_reports_hash CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_monthly_reports_guardian_ref CHECK (guardian_ref ~ '^pa_[0-9a-f]{32}$'),
    CONSTRAINT ck_monthly_reports_block_count CHECK (block_count IS NULL OR block_count >= 0),
    CONSTRAINT ck_monthly_reports_completion CHECK (
        (request_status = 'REQUESTED' AND completed_at IS NULL AND ai_status IS NULL AND error_code IS NULL)
        OR (request_status = 'SUCCEEDED' AND completed_at IS NOT NULL AND ai_status IS NOT NULL AND error_code IS NULL AND ai_payload IS NOT NULL)
        OR (request_status = 'FAILED' AND completed_at IS NOT NULL AND ai_status IS NULL AND error_code IS NOT NULL)
    )
);

CREATE UNIQUE INDEX uq_monthly_reports_canonical_month
    ON monthly_reports(teacher_id, student_id, report_month)
    WHERE report_kind = 'MONTHLY';
CREATE INDEX idx_monthly_reports_studio
    ON monthly_reports(teacher_id, report_month DESC, updated_at DESC, id DESC);

CREATE TABLE monthly_report_outbox (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    report_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    tenant_alias VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    publish_attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_monthly_report_outbox_report FOREIGN KEY (report_id, teacher_id)
        REFERENCES monthly_reports(id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_monthly_report_outbox_request UNIQUE (report_id, event_type),
    CONSTRAINT ck_monthly_report_outbox_status CHECK (status IN ('PENDING','PUBLISHING','PUBLISHED','FAILED')),
    CONSTRAINT ck_monthly_report_outbox_attempts CHECK (publish_attempts >= 0)
);
CREATE INDEX idx_monthly_report_outbox_due ON monthly_report_outbox(status, next_attempt_at, created_at);

CREATE TABLE monthly_report_result_inbox (
    event_id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    report_id UUID NOT NULL,
    event_payload JSONB NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_monthly_report_result_report FOREIGN KEY (report_id, teacher_id)
        REFERENCES monthly_reports(id, teacher_id) ON DELETE RESTRICT
);

CREATE TABLE monthly_report_artifacts (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    report_id UUID NOT NULL,
    revision_no INTEGER NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    sha256 VARCHAR(71) NOT NULL,
    page_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_monthly_report_artifact_report FOREIGN KEY (report_id, teacher_id)
        REFERENCES monthly_reports(id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_monthly_report_artifact_revision UNIQUE (report_id, revision_no),
    CONSTRAINT ck_monthly_report_artifact_revision CHECK (revision_no >= 0),
    CONSTRAINT ck_monthly_report_artifact_hash CHECK (sha256 ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_monthly_report_artifact_pages CHECK (page_count BETWEEN 1 AND 100),
    CONSTRAINT ck_monthly_report_artifact_status CHECK (status IN ('READY','SUPERSEDED'))
);

CREATE TABLE monthly_report_deliveries (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    report_id UUID NOT NULL,
    artifact_id UUID NOT NULL,
    parent_id UUID NOT NULL REFERENCES parent_profiles(id) ON DELETE RESTRICT,
    client_idempotency_key VARCHAR(200) NOT NULL,
    request_hash VARCHAR(71) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    channel VARCHAR(20) NOT NULL DEFAULT 'PARENT_APP',
    failure_code VARCHAR(80),
    queued_at TIMESTAMPTZ NOT NULL,
    delivered_at TIMESTAMPTZ,
    CONSTRAINT fk_monthly_report_delivery_report FOREIGN KEY (report_id, teacher_id)
        REFERENCES monthly_reports(id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT fk_monthly_report_delivery_artifact FOREIGN KEY (artifact_id)
        REFERENCES monthly_report_artifacts(id) ON DELETE RESTRICT,
    CONSTRAINT uq_monthly_report_delivery_key UNIQUE (teacher_id, client_idempotency_key, report_id),
    CONSTRAINT ck_monthly_report_delivery_status CHECK (status IN ('QUEUED','DELIVERED','FAILED')),
    CONSTRAINT ck_monthly_report_delivery_channel CHECK (channel = 'PARENT_APP'),
    CONSTRAINT ck_monthly_report_delivery_hash CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_monthly_report_delivery_completion CHECK (
        (status = 'QUEUED' AND delivered_at IS NULL AND failure_code IS NULL)
        OR (status = 'DELIVERED' AND delivered_at IS NOT NULL AND failure_code IS NULL)
        OR (status = 'FAILED' AND delivered_at IS NULL AND failure_code IS NOT NULL)
    )
);

DO $$ DECLARE table_name text; BEGIN
  FOREACH table_name IN ARRAY ARRAY['monthly_reports','monthly_report_outbox','monthly_report_result_inbox','monthly_report_artifacts','monthly_report_deliveries'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
    EXECUTE format('CREATE POLICY %I ON %I USING (teacher_id = current_checkon_teacher_id()) WITH CHECK (teacher_id = current_checkon_teacher_id())', table_name || '_tenant', table_name);
  END LOOP;
END $$;
