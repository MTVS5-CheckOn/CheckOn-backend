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

#### ROS-004 학생 실명 저장 및 강사 접근 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 저장 경계: 학생 실명은 `student_profiles.alias` 및 `ai_student_aliases.alias`와 분리한 별도 PostgreSQL PII 테이블에 학생 전역 정보로 저장한다.
- 접근 정책: 인증 주체의 `teacherProfileId`와 학생의 현재 `ACTIVE` 강사 관계가 모두 확인된 강사만 실명을 등록·조회·수정할 수 있다. 관계 종료 후 이전 강사의 접근은 차단한다.
- 입력 계약: 이번 범위는 강사의 멱등 등록·수정 API만 제공한다. 이름은 1~100자이며 빈 문자열과 앞뒤 공백을 허용하지 않는다.
- 감사 범위: 전체 변경 이력은 보존하지 않고 마지막 수정 시각, 수정 Account ID, 역할만 저장한다.
- Dashboard: alerts와 reminders의 `studentName`은 nullable이다. 미등록 시 null이며 Roster alias 또는 AI alias로 대체하지 않는다.
- 기존 데이터: alias backfill을 하지 않으며 기존 학생은 실명 입력 전까지 null이다.
- AI 격리: AI 요청 DTO와 저장 Snapshot에는 기존 AI alias만 사용하며 실명을 추가하지 않는다.
- OPEN: 학생·학부모 입력 권한, 관계 종료·탈퇴 후 삭제와 보존 기간, 필드 암호화·외부 Vault, 운영 DB·백업 암호화.
- 코드 근거:
  - `src/main/resources/db/migration/V12__create_student_personal_information.sql`
  - `src/main/java/com/checkon/roster/application/SaveStudentPersonalInformationService.java`
  - `src/main/java/com/checkon/dashboard/application/DashboardBriefingService.java`
- 마지막 검증일: 2026-08-05

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

### Class Management

#### SCREEN-CLASS-001 클래스 보관과 활성 소속 종료

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 화면의 클래스 삭제는 물리 삭제가 아니라 클래스 상태를 `ARCHIVED`로 전환하는 보관 동작이다.
- 원자성: 보관 시 해당 클래스의 모든 `ACTIVE` 소속을 같은 트랜잭션과 같은 `Instant`로 종료한 뒤 클래스를 `ARCHIVED`로 전환한다. 어느 단계든 실패하면 전체를 rollback한다.
- 이력 보존: 학생, 강사-학생 관계, 종료된 클래스 소속, 학습 기록과 파생 이력은 삭제하지 않는다.
- 재요청: 이미 보관된 클래스에 대한 요청은 새 종료 이력을 만들거나 기존 종료 시각을 변경하지 않는 멱등 동작으로 처리한다.
- 목록·상세: 기본 클래스 목록은 `ACTIVE`만 반환한다. 직접 상세 조회는 감사와 재요청 결과 확인을 위해 `ARCHIVED`도 상태와 함께 반환한다.
- 금지 사항: `class_groups` hard delete, 학생 정보 연쇄 삭제, 이력 cascade delete.
- 테넌트 경계: 인증된 TEACHER principal의 `teacherProfileId`만 사용하며, 존재하지 않는 클래스와 다른 강사의 클래스는 동일한 404 결과로 처리한다.
- 영향 범위: Flyway, `ClassGroup`, `ClassEnrollment`, application 트랜잭션·동시성 제어, 클래스 보관 API, OpenAPI와 통합 테스트.
- 코드 근거:
  - `src/main/resources/db/migration/V13__add_class_management_fields.sql`
  - `src/main/java/com/checkon/roster/application/ClassManagementService.java`
  - `src/main/java/com/checkon/roster/presentation/ClassGroupController.java`
  - `src/test/java/com/checkon/roster/presentation/ClassGroupControllerIntegrationTest.java`
  - `src/test/java/com/checkon/global/persistence/TeacherTenantRowLevelSecurityIntegrationTest.java`
- 마지막 검증일: 2026-08-09

#### SCREEN-CLASS-002 클래스 과목과 메모 입력 계약

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 과목: `subject`는 코드나 enum이 아닌 표시용 자유 문자열이다. 앞뒤 공백 제거 후 1~100자여야 하며 신규 등록과 수정 입력에서 필수다.
- 메모: `memo`는 nullable이며 값이 있으면 최대 1000자다.
- 금지 사항: Learning Record의 `subjectTrack`을 클래스 과목으로 재사용하거나 승인되지 않은 과목 코드 체계를 추가하지 않는다.
- 기존 데이터 호환: 기존 클래스에 임의 과목 값을 backfill하지 않는다. 신규 쓰기에는 `subject`를 필수로 적용하되, 마이그레이션 전 행은 수정 전까지 응답에서 nullable일 수 있음을 API 계약에 명시한다.
- 영향 범위: 다음 Flyway 마이그레이션, 클래스 도메인·요청 검증·응답 DTO, OpenAPI와 경계값 테스트.
- 코드 근거:
  - `src/main/resources/db/migration/V13__add_class_management_fields.sql`
  - `src/main/java/com/checkon/roster/domain/ClassGroup.java`
  - `src/main/java/com/checkon/roster/presentation/ClassGroupController.java`
  - `src/main/resources/openapi/dashboard-api.yaml`
  - `src/test/java/com/checkon/roster/infrastructure/persistence/ClassManagementMigrationIntegrationTest.java`
- 마지막 검증일: 2026-08-09

### Engagement

#### ENG-001 근거 기반 강사 검토

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 검증된 Detection signal과 하나 이상의 Evidence만 경보 후보가 된다. AI 결과는 `PENDING_REVIEW`이며 인증된 강사가 `APPROVED` 또는 `REJECTED`로 최종 판단한다. 같은 판단의 재요청은 멱등 처리하고 반대 판단으로 변경하지 않는다.
- ongoing 중복 억제: 같은 강사·학생·`signal_type`의 open Alert가 이미 있고 새 signal의 `lifecycle=ONGOING`이면 새 Alert와 Todo만 만들지 않는다. 새 signal과 evidence는 Detection 이력으로 보존한다. open은 `REJECTED`가 아니고 완료된 Intervention도 없는 Alert이며, `NEW`·`FOLLOW_UP`과 일반 재발·쿨다운 정책은 이 규칙으로 변경하지 않는다.
- 코드 근거: `V9__create_engagement_review_flow.sql`, `EngagementAlert`, `EngagementCandidateService`
- 마지막 검증일: 2026-08-13

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
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: `파일 업로드 -> AI 프로파일링·매핑 제안 -> 강사 검토·수정 -> 백엔드 확정 -> 백엔드 전체 행 변환·검증·저장` 순서를 사용한다.
- AI 책임: AI는 원본 파일을 프로파일링하고 원본 컬럼과 CheckOn 표준 필드의 매핑을 제안한다. 강사 확정 이후 전체 행을 변환한 결과 파일이나 `output_url`을 만들지 않는다.
- 강사 책임: AI 제안을 검토하고 필요한 매핑을 직접 수정한 뒤 백엔드에 확정을 요청한다.
- 백엔드 책임: 강사의 최종 매핑을 검증·확정하고, 원본 파일을 다시 읽어 전체 행을 변환·검증한 뒤 원본 학습 기록을 저장한다. 최종 저장 단계는 LR-002의 강사 테넌트·학생 관계 검증 경계를 재사용한다.
- 금지 사항: AI의 제안을 검토 없이 확정하거나 AI가 백엔드 대신 최종 학습 기록을 소유하게 하지 않는다.
- 현재 코드 상태: 단건 Learning Record 등록 경계만 구현됐으며 Import API, 매핑 저장 모델, 전체 행 변환 및 행별 결과 저장은 없다.
- 코드 근거:
  - `src/main/java/com/checkon/learning/application/RegisterLearningRecordService.java`
  - Import 구현 부재는 `docs/POLICY_GAP_ANALYSIS.md`에서 별도로 대조했다.
- 마지막 검증일: 2026-08-06

#### LR-005 AI Import 프로파일링과 개인정보 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `UNVERIFIED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- 선택안: AI 서비스가 원본 파일을 읽고 컬럼 타입·통계·마스킹 샘플을 만드는 B안을 사용한다.
- 선택 이유: AI팀에 이미 존재하는 타입 추정, 개인정보 의심 컬럼 판별, 마스킹 및 추가 분석 로직을 활용하고 동일 기능의 중복 구현을 줄인다.
- 개인정보 경계: AI 프로파일링 서비스는 원본 파일을 일시적으로 읽을 수 있지만, LLM 요청, 매핑 결과, AI 영속 저장소와 로그에는 실명·연락처 등 원문 값을 포함하지 않는다. 기존 가드레일과 테스트를 유지한다.
- 결과 경계: AI가 반환하는 산출물은 파일 구조와 매핑 제안이며, 실명 포함 전체 행 변환 파일은 반환하지 않는다.
- 트레이드오프: 백엔드만 원본을 읽는 구조보다 AI 서비스의 원본 접근 범위가 넓지만 기존 프로파일링 구현을 재사용할 수 있다.
- 구현 판정 제한: AI 저장소의 실제 코드와 테스트는 현재 백엔드 저장소에서 확인하지 않았으므로 구현 상태를 확정하지 않는다.
- 마지막 검증일: 2026-08-06

#### LR-006 Import 매핑 확정과 재사용의 기준 데이터

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 확정 주체: 강사의 매핑 확정 요청은 백엔드가 받으며 확정 매핑의 권위 있는 원본을 보관한다.
- 재사용 협력: 백엔드는 확정된 매핑을 AI에 알려주고, AI는 동일 양식에서 LLM 재호출을 줄이기 위한 재사용 데이터로 활용한다.
- 권위 경계: AI가 보유한 재사용 정보는 캐시 또는 복제본이며 확정 매핑의 권위 있는 원본은 백엔드다.
- 저장 후보: 강사·테넌트, `source_fingerprint`, 최종 `mapping_spec`, 스키마·매핑 버전, 확정자와 확정 시각이 논의됐으나 정확한 저장 계약은 아직 확정하지 않았다.
- 미확정: 저장 필드와 제약, `source_fingerprint` 계산 규칙, 강사별·조직별 재사용 범위, 확정 통지 API, 멱등성·재시도·폐기·버전 호환 정책.
- 금지 사항: AI의 재사용 데이터만을 확정 매핑의 유일한 원본으로 사용하지 않는다.
- 마지막 검증일: 2026-08-06

#### LR-007 Import 매핑 검증과 행별 검증 책임

- 결정 상태: `CONFIRMED`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- AI 결과: AI는 제안한 컬럼 매핑, 매핑되지 않은 원본 컬럼, 매핑 후보를 찾지 못한 표준 필드, 신뢰도, 강사 확인이 필요한 모호한 매핑, 중복·빈 헤더 등 프로파일링 주의사항을 반환한다.
- 컬럼 식별: 이름이 같은 중복 헤더를 조용히 병합하지 않고 시트와 컬럼 위치 또는 안정적인 `column_id`로 구분한다.
- 확정 검증: AI는 필수 필드 누락을 이유로 `BLOCKED` 또는 최종 확정 불가 상태를 만들지 않는다. 백엔드가 Import 유형별 필수 필드와 강사가 수정한 최종 매핑을 기준으로 확정 가능 여부를 판단한다.
- 행별 검증: 확정 매핑 적용 후 날짜·숫자·필수 값, 학생 연결·소유권, 동의, 중복 및 DB 저장 규칙은 백엔드가 각 행에 대해 최종 판정한다.
- 이유: AI의 최초 제안 이후 강사가 매핑을 수정할 수 있고, 필수 필드와 저장 가능 여부는 백엔드의 최신 제품 정책과 DB 상태에 의존한다.
- 마지막 검증일: 2026-08-06

#### LR-008 Import 결과와 사용자 문구 책임

- 결정 상태: `CONFIRMED`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- 백엔드 결과: 백엔드는 전체·성공·실패·중복 등의 집계와 원본 행 번호, 처리 상태, 오류 코드, 오류 필드를 구조화된 데이터로 제공한다.
- 사용자 문구: 강사 화면의 최종 문구는 백엔드의 구조화된 결과를 바탕으로 기획·프론트엔드가 결정한다. AI팀이 작성한 검증 항목과 문구 초안은 참고자료로 전달한다.
- AI 범위: AI는 프로파일링·매핑 단계의 구조상 주의사항과 매핑 설명을 제공할 수 있지만 실제 Import 행의 최종 성공·실패 문구를 소유하지 않는다.
- 미확정: 부분 성공 상태명, 행별 상태·오류 코드, 재처리 가능 범위와 최종 화면 문구.
- 마지막 검증일: 2026-08-06

#### LR-009 Import 연동 계약과 제품 정책 묶음

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`, `CODE_CONFIRMED`
- 백엔드가 AI에 제공해야 할 계약:
  - 표준 Import 스키마와 Import 유형별 필드 정의
  - AI의 원본 파일 접근 방식과 파일 메타데이터
  - 확정 매핑 및 `source_fingerprint` 전달 계약
  - 비식별 공통 테스트 파일과 기대 프로파일·매핑 결과
- 추가 결정 필요:
  - 지원 파일 형식, 크기·행·시트 제한, 수식·병합 셀·숨김 행 처리
  - 학생 식별과 동명이인 처리
  - 동의 종류와 미동의 행 처리
  - 중복 기록 판정 및 부분 성공·재처리 정책
  - 원본 파일 보존 기간과 접근 URL 만료 정책
  - 매핑 검증·확정 API 및 HTTP 오류 계약
- 금지 사항: 위 항목을 확정하기 전에 API 경로, 필수 필드, fingerprint 알고리즘, 오류 enum 또는 DB 제약을 임의로 구현하지 않는다.
- 현재 코드 상태: 백엔드 Import API와 저장 모델은 없다.
- 마지막 검증일: 2026-08-06

#### LR-004 반복 문항 식별 정책

- 결정 상태: `OPEN`
- 구현 상태: `NOT_IMPLEMENTED`
- 근거 수준: `CONVERSATION_ONLY`, `CODE_CONFIRMED`
- 현재 상태: 안정적인 문항 ID 또는 문제은행 계약이 없으며 `external_record_ref`도 문항 ID로 확정되지 않았다.
- 결정 필요: 동일 문항, 유사 문항, 변형 문항을 어떤 수준에서 동일하게 볼지와 외부 시스템 식별자를 누가 소유할지 합의해야 한다.
- 금지 사항: 현 단계에서 `external_record_ref`를 문항 ID 또는 멱등 키로 간주하지 않는다.
- 마지막 검증일: 2026-08-04

#### LR-010 위험신호 학습 태그 입력 계약

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `EXTERNAL_CONTRACT`
- nullable 경계: `areaTag`, `subjectTrack`, `typeTag`, `itemFormat`은 모두 nullable이다. null을 임의 값으로 보정하거나 누락 때문에 학습기록 등록 전체를 거절하지 않는다.
- 허용 값: `areaTag`는 `reading`, `literature`, `speech_writing`, `language`, `media`, `subjectTrack`은 `common`, `elective`, `typeTag`는 `fact`, `infer`, `critic`, `concept`, `apply`, v1 `itemFormat`은 `mcq`만 허용한다. 대소문자·한글 표시명·임의 값은 입력 시점에 거절한다.
- 영역 의미: `areaTag`는 지문 세트나 소재가 아니라 문항별 측정 대상이다. `speech`와 `writing`은 `speech_writing`으로 조용히 매핑하지 않고 구버전 값으로 거절한다.
- 과목 유도: 두 값이 모두 있으면 `reading`·`literature`의 `subjectTrack`은 `common`, `speech_writing`·`language`·`media`는 `elective`여야 한다. 한 값이 null이면 다른 값을 자동 생성하지 않는다.
- 유형 의미: `typeTag`는 `areaTag`와 직교하며 특정 영역에 하나의 유형을 강제하지 않는다. `apply`는 입력 사실로 보존하되 v1 R6 집계 제외 여부는 AI 규칙 책임이다.
- 규칙 입력: R1은 `SOLVE.correct`, R4는 `SOLVE.durationSec`과 선택적 `passageWordCount`, R6는 `areaTag`·`typeTag`·`correct`를 사용한다. 입력 검증은 태그 어휘와 영역-과목 정합을 보장하고 AI 신호 발화용 데이터를 인위적으로 만들지 않는다.
- 실패 경계: 잘못된 태그 한 건은 `POST /api/v1/learning-records`에서 `400 INVALID_REQUEST`로 거절하여, 저장 후 Detection 요청 전체가 AI `400 INVALID_SCHEMA`가 되는 것을 방지한다.
- 코드 근거:
  - `src/main/java/com/checkon/learning/presentation/LearningRecordController.java`
  - `src/main/resources/openapi/dashboard-api.yaml`
  - `src/test/java/com/checkon/learning/presentation/LearningRecordControllerIntegrationTest.java`
- 마지막 검증일: 2026-08-13

### Detection·AI 연동

#### DET-001 운영 Detection 입력과 분석 기간

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 운영 API는 인증된 강사와 `analysisDate`를 기준으로 서버가 테넌트 키와 56일 학습 기록 스냅샷을 만든다. 서비스 시간대는 `Asia/Seoul`이다.
- 빈 기록 처리: 56일 `learning_events`가 비어도 활성·전송 가능 학생이 있으면 관계 시작 주부터 최대 10주 `detection_evidence`를 포함해 분석한다. 활성·전송 가능 학생이 전혀 없을 때만 기존 `NO_LEARNING_RECORDS` 응답으로 거절한다.
- 학생 상태: 현재 강사와의 관계가 `ACTIVE`인 학생만 AI 요청에 포함하고 반 배정 여부와 무관하게 `enrolled`로 보낸다. Backend v1에는 휴원·복귀 상태 전환 기능이 없으므로 반 미배정을 `paused`로 해석하지 않는다. 종료 관계의 과거 학습 기록은 보존하되 현재 Detection 요청에서는 제외한다. 반 미배정 학생의 `class_ref`는 `cl_unassigned`다.
- 재원 기간: `students[].enrolled_weeks`는 반 등록 시각이 아니라 현재 강사-학생 관계의 `teacher_student_relationships.started_at`부터 계산한다. 반 변경·미배정으로 재원 기간을 초기화하지 않으며 결과는 0 이상이다.
- 학습기록 source: v1 학습기록 유입 경로는 강사 수기 입력뿐이며 저장된 `MANUAL`을 변환하지 않고 `learning_events[].source`로 보낸다. source whitelist나 임의 제외 규칙을 Backend에 추가하지 않는다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/OperationalDetectionRunService.java`
  - `src/main/java/com/checkon/detection/application/DetectionTenantKey.java`
  - `src/main/java/com/checkon/detection/presentation/DetectionRunExceptionHandler.java`
- 마지막 검증일: 2026-08-13

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
- 진단 통계: AI의 `r1_threshold_pp`, `r1_threshold_source`, `r1_pool_n`은 nullable 진단 메타데이터로 Adapter completed 이벤트를 거쳐 `detection_runs.response_stats_payload`에 보존한다. 화면 API에는 노출하지 않으며 필드가 없는 기존 응답도 허용한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/DetectionResponseStorageService.java`
  - `src/test/java/com/checkon/detection/application/DetectionResponseStorageServiceTest.java`
- 마지막 검증일: 2026-08-13

#### DET-004 일일 Detection 자동 실행

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- 실행 시각: 매일 `02:10`, `Asia/Seoul`. Spring cron은 초를 포함한 `0 10 2 * * *`를 사용하고 JVM 기본 시간대에 의존하지 않는다.
- 분석 날짜: 주입된 `Clock`으로 실행 시각의 `Asia/Seoul` 날짜를 계산하고 그 당일을 `analysisDate`로 사용한다.
- 실행 대상: `teacher_profiles`가 존재하며 연결된 Account의 역할과 상태가 각각 `TEACHER`, `ACTIVE`인 강사다. 활성 학생 관계나 학습 기록 존재 여부를 대상 목록 SQL에 중복 구현하지 않는다.
- 빈 기록: 56일 학습 기록이 0건이어도 활성·전송 가능 학생이 있으면 관계 시작 주부터 주간 활동 `0` 근거를 넣어 실행한다. 활성·전송 가능 학생도 없을 때만 `NO_LEARNING_RECORDS`로 건너뛰고 다음 강사를 계속 처리한다.
- 실패 격리: 강사별 실행을 독립적으로 처리하고 한 강사의 실패가 나머지 강사의 실행을 중단시키지 않는다. 성공, 이미 완료·중복, 학습 기록 없음, 실패를 집계한다. 복구 불가능한 JVM `Error`는 삼키지 않는다.
- 일별 멱등성: 같은 강사와 `analysisDate`에는 논리적인 Detection run 하나만 허용한다. 기존 `(teacher_id, analysis_date)` 및 `idempotency_key` 유일 제약과 일별 멱등 키를 재사용한다. 이미 `SUCCEEDED`인 run은 AI를 다시 호출하지 않으며 기존 `FAILED` run은 새 attempt로 재시도한다.
- 실행 방식: 첫 구현은 강사를 정해진 순서로 순차 실행한다. 무제한 병렬 처리와 별도 비동기 executor를 사용하지 않는다.
- 누락 실행: 정시 실행만 담당하며 서버 중단 시간의 자동 catch-up이나 과거 날짜 소급 실행은 하지 않는다. 누락분은 기존 운영 Detection API로 수동 실행한다.
- Kafka 실행 경계: 스케줄러와 교사 요청 API는 `OperationalDetectionRunService`를 호출하고 Kafka 요청 Outbox를 만든다. 독립 `checkon-kafka-adapter` 애플리케이션이 requested topic을 소비해 AI `POST /v1/detect`를 호출한다. AI 서버는 Kafka를 직접 소비·발행하지 않는다. 독립 Adapter는 HTTP 결과를 completed/failed topic으로 변환하고 Backend Result Consumer가 최종 상태와 신호를 저장한다. Backend 내장 HTTP Adapter Consumer는 fallback 코드로만 유지하며 기본 비활성화한다. 두 Adapter를 동시에 활성화하지 않는다.
- 이벤트 계약: Backend `checkon.risk-detection.requested.v1` → 독립 Adapter, 독립 Adapter → Backend Result Consumer `checkon.risk-detection.completed.v1`/`checkon.risk-detection.failed.v1`를 사용한다. 모든 메시지는 `schema_version=1.0` envelope와 `event_id`, `correlation_id(=run_id)`, `causation_id`, `tenant_alias`, `run_id`, `attempt_id`, `request_id`, `idempotency_key`, `snapshot_hash`를 가진다. AI와의 외부 계약 정본은 AI OpenAPI `POST /v1/detect`, Backend-Adapter Kafka 정본은 `docs/contracts/risk-detection-kafka.asyncapi.yaml`이다.
- HTTP 변환: Adapter는 `payload` 전체를 요청 body로 보내고 envelope의 `tenant_alias`를 `X-Tenant-Id`, `request_id`를 `X-Request-Id`, `idempotency_key`를 `Idempotency-Key`로 그대로 보낸다. 동일 requested 이벤트의 재전달은 세 값과 body·snapshot hash를 유지한다. Read timeout은 AI 계약의 최소 60초보다 긴 65초를 기본으로 둔다. HTTP 200은 completed, 400·409는 재시도하지 않는 failed로 변환한다. 네트워크·timeout·빈 응답·5xx는 Kafka 재시도 후 DLT에 보존하고 최종 failed 이벤트를 발행한다.
- 과거 경보 컨텍스트: AI 요청의 `alert_context`는 현재 분석 대상 학생의 기존 Engagement Alert를 학생 AI alias와 `signal_type` 기준으로 제공한다. 미검토 또는 후속 조치가 끝나지 않은 Alert는 `open`, 거절 또는 완료된 Intervention이 있는 Alert는 `resolved`로 보낸다. 완료 Intervention이 있으면 `followed_up=true`와 완료 시각을, 거절이면 `followed_up=false`와 결정 시각을 사용한다. 같은 학생·신호 유형에 open 이력이 있으면 가장 최근 open을 우선하고, 없으면 가장 최근 resolved만 보낸다.
- v1 위험신호 범위: v1은 R1(정답률 하락)·R3(학습 공백)·R4(숨은 위기)·R6(유형 편중)만 사용한다. 과제 예정 건수와 휴원·복귀를 만드는 production 기능이 없는 동안 R2(제출 저조)·R5(복귀 케어)는 제외한다.
- 학습 공백 근거: `payload.detection_evidence`는 선택 필드이며 기존 request와 저장 snapshot의 `assignment_window`·`enrollment_transition` 역직렬화 호환성은 유지한다. 새 v1 요청은 `weekly_activity`만 만든다. 학생별 `teacher_student_relationships.started_at`의 `Asia/Seoul` 소속 주 월요일보다 이전 주는 근거 미존재로 행을 생략하고, 관계 시작 주부터 실제 활동이 0건이면 `activity_count=0`을 보낸다. 논리 `source_table`은 `student_week_activity`로 고정하고 실제 PostgreSQL 물리 테이블명은 외부에 노출하지 않는다. 적용된 V15 테이블과 RLS 정책은 변경하지 않는다.
- 근거 조회: AI 완료 결과의 `(source_table, record_id)`는 해당 run의 불변 요청 스냅샷에 존재하는 정확한 쌍만 저장한다. 기존 학습 기록의 legacy source name은 호환을 위해 record_id 기준으로 읽되, 새 부재·복귀 근거는 쌍을 엄격히 대조한다. 강사 Alert 상세 화면은 저장된 source·record_id·AI 요약을 제공한다.
- 운영 조회: 강사 Detection run 상태 조회는 성공 응답에 저장된 `stats`와 `rules_skipped`의 규칙 ID·사유·대상 학생 수를 제공한다. 실행 중이거나 실패해 성공 stats가 없으면 `stats=null`을 반환해 정상적인 신호 0건과 근거 부족으로 규칙을 실행하지 못한 경우를 구분한다.
- advisory 신호: AI 응답의 `advisory`는 `detection_signal_results`에 보존한다. `true`이면 학생 상세 참고용으로만 유지하며 Engagement Alert·오늘 할 일(Todo)·대시보드 확인 필요 신호 후보에서 제외하고 TOP N 슬롯을 소비하지 않는다. `false`만 기존 Alert 후보 흐름을 따른다.
- advisory lifecycle: advisory 신호는 Alert와 `alert_context`를 만들지 않으며 반복 실행에서 `lifecycle=new`가 될 수 있는 현재 동작을 유지한다.
- hash: `detection_evidence`는 `snapshot_hash` 대상이다. 누락과 빈 배열은 동일하고, 배열은 `(kind, student_ref, at, source_table, record_id)`, JSON key는 오름차순, UTF-8·공백 없는 JSON으로 정규화한다. `snapshot_hash` 자신과 `classes`는 hash 입력에서 제외한다. AI 팀이 수정된 참조 구현과 실요청형 벡터를 제공하기 전까지 Java hasher와 고정 벡터 테스트를 변경하지 않는다.
- 메시지 크기: 40명 기준 약 1.59 MiB payload와 Kafka envelope를 수용하도록 개발 Compose broker와 Spring producer/consumer는 3 MiB로 설정한다. 운영 broker·AI consumer도 같은 값 이상을 배포 설정에서 보장해야 한다.
- 기존 Run 호환: Kafka 도입 전 `teacher_<uuid>:date` 형식으로 저장된 기존 `idempotency_key`는 데이터 마이그레이션으로 일괄 수정하지 않는다. 백엔드 내부에서만 legacy key를 인정해 기존 run의 상태·재시도를 보존하고, 새 Kafka 메시지에는 항상 `tenant_alias:date`만 넣는다.
- 요청 내구성: Detection run·attempt·Outbox 행을 같은 DB 트랜잭션으로 저장한다. Outbox publisher는 `PENDING`을 at-least-once로 발행하고 최대 8회 전송 실패하면 Outbox와 해당 run을 `KAFKA_PUBLISH_FAILED`로 실패 처리한다. 같은 날짜의 후속 수동/스케줄 요청은 새 attempt로 재시도할 수 있다.
- 결과 내구성: 완료·실패 Consumer는 Inbox의 `event_id` unique 제약으로 멱등 처리한다. 처리·검증·결과 저장은 한 트랜잭션이고, 실패 시 1초·2초 간격 총 3회 재시도한 뒤 `<topic>.dlt`로 보낸다. 오래되었거나 이미 대체된 attempt 결과는 저장하지 않는다.
- Adapter 전달 보장: 독립 Adapter requested Consumer는 Inbox에 요청을 먼저 저장하고 AI의 `Idempotency-Key` 계약으로 HTTP 중복 호출을 안전하게 만든다. completed/failed는 Adapter Outbox와 Kafka broker 확인을 거쳐 발행하고, Backend Result Consumer의 현재 attempt 검사와 Inbox로 한 번만 반영한다.
- partition key·순서: 요청과 응답의 Kafka key는 opaque `tenant_alias`다. 같은 강사 내 순서는 보장하되, 강사 간 전체 순서는 보장하지 않는다.
- 운영 제외: 자동 catch-up, 제한 병렬 처리, 신규 분산락 라이브러리, 운영 모니터링 대시보드, 운영 broker TLS/SASL·ACL·retention 수치와 Backend HTTP Adapter 동시 처리량은 이번 애플리케이션 구현 범위 밖이며 배포 환경에서 확정한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/infrastructure/scheduling/DetectionScheduler.java`
  - `src/main/java/com/checkon/detection/application/ScheduledDetectionJob.java`
  - `src/main/java/com/checkon/detection/application/ScheduledDetectionTargetProvider.java`
  - `src/main/java/com/checkon/detection/application/OperationalDetectionRunService.java`
  - `src/main/java/com/checkon/detection/application/DetectionAttemptCoordinator.java`
  - `src/main/java/com/checkon/detection/application/KafkaDetectionRequestService.java`
  - `src/main/java/com/checkon/detection/application/KafkaDetectionResultConsumer.java`
  - `src/main/java/com/checkon/detection/application/KafkaDetectionHttpAdapter.java`
  - `src/main/java/com/checkon/detection/integration/kafka/KafkaOutboxPublisher.java`
  - `src/main/java/com/checkon/detection/integration/kafka/KafkaDetectionResultListener.java`
  - `src/main/java/com/checkon/detection/integration/kafka/KafkaDetectionHttpAdapterListener.java`
  - `src/main/java/com/checkon/learning/application/LearningRecordSnapshotService.java`
  - `src/main/resources/db/migration/V14__add_risk_detection_kafka_outbox.sql`
  - `src/main/resources/db/migration/V15__create_detection_evidence_projections.sql`
  - `docs/contracts/risk-detection-kafka.asyncapi.yaml`
  - `src/test/java/com/checkon/detection/infrastructure/scheduling/DetectionSchedulerTest.java`
  - `src/test/java/com/checkon/detection/application/ScheduledDetectionJobTest.java`
  - `src/test/java/com/checkon/detection/application/ScheduledDetectionTargetProviderIntegrationTest.java`
- 마지막 검증일: 2026-08-13

#### DET-005 동의 기능 전 AI 분석 임시 정책

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 현재 임시 정책: 동의 UI와 백엔드 저장 기능이 구현되기 전에는 `PRE_CONSENT_ALLOW_ALL`을 기본값으로 사용한다. 이 모드에서 서버가 만든 AI 스냅샷의 학생 `consent`는 `granted`이며, 시현·테스트·승인된 임시 운영에서 실제 분석 요청과 결과 저장이 계속 가능하다.
- 의미 구분: 이 값은 현재 외부 AI 계약의 분석 허용 필드이며, 학생이 실제로 동의했다는 영속 기록을 새로 만드는 기능은 아니다. 동의 원본·시각·약관 버전은 아직 저장하지 않는다.
- 전환 정책: 프론트엔드가 받은 동의 결과를 백엔드에 저장하는 기능이 배포되면 설정을 `REQUIRE_RECORDED_GRANT`로 전환한다. 현재 저장된 grant가 없는 학생은 `unknown`으로 보고 학생·학습 이벤트·반 참조를 모두 AI 스냅샷에서 제외한다. 실제 저장 상태 조회는 동의 도메인 구현 시 이 정책에 연결한다.
- 전송 경계: 동의 판단은 AI 호출 또는 미래 Kafka 요청 이벤트를 만들기 전에 백엔드 스냅샷 생성 단계에서 수행한다. 따라서 전송 방식이 HTTP에서 Kafka로 바뀌어도 같은 정책을 재사용한다.
- 코드 근거:
  - `src/main/java/com/checkon/detection/application/AiDetectionConsentMode.java`
  - `src/main/java/com/checkon/detection/application/AiDetectionConsentPolicy.java`
  - `src/main/java/com/checkon/detection/integration/ai/AiDetectionConsentProperties.java`
  - `src/main/java/com/checkon/learning/application/LearningRecordSnapshotService.java`
  - `src/test/java/com/checkon/learning/application/LearningRecordSnapshotServiceTest.java`
- 남은 범위: 실제 동의 테이블, 프론트엔드 결과 수신 API, 동의·철회 시각과 약관 버전, 동의 변경 시 기존 Detection 재시도·보존 정책은 후속 동의 기능 범위에서 결정·구현한다.
- 마지막 검증일: 2026-08-12

### Problem Generation·AI 연동

#### PG-001 v1 문제 출제 범위와 책임 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `EXTERNAL_CONTRACT`
- 레거시 v1 입력: 기존 `/problem-requests` 계약은 강사가 AI taxonomy 목표를 직접 선택하는 `teacher_manual`, 문법 `language`, 객관식 `mcq`, 지문 없음과 문항 수 1~10을 유지한다.
- 프론트 출제 스튜디오 입력: Step 1에서 학생과 영역×유형별 문항 수를 선택하고 Step 2에서 공통 난이도를 정한다. AI v1 evidence가 준비된 셀은 `language × CONCEPT`, `language × INFER`뿐이며 Backend는 이 두 셀만 허용한다. 같은 skill node에서 여러 문항을 생성하는 것은 허용하고 한 요청의 합계는 1~20문항으로 유지하되 시연 권장 수량은 1~3문항이다.
- 책임 경계: 백엔드는 인증·테넌트 소유권·alias 변환·요청 저장·결과 미러링·강사 검토·발행을 소유한다. AI는 생성·검증 결과만 소유하며 AI 검증 상태는 강사 승인 상태가 아니다.
- 개인정보: Kafka와 AI payload에는 학생·강사 실명, 연락처, 내부 학생·반 UUID를 넣지 않는다. `tn_`, `st_`, `cl_` opaque alias만 사용한다.
- 현재 제한: 약점 분석 화면은 백엔드 학습 기록을 읽어 강사 선택을 돕지만 출제 대상을 자동 확정하지 않는다. 문항 직접 수정·교체·삭제와 학생용 과제 조회·제출은 후속 범위다.
- 마지막 검증일: 2026-08-13

#### PG-002 Kafka 어댑터와 AI HTTP 전용 통신 경계

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `EXTERNAL_CONTRACT`, `CODE_CONFIRMED`
- 목표 통신 경계: CheckOn 백엔드는 요청과 Outbox를 원자 저장해 Kafka로 전달하고, 별도 Kafka 어댑터 서버가 이를 소비해 AI 서버의 HTTP API를 호출한다. AI 서버는 Kafka consumer·producer를 소유하지 않고 HTTP만 제공한다.
- 현재 구현: CheckOn 백엔드 내부에는 Kafka 요청 publisher와 결과 listener·멱등 소비·DLT가 구현돼 있다. 별도 어댑터 서버는 아직 없으며 CheckOn 백엔드 내부에는 AI HTTP client를 추가하지 않는다.
- 결과 경로: 별도 어댑터가 AI job 상태와 items를 HTTP로 조회하고 정규화한 `worker_job.*` 결과 이벤트를 Kafka에 발행하며, CheckOn 백엔드는 기존 결과 listener로 소비한다.
- 실패 처리 배분: CheckOn 백엔드는 Kafka 발행·소비 retry와 DLT를, 어댑터는 AI HTTP timeout·retry·polling·멱등성을 소유한다.
- 공통 신뢰성 정본: Kafka envelope·opaque `tenant_alias` partition key·Transactional Outbox·Inbox `event_id` 멱등·불변 요청 snapshot/hash·요청 snapshot 대비 결과 검증·attempt 이력·늦거나 대체된 결과 무시 규칙은 `DET-004` 위험탐지 계약을 우선 적용한다.
- Kafka 결과 소비 재시도: 위험탐지와 동일하게 최초 처리 포함 총 3회, 실패 간격 1초·2초 후 DLT로 보낸다. AI HTTP 호출의 `Retry-After`, timeout과 재호출 간격은 어댑터의 기능별 계약이며 Kafka 공통 backoff와 섞지 않는다.
- 추적: `event_id`, `correlation_id=problem_request_id`, AI `job_id`, `execution_id`, `set_id`, backend·adapter·AI 멱등 키를 구분한다.
- 설정 경계: 토픽·consumer group·Broker TLS/SASL·ACL은 백엔드/어댑터 운영값이며, AI base URL·인증·HTTP timeout은 어댑터 설정으로 둔다.
- 코드 근거:
  - `src/main/java/com/checkon/problem/integration/kafka`
  - `src/main/java/com/checkon/problem/infrastructure/outbox`
  - `src/main/resources/db/migration/V16__create_problem_generation_kafka_boundary.sql`
- 마지막 검증일: 2026-08-12

#### PG-003 불완전한 AI 결과 계약의 보존 정책

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `EXTERNAL_CONTRACT`
- 보존 정책: AI 결과와 versions 원문을 `jsonb`로 저장하고, 원문에 실제로 존재하는 값만 별도 문항 read model로 투영한다.
- 최신 AI 계약: `POST /v1/problems`는 `job_id`와 상태를 반환하고, 상태 조회는 `GET /v1/problems/{job_id}`다. terminal 결과의 `set_id`로 `GET /v1/problems/{set_id}/items` 요약 목록을 조회한 뒤 각 `slot_index`에 대해 `GET /v1/problems/{set_id}/items/{slot_index}`를 호출해야 전체 문항 본문을 얻는다.
- 금지 사항: 원문에 없는 문항 상세를 추론해 만들거나 AI `verified`를 강사 승인·학생 발행으로 간주하지 않는다.
- 연계 조건: 별도 어댑터가 HTTP items 조회 시 tenant alias와 job 소유권을 검증하고 AI 응답을 backend용 result event로 정규화한다. CheckOn 백엔드는 AI HTTP 응답을 직접 처리하지 않는다.
- 확정된 어댑터 관찰 계약: `POST /v1/problems` timeout은 300초이며 응답이 비종단일 때만 polling한다. AI는 `Retry-After`와 전체 처리 deadline을 현재 제공하지 않는다. child 관찰 상한 21분은 Adapter 운영 정책이며 초과 시 `timed_out`으로 종결한다. 늦게 도착한 AI 성공은 감사·회수 대상으로만 남기며 이미 terminal인 부모 상태를 되돌리지 않는다.
- 계약 정본: AI HTTP v1은 AI 저장소의 HTTP fixture 17종을 임시 정본으로 사용하고, adapter의 HTTP→Kafka normalized fixture는 adapter 저장소가 소유한다. AI OpenAPI는 실제 schema가 채워지기 전까지 정본이 아니다.
- 마지막 검증일: 2026-08-13

#### PG-004 프론트 출제 스튜디오 4단계 계약

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `FRONTEND_DESIGN`
- Step 1: 인증 강사의 ACTIVE 학생만 페이지 조회한다. 표시 이름은 강사가 접근 가능한 `student_personal_information.real_name`을 우선하고 없으면 학생 프로필 alias를 사용한다. ACTIVE 클래스·과목, 관계 시작일 기준 관리 일수, 최근 30일 Engagement Alert 수와 현재 생성 가능한 셀 목록을 함께 반환한다. 프론트는 생성 가능한 목록에 없는 셀을 비활성화한다.
- 약점 분석: 최근 8주 `SOLVE` 학습 기록 중 정오답과 영역·유형이 모두 있는 행을 집계한다. 셀 표본이 10건 미만이면 `ON_HOLD`, 10건 이상이면 학생 전체 평균 이상을 `GOOD`, 미만을 `WEAK_SIGNAL`로 제공한다. 화면의 `WEAK_CONFIRMED` 임계값은 확정 근거가 없어 자동 생성하지 않는다.
- Step 2: Step 1에서 선택한 영역×유형별 문항 수를 그대로 이어받고 공통 난이도와 함께 하나의 요청 snapshot으로 저장해 Transactional Outbox에 반영한다. 시안의 12문항과 7문항은 서로 다른 예시 화면이며 같은 흐름의 값 충돌이 아니다. 프론트가 내부 taxonomy ID를 알 필요는 없다.
- Step 3: AI 원문 결과는 계속 `jsonb`로 보존한다. 문두·선지·정답·출제 근거·검증 상태가 있는 결과만 별도 read model로 투영하며, 불완전한 결과는 추론해 채우지 않고 `UNSUPPORTED`로 표시한다.
- Step 4: 교사가 선택한 문항만 저장 세트와 발행 과제에 포함한다. AI 검증 상태, 교사 선택, 저장, 학생 발행은 서로 다른 상태다. 같은 요청의 저장과 발행 재호출은 중복 세트·과제를 만들지 않는다.
- PDF: 백엔드는 선택 문항과 대상 학생을 포함한 인쇄용 구조화 데이터를 제공한다. 실제 PDF 레이아웃·폰트 렌더링은 프론트가 소유하며 서버 PDF 라이브러리는 이번 범위에 추가하지 않는다.
- 개인정보·테넌트: 학생 실명과 내부 UUID는 강사 REST 응답과 백엔드 DB 안에서만 사용한다. Kafka/AI에는 기존 `tn_`, `st_` alias 경계를 유지하고 모든 신규 테이블에 FORCE RLS를 적용한다.
- 마지막 검증일: 2026-08-13

#### PG-005 출제 스튜디오와 AI 계약 충돌 격리

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `EXTERNAL_CONTRACT`, `FRONTEND_DESIGN`
- fan-out: backend 부모 요청의 `targets[]`에서 영역×유형 셀 하나당 child execution 하나를 만들고, 별도 adapter가 child별 `POST /v1/problems`를 호출한다. 부모 합계는 최대 20문항이다.
- v1 target 해석: Backend는 AI 전용 `skill_node_id`를 저장하거나 하드코딩하지 않는다. Adapter는 AI taxonomy catalog에서 요청의 `area_tag`와 `type_affinity`가 일치하고 `has_evidence=true`인 node를 안정 정렬해 `manual_targets`로 채운다. 현재 결과는 `language.grammar.phonological_change` 하나이며 후보가 없으면 AI를 호출하지 않고 `NO_EVIDENCE_READY_TARGET` 실패 결과를 반환한다.
- child 식별: `problem_request_id`, `problem_execution_id`, `target_index`, `adapter_execution_id`, AI `execution_id`·`job_id`·`set_id`를 분리한다. AI ID는 부모 단일 컬럼이 아니라 child별로 보존한다. `X-Request-Id` echo는 추적 보조값일 뿐 소유권·상관관계의 정본이 아니다.
- 상태 집계: child가 하나라도 실행 중이면 부모 `RUNNING`, 전부 종단이고 성공 문항이 있으면서 실패 child가 없으면 `SUCCEEDED`, 성공 문항과 실패 child가 함께 있으면 `PARTIAL_SUCCESS`, 성공 문항이 없고 실패가 있으면 `FAILED`로 집계한다. 전부 `rejected_insufficient`인 0건 결과는 업무상 완료로 취급한다.
- 문항 전달: adapter는 `GET /v1/problems/{set_id}/items` 요약 뒤 `GET /v1/problems/{set_id}/items/{slot_index}`를 N+1 방식으로 조회해 문항 전량을 Kafka 결과 이벤트에 싣는다. AI의 `choices[{no,text,why_wrong}]`와 1-based `answer.correct_no`는 Backend 호환 구조로 정규화한다. 상한 초과 시 adapter 소유 저장소의 `result_ref`로 대체하며 AI 재조회에 의존하지 않는다.
- Step 1 진단: AI `POST /v1/diagnosis`를 판정 정본으로 채택하되 동기 HTTP 경로 `BE → adapter → AI`로 호출하고 timeout은 5초로 한다. 실패 시 과거 판정이나 backend 자체 계산을 섞지 않고 빈 grid를 반환한다. 현재 학습 기록에는 `tag_confirmed`, `skill_node_id`가 없어 입력 정책·스키마 보완 전에는 기존 backend 집계를 즉시 교체하지 않는다.
- 영역·자료: AI는 5영역을 측정하되 화면은 원본 셀을 보존해 표시한다. 자료 입력 화면이 없는 v1에서는 `language` 외 출제를 비활성화하고 `media`를 `language`로 임의 변환하지 않는다. 이는 AI 미지원이 아니라 passage/work/material 입력 화면 미구현 제한이다.
- HTTP 멱등: AI 보존 기간은 30일이다. 문제 생성은 canonical JSON SHA-256, 진단은 body의 `snapshot_hash`를 동일성 축으로 사용한다. adapter child 매핑도 30일 이상 보존한다.
- 출제 source 변환: 프론트의 강사 약점 선택 의미는 Backend snapshot에 보존하고, AI v1 호출의 `target_source`는 실제 지원 값인 `teacher_manual`로 adapter가 변환한다. 이는 자동 약점 출제 `weakness_auto`를 의미하지 않는다.
- 검증 상태 변환: AI `verified`, `needs_review`, `verification_unavailable`, `dropped`는 각각 Backend `PASSED`, `REVIEW_REQUIRED`, `UNVERIFIABLE`, `EXCLUDED`로 보존한다. AI 검증 상태는 교사 승인과 분리한다.
- AI ID 안정성: AI가 GET 응답마다 새 `meta.execution_id`를 만드는 현재 결함이 해결될 때까지 Adapter는 POST에서 받은 `execution_id`를 정본으로 보존하고 GET의 다른 값을 무시한다.
- 기능별 예외: 21분 polling, 셀별 child fan-out, 문항 본문 전량 이벤트, 부모 `PARTIAL_SUCCESS`, 교사 선택·저장·발행은 문제 출제 전용이며 위험탐지 공통 계약으로 확장하지 않는다.
- 남은 운영 차단: AI 요청·결과 저장소의 인메모리 구조와 신규 HTTP endpoint 배포 파이프라인은 운영 전 해소해야 한다. HTTP 인증은 v1 내부망 전제이며 망 경계가 바뀌면 별도 계약을 추가한다.
- 마지막 검증일: 2026-08-13

### Frontend·UX

#### SCREEN-PAGE-001 화면 목록 페이지와 안정 정렬

- 결정 상태: `CONFIRMED`
- 구현 상태: `PARTIAL`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 페이지 계약: API의 `page`는 0부터 시작하며 기본값은 0이다. `size` 기본값은 20이고 허용 범위는 1~100이다.
- 정렬 계약: 클래스 목록은 `createdAt DESC, id DESC` 순으로 정렬한다. `id`는 동일 생성 시각의 안정적인 마지막 tie-breaker다.
- 응답 메타데이터: `content`, `page`, `size`, `totalElements`, `totalPages`를 반환한다.
- 화면 번호: 행 번호는 영속 데이터나 API 필드가 아니다. 프론트가 `page * size + index + 1`로 계산한다.
- 금지 사항: 전체 목록 반환 후 프론트 페이지 처리, UUID를 화면 번호로 노출, tie-breaker 없는 정렬.
- 영향 범위: 화면 목록 API, OpenAPI, 페이지 경계·빈 목록·안정 정렬 테스트.
- 구현 범위: 이번 변경에서는 클래스 목록에 적용했다. 향후 학생·신호·케어 등 다른 화면 목록은 해당 단위 구현 전까지 이 정책의 적용 범위 밖이다.
- 코드 근거:
  - `src/main/java/com/checkon/roster/infrastructure/persistence/ClassGroupQueryRepository.java`
  - `src/main/java/com/checkon/roster/application/ClassManagementService.java`
  - `src/main/resources/openapi/dashboard-api.yaml`
  - `src/test/java/com/checkon/roster/presentation/ClassGroupControllerIntegrationTest.java`
  - `src/test/java/com/checkon/global/openapi/ClassManagementOpenApiContractTest.java`
- 마지막 검증일: 2026-08-09

#### ENG-005 개입 기반 Reminder 자동 생성과 대시보드 집계

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`
- 생성: `APPROVED` Alert에 OPEN Intervention을 생성할 때 같은 트랜잭션에서 생성 시각 7일 뒤의 ACTIVE Reminder를 하나 생성한다. 시각은 주입된 `Clock`에서 한 번만 구한다.
- 상태 전이: Intervention 취소 시 연결된 ACTIVE Reminder만 같은 시각으로 취소한다. Intervention 완료 시 Reminder는 변경하지 않는다. Reminder 직접 완료·취소 API는 유지한다.
- 대시보드: 조회일 다음 날의 `Asia/Seoul` 시작 Instant 미만인 ACTIVE Reminder와 OPEN Intervention만 Alert별로 집계한다. 대표는 Intervention `createdAt DESC, id DESC`이고, 유효 Intervention 수를 `interventionCount`로 반환한다. 카드는 `scheduledAt ASC, alertId ASC`로 정렬한다.
- API 계약: 목록 필드는 `reminders`, 도메인 단건은 `Reminder`, 상태는 `ACTIVE`, `COMPLETED`, `CANCELLED`만 사용한다. 기존 수동 Reminder 생성 API와 `scheduledAt` 입력 계약은 제거한다.
- 기존 데이터: Reminder가 하나도 없는 OPEN Intervention만 `createdAt + 7일`로 backfill하고 기존 Reminder의 상태와 예정 시각은 보존한다.
- 테넌트·개인정보: 인증 주체의 `teacherProfileId`와 PostgreSQL RLS를 함께 사용하며 summary는 `개입 후 재확인이 예정되어 있습니다.` 고정 문구만 반환한다.
- 제외: 재예약, 수동 시각 입력, 알림 발송, Alert당 ACTIVE Reminder 유일 제약은 구현하지 않는다.
- 마지막 검증일: 2026-08-05

#### DASH-001 날짜별 브리핑 경보 조회

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 인증된 강사는 `Asia/Seoul` 기준 오늘 또는 과거 날짜의 저장된 브리핑 경보를 조회한다. 미래 날짜는 `FUTURE_DATE_NOT_ALLOWED`로 거절한다.
- 테넌트·상태: 테넌트는 인증 주체의 `teacherProfileId`에서만 결정하며 PostgreSQL RLS를 함께 적용한다. 경보 상태는 `PENDING_REVIEW`, `APPROVED`, `REJECTED`를 별도 변환 없이 반환한다.
- 개인정보: Alert와 Reminder의 `studentName`은 nullable이며, `student_personal_information.real_name` 미등록 시 JSON key를 유지한 채 null을 반환한다. `student_profiles.alias` 또는 AI alias로 대체하지 않는다. 실명 저장은 `PUT /api/v1/students/{studentId}/personal-information/name`이 담당한다(ROS-004).
- 함께 제공하는 대시보드 데이터: `alerts`, 조회일까지 이월된 미완료 `todos`(ENG-004), 조회일 기준 대상 `reminders`(ENG-005)를 한 응답에서 제공한다. 주간 캘린더는 별도 `GET /api/v1/dashboard/calendar` 계약(DASH-002)이다.
- 화면 복원 필드: `alerts[]`는 기존 값에 `className`, `displayLabel`, `createdAt`을 추가한다. `todos[]`는 연결 신호의 `displayLabel`과 Todo `createdAt`을 추가한다. 반 이름은 Alert가 가리키는 AI `class_ref`와 같은 강사 소유 반을 해석하며 찾을 수 없으면 nullable key로 반환한다.
- 이번 범위가 아닌 것: `feedbackGiven`, observing, 통계. 상담 일정·문의·리포트 승인 대기 Todo와 Reminder 실제 알림 발송도 구현하지 않는다.
- 코드 근거:
  - `src/main/java/com/checkon/dashboard/application/DashboardBriefingService.java`
  - `src/main/java/com/checkon/dashboard/presentation/DashboardController.java`
- 마지막 검증일: 2026-08-13

#### DASH-002 주간 캘린더의 브리핑 경보 집계

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 주간 범위: 요청은 `startedAt`, `endedAt`을 모두 받는다. `startedAt`은 월요일, `endedAt`은 같은 주의 일요일이어야 하며 두 날짜를 포함한 정확히 7일만 조회한다.
- 집계 정책: `eventCount`는 해당 강사의 `detection_runs.analysis_date`에 연결된 `engagement_alerts` 수다. `PENDING_REVIEW`, `APPROVED`, `REJECTED`를 모두 포함하며 Evidence 행 수와 무관하게 경보 한 건을 한 번 센다.
- 미래 정책: 미래 주 조회를 허용한다. 저장된 브리핑 경보가 없으면 미래 날짜도 0을 반환한다.
- 빈 날짜: 이벤트가 없는 날짜를 포함해 월요일부터 일요일까지 날짜 오름차순으로 항상 7개를 반환한다.
- 날짜 기준: 서비스 날짜 기준은 `Asia/Seoul`이다. 현재 집계 키는 이미 서비스 논리 날짜인 PostgreSQL `DATE`의 `analysis_date`이므로 DB 서버 시간대나 경보 생성 `Instant`를 `LocalDate`로 변환하지 않는다.
- 제외 범위: 활성 리마인드, 상담 일정, 오늘의 할 일, 문의, 리포트 승인 대기는 집계하지 않는다. 리마인드는 경보와의 중복 제거 정책이 없고 나머지는 현재 저장 모델이 없다.
- 테넌트·보안: 인증 주체의 `teacherProfileId`만 사용하고 읽기 트랜잭션 안에서 RLS 컨텍스트를 설정한다. 요청에서 받은 강사·테넌트 식별자는 신뢰하지 않는다.
- 코드 근거:
  - `src/main/java/com/checkon/dashboard/application/DashboardCalendarService.java`
  - `src/main/java/com/checkon/dashboard/presentation/DashboardController.java`
- 마지막 검증일: 2026-08-05

#### SEC-004 개발 단계 보호 API 테스트 인증

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- 정책: 프론트엔드 연동 병목을 줄이기 위해 개발 프로필에서 인증 헤더가 없는 보호 API 요청 전체에 테스트용 TEACHER principal을 자동 주입한다. 기본 및 운영 설정은 실제 JWT 인증 필수를 유지한다. 인가 검증은 별도 후속 범위다.
- 테넌트 경계: 테스트 요청은 서버 환경변수 `TEST_ACCOUNT_ID`와 `TEST_TEACHER_PROFILE_ID`로 지정한 실제 테스트 강사 Account·TeacherProfile을 사용한다. 이를 통해 감사 FK를 기록하는 쓰기 API도 정상 동작한다. 요청의 `teacherId` 또는 `tenantId`는 계속 신뢰하지 않는다. Bearer 헤더가 있으면 테스트 인증으로 대체하지 않고 실제 JWT 검증을 수행한다.
- 안전 범위: 회원가입·로그인·Refresh는 기존 공개 필터 체인을 그대로 사용하고 Logout에도 가짜 세션을 주입하지 않는다. 테스트 인증이 활성화됐는데 Account 또는 TeacherProfile ID가 없으면 애플리케이션 시작을 실패시킨다.
- 코드 근거:
  - `src/main/java/com/checkon/global/config/AccountSecurityConfiguration.java`
  - `src/main/java/com/checkon/global/config/DevelopmentTestAuthenticationFilter.java`
  - `src/main/resources/application-dev.yaml`
- 마지막 검증일: 2026-08-06

#### ENG-004 경보 후속 조치 Todo

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_VERIFIED`
- 종류·생성: 이번 범위는 `ALERT_FOLLOW_UP`만 구현하며, Evidence가 있고 `advisory=false`인 `PENDING_REVIEW` 경보 한 건마다 같은 업무 흐름에서 Todo 한 건을 이벤트 기반으로 생성한다. Detection 스케줄러는 Todo를 직접 생성하지 않는다.
- 날짜·이월: `dueDate`는 주입된 `Clock`과 `Asia/Seoul`로 계산한 경보 생성일이다. 원래 날짜를 수정하거나 이월 스케줄러를 두지 않고, `OPEN AND due_date <= 조회일` 조건으로 조회한다.
- 상태·완료: 상태는 `OPEN`, `DONE`이며 `done=true` 완료 요청은 멱등이다. 완료 취소와 재개는 지원하지 않는다. 연결 경보가 승인 또는 거절되면 같은 트랜잭션에서 미완료 Todo를 완료한다.
- 개인정보: Todo 문구에는 학생 실명, 학생 alias, 학습 기록 내용을 포함하지 않고 고정 문장을 사용한다.
- 테넌트·보안: 인증 주체의 `teacherProfileId`만 테넌트 경계로 사용하며 애플리케이션 조건과 PostgreSQL FORCE RLS를 함께 적용한다.
- API·조회: `GET /api/v1/dashboard/briefing`은 조회일 이전까지의 미완료 Todo를 `dueDate`, `createdAt`, `id` 순으로 반환하고 연결 신호의 `displayLabel`과 Todo `createdAt`을 함께 제공한다. `PATCH /api/v1/todos/{todoId}`는 `done=true`만 허용하며, 없음과 다른 테넌트 접근은 동일한 `TODO_NOT_FOUND` 404로 처리한다.
- 기존 데이터: V10 적용 시 기존 `PENDING_REVIEW` 경보만 경보 생성 시각의 `Asia/Seoul` 날짜로 backfill한다. 확정된 경보에는 OPEN Todo를 추가하지 않으며 `(alert_id, kind)` UNIQUE로 재삽입을 차단한다.
- 후속 범위: inquiry, report, 상담 일정 Todo는 구현하지 않는다.
- 코드 근거: `V10__create_alert_follow_up_todos.sql`, `AlertFollowUpTodo`, `TodoService`, `DashboardBriefingService`
- 검증: advisory 제외와 화면 복원 계약을 포함해 전체 Gradle 빌드 224건이 통과했다. 실제 운영 데이터가 채워진 V9→V10 승격 리허설과 Todo 전용 제한 역할 DML 검증은 아직 수행하지 않았다.
- 마지막 검증일: 2026-08-13

#### ENG-006 위험신호 상세 화면 복원 계약

- 결정 상태: `CONFIRMED`
- 구현 상태: `IMPLEMENTED`
- 근거 수준: `CONVERSATION_CONFIRMED`, `CODE_CONFIRMED`
- API: `GET /api/v1/engagement/alerts/{alertId}` 하나로 상세 화면을 복원할 수 있도록 `alertId`, `studentId`, nullable `studentName`, nullable `className`, `ruleId`, `signalType`, `displayLabel`, `brief`, `briefFallback`, `status`, `createdAt`, `evidence[]`를 반환한다.
- Evidence: 각 항목은 저장된 `sourceHint`, `recordId`, `summary`를 제공하며 원본 학습 기록이나 학생 개인정보를 새로 조합하지 않는다.
- 테넌트·보안: 인증 주체의 `teacherProfileId`와 PostgreSQL RLS를 함께 적용하고, 없음과 다른 테넌트 접근은 동일한 404로 처리한다.
- 마지막 검증일: 2026-08-13

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
| 2026-08-13 | 위험신호 학습 태그 4종의 nullable·화이트리스트·영역-과목 유도 계약을 LR-010으로 구현 | 입력 거절·null 보존·DB 및 Detection snapshot 전달 집중 테스트와 Backend 전체 240건 `clean build` 통과 |
| 2026-08-13 | 위험신호 v1을 R1·R3·R4·R6으로 한정하고 ACTIVE 학생 상태·관계 기준 재원 기간·등록 후 weekly activity·ONGOING open Alert 중복 억제·R1 진단 stats 보존 정책을 구현 | Backend 237건·Adapter 37건 테스트와 두 저장소 전체 Gradle `clean build` 통과 |
| 2026-08-13 | PR #43의 독립 Kafka Adapter 경계를 정본으로 유지하면서 위험신호 화면 계약, 과거 Alert context, 종료 학생 제외, advisory 소비 규칙을 통합 | 집중 단위·PostgreSQL 통합 테스트와 전체 Gradle build 224건 통과 |
| 2026-08-12 | 문제 출제 studio 요청을 target별 child Outbox 이벤트로 fan-out하고 child 결과 멱등 처리·AI ID 고정·부모 `PARTIAL_SUCCESS` 집계를 구현 | child DB·화면 통합 테스트와 임베디드 Kafka fan-out·기존 단일 요청 호환 테스트, 전체 211건 및 Gradle build 통과 |
| 2026-08-12 | 위험탐지 `DET-004`를 AI 기능 공통 Kafka 신뢰성 정본으로 확정하고, 문제 출제 fan-out·21분 관찰·문항 전량 이벤트·부분 성공을 기능별 예외로 분리해 PG-002·003·005 확정 | 사용자 승인과 AI 최종 회신을 정책 문서에 정적 반영. 코드·DB·테스트는 변경하지 않음 |
| 2026-08-12 | Kafka-HTTP adapter를 별도 서버로 확정하고 AI HTTP 호출·polling·items 정규화를 adapter 책임으로 배치. Step 1→2 정보 이동과 12/7 화면 예시 충돌 해소 | 사용자 확정사항을 전달 문서·PG-002~005에 정적 반영. 코드 테스트는 재실행하지 않음 |
| 2026-08-12 | 최신 AI HTTP 계약과 사용자 확정 통신 경계에 맞춰 PG-002~005 상태를 재분류하고 AI 전용 HTTP·별도 Kafka adapter 구조를 기록 | 최신 AI 전달 문서와 현재 코드·DB 정적 대조. 문서 변경만 수행해 코드 테스트는 재실행하지 않음 |
| 2026-08-12 | PG-004~005 프론트 출제 스튜디오 4단계 계약과 AI 계약 충돌 격리 정책 등록·구현 | Step 1~4 BDD 통합 테스트, 기존 문제 출제·OpenAPI·RLS 집중 회귀, 전체 204건 테스트와 Gradle build 통과 |
| 2026-08-12 | PG-001~003 문제 출제 v1 경계, Kafka Outbox·멱등 결과 소비·원문 결과 보존 정책 등록 및 구현 | 문제 출제 REST·Outbox·Kafka·DLT·RLS·OpenAPI BDD 집중 테스트와 최신 dev 기준 전체 200건 테스트, Gradle 빌드 통과 |
| 2026-08-09 | SCREEN-CLASS-001·002 구현 완료, SCREEN-PAGE-001 클래스 목록 범위 구현 및 팀 공유 문서 추가 | 클래스 관리·Flyway·RLS·OpenAPI 집중 테스트 39건 통과. `gradlew clean build` 전체 173건 통과 |
| 2026-08-09 | SCREEN-CLASS-001·002와 SCREEN-PAGE-001 승인 정책 등록, ROS-003 구현 상태 정정 및 중복 SEC-003을 SEC-004로 정정 | 사용자 승인 대화와 현재 정책·스키마를 정적 대조. 기능 구현 전 상태 기록 |
| 2026-08-06 | LR-003 구현 상태 정정 및 LR-005~LR-009 Import 프로파일링·매핑 확정·검증·결과·미확정 계약 정책 등록 | 정책 레지스트리, Gap 분석, 현재 백엔드 Import 구현 부재를 정적 대조. 코드·테스트 변경 없음 |
| 2026-08-12 | DET-004 실행 시각을 02:10으로 변경하고 DET-005 동의 기능 전 임시 AI 분석 정책 등록 | 사용자 확정 정책과 Detection 스냅샷·스케줄 코드 동기화. 집중 테스트와 전체 빌드 176건 통과 |
| 2026-08-12 | DET-001·004에 AI 계약 v0.2 `detection_evidence`(10주 과제·활동, 복귀 이력), hash 정규화, 3 MiB Kafka 메시지 한도를 등록 | AI 제공 고정 샘플·계약을 기준으로 구현·BDD 검증 예정 |
| 2026-08-05 | ENG-005 개입 기반 Reminder 자동 생성·취소 연동과 Alert 단위 대시보드 집계 정책 등록 및 구현 | Reminder, Engagement·Dashboard, Detection·Scheduler 집중 테스트 통과. 전체 빌드 결과는 최종 보고 참조 |
| 2026-08-05 | ENG-004 경보 후속 조치 Todo 정책 등록 및 구현 | Todo 단위 테스트, Engagement·Dashboard 22건, 전체 빌드 130건 통과. 운영 데이터 승격 리허설과 Todo 전용 제한 역할 DML은 미실행 |
| 2026-08-05 | DET-004 후속 확장 대상을 Redis에서 Kafka 비동기 실행으로 정정 | 정책 문구 정적 확인. Kafka 구현은 현재 범위에서 제외 |
| 2026-08-05 | DET-004 매일 02:00 강사별 Detection 자동 실행 정책 등록 및 구현 | `*Schedul*`, `*Detection*`, `*Dashboard*` 테스트 통과 |
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
