# 문제 출제 Backend 상태 문서 이전 안내

- 기존 스냅샷 기준일: 2026-08-13
- 문서 상태: `SUPERSEDED`
- 대체일: 2026-08-14

이 파일의 기존 내용은 Adapter가 taxonomy catalog에서 `skill_node_id`를 선택하고 AI가 Kafka를 직접
소비하는 초기 계약을 포함하고 있어 더 이상 현재 통합 계약으로 사용하지 않는다. 과거 내용은 Git
이력에서 확인한다.

현재 정본은 다음 두 문서다.

1. 제품·Backend 정책: [`POLICY_REGISTER.md`](POLICY_REGISTER.md)의 `PG-001`~`PG-005`
2. 팀 간 실행 계약: [`PROBLEM_GENERATION_AI_TEAM_HANDOFF.md`](PROBLEM_GENERATION_AI_TEAM_HANDOFF.md)

현재 상태를 요약하면 다음과 같다.

- Backend 부모 요청·child fan-out·Transactional Outbox·결과 consumer는 구현돼 있다.
- AI HTTP 계약은 commit `7bc3d6592c6ccd48432ba941053a0673a922dca6`을 기준으로 고정한다.
- Adapter는 선택된 diagnosis node를 변경하지 않고 AI HTTP로 전달해야 한다.
- Backend node provenance, dropped slot projection, 3 MiB 초과 `RESULT_TOO_LARGE` 정책은 구현 예정이다.
- revision의 Backend·Adapter·Frontend 연동은 영상 MVP에서 제외한다.
- 실제 AI→Adapter→Kafka→Backend→review E2E는 아직 검증되지 않았다.
