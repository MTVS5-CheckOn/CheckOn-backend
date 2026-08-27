# 학부모 상담 로컬 데모 씨드

로컬 PostgreSQL과 Backend가 준비된 상태에서 저장소 루트에서 실행한다.

```powershell
.\scripts\demo\parent-counsel\seed-local-parent-counsel.ps1
```

이 씨드는 개발 프로필의 테스트 강사 principal과 일치하는 강사, 전용 학부모·학생·반,
수신 문의 6건, 실제 발송문 5건, 현재 라벨 2개를 준비한다. 고정된 비실명 데모 ID만
upsert하므로 반복 실행해도 상담 이력 수가 증가하지 않으며 위험탐지·Problem Studio 씨드의
학생과 반은 사용하지 않는다.

씨드 후 현재 checkout의 Backend를 기본 18080 포트에서 실행한다.

```powershell
.\scripts\demo\parent-counsel\start-local-backend.ps1 -EnableTestAuthentication
```

별도 작업트리에는 ignore된 `.env`가 복사되지 않으므로 필요하면
`-EnvironmentFile C:\work\project\CheckOn\.env`처럼 기존 로컬 환경 파일 경로를 지정한다.
스크립트는 환경변수 값이나 비밀번호를 출력하지 않는다.
`-EnableTestAuthentication`은 로컬 dev principal을 사용할 때만 지정하고 배포 환경에서는 사용하지 않는다.
배포 프론트 Origin의 CORS preflight도 로컬 Backend에서 확인하려면 다음처럼 명시한다.

```powershell
.\scripts\demo\parent-counsel\start-local-backend.ps1 `
  -EnableTestAuthentication `
  -AllowedOrigins 'https://checkon-front.vercel.app'
```

`AllowedOrigins`는 로컬 프로세스의 CORS 설정만 덮어쓰며 배포 설정을 변경하지 않는다.

`demo-counsel-start` 문의에는 아직 draft job을 만들지 않는다. 먼저
`GET /api/v1/counsel/inquiries`에서 반환된 백엔드 저장 컨텍스트를 읽고 새
`Idempotency-Key`와 함께 `POST /api/v1/counsel/drafts`를 호출한다. 응답은 `202`이며
`Location`을 폴링한다. Adapter/AI가 실행 중이지 않으면 `queued`에 머무를 수 있고,
그 상태는 AI 왕복 성공을 의미하지 않는다.
