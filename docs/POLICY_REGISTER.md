# CheckOn 정책 레지스트리

이 문서는 기획서, Codex 대화, 코드, Flyway 마이그레이션 사이에 흩어진 정책을 한곳에서 추적하기 위한 기준 문서다.
기존 기획서를 즉시 대체하지 않으며, 정책의 확정 여부와 현재 구현 여부를 분리해 기록한다.

## 1. 사용 원칙

- 새로운 정책을 발견하거나 결정하면 먼저 이 문서에 등록한다.
- 대화에서 제안된 내용은 사용자가 명시적으로 승인하기 전까지 `PROPOSED` 또는 `OPEN`으로 둔다.
- 코드에 존재한다는 이유만으로 제품 정책을 `CONFIRMED`로 올리지 않는다.
- `CONFIRMED` 정책과 기존 기획서가 다르면 기획서를 바로 고치지 않고 `CONFLICT` 항목으로 기록해 검토한다.
- 구현 상태는 현재 브랜치의 코드, Flyway, 테스트로 확인한다.
- 과거 테스트 결과는 현재 상태의 증거가 아니므로 `마지막 검증일` 이후 변경이 있으면 다시 검증한다.
- 정책 변경 시 기존 항목을 삭제하지 않고 `SUPERSEDED`로 전환하고 대체 정책 ID를 남긴다.

## 2. 상태 정의

### 결정 상태

| 상태 | 의미 |
| --- | --- |
| `CONFIRMED` | 사용자가 명시적으로 승인했거나 저장소의 확정 의사결정 문서에 기록된 정책 |
| `PROPOSED` | 권장안 또는 논의안이지만 아직 승인되지 않은 정책 |
| `OPEN` | 결론을 내리지 않았거나 추가 합의가 필요한 정책 |
| `CONFLICT` | 확정 근거끼리 충돌하여 선택이 필요한 정책 |
| `SUPERSEDED` | 더 새로운 정책으로 대체된 과거 정책 |

### 구현 상태

| 상태 | 의미 |
| --- | --- |
| `IMPLEMENTED` | 현재 코드와 DB 스키마에서 구현을 확인함 |
| `PARTIAL` | 정책 일부만 구현됐거나 적용 범위가 제한됨 |
| `NOT_IMPLEMENTED` | 정책은 존재하지만 구현되지 않음 |
| `NOT_APPLICABLE` | 구현 여부를 적용할 수 없는 운영·협업 원칙 |
| `UNVERIFIED` | 현재 브랜치에서 아직 확인하지 않음 |

### 근거 수준

| 수준 | 의미 |
| --- | --- |
| `CODE_CONFIRMED` | 현재 코드, Flyway 또는 테스트에서 확인함 |
| `DOCUMENT_CONFIRMED` | 저장소의 확정 문서에서 확인함 |
| `CONVERSATION_CONFIRMED` | 사용자와의 대화에서 확정됐으나 저장소 문서에는 없었음 |
| `CONVERSATION_ONLY` | 대화에서만 제안·논의됐고 확정되지 않음 |
| `LIVE_VERIFIED` | 현재 배포 화면 또는 실행 환경에서 직접 확인함 |

## 3. 정책 변경 절차

1. 정책 후보를 관련 영역에 새 ID로 등록한다.
2. 대화 근거, 기획 문서, 코드, Flyway, 테스트를 서로 구분해 적는다.
3. 결정 상태와 구현 상태를 각각 판정한다.
4. `OPEN`, `PROPOSED`, `CONFLICT`는 사용자의 결정 없이 구현하지 않는다.
5. 확정 후 영향받는 기획서, API 계약, 마이그레이션, 코드, 테스트를 확인한다.
6. 검증 명령과 결과를 변경 이력에 남기고 `마지막 검증일`을 갱신한다.

## 4. 정책 목록

### 인증·테넌트·RLS

#### SEC-001 인증 주체 기반 테넌트 식별

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 강사 소유 데이터의 테넌트 ID는 인증된 principal의 `teacherProfileId`에서만 가져온다. 요청 본문의 `teacherId`나 `Account.id`, `ClassGroup.id`를 테넌트 ID로 신뢰하지 않는다.
- 보안 결과: 접근할 수 없는 다른 테넌트의 리소스는 존재 여부가 노출되지 않도록 안전한 동일 응답을 사용한다.
- 코드 근거:
  - `src/main/java/com/checkon/account/infrastructure/security/AuthenticatedAccount.java`
  - `src/main/java/com/checkon/learning/presentation/LearningRecordController.java`
  - `src/main/java/com/checkon/detection/presentation/DetectionRunController.java`
- 마지막 검증일: 2026-08-04

#### SEC-002 트랜잭션 로컬 RLS 컨텍스트

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: RLS가 적용되는 application 유스케이스는 활성 트랜잭션 안에서 `checkon.current_teacher_id`를 `set_config(..., true)`로 설정한다. 커넥션 풀에 테넌트 값이 남아서는 안 된다.
- 운영 조건: 애플리케이션 DB 역할은 non-owner, non-superuser, `NOBYPASSRLS`여야 하며 Flyway 관리자 역할과 분리한다.
- 검증 조건: 무컨텍스트 기본 거절, 교차 테넌트 SELECT/INSERT/UPDATE/DELETE 거절, commit/rollback 후 컨텍스트 제거를 실제 제한 역할로 검증한다.
- 코드 근거:
  - `src/main/java/com/checkon/global/persistence/TeacherTenantDatabaseContext.java`
  - `src/main/java/com/checkon/global/persistence/TenantDatabaseRoleSafetyVerifier.java`
  - `src/main/resources/db/migration/V7__enforce_teacher_tenant_row_level_security.sql`
  - `src/test/java/com/checkon/global/persistence/TeacherTenantRowLevelSecurityIntegrationTest.java`
- 마지막 검증일: 2026-08-04

#### SEC-003 StudentProfile RLS 적용 범위

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`, `CODE_CONFIRMED`
- 현재 상태: `student_profiles`에는 강사별 직접 RLS 정책이 없다. 강사 소유 관계와 학습 기록은 별도 테넌트 경계를 적용한다.
- 결정 필요: 학생 프로필 자체를 어떤 주체가 소유·조회하는지, 강사 변경 이력과 보호자 접근을 포함해 합의해야 한다.
- 금지 사항: 소유권 정책이 정해지기 전에 `student_profiles`에 임의의 강사 소유 RLS를 추가하지 않는다.
- 코드 근거: `src/main/resources/db/migration/V7__enforce_teacher_tenant_row_level_security.sql`
- 마지막 검증일: 2026-08-04

### Roster

#### ROS-001 학생의 활성 강사 관계

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `DOCUMENT_CONFIRMED`, `CODE_CONFIRMED`
- 정책: MVP에서 학생은 활성 강사를 최대 한 명만 가진다. 동시 요청에서도 이 규칙이 깨지지 않도록 DB partial unique index로 보장한다.
- 이력 정책: 종료된 관계는 삭제하지 않고 `ENDED`와 `ended_at`으로 보존하며, 재연결 시 새 행을 만든다.
- 코드 근거:
  - `src/main/resources/db/migration/V6__create_roster_model.sql`
  - `src/main/java/com/checkon/roster/domain/TeacherStudentRelationship.java`
- 마지막 검증일: 2026-08-04

#### ROS-002 학생의 활성 반 소속

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `DOCUMENT_CONFIRMED`, `CODE_CONFIRMED`
- 정책: MVP에서 학생은 활성 반을 최대 한 개만 가진다. 활성 반의 소유 강사와 학생의 활성 강사가 일치해야 한다.
- 이력 정책: 종료된 소속은 보존하고, 이후 소속은 새 행으로 기록한다.
- 코드 근거:
  - `src/main/resources/db/migration/V6__create_roster_model.sql`
  - `src/main/java/com/checkon/roster/domain/ClassEnrollment.java`
- 마지막 검증일: 2026-08-04

#### ROS-003 미확정 Roster 정책 묶음

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`
- 결정 필요:
  - 학생 회원가입 및 계정 연결 방식
  - 보호자와 초대 흐름
  - 관계 `PAUSED` 상태 도입 여부
  - 관계 종료 사유의 분류와 필수 여부
  - 반 자체의 학년 보유 여부
- 금지 사항: 별도 결정 없이 enum, 컬럼, 상태 전이 또는 API 계약을 추가하지 않는다.
- 마지막 검증일: 2026-08-04

### Engagement

#### ENG-001 근거 기반 강사 검토

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 검증된 Detection signal과 하나 이상의 Evidence만 경보 후보가 된다. AI 결과는 `PENDING_REVIEW`이며 인증된 강사가 `APPROVED` 또는 `REJECTED`로 최종 판단한다. 같은 판단의 재요청은 멱등 처리하고 반대 판단으로 변경하지 않는다.
- 코드 근거: `V9__create_engagement_review_flow.sql`, `EngagementAlert`, `EngagementCandidateService`
- 마지막 검증일: 2026-08-04

#### ENG-002 승인 경보 기반 개입과 리마인드

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 이 수직 단위의 개입은 승인된 경보에서 새 이력으로 생성한다. 개입 한 건당 활성 리마인드는 최대 하나이며 완료·취소 뒤에는 새 리마인드를 만들 수 있다.
- 코드 근거: `V9__create_engagement_review_flow.sql`, `Intervention`, `InterventionReminder`
- 마지막 검증일: 2026-08-04

#### ENG-003 후속 운영 정책

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`
- 결정 필요: 리마인드 실제 발송 채널·스케줄러, 경보 보존/소멸 및 재발·쿨다운, 일반 상담 기록 생성, 개입 유형 표준 enum, 재예약의 단일 API 의미.
- 현재 범위: 리마인드 DB 저장과 활성·완료·취소 상태 관리까지만 제공한다.
- 마지막 검증일: 2026-08-04

### Learning Record·Import

#### LR-001 external_record_ref 처리

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: `external_record_ref`는 nullable이며 중복된 non-null 값도 허용한다. 공백 값은 저장 전에 null로 정규화한다.
- 하지 않는 것: UNIQUE 제약 또는 인덱스, 멱등 키 사용, 중복 거절, 기존 행 갱신 의미를 임의로 추가하지 않는다.
- 코드 근거:
  - `src/main/resources/db/migration/V8__create_learning_records_and_ai_student_aliases.sql`
  - `src/test/java/com/checkon/learning/infrastructure/persistence/LearningRecordPersistenceIntegrationTest.java`
- 마지막 검증일: 2026-08-04

#### LR-002 단건 수동 등록의 서버 소유 값

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 단건 등록은 인증된 강사 경계에서 처리하며 `sourceType`은 서버가 `MANUAL`로 지정한다. 학생과 반은 모두 해당 강사의 활성 관계 및 소유 범위 안에 있어야 한다.
- 재사용 경계: 향후 Import도 최종 검증·저장 단계에서 단건 등록 application 경계의 규칙을 재사용한다.
- 코드 근거:
  - `src/main/java/com/checkon/learning/presentation/LearningRecordController.java`
  - `src/main/java/com/checkon/learning/application/RegisterLearningRecordService.java`
  - `src/test/java/com/checkon/learning/presentation/LearningRecordControllerIntegrationTest.java`
- 마지막 검증일: 2026-08-04

#### LR-003 Import 책임 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- 정책: `파일 업로드 -> AI 프로파일링·매핑 제안 -> 강사 검토·수정 -> 백엔드 확정 -> 백엔드 전체 행 변환·검증·저장` 순서를 사용한다.
- 책임: AI는 구조와 매핑을 제안한다. 백엔드는 확정된 매핑을 다시 읽고 전체 행을 변환·검증한 뒤 원본 학습 기록을 저장한다.
- 미확정: 매핑 재사용 범위, 원본 파일 접근 계약, 오류·부분 성공 상태, source fingerprint 정책.
- 금지 사항: AI의 제안을 검토 없이 확정하거나 AI가 백엔드 대신 최종 학습 기록을 소유하게 하지 않는다.
- 마지막 검증일: 2026-08-04

#### LR-004 반복 문항 식별 정책

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`, `CODE_CONFIRMED`
- 현재 상태: 안정적인 문항 ID 또는 문제은행 계약이 없으며 `external_record_ref`도 문항 ID로 확정되지 않았다.
- 결정 필요: 동일 문항, 유사 문항, 변형 문항을 어떤 수준에서 동일하게 볼지와 외부 시스템 식별자를 누가 소유할지 합의해야 한다.
- 금지 사항: 현 단계에서 `external_record_ref`를 문항 ID 또는 멱등 키로 간주하지 않는다.
- 마지막 검증일: 2026-08-04

### Detection·AI 연동

#### DET-001 운영 Detection 입력과 분석 기간

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 운영 API는 인증된 강사와 `analysisDate`를 기준으로 서버가 테넌트 키와 56일 학습 기록 스냅샷을 만든다. 서비스 시간대는 `Asia/Seoul`이다.
- 빈 기록 처리: 분석할 학습 기록이 없으면 `NO_LEARNING_RECORDS`로 거절한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/OperationalDetectionRunService.java`
  - `src/main/java/com/checkon/detection/application/DetectionTenantKey.java`
  - `src/main/java/com/checkon/detection/presentation/DetectionRunExceptionHandler.java`
- 마지막 검증일: 2026-08-04

#### DET-002 외부 AI 호출과 트랜잭션 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 외부 AI HTTP 호출을 DB 트랜잭션 안에서 수행하지 않는다. 준비, 시도 시작, 실패 기록, 성공 응답 저장은 각각 짧은 트랜잭션 경계를 사용한다.
- 재시도 이력: 실패한 요청 시도도 보존하며 재시도는 새 attempt 행으로 기록한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/RiskDetectionExecutionService.java`
  - `src/main/java/com/checkon/detection/application/DetectionAttemptCoordinator.java`
- 마지막 검증일: 2026-08-04

#### DET-003 AI 응답 검증과 원자적 저장

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: AI 응답의 학생·반·근거 소유권, 필수 값, 중복 ID, 점수·순위·lifecycle·metadata를 요청 스냅샷과 대조한 뒤 저장한다.
- 원자성: 응답 일부만 저장되면 안 되며 검증 또는 저장 실패 시 signal과 evidence 전체를 롤백한다. 요청 시도 이력은 별도 경계에서 보존한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/DetectionResponseStorageService.java`
  - `src/test/java/com/checkon/detection/application/DetectionResponseStorageServiceTest.java`
- 마지막 검증일: 2026-08-04

### Frontend·UX

#### UX-001 배포 프론트의 화면 프레임 기준

- 결정 상태: `CONFIRMED`
- 구현 상태: `NOT_APPLICABLE`
- 근거 수준: `CONVERSATION_CONFIRMED`, `LIVE_VERIFIED`
- 기준 URL: <https://checkon-front.vercel.app/a/019f8999-4c92-73d8-ae5f-572c22b0523a/dashboard>
- 정책: 이후 화면 설계와 기능 배치는 위 배포 프론트의 전체 프레임과 정보 구조를 기준으로 진행한다.
- 2026-08-04 확인 구조:
  - 좌측 영역: CheckOn 브랜드, 요금제·강사 컨텍스트, 학원 선택, 주요 메뉴
  - 주요 메뉴: 대시보드, 학부모 360, 학생 목록, 상담 일정, 설정
  - 대시보드 본문: `오늘의 브리핑`과 `통계` 탭
  - 오늘의 브리핑: 날짜 탐색과 `확인이 필요한 신호` 카드 목록 중심
- 적용 범위: 화면의 큰 레이아웃, 내비게이션 위치, 정보 계층, 화면 간 진입 구조
- 별도 합의 가능 범위: 색상·간격·문구·세부 컴포넌트·반응형 동작은 프론트 구현 과정에서 조정할 수 있다. 프레임 자체를 바꾸는 변경은 먼저 사용자와 합의한다.
- 해석 금지:
  - URL에 포함된 UUID를 백엔드의 테넌트 ID 또는 공개 API 계약으로 확정하지 않는다.
  - 화면의 예시 사용자·학원·신호 데이터를 실제 운영 데이터나 확정된 도메인 정책으로 해석하지 않는다.
  - 배포 화면이 존재한다는 사실을 해당 기능의 백엔드 연동 또는 End-to-End 검증 완료 근거로 사용하지 않는다.
- 마지막 검증일: 2026-08-04

## 5. 기존 기획서 대조 대기 목록

이 절은 기존 기획서를 직접 수정하기 전에 사용할 작업 큐다.

| 대상 | 관련 정책 | 상태 | 다음 작업 |
| --- | --- | --- | --- |
| `docs/ideation_v4_3.md` | 전체 | 대조 전 | 정책별 `일치 / 반드시 수정 / 설명 보완 / 결정 필요` 분류 |
| `docs/ideation_improvement plan.md` | 전체 | 대조 전 | 확정 정책과 제안 내용을 분리해 검토 |

## 6. 변경 이력

| 날짜 | 변경 | 검증 |
| --- | --- | --- |
| 2026-08-04 | UX-001 배포 프론트 화면 프레임 기준 등록 | 배포 URL의 대시보드 화면을 브라우저로 직접 확인 |
| 2026-08-04 | 정책 레지스트리 구조와 인증·RLS·Roster·Learning Record·Import·Detection 초기 항목 등록 | 현재 브랜치의 관련 코드, V6~V8 Flyway, 테스트 소스 정적 확인. 테스트 실행 전 |

## 7. 새 항목 템플릿

```md
#### DOMAIN-NNN 정책명

- 결정 상태: `OPEN`
- 구현 상태: `UNVERIFIED`
- 근거 수준: `CONVERSATION_ONLY`
- 정책 또는 질문:
- 이유:
- 하지 않는 것:
- 영향 범위:
- 대화·문서 근거:
- 코드 근거:
- 대체 정책: 없음
- 마지막 검증일: YYYY-MM-DD
```
