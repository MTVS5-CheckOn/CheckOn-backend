# 백엔드 위험 탐지 Kafka 운영·개발 가이드

## HTTP와 무엇이 다른가

HTTP는 백엔드가 요청을 보내고 같은 연결에서 AI 응답을 받습니다. Kafka에서는 메시지를 broker에 저장한 뒤 나중에 상대 서비스가 읽습니다. 그래서 `POST /api/v1/detection-runs`는 AI 결과가 아니라 **접수 상태(`202 Accepted`)** 를 반환하고, 화면은 `GET /api/v1/detection-runs/{runId}`로 진행 상태를 확인합니다.

| 관심사 | HTTP 동기 호출 | 현재 Kafka 설계 |
| --- | --- | --- |
| 요청 성공의 의미 | AI가 즉시 200 응답 | DB에 Run/Attempt/Outbox를 함께 저장 |
| 실제 AI 전송 | 호출 트랜잭션 중 | Outbox worker가 이후 발행 |
| 결과 수신 | 호출 반환값 | `completed`/`failed` consumer |
| 중복 | HTTP 재호출 위험 | event ID Inbox + request idempotency |
| 실패 복구 | 호출자 재시도 | producer retry, consumer retry/DLT, Run 상태 조회 |

Kafka는 HTTP를 단순히 URL 대신 topic으로 바꾸는 것이 아닙니다. **나중에 전달되고 중복될 수 있다**는 특성을 코드와 화면 상태에 반영해야 합니다.

## 구현 흐름

```text
02:10 Scheduler 또는 교사 POST
        │
        ▼
DetectionRun + Attempt + kafka_outbox_events (한 DB 트랜잭션)
        │
        ▼
Outbox Publisher ── requested.v1 ──▶ AI Consumer
                                      │
                 completed.v1 / failed.v1
                                      ▼
Backend Listener → Inbox 중복 방지 → 응답 검증·신호 저장 또는 실패 기록
```

### 코드 책임

- `KafkaDetectionRequestService`: 기존의 AI 요청 DTO를 versioned event로 감싸 Outbox에 적재합니다.
- `KafkaOutboxPublisher`: `PENDING` Outbox를 broker로 보냅니다. 최대 8회 실패하면 Run을 `FAILED`로 전환합니다.
- `KafkaDetectionResultListener` / `KafkaDetectionResultConsumer`: AI 결과를 재시도·DLT 정책과 함께 소비합니다.
- `KafkaInboxRepository`: AI가 같은 결과를 여러 번 보내도 `event_id` 한 번만 반영합니다.
- `DetectionResponseStorageService`: HTTP 시절과 같은 강한 응답 검증과 원자적 저장을 계속 담당합니다.

## 설정과 로컬 실행

1. `.env`를 한 번만 준비한 뒤 프로젝트 루트에서 `powershell -ExecutionPolicy Bypass -File .\scripts\run-local-kafka.ps1`을 실행합니다. 이 스크립트는 PostgreSQL·Kafka만 기동하고, `.env`를 현재 프로세스에 읽고, Gradle daemon을 새로 시작해 백엔드를 실행합니다. `FLYWAY_DB_USERNAME`을 이미 쓰는 기존 `.env`도 `FLYWAY_DB_USER`로 자동 호환합니다.
2. 백엔드를 로컬에서 실행할 때 기본 broker는 `localhost:9094`입니다. Docker Compose 안에서 실행하는 백엔드는 `kafka:19092`를 사용합니다. 필요하면 `KAFKA_BOOTSTRAP_SERVERS`로 바꿉니다.
3. 실제 Kafka 연동을 끄고 DB/웹 테스트만 실행하려면 `CHECKON_KAFKA_ENABLED=false`를 사용합니다. 이 경우 Outbox는 기록되지만 publisher/listener는 실행되지 않습니다.
4. 토픽 확인은 `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list`로 합니다. 현재 개발 Compose는 토픽 자동 생성을 허용합니다. 운영은 토픽, 파티션 수, retention을 인프라에서 명시 생성해야 합니다.

## 운영에서 특히 확인할 것

- **Outbox가 쌓임**: AI 또는 broker 장애일 수 있습니다. `PENDING`/`FAILED` 수와 `last_error`를 점검합니다. `FAILED`이면 Run도 `KAFKA_PUBLISH_FAILED`로 실패 처리되어 새 요청이 가능합니다.
- **DLT가 쌓임**: AI 결과 payload가 계약과 다르거나 DB 저장이 실패한 것입니다. DLT 원문과 `event_id`로 원인을 고친 뒤, 새 이벤트를 원 topic에 발행합니다. DLT를 바로 비우지 않습니다.
- **순서**: key가 `tenant_alias`이므로 같은 강사의 메시지는 같은 partition에 들어갑니다. 전체 강사 간 순서는 보장하지도 필요하지도 않습니다.
- **중복**: producer와 consumer 모두 at-least-once입니다. 중복은 장애가 아니라 정상 경우로 보고 Inbox/상태 전이로 안전하게 처리합니다.
- **보안**: `tenant_alias`는 가명 식별자입니다. 메시지에 teacher ID, student ID, 실명, 연락처를 넣지 않습니다. 운영 TLS/SASL, ACL, retention은 배포 인프라에서 별도 설정합니다.

## 변경 시 순서

이벤트 payload를 바꾸려면 먼저 `docs/contracts/risk-detection-kafka.asyncapi.yaml`을 수정하고 AI 팀과 호환성을 확인합니다. optional 필드 추가처럼 하위 호환이면 `schema_version`을 유지할 수 있지만, 의미 변경·필수 필드 변경·삭제는 새 버전 topic을 만들어 병행 운영합니다. Java DTO, Flyway, 테스트, AI 가이드를 그 다음에 함께 바꿉니다.
