# 문제 출제 로컬 E2E 환경

로컬 Backend와 Adapter가 Docker PostgreSQL/Kafka를 공유하고, Adapter만 배포 AI 서버에 HTTP로 연결한다.

```text
Frontend/API client -> Backend :8080 -> Kafka :9094 -> Adapter :8081 -> deployed AI
```

## 시작

Backend `.env`의 DB/Flyway/JWT 값이 준비된 상태에서 실행한다. AI 주소에는 `/v1` 경로를 붙이지 않는다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\problem-generation\start-local-e2e.ps1 `
  -AiBaseUrl 'https://deployed-ai.example.com' `
  -AdapterRoot 'C:\path\to\checkon-kafka-adapter'
```

`AdapterRoot`는 반드시 현재 문제 출제 진단 프록시가 병합된 Adapter checkout을 지정한다.
과거 worktree나 다른 브랜치를 자동 선택하지 않는다. 시작 과정에서
`POST /internal/v1/problem-diagnoses`가 존재하는지도 확인한다.

Backend와 Adapter는 같은 환경변수 이름을 서로 다른 통신 구간에 사용한다. 시작 스크립트가
각 프로세스를 실행하기 직전에 아래 값을 분리해서 설정하므로 Backend `.env` 값을 Adapter에
그대로 전달하지 않는다.

```text
Backend -> Adapter: AI_PROBLEM_DIAGNOSIS_PATH=/internal/v1/problem-diagnoses
Adapter -> AI:      AI_PROBLEM_DIAGNOSIS_PATH=/v1/diagnosis
```

스크립트는 다음을 수행한다.

1. AI `/openapi.json` 도달 여부 확인
2. Docker PostgreSQL/Kafka 시작
3. `checkon_kafka_adapter` 전용 DB 준비
4. dev 테스트 인증 principal과 문제 출제 학습 기록 준비
5. Adapter와 Backend를 숨김 프로세스로 실행
6. Adapter 진단 라우트와 `localhost:8081`, `localhost:8080` 포트 준비 확인

로그와 PID 상태는 `build/local-problem-generation-e2e/`에 저장된다. 비밀번호와 AI 토큰은 저장하지 않는다.
시작 스크립트는 배포 AI의 `/openapi.json`에 접근하고, 씨드 스크립트는 로컬 DB에 테스트 데이터를 쓴다.

## 실제 왕복 검증

환경 시작이 끝난 뒤 별도 PowerShell에서 실행한다.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\problem-generation\test-local-e2e.ps1 `
  -TimeoutSeconds 300
```

진단 응답, 최종 요청 상태, slot projection 결과는
`build/local-problem-generation-e2e/*.json`에 저장된다. 진단이 `GENERATED`가 아니거나 문제 출제가
`FAILED`이거나, 요청한 두 slot·검토 가능한 item·projection 상태가 확인되지 않으면
스크립트가 원인 코드와 함께 실패한다.
이 검증은 배포 AI에 실제 진단·문제 출제 요청을 보내고 Backend DB에 요청 이력을 남긴다.

## 종료

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File .\scripts\problem-generation\stop-local-e2e.ps1
```

종료 스크립트는 PID, 프로세스 이름, 시작 시각이 모두 일치하는 Backend/Adapter만 종료한다.
PostgreSQL과 Kafka는 빠른 재실행을 위해 유지한다.

## 고정 테스트 식별자

- 강사: `0198f000-0000-7000-8000-000000000001`
- 학생: `0198f000-0000-7000-8000-000000000002`
- 반: `0198f000-0000-7000-8000-000000000003`

dev 프로필의 테스트 인증을 사용하므로 로컬 API 요청에는 Authorization 헤더를 넣지 않는다.
씨드 과정은 공통 위험탐지 데모 principal·학생·반도 멱등하게 준비하고,
`source_type=problem-studio-e2e`인 기존 학습 기록만 교체한다.
