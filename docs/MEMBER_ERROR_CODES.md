# MEMBER 오류 코드 정본

작성 2026-08-25 (KST) · 대상 `com.checkon.member` 경계

이 표가 PR1 의 `member/common/error/MemberErrorCode` enum 과 프론트 분기의 **유일한 정본**이다.
계약 파일 `src/main/resources/openapi/member-api.yaml` 의 `MemberErrorCode` enum 과 **항목이 정확히 일치해야 한다**
(PR0 검사 #3 이 diff 로 강제한다).

내용은 설계 정본 `docs/MEMBER_DESIGN.md` §7-2 를 그대로 옮긴 것이다. 둘이 갈리면 §7-2 가 정본이다.

`member/common/error/MemberErrorCode` **enum 한 곳**에 모은다. (기존 백엔드는 컨트롤러마다 문자열 리터럴을 쓰지만, member는 프론트가 분기해야 하므로 enum으로 고정한다.)

| HTTP | code | 언제 | 프론트 처리 |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 검증 실패. `details`에 필드별 위반 | 필드 오류 표시 |
| 401 | `AUTHENTICATION_REQUIRED` | 토큰 없음·만료·세션 폐기 | refresh 1회 → 실패 시 로그인 |
| 401 | `INVALID_CREDENTIALS` | 로그인 실패. 🔴 **공개 학생 ID 가 없을 때도 같은 코드**다 — 더미 해시로 BCrypt 시간을 태워 열거를 막는다(`LoginService:31-32` 패턴) | "아이디 또는 비밀번호가 올바르지 않습니다" 하나로만 |
| 401 | `ACCOUNT_NOT_ACTIVE` | 계정 정지·탈퇴 | 고객센터 안내 |
| 403 | `ROLE_FORBIDDEN` | 학생 앱이 학부모 API 호출 등 | 잘못된 앱 안내 |
| 403 | `STUDENT_ACTIVATION_REQUIRED` | 대기 학생이 제한 API 호출 | 활성화 대기 화면 |
| 404 | `RESOURCE_NOT_FOUND` | 부재 **및** 권한 없음 | 동일 처리 |
| 409 | `EMAIL_ALREADY_EXISTS` | 가입 중복 | 로그인 유도 |
| 409 | `IDEMPOTENCY_CONFLICT` | 같은 key + 다른 body | **자동 재시도 금지** |
| 409 | `REVISION_CONFLICT` | attempt `baseVersion` 불일치 | 재조회 후 병합 |
| 409 | `CHILD_ALREADY_LINKED` | 이미 활성 학부모가 있는 학생 | 안내 |
| 409 | `ATTEMPT_ALREADY_SUBMITTED` | 제출된 attempt에 progress/submit | 결과 화면으로 |
| 409 | `INVITE_ALREADY_CLAIMED` | 같은 초대 재사용 | 기존 관계 재조회 = 성공 취급 |
| 410 | `INVITE_EXPIRED` | 만료·폐기 | 새 코드 요청 |
| 422 | `SUBMISSION_INCOMPLETE` | 미응답 금지 정책일 때만 | 미응답 문항 하이라이트 |
| 422 | `RELATIONSHIP_REQUIRED` | 강사/자녀 관계 필요 | 등록 유도 |
| 422 | `WORKSHEET_NOT_GRADABLE` | 🔴 **조건이 바뀌었다**(§1-4 ③④ 해소). 정답 텍스트 역산이 아니라 — `correctNo`·`areaTag`·`typeTag`·`skillNodeId` 중 **하나라도 null** 인 문항이 있을 때 | 강사에게 문의 안내. `details`에 itemId 목록 |
| 429 | `RATE_LIMITED` | 공개 ID 검증·초대 검증 | `Retry-After` |
| 500 | `INTERNAL` | 분류되지 않은 서버 오류 (`components.responses.InternalError`) | 재시도 유도. 🔴 스택·원인 문자열을 내려보내지 않는다 |
| 503 | `DEPENDENCY_UNAVAILABLE` | 하위 의존 실패 | 캐시 유지 + 재시도 |
| 504 | `DEPENDENCY_TIMEOUT` | 타임아웃 | 비동기 상태 조회로 전환 |

🔴 **21개다.** 이전 판은 18개였고 `INVALID_CREDENTIALS`·`ACCOUNT_NOT_ACTIVE`·`INTERNAL` 셋이 빠져 있었다 — 계약(`member-api.yaml`)에는 처음부터 있었다. PR0 완료 보고 반증 ②에서 잡혔다. 이 표와 `member-api.yaml` 의 `MemberErrorCode` enum 은 **항상 같은 개수**여야 한다(PR0 검사 #3 이 diff 로 강제한다).

🔴 AI의 `template_only` · `rejected_insufficient` · `no_data`는 **5xx가 아니다**. `CheckOn-AI` `CLAUDE.md:15` 불변식 4번("게이트 반려는 오류가 아니다")과 일치시킨다. 백엔드는 정상 도메인 상태로 저장하고 사용자 문구로 매핑한다.

## 계약과의 정합

`member-api.yaml` 의 `MemberErrorCode` enum 과 이 표는 **둘 다 21개**로 일치한다.

이전 판 §7-2 는 18개였고 `INVALID_CREDENTIALS` · `ACCOUNT_NOT_ACTIVE` · `INTERNAL` 셋이 빠져 있었다.
계약에는 처음부터 있었고 PR0 완료 보고 반증 ②에서 잡혀 설계 §7-2 를 21개로 올렸다.
