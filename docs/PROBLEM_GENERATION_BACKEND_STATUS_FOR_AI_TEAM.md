# 문제 출제 Backend 구현 현황과 AI 연동 안내

- 작성 기준일: 2026-08-13
- Backend 기준 브랜치: `dev`
- 대상 독자: CheckOn AI 팀, Kafka-HTTP Adapter 담당자
- 정책 정본: `docs/POLICY_REGISTER.md`의 `PG-001`~`PG-005`
- 구현 반영: 문제 출제 PR #42가 `dev`에 병합됨

## 1. 먼저 보는 현재 결론

Backend에는 강사용 문제 출제 스튜디오의 REST API, 요청·결과 저장 구조, Kafka 요청 발행,
Kafka 결과 소비, 문항 검토 모델, 선택·저장·학생 발행 흐름이 구현되어 있다.

다만 전체 시스템 기준으로는 아직 `PARTIAL`이다. Backend가 발행한 문제 출제 Kafka 요청을 소비하여
AI HTTP API를 호출하고, 결과를 다시 Kafka로 반환하는 **문제 출제 Adapter worker가 현재 별도 Adapter
저장소에 구현되어 있지 않다.** AI 최신 `origin/develop`에는 `/v1/problems` route가 있으나
`/v1/diagnosis`는 없고, 문제 생성 조회 모델의 재시작 복구와 `execution_id` 안정성 결함이 남아 있다.

```text
Frontend
  → CheckOn Backend REST                                  구현
  → Backend DB + Transactional Outbox                    구현
  → Kafka problem-generation request                     구현
  → Kafka-HTTP Adapter problem-generation worker         미구현
  → AI HTTP /v1/problems + polling + /items              구현(운영 결함 잔존)
  → Adapter result Outbox + Kafka result                 미구현
  → Backend result consumer + 문항 read model            구현
  → 교사 검토·선택·저장·발행                            구현
```

따라서 Backend 단독 통합 테스트는 가능하지만, 실제 AI를 호출한 운영 E2E 완료 상태는 아니다.

## 2. 팀별 책임 경계

### Backend

- 인증된 `teacherProfileId`를 테넌트 원본으로 사용한다.
- 학생·클래스 접근 권한을 확인한다.
- 강사 화면용 학생 목록과 현재 약점 집계를 제공한다.
- 출제 요청, child execution, Transactional Outbox를 같은 DB 트랜잭션에 저장한다.
- Kafka 결과의 테넌트·요청·child 식별자와 멱등성을 검증한다.
- AI 결과 원문을 보존하고 실제로 존재하는 문항만 검토용 read model로 투영한다.
- AI 검증 상태와 교사의 선택·저장·학생 발행 상태를 분리한다.

### Kafka-HTTP Adapter

- Backend의 `pg-child-request-1` 이벤트를 소비한다.
- child 하나당 AI `POST /v1/problems`를 한 번 호출한다.
- 비종단 job을 영속적으로 추적하고 polling한다.
- 성공 시 AI items API에서 문항 전체를 가져온다.
- Backend가 처리할 수 있는 `worker_job.*` 결과 이벤트로 정규화한다.
- Adapter Inbox, work ledger, result Outbox, HTTP retry와 Kafka DLT를 소유한다.

### AI

- HTTP API만 제공하며 Kafka producer/consumer를 직접 소유하지 않는다.
- 문제 생성·진단·검증 로직과 AI 내부 `job_id`, `execution_id`, `set_id`를 소유한다.
- 같은 멱등 키와 같은 canonical request에는 같은 논리 job을 반환한다.
- 문항 본문과 검증 결과를 영속 저장하고 job/items 조회를 제공한다.
- AI의 `verified`는 교사 승인 또는 학생 발행 완료를 의미하지 않는다.

## 3. Frontend가 호출하는 Backend API

모든 경로의 base path는 `/api/v1`이다. 운영 환경에서는 Bearer 인증이 필요하며 테넌트는 요청 body가
아니라 인증 principal로 결정한다.

| 화면 단계 | Method | Path | 역할 |
| --- | --- | --- | --- |
| Step 1 | `GET` | `/problem-studio/students?page=0&size=20` | 활성 학생, 반·과목, 관리 일수, 최근 신호 조회 |
| Step 1 | `GET` | `/problem-studio/students/{studentId}/weakness-analysis` | 최근 8주 집계와 생성 가능 셀 목록 |
| Step 2 | `POST` | `/problem-studio/requests` | 영역×유형별 문제 출제 요청 |
| 공통 상태 | `GET` | `/problem-requests/{requestId}` | 비동기 요청 상태 polling |
| Step 3 | `GET` | `/problem-studio/requests/{requestId}/review` | 생성 문항과 검증 상태 조회 |
| Step 4 | `PUT` | `/problem-studio/requests/{requestId}/selection` | 교사가 선택한 문항 교체 |
| Step 4 | `POST` | `/problem-studio/requests/{requestId}/save` | 선택 문항을 문제 세트로 저장 |
| Step 4 | `POST` | `/problem-studio/requests/{requestId}/publish` | 대상 학생에게 과제 발행 |
| Step 4 | `GET` | `/problem-studio/requests/{requestId}/printable` | 프론트 PDF 렌더링용 데이터 |

기존 taxonomy 수동 요청용 API도 유지한다.

| Method | Path | 역할 |
| --- | --- | --- |
| `POST` | `/problem-requests` | `manualTargets`, `taxonomyVersion`을 직접 지정하는 레거시 v1 요청 |
| `GET` | `/problem-requests/{requestId}` | 레거시와 Studio가 공유하는 상태·원문 결과 조회 |

### 3.1 Studio 출제 요청 예시

```http
POST /api/v1/problem-studio/requests
Authorization: Bearer <access-token>
Idempotency-Key: studio-20260813-0001
Content-Type: application/json
```

```json
{
  "studentId": "0198f000-0000-7000-8000-000000000002",
  "targets": [
    {
      "areaTag": "language",
      "typeTag": "CONCEPT",
      "count": 3
    },
    {
      "areaTag": "language",
      "typeTag": "INFER",
      "count": 2
    }
  ],
  "difficulty": "MEDIUM"
}
```

- `targets`는 1~20개다.
- AI v1 evidence가 있는 `language × CONCEPT`, `language × INFER`만 허용한다. 그 외 셀은 저장·Outbox 생성 전에 `400 INVALID_REQUEST`로 거절한다.
- 동일한 `areaTag + typeTag` 조합을 중복 요청할 수 없다.
- 각 target의 `count`는 1~20이며 한 부모 요청의 합계는 최대 20이다.
- 같은 evidence-ready skill node에서 여러 문항을 생성하는 것을 허용한다. 시연 권장 수량은 target당 1~3문항이다.
- 같은 강사, 같은 `Idempotency-Key`, 같은 정규화 payload는 기존 요청을 재사용한다.
- 같은 키에 다른 payload를 보내면 `409 IDEMPOTENCY_CONFLICT`다.

대표 응답은 `202 Accepted`다.

```json
{
  "requestId": "0198f100-0000-7000-8000-000000000001",
  "status": "QUEUED",
  "replayed": false
}
```

응답 `Location`은 `/api/v1/problem-requests/{requestId}`이며 프론트는 이 경로로 상태를 polling한다.

## 4. Backend 내부 요청 구조

Studio 요청 하나는 부모 request와 여러 child execution으로 나뉜다.

```text
problem_generation_requests 1
  ├─ problem_generation_request_targets N
  ├─ problem_generation_executions N
  └─ problem_generation_outbox N
```

- 영역×유형 target 하나당 child execution 하나를 만든다.
- child마다 독립적인 `problem_execution_id`, `target_index`, AI 멱등 키를 가진다.
- 요청과 Outbox는 같은 트랜잭션에 저장한다.
- Kafka 발행은 DB 트랜잭션 밖에서 수행한다.
- Outbox는 stale claim 회수와 지수 backoff를 지원한다.
- 발행 최대 시도 초과 시 child를 `DELIVERY_FAILED`로 기록하고 부모 상태를 다시 집계한다.

신규 문제 출제 테이블에는 PostgreSQL FORCE RLS가 적용되어 있다. Kafka와 AI에는 실제 강사·학생·반
UUID나 실명을 보내지 않고 `tn_`, `st_`, `cl_` opaque alias만 보낸다.

## 5. Backend → Adapter Kafka 요청

### 5.1 기본 설정

| 항목 | 기본값 |
| --- | --- |
| 요청 topic | `checkon.ai.problem-generation.requests.v1` |
| 결과 topic | `checkon.ai.problem-generation.results.v1` |
| Backend 결과 DLT | `checkon.backend.problem-generation.results.dlt.v1` |
| Backend consumer group | `checkon-backend-problem-generation-v1` |
| Kafka key | `tn_...` tenant alias |

Backend의 문제 출제 Kafka 기능은 기본적으로 꺼져 있다. 연동 환경에서는 다음 설정이 필요하다.

```text
AI_PROBLEM_KAFKA_ENABLED=true
```

### 5.2 child 요청 record

- `event_type`: `problem_generation.requested`
- `schema_version`: `pg-child-request-1`
- record key: `tenant_id`와 같은 `tn_...` alias
- `correlation_id`: 부모 `problem_request_id`

```json
{
  "event_id": "0198f100-0000-7000-8000-000000000101",
  "event_type": "problem_generation.requested",
  "occurred_at": "2026-08-13T03:00:00Z",
  "tenant_id": "tn_0123456789abcdef0123456789abcdef",
  "schema_version": "pg-child-request-1",
  "correlation_id": "0198f100-0000-7000-8000-000000000001",
  "causation_id": null,
  "payload": {
    "problem_request_id": "0198f100-0000-7000-8000-000000000001",
    "problem_execution_id": "0198f100-0000-7000-8000-000000000011",
    "target_index": 0,
    "idempotency_key": "pgc_0198f100000070008000000000000011",
    "request": {
      "target_kind": "student",
      "target_ref": "st_0123456789abcdef0123456789abcdef",
      "target_source": "teacher_manual",
      "taxonomy_version": "frontend-studio-1",
      "area_tag": "language",
      "type_tags": ["concept"],
      "item_format": "mcq",
      "count": 3,
      "requested_difficulty": "medium",
      "target": "auto",
      "passage": null,
      "snapshot_hash": "sha256:<64-lowercase-hex>"
    }
  }
}
```

Kafka record header에도 `event_id`, `event_type`, `schema_version`, `correlation_id`, `tenant_id`를
UTF-8 문자열로 넣는다.

### 5.3 Adapter가 AI HTTP로 변환할 때의 확정 원칙

- `payload.request`는 Backend snapshot이며 그대로는 AI v1 body가 아니다. Adapter가 아래 `manual_targets`를 보강한 뒤 `POST /v1/problems` body로 사용한다.
- envelope의 `tenant_id`를 `X-Tenant-Id`로 보낸다.
- `payload.idempotency_key`를 `Idempotency-Key`로 보낸다.
- `X-Request-Id`는 `{problem_execution_id}:{target_index}` 형태의 추적 보조값으로 사용한다.
- Backend와 Adapter의 상관관계 정본은 header echo가 아니라 명시적인 child 식별 필드다.
- Backend snapshot에는 강사가 약점 셀을 선택했다는 의미가 남아 있지만, 현재 AI v1 호출의
  `target_source`는 지원 값인 `teacher_manual`로 변환한다. 이는 `weakness_auto`가 아니다.
- Backend는 AI 전용 `skill_node_id`를 저장하거나 하드코딩하지 않는다. Adapter는 AI taxonomy catalog에서
  `area_tag`, `type_affinity`, `has_evidence=true`가 일치하는 node를 안정 정렬해 `manual_targets`로 넣는다.
- 현재 두 허용 셀은 모두 `language.grammar.phonological_change`로 해석된다. 후보가 없으면 AI를 호출하지
  않고 `NO_EVIDENCE_READY_TARGET` 실패 결과를 Backend로 보낸다.
- AI GET 응답의 `meta.execution_id`가 매번 바뀌는 결함이 해결될 때까지 Adapter는 POST 응답의
  `execution_id`를 정본으로 저장하고 GET에서 달라진 값을 무시한다.

## 6. AI HTTP에 기대하는 기능

Backend는 AI HTTP를 직접 호출하지 않는다. 다음 API는 Adapter가 호출한다.

| Method | AI Path | 기대 동작 |
| --- | --- | --- |
| `POST` | `/v1/problems` | 멱등 문제 생성 job 시작 또는 기존 job 반환 |
| `GET` | `/v1/problems/{job_id}` | 비종단 job 상태 조회, terminal 시 `set_id` 확인 |
| `GET` | `/v1/problems/{set_id}/items` | 문항 `slot_index`와 요약 목록 조회 |
| `GET` | `/v1/problems/{set_id}/items/{slot_index}` | 개별 문항 본문·선지·정답·검증 결과 조회 |
| `POST` | `/v1/diagnosis` | 아직 AI route와 Backend 배선 모두 없음 |

job 상태와 items GET에는 `X-Tenant-Id`만 필요하다. 최대 20문항이면 items 요약 1회와 detail 최대 20회가
필요하다. AI는 현재 `/v1/health`를 제공하지 않으므로 Adapter의 임시 readiness probe는
`GET /openapi.json`의 200 응답을 사용할 수 있다.

확정된 관찰 조건은 다음과 같다.

- `POST /v1/problems` timeout: 300초
- POST 응답이 비종단일 때만 polling
- child 관찰 상한: 21분
- 21분 초과 시 child 결과: `timed_out`
- AI 멱등 보존: 30일
- 같은 key + 같은 canonical body: 같은 논리 job
- 같은 key + 다른 body: HTTP 409
- HTTP 400·409: 재시도하지 않음
- network·timeout·HTTP 5xx: Adapter 정책에 따라 제한 재시도
- AI는 현재 `Retry-After`와 전체 처리 deadline을 제공하지 않는다. 21분은 Adapter 운영 상한이다.

AI는 재시작 후에도 job과 items를 조회할 수 있도록 결과를 영속 보존해야 한다. 인메모리 저장만으로는
운영 E2E 조건을 충족하지 못한다.

## 7. Adapter → Backend Kafka 결과

Backend는 다음 event type을 처리한다.

- `worker_job.progress`
- `worker_job.running`
- `worker_job.succeeded`
- `worker_job.failed`
- `worker_job.cancelled`
- 같은 suffix의 `problem_generation.*`

권장 표준은 `worker_job.*`과 `payload.worker_kind=problem_generation`이다.
결과 record key도 요청과 같은 `tenant_id` alias를 사용하는 것을 권장한다. 현재 Backend consumer는
record key/header가 아니라 JSON body의 `tenant_id`, 요청·child 식별자를 계약 검증의 정본으로 사용한다.

### 7.1 성공 결과 권장 예시

```json
{
  "event_id": "0198f200-0000-7000-8000-000000000001",
  "event_type": "worker_job.succeeded",
  "occurred_at": "2026-08-13T03:02:00Z",
  "tenant_id": "tn_0123456789abcdef0123456789abcdef",
  "schema_version": "worker-job-1",
  "correlation_id": "0198f100-0000-7000-8000-000000000001",
  "payload": {
    "worker_kind": "problem_generation",
    "problem_request_id": "0198f100-0000-7000-8000-000000000001",
    "problem_execution_id": "0198f100-0000-7000-8000-000000000011",
    "target_index": 0,
    "adapter_execution_id": "0198f200-0000-7000-8000-000000000011",
    "job_id": "job-20260813-0001",
    "execution_id": "execution-20260813-0001",
    "set_id": "set-20260813-0001",
    "result_status": "completed",
    "result": {
      "problems": [
        {
          "id": "problem-0001",
          "stem": "다음 중 적절한 설명을 고르세요.",
          "options": ["정답", "오답 1", "오답 2", "오답 3"],
          "correct_answer": "정답",
          "explanation": "정답 해설",
          "source_basis": "생성 근거",
          "validation_status": "passed",
          "validation_message": null
        }
      ]
    },
    "versions": {
      "model": "m2-v1"
    }
  }
}
```

child 결과에서는 다음 값을 반드시 보내고 한 child 수명 동안 바꾸지 않는 것을 계약으로 한다.

- `problem_request_id`
- `problem_execution_id`
- `target_index`
- `adapter_execution_id`
- AI `job_id`
- AI `execution_id`
- 성공 시 `set_id`

Backend는 tenant alias, child ID, target index, 이미 저장된 Adapter/AI ID를 대조한다. 값이 바뀌거나 다른
tenant의 결과가 오면 계약 오류로 DLT에 보낸다.

> 현재 parser는 일부 child 식별자를 기술적으로 nullable로 읽을 수 있다. 그러나 Studio 상호 계약에서는
> 위 필드를 필수로 간주한다. AI/Adapter fixture는 누락 가능성을 전제로 작성하지 않아야 한다.

### 7.2 진행 이벤트

진행 이벤트는 `worker_job.progress` 또는 `worker_job.running`을 사용한다. Backend는 부모/child를
`RUNNING`으로 반영하며 terminal 문항 결과를 투영하지 않는다.

### 7.3 실패·취소·특수 child 상태

- 실패: `worker_job.failed`
- 취소: `worker_job.cancelled`
- timeout: `payload.result_status` 또는 `payload.child_status`에 `timed_out`
- 데이터 부족 정상 종료: `rejected_insufficient`
- Adapter→Kafka 전달 실패: `delivery_failed`

실패 이벤트에는 안전한 `error_code`를 포함해야 하며 개인정보, 원문 prompt, secret, 내부 stack trace를
넣지 않는다.

## 8. Backend 문항 투영 호환성

Backend는 성공 결과 원문을 JSONB로 보존한 뒤 다음 후보 이름을 호환해서 읽는다.

| 의미 | 인식하는 필드 |
| --- | --- |
| 문항 배열 | `problems`, `items`, `questions`, 또는 `data` 내부의 같은 배열 |
| 문두 | `stem`, `question`, `prompt`, `question_text` |
| 선지 배열 | `options`, `choices`, `answers` |
| 선지 객체 내용 | `text`, `content`, `value`, `label` |
| 외부 문항 ID | `id`, `problem_id`, `question_id`, `item_id` |
| 정답 | `correct_answer`, `answer`, `correctAnswer`, 1-based `answer.correct_no` |
| 정답 index | `correct_option_index`, `answer_index` |
| 지문 | `passage`, `context` |
| 해설 | `explanation`, `rationale`, `solution` |
| 생성 근거 | `source_basis`, `generation_basis`, `basis`, `evidence` |
| 검증 상태 | `validation_status`, `verification_status`, `verification.status` |

문두가 없거나 선지가 2개 미만이면 그 문항은 read model로 만들지 않는다. 원문에 없는 값을 Backend가
추론해서 채우지 않는다.

검증 상태 매핑은 다음과 같다.

| AI 표현 예 | Backend 상태 |
| --- | --- |
| `passed`, `verified`, `valid`, `success` | `PASSED` |
| `review_required`, `needs_review`, `warning`, `manual_review` | `REVIEW_REQUIRED` |
| `unverifiable`, `verification_unavailable`, `verification_failed`, `invalid`, `error` | `UNVERIFIABLE` |
| `excluded`, `dropped`, `discarded`, `rejected`, `disposed` | `EXCLUDED` |

정답 또는 검증 상태가 없으면 Backend는 문항을 자동 승인하지 않고 `UNVERIFIABLE`로 둔다.

## 9. 부모 상태 집계

| 부모 상태 | 의미 |
| --- | --- |
| `QUEUED` | 요청과 Outbox 저장 완료 |
| `DISPATCHED` | Kafka broker가 요청 발행을 확인 |
| `RUNNING` | 하나 이상의 child가 아직 실행 중 |
| `SUCCEEDED` | 전부 종단이며 검토 가능한 문항이 있고 실패 child가 없음 |
| `PARTIAL_SUCCESS` | 검토 가능한 문항과 실패 child가 함께 존재 |
| `FAILED` | 성공 문항이 없고 실패 child가 존재 |
| `CANCELLED` | 취소 결과 수신 |
| `DELIVERY_FAILED` | Backend가 Kafka 요청 발행에 최종 실패 |

모든 child가 `rejected_insufficient`이고 생성 문항이 0개인 경우는 시스템 오류가 아니라 업무상 완료로
취급한다. `PARTIAL_SUCCESS`여도 생성된 문항이 있으면 교사는 Step 3·4에서 검토·저장·발행할 수 있다.

## 10. 멱등성과 계약 오류 처리

- Backend 요청 Outbox는 at-least-once 발행이다.
- 결과 `event_id`를 소비 이력에 저장한다.
- 같은 `event_id`와 같은 payload 재수신은 no-op이다.
- 같은 `event_id`에 다른 payload가 오면 계약 오류다.
- `correlation_id`와 `payload.problem_request_id`를 함께 보내면 값이 같아야 한다.
- 결과의 `tenant_id`는 요청에서 받은 `tn_...` 값을 그대로 돌려줘야 한다.
- terminal child를 다른 terminal/진행 상태로 교체할 수 없다.
- 한 child에서 저장된 `adapter_execution_id`, `job_id`, `execution_id`, `set_id`는 뒤 이벤트에서 바뀔 수 없다.
- 계약 오류는 재시도하지 않고 Backend DLT로 보낸다.
- 일시적인 Backend 소비 실패는 1초·2초 간격으로 재시도한 뒤 DLT로 보낸다.

## 11. Step 1 진단의 현재 상태

현재 Backend의 `/problem-studio/students/{studentId}/weakness-analysis`는 AI `/v1/diagnosis`를 호출하지
않는다. 최근 8주 Backend 학습 기록을 영역×유형별로 집계한다.

- 표본 10건 미만: `ON_HOLD`
- 표본 10건 이상이며 학생 전체 평균 이상: `GOOD`
- 표본 10건 이상이며 학생 전체 평균 미만: `WEAK_SIGNAL`

확정된 목표 구조는 `Frontend → Backend → Adapter → AI POST /v1/diagnosis`의 동기 호출이며 AI timeout은
5초다. 그러나 Backend 학습 기록에 AI 입력용 `tag_confirmed`, `skill_node_id`가 아직 충분히 연결되지 않아
현재 통계 구현을 즉시 대체하지 않았다.

즉, **문제 생성 child Kafka 연동과 Step 1 AI 진단 연동은 서로 다른 미완료 작업**이다.

## 12. 구현·검증 상태 구분

### Backend에서 구현됨

- Frontend REST endpoint와 OpenAPI
- 인증 강사 기준 대상 소유권 검사
- opaque alias 생성·재사용
- 부모 요청과 target별 child execution
- Transactional Outbox와 Kafka publisher
- 결과 listener, 멱등 소비, 계약 검증, DLT
- child 상태와 부모 `PARTIAL_SUCCESS` 집계
- AI 원문 JSONB 보존과 문항 read model 투영
- 교사 선택, 문제 세트 저장, 학생 과제 발행, printable 데이터
- 문제 출제 테이블 FORCE RLS

### Backend 테스트로 검증됨

- 복수 target의 child Kafka fan-out
- tenant alias key/header/body
- 내부 강사·학생 UUID가 Kafka payload에 포함되지 않는지 확인
- 합성 성공 결과의 Kafka 소비와 상태 저장
- 계약 오류 DLT
- child 성공+실패의 `PARTIAL_SUCCESS`
- 문항 검토·선택·저장·발행의 멱등성
- 다른 강사의 요청 조회 차단
- Frontend OpenAPI 경로 계약
- `answer.correct_no`와 AI 검증 vocabulary의 Backend 투영
- 비지원 셀의 요청·Outbox 사전 차단
- `/api/v1/problem-studio/**`의 명시적 `ROLE_TEACHER` 경계

### 아직 실제 E2E로 검증되지 않음

- 실제 Adapter가 Backend request topic을 소비하는 과정
- 실제 AI `POST /v1/problems` 호출
- 실제 AI job polling과 items 조회
- Adapter result Outbox가 결과 topic에 발행하는 과정
- 실제 AI 문항이 Backend review API에 나타나는 전체 왕복

Backend 테스트 일부는 Embedded Kafka와 합성 결과 이벤트를 사용한다. 이를 실제 AI E2E 통과로 해석하면
안 된다.

## 13. 현재 알려진 미완료·주의 사항

1. 별도 Kafka Adapter 저장소에 문제 출제 worker가 없다.
2. AI 최신 기본 브랜치에는 `/v1/problems`가 있으나 `/v1/diagnosis`는 없다.
3. AI GET 응답의 `meta.execution_id`가 호출마다 바뀌어 Adapter가 POST 값을 보존해야 한다.
4. AI가 DB store를 사용해도 `_views`가 재시작 후 복원되지 않아 polling/items 조회가 깨질 수 있다.
5. 대용량 결과의 `result_ref` fallback 정책은 문서에 있으나 Backend 회수 경로는 구현되지 않았다. 현재 E2E는 Adapter가 문항 본문을 결과 이벤트에 전량 싣는 방식이 가장 직접적이다.
6. 21분 뒤 도착한 late result의 별도 감사·회수 저장은 Backend에 아직 없다.
7. `language × CONCEPT/INFER` 외 셀 차단과 프론트 `generationCapabilities` 제공은 Backend에 반영됐다. Adapter의 taxonomy 조회·`manual_targets` 보강은 아직 미구현이다.
8. `/api/v1/problem-studio/**`의 명시적 `TEACHER` matcher는 Backend에 반영됐다.
9. 학생용 과제 목록·풀이·제출·채점 API와 문항 수정·교체·재생성은 후속 범위다.

## 14. AI 팀에 요청하는 확인 항목

다음 남은 항목을 실제 fixture와 함께 회신하면 운영 계약을 닫을 수 있다. endpoint와 현재 vocabulary는 이번 피드백으로 확정했다.

1. taxonomy catalog endpoint와 57-node 실제 fixture
2. job의 모든 phase와 terminal 상태 fixture
3. items summary와 slot detail의 실제 성공 fixture
4. 실패 fixture와 안정적인 `error_code`
5. `rejected_insufficient`, `cancelled`, timeout 표현 방식
6. 같은 Idempotency-Key의 same-body replay와 different-body 409 동작
7. job과 items의 영속 보존 및 재시작 후 조회 여부
8. `meta.execution_id` 안정성 결함 수정 일정
9. `_views` 재시작 복구 또는 PostgreSQL fallback 계약
10. POST가 terminal 응답을 직접 반환할 수 있는 조건과 polling이 필요한 조건
11. 향후 `Retry-After`와 서버 전체 deadline 제공 계획
12. 최대 문항 수, 최대 응답 크기, 평균·최대 처리 시간
13. 내부망 HTTP 인증 전제와 향후 서비스 인증 계획
14. `/v1/diagnosis` 실제 request/response fixture와 필요한 `tag_confirmed`, `skill_node_id` 입력 규칙

## 15. 권장 상호 계약 테스트 fixture

Backend, Adapter, AI 저장소에서 최소 다음 fixture를 공유하기를 권장한다.

1. `pg-child-request-1` 정상 요청
2. 같은 요청 이벤트 재전달
3. `POST /v1/problems` terminal 성공
4. `POST /v1/problems` 비종단 후 polling 성공
5. items 정상 응답
6. `rejected_insufficient`
7. HTTP 400
8. 같은 key + 다른 body의 HTTP 409
9. network/timeout/HTTP 5xx
10. Adapter `worker_job.running`
11. Adapter `worker_job.succeeded` 전체 문항 결과
12. Adapter `worker_job.failed`
13. tenant mismatch
14. child ID 또는 `target_index` mismatch
15. 동일 `event_id` 같은 payload 재전달
16. 동일 `event_id` 다른 payload 충돌
17. 21분 timeout
18. 프로세스 재시작 후 미완료 job 복구

## 16. Backend 코드 위치

| 파일 | 책임 |
| --- | --- |
| `src/main/java/com/checkon/problem/presentation/ProblemStudioController.java` | Frontend Step 1~4 REST API |
| `src/main/java/com/checkon/problem/presentation/ProblemGenerationController.java` | 레거시 요청과 공용 상태 조회 |
| `src/main/java/com/checkon/problem/application/ProblemGenerationRequestService.java` | 요청 검증, alias, child fan-out, Outbox 원자 저장 |
| `src/main/java/com/checkon/problem/infrastructure/outbox/ProblemGenerationOutboxPublisher.java` | Kafka 요청 발행 scheduler |
| `src/main/java/com/checkon/problem/integration/kafka/ProblemGenerationResultListener.java` | 결과 topic consumer |
| `src/main/java/com/checkon/problem/integration/kafka/ProblemGenerationResultEventParser.java` | 결과 envelope와 상태 계약 해석 |
| `src/main/java/com/checkon/problem/application/ProblemGenerationResultProcessor.java` | 멱등 처리, child 검증, 부모 상태 집계 |
| `src/main/java/com/checkon/problem/application/ProblemGenerationItemProjector.java` | AI 결과를 검토 문항 read model로 투영 |
| `src/main/java/com/checkon/problem/application/ProblemStudioService.java` | 약점 집계, 검토, 선택, 저장, 발행, printable |
| `src/main/resources/db/migration/V16__create_problem_generation_kafka_boundary.sql` | 부모 요청, Outbox, 결과 소비 이력, RLS |
| `src/main/resources/db/migration/V17__create_problem_studio_frontend_flow.sql` | target, 문항, 저장 세트, 과제 |
| `src/main/resources/db/migration/V18__create_problem_generation_child_executions.sql` | child execution과 AI ID |
| `src/main/resources/db/migration/V19__connect_problem_generation_child_events.sql` | child와 Outbox 연결 |
| `src/main/resources/openapi/dashboard-api.yaml` | Frontend REST 계약 |

## 17. 문서 해석 주의

`docs/PROBLEM_GENERATION_AI_TEAM_HANDOFF.md`, `docs/PROBLEM_GENERATION_KAFKA_DECISIONS.md`,
`docs/PROBLEM_STUDIO_AI_TEAM_HANDOFF.md`에는 구현 중간 시점의 설명이 일부 남아 있다. 예를 들어 child execution,
`PARTIAL_SUCCESS`, 문항 read model, 저장·발행이 미구현이라고 적힌 부분은 현재 코드와 맞지 않는다.

정책 판단은 `docs/POLICY_REGISTER.md`의 `PG-001`~`PG-005`를 우선하고, 구현 여부는 이 문서와 현재
`dev` 코드·테스트를 기준으로 확인한다.
