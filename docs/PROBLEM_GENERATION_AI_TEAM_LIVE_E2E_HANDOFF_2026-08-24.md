# 문제 출제 실제 AI 통신 E2E 인계 (2026-08-24)

## 1. 검증 범위

아래 실제 경로를 로컬 Backend와 Adapter, 배포 AI 서버로 검증했다.

```text
Problem Studio REST
  -> CheckOn Backend
  -> Kafka request topic
  -> checkon-kafka-adapter Inbox
  -> https://checkon.bellrajin.com/v1/problems
  -> AI job polling / items / slot detail
  -> Adapter Outbox
  -> Kafka result topic
  -> Backend projection
  -> Problem Studio review REST
```

- Backend: `feature/problem-generation/69`
- Adapter: `codex/feature/problem-generation-contract-71`
- AI: 배포 서버 `https://checkon.bellrajin.com`
- Kafka: `localhost:9094`
- 실행일: 2026-08-24 KST

학생 실명이나 원본 학습 기록은 이 문서에 남기지 않는다.

## 2. 성공한 실제 왕복

### 2.1 문제 생성

- 영역: `language`
- 유형: `infer`
- 목표 node: `language.grammar.addition`
- 요청 수: 1
- AI job ID: `05da7e89-6b51-417f-bd4c-18363c219a69`
- AI set ID: `9a705e2b-4c5c-500e-b15d-363818b8821c`
- 결과: `generated`, slot 1/1 반영
- Adapter HTTP 조정 횟수: 34
- 결과 순서: progress -> terminal reference -> slot detail
- Backend 최종 상태: `SUCCEEDED`
- Backend review projection: `PROJECTED`

실제 생성된 문항의 stem, 5개 선택지, 정답, 오답 근거, evidence, 검증 상태가 Backend review API까지 보존됐다.

### 2.2 문항 AI 수정

- revision kind: `ai_refine`
- base revision: 0
- AI current revision: 1
- 결과: 수정된 slot detail을 재조회하고 Backend에 반영
- Backend revision 최종 상태: `SUCCEEDED`
- Backend slot current revision: 1

초기 검증에서 Adapter revision 결과에 `target_index`가 빠져 Backend DLT로 이동했다. 이는 AI 응답 문제가 아니라 Adapter 결과 envelope 조립 문제였다. Adapter가 원본 generation Inbox의 `target_index`를 claim 시 함께 읽고 revision 성공/실패 payload에 포함하도록 수정했다. 같은 멱등 요청을 재처리한 뒤 Backend 적용까지 성공했다.

## 3. AI 서버에서 관측된 비차단 항목

### 3.1 OpenAPI 계약 정보 누락

`GET /openapi.json`은 아래 경로를 노출하지만 문제 출제 요청 본문, 필수 헤더, 응답 필드가 구체 스키마가 아닌 `additionalProperties: true`로 표시된다.

- `POST /v1/problems`
- `GET /v1/problems/{job_id}`
- `GET /v1/problems/{set_id}/items`
- `GET /v1/problems/{set_id}/items/{slot_index}`
- `POST /v1/problems/{set_id}/items/{slot_index}/revisions`

AI팀 요청 사항:

1. `X-Tenant-Id`, `X-Request-Id`, `Idempotency-Key` 필수 여부를 OpenAPI parameters에 명시한다.
2. POST request body와 202 response, job 조회, item summary/detail, revision 요청/성공/409 응답을 named schema로 노출한다.
3. `queued|leased|running|paused|succeeded|failed|cancelled` phase와 terminal result 조합을 enum/oneOf로 표현한다.
4. revision 409의 `reason`, `current_revision_no`를 계약에 포함한다.

현재 통신 자체는 성공하지만, 배포 계약을 OpenAPI만 보고 독립 검증하거나 클라이언트를 생성할 수 없는 상태다.

### 3.2 헬스 경로

`GET /health`는 404를 반환했다. 운영 헬스 체크의 정식 경로가 따로 있다면 문서에 명시하고, 없다면 인증이나 LLM 호출 없이 프로세스 준비 상태를 확인할 수 있는 경로를 제공해 달라.

### 3.3 generation_exhausted 관측

동일한 `language/infer/language.grammar.addition` 조건으로 이어서 실행한 별도 요청 한 건은 다음과 같이 정상적인 실패 계약으로 종단했다.

- AI job ID: `516d84af-79af-4010-ab70-0bee1459f1ea`
- AI set ID: `c735e010-8130-5e51-843a-f871ce772abe`
- status counts: `dropped=1`, 나머지 0
- slot failure reason: `generation_exhausted`
- Backend 최종 상태: `FAILED`, `ALL_CHILD_EXECUTIONS_FAILED`

이는 HTTP/Kafka 통신 오류가 아니며 Adapter와 Backend가 AI의 도메인 실패를 계약대로 반영했다. 다만 같은 조건에서 직전 요청은 성공했으므로, AI팀은 위 job ID를 기준으로 생성 시도별 gate 탈락 원인과 재현 가능성을 확인해 달라. 실패가 허용된 정상 품질 게이트 결과라면 별도 수정은 필요 없다.

## 4. 배포 화면 검증 경계

기준 배포 프런트의 `문제 출제 스튜디오 -> Step 1` 화면과 약점 매트릭스는 브라우저 콘솔 오류 없이 렌더링됐다. 그러나 배포 JavaScript bundle 안에 학생 목록과 약점 수치가 고정되어 있으며 `problem-studio` 또는 `problem-requests` Backend API 호출은 확인되지 않았다.

따라서 현재 증명된 범위는 다음과 같다.

- 실제 Backend -> Kafka -> Adapter -> 배포 AI -> Kafka -> Backend: 성공
- Backend review REST에서 생성·수정 문항 조회: 성공
- 배포 프런트 화면 렌더링: 성공
- 배포 프런트가 실제 Backend 데이터로 Step 1~4를 수행하는 화면 E2E: 미구현 또는 미배포

프런트 연동은 이 작업에서 수정 가능한 저장소 범위 밖이므로 변경하지 않았다.

## 5. 완료 판정

- AI 통신 차단 오류: 없음
- Backend 수정 필요: 없음
- Adapter 수정 필요: `target_index` 누락 수정 완료
- AI팀 필수 확인: OpenAPI 상세 계약 및 헬스 경로 문서화
- AI팀 품질 확인: `generation_exhausted` job의 gate 탈락 원인
- 남은 제품 E2E: 실제 Backend API에 연결된 프런트 Step 1~4 배포 후 재검증
