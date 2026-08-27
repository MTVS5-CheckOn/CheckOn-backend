-- V44 — member 상담 원장(1) · 발행 메시지(1) 총 2테이블.
--
-- 이 파일이 만드는 것:
--   1. member_consultations: 학부모 원문·마스킹본·상담 상태·AI 보조 상태 원장
--   2. member_consultation_messages: 강사가 승인해 발행한 메시지만 저장하는 원장
--   3. 부모-자식 owner 값이 갈리지 않도록 parent/student/teacher 복합 FK 비정규화
--   4. 두 테이블 ENABLE + FORCE RLS, 신규 정책 7개
--
-- 기존(승우님) 테이블 정책 변경 0건. 기존 테이블 ALTER 0건.
-- 정책 술어는 다른 RLS 테이블을 EXISTS·IN·JOIN 으로 참조하지 않는다.
-- AI raw 초안은 저장하지 않는다. messages.published_at NOT NULL 이 미승인 노출을 막는다.
-- TODO(MB-09): 취소 가능 시점 확정 전까지 학부모 UPDATE 정책을 열지 않는다.

CREATE TABLE member_consultations (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    parent_id UUID NOT NULL REFERENCES parent_profiles (id) ON DELETE RESTRICT,
    student_id UUID NOT NULL REFERENCES student_profiles (id) ON DELETE RESTRICT,
    teacher_id UUID NOT NULL REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    content TEXT NOT NULL,
    masked_content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    ai_status VARCHAR(24) NOT NULL,
    topic VARCHAR(30),
    urgency VARCHAR(20),
    context_type VARCHAR(16),
    context_id UUID,
    ai_job_id VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    CONSTRAINT ck_member_consultations_content_length
        CHECK (char_length(content) BETWEEN 1 AND 2000),
    CONSTRAINT ck_member_consultations_status
        CHECK (status IN ('SUBMITTED', 'REVIEWING', 'ANSWERED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_member_consultations_ai_status
        CHECK (ai_status IN (
            'NOT_REQUESTED', 'PENDING', 'READY', 'TEMPLATE_ONLY',
            'REJECTED_INSUFFICIENT', 'UNAVAILABLE'
        )),
    CONSTRAINT ck_member_consultations_topic
        CHECK (topic IS NULL OR topic IN ('grade', 'schedule', 'counsel_request', 'etc')),
    CONSTRAINT ck_member_consultations_urgency
        CHECK (urgency IS NULL OR urgency IN ('immediate', 'normal')),
    CONSTRAINT ck_member_consultations_context_pair
        CHECK ((context_type IS NULL) = (context_id IS NULL)),
    CONSTRAINT ck_member_consultations_context_type
        CHECK (context_type IS NULL OR context_type IN ('RECORD', 'ANALYSIS', 'REPORT')),
    CONSTRAINT ck_member_consultations_updated_at
        CHECK (updated_at >= created_at),
    CONSTRAINT uq_member_consultations_parent UNIQUE (id, parent_id),
    CONSTRAINT uq_member_consultations_student UNIQUE (id, student_id),
    CONSTRAINT uq_member_consultations_teacher UNIQUE (id, teacher_id)
);

CREATE INDEX ix_member_consultations_parent_student_created
    ON member_consultations (parent_id, student_id, created_at DESC, id DESC);

CREATE INDEX ix_member_consultations_teacher_status
    ON member_consultations (teacher_id, status, created_at);

CREATE TABLE member_consultation_messages (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    consultation_id UUID NOT NULL,
    parent_id UUID NOT NULL,
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    author_role VARCHAR(10) NOT NULL,
    content TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_consultation_messages_parent
        FOREIGN KEY (consultation_id, parent_id)
        REFERENCES member_consultations (id, parent_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_consultation_messages_student
        FOREIGN KEY (consultation_id, student_id)
        REFERENCES member_consultations (id, student_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_consultation_messages_teacher
        FOREIGN KEY (consultation_id, teacher_id)
        REFERENCES member_consultations (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_consultation_messages_role
        CHECK (author_role IN ('PARENT', 'TEACHER')),
    CONSTRAINT ck_member_consultation_messages_content_length
        CHECK (char_length(content) BETWEEN 1 AND 2000),
    CONSTRAINT ck_member_consultation_messages_created_at
        CHECK (created_at <= published_at)
);

CREATE INDEX ix_member_consultation_messages_thread
    ON member_consultation_messages (consultation_id, published_at, id);

ALTER TABLE member_consultations ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_consultations FORCE ROW LEVEL SECURITY;
ALTER TABLE member_consultation_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_consultation_messages FORCE ROW LEVEL SECURITY;

CREATE POLICY member_consultations_member_parent_select
    ON member_consultations
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
    );

CREATE POLICY member_consultations_member_parent_insert
    ON member_consultations
    FOR INSERT WITH CHECK (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
        AND status = 'SUBMITTED'
    );

CREATE POLICY member_consultations_member_teacher_select
    ON member_consultations
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_consultations_member_teacher_update
    ON member_consultations
    FOR UPDATE USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    ) WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_consultation_messages_member_parent_select
    ON member_consultation_messages
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
    );

CREATE POLICY member_consultation_messages_member_teacher_select
    ON member_consultation_messages
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_consultation_messages_member_teacher_insert
    ON member_consultation_messages
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND author_role = 'TEACHER'
    );
