# member API 엔드포인트별 분기·예외 매트릭스 (정본)

> 대상: `member-api.yaml` 오퍼레이션 **46개 전수**
> 이 문서가 **분기 판정의 유일한 정본**이다. PR 지시서·프론트 핸들러·MSW 픽스처가 전부 여기를 참조한다.
> 작성 2026-08-24 (KST) · 실측 기준 `CheckOn-backend` **`origin/dev` `daf3468`** (2026-08-25 재측정)

---

## §0. 공통 분기 규칙 — 46개 전부에 **항상** 적용된다

아래 8개는 오퍼레이션별 표에 **다시 적지 않는다.** 표에는 그 엔드포인트에만 있는 분기만 적는다.

### 0-1. 분기 8분류

모든 요청은 이 순서로 판정한다. 앞에서 걸리면 뒤는 보지 않는다.

| # | 분류 | 언제 | 응답 | 클라이언트 |
|---|---|---|---|---|
| ① | **인증** | 토큰 없음·만료·서명 불일치·세션 폐기·계정 비활성 | `401 AUTHENTICATION_REQUIRED` | single-flight refresh **1회** → 실패 시 메모리 토큰·Query cache 비우고 로그인 이동 |
| ② | **역할·활성화** | role 불일치 / 대기 학생이 제한 API 호출 | `403 ROLE_FORBIDDEN` · `403 STUDENT_ACTIVATION_REQUIRED` | code 로 분기. 전자는 잘못된 앱 안내, 후자는 활성화 대기 화면 |
| ③ | **입력 검증** | 필수 누락·타입·범위·형식·`limit` 상한 초과 | `400 INVALID_REQUEST` + `details:[{field, reason}]` | 필드별 인라인 오류. 🔴 첫 오류 필드로 focus 이동 |
| ④ | **부재·권한없음** | 리소스 없음 **또는** 남의 리소스 | `404 RESOURCE_NOT_FOUND` | 🔴 둘을 구분하지 않는다. 목록으로 복귀 |
| ⑤ | **충돌** | 멱등키 재사용·낙관락·중복 관계·상태 전이 위반 | `409` (코드별) | 🔴 **자동 재시도 금지.** 코드별 처리는 각 표 참조 |
| ⑥ | **상태 전이 위반** | 이미 제출됨 / 이미 취소됨 / 미발행 접근 | `409` 또는 `404` | 각 표 참조 |
| ⑦ | **의존 실패** | 스토리지·Kafka·AI·외부 호출 실패 | `503 DEPENDENCY_UNAVAILABLE` · `504 DEPENDENCY_TIMEOUT` | 캐시 유지 + 재시도 버튼. 🔴 자동 무한 재시도 금지 |
| ⑧ | **데이터 없음** | 조회는 성공했으나 값이 없음 | **`200`** + `status: NO_DATA \| INSUFFICIENT \| NOT_PRODUCED` | 🔴 오류가 아니다. 빈 상태 UI. 0 으로 채우지 마라 |

### 0-2. 항상 참인 것

- **응답 형식**: 성공 `{"data": …}` · 오류 `{"error":{"code","message","details"?}}` · 모든 응답에 `X-Request-Id`
- **`429 RATE_LIMITED`** + `Retry-After` 는 46개 전부에서 나올 수 있다(전역 레이트 리밋).
- **`500 INTERNAL`** 은 46개 전부에서 나올 수 있다. 🔴 `message` 에 예외 내용·스택·쿼리·개인정보를 넣지 않는다. 클라이언트는 자동 재시도하지 않고 `X-Request-Id` 를 보여준다.
- **`⑧`은 오류 코드가 아니다.** AI 의 `template_only`·`rejected_insufficient`·`no_data` 도 마찬가지로 200 계열이다(`CheckOn-AI` `CLAUDE.md:15` 불변식 4).
- 🔴 **비어 있음(`items: []`)과 데이터 없음(`status: NO_DATA`)은 다르다.** 목록은 전자, 지표는 후자를 쓴다.

### 0-3. 대기 학생(`PENDING_PARENT_LINK`) 허용 목록 — 🔴 **MB-02 CONFIRMED · 이 3개 외 전부** `403 STUDENT_ACTIVATION_REQUIRED`

```
GET  /member/auth/session                        세션·역할 확인
GET  /member/auth/students/activation-status     활성화 상태 + 공개 학생 ID 확인
POST /api/v1/auth/logout                         (member 밖 · 기존 API)
```

🔴 **강사 초대 검증·등록은 허용 목록에서 빠졌다.** 최초 기획이 "학부모가 자녀 등록을 완료하기 전까지 학생은 자기 계정 ID만 확인 가능" 이기 때문이다.
따라서 `POST /member/students/me/invitations/verification` 과 `/invitations` 는 **활성화 이후에만** 호출할 수 있고, 대기 중이면 `403 STUDENT_ACTIVATION_REQUIRED` 다.

판정은 Controller 가 아니라 `StudentActivationGuard` **한 곳**에서 하고, 허용 목록은 상수 배열 한 곳에만 둔다.

### 0-4. 멱등성 3분기 — `Idempotency-Key` 를 받는 모든 오퍼레이션 공통

| 조건 | 응답 |
|---|---|
| key 없음 (필수인데) | `400 INVALID_REQUEST` (`details.field = "Idempotency-Key"`) |
| 같은 key + **같은** request hash | 저장된 응답을 **그대로** 반환 (상태 코드까지 동일) |
| 같은 key + **다른** request hash | `409 IDEMPOTENCY_CONFLICT` — 🔴 자동 재시도 금지 |

`member_idempotency_records` 보관 24시간. `request_hash` 는 `sha256:<64hex>`.

### 0-5. 목록 오퍼레이션 공통

| 조건 | 응답 |
|---|---|
| `limit` > 50 | 🔴 `400 INVALID_REQUEST`. **조용히 깎지 않는다** |
| `cursor` 형식 오류·위조·만료 | `400 INVALID_REQUEST` |
| 결과 0건 | `200` + `{"items": [], "nextCursor": null, "hasNext": false}` |
| 마지막 페이지 | `nextCursor: null`, `hasNext: false` |

### 0-6. 🔴 절대 하지 않는 것

- 권한 없음을 `403` 으로 구분해서 존재를 노출하는 것 (④는 무조건 404)
- 미제출 attempt 응답에 `correctAnswer`·`explanation`·`correct` 를 넣는 것
- 미발행(`DRAFT`/`REVIEW_READY`/`FAILED`) 보고서를 학부모에게 노출하는 것
- 강사 미승인 AI 초안을 학부모 응답에 넣는 것
- 값이 없을 때 `0` 이나 그럴듯한 문자열로 채우는 것 (→ `null` + `status`)
- 상한으로 잘랐는데 무엇을 왜 잘랐는지 안 밝히는 것

---

## §1. 인증 · 학습지 · attempt (11)

### `POST /member/auth/students/sign-up` — 학생 가입 (Public)

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | — | `201` `{accountId, role:"STUDENT", studentPublicId, activationStatus:"PENDING_PARENT_LINK"}` | 공개 ID 보여주고 활성화 대기 화면으로 |
| 이메일 중복 | `accounts.email` unique 위반 | `409 EMAIL_ALREADY_EXISTS` | 로그인 유도. 🔴 "이미 가입된 이메일" 외 정보를 더 주지 않는다 |
| 약관 미동의 | `termsAgreed=false` | `400 INVALID_REQUEST` | 체크박스 focus |
| 학년 범위 밖 | `grade` ∉ 1..3 (`V6:23-29` CHECK) | `400 INVALID_REQUEST` | — |
| 비밀번호 정책 | 8자 미만 / 200자 초과 | `400 INVALID_REQUEST` | — |
| 공개 ID 발급 충돌 | unique 위반이 **5회 연속** | `500 INTERNAL` + 알람 | 잠시 후 재시도 안내 |
| 가입 폭주 | IP·이메일 단위 | `429` | `Retry-After` |

🔴 `accounts` INSERT 성공 후 `student_profiles`·`member_student_public_ids`·`member_student_activation`·`member_display_names` 중 하나라도 실패하면 **전부 롤백**한다. 계정만 남은 상태를 만들지 않는다.

### `POST /member/auth/parents/sign-up` — 학부모 가입 (Public)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | — | `201` `{accountId, role:"PARENT", studentPublicId:null, activationStatus:null}` |
| 이메일 중복 | | `409 EMAIL_ALREADY_EXISTS` |
| 약관 미동의 / 형식 오류 | | `400 INVALID_REQUEST` |
| 🔴 RLS 컨텍스트 미설정 | `parent_profiles` INSERT 시 `checkon.current_account_id` 가 없음 | RLS 위반 → `500 INTERNAL` | 
| | | |

🔴 `parent_profiles` 는 `FORCE RLS` 다(`V33:105-106`). account 생성 **직후** account 컨텍스트를 설정하고 나서 INSERT 해야 한다. 이걸 빼면 0행이 조용히 들어가는 게 아니라 **정책 위반 예외**가 난다 — 통합 테스트로 반드시 잡는다.

### `POST /member/auth/students/login` — 학생 로그인 (Public) 🔴 MB-01

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | 공개 ID → email 해석 성공 + 비밀번호 일치 | `200` `MemberAuthResult` + `Set-Cookie: CHECKON_REFRESH` (Path `/api/v1/auth`) | access token 은 **메모리에만**. 홈으로 |
| 공개 ID 없음 | `member_student_public_ids` 미일치 | 🔴 `401 INVALID_CREDENTIALS` | "ID 또는 비밀번호를 확인하세요" 하나로 |
| 계정 미연결 | `student_profiles.account_id IS NULL` | 🔴 `401 INVALID_CREDENTIALS` (동일) | 동일 |
| 비밀번호 불일치 | | `401 INVALID_CREDENTIALS` | 동일 |
| 계정 비활성 | `accounts.status ∈ {SUSPENDED, WITHDRAWN}` | `401 ACCOUNT_NOT_ACTIVE` | 문의 안내 |
| 형식 오류 | 정규화 후 `^STU-[A-Z0-9]{6,12}$` 불일치 · 비밀번호 8자 미만 | `400 INVALID_REQUEST` | 필드 오류 |
| 대기 학생(`PENDING_PARENT_LINK`) | | 🔴 **`200` 로그인 성공** | 활성화 대기 화면으로. 로그인 자체는 막지 않는다 |
| 로그인 폭주 | 공개 ID·IP 단위 | `429` | `Retry-After` |

🔴 **위 3개 실패(ID 없음·계정 미연결·비밀번호 불일치)를 구분하지 않는다.** 그리고 조회 실패 시에도 **더미 해시로 BCrypt 시간을 태운 뒤** 응답한다 — 안 그러면 응답 시간으로 공개 ID 존재 여부가 새어나간다(기존 `LoginService.java:31-32` 의 dummy-hash 와 같은 이유).

🔴 **쿠키 Path 는 `/api/v1/auth` 다.** member 가 자기 Path 를 쓰면 기존 `POST /api/v1/auth/refresh` 가 그 쿠키를 못 읽는다. 이름·`Secure`·`SameSite`·TTL 은 `AuthenticationProperties`(빈)에서 가져온다 — 하드코딩 금지.

🔴 **학부모는 이 엔드포인트를 쓰지 않는다.** 기존 `POST /api/v1/auth/login`(이메일) 그대로다.

### `GET /member/auth/session` — 앱 bootstrap

| 분기 | 조건 | 응답 |
|---|---|---|
| 학생 정상 | | `200` `{role:"STUDENT", studentProfileId, activationStatus, studentPublicId, teachers[], …}` |
| 학부모 정상 | | `200` `{role:"PARENT", parentProfileId, activationStatus:null, studentPublicId:null, …}` |
| 프로필 없음 | 계정은 있는데 `student_profiles`/`parent_profiles` 행이 없음 | 🔴 `401 AUTHENTICATION_REQUIRED` (깨진 상태 = 인증 불가로 본다) |
| 강사 토큰 | `role=TEACHER` | `403 ROLE_FORBIDDEN` |
| 연결 강사 0명 | | `200` + `teachers: []` (오류 아님) |

🔴 대기 학생도 **호출 가능**하다(§0-3).

### `GET /member/auth/students/activation-status` — 활성화 폴링

| 분기 | 조건 | 응답 |
|---|---|---|
| 대기 | | `200` `{status:"PENDING_PARENT_LINK", studentPublicId, activatedAt:null}` |
| 활성 | | `200` `{status:"ACTIVE", …, activatedAt}` |
| 비활성화 | | `200` `{status:"DEACTIVATED", …}` |
| 학부모 토큰 | | `403 ROLE_FORBIDDEN` |

🔴 프론트는 API 모드에서 5초 간격 폴링한다(`useStudentActivationQuery`). **폴링 총 시간 상한**을 두고, 초과 시 폴링을 멈추고 수동 새로고침 버튼으로 전환한다. 무한 폴링 금지.

### `GET /member/students/me/home`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| 진행 중 attempt 없음 | | `200` + `continuing: null` (오류 아님) |
| 오늘 학습지 없음 | | `200` + `todayWorksheets: []` |
| 약점 산출 불가 | 표본 부족·태그 없음 | `200` + `weakness.status: INSUFFICIENT` 또는 `NO_DATA`, 값은 `null` |
| 대기 학생 | | `403 STUDENT_ACTIVATION_REQUIRED` |

### `GET /member/students/me/worksheets` — 배정 학습지 목록

§0-5 외 추가 분기:

| 분기 | 조건 | 응답 |
|---|---|---|
| `status` 필터 값 오류 | ∉ `NEW\|IN_PROGRESS\|COMPLETED` | `400 INVALID_REQUEST` |
| 배정 0건 | | `200` + `items: []` |
| 채점 불가 학습지 포함 | §1-4 ③ 해석 실패 문항 보유 | `200` — 목록에는 **보인다**. 거절은 attempt 시작 시점 |

### `GET /member/students/me/worksheets/{assignmentId}` — 상세

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` (🔴 문항 본문·정답·해설 **없음**) |
| 남의 학습지 / 없음 | | `404 RESOURCE_NOT_FOUND` |
| assignmentId 형식 오류 | UUID 아님 | `400 INVALID_REQUEST` |
| 제목 원본 없음 | `problem_assignments` 에 title 컬럼 없음(§1-4 ⑤) | `200` + 파생 규칙으로 생성한 제목. 🔴 파생 규칙을 코드 주석에 명시 |

### `POST /member/students/me/worksheets/{assignmentId}/attempts` — 시작/재개

🔴 이 엔드포인트가 가장 분기가 많다.

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 신규 시작 | 열린 attempt 없음 | **`201`** `AttemptInProgress` | 풀이 화면 |
| 재개 | `status='IN_PROGRESS'` attempt 존재 | **`200`** 기존 attempt 그대로 | 🔴 답안·타이머 복원. 새로 만들지 않는다 |
| 이미 제출 완료 | `status='SCORED'` 만 있음 | `409 ATTEMPT_ALREADY_SUBMITTED` + `details.attemptId` | 결과 화면으로 이동 |
| 채점 불가 학습지 | `correctAnswerText` ↔ 보기 완전일치가 1개가 아님 | 🔴 `422 WORKSHEET_NOT_GRADABLE` + `details.itemIds[]` | "선생님께 문의" 안내. **attempt 를 만들지 않는다** |
| 문항 0개 | 스냅샷이 비었음 | `422 WORKSHEET_NOT_GRADABLE` | 동일 |
| 남의 학습지 / 없음 | | `404 RESOURCE_NOT_FOUND` | |
| 강사 관계 종료 | `teacher_student_relationships` 가 `ENDED` | `404 RESOURCE_NOT_FOUND` | 🔴 403 이 아니다 |
| 동시 이중 시작 | 두 탭에서 동시 POST | partial unique 위반 → **재조회 후 `200`** | 정상 동작. 사용자에게 오류를 보이지 않는다 |
| 대기 학생 | | `403 STUDENT_ACTIVATION_REQUIRED` | |

🔴 `201` 과 `200` 을 프론트가 **구분하지 않아도** 되도록 body 는 동일 스키마다. 하지만 서버는 구분해서 반환한다(로그·지표용).

### `GET /member/students/me/attempts/{attemptId}`

| 분기 | 조건 | 응답 |
|---|---|---|
| 진행 중 | | `200` `AttemptInProgress` (🔴 정답·해설 없음) |
| 채점 완료 | | `200` `AttemptResult` (정답·해설 **포함**) |
| 제출됐지만 채점 전 | `status='SUBMITTED'` | `200` + `status:"SUBMITTED"` — 🔴 정답·해설 **없음**. 프론트는 폴링 |
| 남의 attempt / 없음 | | `404 RESOURCE_NOT_FOUND` |

### `PATCH /member/students/me/attempts/{attemptId}/progress` — 자동저장

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 저장 | | `200` `{version, totalActiveElapsedSeconds, savedAt, duplicated:false}` | 조용히 성공 |
| 중복 전송 | 같은 `clientSequence` 재전송 | `200` + `duplicated: true` | 🔴 오류 아님. 무시 |
| 낙관락 충돌 | `baseVersion` ≠ 서버 `version` | `409 REVISION_CONFLICT` + `details.currentVersion` | attempt 재조회 → 병합 → 1회 재시도 |
| 이미 제출 | `status ≠ 'IN_PROGRESS'` | `409 ATTEMPT_ALREADY_SUBMITTED` | 결과 화면으로 |
| 음수 시간 | `activeElapsedSecondsDelta` < 0 | `400 INVALID_REQUEST` | 🔴 0 으로 보정하지 않는다 |
| 시간 이상치 | 항목당 delta > 600초 | `400 INVALID_REQUEST` | 🔴 조용히 깎지 않는다 |
| 없는 itemId | attempt 스냅샷에 없는 키 | `400 INVALID_REQUEST` + `details.itemIds[]` | |
| 보기 번호 범위 밖 | `selectedNo` ∉ 1..N | `400 INVALID_REQUEST` | |
| 남의 attempt / 없음 | | `404 RESOURCE_NOT_FOUND` | |

🔴 자동저장 실패는 **사용자 흐름을 막지 않는다.** 프론트는 실패를 조용히 로컬 큐에 쌓고 다음 주기에 재전송하되, `409 REVISION_CONFLICT` 만 즉시 재조회한다.

### `POST /member/students/me/attempts/{attemptId}/submission` — 제출·채점

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | | `200` `AttemptResult` | 결과 화면 |
| 재제출(같은 key·같은 body) | | `200` **저장된 동일 결과** | 정상 동작 |
| 같은 key·다른 body | | `409 IDEMPOTENCY_CONFLICT` | 🔴 자동 재시도 금지. 재조회 안내 |
| 이미 제출됨 | `status ∈ {SUBMITTED, SCORED}` | `409 ATTEMPT_ALREADY_SUBMITTED` | 결과 화면 |
| 낙관락 충돌 | `baseVersion` 불일치 | `409 REVISION_CONFLICT` | 재조회 → 재제출 |
| 미응답 문항 존재 | 정책이 "미응답 금지"일 때 **만** | `422 SUBMISSION_INCOMPLETE` + `details.itemIds[]` | 해당 문항 하이라이트. 🔴 정책은 MB-05 미확정 |
| 채점 불가 문항 | 스냅샷 `correct_no` 가 null | `422 WORKSHEET_NOT_GRADABLE` | 문의 안내 |
| `learning_records` INSERT 실패 | 강사 관계가 그 사이 종료 → RLS 거절 | 🔴 **전체 롤백** → `409` + 관계 종료 안내 | 목록 복귀 |
| 대기 학생 | | `403 STUDENT_ACTIVATION_REQUIRED` | |

🔴 제출은 **한 트랜잭션**이다. 채점은 성공했는데 `learning_records` 만 실패해서 통계가 틀어지는 상태를 만들지 않는다.

---

## §2. 학생 기록 · 질문 · 프로필 · 초대 (11)

### `GET /member/students/me/learning-records` (목록) / `/{recordId}` (상세)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| `month` 형식 오류 | `YYYY-MM` 아님 | `400 INVALID_REQUEST` |
| 미래 월 | | `200` + `items: []` (🔴 400 아님 — 정상 조회 결과가 없는 것) |
| 기록 0건 | | `200` + `items: []` |
| 남의 기록 / 없음 | | `404 RESOURCE_NOT_FOUND` |
| 추이 데이터 부족 | 전월 표본 미달 | `200` + `trend[].status: INSUFFICIENT`, `accuracyRate: null` |
| 약점 분류 불가 | 문항 태그 NULL (§1-4 ④) | `200` + `weakness.status: NO_DATA` |

🔴 `trend` 가 비면 **빈 배열**이다. 하드코딩된 과거 값(현재 프론트 `52/61/68`)을 서버가 만들지 않는다.

### `GET /member/students/me/questions` (목록) / `/{questionId}` (상세)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| 질문 0건 | | `200` + `items: []` |
| 남의 질문 / 없음 | | `404 RESOURCE_NOT_FOUND` |
| 강사 미답변 | | `200` + `status:"WAITING"`, `teacherAnswer` 필드 없음 |

### `POST /member/students/me/questions` — 질문 작성

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `201` |
| 내용 길이 | 1자 미만 / 2000자 초과 | `400 INVALID_REQUEST` |
| 남의 assignment | | `404 RESOURCE_NOT_FOUND` |
| 없는 itemId | 해당 assignment 소속 아님 | `400 INVALID_REQUEST` |
| attempt 미시작 | 참조한 attempt 없음 | `404 RESOURCE_NOT_FOUND` |
| 강사 관계 없음 | assignment 의 강사와 관계 종료 | `422 RELATIONSHIP_REQUIRED` |
| 멱등 재전송 | | §0-4 |

🔴 `teacherId` 를 body 로 받지 않는다. assignment 에서 서버가 결정한다. body 에 `teacherId` 가 오면 `400 INVALID_REQUEST`(`extra field`).

### `POST /member/students/me/questions/{questionId}/messages` — 추가 질문

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `201`, 질문 상태 `ANSWERED` → `FOLLOW_UP` |
| 답변 전 추가 질문 | `status='WAITING'` | 🔴 정책 미확정(MB-09). **잠정: 허용**, 상태 유지 |
| 종료된 질문 | `status='CLOSED'` | `409` |
| 추가 질문 횟수 초과 | 정책 상한 | `409` + `details.limit`. 🔴 상한값은 설정값, 하드코딩 금지 |
| 남의 질문 | | `404 RESOURCE_NOT_FOUND` |

### `GET /member/students/me/profile` · `PATCH /profile/notification-preference`

| 분기 | 조건 | 응답 |
|---|---|---|
| 조회 정상 | | `200` |
| 이름 없음 | `member_display_names` 행 없음 | 🔴 `500 INTERNAL` + 알람. 가입에서 반드시 만들었어야 한다 |
| 연결 강사 0명 | | `200` + `teachers: []` |
| 알림 변경 정상 | | `204` (body 없음) |
| `enabled` 누락 | | `400 INVALID_REQUEST` |

### `POST /member/students/me/invitations/verification` — 초대 검증

| 분기 | 조건 | 응답 |
|---|---|---|
| 유효 | | `200` `{valid:true, teacher, expiresAt}` |
| 없는 코드 | `code_hash` 미일치 | `404 RESOURCE_NOT_FOUND` |
| 만료·폐기 | `expires_at` 경과 / `revoked_at` 있음 | `410 INVITE_EXPIRED` |
| 대상 역할 불일치 | `target_role='PARENT'` 인데 학생이 호출 | `404 RESOURCE_NOT_FOUND` (🔴 역할을 노출하지 않는다) |
| 열거 시도 | 분당 상한 초과 | `429` + `Retry-After` |
| 🔴 대기 학생 | `activationStatus = PENDING_PARENT_LINK` | **`403 STUDENT_ACTIVATION_REQUIRED`** (MB-02) |

🔴 존재/부재의 **응답 시간 차를 만들지 않는다.** 없는 코드도 해시 계산을 수행한다(`LoginService` 의 dummy BCrypt 패턴과 같은 이유).

### `POST /member/students/me/invitations` — 초대 등록

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | | `201` `TeacherSummary` | 강사 목록 갱신 |
| 이미 같은 강사와 연결 (내 계정) | 관계가 이미 ACTIVE | 🔴 **`200`** + 기존 `TeacherSummary` | 오류 아님. 완료 화면 (MB-04 멱등) |
| 같은 코드를 내가 다시 제출 | `member_invitation_claims` 에 내 기록 존재 | 🔴 **`200`** + 기존 `TeacherSummary` | 동일 |
| 🔴 **다른 계정이 이미 쓴 코드** | `max_claims=1` 소진 | `409 INVITE_ALREADY_CLAIMED` | 새 코드 요청 안내 |
| 대기 학생 | `PENDING_PARENT_LINK` | `403 STUDENT_ACTIVATION_REQUIRED` | 활성화 대기 화면 (MB-02) |
| 만료·폐기 | | `410 INVITE_EXPIRED` | |
| 없는 코드 | | `404 RESOURCE_NOT_FOUND` | |

🔴 **"코드 1회 사용"(`member_invitation_claims`)과 "관계 중복"(`teacher_student_relationships`)은 다른 제약**이다. 둘 다 `INVITE_ALREADY_CLAIMED` 로 매핑하되, `details.reason` 으로 구분해 로그에 남긴다. 23505 는 **constraint 이름으로만** 분기한다.

---

## §3. 학부모 자녀 · 홈 · 기록 · 분석 (8)

### `GET /member/parents/me/children`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` `{items:[...]}` |
| 자녀 0명 | | `200` + `items: []` → 자녀 등록 유도 화면 |
| 자녀가 DEACTIVATED | | `200` + 포함하되 `activationStatus` 로 표시 |

### `POST /member/parents/me/children/verification` — 사전 확인

| 분기 | 조건 | 응답 |
|---|---|---|
| 등록 가능 | | `200` `{registrable:true, name:"김*수", grade}` — 🔴 **부분 마스킹 이름만** |
| 이미 다른 학부모 연결 | | `200` `{registrable:false, reason:"ALREADY_LINKED", name:null}` |
| 없는 공개 ID | | `404 RESOURCE_NOT_FOUND` |
| 형식 오류 | 정규화 후에도 `^STU-[A-Z0-9]{6,12}$` 불일치 | `400 INVALID_REQUEST` |
| 열거 시도 | 분당 상한 초과 | `429` + `Retry-After` |

🔴 이 응답은 **안내일 뿐 등록을 보장하지 않는다.** `registrable:true` 를 받고도 등록이 `409` 로 실패할 수 있다(그 사이 다른 학부모가 등록). 프론트는 이걸 정상 흐름으로 처리한다.

### `POST /member/parents/me/children` — 자녀 등록

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | | `201` `{child}`, 학생 `activationStatus` → `ACTIVE` | 완료 화면 + 자녀 목록 갱신 |
| 이미 다른 학부모 연결 | `uq_parent_student_relationships_active_student` 위반 (`V33:96-98`) | `409 CHILD_ALREADY_LINKED` | 안내 화면. 🔴 누구인지 노출하지 않는다 |
| 이미 **내가** 연결 | 같은 parent-student 재등록 | `409 CHILD_ALREADY_LINKED` + `details.alreadyMine:true` | 🔴 성공처럼 처리 — 자녀 목록으로 |
| 없는 공개 ID | | `404 RESOURCE_NOT_FOUND` | |
| 동시 2요청 | 두 학부모가 같은 순간 | 하나만 `201`, 나머지 `409 CHILD_ALREADY_LINKED` | 정상 동작 |
| 멱등 재전송 | | §0-4 | |
| 활성화 전이 실패 | `member_student_activation` UPDATE 실패 | 🔴 **전체 롤백** → `500 INTERNAL` | 재시도 |

### `GET /member/parents/me/children/{studentId}/home`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| 연결 안 된 자녀 | | `404 RESOURCE_NOT_FOUND` |
| 연결 종료된 자녀 | 관계 `ENDED` | `404 RESOURCE_NOT_FOUND`. 🔴 과거 데이터 열람 정책은 MB-08 미확정 |
| `teacherId` 필터 지정했는데 관계 없음 | parent↔teacher **또는** student↔teacher 없음 | `404 RESOURCE_NOT_FOUND` |
| 지표 산출 불가 | | `200` + `metrics[].status: NO_DATA`, `value: null` |
| 발행 보고서 없음 | | `200` + `latestReport: null` |

🔴 `teacherId` 는 필터이지 권한이 아니다. 서버가 **관계 교집합을 매 요청 재검증**한다.

### `GET .../children/{studentId}/learning-records` · `/{recordId}`

학생판(§2)과 동일하되, ④ 판정에 **자녀 연결 검증**이 먼저 온다. 연결되지 않은 자녀의 recordId 는 `404`.

### `GET .../children/{studentId}/analysis?month=`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| `month` 누락 | required | `400 INVALID_REQUEST` |
| `month` 형식 오류 | | `400 INVALID_REQUEST` |
| 미래 월 | | `200` + 전 지표 `status: NO_DATA` (🔴 400 아님) |
| 집계 미생성 | 배치 미실행 | `200` + `status: NO_DATA` + `calculatedAt: null` |
| 표본 부족 | 월 문항 < 최소치 | `200` + `status: INSUFFICIENT`, `accuracyRate: null` |
| 전월 없음 | | `200` + `improvement.status: NO_PREVIOUS_PERIOD`, `accuracyDeltaPp: null` |
| 태그 없는 문항만 | §1-4 ④ | `200` + `weaknessRanking: []`, `primaryWeakness: null` |

🔴 **전국 백분위 필드는 응답에 존재하지 않는다.** 스키마에도 없다.

### `GET .../analysis/weaknesses/{areaTag}/{typeTag}`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| 태그 값 오류 | `areaTag` ∉ 5종 / `typeTag` ∉ 5종 | `400 INVALID_REQUEST` |
| 대문자 입력 | `CONCEPT` | 🔴 `400 INVALID_REQUEST`. 소문자가 정본 |
| 해당 셀 데이터 없음 | | `200` + `status: NO_DATA`, `recentItems: []` |
| 상한으로 잘림 | `recentItems` 가 상한 초과 | `200` + `truncated: {applied:true, limit, orderedBy}` — 🔴 무엇을 왜 잘랐는지 반드시 |

---

## §4. 학부모 보고서 · PDF · 상담 (8)

### `GET .../children/{studentId}/reports` (목록) / `/{reportId}` (상세)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` (🔴 `PUBLISHED` 만) |
| 발행 보고서 0건 | | `200` + `items: []` |
| 미발행 보고서 조회 | `DRAFT`/`REVIEW_READY`/`FAILED` 의 reportId | 🔴 `404 RESOURCE_NOT_FOUND` (존재를 숨긴다) |
| 연결 안 된 강사의 보고서 | | `404 RESOURCE_NOT_FOUND` |
| 섹션 미산출 | 전국 백분위 등 | `200` + `sections[].status: NOT_PRODUCED` + `unproducedReason` |
| PDF 없음 | | `200` + `hasPdf: false` |

### `POST .../reports/{reportId}/file-access` — PDF URL 발급

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | | `201` `{url, expiresAt, contentType, checksum, sizeBytes, pageCount}` | 새 탭 열기 |
| PDF 미생성 | `member_report_files` 행 없음 | `404 RESOURCE_NOT_FOUND` | "준비 중" 안내 |
| 미발행 보고서 | | `404 RESOURCE_NOT_FOUND` | |
| 관계 종료 | 발급 시점 재검증 실패 | `404 RESOURCE_NOT_FOUND` | |
| checksum 불일치 | 스토리지 파일이 변조·손상 | 🔴 `503 DEPENDENCY_UNAVAILABLE` + 알람. **URL 을 주지 않는다** | 재시도 |
| 스토리지 장애 | | `503` / `504` | 재시도 버튼 |
| `pageCount` 미측정 | | `201` + `pageCount: null` (🔴 0 으로 채우지 않는다) | 표시 생략 |

### `GET /member/files/reports/{token}` — 실제 다운로드 (Public 경로, 토큰 인증)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` `application/pdf` + `Content-Disposition` |
| 토큰 만료 | | `404` |
| 토큰 위조·서명 불일치 | | `404` |
| 토큰 유효하나 관계 종료 | 🔴 다운로드 시점 **재검증** | `404` |
| 스토리지 장애 | | `503` / `504` |

🔴 만료·위조·관계종료·부재를 **전부 404 로 통일**한다. 구분하면 토큰 유효성을 탐색당한다.

### `POST /member/parents/me/consultations` — 상담 요청

| 분기 | 조건 | 응답 | 클라이언트 |
|---|---|---|---|
| 정상 | | `201` `{consultationId, status:"SUBMITTED", aiAssistance}` | 접수 완료 화면 |
| 🔴 AI 초안 실패 | Kafka·AI 장애 | **`201`** + `aiAssistance:"UNAVAILABLE"` | 🔴 **오류가 아니다.** 접수 완료 그대로 |
| 🔴 반(class) 없음 | `class_ref` 를 못 만듦 | `201` + `aiAssistance:"NOT_REQUESTED"` | 동일. 더미 alias 금지 |
| 내용 길이 | 1자 미만 / 2000자 초과 | `400 INVALID_REQUEST` | |
| 연결 안 된 자녀 | | `404 RESOURCE_NOT_FOUND` | |
| 강사 관계 없음 | parent↔teacher 또는 student↔teacher 없음 | `422 RELATIONSHIP_REQUIRED` | 강사 등록 유도 |
| `context` 참조 대상 없음 | 지정한 record/report 가 남의 것 | `404 RESOURCE_NOT_FOUND` | |
| 멱등 재전송 | | §0-4 | |

🔴 **요청 저장 트랜잭션 안에서 AI 를 호출하지 않는다.** 저장 → 커밋 → outbox 발행 순서다. AI 실패가 사용자 오류가 되는 경로를 구조적으로 없앤다.

### `GET .../consultations` (목록) / `/{consultationId}` (상세)

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` — 🔴 `published_at` 이 있는 메시지만 |
| 답변 전 | | `200` + `messages` 에 학부모 원문만, `answeredAt: null` |
| AI 초안 존재하나 미승인 | | 🔴 `200` + `messages` 에 **포함되지 않음** |
| 상담 0건 | | `200` + `items: []` |
| 남의 상담 | | `404 RESOURCE_NOT_FOUND` |

### `POST .../consultations/{id}/cancellation` — 취소

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | `status ∈ {SUBMITTED, REVIEWING}` | `200` + `status:"CANCELLED"` |
| 이미 답변 발행됨 | `status='ANSWERED'` | `409` + `details.reason:"ALREADY_ANSWERED"` |
| 이미 취소됨 | | `200` (🔴 멱등 — 409 아님) |
| 종료됨 | `status='CLOSED'` | `409` |
| 남의 상담 | | `404 RESOURCE_NOT_FOUND` |

🔴 취소 가능 시점은 MB-09 미확정. 위 표는 **잠정값**이고, 확정되면 이 표를 먼저 고치고 코드를 고친다.

---

## §5. 학부모 알림 · 프로필 · 초대 (7)

### `GET /member/parents/me/notifications`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `200` |
| 알림 0건 | | `200` + `items: []` |
| 대상 리소스 삭제됨 | `target.resourceId` 가 이제 없음 | `200` + 알림은 유지, 클릭 시 `404` 처리 |

### `POST .../notifications/{notificationId}/read` · `/read-all`

| 분기 | 조건 | 응답 |
|---|---|---|
| 정상 | | `204` |
| 이미 읽음 | | `204` (🔴 멱등) |
| 남의 알림 / 없음 | | `404 RESOURCE_NOT_FOUND` |
| 전체 읽음, 대상 0건 | | `204` (🔴 오류 아님) |

### `GET /member/parents/me/profile` · `PATCH /profile/notification-preference`

학생판(§2)과 동일. 추가:

| 분기 | 조건 | 응답 |
|---|---|---|
| 자녀 0명 | | `200` + `children: []` |
| 연결 강사 0명 | | `200` + `teachers: []` |

### `POST /member/parents/me/invitations/verification` · `/invitations`

학생판(§2)과 동일한 분기. 차이:

| 분기 | 조건 | 응답 |
|---|---|---|
| 대상 역할 불일치 | `target_role='STUDENT'` 인데 학부모가 호출 | `404 RESOURCE_NOT_FOUND` |
| 이미 같은 강사와 연결 | `uq_parent_teacher_relationships_active_pair` 위반 (`V33:61`) | `409 INVITE_ALREADY_CLAIMED` → 성공처럼 처리 |

---

## §6. 이 문서를 어떻게 쓰는가

1. **PR 구현 시**: 담당 엔드포인트의 표를 열고, 표의 모든 행에 대해 통합 테스트를 1:1 로 만든다. 표에 없는 분기를 코드가 만들면 **표를 먼저 고친다**.
2. **프론트 구현 시**(PR10): 각 표의 "클라이언트" 열이 핸들러 명세다. MSW 픽스처는 표의 행 수만큼 만든다.
3. **리뷰 시**: 이 표에 없는 오류 코드가 코드에 나타나면 반려.
4. 🔴 **잠정 표시된 행**(MB-05 미응답 제출 · MB-08 관계 종료 후 열람 · MB-09 상담 취소·추가질문)은 확정 전까지 구현하지 말고, 확정되면 **표 → 코드 → 테스트** 순으로 고친다.

## §7. 미확정이 걸린 분기 (구현 전 확정 필요)

✅ **확정됨 (2026-08-25)** — MB-01 학생은 공개 학생 ID 로그인 · MB-02 대기 학생 3개만 · MB-04 역할지정 1회용 7일 + 동일 강사 재등록은 200 멱등. 아래는 **아직 안 정해진 것**만이다.

| 분기 | 미확정 ID | 잠정값 |
|---|---|---|
| 미응답 문항 있어도 제출 가능한가 | MB-05 | 허용(=`422 SUBMISSION_INCOMPLETE` 안 씀) |
| 관계 종료 후 과거 기록·보고서 열람 | MB-08 | 불가(404) |
| 상담 취소 가능 시점 / 추가 질문 횟수 | MB-09 | §4 표의 잠정값 |
| progress delta 상한 | MB-06 | 항목당 600초, 저장 주기 30초 |
| 월 최소 표본 / 월 경계 timezone | MB-07 | 10문항 / `Asia/Seoul` |

---

## §8. 커버리지 색인 — 46개 전수

🔴 이 표가 **기계 검증의 기준**이다. `member-api.yaml` 의 오퍼레이션 수와 이 표의 행 수가 다르면 둘 중 하나가 뒤처진 것이다.
일부 오퍼레이션은 §1~§5 에서 `GET .../x` · `PATCH .../y` 처럼 **묶어서** 다뤄진다. 묶였어도 분기는 각각 적용된다.

| 절 | Method | Path | 요약 |
|---|---|---|---|
| §1 | `POST` | `/member/auth/parents/sign-up` | 학부모 가입 |
| §1 | `GET` | `/member/auth/session` | 앱 bootstrap — 현재 계정·프로필·활성화 상태 |
| §1 | `GET` | `/member/auth/students/activation-status` | 학생 활성화 상태 (대기 화면 폴링용) |
| §1 | `POST` | `/member/auth/students/login` | 학생 로그인 (공개 학생 ID + 비밀번호) |
| §1 | `POST` | `/member/auth/students/sign-up` | 학생 가입 |
| §1 | `GET` | `/member/students/me/attempts/{attemptId}` | attempt 조회 (복귀 시 상태 복원) |
| §1 | `PATCH` | `/member/students/me/attempts/{attemptId}/progress` | 답안·풀이시간 자동 저장 |
| §1 | `GET` | `/member/students/me/attempts/{attemptId}/result` | 채점 결과 (정답·해설 포함) |
| §1 | `POST` | `/member/students/me/attempts/{attemptId}/submission` | 제출 + 서버 채점 |
| §1 | `GET` | `/member/students/me/worksheets` | 배정 학습지 목록 |
| §1 | `GET` | `/member/students/me/worksheets/{assignmentId}` | 학습지 상세 (문항 본문 없음) |
| §1 | `POST` | `/member/students/me/worksheets/{assignmentId}/attempts` | 풀이 시작 또는 재개 |
| §2 | `GET` | `/member/students/me/home` | 학생 홈 |
| §2 | `POST` | `/member/students/me/invitations` | 강사 초대 등록 (대기 학생도 호출 가능) |
| §2 | `POST` | `/member/students/me/invitations/verification` | 강사 초대코드 검증 (대기 학생도 호출 가능) |
| §2 | `GET` | `/member/students/me/learning-records` | 학습기록 목록 |
| §2 | `GET` | `/member/students/me/learning-records/{recordId}` | 학습기록 상세 |
| §2 | `GET` | `/member/students/me/profile` | 학생 내 정보 |
| §2 | `PATCH` | `/member/students/me/profile/notification-preference` | 알림 설정 변경 |
| §2 | `GET` | `/member/students/me/questions` | 질문 목록 |
| §2 | `POST` | `/member/students/me/questions` | 문제 질문 작성 |
| §2 | `GET` | `/member/students/me/questions/{questionId}` | 질문 상세 |
| §2 | `POST` | `/member/students/me/questions/{questionId}/messages` | 추가 질문 |
| §3 | `GET` | `/member/parents/me/children` | 자녀 목록 |
| §3 | `POST` | `/member/parents/me/children` | 자녀 등록 |
| §3 | `POST` | `/member/parents/me/children/verification` | 자녀 사전 확인 (안내용) |
| §3 | `GET` | `/member/parents/me/children/{studentId}/analysis` | 고급 분석 (차트 원시 series) |
| §3 | `GET` | `/member/parents/me/children/{studentId}/analysis/weaknesses/{areaTag}/{typeTag}` | 취약 영역 상세 |
| §3 | `GET` | `/member/parents/me/children/{studentId}/home` | 자녀 홈 요약 |
| §3 | `GET` | `/member/parents/me/children/{studentId}/learning-records` | 자녀 학습기록 목록 |
| §3 | `GET` | `/member/parents/me/children/{studentId}/learning-records/{recordId}` | 자녀 학습기록 상세 |
| §4 | `GET` | `/member/files/reports/{token}` | 보고서 PDF 다운로드 (signed URL 의 착지점) |
| §4 | `GET` | `/member/parents/me/children/{studentId}/consultations` | 상담 목록 |
| §4 | `GET` | `/member/parents/me/children/{studentId}/consultations/{consultationId}` | 상담 상세 |
| §4 | `POST` | `/member/parents/me/children/{studentId}/consultations/{consultationId}/cancellation` | 상담 취소 |
| §4 | `GET` | `/member/parents/me/children/{studentId}/reports` | 발행된 보고서 목록 |
| §4 | `GET` | `/member/parents/me/children/{studentId}/reports/{reportId}` | 보고서 상세 (발행 스냅샷) |
| §4 | `POST` | `/member/parents/me/children/{studentId}/reports/{reportId}/file-access` | PDF 열람 URL 발급 |
| §4 | `POST` | `/member/parents/me/consultations` | 상담 요청 |
| §5 | `POST` | `/member/parents/me/invitations` | 강사 초대 등록 |
| §5 | `POST` | `/member/parents/me/invitations/verification` | 강사 초대코드 검증 |
| §5 | `GET` | `/member/parents/me/notifications` | 알림 목록 |
| §5 | `POST` | `/member/parents/me/notifications/read-all` | 전체 읽음 처리 |
| §5 | `POST` | `/member/parents/me/notifications/{notificationId}/read` | 알림 읽음 처리 |
| §5 | `GET` | `/member/parents/me/profile` | 학부모 내 정보 |
| §5 | `PATCH` | `/member/parents/me/profile/notification-preference` | 알림 설정 변경 |

합계 **46개**.
