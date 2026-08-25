# MEMBER 미확정 안건 (Open Items)

작성 2026-08-25 14:10 KST · 대상 `com.checkon.member` 경계
정본 설계: `docs/MEMBER_DESIGN.md` §17 · 확정 내역은 이 문서가 정본이다.

🔴 상태 값은 `PROPOSED` · `OPEN` · `CONFIRMED` · `SUPERSEDED` 넷만 쓴다
(기존 `docs/POLICY_REGISTER.md` 어휘와 맞춘다).
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
| MB-29 | 임시 `MemberPingController` 제거 시점 | **OPEN** — PR1 이 보안 체인 증명용으로 `GET /api/v1/member/ping` 을 두었다. 계약(`member-api.yaml` 46개)에는 넣지 않았고 `MemberImplementedApiOpenApiContractTest` 의 `TEMPORARY_OPERATIONS` 로 예외 처리했다. 🔴 PR3 에서 컨트롤러와 그 예외를 **함께** 지운다 — 하나만 지우면 계약 대조가 red 가 되거나(컨트롤러만 남김) 죽은 예외가 남는다(예외만 남김) | A | PR3 착수 시 |

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
- MB-14 ~ MB-27 은 **아직 비어 있다** — 결번이며 재사용하지 않는다.
- 🔴 코드에 `TODO(MB-nn)` 을 쓰면 **이 표에 그 번호가 있어야 한다.** 코드 규칙 G9 는 TODO 의
  형식만 보고 번호가 실재하는지는 보지 않는다. MB-29 가 그 구멍으로 등재를 빠뜨린 사례다.
