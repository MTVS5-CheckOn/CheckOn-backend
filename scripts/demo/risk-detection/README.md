# 위험 탐지 17명 시연 시드

이 디렉터리는 AI 팀이 전달한 17명·12주 검증 데이터를 개발/시연 DB에 명시적으로 적재하기 위한 자료다. 운영 Flyway에는 포함하지 않는다. 강사·학생·반 UUID와 AI alias가 환경마다 다르므로 애플리케이션 시작 시 자동 실행해서는 안 된다.

## 실행 전 준비

1. Roster API나 기존 시드로 강사 한 명, 학생 17명, 반 2개를 만든다.
2. 각 학생의 `ai_student_aliases.alias`를 확인한다.
3. [`checkon_seed.sql`](./checkon_seed.sql)의 `teacher_id`, 기준 주 `monday`, 학생 alias 17개, 반 UUID 2개를 채운다.
4. `st_09`의 `teacher_student_relationships.started_at`은 기준 주 직전 1주 안으로 맞춘다.
5. `st_10`은 현재 ACTIVE 관계여야 한다. SQL이 기준 주의 `paused → returned` 이력을 추가한다.

```text
psql -h localhost -U checkon -d checkon
checkon=> \i 'scripts/demo/risk-detection/checkon_seed.sql'
checkon=*> COMMIT;
```

SQL은 `BEGIN` 후 검증 결과를 출력하고 자동 `COMMIT`하지 않는다. 반드시 대화형 psql에서 `\i`로 실행하고 결과를 확인한 뒤 같은 세션에서 `COMMIT;` 또는 `ROLLBACK;`을 입력한다. `psql -f`는 파일 종료와 함께 세션도 끝나 미완료 트랜잭션이 롤백되므로 이 스크립트에 사용하지 않는다. `learning_records`에는 재실행용 unique key가 없으므로, 커밋한 SQL을 같은 기준 주에 다시 실행하면 학습 기록이 중복된다.

## 백엔드 반영 범위

- R1·R3·R4·R6: `learning_records`
- R2: `detection_assignment_week_summaries`의 실제 행을 `assignment_window`로 전송
- R5: `detection_student_status_history`의 분석 주 `paused → returned`를 학생 `returned` 상태와 함께 전송
- 반 미배정: `class_ref=cl_unassigned`
- 수동 학기 맥락: `POST /api/v1/detection-runs` body의 `termContext`

```json
{
  "analysisDate": "2026-08-13",
  "termContext": "vacation"
}
```

`termContext`는 `normal`, `new_term`, `vacation` 중 하나이며 생략하면 `normal`이다.

## 현재 시드의 한계

전달본은 `st_12`를 미동의 학생으로 정의하지만 현재 백엔드에는 학생별 AI 동의 저장원이 없다. 임시 정책 `PRE_CONSENT_ALLOW_ALL`에서는 `st_12`도 요청에 포함된다. 전용 동의 기능과 저장 테이블이 구현되기 전에는 “응답 어디에도 없음” 기대값을 검증할 수 없다.

Roster, 학생별 시나리오 매핑, 반 소속, 신규생 시작일은 이 SQL이 만들지 않는다. 이는 실제 API가 생성하는 불변식과 alias 발급 경로를 그대로 검증하기 위한 의도적인 경계다.

## 로컬 E2E 자동 실행

백엔드(8080), Kafka, 독립 Adapter(8081), AI 서버(8000)가 실행 중이면 다음 스크립트가 별도 시연 테넌트에 Roster와 실제 alias를 만들고, 원본 SQL을 적재한 뒤 Detection 실행 완료까지 기다린다.

```powershell
$env:TEST_ACCOUNT_ID = '0198f000-0000-7000-8000-000000009000'
$env:TEST_TEACHER_PROFILE_ID = '0198f000-0000-7000-8000-000000009001'
.\scripts\run-risk-detection-e2e.ps1 -AnalysisDate 2026-08-14
```

스크립트는 기존 기본 시연 강사와 분리된 고정 UUID를 사용한다. 같은 기준 주의 학습 기록과 동일 분석일 실행이 이미 있으면 중복 생성하지 않고 기존 데이터를 재검증한다. 완료 JSON의 `runId`, `status`, `rulesSkipped`, 규칙별 신호 수, evidence 수, Alert 수로 Kafka 왕복과 결과 영속화를 확인할 수 있다.
