# MEMBER 미확정 안건 (Open Items)

작성 2026-08-25 14:10 KST · 대상 `com.checkon.member` 경계
정본 설계: `docs/MEMBER_DESIGN.md` §17 · 확정 내역은 이 문서가 정본이다.

🔴 상태 값은 `CONFIRMED` · `PROPOSED` · `OPEN` · `CONFLICT` · `SUPERSEDED` **다섯**만 쓴다
(정본은 `docs/POLICY_REGISTER.md:20-26`).
🔴 `CLOSED` 라는 값은 **없다.** 닫힌 안건은 `CONFIRMED` 로 두고 근거를 본문에 남긴다.
🔴 코드에서 미확정 안건을 참조할 때는 `// TODO(MB-05):` 형식으로 **번호를 붙인다.** 번호 없는 TODO 는 금지.

## 전수

| ID | 제목 | 상태 | 소유 | 결정 필요 시점 |
|---|---|---|---|---|
| MB-01 | 학생 로그인 식별자 (이메일 vs 공개 학생 ID) | **CONFIRMED** — 학생은 공개 학생 ID + 비밀번호(`POST /api/v1/member/auth/students/login`), 학부모는 이메일 + 비밀번호(기존 `POST /api/v1/auth/login`). member 가 공개 ID→email 변환 후 기존 `LoginService` 호출 — `account` 수정 0 | A | ✅ 2026-08-25 |
| MB-02 | 대기 학생(`PENDING_PARENT_LINK`) 허용 API 범위 | **CONFIRMED** — 세션 조회 · 활성화 상태(+공개 ID) 확인 · 로그아웃 **3개뿐**. 🔴 강사 초대 등록은 허용하지 않는다. 학습 기능은 전부 활성화 후 | A | ✅ 2026-08-25 |
| MB-03 | 학부모 한 계정이 여러 자녀를 등록할 수 있나 | **PROPOSED** — 다자녀 허용. 실측 근거: V33 이 학생당 학부모 1명만 제한하고 학부모당 자녀 수 제한은 두지 않았다 | A | PR4 착수 전 |
| MB-04 | 초대 코드 1회/다회 사용 · 만료 기간 | **CONFIRMED** — 역할이 지정된 한 계정만 쓰는 1회용(`max_claims=1`), 발급 후 **7일**. 학생용·학부모용 별도 발급. 동일 강사 재등록은 새 관계를 만들지 않고 기존 연결을 `200` 으로 반환(멱등) | A | ✅ 2026-08-25 |
| MB-05 | 미응답 문항이 있어도 제출 가능한가 | **CONFIRMED** — 허용(`member.attempt.require-complete-submission=false`). 근거: 계약(`member-api.yaml`)과 분기표(`01_endpoint_branch_matrix.md`) 둘 다 `SUBMISSION_INCOMPLETE` 를 「금지 정책일 때만」으로 적었다. 학생이 모르는 문제를 비우고 내는 것은 자연스럽고, 막으면 학습 흐름이 끊긴다. 🔴 `SUBMISSION_INCOMPLETE` enum 상수는 남긴다 — 정책이 뒤집힐 때 응답에서 쓸 자리다 | A | ✅ 2026-08-26 |
| MB-06 | progress autosave 주기와 시간 이상치 상한 | **PROPOSED** — 30초 주기 · 단일 delta 상한 600초 | A | PR5 착수 전 |
| MB-07 | 월별 최소 표본 수와 월 경계 timezone | **PROPOSED** — 최소 표본 10 · 월 경계 `Asia/Seoul` | A | PR7 착수 전 |
| MB-08 | 관계 종료 후 과거 학습기록·보고서를 학부모가 계속 볼 수 있나 | **OPEN** | A | PR7 착수 전 |
| MB-09 | 상담 취소 가능 시점과 강사 답변 후 추가 질문 허용 횟수 | **OPEN** | A | PR8 착수 전 |
| MB-10 | PDF 보존 기간, 공유 링크, 정정 보고서 정책 | **OPEN** | A | PR9 착수 전 |
| MB-11 | 월별 보고서 AI 생성 transport 와 운영 영속 저장 계약 | **OPEN** — AI 의 `/v1/reports` 는 인메모리라 그대로는 운영 원장으로 쓸 수 없다 | A | PR9 착수 전 |
| MB-12 | `member_*` 테이블을 `TenantDatabaseRoleSafetyVerifier` 목록에 합칠지, 별도 verifier 로 둘지 | **PROPOSED** — 별도 verifier. 팀원 파일 무접촉이 이유다 | A | PR2 착수 전 |
| MB-13 | member 경계 Flyway 번호 범위 | **CONFIRMED** — **V38 ~ V44** (2026-08-25 승우님 회신). V35~V37 선행 머지 확인(`595f3d3`), V38 미점유 실측. 🔴 배포 순서도 V37 → V38 (`docs/MONTHLY_REPORT_DECISIONS_AND_HANDOFF.md` §7 과 같은 규칙) | A | ✅ 2026-08-25 |
| MB-28 | `learning_records`(detection 용)와 `problem_assignment_responses`(diagnosis 용) 둘 다 써야 하나 | ✅ **CONFIRMED · 둘 다 쓴다**(2026-08-26 · 승우님 확인 + PR5b/S4 착수 시 스키마 실측) — 두 테이블은 **서로 대체 불가**다. `learning_records`(V8) 는 **사건 단위**(SOLVE/SUBMIT) 이고 `item_id`·`chosen_no` 컬럼이 아예 없다(`correct` 는 nullable). `problem_assignment_responses`(V34) 는 **문항 단위**이고 `record_type` 이 없으며 `area_tag`·`type_tag`·`skill_node_id` 가 전부 NOT NULL + enum·정규식 고정, `correct = (chosen_no = correct_no)` CHECK 가 있고 오답이면 `misconception_tag` 가 필수다. 🔴 **한쪽만으로는 강사 위험탐지도 학습 진단도 만들 수 없다.** 제출 트랜잭션은 문항당 1행씩 `problem_assignment_responses` 에 INSERT + `learning_records` 에 SOLVE 전량 + SUBMIT 1행 INSERT 를 같은 트랜잭션에서 한다. `LearningRecordWriter` 클래스 주석에 이 근거를 못 박았다. `learning_records.class_group_id` 는 항상 NULL(FK 대상이 학생 컨텍스트에서 안 보임 · PR5 §8 · V8:27-29) | A · 승우님 | ✅ 2026-08-26 |
| MB-29 | 임시 `MemberPingController` 제거 시점 | **CONFIRMED** — ✅ PR3 가 닫았다. `MemberPingController` · `MemberPingResult` 를 지우고 `MemberImplementedApiOpenApiContractTest` 의 `TEMPORARY_OPERATIONS` 상수와 그 예외 테스트를 **같은 커밋에서** 함께 제거했다(3 → 2 테스트). 보안 체인 증명은 `GET /api/v1/member/auth/session` 이 이어받았다. 근거 — PR1 이 보안 체인 증명용으로 `GET /api/v1/member/ping` 을 두었다. 계약(`member-api.yaml` 46개)에는 넣지 않았고 `MemberImplementedApiOpenApiContractTest` 의 `TEMPORARY_OPERATIONS` 로 예외 처리했다. 🔴 PR3 에서 컨트롤러와 그 예외를 **함께** 지운다 — 하나만 지우면 계약 대조가 red 가 되거나(컨트롤러만 남김) 죽은 예외가 남는다(예외만 남김) | A | ✅ 2026-08-25 |
| MB-30 | `member_student_public_ids` · `member_invitation_codes` 의 RLS 미적용 근거와 대안 | **OPEN** — 두 테이블은 **조회 주체가 소유자가 아니다.** 학부모가 자녀의 공개 ID 를 찾고, 학생·학부모가 강사의 초대 코드를 찾는다. 소유자 기준 정책으로는 그 조회가 물리적으로 불가능하고 정의자 권한 함수로 우회하면 RLS 설계 전체가 무의미해진다. 지금은 애플리케이션 rate limit + 정규화 비교(공개 ID)와 평문 미저장(초대 코드)으로 막는다. 🔴 누가 RLS 를 켜면 자녀 등록과 초대 검증이 죽으므로 `MemberDatabaseRoleSafetyVerifier` 가 기동 시 실패시킨다 — **PR4 재측정(2026-08-26): 근거 여전히 유효.** PR4 가 두 테이블을 처음으로 실제로 썼다. ① `member_student_public_ids` 는 학부모가 **아직 자기 것이 아닌** 학생을 공개 ID 로 찾는 경로라 소유 기준 술어가 성립하지 않는다 ② `member_invitation_codes` 는 조회 시점에 학생·학부모와 강사 사이에 **아무 관계도 없다** — 그 관계를 만드는 것이 바로 이 조회다. §6-4-2 범위 세션 변수도 못 쓴다: 범위를 열려면 먼저 관계를 확인해야 하는데 확인 대상이 곧 이 조회다(순환). 실측 `relrowsecurity/relforcerowsecurity = f/f` 둘 다, `MemberDatabaseRoleSafetyVerifier:35-40`(제외 목록) · `:69-76`(켜져 있으면 기동 실패) 확인. 🔴 다만 **인접한 구멍이 새로 드러났다** — `member_invitation_claims` 는 RLS 대상이라 `max_claims` 소진을 판정할 수 없다(MB-38) | A | PR3 착수 전 |
| MB-31 | `member_student_activation` 의 학부모·강사 교차 조회 경로 | **CONFIRMED** — ✅ V39 가 닫았다. `V39__member_display_name.sql` 의 정책 3개 — `member_student_activation_parent_scope_select`(학부모 조회) · `member_student_activation_parent_scope_update`(🔴 PR4 가 쓴다) · `member_student_activation_teacher_scope_select`(PR7 이 쓴다). 설계 §6-4-2 범위 세션 변수 방식이고 함수는 `current_checkon_scope_student_id()` 다. 확인을 삼킨 `MemberDatabaseContext.withVerified*Scope` 로만 열린다 | A | ✅ 2026-08-25 |
| MB-32 | `MemberSession.notificationsEnabled` 를 채울 원본이 PR6 까지 없다 | **OPEN** — 계약(`member-api.yaml:1748`)은 `{ type: boolean }` 으로 선언하되 `:1733` `required` 에서는 뺐다. 🔴 `nullable` 이 아니라 **null 로도 못 채운다.** 알림 설정 테이블은 PR6 소유라 PR3~PR5 응답에는 **키 자체가 없다.** 🔴 프론트는 이 키의 **부재**를 처리해야 한다. PR6 이 채우면 그때 나타난다 | A | PR6 착수 전 |
| MB-33 | `member_student_activation` 강사 조회 정책을 켜는 방법이 팀 간 합의 사항이다 | **OPEN** — `member_student_activation_teacher_scope_select`(V39)는 `current_checkon_teacher_id()`(승우님 컨텍스트 · member 는 절대 규칙 3 으로 **설정 금지**)와 `current_checkon_scope_student_id()`(member 가 만든 변수 · 승우님 코드는 존재를 모른다)를 **동시에** 요구한다. 즉 승우님 쪽 트랜잭션이 member 의 세션 변수를 설정해야 켜진다. 🔴 정책이 틀린 게 아니다 — 미리 깔아둔 것이고 V39 를 놓치면 다음 기회가 V40 이라 지금이 맞다. 🔴 **강사 기능 착수 전에 승우님과 합의**가 필요하다. 설계 §6-4-5 | A · 승우님 | 강사 기능 착수 전 |
| MB-34 | 저장소 전체의 RLS 정책이 테스트에서 검증된 적이 없다 | **OPEN** — 🔴 Testcontainers 기본 사용자가 `super=true bypassrls=true` 라 모든 RLS 가 우회된다(실측 `### PROBE ROLE: test super=true bypassrls=true`). 정책이 막아서 통과한 게 아니라 **RLS 자체가 꺼진 상태로 통과**해 왔다. PR2 의 `MemberScopeSessionVariableIntegrationTest` 도 `isEmpty()` 가 행이 없어서 통과한 것이다. PR3 가 member 쪽을 두 층으로 막았다 — `MemberRlsContextIntegrationTest`(제한 역할로 직접 조회) · `MemberRlsEnforcedApplicationIntegrationTest`(앱 datasource 를 제한 역할로 교체). 🔴 승우님의 `teacher_*` 정책은 **여전히 미검증**이다. 정책이 틀렸다는 뜻이 아니라 **맞는지 틀린지 아무도 확인한 적이 없다**는 뜻이다 | A · 승우님 | 🔴 즉시 공유 |
| MB-15 | member 레이트 리밋이 인스턴스 로컬이다 | **OPEN** — PR4 가 `MemberRateLimiter` 를 인프로세스 고정 창(`ConcurrentHashMap`)으로 만들었다. `build.gradle` 무접촉이라 Bucket4j·Redis·Caffeine 을 넣을 수 없었다(실측: 셋 다 의존성에 없다). 🔴 **다중 인스턴스 배포에서 실효 상한이 인스턴스 수만큼 곱해진다** — 3대면 분당 10회가 아니라 30회다. 열거 방어가 그만큼 약해진다. 분산 리밋은 공유 저장소 도입이 선행 조건이고 그건 공통 PR 이다 | A | 다중 인스턴스 배포 전 |
| MB-16 | 사전 확인·초대 검증의 응답 시간 채널이 테스트로 잡히지 않는다 | **OPEN** — 설계 §9-3 은 "존재/부재 응답 시간 차를 만들지 않는다"를 요구하고 코드는 없는 코드도 해시를 먼저 계산한다. 🔴 그런데 **현재 스위트는 응답 시간을 재지 않는다** — 학생 부재 시 즉시 return 하도록 고쳐도 전부 green 이다(PR4 고의 파괴 #7 로 실측). 타이밍 단언은 CI 러너 편차에 취약해 그대로 넣으면 flaky 가 된다. 통계적 판정(예: 다수 표본의 중앙값 비교)이 필요하고 그건 별도 작업이다 | A | 열거 방어를 보증으로 주장하기 전 |
| MB-35 | 초대코드 **발급**(강사측) 경로가 없다 | **OPEN · 수용**(2026-08-26 판정) — 강사측 발급은 member 범위 밖이므로 테스트 픽스처를 DB 직접 INSERT 로 만드는 것이 맞다. PR4 는 이 상태로 머지한다. — `member_invitation_codes` 에 행을 넣는 API 가 어디에도 없다. PR4 는 등록·검증만 만들었고 테스트 픽스처는 DB 직접 INSERT 로 만든다. 🔴 발급은 `roster`(강사) 경계 일이라 member 가 만들 수 없다(무접촉). 강사 화면 작업 시 승우님 쪽에서 만들거나, member 에 강사용 sub-context 를 여는 결정이 필요하다 | A · 승우님 | 강사 기능 착수 전 |
| MB-36 | 학부모가 **자녀의 강사 목록**을 읽을 수 없다 | ✅ **CONFIRMED · V40 이 닫았다**(2026-08-26 · 승우님 예외 승인) — `V40__member_attempt.sql` 의 `teacher_student_relationships_member_parent_scope_select` 가 §6-4-2 범위 세션 변수(`current_checkon_scope_student_id()`) 로 학부모 SELECT 를 연다. 술어는 RLS 켜진 테이블 참조 0 · 재귀 0. `ChildViewAssembler.java` 가 `withVerifiedChildScope` 안에서 `activationRepository.findStatus` 와 `rosterRelationships.findTeachersOfChild` 를 함께 호출한다. `ChildView.teachers` 는 필드로 돌아왔고 assembler 한 곳에서만 채운다 — 밖에서 부르면 조용히 빈 배열이 된다는 리스크는 계속되므로 assembler 를 유일한 채우기 지점으로 유지한다. 실증: `MembershipIntegrationTest#teachersKeyIsPresentAndPopulated` (강사 담김 + ENDED 제외) · `#teachersKeyIsPresentInRegistrationResponse` (등록 응답도 같은 조립기) · `#teachersLookupIsScopedToVerifiedChild` (남의 자녀 격리). 🔴 예약표 규칙 1 예외 승인: 승우님 테이블(`teacher_student_relationships` = V6)에 정책을 추가하는 것은 V38 이후 처음이다. 근거는 「V38 이 재귀로 뺀 유일한 항목이고 §6-4-2 로 재귀 없이 다시 넣을 수 있다」 — 미룰수록 마이그레이션 자리가 없다 | A · 승우님 | ✅ 2026-08-26 |
| MB-37 | 사전 확인이 **다른 학부모의 연결**을 볼 수 없다 | **OPEN · 수용**(2026-08-26 판정) — `parent_student_relationships_member_parent_select`(V38:107-112)가 `parent_id = current_checkon_parent_id()` 로 격리하므로 학부모 컨텍스트에서 남의 연결은 0행이다. 따라서 `ChildVerification.reason = ALREADY_LINKED` 는 **호출자 자신이 이미 연결된 경우에만** 나온다. 🔴 **이것은 설계대로다** — 설계 정본 `00_member_backend_design.md` §9-1 이 「`V33:96-98` 의 `uq_parent_student_relationships_active_student` 가 **최종 보장**이다. 사전 조회는 **안내용일 뿐이다**」라고 이미 못 박았고, 분기표 §3 도 「이 응답은 **안내일 뿐** 등록을 보장하지 않는다」고 적었다. 다른 학부모가 등록한 학생은 사전 확인에서 `registrable:true` 로 보이고 **등록 시점에 409 로 갈린다** — PR4 통합 테스트가 그 경로를 단언한다. **코드를 고치지 않는다.** 🔴 **닫지 않는 이유**: 학부모에게 「이미 다른 학부모와 연결됨」을 *안내*하려면 결국 정책이 필요하다. 그때까지 열어 둔다 | A | 안내 문구가 제품 요구가 될 때 |
| MB-38 | `max_claims` 소진을 판정할 수 없다 | ✅ **CONFIRMED · V40 이 닫았다**(2026-08-26) — `V40__member_attempt.sql` 이 `uq_member_invitation_claims_single_use (invitation_id)` 를 만들고 `ck_member_invitation_codes_single_use CHECK (max_claims = 1)` 을 건다. `InvitationClaimService.java:114-127` 이 23505 제약 이름으로 걸러 `INVITE_ALREADY_CLAIMED` 로 번역한다. 실증: `MembershipIntegrationTest#secondAccountCannotClaimSameCode`. 🔴 **RLS 로 못 보는 것을 유니크 제약이 대신 막는다** — DB 는 정책과 무관하게 모든 행을 보므로 두 번째 계정의 INSERT 가 23505 로 실패한다(읽지 않고도 막힌다). 🔴 **왜 `revoked_at` 을 겸용하지 않았나** — `revoked_at` 은 「강사가 취소했다」의 뜻이라 「쓰였다」를 겹쳐 쓰면 강사가 둘을 영원히 구분할 수 없다. 「없는 값을 지어내지 마라」의 사촌이다 — **있는 값에 다른 뜻을 얹지 마라.** 기존 pair 제약(`uq_member_invitation_claims_pair`)은 그대로 두고 유니크 인덱스 하나를 추가하는 것이 최소 변경이다 | A | ✅ 2026-08-26 |
| MB-39 | 목록형 조회가 행마다 스코프 전환을 요구한다 | **OPEN · 수용**(2026-08-26 판정) — member 는 승우님 소유 테이블에 정책을 더 붙일 수 없다(예약표 규칙 1 · V38 이 유일한 예외). `saved_problem_set_items_member_student_select`(V38:176)가 `current_checkon_scope_problem_set_id()` 를 요구하고 함수는 한 번에 한 값이라, 학습지 목록의 각 행에 대해 스코프를 열고 count 를 세고 닫아야 한다. 🔴 **한 트랜잭션 안에서** 처리하면 왕복은 1회다 — 커넥션·트랜잭션을 새로 파지 않는다. 소유 증명은 ①의 `problem_assignments` self SELECT 결과 행이며, 그 값을 근거로 받는 `MemberDatabaseContext.withVerifiedProblemSetScope` 만 스코프를 연다(인자를 그대로 믿는 setter 는 두지 않는다). 🔴 목록이 아주 커지면(수백 건 이상) 이 방식이 한계다 — 그때는 승우님과 count 컬럼 또는 뷰를 협의해야 한다. 같은 제약이 PR7 분석에서 다시 나올 전망 | A | 목록 규모가 커질 때 |
| MB-40 | 학습지 표시 제목의 원천 컬럼이 없다 | **OPEN · 수용**(2026-08-26 판정) — `problem_assignments` (V17:147-163)에 title 컬럼이 없다. `WorksheetTitles.derive(itemCount, publishedAt)` 로 결정론 파생하고 파생 규칙 정본을 한 곳(`WorksheetTitles.java`)에 둔다. 🔴 원본 컬럼이 생기면 이 클래스를 지운다 — 임시 조치임을 이름과 주석으로 남긴다. 파생값을 저장하는 곳(`member_learning_sessions.title_text`)은 제출 트랜잭션이 그때의 파생값을 그대로 고정해 저장한다 | A · 승우님 | 학습지에 제목 필드가 필요해질 때 |
| MB-41 | 문항 태그(area/type) 원천이 학생 컨텍스트에서 보이지 않는다 | **OPEN · 수용**(2026-08-26 판정) — 학습지 상세의 `itemBreakdown` 은 area/type 별 문항 수인데, 원천 태그는 `problem_generation_items`(강사 전용 RLS) 또는 `item_snapshot.areaTag/typeTag` 다. 상세 조회는 문항 본문·정답을 내려보내지 않는 규약이라(계약) 상세에서 스코프를 열어 태그만 세는 것도 회피한다. 🔴 그래서 이 PR 은 `itemBreakdown` 을 **빈 배열**로 낸다 — 계약의 개별 항목은 `required: [areaTag, typeTag, count]` 라 지어낼 수 없다. `WorksheetDetailResponse.java` 의 주석에 이 사실을 남긴다. 통계는 PR7 에서 attempt 결과 기준으로 다시 만든다 | A | PR7 착수 전 |
| MB-42 | `MemberCodeRuleTest` 5곳이 OS 경로 구분자에 의존한다 | **OPEN**(2026-08-26 · 승우님 Windows 실측 보고 · 「천천히 해도 된다」) — `G2:125` · `G3:137` · `G4:149` · `G5:161` · `G15:317` 이 전부 `path.toString().contains("/…/")` 로 substring 을 본다. Windows 는 `\…\` 로 나와 오탐한다. 🔴 `Path` segment 단위(`stream().anyMatch(p -> p.getFileName().toString().equals("integration"))`) 로 바꾼다 — 문자열 치환은 경로 어딘가에 같은 이름이 우연히 들어가면 또 틀린다. 🔴 이번 세션(PR5b/S4)에서는 손대지 않는다. 승우님과 세션 분리 · 게이트 정본만 손대는 별도 커밋으로 낸다 | A | 게이트 파일을 다음에 손대는 세션 |

## PR3 착수 전 반드시 확정해야 하는 것

**MB-01 · MB-02 · MB-04** — 셋 다 **2026-08-25 확정 완료**. PR3 착수 조건은 충족됐다.

## ✅ PR2 착수 조건 — 전부 해소됨 (2026-08-25)

| 조건 | 해소 |
|---|---|
| MB-13 Flyway 번호 | ✅ **V38~V44** 확정. `595f3d3` 에서 V38 미점유 실측 |
| V35~V37 선행 머지 (out-of-order) | ✅ `595f3d3` (PR #89 · #91) |
| `dev` 가 V33 중복으로 깨져 있음 | ✅ PR #86 으로 복구 (`advance` → V34) |
| green 기준선 | ✅ **기존 428** / 총 468 / exit 0 / 로컬 4분 9초 / Flyway 37 |
| `CheckOnApplicationTests` 동적 검증 | ✅ 승우님 승인 — 조건 2개 + 별도 커밋 |

🔴 남은 건 **PR1(#92) 머지**뿐이다.

## 번호 규칙

- MB-01 ~ MB-12 는 설계 정본 §17 의 12건과 1:1 대응한다.
- MB-13 은 이 문서에서 신규 부여했다(Flyway 번호 예약).
- MB-28 은 설계 §1-4 ⑤ 에서 부여된 번호를 그대로 쓴다.
- MB-29 는 PR1 에서 신규 부여했다(임시 ping 제거).
- MB-30 은 PR2 에서 신규 부여했다(RLS 미적용 두 테이블).
- MB-31 은 PR2 에서 신규 부여했고 PR3/V39 가 닫았다(`member_student_activation` 의 교차 조회).
- MB-32 는 PR3 에서 신규 부여했다(`notificationsEnabled` 원본 부재).
- MB-33 은 PR3 에서 신규 부여했다(`_teacher_scope_select` 를 켜는 방법이 팀 간 합의 사항).
- MB-34 는 PR3 에서 신규 부여했다(RLS 정책이 테스트에서 검증된 적이 없다). 🔴 member 안건이 아니라 **저장소 전체 안건**이라 강사 기능 착수까지 미루지 않고 즉시 공유한다.
- MB-29 는 PR1 에서 부여하고 PR3 가 닫았다(임시 ping 제거).
- MB-15 · MB-16 은 PR4 에서 **처음 표에 등재**했다. 지시서가 번호를 쓰고 있었지만 이 문서에는 없었다 — 코드 규칙 G14 가 실재하지 않는 `TODO(MB-xx)` 를 red 로 잡는다.
- MB-35 ~ MB-38 은 PR4 에서 신규 부여했고 **2026-08-26 에 넷 다 판정했다** — MB-35 수용 · **MB-36 은 PR5/V40 이 정책 추가로 닫는다**(승우님 예외 승인) · MB-37 수용(설계 §9-1 대로) · **MB-38 은 V40 이 닫았다** (`uq_member_invitation_claims_single_use`).
- MB-35 ~ MB-38 은 PR4 에서 신규 부여했다. 🔴 **넷 다 「기능이 없다」가 아니라 「현재 RLS·제약으로는 판정할 수 없다」**는 종류다. V40 으로 MB-36 · MB-38 을 닫고 MB-37 은 설계상 수용한다.
- MB-39 · MB-40 · MB-41 은 PR5b/S2 에서 신규 부여했다. 셋 다 「member 가 승우님 소유 테이블을 못 바꾼다」에서 파생된 제약이다 — MB-39 는 목록형 조회의 행별 스코프 전환, MB-40 은 학습지 제목 원천 부재(파생), MB-41 은 문항 태그 원천 부재(→ `itemBreakdown` 빈 배열).
- MB-28 은 PR5b/S4 에서 **CONFIRMED · 둘 다 쓴다**로 확정했다(스키마 실측으로 대체 불가 근거 확보).
- MB-42 는 PR5b/S4 에서 신규 부여했다(게이트가 OS 경로 구분자 의존).
- MB-14 ~ MB-27 은 **아직 비어 있다** — 결번이며 재사용하지 않는다.
- 🔴 코드에 `TODO(MB-nn)` 을 쓰면 **이 표에 그 번호가 있어야 한다.** 코드 규칙 G9 는 TODO 의
  형식만 보고 번호가 실재하는지는 보지 않는다. MB-29 가 그 구멍으로 등재를 빠뜨린 사례다.
