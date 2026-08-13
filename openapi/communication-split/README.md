# CheckOn OpenAPI communication split

원본 Apidog 통합 export를 호출자와 수신자 기준으로 먼저 나누고, Frontend -> Backend 계약은 업무 파트별로 다시 분리했다.
각 JSON은 필요한 component만 재귀적으로 포함하며 Apidog 개별 import용 독립 OpenAPI 문서로 구성했다.
전체를 한 번에 가져오려면 `00-checkon-all.openapi.json` 하나만 import한다.

| 파일 | 통신 방향 | 서버 URL | 범위 | API 수 |
|---|---|---|---|---:|
| `01-frontend-backend-auth.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Authentication | 4 |
| `02-frontend-backend-dashboard-engagement.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Dashboard, Engagement, Todo | 12 |
| `03-frontend-backend-roster-class.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Roster Support, Class Management | 6 |
| `04-frontend-backend-learning.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Learning Records | 1 |
| `05-frontend-backend-problem-studio.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Problem Generation, Problem Studio | 10 |
| `06-frontend-backend-detection.openapi.json` | Frontend -> Backend | `http://localhost:8080/api/v1` | Detection | 2 |
| `07-backend-ai-risk-detection.openapi.json` | Backend -> AI Server | `http://localhost:8000` | Risk Detection | 1 |
| `08-backend-ai-counsel.openapi.json` | Backend -> AI Server | `http://localhost:8000` | Counsel Draft | 1 |
| `09-development-backend-detection.openapi.json` | Apidog or Development Tool -> Backend (dev profile only) | `http://localhost:8080` | Development Detection Control | 2 |
| `90-review-required-legacy.openapi.json` | Unconfirmed -> Backend | `http://localhost:8080` | Legacy or duplicate paths requiring owner review | 3 |

## 반드시 확인할 항목

- `90-review-required-legacy.openapi.json`에는 소유 또는 표준 경로가 확정되지 않은 3개 API를 격리했다.
- `GET /`는 설명상 브리핑 API지만 표준 `GET /dashboard/briefing`과 역할이 겹친다.
- `/api/v1/detection-runs*`는 `/detection-runs*`와 역할 및 일부 operationId가 겹치지만 security와 응답 정의가 다르다.
- 원본에는 전역 `servers`가 없어 현재 저장소의 Controller 매핑과 로컬 설정을 기준으로 Backend `http://localhost:8080`, AI `http://localhost:8000`을 추가했다.
- `POST /v1/counsel/drafts` 설명에는 후속 GET/refine API와 Kafka 완료 이벤트가 언급되지만 원본 paths에는 포함되어 있지 않다.
- 원본 export의 OpenAPI 3.0 비호환 nullable 표기와 잘못 들어간 schema-shaped example은 Apidog import 호환 형태로 정규화했다.

## 검증

- 원본 API 42개를 중복 없이 정확히 한 파일에만 배정했다.
- 단일 통합 파일에도 원본 API 42개가 모두 포함되어 있다.
- 모든 결과 JSON을 다시 파싱했다.
- 각 파일 안에서 operationId 중복과 깨진 로컬 component 참조가 없는지 검사했다.
- 계약의 path, parameters, requestBody, responses, security 의미는 유지했다. 잘못된 example은 제거하고 nullable 및 `$ref` 표현을 OpenAPI 3.0 문법으로 정규화했다.
- Redocly와 Swagger CLI에서 OpenAPI 문서 유효성 검증을 통과했다.
- Apidog UI에서의 실제 import는 실행하지 않았으므로 최종 import 확인은 남아 있다.
