# 위험 탐지 운영 작업

이 디렉터리의 스크립트는 배포 과정에서 자동 실행되지 않는다. EC2에서는 `/home/ubuntu/deployment`에서 한 단계만 실행하고, 출력 검토가 끝난 뒤 다음 단계로 이동한다. `.env`, 토큰, 원본 학습 데이터는 출력하지 않는다.

## AI_DEMO_FIXTURE 교정

`correct-ai-demo-fixture-source.sh`는 지정된 demo 강사의 `AI_DEMO_FIXTURE` 24건만 `MANUAL`로 교정한다.

1. `inspect`: 대상 건수와 source별 집계만 확인한다.
2. `apply`: 대상이 정확히 24건일 때만 ID, 기존 source, `updated_at`을 권한 `600` 파일로 백업하고 트랜잭션 업데이트한다.
3. `rollback <backup-file>`: 백업의 24개 ID만 현재 `MANUAL`에서 원래 source와 `updated_at`으로 복원한다.

기존 Detection run, snapshot, signal, evidence, alert 행은 조회하거나 수정하지 않는다.

## 배포 후 위험 탐지만 검증

`verify-risk-detection-ec2.sh`는 전체 세 기능 시연을 실행하지 않는다. 날짜는 해당 강사에게 아직 run이 없는 날짜를 선택한다.

실행 전에 실제 HTTPS Backend origin을 trailing slash 없이 `BACKEND_BASE_URL` 환경 변수로 설정한다. 저장소가 운영 주소를 추정하지 않는다.

1. `preflight <yyyy-MM-dd>`: Backend health와 기존 일별 run 충돌을 확인한다.
2. `submit <yyyy-MM-dd>`: 위험 탐지 한 건만 요청하고 run ID를 권한 `600` 상태 파일에 저장한다.
3. `wait`: 요청 접수 상태가 아니라 Backend가 최종 `SUCCEEDED` 또는 `FAILED`가 될 때까지 확인한다.
4. `frontend`: 프런트가 사용하는 latest Detection 조회에서 같은 run의 `SUCCEEDED`를 확인하고, Engagement Alert 조회에서 같은 run의 대기 알림 수를 확인한다. 정상적인 신호 0건이면 알림 수는 0일 수 있다.

인증이 필요한 배포에서는 `AUTH_BEARER_TOKEN`을 현재 셸 환경에만 설정한다. 명령 기록이나 출력에 토큰 값을 포함하지 않는다.
