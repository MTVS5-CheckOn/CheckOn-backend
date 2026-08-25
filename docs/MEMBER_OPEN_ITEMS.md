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
| MB-05 | 미응답 문항이 있어도 제출 가능한가 | **OPEN** — 금지로 정하면 `SUBMISSION_INCOMPLETE` 가 살아나고, 허용으로 정하면 그 코드는 쓰이지 않는다 | A | PR5 착수 전 |
| MB-06 | progress autosave 주기와 시간 이상치 상한 | **PROPOSED** — 30초 주기 · 단일 delta 상한 600초 | A | PR5 착수 전 |
| MB-07 | 월별 최소 표본 수와 월 경계 timezone | **PROPOSED** — 최소 표본 10 · 월 경계 `Asia/Seoul` | A | PR7 착수 전 |
| MB-08 | 관계 종료 후 과거 학습기록·보고서를 학부모가 계속 볼 수 있나 | **OPEN** | A | PR7 착수 전 |
| MB-09 | 상담 취소 가능 시점과 강사 답변 후 추가 질문 허용 횟수 | **OPEN** | A | PR8 착수 전 |
| MB-10 | PDF 보존 기간, 공유 링크, 정정 보고서 정책 | **OPEN** | A | PR9 착수 전 |
| MB-11 | 월별 보고서 AI 생성 transport 와 운영 영속 저장 계약 | **OPEN** — AI 의 `/v1/reports` 는 인메모리라 그대로는 운영 원장으로 쓸 수 없다 | A | PR9 착수 전 |
| MB-12 | `member_*` 테이블을 `TenantDatabaseRoleSafetyVerifier` 목록에 합칠지, 별도 verifier 로 둘지 | **PROPOSED** — 별도 verifier. 팀원 파일 무접촉이 이유다 | A | PR2 착수 전 |
| MB-13 | member 경계 Flyway 번호 범위 | **CONFIRMED** — **V38 ~ V44** (2026-08-25 승우님 회신). V35~V37 선행 머지 확인(`595f3d3`), V38 미점유 실측. 🔴 배포 순서도 V37 → V38 (`docs/MONTHLY_REPORT_DECISIONS_AND_HANDOFF.md` §7 과 같은 규칙) | A | ✅ 2026-08-25 |
| MB-28 | `learning_records`(detection 용)와 `problem_assignment_responses`(diagnosis 용) 둘 다 써야 하나 | **OPEN** — 지금은 둘 다 쓴다로 가되(기존 강사 위험탐지가 `learning_records` 에 의존) 팀원 확정이 필요하다 | A | PR5 착수 전 |
| MB-29 | 임시 `MemberPingController` 제거 시점 | **CONFIRMED** — ✅ PR3 가 닫았다. `MemberPingController` · `MemberPingResult` 를 지우고 `MemberImplementedApiOpenApiContractTest` 의 `TEMPORARY_OPERATIONS` 상수와 그 예외 테스트를 **같은 커밋에서** 함께 제거했다(3 → 2 테스트). 보안 체인 증명은 `GET /api/v1/member/auth/session` 이 이어받았다. 근거 — PR1 이 보안 체인 증명용으로 `GET /api/v1/member/ping` 을 두었다. 계약(`member-api.yaml` 46개)에는 넣지 않았고 `MemberImplementedApiOpenApiContractTest` 의 `TEMPORARY_OPERATIONS` 로 예외 처리했다. 🔴 PR3 에서 컨트롤러와 그 예외를 **함께** 지운다 — 하나만 지우면 계약 대조가 red 가 되거나(컨트롤러만 남김) 죽은 예외가 남는다(예외만 남김) | A | ✅ 2026-08-25 |
| MB-30 | `member_student_public_ids` · `member_invitation_codes` 의 RLS 미적용 근거와 대안 | **OPEN** — 두 테이블은 **조회 주체가 소유자가 아니다.** 학부모가 자녀의 공개 ID 를 찾고, 학생·학부모가 강사의 초대 코드를 찾는다. 소유자 기준 정책으로는 그 조회가 물리적으로 불가능하고 정의자 권한 함수로 우회하면 RLS 설계 전체가 무의미해진다. 지금은 애플리케이션 rate limit + 정규화 비교(공개 ID)와 평문 미저장(초대 코드)으로 막는다. 🔴 누가 RLS 를 켜면 자녀 등록과 초대 검증이 죽으므로 `MemberDatabaseRoleSafetyVerifier` 가 기동 시 실패시킨다 — **PR4 재측정(2026-08-26): 근거 여전히 유효.** PR4 가 두 테이블을 처음으로 실제로 썼다. ① `member_student_public_ids` 는 학부모가 **아직 자기 것이 아닌** 학생을 공개 ID 로 찾는 경로라 소유 기준 술어가 성립하지 않는다 ② `member_invitation_codes` 는 조회 시점에 학생·학부모와 강사 사이에 **아무 관계도 없다** — 그 관계를 만드는 것이 바로 이 조회다. §6-4-2 범위 세션 변수도 못 쓴다: 범위를 열려면 먼저 관계를 확인해야 하는데 확인 대상이 곧 이 조회다(순환). 실측 `relrowsecurity/relforcerowsecurity = f/f` 둘 다, `MemberDatabaseRoleSafetyVerifier:35-40`(제외 목록) · `:69-76`(켜져 있으면 기동 실패) 확인. 🔴 다만 **인접한 구멍이 새로 드러났다** — `member_invitation_claims` 는 RLS 대상이라 `max_claims` 소진을 판정할 수 없다(MB-38) | A | PR3 착수 전 |
| MB-31 | `member_student_activation` 의 학부모·강사 교차 조회 경로 | **CONFIRMED** — ✅ V39 가 닫았다. `V39__member_display_name.sql` 의 정책 3개 — `member_student_activation_parent_scope_select`(학부모 조회) · `member_student_activation_parent_scope_update`(🔴 PR4 가 쓴다) · `member_student_activation_teacher_scope_select`(PR7 이 쓴다). 설계 §6-4-2 범위 세션 변수 방식이고 함수는 `current_checkon_scope_student_id()` 다. 확인을 삼킨 `MemberDatabaseContext.withVerified*Scope` 로만 열린다 | A | ✅ 2026-08-25 |
| MB-32 | `MemberSession.notificationsEnabled` 를 채울 원본이 PR6 까지 없다 | **OPEN** — 계약(`member-api.yaml:1748`)은 `{ type: boolean }` 으로 선언하되 `:1733` `required` 에서는 뺐다. 🔴 `nullable` 이 아니라 **null 로도 못 채운다.** 알림 설정 테이블은 PR6 소유라 PR3~PR5 응답에는 **키 자체가 없다.** 🔴 프론트는 이 키의 **부재**를 처리해야 한다. PR6 이 채우면 그때 나타난다 | A | PR6 착수 전 |
| MB-33 | `member_student_activation` 강사 조회 정책을 켜는 방법이 팀 간 합의 사항이다 | **OPEN** — `member_student_activation_teacher_scope_select`(V39)는 `current_checkon_teacher_id()`(승우님 컨텍스트 · member 는 절대 규칙 3 으로 **설정 금지**)와 `current_checkon_scope_student_id()`(member 가 만든 변수 · 승우님 코드는 존재를 모른다)를 **동시에** 요구한다. 즉 승우님 쪽 트랜잭션이 member 의 세션 변수를 설정해야 켜진다. 🔴 정책이 틀린 게 아니다 — 미리 깔아둔 것이고 V39 를 놓치면 다음 기회가 V40 이라 지금이 맞다. 🔴 **강사 기능 착수 전에 승우님과 합의**가 필요하다. 설계 §6-4-5 | A · 승우님 | 강사 기능 착수 전 |
| MB-34 | 저장소 전체의 RLS 정책이 테스트에서 검증된 적이 없다 | **OPEN** — 🔴 Testcontainers 기본 사용자가 `super=true bypassrls=true` 라 모든 RLS 가 우회된다(실측 `### PROBE ROLE: test super=true bypassrls=true`). 정책이 막아서 통과한 게 아니라 **RLS 자체가 꺼진 상태로 통과**해 왔다. PR2 의 `MemberScopeSessionVariableIntegrationTest` 도 `isEmpty()` 가 행이 없어서 통과한 것이다. PR3 가 member 쪽을 두 층으로 막았다 — `MemberRlsContextIntegrationTest`(제한 역할로 직접 조회) · `MemberRlsEnforcedApplicationIntegrationTest`(앱 datasource 를 제한 역할로 교체). 🔴 승우님의 `teacher_*` 정책은 **여전히 미검증**이다. 정책이 틀렸다는 뜻이 아니라 **맞는지 틀린지 아무도 확인한 적이 없다**는 뜻이다 | A · 승우님 | 🔴 즉시 공유 |
| MB-15 | member 레이트 리밋이 인스턴스 로컬이다 | **OPEN** — PR4 가 `MemberRateLimiter` 를 인프로세스 고정 창(`ConcurrentHashMap`)으로 만들었다. `build.gradle` 무접촉이라 Bucket4j·Redis·Caffeine 을 넣을 수 없었다(실측: 셋 다 의존성에 없다). 🔴 **다중 인스턴스 배포에서 실효 상한이 인스턴스 수만큼 곱해진다** — 3대면 분당 10회가 아니라 30회다. 열거 방어가 그만큼 약해진다. 분산 리밋은 공유 저장소 도입이 선행 조건이고 그건 공통 PR 이다 | A | 다중 인스턴스 배포 전 |
| MB-16 | 사전 확인·초대 검증의 응답 시간 채널이 테스트로 잡히지 않는다 | **OPEN** — 설계 §9-3 은 "존재/부재 응답 시간 차를 만들지 않는다"를 요구하고 코드는 없는 코드도 해시를 먼저 계산한다. 🔴 그런데 **현재 스위트는 응답 시간을 재지 않는다** — 학생 부재 시 즉시 return 하도록 고쳐도 전부 green 이다(PR4 고의 파괴 #7 로 실측). 타이밍 단언은 CI 러너 편차에 취약해 그대로 넣으면 flaky 가 된다. 통계적 판정(예: 다수 표본의 중앙값 비교)이 필요하고 그건 별도 작업이다 | A | 열거 방어를 보증으로 주장하기 전 |
| MB-35 | 초대코드 **발급**(강사측) 경로가 없다 | **OPEN** — `member_invitation_codes` 에 행을 넣는 API 가 어디에도 없다. PR4 는 등록·검증만 만들었고 테스트 픽스처는 DB 직접 INSERT 로 만든다. 🔴 발급은 `roster`(강사) 경계 일이라 member 가 만들 수 없다(무접촉). 강사 화면 작업 시 승우님 쪽에서 만들거나, member 에 강사용 sub-context 를 여는 결정이 필요하다 | A · 승우님 | 강사 기능 착수 전 |
| MB-36 | 학부모가 **자녀의 강사 목록**을 읽을 수 없다 | **OPEN** — `teacher_student_relationships` 에 학부모용 SELECT 정책이 없다. V38:126-135 가 **의도적으로** 뺐다 — `parent_student_relationships` 를 EXISTS 로 참조해야 하는데 그 테이블의 V33 teacher 정책이 되짚어 무한 재귀가 난다(불변식 4번, 실측). 🔴 그래서 PR4 의 `Child.teachers` 는 **`null`(=모른다)** 이다. **빈 배열로 내리지 않았다** — 그러면 "이 자녀는 강사가 없다"는 틀린 사실을 단정하게 된다. 해법은 §6-4-2 범위 세션 변수(`scope_student_id`)를 쓰는 학부모 SELECT 정책이고, 그건 마이그레이션이 필요해 PR4(0개) 범위 밖이다 | A | PR5 마이그레이션(V40) 때 |
| MB-37 | 사전 확인이 **다른 학부모의 연결**을 볼 수 없다 | **OPEN** — `parent_student_relationships_member_parent_select`(V38:107-112)가 `parent_id = current_checkon_parent_id()` 로 격리하므로, 학부모 컨텍스트에서 남의 연결은 0행이다. 🔴 따라서 `ChildVerification.reason = ALREADY_LINKED` 는 **호출자 자신이 이미 연결된 경우에만** 나온다. 다른 학부모가 이미 등록한 학생은 사전 확인에서 `registrable:true` 로 보이고 **등록 시점에 409 로 갈린다**. 계약(§5-2)이 "사전 확인은 등록을 보장하지 않는다"고 이미 못 박았으므로 계약 위반은 아니지만, 분기표 §3 의 "이미 다른 학부모 연결 → 200 registrable:false" 행은 **현재 스키마로는 구현 불가**다 | A | 분기표 갱신 시 |
| MB-38 | `max_claims` 소진을 판정할 수 없다 | **OPEN** — 🔴 MB-04 는 초대 코드를 `max_claims=1` 1회용으로 확정했는데, **그것을 강제할 수단이 없다.** `member_invitation_claims` 는 계정 소유 정책(V38)으로 격리돼 **남의 claim 이 0으로 보이고**, `uq_member_invitation_claims_pair` 는 *같은 계정*의 재사용만 막는다. 총 claim 수를 제한하는 DB 제약은 없다. 그 결과 **다른 계정이 이미 쓴 코드도 두 번째 계정이 등록에 성공한다.** PR4 는 없는 판정을 있는 척하지 않고 그 분기(`409 INVITE_ALREADY_CLAIMED` 의 "다른 계정 소진")를 **구현하지 않았다**. 해법은 `member_invitation_codes` 에 claim 카운터를 두고 조건부 UPDATE 로 소진을 잠그는 것이며 마이그레이션이 필요하다 | A | PR5 마이그레이션(V40) 때 |

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
- MB-35 ~ MB-38 은 PR4 에서 신규 부여했다. 🔴 **넷 다 「기능이 없다」가 아니라 「현재 RLS·제약으로는 판정할 수 없다」**는 종류다. MB-36 · MB-37 · MB-38 은 마이그레이션 없이는 닫히지 않는다(PR4 는 0개).
  🔴 승우님 테이블(`saved_problem_sets` · `saved_problem_set_items`)은 여기서 **뺐다** — 예약표 규칙 1 상 V38 이 아니면 정책을 못 넣으므로 미루지 않고 §6-4-2 방식으로 V38 에 넣었다.
- MB-14 ~ MB-27 은 **아직 비어 있다** — 결번이며 재사용하지 않는다.
- 🔴 코드에 `TODO(MB-nn)` 을 쓰면 **이 표에 그 번호가 있어야 한다.** 코드 규칙 G9 는 TODO 의
  형식만 보고 번호가 실재하는지는 보지 않는다. MB-29 가 그 구멍으로 등재를 빠뜨린 사례다.
