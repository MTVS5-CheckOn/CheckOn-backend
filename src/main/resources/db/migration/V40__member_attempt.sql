-- V40 — 파일명은 member_attempt 지만 실제로는 **넷**이 들어갈 자리다.
--   1. 🔴 MB-38  초대코드 1회용 강제       ✅ 이전 커밋에서 넣었다
--   2. 🔴 MB-36  학부모의 자녀 강사 조회    ← 이 커밋에서 넣는다 (승우님 예외 승인)
--   3.    attempt 계열 5테이블              ← 다음 커밋
--   4.    RLS 정책 + Verifier 대상          ← 다음 커밋
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
