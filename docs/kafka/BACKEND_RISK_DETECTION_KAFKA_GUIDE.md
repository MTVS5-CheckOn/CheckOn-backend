# 백엔드 위험 탐지 Kafka 운영·개발 가이드

## HTTP와 무엇이 다른가

HTTP는 백엔드가 요청을 보내고 같은 연결에서 AI 응답을 받습니다. Kafka에서는 메시지를 broker에 저장한 뒤 나중에 상대 서비스가 읽습니다. 그래서 `POST /api/v1/detection-runs`는 AI 결과가 아니라 **접수 상태(`202 Accepted`)** 를 반환하고, 화면은 `GET /api/v1/detection-runs/{runId}`로 진행 상태를 확인합니다.

| 관심사 | HTTP 동기 호출 | 현재 Kafka 설계 |
| --- | --- | --- |
| 요청 성공의 의미 | AI가 즉시 200 응답 | DB에 Run/Attempt/Outbox를 함께 저장 |
| 실제 AI 전송 | 호출 트랜잭션 중 | Outbox 발행 후 독립 Adapter가 HTTP 호출 |
| 결과 수신 | 호출 반환값 | Adapter가 `completed`/`failed`로 변환 후 consumer가 반영 |
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
Outbox Publisher ── requested.v1 ──▶ Backend HTTP Adapter
                                      │ POST /v1/detect
                                      ▼
                                    AI API
                                      │ 200 / 4xx / 5xx
                 completed.v1 / failed.v1
                                      ▼
Backend Result Listener → Inbox 중복 방지 → 응답 검증·신호 저장 또는 실패 기록
```

### 코드 책임

- `KafkaDetectionRequestService`: 기존의 AI 요청 DTO를 versioned event로 감싸 Outbox에 적재합니다.
- `KafkaOutboxPublisher`: `PENDING` Outbox를 broker로 보냅니다. 최대 8회 실패하면 Run을 `FAILED`로 전환합니다.
- `KafkaDetectionHttpAdapterListener`: `requested`를 소비하고 일시적인 AI HTTP 실패를 재시도합니다.
- `KafkaDetectionHttpAdapter`: 필수 HTTP 헤더를 만들고 AI의 200/4xx 응답을 내부 `completed`/`failed` 이벤트로 변환합니다.
- `KafkaDetectionResultListener` / `KafkaDetectionResultConsumer`: 내부 결과를 재시도·DLT 정책과 함께 소비합니다.
- `KafkaInboxRepository`: AI가 같은 결과를 여러 번 보내도 `event_id` 한 번만 반영합니다.
- `DetectionResponseStorageService`: HTTP 시절과 같은 강한 응답 검증과 원자적 저장을 계속 담당합니다.

## v0.2 부재·복귀 근거는 어떻게 만드는가

새 `detection_evidence`는 학습 기록이 없는 사실도 AI가 근거로 사용할 수 있게 합니다. 그래서 **학습 이벤트가 0개라는 이유만으로 Run을 거절하지 않습니다.** 활성이고 AI 전송이 허용된 학생이 한 명이라도 있으면 요청과 Outbox가 생성됩니다.

| AI 논리 source | 현재 백엔드 생성 방식 | 안정적 `record_id` | 근거 보기 |
| --- | --- | --- | --- |
| `assignment_week_summary` | `detection_assignment_week_summaries` projection에서 최근 10주 조회 | `assignment-summary:{student_alias}:{week_start}` | Alert 상세에 저장된 source/id/AI 요약 |
| `student_week_activity` | 백엔드 소유 `learning_records`를 학생·서울 기준 주차별로 세고, 없는 주도 0 행 생성 | `activity-summary:{student_alias}:{week_start}` | Alert 상세에 저장된 source/id/AI 요약 |
| `student_status_history` | `detection_student_status_history`의 분석 주 `paused → returned` 조회 | `status-history:{student_alias}:{occurred_at}` | Alert 상세에 저장된 source/id/AI 요약 |

`V15__create_detection_evidence_projections.sql`이 과제 projection과 상태 이력의 테넌트/RLS 경계를 만듭니다. 현재 Assignment·휴원 업무 도메인은 아직 없으므로, 그 기능이 생길 때 해당 업무 저장 트랜잭션에서 이 projection/history를 채우는 writer를 추가해야 합니다. 그 전에는 과제 projection의 없는 주가 `0/0`으로 전송되므로 실제 과제 미제출 운영 판단에는 사용하면 안 됩니다. 활동 수는 현재 존재하는 `learning_records`만 포함합니다. 출결·상담 도메인이 추가되면 같은 주간 집계에 포함할지 제품 정책과 함께 확장합니다.

AI 결과는 새 근거에서 `(source_table, record_id)`가 당시 Run의 immutable snapshot에 **정확히 존재할 때만** 저장됩니다. 이는 AI가 다른 학생·다른 주의 근거를 인용하는 것을 막습니다. 기존 `learning_event` source 명칭은 이미 저장된 v0.1 응답을 읽기 위한 호환 예외입니다.

## 설정과 로컬 실행

1. `.env`를 한 번만 준비한 뒤 프로젝트 루트에서 `powershell -ExecutionPolicy Bypass -File .\scripts\run-local-kafka.ps1`을 실행합니다. 이 스크립트는 PostgreSQL·Kafka만 기동하고, `.env`를 현재 프로세스에 읽고, Gradle daemon을 새로 시작해 백엔드를 실행합니다. `FLYWAY_DB_USERNAME`을 이미 쓰는 기존 `.env`도 `FLYWAY_DB_USER`로 자동 호환합니다.
2. 백엔드를 로컬에서 실행할 때 기본 broker는 `localhost:9094`입니다. Docker Compose 안에서 실행하는 백엔드는 `kafka:19092`를 사용합니다. 필요하면 `KAFKA_BOOTSTRAP_SERVERS`로 바꿉니다.
3. AI 서버를 `http://localhost:8000`에서 실행합니다. 주소가 다르면 백엔드를 시작하기 전에 `AI_BASE_URL`을 설정합니다. 백엔드가 Docker 안에서 실행되고 AI가 호스트에 있다면 Windows Docker Desktop 기준 `http://host.docker.internal:8000`을 사용합니다.
4. 실제 Kafka 연동을 끄고 DB/웹 테스트만 실행하려면 `CHECKON_KAFKA_ENABLED=false`를 사용합니다. 이 경우 Outbox는 기록되지만 publisher/listener는 실행되지 않습니다.
5. 토픽 확인은 `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:19092 --list`로 합니다. 현재 개발 Compose는 토픽 자동 생성을 허용합니다. 운영은 토픽, 파티션 수, retention을 인프라에서 명시 생성해야 합니다.
6. v0.2 40명 요청은 약 1.59 MiB이므로 Kafka broker와 백엔드 producer/consumer는 3 MiB로 맞춥니다. AI HTTP 서버와 API Gateway의 request body 제한도 3 MiB 이상으로 맞춥니다.

### 등록 API 없이 빠르게 Kafka 시연하기

아직 학생·반·학습 기록 API를 준비하지 않았거나 Kafka 연결만 시연해야 하면 아래 명령을 사용합니다.

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\run-local-kafka.ps1 -PrepareDemoData
```

이 옵션은 **로컬 dev 프로세스에서만** 고정 가명 교사·학생·반과 기준일 학습 기록 하나를 idempotent하게 준비하고, dev 테스트 인증을 켭니다. 시연 학습 기록의 AI `source`는 OpenAPI enum에 포함된 `studentHome`을 사용합니다. 운영 API의 `NO_LEARNING_RECORDS` 규칙이나 `.env`는 바꾸지 않습니다. 로컬 DB에는 `Kafka 시연`으로 식별되는 전용 fixture 행만 생성·갱신하며 기존 행은 삭제하거나 변경하지 않습니다. 콘솔에 표시된 날짜를 Apidog `analysisDate`에 넣고, `Authorization` 헤더 없이 `POST /api/v1/detection-runs`를 호출합니다. 성공하면 먼저 `202 REQUESTED`가 반환되고, 독립 Adapter가 AI HTTP를 호출한 뒤 상태 조회에서 `SUCCEEDED` 또는 `FAILED`를 확인할 수 있습니다.

위 명령은 Backend 내장 HTTP Adapter를 명시적으로 끕니다. 별도 `C:\work\project\checkon-kafka-adapter`를 실행해야 requested 이벤트가 AI HTTP로 전달됩니다. 독립 Adapter와 Backend 내장 fallback을 동시에 켜면 서로 다른 consumer group이 같은 요청을 각각 처리하므로 금지합니다.

로컬에서는 반드시 Backend 실행이 완료된 뒤 독립 Adapter를 실행합니다. Backend 실행 스크립트가 Gradle daemon을 정리하므로 순서를 반대로 하면 먼저 실행한 Adapter가 종료될 수 있습니다. 정상 실행 시 포트는 Backend `8080`, 독립 Adapter `8081`, Kafka `9094`입니다.

## 운영에서 특히 확인할 것

- **Outbox가 쌓임**: broker 장애일 수 있습니다. `PENDING`/`FAILED` 수와 `last_error`를 점검합니다. `FAILED`이면 Run도 `KAFKA_PUBLISH_FAILED`로 실패 처리되어 새 요청이 가능합니다.
- **requested DLT가 쌓임**: AI 서버 연결 또는 5xx가 재시도 후에도 실패했거나 Adapter 내부 오류가 발생한 것입니다. DLT handler가 내부 failed 이벤트를 발행하지만, 원문과 AI 로그를 함께 확인합니다.
- **completed/failed DLT가 쌓임**: 결과 계약 검증 또는 DB 저장이 실패한 것입니다. DLT 원문과 `event_id`로 원인을 고친 뒤 재처리합니다. DLT를 바로 비우지 않습니다.
- **순서**: key가 `tenant_alias`이므로 같은 강사의 메시지는 같은 partition에 들어갑니다. 전체 강사 간 순서는 보장하지도 필요하지도 않습니다.
- **중복**: producer와 consumer 모두 at-least-once입니다. Adapter의 HTTP 재호출은 requested envelope의 동일 `tenant_alias`·`request_id`·`idempotency_key`와 body·snapshot hash를 유지합니다. 결과 중복은 Inbox/상태 전이로 안전하게 처리합니다.
- **advisory 신호**: `advisory=true`는 DB에 보존하되 Alert와 Todo를 만들지 않습니다. 강사에게 즉시 할 일을 만들지 않는 학생 상세 참고 정보입니다.
- **보안**: `tenant_alias`는 가명 식별자입니다. 메시지에 teacher ID, student ID, 실명, 연락처를 넣지 않습니다. 운영 TLS/SASL, ACL, retention은 배포 인프라에서 별도 설정합니다.
- **근거가 거절됨**: AI 완료 payload의 `source_table`과 `record_id`가 요청 snapshot에 있던 정확한 쌍인지 먼저 확인합니다. 새 부재·복귀 근거는 `record_id`만 맞아도 통과하지 않습니다.
- **`AI_HTTP_400`이고 `learning_events.*.source`가 거절됨**: AI OpenAPI는 `trackA`, `trackB`, `studentHome`만 허용합니다. 시연 fixture는 `studentHome`을 사용합니다. 운영 학습 기록의 백엔드 `sourceType=MANUAL`을 어느 AI 값으로 변환할지는 의미가 다른 필드이므로 AI·기획과 매핑을 확정한 뒤 별도 변환 계층에 반영해야 합니다.

## 변경 시 순서

이벤트 payload를 바꾸려면 먼저 `docs/contracts/risk-detection-kafka.asyncapi.yaml`을 수정하고 AI 팀과 호환성을 확인합니다. optional 필드 추가처럼 하위 호환이면 `schema_version`을 유지할 수 있지만, 의미 변경·필수 필드 변경·삭제는 새 버전 topic을 만들어 병행 운영합니다. Java DTO, Flyway, 테스트, AI 가이드를 그 다음에 함께 바꿉니다.
