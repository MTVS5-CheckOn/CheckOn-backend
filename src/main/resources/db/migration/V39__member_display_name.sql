-- member 표시 이름 + 범위(scope) 함수 2개 + member_student_activation 교차 조회 정책 3개.
--
-- 🔴 파일 이름은 display_name 이지만 셋이 들어 있다. 이름만 보고 "표시 이름뿐"이라고 읽으면
--    다음 사람이 정책을 못 찾는다.
--      1. member_display_names 테이블 + 정책            (§7)
--      2. current_checkon_scope_student_id / _account_id (§7-1)
--      3. member_student_activation 학부모 SELECT · 학부모 UPDATE · 강사 SELECT (§7-1 · MB-31)
--
-- 🔴 기존(승우님) 테이블 정책 변경 0건이다. member_student_activation 은 member 소유(V38)다.
--    예약표 규칙 1 — 기존 테이블 정책 추가는 V38 뿐이다.
--
-- 🔴 모든 정책 술어는 자기 테이블 컬럼과 current_checkon_*() 만 쓴다 (설계 불변식 4).
--    RLS 가 켜진 테이블을 EXISTS·IN·JOIN 으로 참조하면 무한 재귀가 난다 — PR2 에서 8건 실측.

-- ─────────────── 1. 범위(scope) 함수 2개 ───────────────
-- V38 의 current_checkon_scope_problem_set_id() 가 만든 선례를 그대로 따른다(설계 §6-4-2).
-- 🔴 scope 는 주체가 아니라 "이번 트랜잭션이 열람하려는 대상"이다.
--    이름을 주체 함수(current_checkon_*_id)와 섞지 마라.
--
-- 3단계:
--   ① 앱이 관계 테이블을 자기 컨텍스트로 읽어 소유/담당을 확인한다
--      학부모 → parent_student_relationships (V38 정책이 이미 격리)
--      강사   → teacher_student_relationships (V33 정책이 이미 격리)
--   ② 확인된 id 를 checkon.scope_* 에 넣는다 (set_config(..., true) — 트랜잭션 로컬)
--   ③ 아래 정책이 그 값만 허용한다
--
-- 🔴 ①을 건너뛰고 세션에 값을 넣는 코드 경로가 없어야 한다.
--    확인과 세팅을 한 메서드에 묶고, 그 밖에서 checkon.scope_* 를 쓰는 코드가 0건이어야 한다.
--    그걸 막는 통합 테스트가 이 방식의 안전 근거다 — 없으면 세션 변수는 그냥 우회로다.

CREATE FUNCTION current_checkon_scope_student_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.scope_student_id', true), '')::UUID
$$;

CREATE FUNCTION current_checkon_scope_account_id() RETURNS UUID
LANGUAGE SQL STABLE PARALLEL SAFE AS $$
    SELECT NULLIF(current_setting('checkon.scope_account_id', true), '')::UUID
$$;

COMMENT ON FUNCTION current_checkon_scope_student_id() IS
    'Transaction-local StudentProfile ID the caller already proved access to. Not a subject.';
COMMENT ON FUNCTION current_checkon_scope_account_id() IS
    'Transaction-local Account ID the caller already proved access to. Not a subject.';

-- ─────────────── 2. member_display_names ───────────────
-- 학생·학부모 표시 이름을 저장할 곳이 스키마에 없다.
-- student_profiles 에는 alias 만 있고(V6), parent_profiles 는 id·account_id·account_role·시각뿐이며
-- accounts 에도 이름 컬럼이 없다(V4). 이름 없이는 학부모 앱 자녀 카드도 학생 홈 인사말도 못 만든다.
--
-- 🔴 학생·학부모 공용 테이블 하나다. 역할별로 쪼개지 마라 — 같은 값이 두 곳에 있으면 갈린다.
-- 🔴 student_profiles.alias 를 덮어쓰지 않는다. 가입 시 alias 에 같은 값을 초기값으로만 넣고,
--    이후 member API 는 이 테이블만 읽고 쓴다.
CREATE TABLE member_display_names (
    account_id UUID PRIMARY KEY REFERENCES accounts (id) ON DELETE RESTRICT,
    display_name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_member_display_names_value
        CHECK (display_name = btrim(display_name) AND char_length(display_name) BETWEEN 1 AND 80),
    CONSTRAINT ck_member_display_names_updated_at CHECK (updated_at >= created_at)
);

ALTER TABLE member_display_names ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_display_names FORCE ROW LEVEL SECURITY;

CREATE POLICY member_display_names_self_select ON member_display_names
    FOR SELECT USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

CREATE POLICY member_display_names_self_insert ON member_display_names
    FOR INSERT WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

CREATE POLICY member_display_names_self_update ON member_display_names
    FOR UPDATE USING (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    ) WITH CHECK (
        current_checkon_account_id() IS NOT NULL
        AND account_id = current_checkon_account_id()
    );

-- 🔴 「학부모는 활성 자녀의 이름도 볼 수 있어야 한다」를
--    EXISTS (SELECT 1 FROM parent_student_relationships ...) 로 쓰면 안 된다.
--    그게 PR2 에서 무한 재귀를 낸 형태다(설계 불변식 4 · §6-4-1). 범위 함수로 연다.
CREATE POLICY member_display_names_scope_select ON member_display_names
    FOR SELECT USING (
        current_checkon_scope_account_id() IS NOT NULL
        AND account_id = current_checkon_scope_account_id()
    );

-- ─────────────── 3. member_student_activation 교차 조회 (MB-31) ───────────────
-- V38 은 이 테이블에 학생 self 정책 2개만 넣었다. 학부모·강사 경로는 관계 테이블을
-- 참조해야 해서 불변식 4번에 걸렸다(V38 주석 참조).
--
-- 🔴 정책이 없으면 애플리케이션이 미리 확인해도 소용없다 — SELECT 가 그냥 0행이다.
--    RLS 는 "앱이 사전 확인했다"를 모른다. 그래서 미루는 것은 선택지가 아니었다.
--
-- 🔴 셋을 지금 한 번에 넣는다. member_student_activation 은 member 소유라 V39 에서 넣을 수 있고,
--    PR4 는 예약표에 마이그레이션 0개로 잡혀 있어 UPDATE 정책을 붙일 자리가 없다.
--    지금 안 넣으면 PR4 의 자녀 등록이 조용히 0행 UPDATE 로 끝난다.
--
--      학부모 SELECT — 자녀 활성화 상태 조회        PR3 이 쓴다
--      학부모 UPDATE — PENDING_PARENT_LINK -> ACTIVE  🔴 PR4 가 쓴다
--      강사   SELECT — 담당 학생 활성화 조회        PR7 이 쓴다

CREATE POLICY member_student_activation_parent_scope_select
    ON member_student_activation
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );

CREATE POLICY member_student_activation_parent_scope_update
    ON member_student_activation
    FOR UPDATE USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    ) WITH CHECK (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );

CREATE POLICY member_student_activation_teacher_scope_select
    ON member_student_activation
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
