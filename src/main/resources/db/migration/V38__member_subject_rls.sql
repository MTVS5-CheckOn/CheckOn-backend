-- member 경계(학생·학부모)의 RLS 기반.
--
-- 🔴 이 마이그레이션은 기존 테이블에 정책을 **추가만** 한다.
--    기존 정책을 지우거나 고치는 구문, RLS 를 끄는 구문을 한 번도 쓰지 않는다.
--    기존 teacher 정책의 술어는 한 글자도 건드리지 않는다.
--
-- 🔴 새 정책 이름에는 전부 _member_ 가 들어간다. 회귀 검증(R3)이
--    policyname NOT LIKE '%\_member\_%' 로 기존 정책만 뽑아 술어를 문자열 비교하기 때문이다.
--    이름 규칙을 어기면 새 정책이 그 결과에 섞여 "기존 정책이 바뀌었다"로 오진된다.
--
-- 🔴 모든 술어의 첫 조건은 current_checkon_*_id() IS NOT NULL 이다.
--    teacher 컨텍스트에서는 이 설정이 비어 있으므로 새 정책이 확실히 false 가 된다.
--    하나라도 빠지면 그 정책이 강사 요청에도 걸려 기존 동작이 바뀐다.

-- ─────────────────────────── 1. 주체 함수 3개 ───────────────────────────
-- member 의 주체는 teacher tenant 와 다른 축이다. 이름을 분리해 두 컨텍스트가
-- 서로 간섭하지 않게 한다. 🔴 정의자 권한(definer rights)으로 만들지 않는다 —
-- V7 의 current_checkon_teacher_id() 와 동일하게 STABLE PARALLEL SAFE 만 준다.

CREATE FUNCTION current_checkon_account_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.current_account_id', true), '')::UUID
$$;

CREATE FUNCTION current_checkon_student_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.current_student_id', true), '')::UUID
$$;

CREATE FUNCTION current_checkon_parent_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.current_parent_id', true), '')::UUID
$$;

COMMENT ON FUNCTION current_checkon_account_id() IS
    'Transaction-local account ID for the member boundary. NULL denies every member row.';
COMMENT ON FUNCTION current_checkon_student_id() IS
    'Transaction-local StudentProfile ID. Never set together with checkon.current_teacher_id.';
COMMENT ON FUNCTION current_checkon_parent_id() IS
    'Transaction-local ParentProfile ID. Never set together with checkon.current_teacher_id.';

-- ─────────────── 1-1. 범위(scope) 함수 — 주체가 아니라 열람 대상 ───────────────
-- 🔴 불변식 4번(정책이 RLS 켜진 테이블을 참조하지 않는다) 때문에 교차 조회를 정책만으로 열 수 없다.
--    그렇다고 정책을 빼면 그 기능이 물리적으로 안 된다 — RLS 는 애플리케이션이 "미리 확인했다"를
--    모르므로, 정책이 없으면 SELECT 가 그냥 0행이다.
--
--    그래서 확인된 대상 id 를 트랜잭션 로컬 세션 변수로 넘긴다(설계 §6-4-2):
--      ① 애플리케이션이 problem_assignments 를 학생 컨텍스트로 읽어 소유를 확인한다
--         (problem_assignments_member_student_select 가 이미 격리한다)
--      ② 확인된 problem_set_id 를 checkon.scope_problem_set_id 에 넣는다
--      ③ 아래 정책이 그 값만 허용한다
--    재귀 0 — 정책이 RLS 켜진 테이블을 참조하지 않는다.
--    격리 유지 — ①을 통과한 id 만 세션에 들어간다.
--
-- 🔴 이름을 주체 함수(current_checkon_*_id)와 구분한다. scope 는 "내가 누구인가"가 아니라
--    "이번 트랜잭션이 무엇을 열람하려는가"다. 둘을 섞으면 신뢰 경계가 흐려진다.
-- 🔴 ①을 건너뛰고 세션에 값을 넣는 코드 경로가 없어야 한다. 그걸 막는 통합 테스트가
--    이 방식의 안전 근거다 — 없으면 세션 변수는 그냥 우회로다.
CREATE FUNCTION current_checkon_scope_problem_set_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.scope_problem_set_id', true), '')::UUID
$$;

COMMENT ON FUNCTION current_checkon_scope_problem_set_id() IS
    'Transaction-local ProblemSet ID the caller already proved ownership of. Not a subject.';

-- ──────────────────── 2. 기존 9개 테이블 — 정책 추가만 ────────────────────

-- 2-1. parent_profiles
-- account_id 분기가 필요한 이유: MemberSubjectResolver 는 accountId 만 알고
-- parentProfileId 를 아직 모르는 상태에서 이 테이블을 읽어야 한다.
CREATE POLICY parent_profiles_member_self_select ON parent_profiles
    FOR SELECT USING (
        (current_checkon_parent_id() IS NOT NULL AND id = current_checkon_parent_id())
        OR (current_checkon_account_id() IS NOT NULL AND account_id = current_checkon_account_id())
    );

CREATE POLICY parent_profiles_member_self_insert ON parent_profiles
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

-- 2-2. parent_teacher_relationships
CREATE POLICY parent_teacher_relationships_member_parent_select
    ON parent_teacher_relationships
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
    );

-- 초대코드 등록 경로. 코드 검증은 애플리케이션이 하고, 여기서는 소유만 확인한다.
CREATE POLICY parent_teacher_relationships_member_parent_insert
    ON parent_teacher_relationships
    FOR INSERT WITH CHECK (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
        AND status = 'ACTIVE'
    );

-- 2-3. parent_student_relationships
-- 🔴 지금 스키마로는 학부모가 자녀를 등록하는 것이 물리적으로 불가능하다.
--    V33 의 teacher INSERT 정책이 "이미 ACTIVE 인 강사↔학부모 관계"를 요구하는데,
--    실제 순서는 [학부모 가입 → 자녀 등록 → 강사 초대] 이기 때문이다.
--    그래서 member 경로는 강사 관계 선행을 요구하지 않는다.
--    중복 등록의 최종 보장은 uq_parent_student_relationships_active_student 가 한다.
CREATE POLICY parent_student_relationships_member_parent_select
    ON parent_student_relationships
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
    );

CREATE POLICY parent_student_relationships_member_parent_insert
    ON parent_student_relationships
    FOR INSERT WITH CHECK (
        current_checkon_parent_id() IS NOT NULL
        AND parent_id = current_checkon_parent_id()
        AND status = 'ACTIVE'
    );
-- 🔴 UPDATE·DELETE 정책은 만들지 않는다. 관계 해제는 이번 범위 밖이고,
--    없으면 거절되는 것이 안전한 기본값이다.

-- 2-4. teacher_student_relationships
CREATE POLICY teacher_student_relationships_member_student_select
    ON teacher_student_relationships
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- 🔴 학부모용 SELECT 정책은 만들지 않는다 (설계 불변식 4).
--    parent_student_relationships 를 EXISTS 로 참조해야 하는데 그 테이블의 V33 teacher 정책이
--    teacher_student_relationships 를 되짚어 무한 재귀가 난다(실측).
--    IS NOT NULL 가드는 논리적 격리만 보장하고 재귀는 막지 못한다 —
--    Postgres 는 술어를 평가하기 전에 정책 그래프를 펼친다.
--    학부모가 자녀의 강사를 알아야 하면 애플리케이션이 두 번 나눠 읽는다.

-- 학생이 강사 초대코드를 등록하는 경로.
CREATE POLICY teacher_student_relationships_member_student_insert
    ON teacher_student_relationships
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
        AND status = 'ACTIVE'
    );

-- 2-5. problem_assignments / saved_problem_sets / saved_problem_set_items
-- 학생이 attempt 를 시작할 때 1회 문항 스냅샷을 복사하기 위한 최소 SELECT 권한이다.
-- 복사 이후 모든 조회는 member_* 테이블만 본다.
-- 🔴 problem_generation_items 와 problem_generation_item_options 에는 정책을 만들지 않는다.
--    학생이 볼 문항은 saved_problem_set_items.item_snapshot JSONB 에 이미 동결돼 있다.
--    원본 생성 테이블은 강사 전용으로 남긴다.
CREATE POLICY problem_assignments_member_student_select ON problem_assignments
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- 🔴 problem_assignments 를 EXISTS 로 참조하지 않는다 (설계 불변식 4) —
--    그 테이블도 RLS 가 켜져 있어 정책 그래프가 얽힌다.
--    두 테이블에는 소유자 컬럼이 없어 자기 컬럼만으로 학생 소유를 표현할 수 없다.
--    그래서 §6-4-2 의 범위 세션 변수를 쓴다: 애플리케이션이 problem_assignments 로
--    소유를 먼저 확인하고, 확인된 problem_set_id 를 checkon.scope_problem_set_id 에 넣는다.
--
-- 🔴 정책을 빼고 미루면 안 된다 — 이 두 테이블은 승우님 소유(V17)라
--    예약표 규칙 1 상 V38 이 아니면 정책을 추가할 수 없다. 지금 넣지 않으면
--    PR5 의 학습지 기능이 막히고 규칙 재협의가 필요해진다.
CREATE POLICY saved_problem_sets_member_student_select ON saved_problem_sets
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND current_checkon_scope_problem_set_id() IS NOT NULL
        AND id = current_checkon_scope_problem_set_id()
    );

CREATE POLICY saved_problem_set_items_member_student_select ON saved_problem_set_items
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND current_checkon_scope_problem_set_id() IS NOT NULL
        AND problem_set_id = current_checkon_scope_problem_set_id()
    );

-- 2-6. problem_assignment_responses
-- 🔴 이 테이블은 V34(advance_problem_generation_ai_contract)가 이미 만들었다.
--    member 는 자기 결과 테이블을 만들지 않고 여기에 INSERT 한다(설계 정본 §1-4 ⑤).
--    그래야 강사 대시보드·진단이 학생 제출을 바로 보고 데이터가 두 벌로 갈리지 않는다.
CREATE POLICY problem_assignment_responses_member_student_select
    ON problem_assignment_responses
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- 제출 시 학생이 자기 답안을 기록한다.
-- assignment 소유를 EXISTS 로 확인해 남의 학습지에 쓰는 것을 막는다.
-- 🔴 assignment 소유를 EXISTS 로 확인하지 않는다 (설계 불변식 4) —
--    problem_assignments 도 RLS 가 켜져 있어 정책 그래프가 얽힌다.
--    남의 학습지에 쓰는 것은 애플리케이션이 막는다: 제출 전에 assignment 를 학생
--    컨텍스트로 조회해(problem_assignments_member_student_select 가 이미 격리한다)
--    소유가 확인된 것만 여기에 INSERT 한다.
CREATE POLICY problem_assignment_responses_member_student_insert
    ON problem_assignment_responses
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
-- 🔴 UPDATE·DELETE 정책을 만들지 않는다. 원본이 USING (false) 로 append-only 다.
--    학생에게 열어주면 그 성질이 깨진다.
-- 🔴 학부모 정책도 만들지 않는다. 학부모는 member_learning_sessions 경유로 본다.

-- 2-7. learning_records
CREATE POLICY learning_records_member_student_select ON learning_records
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- 제출 시 학생이 자기 학습 원본을 기록한다.
-- teacher_id 위조를 막기 위해 활성 강사 관계 EXISTS 를 WITH CHECK 에 넣는다.
--
-- 🔴 학생 경로로 INSERT 할 때 class_group_id 는 항상 NULL 로 둔다.
--    non-null 이면 fk_learning_records_class_teacher 가 class_groups(id, teacher_id) 를
--    참조하는데, class_groups 에는 학생 SELECT 정책이 없어 FK 검증이 실패한다.
-- 🔴 강사 관계를 EXISTS 로 확인하지 않는다 (설계 불변식 4) —
--    teacher_student_relationships 도 RLS 가 켜져 있고, 그 테이블에 대한 member 정책과
--    맞물려 정책 그래프가 얽힌다. teacher_id 위조는 애플리케이션이 막는다:
--    학생 컨텍스트로 teacher_student_relationships 를 먼저 조회해
--    (teacher_student_relationships_member_student_select 가 이미 격리한다)
--    활성 관계가 있는 teacher_id 만 넘긴다.
CREATE POLICY learning_records_member_student_insert ON learning_records
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
-- 🔴 학부모 정책은 만들지 않는다. 학부모 화면은 member_learning_sessions 에서 읽는다.

-- ──────────────────── 3. member 기반 테이블 5개 ────────────────────

CREATE TABLE member_student_activation (
    student_id UUID PRIMARY KEY,
    status VARCHAR(24) NOT NULL,
    activated_at TIMESTAMPTZ,
    deactivated_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_student_activation_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_student_activation_status
        CHECK (status IN ('PENDING_PARENT_LINK', 'ACTIVE', 'DEACTIVATED')),
    CONSTRAINT ck_member_student_activation_time
        CHECK ((status <> 'ACTIVE') OR activated_at IS NOT NULL)
);

-- 🔴 이 테이블에는 RLS 를 걸지 않는다. 귀찮아서가 아니라 조회 주체가 소유자가 아니기 때문이다.
--    학부모가 자녀를 등록할 때 "내 것이 아닌" 학생의 공개 ID 를 찾아야 한다.
--    소유자 기준 정책으로는 그 조회가 물리적으로 불가능하고, 정의자 권한 함수로 우회하면
--    RLS 설계 전체가 무의미해진다. 대신 애플리케이션 레벨 rate limit + 정규화 비교로 막는다.
--    🔴 나중에 누가 "왜 여기만 RLS 가 없지?" 하고 켜면 자녀 등록 기능이 죽는다.
--    근거와 대안은 MEMBER_OPEN_ITEMS.md 의 MB-30 에 등재돼 있다.
CREATE TABLE member_student_public_ids (
    student_id UUID PRIMARY KEY,
    public_id VARCHAR(24) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_student_public_ids_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_student_public_ids_public UNIQUE (public_id),
    -- 정규화 저장: 대문자 + 하이픈 1개. 예 STU-B52D9K
    CONSTRAINT ck_member_student_public_ids_format CHECK (public_id ~ '^STU-[A-Z0-9]{6,12}$')
);

-- 🔴 이 테이블에도 RLS 를 걸지 않는다. 같은 이유다 — 조회 주체가 소유자가 아니다.
--    코드 소유자는 강사인데, 코드를 조회하는 것은 학생·학부모다.
--    대신 평문 코드를 절대 저장하지 않는 것으로 방어한다(code_hash 만 보관).
--    근거와 대안은 MEMBER_OPEN_ITEMS.md 의 MB-30 에 등재돼 있다.
CREATE TABLE member_invitation_codes (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    target_role VARCHAR(16) NOT NULL,
    code_hash VARCHAR(71) NOT NULL,
    max_claims INTEGER NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_invitation_codes_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_invitation_codes_hash UNIQUE (code_hash),
    CONSTRAINT ck_member_invitation_codes_role CHECK (target_role IN ('STUDENT', 'PARENT')),
    -- V5 의 refresh_token_hash 와 동일한 형식 규약
    CONSTRAINT ck_member_invitation_codes_hash CHECK (code_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_member_invitation_codes_claims CHECK (max_claims >= 1)
);

CREATE TABLE member_invitation_claims (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    invitation_id UUID NOT NULL,
    account_id UUID NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_invitation_claims_invitation
        FOREIGN KEY (invitation_id) REFERENCES member_invitation_codes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_invitation_claims_account
        FOREIGN KEY (account_id) REFERENCES accounts (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_invitation_claims_pair UNIQUE (invitation_id, account_id)
);

CREATE TABLE member_idempotency_records (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    account_id UUID NOT NULL,
    route_key VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(71) NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_member_idempotency UNIQUE (account_id, route_key, idempotency_key),
    CONSTRAINT ck_member_idempotency_hash CHECK (request_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_member_idempotency_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_member_idempotency_expires ON member_idempotency_records (expires_at);

-- ──────────── 3-1. member 기반 테이블 RLS (3개만) ────────────
-- 🔴 member_student_public_ids 와 member_invitation_codes 는 위 주석의 이유로 제외한다.
--    MemberDatabaseRoleSafetyVerifier 가 기동 시 이 구분을 강제한다 —
--    필수 3개가 꺼져 있어도, 제외 2개가 켜져 있어도 실패시킨다.

ALTER TABLE member_student_activation ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_student_activation FORCE ROW LEVEL SECURITY;
ALTER TABLE member_invitation_claims ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_invitation_claims FORCE ROW LEVEL SECURITY;
ALTER TABLE member_idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_idempotency_records FORCE ROW LEVEL SECURITY;

-- member_student_activation
--
-- 🔴 학부모·강사용 정책을 만들지 않는다 (설계 불변식 4).
--    자녀·담당 학생을 가려내려면 parent_student_relationships / teacher_student_relationships 를
--    EXISTS 로 참조해야 하는데, 둘 다 RLS 가 켜져 있어 정책 그래프가 얽힌다.
--    특히 parent_student_relationships 의 V33 teacher 정책은 teacher_student_relationships 를
--    되짚어 무한 재귀를 만든다(PR2 실측).
--
--    그래서 이 테이블은 **학생 self 접근만** 정책으로 연다.
--    · 학부모의 자녀 활성화 조회·해제(대기 상태 풀기)
--    · 강사의 담당 학생 활성화 조회
--    이 둘은 애플리케이션이 두 단계로 처리한다 — 관계 테이블을 각자의 컨텍스트로 먼저 읽어
--    student_id 목록을 얻고(그 조회는 기존 정책이 이미 격리한다), 그 다음 이 테이블을 읽는다.
--    🔴 그 경로 설계는 PR3(가입·활성화)에서 한다. MB-31 에 등재했다.

CREATE POLICY member_student_activation_member_student_select
    ON member_student_activation
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- 가입 시 본인 레코드를 만든다.
CREATE POLICY member_student_activation_member_student_insert
    ON member_student_activation
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );

-- member_invitation_claims — 본인 것만
CREATE POLICY member_invitation_claims_member_self_select
    ON member_invitation_claims
    FOR SELECT USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

CREATE POLICY member_invitation_claims_member_self_insert
    ON member_invitation_claims
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

-- member_idempotency_records — 본인 것만
CREATE POLICY member_idempotency_records_member_self_select
    ON member_idempotency_records
    FOR SELECT USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

CREATE POLICY member_idempotency_records_member_self_insert
    ON member_idempotency_records
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

COMMENT ON TABLE member_student_public_ids IS
    'No RLS by design: the reader is not the owner (a parent looks up a child''s public ID). See MB-30.';
COMMENT ON TABLE member_invitation_codes IS
    'No RLS by design: the reader is not the owner (students/parents look up a teacher''s code). See MB-30.';
