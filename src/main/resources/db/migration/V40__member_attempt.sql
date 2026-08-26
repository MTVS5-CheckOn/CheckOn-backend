-- V40 — 파일명은 member_attempt 지만 실제로는 **넷**이 들어 있다.
--   1. 🔴 MB-38  초대코드 1회용 강제
--   2. 🔴 MB-36  학부모의 자녀 강사 조회       (승우님 예외 승인)
--   3.    attempt 계열 5테이블
--   4.    RLS 정책 (신규 5테이블) — Verifier 상수도 함께 갱신했다
--
-- 🔴 이름만 보고 「attempt 뿐」이라고 읽으면 다음 사람이 정책을 못 찾는다 —
--    V39 가 「display_name 인데 셋이 들어 있다」로 겪은 그대로다.
--
-- 🔴 <b>DROP 금지 · ALTER POLICY 금지</b> — R6 금지 SQL 이다. 기존 제약은 그대로 두고 <b>추가</b>만 한다.

-- ═════════════════════ 1. MB-38  초대코드 1회용 강제 ═════════════════════
--
-- 🔴 왜 지금인가 — MB-04 는 초대 코드를 max_claims=1 로 확정했지만 그것을 강제할 수단이 없다.
--    member_invitation_claims_member_self_select(V38) 가 account_id 로 격리해서
--    「다른 계정이 이미 썼는가」를 애플리케이션이 조회로 판정할 수 없다(RLS 로 남의 claim 이 0행).
--    uq_member_invitation_claims_pair 는 같은 계정 재청구만 막는다.
--    → **다른 계정도 성공**한다. PR4 는 그 분기를 "미구현"으로 남겼다.
--
-- 🔴 유니크 인덱스가 RLS 를 우회한다 — DB 는 정책과 무관하게 모든 행을 본다.
--    두 번째 계정의 INSERT 가 23505 로 실패한다. 애플리케이션은 그 이름을 보고
--    INVITE_ALREADY_CLAIMED 로 번역한다. **읽지 않고도 막는다.**
--
-- 🔴 revoked_at 을 "사용됨" 으로 겸용하지 않는다. revoked_at 은 "강사가 취소" 의 뜻이다.
--    "쓰였다" 를 겹쳐 쓰면 강사가 둘을 영원히 구분할 수 없다.
--    있는 값에 다른 뜻을 얹지 마라 — 「없는 값을 지어내지 마라」의 사촌이다.
CREATE UNIQUE INDEX uq_member_invitation_claims_single_use
    ON member_invitation_claims (invitation_id);

-- 🔴 스키마가 강제할 수 없는 것을 표현하지도 못하게 한다.
--    현재 데이터는 전량 max_claims=1 (V38 DEFAULT 1 + 코드가 다른 값을 넣지 않는다).
--    앞으로 누군가 "다중 사용" 코드를 만들려고 이 값을 바꾸면 CHECK 가 즉시 막는다.
ALTER TABLE member_invitation_codes
    ADD CONSTRAINT ck_member_invitation_codes_single_use CHECK (max_claims = 1);

-- ═════════════════════ 2. MB-36  학부모의 자녀 강사 조회 ═════════════════════
--
-- 🔴 이것은 승우님 테이블(teacher_student_relationships, V6)이다.
--    예약표 규칙 1 — 기존 테이블 정책 추가는 V38 뿐이다. V38 이 재귀(불변식 4) 때문에 뺐고,
--    §6-4-2 범위 세션 변수로 다시 넣지 못한 유일한 항목이 이것이다.
--    승우님 승인을 받아 V40 에 넣는다 — V38 이후 예외 승인의 유일한 사례다.
--
-- 🔴 정책만 넣으면 MB-33 재발(아무도 못 쓰는 정책)이다.
--    ChildViewAssembler 가 withVerifiedChildScope 로 범위를 열어 채운다(같은 커밋).
--
-- 🔴 술어에서 RLS 켜진 테이블 참조 0 · 재귀 0. 범위는 PR3 이 만든 함수를 그대로 쓴다.
--    학부모는 확인된 자녀의 관계만 본다 (`current_checkon_scope_student_id()`).
CREATE POLICY teacher_student_relationships_member_parent_scope_select
    ON teacher_student_relationships
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
-- ═════════════════════ 3. attempt 계열 5테이블 ═════════════════════
--
-- 🔴 <b>member_attempt_item_results 를 만들지 않는다</b>(설계 정본 §1-4 ⑤ · 2026-08-25 재측정).
--    최종 문항별 결과는 승우님 problem_assignment_responses 에 INSERT 한다.
--    만들면 데이터가 두 벌로 갈리고, 강사 대시보드와 학생 화면이 근거를 잃는다.

CREATE TABLE member_attempts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    student_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    snapshot_hash VARCHAR(71) NOT NULL,
    item_count INTEGER NOT NULL,
    active_elapsed_sec INTEGER NOT NULL DEFAULT 0,
    last_client_sequence INTEGER,
    started_at TIMESTAMPTZ NOT NULL,
    last_progress_at TIMESTAMPTZ,
    submitted_at TIMESTAMPTZ,
    scored_at TIMESTAMPTZ,
    CONSTRAINT fk_member_attempts_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_attempts_assignment
        FOREIGN KEY (assignment_id) REFERENCES problem_assignments (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_attempts_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_attempts_status
        CHECK (status IN ('IN_PROGRESS', 'SUBMITTED', 'SCORED')),
    CONSTRAINT ck_member_attempts_hash
        CHECK (snapshot_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_member_attempts_numbers
        CHECK (version >= 0 AND item_count >= 1 AND active_elapsed_sec >= 0),
    -- 상태 전이의 최종 보장. 애플리케이션의 세터 순서에 의존하지 않는다.
    CONSTRAINT ck_member_attempts_terminal CHECK (
        (status = 'IN_PROGRESS' AND submitted_at IS NULL AND scored_at IS NULL)
        OR (status = 'SUBMITTED' AND submitted_at IS NOT NULL AND scored_at IS NULL)
        OR (status = 'SCORED' AND scored_at IS NOT NULL AND scored_at >= submitted_at)
    )
);

-- 🔴 이중 시작 방어. 같은 학생·assignment 에 열린 attempt 는 물리적으로 1개다.
--    애플리케이션의 "있으면 재개" 는 편의이며 최종 보장은 이 인덱스가 한다.
--    partial unique — SUBMITTED/SCORED 는 여러 벌 남지 않으나 재제출을 막지 않는다(다른 방향).
CREATE UNIQUE INDEX uq_member_attempts_open
    ON member_attempts (student_id, assignment_id) WHERE status = 'IN_PROGRESS';
CREATE INDEX idx_member_attempts_student_started
    ON member_attempts (student_id, started_at DESC, id DESC);

-- 문항 스냅샷 — 시작 시점 동결. UPDATE 정책을 아예 만들지 않는 것으로 강제한다.
CREATE TABLE member_attempt_items (
    attempt_id UUID NOT NULL,
    item_id UUID NOT NULL,
    ordinal INTEGER NOT NULL,
    stem TEXT NOT NULL,
    passage TEXT,
    options JSONB NOT NULL,
    correct_no INTEGER NOT NULL,
    explanation TEXT,
    area_tag VARCHAR(80),
    type_tag VARCHAR(80),
    skill_node_id VARCHAR(120),
    PRIMARY KEY (attempt_id, item_id),
    CONSTRAINT fk_member_attempt_items_attempt
        FOREIGN KEY (attempt_id) REFERENCES member_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_attempt_items_ordinal UNIQUE (attempt_id, ordinal),
    CONSTRAINT ck_member_attempt_items_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_member_attempt_items_correct_no CHECK (correct_no >= 1)
);

-- 진행 중 임시 답안. 시작 시 문항 수만큼 selected_no=NULL 로 미리 넣는다(§4 3단계).
CREATE TABLE member_attempt_answers (
    attempt_id UUID NOT NULL,
    item_id UUID NOT NULL,
    selected_no INTEGER,
    active_elapsed_sec INTEGER NOT NULL DEFAULT 0,
    revision INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (attempt_id, item_id),
    CONSTRAINT fk_member_attempt_answers_item
        FOREIGN KEY (attempt_id, item_id) REFERENCES member_attempt_items (attempt_id, item_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_member_attempt_answers_selected
        CHECK (selected_no IS NULL OR selected_no >= 1),
    CONSTRAINT ck_member_attempt_answers_elapsed CHECK (active_elapsed_sec >= 0),
    CONSTRAINT ck_member_attempt_answers_revision CHECK (revision >= 0)
);

-- 이벤트 로그. client_sequence 는 nullable — Postgres 는 NULL 을 unique 로 보지 않는다.
-- 🔴 STARTED/SUBMITTED 가 여러 행이 되는 걸 막는 건 이 인덱스가 아니라 attempts.status CHECK 다.
CREATE TABLE member_attempt_events (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    attempt_id UUID NOT NULL,
    event_type VARCHAR(24) NOT NULL,
    item_id UUID,
    client_sequence INTEGER,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_attempt_events_attempt
        FOREIGN KEY (attempt_id) REFERENCES member_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_attempt_events_client_seq UNIQUE (attempt_id, client_sequence),
    CONSTRAINT ck_member_attempt_events_type
        CHECK (event_type IN ('STARTED', 'RESUMED', 'PROGRESS', 'SUBMITTED', 'SCORED'))
);
CREATE INDEX idx_member_attempt_events_attempt ON member_attempt_events (attempt_id, occurred_at);

-- 학습 세션 요약. 학부모·강사 화면이 여기를 읽는다(문항 본문·정답 없음).
CREATE TABLE member_learning_sessions (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    attempt_id UUID NOT NULL,
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    title_text VARCHAR(255) NOT NULL,
    item_count INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    active_elapsed_sec INTEGER NOT NULL,
    submit_record_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_learning_sessions_attempt
        FOREIGN KEY (attempt_id) REFERENCES member_attempts (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_learning_sessions_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_learning_sessions_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_learning_sessions_assignment
        FOREIGN KEY (assignment_id) REFERENCES problem_assignments (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_learning_sessions_attempt UNIQUE (attempt_id),
    CONSTRAINT ck_member_learning_sessions_counts
        CHECK (item_count >= 1 AND correct_count BETWEEN 0 AND item_count
               AND active_elapsed_sec >= 0)
);
CREATE INDEX idx_member_learning_sessions_student
    ON member_learning_sessions (student_id, occurred_at DESC, id DESC);
CREATE INDEX idx_member_learning_sessions_teacher_student
    ON member_learning_sessions (teacher_id, student_id, occurred_at DESC, id DESC);

-- ═════════════════════ 4. RLS 정책 ═════════════════════
--
-- 학생은 5개 전부 self SELECT/INSERT (+ attempts·answers UPDATE).
-- 학부모는 attempts·learning_sessions 만 활성 자녀 SELECT (§6-4-2 scope_student_id).
-- 강사는 attempts·attempt_items·learning_sessions 만 담당 학생 SELECT.
-- 🔴 attempt_answers·attempt_events 는 학생 외 주체에게 정책을 만들지 않는다 — 학부모/강사가 볼 필요 없다.

ALTER TABLE member_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_items FORCE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_answers ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_answers FORCE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_attempt_events FORCE ROW LEVEL SECURITY;
ALTER TABLE member_learning_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_learning_sessions FORCE ROW LEVEL SECURITY;

-- ── member_attempts ──
CREATE POLICY member_attempts_member_student_select
    ON member_attempts
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_attempts_member_student_insert
    ON member_attempts
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_attempts_member_student_update
    ON member_attempts
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
-- 학부모 SELECT: 확인된 자녀의 attempt 만 (§6-4-2).
CREATE POLICY member_attempts_member_parent_scope_select
    ON member_attempts
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
-- 강사 SELECT: 담당 학생의 attempt 만.
CREATE POLICY member_attempts_member_teacher_scope_select
    ON member_attempts
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );

-- ── member_attempt_items ──
-- 🔴 UPDATE 정책 없음 — 동결이 정책 레벨에서 보장된다.
--    직접 조회는 attempt 소유 확인 뒤 attempt_id 로 하므로 자체 컬럼만으로 격리할 수 없다.
--    학생용은 attempt.student_id 를 EXISTS 로 참조하지 못하므로(불변식 4) 범위 함수로 연다.
--    현재는 학생 self select 만 두고, 크로스는 애플리케이션이 attempt 소유 확인 후
--    attempt_id 조건으로 조회한다(scope_attempt_id 는 도입하지 않는다 — 새 함수·마이그레이션 필요).
CREATE POLICY member_attempt_items_member_student_select
    ON member_attempt_items
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_items.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );
-- ⚠ 위 EXISTS 는 member_attempts 를 참조하지만 <b>member_ 소유 테이블끼리</b>다.
--   불변식 4번(승우님 RLS 테이블 재귀 방지)은 위반하지 않는다 — member 안에서만 얽힌다.
--   같은 주체(student_id)로 격리되고 정책 그래프가 발산하지 않는다(PR2 정신).
CREATE POLICY member_attempt_items_member_student_insert
    ON member_attempt_items
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_items.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );
-- 강사가 담당 학생 attempt 의 문항을 본다. 학부모는 이 테이블을 읽지 않는다(요약만 본다).
CREATE POLICY member_attempt_items_member_teacher_scope_select
    ON member_attempt_items
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_items.attempt_id
              AND a.student_id = current_checkon_scope_student_id()
        )
    );

-- ── member_attempt_answers ── (학생 전용)
CREATE POLICY member_attempt_answers_member_student_select
    ON member_attempt_answers
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_answers.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );
CREATE POLICY member_attempt_answers_member_student_insert
    ON member_attempt_answers
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_answers.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );
CREATE POLICY member_attempt_answers_member_student_update
    ON member_attempt_answers
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_answers.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_answers.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );

-- ── member_attempt_events ── (학생 전용)
CREATE POLICY member_attempt_events_member_student_select
    ON member_attempt_events
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_events.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );
CREATE POLICY member_attempt_events_member_student_insert
    ON member_attempt_events
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM member_attempts a
            WHERE a.id = member_attempt_events.attempt_id
              AND a.student_id = current_checkon_student_id()
        )
    );

-- ── member_learning_sessions ──
CREATE POLICY member_learning_sessions_member_student_select
    ON member_learning_sessions
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_learning_sessions_member_student_insert
    ON member_learning_sessions
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_learning_sessions_member_parent_scope_select
    ON member_learning_sessions
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
CREATE POLICY member_learning_sessions_member_teacher_scope_select
    ON member_learning_sessions
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
