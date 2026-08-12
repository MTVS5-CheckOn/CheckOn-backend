# AI 팀용 위험 탐지 Kafka 연동 가이드

## 한눈에 보기

백엔드는 위험 탐지 결과를 기다리는 HTTP 호출을 사용하지 않습니다. 백엔드가 요청 이벤트를 Kafka에 발행하면 AI가 소비하고, AI는 **완료** 또는 **실패** 이벤트를 Kafka에 한 번 이상 발행합니다.

| 방향 | Topic | AI 역할 |
| --- | --- | --- |
| Backend → AI | `checkon.risk-detection.requested.v1` | 소비하여 위험 탐지 실행 |
| AI → Backend | `checkon.risk-detection.completed.v1` | 정상 결과를 발행 |
| AI → Backend | `checkon.risk-detection.failed.v1` | 실행 불가/실패 결과를 발행 |

로컬 Docker Compose에서는 broker 주소가 `kafka:19092`(컨테이너 내부)이고, 호스트에서 실행하는 AI는 `localhost:9094`입니다. 개발 외 환경의 주소와 인증 정보는 배포 환경 변수로 제공합니다.

## 반드시 지킬 약속

1. 모든 메시지는 [AsyncAPI 계약](../contracts/risk-detection-kafka.asyncapi.yaml)의 `RiskDetectionEvent` envelope를 사용합니다.
2. `schema_version`은 현재 `1.0`이고, 임의로 필드 이름을 바꾸지 않습니다. 호환되지 않는 변경은 새 topic/버전으로 만듭니다.
3. Kafka key는 반드시 `tenant_alias`입니다. 같은 강사의 이벤트 순서가 한 파티션에서 유지됩니다.
4. 요청의 `payload`는 기존 `POST /v1/detect`의 **JSON 본문 전체**입니다. HTTP 헤더였던 값은 envelope의 `tenant_alias`, `request_id`, `idempotency_key`로 옮겼습니다. v0.2에서는 기존 필드를 바꾸지 않고 선택 배열 `detection_evidence`만 추가합니다. 이 필드가 없는 이전 요청도 계속 처리해야 합니다.
5. 완료 이벤트의 `payload`는 기존 `/v1/detect` 성공 시의 **200 응답 본문 전체**(`data`, `error: null`, `meta`)입니다. AI가 새 응답 형식을 만들지 않습니다.
6. 실패 이벤트의 `payload`는 `code`, `message`, `detail`, `retryable`입니다. `message`는 교사 화면에 노출되지 않습니다.
7. `event_id`는 메시지마다 새 UUIDv7이고, `causation_id`는 처리한 요청 이벤트의 `event_id`입니다. 재발행에도 같은 업무 결과라면 같은 `event_id`가 올 수 있으므로 소비자는 중복 안전해야 합니다.

## v0.2 `detection_evidence` 처리

`detection_evidence`에는 가명화된 근거만 오며, 각 학생에 대해 최근 10주의 아래 두 종류가 항상 전달됩니다.

| `kind` | `source_table` | 핵심 값 | AI 해석 |
| --- | --- | --- | --- |
| `assignment_window` | `assignment_week_summary` | `week_start`, `expected_count`, `submitted_count` | 과제가 있었는데 미제출인지 판단 |
| `weekly_activity` | `student_week_activity` | `week_start`, `activity_count` | `activity_count=0`도 실제 집계 결과로 해석 |
| `enrollment_transition` | `student_status_history` | `occurred_at`, `from_status=paused`, `to_status=returned` | 분석 주 복귀 케어 판단 |

- `record_id`는 응답 `evidence`에 **그대로** 돌려주세요. 백엔드는 `(source_table, record_id)`가 요청에 실제 있었는지 검증합니다. 한 글자라도 바꾸거나 다른 `source_table`과 섞으면 완료 이벤트가 DLT로 갈 수 있습니다.
- `students[].status=returned`인 학생은 같은 분석 주의 `enrollment_transition`도 함께 있어야 합니다.
- `expected_count=0`은 “그 주에는 과제가 없었다”는 뜻이므로 미제출 근거로 쓰지 않습니다.
- `detection_evidence` 누락과 빈 배열은 같은 hash 의미입니다. 배열 순서는 신경 쓰지 않아도 되지만, 값 하나가 달라지면 새 `snapshot_hash`가 됩니다.

### hash 구현 확인

백엔드는 다음 규칙으로 요청 `snapshot_hash`를 계산합니다. AI는 받은 hash를 결과 envelope에 원문 그대로 복사하면 됩니다.

- `snapshot_hash` 자체와 `snapshot_meta.classes`는 hash 입력에서 제외
- object key 오름차순, 공백 없는 UTF-8 JSON
- `students`: `student_ref`, `learning_events`: `record_id`, `alert_context`: `(student_ref, signal_type)` 정렬
- `detection_evidence`: `(kind, student_ref, at, source_table, record_id)` 정렬. `at`은 주간 값이면 `week_start`, 복귀 값이면 시간대가 포함된 `occurred_at`

AI가 제공한 고정 샘플의 기대 hash는 `sha256:20826dda25119fd1cd96c712c2f6a3180fdbca51e8a3503341501f8b5bda3823`입니다. 백엔드는 이 벡터를 BDD 테스트로 검증합니다.

## AI 처리 순서

1. `requested`를 수신하고 `event_id`를 AI 쪽 중복 처리 저장소에 기록합니다. 이미 처리한 ID면 같은 결과를 재발행하거나 안전하게 무시합니다.
2. `tenant_alias`, `request_id`, `idempotency_key`, `snapshot_hash`를 결과 이벤트에 **그대로 복사**합니다.
3. 요청 payload를 검증하고 분석합니다. 학생 실명, 전화번호, 내부 UUID를 역조회하거나 로그에 남기지 않습니다.
4. 성공하면 `completed`, 처리 불가능하면 `failed`를 발행합니다. 결과를 잃지 않도록 AI도 결과 발행 전 자신의 Outbox 또는 동등한 내구성 장치를 사용합니다.
5. Kafka 전송 성공 확인 전에는 자신의 처리 완료 상태를 확정하지 않습니다. 전송 뒤 재시도되어 같은 결과가 두 번 전달될 수 있는 것은 정상이며 백엔드가 멱등 처리합니다.

## 완료 이벤트 예시

```json
{
  "event_id": "019b0000-0000-7000-8000-000000000011",
  "event_type": "risk-detection.completed",
  "schema_version": "1.0",
  "correlation_id": "019b0000-0000-7000-8000-000000000001",
  "causation_id": "019b0000-0000-7000-8000-000000000002",
  "tenant_alias": "tn_0123456789abcdef0123456789abcdef",
  "run_id": "019b0000-0000-7000-8000-000000000001",
  "attempt_id": "019b0000-0000-7000-8000-000000000003",
  "request_id": "019b0000-0000-7000-8000-000000000003",
  "idempotency_key": "tn_0123456789abcdef0123456789abcdef:2026-08-12",
  "snapshot_hash": "sha256-hex-value",
  "occurred_at": "2026-08-12T02:11:31Z",
  "payload": {
    "data": { "signals": [], "stats": { "students_evaluated": 0, "signals_raised": 0, "excluded_under_2w": 0, "capped_out": 0, "rules_skipped": [] } },
    "error": null,
    "meta": { "execution_id": "ai-execution-123", "versions": { "pipeline": "1.0" } }
  }
}
```

## 실패·재시도와 DLT

- 백엔드는 `completed`/`failed` 메시지를 파싱·계약 검증·DB 저장까지 하나의 트랜잭션으로 처리합니다. 같은 `event_id`는 한 번만 반영합니다.
- 소비 중 일시 오류는 1초, 2초 간격으로 총 3회 재시도됩니다. 계속 실패하면 `<원본-topic>.dlt`로 이동합니다.
- DLT는 정상 결과 topic이 아닙니다. AI는 DLT에 직접 결과를 보내지 말고, 원인을 수정한 뒤 새 `completed` 또는 `failed` 이벤트를 원 topic에 발행합니다.
- `failed.retryable=true`는 AI가 일시 장애로 판단했다는 정보입니다. 현재 백엔드는 이 이벤트를 해당 attempt의 실패로 기록합니다. AI가 자체 재시도 후에도 끝내지 못했을 때만 `failed`를 발행하세요.

## AI 팀 확인 체크리스트

- [ ] Kafka consumer group은 AI 서비스 전용 이름을 사용한다. 예: `checkon-ai-risk-detection-v1`.
- [ ] 요청 이벤트의 `event_id`와 `idempotency_key`로 중복 실행을 막는다.
- [ ] 완료/실패 이벤트의 모든 상관관계 필드를 원본 요청에서 복사한다.
- [ ] 기존 OpenAPI의 payload 유효성 검사를 그대로 재사용한다.
- [ ] `detection_evidence`의 세 kind와 `activity_count=0`을 파싱·판정한다.
- [ ] 응답 evidence에 요청의 정확한 `(source_table, record_id)`를 사용한다.
- [ ] 결과 발행 실패가 분석 결과 유실로 이어지지 않게 한다.
- [ ] producer `max.request.size`와 consumer `max.partition.fetch.bytes`를 3 MiB 이상으로 설정한다. 40명 요청은 약 1.59 MiB이며 Kafka envelope가 추가된다.
- [ ] 운영 broker의 TLS/SASL 인증 정보를 코드·메시지·로그에 남기지 않는다.
