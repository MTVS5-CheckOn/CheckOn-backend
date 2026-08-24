-- M2 problem generation: backend-owned request state, opaque AI aliases,
-- Transactional Outbox, and idempotent result consumption.
-- V14 already owns the shared ai_tenant_aliases routing table. M2 reuses that
-- opaque alias and adds only its class aliases and request/result boundary.

CREATE TABLE ai_class_aliases (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    class_group_id UUID NOT NULL,
    alias VARCHAR(35) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_ai_class_aliases_class_teacher
        FOREIGN KEY (class_group_id, teacher_id)
        REFERENCES class_groups (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_ai_class_aliases_teacher_class UNIQUE (teacher_id, class_group_id),
    CONSTRAINT uq_ai_class_aliases_alias UNIQUE (alias),
    CONSTRAINT ck_ai_class_aliases_format CHECK (alias ~ '^cl_[0-9a-f]{32}$')
);

CREATE TABLE problem_generation_requests (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    tenant_alias VARCHAR(35) NOT NULL,
    target_kind VARCHAR(10) NOT NULL,
    student_id UUID,
    class_group_id UUID,
    target_ref VARCHAR(35) NOT NULL,
    client_idempotency_key VARCHAR(200),
    ai_idempotency_key VARCHAR(40) NOT NULL,
    snapshot_hash VARCHAR(71) NOT NULL,
    request_payload JSONB NOT NULL,
    status VARCHAR(30) NOT NULL,
    ai_job_id VARCHAR(120),
    ai_execution_id VARCHAR(120),
    ai_set_id VARCHAR(120),
    ai_result_status VARCHAR(40),
    error_code VARCHAR(80),
    result_payload JSONB,
    versions_payload JSONB,
    requested_at TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_requests_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_problem_generation_requests_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_problem_generation_requests_class_teacher
        FOREIGN KEY (class_group_id, teacher_id)
        REFERENCES class_groups (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_requests_id_teacher UNIQUE (id, teacher_id),
    CONSTRAINT uq_problem_generation_requests_ai_idempotency UNIQUE (ai_idempotency_key),
    CONSTRAINT ck_problem_generation_requests_target CHECK (
        (target_kind = 'STUDENT' AND student_id IS NOT NULL AND class_group_id IS NULL
            AND target_ref ~ '^st_[0-9a-f]{32}$')
        OR
        (target_kind = 'CLASS' AND student_id IS NULL AND class_group_id IS NOT NULL
            AND target_ref ~ '^cl_[0-9a-f]{32}$')
    ),
    CONSTRAINT ck_problem_generation_requests_tenant_alias
        CHECK (tenant_alias ~ '^tn_[0-9a-f]{32}$'),
    CONSTRAINT ck_problem_generation_requests_ai_idempotency
        CHECK (ai_idempotency_key ~ '^pg_[0-9a-f]{32}$'),
    CONSTRAINT ck_problem_generation_requests_snapshot_hash
        CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_problem_generation_requests_status CHECK (status IN (
        'QUEUED', 'DISPATCHED', 'RUNNING', 'SUCCEEDED', 'FAILED',
        'CANCELLED', 'DELIVERY_FAILED'
    )),
    CONSTRAINT ck_problem_generation_requests_time CHECK (
        updated_at >= requested_at
        AND (dispatched_at IS NULL OR dispatched_at >= requested_at)
        AND (completed_at IS NULL OR completed_at >= requested_at)
    )
);

CREATE UNIQUE INDEX uq_problem_generation_requests_client_idempotency
    ON problem_generation_requests (teacher_id, client_idempotency_key)
    WHERE client_idempotency_key IS NOT NULL;
CREATE INDEX idx_problem_generation_requests_teacher_requested
    ON problem_generation_requests (teacher_id, requested_at DESC, id DESC);

CREATE TABLE problem_generation_outbox (
    id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    schema_version VARCHAR(40) NOT NULL,
    event_key VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_outbox_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT uq_problem_generation_outbox_request_type
        UNIQUE (problem_request_id, event_type),
    CONSTRAINT ck_problem_generation_outbox_status
        CHECK (status IN ('PENDING', 'PUBLISHING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT ck_problem_generation_outbox_attempt CHECK (attempt_count >= 0),
    CONSTRAINT ck_problem_generation_outbox_event_key
        CHECK (event_key ~ '^tn_[0-9a-f]{32}$')
);

CREATE INDEX idx_problem_generation_outbox_ready
    ON problem_generation_outbox (teacher_id, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'PUBLISHING');

CREATE TABLE problem_generation_consumed_events (
    event_id UUID PRIMARY KEY,
    teacher_id UUID NOT NULL,
    problem_request_id UUID NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload_hash VARCHAR(71) NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_problem_generation_consumed_request_teacher
        FOREIGN KEY (problem_request_id, teacher_id)
        REFERENCES problem_generation_requests (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT ck_problem_generation_consumed_hash
        CHECK (payload_hash ~ '^sha256:[0-9a-f]{64}$')
);

CREATE FUNCTION check_problem_generation_target_ownership()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.target_kind = 'STUDENT' AND NOT EXISTS (
        SELECT 1 FROM teacher_student_relationships relationship
        WHERE relationship.teacher_id = NEW.teacher_id
          AND relationship.student_id = NEW.student_id
          AND relationship.status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'student problem target requires an active teacher relationship'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_problem_generation_target_ownership
    BEFORE INSERT OR UPDATE OF teacher_id, target_kind, student_id
    ON problem_generation_requests
    FOR EACH ROW EXECUTE FUNCTION check_problem_generation_target_ownership();

ALTER TABLE ai_class_aliases ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_class_aliases FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_outbox FORCE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_consumed_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE problem_generation_consumed_events FORCE ROW LEVEL SECURITY;

CREATE POLICY ai_class_aliases_teacher_select ON ai_class_aliases FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_class_aliases_teacher_insert ON ai_class_aliases FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_class_aliases_teacher_update ON ai_class_aliases FOR UPDATE USING (teacher_id = current_checkon_teacher_id()) WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY ai_class_aliases_teacher_delete ON ai_class_aliases FOR DELETE USING (false);

CREATE POLICY problem_generation_requests_teacher_select ON problem_generation_requests FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_requests_teacher_insert ON problem_generation_requests FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_requests_teacher_update ON problem_generation_requests FOR UPDATE USING (teacher_id = current_checkon_teacher_id()) WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_requests_teacher_delete ON problem_generation_requests FOR DELETE USING (false);

CREATE POLICY problem_generation_outbox_teacher_select ON problem_generation_outbox FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_outbox_teacher_insert ON problem_generation_outbox FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_outbox_teacher_update ON problem_generation_outbox FOR UPDATE USING (teacher_id = current_checkon_teacher_id()) WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_outbox_teacher_delete ON problem_generation_outbox FOR DELETE USING (false);

CREATE POLICY problem_generation_consumed_events_teacher_select ON problem_generation_consumed_events FOR SELECT USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_consumed_events_teacher_insert ON problem_generation_consumed_events FOR INSERT WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY problem_generation_consumed_events_teacher_update ON problem_generation_consumed_events FOR UPDATE USING (false) WITH CHECK (false);
CREATE POLICY problem_generation_consumed_events_teacher_delete ON problem_generation_consumed_events FOR DELETE USING (false);

CREATE FUNCTION resolve_problem_generation_teacher(
    requested_problem_id UUID,
    requested_tenant_alias VARCHAR
)
RETURNS UUID
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT request.teacher_id
    FROM public.problem_generation_requests request
    WHERE request.id = requested_problem_id
      AND request.tenant_alias = requested_tenant_alias
$$;

COMMENT ON FUNCTION resolve_problem_generation_teacher(UUID, VARCHAR) IS
    'Internal Kafka routing lookup; tenant data remains protected by transaction-local RLS.';
