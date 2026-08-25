# MEMBER 오류 코드 정본

작성 2026-08-25 (KST) · 대상 `com.checkon.member` 경계

이 표가 PR1 의 `member/common/error/MemberErrorCode` enum 과 프론트 분기의 **유일한 정본**이다.
계약 파일 `src/main/resources/openapi/member-api.yaml` 의 `MemberErrorCode` enum 과 **항목이 정확히 일치해야 한다.**

기존 백엔드는 컨트롤러마다 문자열 리터럴을 쓰지만, member 는 프론트가 분기해야 하므로 enum 으로 고정한다.

## 전수 (21개)

| HTTP | code | 언제 | 프론트 처리 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 검증 실패. `details` 에 필드별 위반 | 필드 오류 표시 |
| 401 | `AUTHENTICATION_REQUIRED` | 토큰 없음·만료·세션 폐기 | refresh 1회 → 실패 시 로그인 |
| 401 | `INVALID_CREDENTIALS` | 학생 로그인 실패. 공개 ID 부재·비밀번호 불일치·계정 미연결을 **구분하지 않는다** (더미 해시로 BCrypt 시간을 태워 공개 ID 열거를 막는다) | 로그인 실패 안내. 어느 쪽이 틀렸는지 노출 금지 |
| 401 | `ACCOUNT_NOT_ACTIVE` | 계정이 정지·탈퇴 상태 | 계정 상태 안내 |
| 403 | `ROLE_FORBIDDEN` | 학생 앱이 학부모 API 호출 등 | 잘못된 앱 안내 |
| 403 | `STUDENT_ACTIVATION_REQUIRED` | 대기 학생이 제한 API 호출 | 활성화 대기 화면 |
| 404 | `RESOURCE_NOT_FOUND` | 부재 **및** 권한 없음 | 동일 처리 |
| 409 | `EMAIL_ALREADY_EXISTS` | 가입 중복 | 로그인 유도 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 key + 다른 body | **자동 재시도 금지** |
| 409 | `REVISION_CONFLICT` | attempt `baseVersion` 불일치 | 재조회 후 병합 |
| 409 | `CHILD_ALREADY_LINKED` | 이미 활성 학부모가 있는 학생 | 안내 |
| 409 | `ATTEMPT_ALREADY_SUBMITTED` | 제출된 attempt 에 progress/submit | 결과 화면으로 |
| 409 | `INVITE_ALREADY_CLAIMED` | 같은 초대 재사용 | 기존 관계 재조회 = 성공 취급 |
| 410 | `INVITE_EXPIRED` | 만료·폐기 | 새 코드 요청 |
| 422 | `SUBMISSION_INCOMPLETE` | 미응답 금지 정책일 때만 | 미응답 문항 하이라이트 |
| 422 | `RELATIONSHIP_REQUIRED` | 강사/자녀 관계 필요 | 등록 유도 |
| 422 | `WORKSHEET_NOT_GRADABLE` | 문항 스냅샷의 correctNo · areaTag · typeTag · skillNodeId 중 하나라도 null (설계 §1-4 ⑥). 판정은 attempt 시작 시점 | 강사에게 문의 안내. `details` 에 itemId 목록 |
| 429 | `RATE_LIMITED` | 공개 ID 검증·초대 검증 | `Retry-After` |
| 500 | `INTERNAL` | 그 밖의 서버 오류. 🔴 `message` 에 예외 내용·스택·쿼리를 담지 않는다 | 자동 재시도 금지. `X-Request-Id` 를 노출해 문의 유도 |
| 503 | `DEPENDENCY_UNAVAILABLE` | 하위 의존 실패 | 캐시 유지 + 재시도 |
| 504 | `DEPENDENCY_TIMEOUT` | 타임아웃 | 비동기 상태 조회로 전환 |

🔴 AI 의 template_only · rejected_insufficient · no_data 는 **5xx 가 아니다.**
`CheckOn-AI` 의 불변식 4번("게이트 반려는 오류가 아니다")과 일치시킨다.
백엔드는 정상 도메인 상태로 저장하고 사용자 문구로 매핑한다.

## 🔴 설계 정본 §7-2 와의 차이 — 이 문서가 정본이다

설계 정본 `docs/MEMBER_DESIGN.md` §7-2 표에는 **18개**만 있고, 계약 파일의 enum 에는 **21개**가 있다.
아래 3개가 §7-2 표에서 누락돼 있었다. 근거는 전부 계약 파일 본문이며 **지어낸 값이 아니다.**

| 코드 | HTTP | 근거 (`member-api.yaml`) |
|---|---|---|
| `INVALID_CREDENTIALS` | 401 | `/member/auth/students/login` 의 `'401'` 설명 · 본문 "더미 해시로 BCrypt 시간을 태운 뒤 존재하는 경우와 같은 401" |
| `ACCOUNT_NOT_ACTIVE` | 401 | 같은 `'401'` 설명 — "계정이 정지·탈퇴 상태" |
| `INTERNAL` | 500 | `components.responses.InternalError` — "500. message 에 예외 내용·스택·쿼리를 담지 않는다" |

설계 §7-2 표를 이 표에 맞춰 갱신하는 것은 별도 작업으로 둔다(이 PR 은 `MEMBER_DESIGN.md` 를 원본 그대로 옮긴다).
