# CheckOn 프론트엔드·백엔드 협업 가이드

- 기준 브랜치: `dev`
- 기준일: 2026-08-24
- API 정본: `src/main/resources/openapi/dashboard-api.yaml`
- 개발 Swagger UI: `http://localhost:8080/swagger-ui.html`
- 개발 API base URL: `http://localhost:8080/api/v1`

이 문서는 프론트엔드 개발자와 백엔드 개발자가 면대면으로 API를 연결할 때 확인할 단일 체크리스트다.
화면 정책을 새로 결정하는 문서가 아니며, 현재 구현·계약·외부 시스템 의존성을 구분한다.

## 1. 현재 준비도 결론

백엔드는 아래 44개 운영 API에 대해 Controller와 OpenAPI 경로가 정확히 일치하며, 인증·CORS·오류·비동기
조회 경계를 제공한다. 따라서 아래의 **연동 가능 범위**는 프론트 개발자가 공통 API 클라이언트를 만든 뒤
화면에 연결할 수 있다.

다만 전체 제품 화면이 모두 구현된 것은 아니다. **미구현·결정 필요 범위**는 임시 API나 추정 필드로
연결하지 않는다. AI 기능은 Backend만 실행한다고 완료되지 않으며 Kafka Adapter와 AI 서버가 함께 떠야
실제 terminal 결과까지 확인할 수 있다.

### 연동 가능 범위

- 강사 회원가입, 로그인, Refresh, 로그아웃
- 대시보드 브리핑·주간 캘린더
- 클래스 목록·등록·상세·수정·보관
- 학생 실명 등록·수정, 휴원·복귀
- 단건 학습 기록 등록
- Detection 실행 요청·최근 실행·단건 상태 조회
- Alert 목록·상세·승인·거절, Intervention·Reminder 상태 변경, Todo 완료
- 문제 출제 요청 조회와 Problem Studio Step 1~4
- 상담 초안 생성·조회·다듬기·발송 표시, 문의 분류·확정

### 외부 시스템까지 있어야 완료되는 범위

| 기능 | Backend 역할 | 추가 실행 조건 |
|---|---|---|
| Detection | 요청·Snapshot·Outbox·결과 저장·상태 조회 | Kafka, `checkon-kafka-adapter`, AI Detection HTTP |
| Problem Studio 생성 | 요청·child Outbox·결과 projection·검토/저장/발행 | Kafka, 문제 출제 Adapter, AI Problem HTTP |
| 상담 초안 | 요청·Outbox·결과 저장·조회 | Kafka 또는 설정된 Adapter/AI 실행 경로 |
| 문의 분류 | 요청 검증·저장·확정 | Classify Adapter/AI HTTP |

### 미구현·결정 필요 범위

- 일반 학생 등록·전체 학생 목록·검색·반 이동 등 Roster 전체 관리
- CSV/엑셀 Import 프로파일링·매핑·확정·저장
- 학부모 360 전용 API, 보호자 계정·초대 흐름
- 상담 일정 관리와 실제 메시지 발송
- 설정 화면 전용 API
- 학생용 과제 목록·풀이·제출·채점
- 문항 직접 수정·교체·삭제와 revision 이력
- 서버 PDF 파일 생성: Backend는 인쇄용 구조화 데이터만 반환하고 PDF 렌더링은 프론트가 담당

이 범위는 일부 정책이 `OPEN`이거나 현재 MVP에서 제외됐다. 프론트 화면에 필요하면 구현 전에 요청·응답과
상태 전이를 먼저 합의한다.

## 2. 개발 환경 합의

백엔드 실행 전 다음 값을 확인한다.

| 항목 | 로컬 개발값 | 배포 시 주의 |
|---|---|---|
| Spring profile | `dev` | 운영에서 dev 테스트 인증 금지 |
| API base URL | `http://localhost:8080/api/v1` | 환경별 HTTPS Backend URL 사용 |
| `AUTH_ALLOWED_ORIGINS` | 예: `http://localhost:5173` | 프론트 Origin을 경로 없이 정확히 등록 |
| `REFRESH_COOKIE_SECURE` | `false` | HTTPS 배포는 `true` |
| `REFRESH_COOKIE_SAME_SITE` | `Lax` | cross-site HTTPS면 `None`과 `Secure=true` 조합 검토 |
| `OPENAPI_ENABLED` | `true` | 기본값은 `false`; 필요한 개발 환경에서만 활성화 |

`AUTH_ALLOWED_ORIGINS`에는 `https://front.example.com/path`처럼 경로를 넣지 않는다. 스킴·호스트·선택적
포트만 포함하며 여러 값은 쉼표로 구분한다. 허용 Origin은 와일드카드가 아니다.

개발 프로필에서 테스트 인증이 켜져 있고 `Authorization` 헤더가 완전히 없으면 서버의 고정 TEACHER
principal을 사용할 수 있다. 빈 Bearer나 잘못된 Bearer를 보내면 실제 JWT 검증 경로로 들어가므로 테스트
인증으로 대체되지 않는다. 로그인 흐름을 검증할 때는 반드시 실제 Access Token을 사용한다.

## 3. 프론트 공통 API 클라이언트

### 인증 흐름

1. `POST /auth/login`에 `credentials: 'include'` 또는 Axios `withCredentials: true`를 사용한다.
2. 응답 본문의 `data.accessToken`, `data.accessTokenExpiresAt`, `data.account`를 저장한다.
3. 보호 API에는 `Authorization: Bearer <accessToken>`을 붙인다.
4. Access Token 만료 시 `POST /auth/refresh`를 credential 요청으로 한 번만 호출한다.
5. 성공하면 새 Access Token으로 원래 요청을 한 번 재시도한다.
6. Refresh가 `401`이면 인증 상태를 지우고 로그인 화면으로 이동한다.
7. `POST /auth/logout`은 Bearer와 credential 옵션을 모두 사용하고, 성공 여부와 관계없이 로컬 인증 상태를 정리한다.

Refresh Token은 JavaScript에서 읽을 수 없는 HttpOnly 쿠키다. 응답 JSON이나 로컬 스토리지에서 찾지 않는다.
동시에 여러 API가 `401`이어도 Refresh 요청은 single-flight로 한 번만 실행하고 나머지는 같은 결과를 기다린다.

### CORS 허용 범위

- 메서드: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`
- 요청 헤더: `Authorization`, `Content-Type`, `Idempotency-Key`
- 응답에서 읽을 수 있는 추가 헤더: `Location`
- credential 요청: 허용

새 커스텀 헤더가 필요하면 프론트에서 먼저 임의 추가하지 말고 백엔드와 CORS 계약을 합의한다.

### 공통 오류 처리

인증·인가와 업무 오류는 기본적으로 다음 형식을 사용한다.

```json
{
  "code": "UNAUTHORIZED",
  "message": "인증이 필요합니다."
}
```

- `401 UNAUTHORIZED`: Access Token이 없거나 유효하지 않음. Refresh 후보
- `401 SESSION_INVALID`: Refresh 실패. 로그인 화면으로 이동
- `403 FORBIDDEN`: 인증됐지만 TEACHER 권한이 없음. Refresh로 해결하지 않음
- `400`: 입력·형식 오류. `code`에 따라 필드 또는 화면 메시지 표시
- `404`: 없음과 다른 테넌트 접근을 구분하지 않음
- `409`: 멱등 키 충돌 또는 허용되지 않는 상태 전이
- `502`: Adapter/AI 연동 실패

회원가입 검증 오류만 `fieldErrors[]`가 추가될 수 있다. 화면 분기는 HTTP 상태만 사용하지 말고 `code`를 함께
사용한다. 서버의 `message`는 사용자 표시가 가능하지만 알려지지 않은 `code`에는 공통 문구를 사용한다.

### 멱등성과 비동기 요청

`Idempotency-Key`가 필요한 API는 동일 사용자 동작의 네트워크 재시도 동안 같은 키를 재사용한다. 새로운
사용자 동작에는 새 키를 만든다. 같은 키에 다른 요청 본문을 보내면 `409`다.

`202 Accepted`는 최종 성공이 아니다. 응답의 `Location`을 우선 사용해 상태를 조회하고 terminal 상태까지
화면에 진행 상태를 표시한다.

| 시작 API | 상태 조회 |
|---|---|
| `POST /detection-runs` | 응답 `Location` 또는 `GET /detection-runs/{runId}` |
| `POST /problem-requests` | 응답 `Location` 또는 `GET /problem-requests/{requestId}` |
| `POST /problem-studio/requests` | 응답 `Location`의 공통 요청 조회 후, 완료 시 `/problem-studio/requests/{requestId}/review` |
| `POST /counsel/drafts` | 응답 `Location` 또는 `GET /counsel/drafts/{jobId}` |

Polling 간격과 최대 대기 시간은 화면 UX와 함께 합의한다. 서버가 `Retry-After`를 반환하면 그 값을 우선한다.
페이지를 벗어나면 polling을 취소하고 다시 진입할 때 저장된 ID로 상태를 복원한다.

### 페이지네이션

- 요청: `page`는 0부터 시작, 기본 0
- 요청: `size` 기본 20, 허용 범위 1~100
- 응답: 최상위 `metadata`, `items`
- 화면 행 번호: `metadata.pageNumber * metadata.pageSize + index + 1`

## 4. 운영 API 44개

### Authentication · 4개

- `POST /auth/sign-up/teachers`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`

### Dashboard · 2개

- `GET /dashboard/briefing`
- `GET /dashboard/calendar`

### Class·Roster · 8개

- `GET /classes`
- `POST /classes`
- `GET /classes/{classId}`
- `PATCH /classes/{classId}`
- `POST /classes/{classId}/archive`
- `PUT /students/{studentId}/personal-information/name`
- `POST /students/{studentId}/pause`
- `POST /students/{studentId}/return`

### Learning Records · 1개

- `POST /learning-records`

### Detection · 3개

- `POST /detection-runs`
- `GET /detection-runs/latest`
- `GET /detection-runs/{runId}`

### Engagement·Todo · 10개

- `GET /engagement/alerts`
- `GET /engagement/alerts/{alertId}`
- `POST /engagement/alerts/{alertId}/approval`
- `POST /engagement/alerts/{alertId}/rejection`
- `POST /engagement/alerts/{alertId}/interventions`
- `POST /engagement/interventions/{interventionId}/completion`
- `POST /engagement/interventions/{interventionId}/cancellation`
- `POST /engagement/reminders/{reminderId}/completion`
- `POST /engagement/reminders/{reminderId}/cancellation`
- `PATCH /todos/{todoId}`

### Problem Generation·Studio · 10개

- `POST /problem-requests`
- `GET /problem-requests/{requestId}`
- `GET /problem-studio/students`
- `GET /problem-studio/students/{studentId}/weakness-analysis`
- `POST /problem-studio/requests`
- `GET /problem-studio/requests/{requestId}/review`
- `PUT /problem-studio/requests/{requestId}/selection`
- `POST /problem-studio/requests/{requestId}/save`
- `POST /problem-studio/requests/{requestId}/publish`
- `GET /problem-studio/requests/{requestId}/printable`

### Counsel · 6개

- `POST /counsel/drafts`
- `GET /counsel/drafts/{jobId}`
- `POST /counsel/drafts/{jobId}/refine`
- `POST /counsel/drafts/{jobId}/sent`
- `POST /counsel/inquiries/{inquiryRef}/classify`
- `POST /counsel/inquiries/{inquiryRef}/confirmation`

요청·응답 필드, enum, nullable, 예제, 오류 코드는 Swagger를 정본으로 본다. 생성된 ZIP·분할 JSON은 Git에
커밋하지 않는다. 별도 전달이 필요하면 비공개 채널에서 일회성으로 공유한다.

## 5. 화면별 연결 순서

### 기본 Smoke Test

1. Swagger UI와 `/openapi/dashboard-api.yaml`이 개발 환경에서 열리는지 확인한다.
2. 프론트 Origin으로 로그인하고 `Set-Cookie`가 브라우저에 저장되는지 확인한다.
3. Access Token으로 대시보드 브리핑을 호출한다.
4. Refresh 후 기존 요청을 다시 호출한다.
5. 로그아웃 후 Refresh가 `401 SESSION_INVALID`인지 확인한다.
6. 허용 목록에 없는 Origin이 CORS에서 차단되는지 확인한다.

### 기능 연결 권장 순서

1. 인증 공통 클라이언트
2. 클래스 조회·관리
3. 기존 테스트 학생 기반 대시보드·Alert·Todo
4. 학습 기록 등록 후 Detection 요청·상태 조회
5. Alert 검토·Intervention·Reminder
6. Problem Studio
7. Counsel

AI 기능을 먼저 연결하면 인프라 실패와 프론트 오류를 구분하기 어렵다. 인증·클래스·대시보드의 동기 API로
공통 클라이언트를 먼저 검증한다.

## 6. 면대면 미팅 체크리스트

### 시작 전에 준비할 값

- [ ] 프론트 개발 Origin과 포트
- [ ] Backend base URL
- [ ] 개발 테스트 Account/TeacherProfile 또는 실제 가입 계정
- [ ] Kafka·Adapter·AI 실행 여부
- [ ] 연결할 첫 화면과 필요한 시드 데이터

### 함께 결정할 항목

- [ ] Access Token 저장 위치와 앱 재시작 시 복원 방식
- [ ] Refresh single-flight 구현 위치
- [ ] 공통 오류 `code`와 사용자 문구 매핑
- [ ] 비동기 polling 간격·최대 대기·페이지 이탈 처리
- [ ] 날짜 표시 시간대: 서버 기준 `Asia/Seoul`, 전송 시간은 ISO-8601
- [ ] nullable 값의 빈 상태 UI
- [ ] Problem Studio PDF 렌더링·폰트 책임
- [ ] 현재 미구현 화면을 숨길지 비활성화할지

### 문제 발생 시 전달할 정보

- 요청 시각과 화면 이름
- HTTP method와 path. 비밀번호·Token·쿠키 값은 제거
- HTTP status, 응답 `code`, `message`
- 브라우저 Origin과 preflight 여부
- 비동기 요청이면 `requestId`, `runId`, `jobId` 중 해당 식별자
- 재시도 여부와 사용한 `Idempotency-Key`가 동일했는지 여부

## 7. 완료 기준과 검증 경계

백엔드 자동 검증은 다음을 확인한다.

- Controller와 OpenAPI 운영 API 44개 정확히 일치
- operationId 중복·summary·tag 누락 없음
- 보호 API의 `401`, `403` 계약
- 모든 `202` 응답의 `Location`
- 실제 HTTP 회원가입→로그인→보호 API→Refresh→Logout 흐름
- 허용·거절 Origin, preflight, credential, `Location` 노출
- Swagger UI와 정적 OpenAPI의 개발 환경 노출, 비활성 환경 미노출
- Redocly·Swagger 표준 검증

이 자동 검증은 실제 프론트 저장소의 UI 연결, 배포 도메인, 실제 AI terminal 결과를 증명하지 않는다. 최종
협업 완료는 프론트 개발자와 같은 환경에서 기본 Smoke Test를 한 번 통과했을 때 선언한다.
