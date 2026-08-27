-- V41 — 질문(2) · 알림 설정(1) · 알림(1) 총 4테이블.
--
-- 🔴 이름만 보고 「question 뿐」이라고 읽으면 다음 사람이 못 찾는다. V39·V40 이 겪은 그대로다.
--    이 파일이 만드는 것:
--      1. member_notification_preferences  (§1)
--      2. member_questions                  (§2)
--      3. member_question_messages          (§3)
--      4. member_notifications              (§4)
--      5. 네 테이블 전부 ENABLE + FORCE RLS + 정책         (§5)
--
-- 🔴 member_display_names 는 V39 가 이미 만들었다. 이 마이그레이션은 만들지 않고 읽기만 한다
--    (PR6 지시서 §1 표).
--
-- 🔴 기존(승우님) 테이블 정책 변경 0건. 기존 테이블 ALTER 0건.
--    예약표 규칙 1 — 기존 테이블 정책 추가는 V38 뿐이다.
--
-- 🔴 모든 술어의 첫 조건은 current_checkon_*_id() IS NOT NULL. 이름에 _member_ 포함.
--    회귀 검증(R3)이 policyname NOT LIKE '%\_member\_%' 로 기존 정책만 뽑아 술어를 문자열
--    비교한다. 규칙을 어기면 새 정책이 그 결과에 섞여 "기존 정책이 바뀌었다"로 오진된다.
--
-- 🔴 R6 금지 SQL 을 쓰지 않는다. DROP · ALTER POLICY · DISABLE ROW LEVEL · SECURITY DEFINER 금지.

-- ═════════════════════ 1. member_notification_preferences ═════════════════════
-- 🔴 행 부재를 false 로 읽지 마라. 부재 = 기본값이고, 기본값은 Settings
--    (member.notification.default-enabled)에 있다. 코드·SQL 에 리터럴 금지.
CREATE TABLE member_notification_preferences (
    account_id UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE RESTRICT,
    notifications_enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_member_notification_preferences_updated_at
        CHECK (updated_at >= created_at)
);

-- ═════════════════════ 2. member_questions ═════════════════════
-- 🔴 상태 전이의 최종 보장은 DB CHECK 가 한다. 애플리케이션 세터 순서에 의존하지 않는다.
--    WAITING → ANSWERED(강사 답변 · 이 PR 에서 만들지 않음 · MEMBER_TEACHER_CONTRACT.md)
--    ANSWERED → FOLLOW_UP(학생 추가 질문)
--    FOLLOW_UP → ANSWERED(강사 재답변)
CREATE TABLE member_questions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    attempt_id UUID,
    item_id UUID,
    title VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    follow_up_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    answered_at TIMESTAMPTZ,
    CONSTRAINT fk_member_questions_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_questions_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_questions_assignment
        FOREIGN KEY (assignment_id) REFERENCES problem_assignments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_questions_attempt
        FOREIGN KEY (attempt_id) REFERENCES member_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_questions_status
        CHECK (status IN ('WAITING', 'ANSWERED', 'FOLLOW_UP')),
    CONSTRAINT ck_member_questions_answered_at
        CHECK (status = 'WAITING' OR answered_at IS NOT NULL),
    -- 🔴 itemId 는 attemptId 없이 존재할 수 없다. 문항만 지목하려면 attempt 소유가 먼저다.
    --    없으면 애플리케이션이 saved_problem_set_items 를 다시 봐야 하는데, 그건
    --    "복사 이후 조회는 member_* 만 본다"(PR5) 를 깬다.
    CONSTRAINT ck_member_questions_item_requires_attempt
        CHECK (item_id IS NULL OR attempt_id IS NOT NULL),
    CONSTRAINT ck_member_questions_follow_up_nonneg
        CHECK (follow_up_count >= 0),
    CONSTRAINT ck_member_questions_title_length
        CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT ck_member_questions_content_length
        CHECK (char_length(content) BETWEEN 1 AND 2000)
);

CREATE INDEX ix_member_questions_student_created
    ON member_questions (student_id, created_at DESC, id DESC);
CREATE INDEX ix_member_questions_teacher_status
    ON member_questions (teacher_id, status);

-- ═════════════════════ 3. member_question_messages ═════════════════════
-- 🔴 author_role 은 애플리케이션이 채우지만 최종 보장은 정책(§5)이 한다:
--    학생 INSERT WITH CHECK 에 author_role = 'STUDENT'
--    강사 INSERT WITH CHECK 에 author_role = 'TEACHER'
--    → 학생이 자기 컨텍스트로 'TEACHER' 를 INSERT 하려 하면 정책이 거절한다.
CREATE TABLE member_question_messages (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    question_id UUID NOT NULL,
    author_role VARCHAR(8) NOT NULL,
    author_account_id UUID NOT NULL,
    content TEXT NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_question_messages_question
        FOREIGN KEY (question_id) REFERENCES member_questions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_question_messages_author
        FOREIGN KEY (author_account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_question_messages_role
        CHECK (author_role IN ('STUDENT', 'TEACHER')),
    CONSTRAINT ck_member_question_messages_content_length
        CHECK (char_length(content) BETWEEN 1 AND 2000)
);

CREATE INDEX ix_member_question_messages_thread
    ON member_question_messages (question_id, published_at, id);

-- ═════════════════════ 4. member_notifications ═════════════════════
-- 🔴 type 은 계약(member-api.yaml:2364)의 enum 5개를 그대로 반영한다.
--    QUESTION_ANSWERED 는 이 PR 이 발행하지 않지만 (강사 답변 API 없음 · 학생 알림 목록 없음)
--    계약에 있으므로 CHECK 에 포함한다. yaml 을 계약 정본으로 삼는다.
--    🔴 PR6 지시서 §1 은 QUESTION_ANSWERED 를 제외한 4개만 쓰라고 했지만, 실제 yaml 은
--       5개 값을 갖는다(2026-08-27 재확인). 지시가 계약과 어긋나 계약을 따랐다 — REPORT §9.
--
-- 🔴 uq_member_notifications_source (source_type, source_id, recipient_account_id) —
--    같은 원본으로 같은 수신자에게 두 번 발행되지 않는다. NotificationPort 가
--    ON CONFLICT DO NOTHING 으로 중복을 조용히 무시한다(계약 §12 "알림 — 중복 무시").
CREATE TABLE member_notifications (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    recipient_account_id UUID NOT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(200) NOT NULL,
    body TEXT,
    target_student_id UUID,
    target_resource_id UUID,
    source_type VARCHAR(48) NOT NULL,
    source_id UUID NOT NULL,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_notifications_recipient
        FOREIGN KEY (recipient_account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_notifications_type
        CHECK (type IN (
            'REPORT_PUBLISHED',
            'CONSULTATION_ANSWERED',
            'QUESTION_ANSWERED',
            'LEARNING_SUBMITTED',
            'CHILD_LINKED'
        )),
    CONSTRAINT ck_member_notifications_title_length
        CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT uq_member_notifications_source
        UNIQUE (source_type, source_id, recipient_account_id)
);

CREATE INDEX ix_member_notifications_recipient_created
    ON member_notifications (recipient_account_id, created_at DESC, id DESC);

-- ═════════════════════ 5. RLS ═════════════════════
ALTER TABLE member_notification_preferences ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_notification_preferences FORCE ROW LEVEL SECURITY;
ALTER TABLE member_questions ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_questions FORCE ROW LEVEL SECURITY;
ALTER TABLE member_question_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_question_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE member_notifications ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_notifications FORCE ROW LEVEL SECURITY;

-- ── 5-1. member_notification_preferences ── (본인 self 전용)
CREATE POLICY member_notification_preferences_self_select
    ON member_notification_preferences
    FOR SELECT USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );
CREATE POLICY member_notification_preferences_self_insert
    ON member_notification_preferences
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );
CREATE POLICY member_notification_preferences_self_update
    ON member_notification_preferences
    FOR UPDATE USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    ) WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

-- ── 5-2. member_questions ──
CREATE POLICY member_questions_member_student_select
    ON member_questions
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_questions_member_student_insert
    ON member_questions
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
-- 🔴 학생 UPDATE — 후속 질문(FOLLOW_UP) 전이에 필요하다. 학생이 자기 질문의
--    follow_up_count 를 +1 하고 status 를 FOLLOW_UP 으로 바꾸는 경로다.
--    student_id 매칭이 남의 질문 위조를 막고, 애플리케이션이 status·follow_up_count 외의
--    컬럼(teacher_id·content 등)을 SET 하지 않는다 — RLS 는 컬럼 범위를 제한하지 못한다.
CREATE POLICY member_questions_member_student_update
    ON member_questions
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
-- 🔴 강사 정책은 이 PR 에서 만든다. 강사 답변 API 는 만들지 않지만, 계약 정본
--    (MEMBER_TEACHER_CONTRACT.md)이 이 정책 이름을 인용한다. 후속 PR 이 정책을 다시 뽑아
--    쓰지 않게 지금 넣어 둔다.
CREATE POLICY member_questions_member_teacher_select
    ON member_questions
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );
CREATE POLICY member_questions_member_teacher_update
    ON member_questions
    FOR UPDATE USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    ) WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 5-3. member_question_messages ──
-- 🔴 EXISTS 는 member_questions 를 참조한다 — <b>member_ 소유 테이블끼리</b>다.
--    불변식 4번(승우님 RLS 테이블 재귀 방지)은 위반하지 않는다.
--    같은 주체(student_id / teacher_id)로 격리되므로 정책 그래프가 발산하지 않는다.
CREATE POLICY member_question_messages_member_student_select
    ON member_question_messages
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_questions q
            WHERE q.id = member_question_messages.question_id
              AND q.student_id = current_checkon_student_id()
        )
    );
-- 🔴 author_role = 'STUDENT' 를 정책에 넣는다. 없으면 학생이 자기 질문에
--    TEACHER 메시지를 INSERT 해 답변을 위조한다. 애플리케이션 검증이 아니라
--    정책이 최종 보장이다.
CREATE POLICY member_question_messages_member_student_insert
    ON member_question_messages
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND author_role = 'STUDENT'
        AND EXISTS (
            SELECT 1 FROM member_questions q
            WHERE q.id = member_question_messages.question_id
              AND q.student_id = current_checkon_student_id()
        )
    );
CREATE POLICY member_question_messages_member_teacher_select
    ON member_question_messages
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_questions q
            WHERE q.id = member_question_messages.question_id
              AND q.teacher_id = current_checkon_teacher_id()
        )
    );
CREATE POLICY member_question_messages_member_teacher_insert
    ON member_question_messages
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND author_role = 'TEACHER'
        AND EXISTS (
            SELECT 1 FROM member_questions q
            WHERE q.id = member_question_messages.question_id
              AND q.teacher_id = current_checkon_teacher_id()
        )
    );

-- ── 5-4. member_notifications ──
-- 🔴 INSERT 정책은 current_checkon_account_id() IS NOT NULL 하나뿐이다 — 발행 주체가
--    수신자가 아니기 때문이다(PR2 의 member_student_public_ids 와 같은 이유).
--    술어를 "발행자가 수신자의 학부모인가" 로 좁히려면 parent_student_relationships 에
--    학생 SELECT 정책이 필요한데(전수 #6), 그 정책은 기존 테이블이라 예약표 규칙 1 상 V38
--    아니면 추가할 수 없다. → open item 등재 (MB-43).
--    SELECT/UPDATE 는 수신자 전용이므로 읽기는 새지 않는다.
--    DELETE 정책은 만들지 않는다 (삭제 없음).
CREATE POLICY member_notifications_publisher_insert
    ON member_notifications
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
    );
CREATE POLICY member_notifications_recipient_select
    ON member_notifications
    FOR SELECT USING (
        current_checkon_account_id() IS NOT NULL
        AND recipient_account_id = current_checkon_account_id()
    );
CREATE POLICY member_notifications_recipient_update
    ON member_notifications
    FOR UPDATE USING (
        current_checkon_account_id() IS NOT NULL
        AND recipient_account_id = current_checkon_account_id()
    ) WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND recipient_account_id = current_checkon_account_id()
    );
