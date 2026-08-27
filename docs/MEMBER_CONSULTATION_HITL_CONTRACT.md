# member 상담 HITL 후속 계약

PR8은 학부모 상담 원장과 발행 메시지 경계까지만 만든다. AI 초안 생성과 강사 승인 API는
상담 접수 트랜잭션에 포함하지 않는다. 따라서 AI·Kafka·HTTP 전면 장애여도 상담 저장 성공은
`201`이다.

## 강사 검토·승인 API 후보

- `GET /api/v1/teachers/me/member-consultations/{consultationId}/draft`
- `POST /api/v1/teachers/me/member-consultations/{consultationId}/publication`

경로와 소유 패키지는 팀 확정 전 후보일 뿐이다. PR8은 위 API를 구현하지 않는다.

## 발행 책임

- 강사 승인 서비스가 `member_consultation_messages.published_at`을 쓴다.
- `member_consultation_messages`에는 승인·발행된 본문만 INSERT한다.
- AI raw 초안은 이 테이블에 저장하지 않는다.
- 학부모 상세 응답은 이 테이블의 행만 읽으므로 미승인 초안이 섞일 수 없다.

## evidence 없는 초안

AI 결과의 근거 목록이 비어 있으면 강사 승인 서비스가 발행을 거절해야 한다. 이 검증은
학부모 조회가 아니라 강사 publication 명령의 트랜잭션 안에서 수행한다.

## 결과 연결 지점

향후 adapter 결과 listener는 member가 소유한 포트를 호출해 `ai_status`와 `ai_job_id`만 갱신한다.
원문·초안 본문을 listener payload로 다시 저장하지 않는다. 결과 상태 매핑은 다음과 같다.

| adapter 결과 | member `ai_status` |
|---|---|
| `generated` | `READY` |
| `template_only` | `TEMPLATE_ONLY` |
| `rejected_insufficient` | `REJECTED_INSUFFICIENT` |
| 실패·timeout·5xx·알 수 없는 값 | `UNAVAILABLE` |

PR8에는 결과 listener의 프로덕션 호출자가 없다. `CounselAiAdapter`는 별칭·마스킹 형식의 순수
변환만 담당하며 네트워크·Kafka·LLM 클라이언트를 갖지 않는다.

## 아직 열지 않는 경로

- 반을 특정하지 못해 `class_ref`가 없으면 더미 `cl_` 별칭을 만들지 않고 AI 전송을 생략한다.
- TODO(MB-09): 상담 취소 가능 시점과 추가 질문 횟수가 확정되기 전에는 학부모 UPDATE 정책과
  cancellation endpoint를 열지 않는다.
