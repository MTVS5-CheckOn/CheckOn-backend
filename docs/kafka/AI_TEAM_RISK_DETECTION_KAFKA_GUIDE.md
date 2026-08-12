# AI 팀용 위험 탐지 연동 가이드

## 결론

AI 서버는 Kafka를 연결하거나 메시지를 소비·발행하지 않습니다. AI 팀은 기존 `POST /v1/detect` HTTP API만 운영하면 됩니다. Kafka는 백엔드 내부 비동기 처리 경계이며, 백엔드의 `KafkaDetectionHttpAdapter`가 Kafka 요청을 소비해 AI HTTP API를 호출하고 그 응답을 다시 백엔드 Kafka 결과 이벤트로 변환합니다.

```text
Backend Outbox → requested Kafka → Backend HTTP Adapter → AI POST /v1/detect
                                                    200/4xx/5xx
Backend Result Consumer ← completed/failed Kafka ← Backend HTTP Adapter
```

## AI 서버가 제공할 계약

- endpoint: `POST /v1/detect`
- operationId: `detectRiskSignals`
- 요청 본문: `DetectRequest` 전체
- 응답: `200`, `400`, `409`, `500`
- FastAPI 기본 `422`는 사용하지 않음

필수 헤더는 다음 세 개입니다.

| 헤더 | 백엔드가 넣는 값 | 재시도 규칙 |
| --- | --- | --- |
| `X-Tenant-Id` | Kafka 요청의 가명 `tenant_alias` | 같은 업무 요청에서 유지 |
| `X-Request-Id` | HTTP 시도마다 새 UUID | HTTP 재시도마다 새 값 |
| `Idempotency-Key` | Kafka 요청의 `idempotency_key` | 같은 업무 요청에서 반드시 유지 |

`Idempotency-Key`가 같고 `snapshot_hash`도 같으면 AI는 저장된 성공 응답을 다시 반환할 수 있어야 합니다. 같은 키에 다른 `snapshot_hash`가 오면 `409`를 반환합니다.

## `detection_evidence`와 hash

요청의 `detection_evidence`는 기존 OpenAPI 정의대로 전달됩니다. 백엔드는 배열을 포함해 `snapshot_hash`를 계산하며, AI는 요청의 `(source_table, record_id)`만 결과 evidence로 인용해야 합니다.

- `assignment_window`: 최근 10주 과제 예정/제출 집계
- `weekly_activity`: 최근 10주 활동 수이며 0도 명시
- `enrollment_transition`: 분석 주의 휴원 후 복귀 이력

필드 누락과 빈 배열은 같은 hash이고, 배열 순서만 달라진 경우도 같은 hash입니다. 집계값이 달라지면 hash가 달라집니다.

## 응답 처리 책임

AI는 Kafka envelope를 만들 필요가 없습니다. HTTP 응답만 OpenAPI에 맞추면 이후 처리는 백엔드가 담당합니다.

- `200`: 백엔드가 `risk-detection.completed` 내부 이벤트 생성
- `400`: 잘못된 요청이므로 백엔드가 재시도 없이 실패 처리
- `409`: 멱등 키 충돌이므로 백엔드가 재시도 없이 실패 처리
- `500` 또는 연결 실패: 백엔드가 HTTP 호출을 재시도하고, 모두 실패하면 실패 처리

## AI 팀 확인 체크리스트

- [ ] `/openapi.json`에 `POST /v1/detect`와 `operationId: detectRiskSignals`가 있다.
- [ ] 세 필수 헤더가 모두 required로 표시된다.
- [ ] 200·400·409·500 스키마가 문서와 실제 응답에서 일치한다.
- [ ] 동일 Idempotency-Key/동일 hash 재호출이 중복 실행되지 않는다.
- [ ] HTTP 요청 최대 크기가 40명 기준 약 1.59 MiB 이상을 허용한다.
- [ ] `detection_evidence`와 evidence 역참조 규칙을 지킨다.
- [ ] 학생 실명·내부 UUID·연락처를 요구하거나 로그에 남기지 않는다.

AI 팀은 Kafka broker 주소, topic, consumer group, DLT를 설정하지 않아도 됩니다. 해당 항목은 모두 백엔드 책임입니다.
