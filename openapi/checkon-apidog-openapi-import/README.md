# CheckOn 프론트엔드용 Apidog OpenAPI

`src/main/resources/openapi/dashboard-api.yaml`을 정본으로 생성한 Frontend -> Backend 전용 패키지다.
전체를 한 번에 가져오려면 `00-checkon-frontend.openapi.json` 하나만 import한다.

| 파일 | 범위 | API 수 |
|---|---|---:|
| `01-frontend-backend-auth.openapi.json` | Authentication | 4 |
| `02-frontend-backend-dashboard-engagement.openapi.json` | Dashboard, Engagement, Todo | 12 |
| `03-frontend-backend-roster-class.openapi.json` | Roster Support, Class Management | 8 |
| `04-frontend-backend-learning.openapi.json` | Learning Records | 1 |
| `05-frontend-backend-problem-studio.openapi.json` | Problem Generation, Problem Studio | 10 |
| `06-frontend-backend-detection.openapi.json` | Detection | 3 |
| `07-frontend-backend-counsel.openapi.json` | Counsel Draft and Inquiry Classification | 6 |

## 프론트 호출 규칙

- 서버 URL: `http://localhost:8080/api/v1`
- 보호 API: `Authorization: Bearer <accessToken>`
- 로그인·Refresh·Logout: `credentials: include` 또는 `withCredentials: true`
- 페이지 요청은 0-based이며 목록 응답은 최상위 `metadata`, `items`다.
- 202 응답의 `Location` 헤더를 읽어 polling 경로로 사용한다.

## 검증

- Controller와 실행 OpenAPI의 운영 API 44개가 정확히 일치한다.
- 7개 분할 파일에 44개 API를 중복 없이 한 번씩 배정했다.
- 단일 통합 파일에도 44개 API가 모두 포함되어 있다.
- operationId 중복이 없고 Redocly 표준 검증을 통과했다.
- 실제 Apidog UI import는 프론트 개발자가 연결한 워크스페이스에서 최종 확인한다.
