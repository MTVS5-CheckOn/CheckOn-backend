# 프론트 화면 데이터 정책과 클래스 API 인계

이 문서는 프론트엔드 연동을 위한 팀 공유 요약이다. 정책의 최종 기준은
`docs/POLICY_REGISTER.md`, 실행 계약의 최종 기준은
`src/main/resources/openapi/dashboard-api.yaml`이다. 불일치가 발견되면 정책
레지스트리에서 결정 상태를 먼저 확인하고, 확정 정책·OpenAPI·코드·테스트를
같은 변경 단위에서 동기화한다.

## 1. 이번 구현 범위

이번 수직 단위는 인증된 강사의 클래스 관리만 제공한다.

- 클래스 목록과 활성 학생 수
- 클래스 등록, 상세 조회와 기본 정보 수정
- 클래스 보관과 활성 소속 종료
- 0-based 페이지와 안정 정렬
- Bearer JWT 및 TEACHER 권한

클래스별 학생 행, 학생 목록·상세, 실명 외 PII, 보호자, 학생·보호자 메모,
이상신호, 학업 분석과 케어 화면은 각각의 정책 승인 뒤 별도 단위로 구현한다.

## 2. 확정 정책

### SCREEN-CLASS-001 클래스 보관

- 화면의 삭제 동작은 hard delete가 아니라 `ARCHIVED` 상태 전이다.
- 보관 시 해당 클래스의 모든 `ACTIVE` 소속을 먼저 종료하고 클래스를
  `ARCHIVED`로 바꾼다.
- 소속 종료와 클래스 상태 전이는 한 트랜잭션과 하나의 `Instant`를 사용한다.
  하나라도 실패하면 전부 rollback한다.
- 학생, 강사-학생 관계, 종료된 소속, 학습 기록과 파생 이력은 보존한다.
- 이미 보관된 클래스의 재요청은 종료 시각이나 이력을 다시 쓰지 않는 멱등
  동작이다.
- 기본 목록에서는 `ACTIVE` 클래스만 제공하고 직접 상세 조회에서는
  `ARCHIVED` 상태도 확인할 수 있다.

### SCREEN-CLASS-002 과목과 메모

- `subject`는 과목 코드나 enum이 아닌 표시용 자유 문자열이다.
- 신규 등록·수정 시 `subject`는 필수이며, 앞뒤 공백 제거 후 1~100자다.
- `memo`는 nullable이고 값이 있으면 최대 1000자다.
- Learning Record의 `subjectTrack`은 클래스 과목으로 재사용하지 않는다.
- 기존 클래스에 의미 없는 과목을 backfill하지 않는다. 마이그레이션 전 행의
  `subject`는 사용자가 수정하기 전까지 조회 응답에서 null일 수 있다.

### SCREEN-PAGE-001 페이지와 정렬

- `page`는 0부터 시작하고 기본값은 0이다.
- `size` 기본값은 20이며 1~100만 허용한다.
- 클래스 목록은 `createdAt DESC, id DESC`로 정렬한다.
- 응답은 `content`, `page`, `size`, `totalElements`, `totalPages`를 제공한다.
- 화면의 행 번호는 API 데이터가 아니다. 프론트가
  `page * size + index + 1`로 계산한다.

## 3. HTTP 계약

OpenAPI의 server base path는 `/api/v1`이므로 path key에는 이 접두사를
중복해서 넣지 않는다.

| Method | HTTP 경로 | 용도 | 성공 응답 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/classes?page=0&size=20` | 활성 클래스 목록 | `200` |
| `POST` | `/api/v1/classes` | 클래스 등록 | `201` |
| `GET` | `/api/v1/classes/{classId}` | 클래스 상세 | `200` |
| `PATCH` | `/api/v1/classes/{classId}` | 이름·과목·메모 수정 | `200` |
| `POST` | `/api/v1/classes/{classId}/archive` | 클래스 보관 | `200` |

`DELETE /classes/{classId}`는 제공하지 않는다.

### 클래스 필드

| 필드 | 타입 | nullable | 책임 |
| --- | --- | --- | --- |
| `classId` | UUID | 아니오 | 백엔드 식별자 |
| `name` | string, 1~100 | 아니오 | 등록·수정 입력 및 출력 |
| `subject` | string, 1~100 | 레거시 조회만 가능 | 신규 등록·수정 입력 및 출력 |
| `memo` | string, max 1000 | 예 | 수정 입력 및 출력 |
| `status` | `ACTIVE` 또는 `ARCHIVED` | 아니오 | 백엔드 원본 상태 |
| `activeStudentCount` | integer, min 0 | 아니오 | `ACTIVE` 소속의 서버 집계 |
| `createdAt` | RFC 3339 date-time | 아니오 | 생성 시각 |
| `updatedAt` | RFC 3339 date-time | 아니오 | 마지막 변경 시각 |
| `rowNo` | integer | 해당 없음 | API 미제공, 프론트 계산 |

등록 입력은 `name`, `subject`와 선택적 `memo`를 받는다. 수정 입력도 현재
화면의 세 필드를 한 번에 검증하며 `name`, `subject`는 필수이고 `memo`는
nullable이다.

## 4. 보안과 테넌트 경계

- 기본·운영 환경의 모든 클래스 API는 Bearer JWT와 `TEACHER` 역할이
  필요하다. dev 프로필에서만 SEC-004의 서버 고정 테스트 TEACHER principal
  예외를 사용할 수 있으며, 운영 무인증 계약으로 해석하지 않는다.
- 테넌트는 요청의 `teacherId`가 아니라 인증 principal의
  `teacherProfileId`에서만 얻는다.
- application 유스케이스는 트랜잭션 안에서 RLS 컨텍스트를 설정하고, SQL과
  Repository에도 명시적인 `teacherId` 조건을 둔다.
- 존재하지 않는 클래스와 다른 강사의 클래스는 같은 `CLASS_NOT_FOUND` 404로
  처리한다.
- 미인증은 401, PARENT·STUDENT는 403이다.

## 5. 보관 트랜잭션

보관 요청은 다음 순서를 하나의 트랜잭션에서 수행한다.

1. 인증 강사 소유 클래스 행을 쓰기 잠금으로 조회한다.
2. 해당 클래스의 `ACTIVE` 소속 행을 쓰기 잠금으로 조회한다.
3. 한 번 얻은 종료 시각으로 각 소속을 `ENDED` 처리한다.
4. 클래스를 `ARCHIVED` 처리한다.
5. 변경을 함께 commit한다.

DB는 보관된 클래스에 새 `ACTIVE` 소속을 추가하는 동작과 활성 소속을 남긴
채 클래스를 보관하는 직접 변경도 거절해야 한다. 애플리케이션 잠금과 DB
불변식을 함께 사용해 동시 요청에서도 중간 상태를 남기지 않는다.

## 6. 기존 데이터와 배포

신규 마이그레이션은 적용된 `V1~V12`를 수정하지 않는다. `subject`와 `memo`를
새 nullable 컬럼으로 추가해 기존 행을 보존한다. 새 등록·수정 API와 V13 이후
직접 INSERT에는 `subject` 필수 규칙을 적용하고, 한 번 입력된 subject를 직접
UPDATE로 null로 되돌리는 것도 거절한다. 기존 null subject 행의 보관 등
과목과 무관한 변경은 허용한다. 따라서 별도 backfill이나 데이터 삭제는 없다.

V13 적용 전 검사는 FORCE RLS를 우회한다고 가정하지 않는다. 강사별
transaction-local tenant context를 순회해 `ARCHIVED` 클래스에 `ACTIVE` 소속이
남아 있는지 확인하고, 발견하면 데이터를 자동 변경하지 않고 마이그레이션을
중단해 운영자가 원인을 확인하게 한다.

이 변경의 복구는 기존 마이그레이션 파일을 되돌리는 것이 아니라 후속 Flyway로
API 사용을 중단하고 필요한 호환 변경을 추가하는 방식으로 수행한다. 보관된
클래스를 다시 `ACTIVE`로 되돌리는 복원 정책과 API는 이번 범위에 없다.

## 7. 이번 범위에서 하지 않는 것

- 클래스 hard delete와 연쇄 삭제
- 보관 클래스 복원
- 과목 taxonomy 또는 Learning Record 과목과의 자동 매핑
- 클래스별 학생 행과 학생 PII
- 학생·보호자 메모
- 보호자 연결·초대
- 이상신호, 학업 분석과 케어 집계
- 프론트 저장소 변경 및 실제 E2E 호출

## 8. 구현·검증 상태

정책은 2026-08-09 사용자 승인으로 확정됐고, 클래스 관리 수직 단위에
구현됐다.

- DB·승격: `V13__add_class_management_fields.sql`
- 도메인·유스케이스: `ClassGroup`, `ClassManagementService`
- 조회·HTTP: `ClassGroupQueryRepository`, `ClassGroupController`
- 실행 계약: `src/main/resources/openapi/dashboard-api.yaml`
- 정책 기준: `docs/POLICY_REGISTER.md`

검증 결과는 다음과 같다.

- 클래스 도메인, API, OpenAPI, V12→V13 정상 승격과 불일치 데이터 중단,
  PostgreSQL 제약·RLS·동시성 집중 테스트 39건 통과
- `gradlew clean build` 전체 173건 통과, 실패·오류·건너뜀 0건
- 제한된 runtime 역할의 자기 소유 클래스 hard delete가 0행이며 원본 행이
  보존되는 것을 PostgreSQL 통합 테스트로 확인

운영 배포와 프론트 E2E 호출은 이 로컬 검증 범위에 포함하지 않았다. 운영
승격 전에 DB 백업을 확인하고, V13 사전검사가 기존 `ARCHIVED` 클래스와
`ACTIVE` 소속의 불일치를 보고하면 자동 수정하지 말고 해당 테넌트 데이터를
먼저 조사한다.
