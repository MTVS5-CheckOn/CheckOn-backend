# CheckOn 기획서 정책 대조 보고서

- 대조 기준: `docs/POLICY_REGISTER.md`
- 대상 문서:
  - `docs/ideation_v4_3.md`
  - `docs/ideation_improvement plan.md`
- 대조일: 2026-08-04
- 범위: 현재 `CheckOn-backend` 저장소에서 확인 가능한 정책과 구현

## 1. 결론

두 기획서는 제품 방향과 AI 팀의 구현 설명에는 강하지만, 현재 Spring 백엔드에서 확정된 테넌트 경계, RLS 실행 조건, Roster 불변식, Learning Record 저장 정책은 거의 반영하지 않는다.

가장 먼저 고쳐야 할 부분은 다음 세 가지다.

1. `ideation_v4_3.md`의 “데이터베이스 전 테이블·전 조회에 테넌트 격리가 구현됐다”는 표현은 현재 백엔드 근거보다 넓다. `student_profiles`의 소유권과 RLS는 아직 미확정이다.
2. 두 문서는 스마트 Import의 시그니처 캐시, 멱등성, 수동 폴백 등을 구현 완료 또는 계약 확정으로 표현한다. 현재 백엔드에는 Import 구현이 없고, 매핑 재사용·원본 파일 접근·오류 및 부분 성공·fingerprint 정책이 미확정이다.
3. 위험신호 규칙 6종과 브리핑·게이트 구현은 현재 Spring 백엔드가 아니라 별도 AI 서비스의 주장으로 보인다. 문서에서 구현 주체와 검증 저장소를 명시해야 한다. 현재 백엔드에서 확인되는 것은 56일 스냅샷 준비, 외부 AI 호출, 응답 검증·저장, 요청 시도 이력이다.

이번 대조에서는 AI 저장소를 검사하지 않았다. 따라서 AI 구현 완료 주장을 부정하는 것이 아니라 `CheckOn-backend에서 검증되지 않음`으로 분류한다.

## 2. 판정 기준

| 판정 | 의미 |
| --- | --- |
| `일치` | 정책 레지스트리와 같은 내용을 충분한 범위로 표현함 |
| `반드시 수정` | 확정 정책과 충돌하거나 현재 근거보다 넓은 구현 완료를 주장함 |
| `설명 보완` | 방향은 충돌하지 않지만 중요한 경계·불변식·책임이 빠져 있음 |
| `결정 필요` | 기획서가 기능을 요구하지만 저장·소유권·상태 전이 정책이 아직 열려 있음 |

## 3. 정책별 대조

| 정책 ID | `ideation_v4_3.md` | `ideation_improvement plan.md` | 판정 | 필요한 조치 |
| --- | --- | --- | --- | --- |
| SEC-001 | 9.5에서 강사별 격리 원칙을 제시하지만 인증 principal에서 `teacherProfileId`를 파생하는 경계는 없음 | 개인정보 무접촉 구조는 설명하지만 백엔드 테넌트 식별 경계는 없음 | `설명 보완` | 요청 값이 아니라 인증된 `teacherProfileId`만 테넌트로 사용한다고 기술명세 또는 보안 절에 명시 |
| SEC-002 | 9.5에서 격리를 선언하지만 transaction-local RLS context와 제한된 runtime DB 역할은 없음 | RLS 실행 및 검증 조건 없음 | `설명 보완` | application transaction 내부 `set_config`, runtime/Flyway 역할 분리, 실제 제한 역할 DML 검증 조건 추가 |
| SEC-003 | “데이터베이스 전 테이블·전 조회”에 테넌트 키가 구현됐다고 단정 | 직접 언급 없음 | `반드시 수정` | 현재 RLS 적용 범위를 정확히 쓰고 `student_profiles` 소유권·RLS는 `OPEN`으로 분리 |
| ROS-001 | 학생당 활성 강사 최대 1명과 종료 이력 보존 규칙 없음 | 관련 설명 없음 | `설명 보완` | Roster 또는 데이터 모델 명세에 partial unique invariant와 `ENDED + ended_at` 이력 보존 추가 |
| ROS-002 | 학생당 활성 반 최대 1개와 반 소유 강사 일치 규칙 없음 | 관련 설명 없음 | `설명 보완` | 활성 반 유일성 및 강사 관계 일치 불변식 추가 |
| ROS-003 | 퇴원·휴원 처리를 Must로 두고, 퇴원 시 파기와 이직 시 동결·파기를 제안 | 관련 설명 없음 | `결정 필요` | `PAUSED`, 종료 사유, 학생 계정·보호자·초대, 데이터 파기와 이력 보존의 경계를 먼저 확정 |
| LR-001 | `external_record_ref` 정책 없음 | 관련 설명 없음 | `설명 보완` | nullable·중복 허용이며 UNIQUE·멱등 키·update 의미가 아님을 Import/데이터 계약에 명시 |
| LR-002 | 트랙 B 자체 기록은 설명하지만 단건 등록의 서버 소유 `MANUAL` 값과 활성 관계 검증은 없음 | F1에서 강사 입력을 언급하지만 저장 경계는 없음 | `설명 보완` | 단건 등록과 Import가 같은 application 검증 경계를 재사용한다고 기술명세에 추가 |
| LR-003 | AI 매핑 제안 → 강사 확정 → 변환이라는 흐름은 일치. 다만 시그니처 캐시·조사 에이전트·수동 폴백을 구현 수준으로 확정 | 스마트 Import와 시그니처 캐시를 완료로 표시 | `반드시 수정` | 핵심 책임 흐름은 유지하되 백엔드 Import는 `NOT_IMPLEMENTED`, 세부 정책은 `OPEN`, AI 구현은 저장소별 검증 상태로 분리 |
| LR-004 | 문항 재생성·유사 문항·태깅을 설명하지만 안정적 문항 ID와 동일성 기준은 없음 | 학생별 다른 지문이 별도 절차를 요구한다고만 설명 | `결정 필요` | 동일·유사·변형 문항 기준, 문항 ID 소유 주체, 외부 식별자 계약을 확정하기 전 중복·반복 추정 정책을 만들지 않도록 표시 |
| DET-001 | 직전 8주 기준선은 백엔드 56일 스냅샷과 일치. 2주 판정 창·규칙 6종 구현 주체는 불명확 | 동일하게 2주/8주와 규칙 6종을 제시 | `설명 보완` | 백엔드는 56일 데이터를 준비하고 AI 서비스가 판정한다는 시스템 경계를 명시. AI 규칙 구현 근거는 별도 저장소로 연결 |
| DET-002 | 외부 AI 호출의 DB 트랜잭션 경계와 attempt 이력 없음 | 멱등·영속·부분 실패만 포괄적으로 언급 | `설명 보완` | 외부 HTTP는 DB 트랜잭션 밖, 준비·시도·실패·저장은 짧은 트랜잭션, 재시도는 새 attempt로 보존한다고 추가 |
| DET-003 | 모든 경보에 근거를 요구해 방향은 일치하지만 응답 전체 검증과 원자적 저장은 없음 | 근거 추적은 있으나 백엔드 저장 원자성은 없음 | `설명 보완` | 학생·반·evidence 소유권 검증 후 signal/evidence 전체를 원자적으로 저장한다고 추가 |

## 4. 문서별 상세 발견

### 4.1 `docs/ideation_v4_3.md`

#### 반드시 수정

1. **테넌트 격리 범위 과장**
   - 위치: 9.5, 672행
   - 현재 표현: 데이터베이스 전 테이블·전 조회의 필수 키로 구현
   - 백엔드 확인 결과: V7은 Roster 관계·반·Detection 테이블에 RLS를 적용하고 V8은 Learning Record와 AI alias에 적용한다. `student_profiles` 자체의 RLS 정책은 없다.
   - 권장 표현: “강사 소유 데이터 테이블에 RLS를 단계적으로 적용했으며, 학생 프로필 자체의 소유권·RLS 범위는 별도 정책 확정이 필요하다.”

2. **스마트 Import의 구현 완료 범위**
   - 위치: 5.7 350행, 7.5 549행, 8.2 574행
   - 현재 표현: 시그니처 캐시, 조사 에이전트, 수동 폴백을 포함한 메커니즘이 구현 수준으로 확정
   - 백엔드 확인 결과: 단건 Learning Record application 경계만 구현됐고 Import API·상태·저장 모델은 없다.
   - 권장 표현: AI 서비스에서 검증된 부분, 팀 간 합의된 책임 흐름, Spring 백엔드 미구현 부분을 세 문장으로 분리한다.

3. **통합 구현 완료 주체 불명확**
   - 위치: 7.1 481행, 7.5 542~552행, 11장 진행 현황
   - 현재 표현: 감지기·브리핑·마스킹·감사 원장 등이 “실서버 경로”와 자동 테스트로 구현 완료
   - 백엔드 확인 결과: Spring 백엔드는 학습 기록 스냅샷과 외부 AI 연동·저장을 구현했다. 규칙 6종 판정, LLM 문장화, 마스킹 코퍼스, 860건 테스트는 이 저장소에서 확인되지 않는다.
   - 권장 표현: 표에 `구현 주체`, `저장소`, `검증일`, `통합 검증 여부` 열을 추가한다.

#### 설명 보완

- 6장 시스템 구성에 `인증 principal → teacherProfileId → transaction-local RLS → teacher-scoped repository` 보안 흐름을 추가한다.
- 8.2 데이터 수집에 단건 수동 입력과 Import가 최종적으로 동일한 백엔드 검증·저장 경계를 사용한다고 추가한다.
- 9.5 신뢰성 공학에서 AI 작업의 부분 성공 보존과 Spring 백엔드의 Detection 응답 원자적 저장을 구분한다. 전자는 완료 산출물을 보존할 수 있지만, 후자는 한 응답의 signal/evidence 일부 저장을 허용하지 않는다.

#### 결정 필요

- 6.1의 퇴원·휴원 Must와 9.3의 파기·동결 원칙을 Roster 상태 전이 및 법적 보존 정책으로 구체화해야 한다.
- 반복·유사·재생성 문항을 연결할 안정적 식별자와 동일성 기준이 없다.
- Import의 매핑 재사용 범위, AI 서비스 원본 파일 접근, 오류·부분 성공, fingerprint 정책이 열려 있다.

### 4.2 `docs/ideation_improvement plan.md`

#### 반드시 수정

1. **완료 목록의 시스템 경계 표시**
   - 위치: 134~141행
   - 스마트 Import, 감지 엔진, 문장화, AI 영속 계층을 모두 완료로 표기한다.
   - 각 항목을 `AI 서비스 완료`, `Spring 백엔드 완료`, `통합 완료`, `미구현`으로 나눠야 한다. 현재 백엔드와 AI 서비스 간 End-to-End 통합은 문서 스스로 150행에서 예정이라고 밝히므로, 134~141행의 “완료”와 혼동된다.

2. **Import 완료 표현**
   - 위치: 137행
   - 현재 백엔드에는 Import가 없으므로 “AI 매핑 제안 골격/실험 완료, 백엔드 확정·변환·행 검증·저장 미구현”으로 범위를 좁혀야 한다.

#### 설명 보완

- 79~85행 아키텍처 원칙에 강사 테넌트 경계와 RLS를 추가한다.
- 91행 F1에 `external_record_ref`의 nullable·중복 허용 정책을 연결한다.
- 92~94행 F2~F5에 Spring 백엔드의 56일 snapshot, 외부 AI 호출, 응답 검증·저장 책임을 추가한다.
- 85행 “부분 실패 시 산출물 보존”은 Detection 응답의 부분 저장 허용으로 오해되지 않도록 적용 단위를 명시한다.

## 5. 권장 수정 순서

1. 두 문서의 모든 “구현 완료” 문장에 구현 주체와 검증 범위를 표시한다.
2. `ideation_v4_3.md` 672행의 전 테이블 RLS 표현을 현재 적용 범위로 수정한다.
3. Import를 `확정된 책임 흐름`, `AI 측 구현`, `백엔드 미구현`, `OPEN 정책`으로 분리한다.
4. 기술명세서에 SEC-001~003, ROS-001~003, LR-001~004, DET-001~003을 연결한다.
5. `결정 필요` 항목은 사용자 합의 후 정책 레지스트리를 먼저 갱신하고 기획서를 수정한다.

## 6. 검증 근거와 한계

### 현재 백엔드에서 확인한 근거

- `src/main/resources/db/migration/V6__create_roster_model.sql`
- `src/main/resources/db/migration/V7__enforce_teacher_tenant_row_level_security.sql`
- `src/main/resources/db/migration/V8__create_learning_records_and_ai_student_aliases.sql`
- `src/main/java/com/checkon/global/persistence/TeacherTenantDatabaseContext.java`
- `src/main/java/com/checkon/learning/application/RegisterLearningRecordService.java`
- `src/main/java/com/checkon/detection/application/OperationalDetectionRunService.java`
- `src/main/java/com/checkon/detection/application/DetectionAttemptCoordinator.java`
- `src/main/java/com/checkon/detection/application/DetectionResponseStorageService.java`

### 이번 대조에서 확인하지 않은 것

- 별도 AI 서비스 저장소의 현재 코드와 테스트
- 프론트엔드 구현 상태
- 기획서가 참조하는 기술명세서 v1, 와이어프레임, 외부 계약 문서
- 실제 운영 서비스의 End-to-End 동작

따라서 외부 구성요소의 구현 주장은 `거짓`이 아니라 `이 백엔드 저장소만으로 검증할 수 없음`으로 해석해야 한다.
