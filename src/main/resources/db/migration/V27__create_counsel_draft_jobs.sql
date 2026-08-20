CREATE TABLE counsel_draft_jobs (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    tenant_alias VARCHAR(40) NOT NULL,
    inquiry_ref VARCHAR(120) NOT NULL,
    student_ref VARCHAR(40) NOT NULL,
    parent_ref VARCHAR(40) NOT NULL,
    class_ref VARCHAR(40) NOT NULL,
    topic VARCHAR(30) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    job_id VARCHAR(80) NOT NULL,
    job_phase VARCHAR(20) NOT NULL,
    ai_execution_id VARCHAR(80),
    requested_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_counsel_draft_job_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_counsel_draft_job_idempotency_key
        UNIQUE (teacher_id, idempotency_key),
    CONSTRAINT uq_counsel_draft_job_job_id
        UNIQUE (teacher_id, job_id),
    CONSTRAINT ck_counsel_draft_job_topic
        CHECK (topic IN ('grade', 'schedule', 'counsel_request', 'etc')),
    CONSTRAINT ck_counsel_draft_job_phase
        CHECK (job_phase IN ('queued', 'leased', 'running', 'paused', 'succeeded', 'failed', 'cancelled')),
    CONSTRAINT ck_counsel_draft_job_time CHECK (updated_at >= requested_at)
);

CREATE INDEX idx_counsel_draft_job_inquiry
    ON counsel_draft_jobs (teacher_id, inquiry_ref, requested_at DESC);

ALTER TABLE counsel_draft_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE counsel_draft_jobs FORCE ROW LEVEL SECURITY;

CREATE POLICY counsel_draft_jobs_select ON counsel_draft_jobs
    FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_draft_jobs_insert ON counsel_draft_jobs
    FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_draft_jobs_update ON counsel_draft_jobs
    FOR UPDATE USING (teacher_id = current_checkon_teacher_id())
    WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY counsel_draft_jobs_delete ON counsel_draft_jobs
    FOR DELETE USING (false);

COMMENT ON TABLE counsel_draft_jobs IS
    'Local bookkeeping of AI counsel draft jobs by (teacher, Idempotency-Key); the AI PG read model remains the source of truth for draft bodies.';
