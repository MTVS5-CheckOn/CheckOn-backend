# M2 문제 출제 Kafka 구현 결정 기록

기준 문서는 `14_m2_backend_erd (1).md`, `15_m2_backend_integration_spec (1).md`
(2026-08-07)이며, AI 팀의 T5 후속 결정 전에도 교체 가능한 경계부터 구현한다.

## 이번 구현에서 확정한 안전 기본값

1. 백엔드는 인증된 `teacherProfileId`만 테넌트 원본으로 신뢰한다.
2. Kafka에는 내부 UUID나 실명 대신 `tn_`, `st_`, `cl_` opaque alias를 보낸다. `tn_`은 위험 탐지 Kafka가 V14에서 만든 공용 routing alias를 재사용하고, 문제 출제는 별도 tenant alias 체계를 만들지 않는다.
3. 요청 이벤트는 Transactional Outbox로 저장하고 Kafka 발행은 DB 트랜잭션 밖에서 한다.
4. 요청 토픽 key는 tenant alias다. 같은 강사의 출제 요청 순서를 보존한다.
5. 결과 consumer는 `event_id`와 요청의 terminal 상태 전이를 함께 검사한다.
6. 계약 위반 이벤트는 재시도하지 않고 DLT로 보낸다. 일시적인 처리 실패만 두 번 재시도한다.
7. v1 입력은 `teacher_manual`, `language`, `mcq`, 지문 없음, 1~10문항으로 제한한다.
8. `manual_targets`는 AI taxonomy의 논리 스킬 노드 ID이며 개인정보 alias가 아니다.
9. 강사 검토 상태와 발행 상태는 AI 검증 상태와 분리한다. 이번 변경은 계약 부재로 승인·발행을 구현하지 않는다.
10. 공용 `ai_tenant_aliases`는 위험 탐지 결과 라우팅과의 호환성을 유지하고, 문제 출제 전용 `ai_class_aliases`, 요청, Outbox, 소비 이력 4개 테이블에는 FORCE RLS를 적용한다.

## 후속 문서에 따라 바뀔 수 있도록 격리한 부분

- 토픽 이름, consumer group, 배치 크기와 재시도 값은 환경 설정으로 분리했다.
- 이벤트 envelope와 payload 변환은 Kafka integration 계층에 둔다.
- AI 결과 본문은 `jsonb` 원문으로 보존한다. 후속 문항 상세 계약이 오면 별도 read model로 투영한다.
- `problem_generation.*`와 문서의 `worker_job.*` terminal 이벤트를 모두 받을 수 있게 결과 해석을 한 곳에 둔다.

## 현재 문서 결함 때문에 의도적으로 구현하지 않은 범위

- 완료 조회 예시에 문두·선지·정답·해설이 없고 Step 3 상세 API도 미구현이므로 문항 검토·수정·승인·발행 API는 만들지 않았다.
- 스킬 노드 목록 조회 계약이 없어 taxonomy catalog를 하드코딩하지 않았다.
- 약점 자동 출제는 현재 AI에서 실패하므로 열지 않았다.
- 요청 쿼터의 기준 기간·상한·초과 응답 계약이 없어 임의 제한을 만들지 않았다.
- Kafka 결과에 `problem_request_id` 또는 이를 가리키는 `correlation_id`가 반드시 필요하다고 결정했다. 이 값이 없으면 안전하게 테넌트 요청을 찾을 수 없어 DLT 대상이다.
- AI 결과 영속화와 실제 메시지 크기 상한은 후속 합의가 필요하다.

## 운영 전 확인 사항

- AI consumer가 `event_id` 또는 `idempotency_key`를 영속 저장해 중복 요청을 멱등 처리하는지 확인한다.
- Broker TLS/SASL, ACL, 토픽 파티션 수, 보존 기간을 배포 환경에서 확정한다.
- 전체 문항 snapshot을 결과 이벤트에 싣거나 안정적인 상세 조회 API를 제공하는지 확정한다.
- AI 팀 최신 스키마 fixture로 producer/consumer 계약 테스트를 상호 실행한다.
