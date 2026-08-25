# 월간 리포트 구현 결정 및 팀 인계

## 1. AI팀 문서보다 현재 서버 계약을 우선한 지점

1. AI `POST /v1/reports`는 동기 200이지만 Backend 공개 API는 202다. AI 계약을 바꾼 것이 아니라, 최대 1,350초 호출을 Kafka Adapter worker 안에서 수행하도록 실행 경계를 감쌌다.
2. 기능 패키지만 추가하지 않고 월간 리포트 전용 scheduler, Kafka consumer group, Inbox/Outbox를 둔다. 현재 Adapter는 단일 배포물이지만 장시간 리포트 생성은 다른 기능 worker pool을 점유하지 않는다.
3. AI 문서의 `guardian_ref`는 실제 학부모 ID가 아니다. 기존 `ai_guardian_aliases`의 강사-학생 범위 opaque alias를 사용하고, 실제 발송 대상은 Backend의 활성 parent 관계로 다시 확인한다.
4. AI 상태와 발송 상태를 한 enum으로 합치지 않는다. `template_only`와 `rejected_insufficient`는 정상 AI 결과지만 발송 가능한 `ready`는 아니다.
5. AI 문서에 없는 학생명·반·월·PDF·수신자·발송은 Backend facade가 조합한다. AI에 실명·연락처·반 평균·석차를 보내지 않는다.

## 2. 명세에 없어 Backend가 보완한 결정

- 생성 입력은 프론트가 전달하지 않고 Backend의 최신 GENERATED 진단과 월별 채점 결과에서 만든다.
- 정기 리포트는 학생·월당 하나, 수시 리포트는 별도 이력으로 둔다.
- PDF 자체 대신 `storage_key + sha256 + page_count + revision`을 저장한다.
- 새 PDF 등록 시 이전 artifact를 보존하되 `SUPERSEDED`로 전환한다.
- v1 발송 채널은 실제로 모델링된 학부모 앱만 사용한다.
- 일괄 발송은 전체 롤백 대신 report별 부분 결과를 반환한다.

## 3. 정책 확정이 필요한 권장 구현

정본 유일성, PDF 저장소·보존 기간, 실제 발송 provider, 재발송 제한, 승인 actor/시각의 별도 모델은 아직 제품 확정이 없다. 현재 구현은 교체 가능한 권장 기본값이며 `POLICY_REGISTER.md`의 REP-002~004에 기록했다.

## 4. AI팀에 요청할 사항

- `/v1/reports` 생성 request/response와 block revision 2경로를 named OpenAPI schema로 노출한다.
- `source.weakness_map`, `misconceptions`, `item_results`, `metrics`, `time_series`, `cell_min_items`의 완전한 JSON Schema와 고정 fixture를 제공한다.
- `ready | rejected_insufficient | template_only`별 `blocks`, `block_count`, `unproduced_sections` 불변식을 명시한다.
- `X-Tenant-Id`, `X-Request-Id`를 OpenAPI parameters에 실제로 노출한다.
- 1,350초 상한이 낮아지면 각 prompt timeout과 전체 요청 상한을 같은 변경에서 통보한다.

## 5. 프론트엔드 인계(프론트 저장소 미수정)

- Backend 경로는 `/api/v1/report-studio/reports`, `/reports/{reportId}`, `/reports/{reportId}/artifacts`, `/deliveries:bulk`다.
- 생성은 202와 `Location`을 받고 상세를 재조회한다.
- 목록의 AI 상태·delivery 상태·recipient 상태·pageCount를 각각 표시한다.
- `ready + connected + artifact READY`가 아니면 선택을 비활성화하되 서버도 발송 시 재검증한다.
- PDF 쪽수 7/8 하드코딩을 제거하고 `pageCount`를 사용한다.
- 블록 수정·원문 복귀는 Backend facade가 Adapter 내부 proxy를 통해 AI revision 계약을 호출한다. 409에서는 강제 덮어쓰지 말고 최신 상세을 다시 조회한다.

## 6. 아직 구현하지 않은 외부 범위

- AI 서버 및 프론트엔드 코드 변경
- 실제 PDF 렌더링/업로드 provider
- 학부모 앱 push 또는 외부 메시지 provider
- 운영 Kafka topic 생성·ACL·retention과 실제 AI E2E
