# 문제 출제 스튜디오 — Backend 최종 확정 및 AI 팀 전달본

- 수신 대상: M2 문제 출제 AI 팀
- 기준 문서: `2026-08-12_m2_http_contract_reply_to_BE.md`, `2026-08-12_m2_studio_pending_items_confirmed.md`
- 백엔드 기준 브랜치: `codex/feature/problem-generation-kafka-recovery/37`
- 작성일: 2026-08-12
- 문서 상태: `BACKEND_CONFIRMED` — adapter 확인과 AI 운영 선행조건 해소 필요

## 1. 확정 원칙

공통으로 표준화할 비동기 신뢰성 규칙은 이미 운영 경계가 구현된 **위험탐지 계약을 정본**으로 한다.
문제 출제에서만 필요한 장시간 AI job, 다중 target과 문항 본문 처리는 기능별 계약으로 분리한다.

### 1.1 위험탐지 정본을 따르는 공통 규칙

- 요청과 Transactional Outbox를 같은 DB 트랜잭션으로 저장하고 외부 통신은 트랜잭션 밖에서 수행한다.
- Kafka envelope은 `schema_version`, `event_id`, `correlation_id`, `causation_id`, opaque `tenant_alias`와 기능별 실행 ID를 가진다.
- Kafka key는 opaque `tenant_alias`를 사용한다.
- consumer는 Inbox의 `event_id` unique 제약으로 멱등 처리한다.
- Kafka 결과 처리 실패는 최초 포함 총 3회, 1초·2초 간격으로 재시도한 뒤 DLT로 보낸다.
- 요청 snapshot과 hash는 불변으로 보존하고, 결과의 소유권·근거·ID를 요청 snapshot에 대조한다.
- attempt 이력을 보존하며 이미 대체됐거나 terminal 이후 늦게 온 결과로 확정 상태를 되돌리지 않는다.
- AI 호출에는 실명·내부 UUID를 보내지 않고 `tn_`, `st_`, `cl_` alias만 사용한다.

### 1.2 문제 출제에만 적용하는 기능별 규칙

- 영역×유형 셀 하나당 child execution 하나로 fan-out한다.
- adapter의 AI job 관찰 상한은 21분이다.
- adapter가 items를 조회해 정규화된 Kafka 결과 이벤트에 문항 본문 전량을 싣는다.
- 일부 child만 성공할 수 있으므로 부모 상태에 `PARTIAL_SUCCESS`를 둔다.
- AI 검증 상태와 교사 선택·저장·학생 발행 상태를 분리한다.

위 항목은 위험탐지에 역으로 적용하지 않는다. 특히 위험탐지 payload에 문항 본문 전달 방식이나
`PARTIAL_SUCCESS`를 공통 규칙처럼 추가하지 않는다.

## 2. 통신 및 소유권 경계

- AI 서버는 HTTP API만 제공한다.
- Kafka-HTTP adapter는 CheckOn Backend와 분리된 별도 서버다.
- Backend는 Kafka 요청 발행과 결과 소비, 프론트 Step 1~4, 교사 검토·저장·발행을 소유한다.
- adapter는 Kafka 요청 소비, AI HTTP 호출, retry·polling, child 매핑, 결과 정규화와 Kafka 결과 발행을 소유한다.
- AI는 생성·진단·검증과 AI 내부 `job_id`, `execution_id`, `set_id`를 소유한다.
- Backend 안에 AI HTTP client나 polling scheduler를 추가하지 않는다.

## 3. 확정된 문제 생성 계약

1. 부모 요청의 `targets[]` 합계는 최대 20문항이다.
2. adapter는 영역×유형 셀 하나마다 `POST /v1/problems`를 한 번 호출한다.
3. child에는 `problem_request_id`, `problem_execution_id`, `target_index`, `adapter_execution_id`, AI `execution_id`, `job_id`, `set_id`를 각각 보존한다.
4. `X-Request-Id={problem_execution_id}:{target_index}`는 로그 추적용이다. Backend↔adapter 상관관계는 명시적인 child 필드가 정본이다.
5. POST timeout은 300초이며, 응답 상태가 비종단일 때만 polling한다.
6. 관찰 상한 21분을 넘으면 child는 `timed_out`이다. 이후 AI 성공 결과는 감사·회수용으로 남기되 terminal 부모 상태를 번복하지 않는다.
7. `GET /v1/problems/{job_id}/items`가 문항 조회 정본이다. adapter는 문항을 정규화 결과 이벤트에 전량 적재한다.
8. AI 멱등 보존은 30일이다. 같은 key와 같은 canonical body는 같은 job을, 다른 body는 409를 반환한다. 실패 job을 새로 실행하려면 새 key가 필요하다.
9. Backend snapshot에는 강사가 약점 셀을 선택했다는 의미를 보존하고, adapter가 AI v1 호출의 `target_source`를 지원 값 `teacher_manual`로 변환한다. 이를 `weakness_auto`로 보내지 않는다.

HTTP 5xx·네트워크 오류·timeout의 재시도 간격은 adapter 기능 설정이다. Kafka 결과 consumer의 공통
재시도 1초·2초와 동일한 값으로 강제하지 않으며, `Retry-After`가 있으면 이를 우선한다.

### 3.1 Backend가 발행하는 child 요청 이벤트

`schema_version=pg-child-request-1`이며 Kafka key는 `tenant_alias`다. `payload.request`는 adapter가
AI `POST /v1/problems` body로 사용할 단일 영역·유형 요청이다.

```json
{
  "event_id": "<uuid>",
  "event_type": "problem_generation.requested",
  "occurred_at": "<instant>",
  "tenant_id": "tn_...",
  "schema_version": "pg-child-request-1",
  "correlation_id": "<problem_request_id>",
  "causation_id": null,
  "payload": {
    "problem_request_id": "<uuid>",
    "problem_execution_id": "<uuid>",
    "target_index": 0,
    "idempotency_key": "pgc_...",
    "request": {
      "target_kind": "student",
      "target_ref": "st_...",
      "target_source": "teacher_manual",
      "area_tag": "language",
      "type_tags": ["fact"],
      "item_format": "mcq",
      "count": 3,
      "requested_difficulty": "medium",
      "target": "auto",
      "passage": null,
      "snapshot_hash": "sha256:..."
    }
  }
}
```

### 3.2 adapter가 반환해야 하는 child 결과 필드

기존 `worker_job.succeeded|failed|cancelled|running` envelope에 다음 필드를 반드시 포함한다.

- `correlation_id`와 `payload.problem_request_id`: 동일한 부모 요청 UUID
- `payload.problem_execution_id`: Backend가 요청에 넣은 child UUID
- `payload.target_index`: Backend 요청과 동일한 0-based index
- `payload.adapter_execution_id`: adapter가 생성한 UUID
- `payload.execution_id`, `job_id`, `set_id`: AI가 반환한 식별자
- 성공 결과의 `payload.result.problems` 또는 `items`: 정규화한 문항 전량

Backend는 child ID와 target index, tenant alias, 고정된 adapter/AI ID를 검증한다. 불일치 이벤트는
계약 위반으로 처리하며 재시도 대상이 아니라 DLT 대상이다.

## 4. 상태 집계

| child 집계 | 부모 상태 |
| --- | --- |
| 하나라도 실행 중 | `RUNNING` |
| 전부 종단, 성공 문항 1개 이상, 실패 child 없음 | `SUCCEEDED` |
| 전부 종단, 성공 문항 1개 이상, 실패·timeout child 있음 | `PARTIAL_SUCCESS` |
| 성공 문항 0개, 전부 `rejected_insufficient` | `SUCCEEDED`(0건) |
| 성공 문항 0개, 실패·timeout 1개 이상 | `FAILED` |

`verified`와 `needs_review`는 발행 후보가 될 수 있다. `verification_unavailable`은 교사 판단이 필요하고,
`dropped`는 문항이 존재하지 않는다. 이 상태들은 교사 승인이나 학생 발행 완료를 뜻하지 않는다.

## 5. Step 1 진단

- 최종 판정 정본은 AI `POST /v1/diagnosis`다.
- 호출은 `Frontend → Backend → adapter → AI` 동기 HTTP이며 AI timeout은 5초다.
- `snapshot_hash`가 같은 경우에만 같은 진단을 재사용한다.
- AI 오류 시 과거 진단이나 Backend 자체 계산을 섞지 않고 빈 grid를 반환한다.
- AI의 측정 5영역 원본 셀은 보존하고, 화면 표시 병합은 프론트 책임으로 둔다.

다만 현재 Backend 학습기록에는 AI 입력의 `tag_confirmed`, `skill_node_id`가 없다. 임의 기본값을 만들어
AI에 보내면 안 되므로 해당 입력의 생성·저장 정책을 확정하기 전까지 기존 Backend 집계를 즉시 제거하지
않는다. 이는 계약 확정과 구현 완료를 구분하기 위한 조치다.

## 6. 영역별 자료와 v1 화면 제한

AI는 5영역을 지원하지만 `language` 외 영역은 passage/work/material 자료 명세가 필요하다. 현재 프론트에는
자료 입력 화면이 없으므로 v1 출제 선택은 `language`만 활성화한다. `media`가 약점으로 표시돼도
`language`로 변환하지 않고 “자료 입력 화면 미구현”으로 출제 버튼을 비활성화한다.

Step 1에서 선택한 영역·유형·문항 수는 Step 2로 그대로 이동한다. 두 화면의 12문항과 7문항 표시는 서로
다른 예시이므로 계약 충돌이 아니다.

## 7. 계약 정본과 현재 미해소 위험

- AI HTTP v1 정본: AI 저장소의 HTTP fixture 17종. 실제 schema가 채워질 때까지 OpenAPI는 정본이 아니다.
- adapter Kafka 정본: adapter 저장소의 HTTP→Kafka normalized fixture.
- 기존 AI `worker_job.*` fixture 4종: adapter 변환 결과를 검증하는 참고 계약으로 보존.

운영 전 반드시 해소할 항목은 다음 두 가지다.

1. AI 요청·결과의 인메모리 저장을 영속 저장소로 전환해 재기동 후 GET 손실을 막는다.
2. 신규 diagnosis/problems endpoint의 배포 파이프라인과 실제 라우팅을 준비한다.

HTTP 인증은 v1 내부망 전제로 미적용 상태를 수용한다. adapter가 신뢰망 밖에 배치되면 이 결정을 다시
열고 서비스 인증 계약을 추가한다.

## 8. Backend 구현 영향과 현재 상태

현재 Backend는 한 부모 요청에 AI ID 한 세트만 저장하고 있어 다음 변경은 아직 구현되지 않았다.

- child execution 테이블과 child별 AI ID·멱등 매핑
- 부모 `PARTIAL_SUCCESS` 상태와 집계 로직
- 21분 이후 late result 감사 기록
- 진단 adapter 호출과 AI 입력 snapshot 생성
- 비-language 선택 비활성화를 위한 API 표시 필드

따라서 이 문서는 계약 확정본이며, 현재 코드가 모두 반영됐다는 완료 보고가 아니다. Backend 변경은
별도 구현·Flyway·OpenAPI·BDD 테스트 단위로 진행한다.

## 9. 팀별 확인

| 팀 | 확인 대상 | 상태 |
| --- | --- | --- |
| Backend | 공통 정본, child 구조, 부모 집계, Step 1 진단 방향 | `CONFIRMED` |
| AI | HTTP fixture, 30일 멱등, 300초 POST, 21분 상한 전제 | AI 회신 기준 `CONFIRMED` |
| Adapter | 영속 work ledger, HTTP retry/polling, result outbox, normalized fixture | 확인 필요 |
| 제품/Frontend | 부모 최대 20, 비-language 비활성, 빈 grid 실패 표시 | 확인 필요 |
