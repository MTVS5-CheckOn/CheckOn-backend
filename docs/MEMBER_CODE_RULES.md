# member 백엔드 코드 규칙 (Java · Spring 적용판)

> 작성 2026-08-24 (KST) · 실측 기준 `CheckOn-backend` **`origin/dev` `daf3468`** (2026-08-25 재측정)
> 리뷰어(사람·에이전트)는 이 문서를 기준으로 PR을 **반려할 수 있다.**

---

## §0. 🔴 이 문서의 지위 — 무엇을 적고 무엇을 적지 않는가

`CheckOn-AI` `docs/03_coding_rules.md`(108줄)가 **원칙의 정본**이다. 하드코딩 금지 · 라이브러리 우선 · 모듈화 · 인터페이스 우선 · 에러 처리 · 이름 · 테스트 · PR 규칙이 거기 있다.

🔴 **그 목록을 여기 복제하지 않는다.** 같은 규칙이 두 곳에 있으면 갈린다(`docs/02_ownership.md` §4-1 원칙).

그런데 그 문서는 **Python/FastAPI 전용**이다 — `mypy`, Pydantic, `uv add`, `llm/gateway`, `contracts/taxonomy.py`, `datetime.now()` 금지. Java/Spring 저장소에 그대로 적용할 수 없다.

| 무엇 | 정본 |
|---|---|
| **원칙** (왜 하드코딩이 안 되는가, 왜 의존 방향이 한쪽인가) | `CheckOn-AI` `docs/03_coding_rules.md` |
| **Java·Spring 적용** (record 인가 class 인가, `@Transactional` 을 어디 붙이나, RLS 컨텍스트를 언제 세팅하나) | **이 문서** |
| 분기·예외 → HTTP 매핑 | `01_endpoint_branch_matrix.md` |
| 소유 경계·무접촉 | `00_member_backend_design.md` §3 · `02_regression_guard.md` |

이 문서는 **member 패키지에만 적용된다.** 기존 패키지에 소급하지 않는다(무접촉).

---

## §1. 실측한 현재 관행 — 규칙은 여기서 나왔다

`CheckOn-backend` `origin/dev` `daf3468` 를 직접 재서 얻은 값이다. **취향이 아니라 이 저장소가 이미 하고 있는 것**이다.

| 항목 | 실측 | 이 문서의 규칙 |
|---|---|---|
| 들여쓰기 | 샘플 40파일 **전부 탭** | 탭 |
| 줄 길이 | 중앙값 31자 · p95 89자 · 최대 771자(`EngagementExceptionHandler.java:5`) | **100자 상한** |
| `record` 비율 | 278개 중 **107개(38%)** | 불변 데이터는 record |
| Lombok | build.gradle 에 선언은 있으나 `src/main` **사용 0건** | **쓰지 않는다** |
| `@Transactional` 위치 | application 29 · infrastructure 4 · **presentation 0 · domain 0** | 트랜잭션 경계 = application |
| 상태 문자열 하드코딩 | `"ACTIVE"`/`"PUBLISHED"` 등 **2건뿐** | enum |
| 정적분석 플러그인 | checkstyle·spotless·pmd·spotbugs·jacoco·archunit **0개** | 🔴 §11 참조 — build.gradle 무접촉으로 강제한다 |

🔴 **`build.gradle` 은 무접촉이다.** checkstyle·spotless 를 넣고 싶어질 것이다. 넣지 마라 — 공유 파일이고 승우님 빌드가 바뀐다. 대신 §11 의 테스트로 강제한다.

---

## §2. 하드코딩 금지 — 값은 코드 밖에

| 값 | 어디에 | 코드에 쓰면 |
|---|---|---|
| 월별 최소 표본 수(10) | `MemberMetricsProperties` (`@ConfigurationProperties`) | 🔴 반려 — 정책이 바뀌면 과거 집계 재현이 깨진다 |
| progress delta 상한(600초), 저장 주기 | `MemberAttemptProperties` | 🔴 반려 |
| 공개 ID 발급 재시도 상한(5) | properties | 🔴 반려 |
| 초대 만료(7일)·`max_claims` | DB `member_invitation_codes` 컬럼 | 🔴 반려 — 코드에 고정하면 초대마다 다르게 못 준다 |
| rate limit 임계 | properties | 반려 |
| 상태값 (`IN_PROGRESS`, `PENDING_PARENT_LINK`, `PUBLISHED`) | **enum** | 🔴 문자열 리터럴이 2곳 이상 나오면 반려 |
| 오류 코드 | `MemberErrorCode` enum | 🔴 문자열 리터럴 반려 |
| area/type 태그 | enum (`AreaTag`·`TypeTag`, 소문자 정본) | 반려 |
| 페이지 `limit` 기본 20 / 최대 50 | 상수 **한 곳** (`CursorPage`) | 여러 컨트롤러에 흩어지면 반려 |
| DB URL·시크릿·스토리지 버킷 | 환경변수 → `@ConfigurationProperties` | 🔴 `System.getenv` 직접 호출 반려 |

**허용되는 리터럴**: `0`, `1`, `-1`, 빈 문자열, 테스트 픽스처, 정규식 패턴 상수(`static final`).

🔴 **매직 넘버 판정 기준**: 숫자가 *무엇을 의미하는지 이름 없이는 알 수 없으면* 매직 넘버다. `if (attempts >= 5)` 는 반려, `if (attempts >= properties.maxIssueRetries())` 는 통과.

---

## §3. 스파게티 방지 — 의존 방향을 한쪽으로

### 3-1. 계층 방향

```
presentation → application → domain ← infrastructure
                    ↓
              integration (다른 패키지로 나가는 유일한 문)
```

🔴 반려 대상:

- `presentation` 이 Repository·Entity 를 직접 참조
- `domain` 이 Spring 애노테이션(`@Service`, `@Transactional`, `@Component`)을 가짐 — JPA 애노테이션은 허용
- `application` 이 `HttpServletRequest`·`ResponseEntity`·`Jwt` 를 앎
- `infrastructure` 가 `application` 을 참조 (역방향)

### 3-2. 🔴 패키지 경계 — 이게 이 프로젝트의 핵심

```
member/**  →  member/integration/**  →  (account · roster · learning · problem · counsel)
```

- member 의 **어떤 클래스도** 다른 패키지의 Repository·Entity 를 직접 import 하지 않는다. **`member/integration/**` 만 예외**다.
- integration 어댑터는 상대 패키지 타입을 **member 전용 immutable record 로 변환해서** 돌려준다. 남의 Entity 를 member domain 으로 전파하면 반려.
- 🔴 **`TeacherTenantDatabaseContext` import 금지** — member 전 범위. (§11 게이트가 강제)

### 3-3. 크기 상한

| 대상 | 상한 | 근거 |
|---|---|---|
| 클래스 | **400줄** | 기존 최대가 437줄(`DetectionResponseStorageService`). 그보다 늘리지 않는다 |
| 메서드 | **50줄** | |
| 메서드 파라미터 | **5개** | 넘으면 파라미터 객체(record) |
| 중첩 깊이 | **3** | `if` 안의 `for` 안의 `if` 까지 |
| 한 클래스의 public 메서드 | **10개** | 넘으면 책임이 둘 이상 |

🔴 상한을 넘겨야 한다면 **넘기지 말고 쪼갠다.** 예외를 두려면 클래스 상단에 사유를 적고 PR 본문에도 적는다. "일단 넘기고 나중에" 는 없다.

### 3-4. 순환 참조

member 내부 하위 패키지끼리 순환 참조 금지. `student` ↔ `parent` 가 서로 부르면 공통 부분을 `common` 으로 올린다.

---

## §4. 타입·데이터

- **불변 데이터는 `record`.** DTO·명령·조회 결과·값 객체 전부. 가변 필드가 필요하면 그때만 class.
- **Lombok 을 쓰지 않는다.** 실측 `src/main` 사용 0건. `@Data`·`@Builder` 로 만든 가변 객체가 경계를 넘어다니는 게 이 규칙의 이유다.
- 상태값은 **enum**. 자유 문자열 상태 금지.
- 🔴 **`Map<String, Object>` 로 돌려막지 마라.** 경계를 넘는 데이터는 전부 record. `Map` 이 3단계 이상 전파되면 모델 누락 신호다.
- `Optional` 은 **반환 타입에만.** 필드·파라미터에 쓰면 반려.
- 🔴 **null 을 값으로 쓸 때는 "모른다"는 뜻이다.** 0 이나 빈 문자열로 채우지 마라(`01_endpoint_branch_matrix.md` §0-1 ⑧).
- 시각은 `Instant`. 🔴 **`Instant.now()` 직접 호출 금지 — `Clock` 주입.** 테스트에서 시간을 고정해야 한다(월 경계·만료·타이머).
- ID 는 `UUID`. `String` 으로 다루면 반려.
- 금액·비율은 `double` 금지. 비율은 `BigDecimal` 또는 `count/count` 로 마지막에 한 번만 나눈다.

---

## §5. 예외 처리

- 🔴 **`catch (Exception e) {}` 절대 금지.** 삼키려면 왜 안전한지 주석 + 로그.
- 도메인 예외는 `MemberException(MemberErrorCode, message)` 하나로 통일. 오류별 예외 클래스를 남발하지 않는다.
- 🔴 **흐름 제어에 예외를 쓰지 마라.** "데이터 없음"은 예외가 아니라 `status` 반환값이다.
- 상대 패키지·라이브러리 예외를 member 밖으로 전파하지 않는다. `integration/**` 에서 `MemberException` 으로 변환한다.
- 🔴 **DB 제약 위반(`DataIntegrityViolationException`)은 constraint 이름으로만 분기한다.** 메시지 문자열 파싱 반려 — DB 버전이 바뀌면 조용히 깨진다.

```java
// 반려
if (e.getMessage().contains("uq_parent_student")) { ... }

// 통과
private static final String UQ_ACTIVE_STUDENT = "uq_parent_student_relationships_active_student";
if (constraintNameOf(e).equals(UQ_ACTIVE_STUDENT)) { throw new MemberException(CHILD_ALREADY_LINKED, ...); }
```

- 🔴 **500 응답 `message` 에 예외 내용·스택·쿼리·개인정보를 넣지 않는다.** 로그에만 남긴다.

---

## §6. 트랜잭션·DB — Spring 특유

- 🔴 **`@Transactional` 은 application 계층에만.** 실측 관행(application 29 / presentation 0)과 같다. presentation·domain 에 붙으면 반려.
- 조회는 `@Transactional(readOnly = true)`.
- 🔴 **RLS 컨텍스트는 `@Transactional` 진입 직후 첫 작업이다.** `MemberDatabaseContext.set(...)` 을 빼먹으면 조회가 0건이 되거나 INSERT 가 정책 위반으로 터진다. 트랜잭션 밖에서 세팅하면 커넥션 풀 때문에 다음 요청에 샌다.
- 🔴 **`set_config(..., true)` — 세 번째 인자 반드시 `true`.** 트랜잭션 로컬이어야 커밋/롤백 후 남지 않는다.
- 🔴 **member 트랜잭션에서 `checkon.current_teacher_id` 를 설정하지 않는다.** 이 설계의 최상위 불변식이다.
- 잠금은 `SELECT ... FOR UPDATE`. 낙관락은 `version` 컬럼. 둘을 섞을 때는 어느 쪽이 최종 보장인지 주석에 적는다.
- 🔴 **최종 보장은 DB 제약이다.** 사전 조회로 중복을 막았다고 믿지 마라 — 동시 요청에서 뚫린다. 사전 조회는 사용자 안내용이고, unique index 가 진짜 방어다.
- N+1 금지. 목록 조회는 한 방 쿼리 또는 명시적 배치 로딩. 🔴 통합테스트에서 쿼리 수를 단언한다.
- 마이그레이션은 forward-only. 롤백 SQL 을 쓰지 않고 보정 마이그레이션을 새로 만든다.

---

## §7. 이름

- 축약어 금지: `ctx`, `mgr`, `tmp`, `req`, `res`, `svc`, `repo`. 예외: `id`, `db`, `url`, `dto`.
- 클래스: `Member` 접두를 공통 컴포넌트에만. 기능 클래스는 도메인 이름으로(`AttemptSubmissionService`, `ChildRegistrationService`).
- 🔴 **동어반복 금지.** `StudentSignUpService` 위에 `// 학생 가입 서비스` 주석을 달지 마라. 정보가 0이다.
- boolean 은 `is`/`has`/`can` 접두.
- 테스트 메서드는 **한글 문장** 또는 `조건_기대결과` 형식. 무엇을 단언하는지 이름만 보고 알 수 있어야 한다.
- 🔴 미확정 안건에 걸린 코드는 `// TODO(MB-05):` 형식. **안건 번호 없는 TODO 금지** — 추적이 안 된다.

---

## §8. 주석

주석은 **"왜"** 를 적는다. "무엇"은 코드가 말한다.

```java
// 반려 — 코드를 한국어로 반복
// student_id 로 조회한다
var student = repository.findById(studentId);

// 통과 — 코드가 말하지 않는 것
// 사전 조회는 안내용이다. 동시 등록의 최종 보장은
// uq_parent_student_relationships_active_student 가 한다.
var preview = repository.findRegistrable(publicId);
```

🔴 **상한으로 자를 때는 무엇을 왜 잘랐는지 반드시 적는다.** `items.subList(0, 3)` 만 있고 어느 3건인지 아무도 모르는 코드를 만들지 마라.

---

## §9. 테스트

- 🔴 **테스트 없는 로직 PR 금지.** 최소: 정상 1 + 경계 1 + 실패 1.
- 🔴 `01_endpoint_branch_matrix.md` 의 **표 행 하나 = 테스트 하나.** 행 수와 테스트 수가 같아야 한다.
- RLS·동시성 테스트는 **제한 DB 역할**로. superuser 로 돌면 통과해도 의미가 없다.
- 동시성 테스트는 실제 두 스레드(`CountDownLatch`). `@Transactional` 을 붙이면 같은 트랜잭션이라 경쟁이 재현되지 않는다.
- 🔴 **실 LLM 호출 0회.** AI 는 fake/고정 픽스처.
- 고의 파괴로 검사가 진짜 잡는지 확인한다 — ① 파괴 ② **적용됐는지 확인** ③ red ④ 원복.
- 🔴 **red 판정은 출력이 아니라 종료 코드로.** Gradle 은 `--rerun-tasks` 를 붙인다(`UP-TO-DATE` 로 건너뛰면 red 를 놓친다). `--tests` 필터가 0건 매치면 실패하므로 실행된 테스트 수도 같이 본다.

---

## §10. PR·커밋

- PR 은 **한 가지 일만.** "기능 + 리팩토링 + 오타" 금지 — 3개로 쪼갠다.
- 목표 크기 **≤ 400줄**(생성 파일 제외).
- 🔴 기존 파일 변경이 생기면 **같은 PR 에 숨기지 않는다.** 별도 PR 로 분리하고 PR 본문 첫 줄에 `⚠ 승인 필요: [파일명]`.
- PR 본문 「확인 방법」에 **실행 OS · 로컬 pass/skip · 원격 CI 결과와의 차이**를 적는다.
- 🔴 push 전 `scripts/member-no-regression.sh` PASS (`02_regression_guard.md`).

---

## §11. 🔴 자동 게이트 — `build.gradle` 을 건드리지 않고 강제하는 법

정적분석 플러그인이 0개이고 `build.gradle` 은 무접촉이다. 그래서 규칙을 **평범한 JUnit 테스트**로 강제한다. 의존성 추가 0건, 승우님 빌드 영향 0건.

`src/test/java/com/checkon/member/rules/MemberCodeRuleTest.java` 하나에 몰아넣는다.

```java
class MemberCodeRuleTest {

    private static final Path MEMBER = Path.of("src/main/java/com/checkon/member");

    private static List<Path> memberSources() throws IOException {
        try (var walk = Files.walk(MEMBER)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    // G1. 🔴 teacher 컨텍스트 오염 금지 — 이 설계의 최상위 불변식
    @Test void doesNotTouchTeacherTenantContext() throws IOException {
        assertThat(grepFiles("TeacherTenantDatabaseContext|checkon\\.current_teacher_id")).isEmpty();
    }

    // G2. 패키지 경계 — integration 밖에서 남의 패키지 import 금지
    @Test void onlyIntegrationCrossesPackageBoundary() throws IOException {
        var offenders = memberSources().stream()
            .filter(p -> !p.toString().contains("/integration/"))
            .filter(p -> readString(p).matches("(?s).*import com\\.checkon\\.(account|roster|learning|problem|counsel|engagement|dashboard)\\..*"))
            .toList();
        assertThat(offenders).isEmpty();   // 실패 시 파일 목록이 그대로 나온다
    }

    // G3. presentation 이 Repository·Entity 직접 참조 금지
    @Test void presentationDoesNotTouchPersistence() { ... }

    // G4. domain 에 Spring 애노테이션 금지 (JPA 는 허용)
    @Test void domainHasNoSpringAnnotations() { ... }

    // G5. @Transactional 은 application 에만
    @Test void transactionalOnlyInApplication() throws IOException {
        var offenders = memberSources().stream()
            .filter(p -> readString(p).contains("@Transactional"))
            .filter(p -> !p.toString().contains("/application/"))
            .toList();
        assertThat(offenders).isEmpty();
    }

    // G6. Lombok 금지
    @Test void doesNotUseLombok() throws IOException {
        assertThat(grepFiles("import lombok")).isEmpty();
    }

    // G7. 크기 상한
    @Test void classesStayUnder400Lines() throws IOException {
        var offenders = memberSources().stream()
            .filter(p -> countLines(p) > 400)
            .map(p -> p + " (" + countLines(p) + "줄)").toList();
        assertThat(offenders).isEmpty();
    }
    @Test void linesStayUnder100Chars() { ... }

    // G8. 🔴 오류 코드 문자열 리터럴 금지 — enum 만. 🔴 21개 전량이다(2026-08-25 확대).
    //     이전 판은 6개만 막았다. 나머지 15개는 리터럴로 새어도 안 잡혔다.
    private static final String ERROR_CODES = String.join("|",
        "INVALID_REQUEST", "AUTHENTICATION_REQUIRED", "INVALID_CREDENTIALS", "ACCOUNT_NOT_ACTIVE",
        "ROLE_FORBIDDEN", "STUDENT_ACTIVATION_REQUIRED", "RESOURCE_NOT_FOUND", "EMAIL_ALREADY_EXISTS",
        "IDEMPOTENCY_CONFLICT", "REVISION_CONFLICT", "CHILD_ALREADY_LINKED", "ATTEMPT_ALREADY_SUBMITTED",
        "INVITE_ALREADY_CLAIMED", "INVITE_EXPIRED", "SUBMISSION_INCOMPLETE", "RELATIONSHIP_REQUIRED",
        "WORKSHEET_NOT_GRADABLE", "RATE_LIMITED", "INTERNAL", "DEPENDENCY_UNAVAILABLE", "DEPENDENCY_TIMEOUT");

    @Test void errorCodesComeFromEnum() throws IOException {
        // 🔴 따옴표를 포함해서 찾는다. 따옴표 없이 INTERNAL 을 찾으면
        //    HttpStatus.INTERNAL_SERVER_ERROR 가 걸려서 게이트가 못 쓰게 된다.
        var pattern = Pattern.compile("\"(" + ERROR_CODES + ")\"");
        var offenders = memberSources().stream()
            // 🔴 enum 선언 자체는 제외한다. 여기 말고는 리터럴이 있을 곳이 없다.
            .filter(p -> !p.getFileName().toString().equals("MemberErrorCode.java"))
            .filter(p -> pattern.matcher(readString(p)).find())
            .toList();
        assertThat(offenders).isEmpty();
    }

    // G14. 🔴 코드의 TODO(MB-xx) 번호가 실제로 등록된 안건인가
    //      G9 는 TODO 의 **형식**만 본다 — 번호가 붙어 있으면 통과하고, 그 번호가
    //      실재하는지는 안 본다. PR1 에서 TODO(MB-29) 가 안건 없이 들어갔다.
    @Test void todoIssueNumbersAreRegistered() throws IOException {
        var registered = Pattern.compile("MB-(\\d+)")
            .matcher(Files.readString(Path.of("docs/MEMBER_OPEN_ITEMS.md")))
            .results().map(r -> r.group()).collect(Collectors.toSet());
        var referenced = memberSources().stream()
            .flatMap(p -> Pattern.compile("TODO\\((MB-\\d+)\\)").matcher(readString(p))
                .results().map(r -> r.group(1)))
            .collect(Collectors.toCollection(TreeSet::new));
        // 🔴 PR 번호 TODO(PR3) 는 대상이 아니다 — 안건이 아니라 일정이다
        assertThat(referenced).allSatisfy(n ->
            assertThat(registered).as("TODO(%s) 가 MEMBER_OPEN_ITEMS.md 에 없다", n).contains(n));
    }

    // G15. 🔴 RLS 테이블을 읽는 application 서비스가 자기 트랜잭션에서 컨텍스트를 여는가
    //      set_config(..., true) 는 트랜잭션 로컬이다(설계 §6-4-4). 리졸버 → 인터셉터 →
    //      서비스가 각각 다른 트랜잭션이라 "앞에서 열었으니 됐다"가 성립하지 않는다.
    //      🔴 빠뜨리면 예외가 아니라 **0행**이라 조용하다. 그래서 규칙이 아니라 게이트다.
    @Test void memberServicesOpenTheirOwnRlsContext() throws IOException {
        var offenders = memberSources().stream()
            .filter(p -> p.toString().contains("/application/"))
            .filter(p -> p.getFileName().toString().endsWith("Service.java"))
            .filter(p -> readString(p).contains("Repository"))
            .filter(p -> !CONTEXT_OPENERS.matcher(readString(p)).find())
            .filter(p -> !RLS_WAIVER.matcher(readString(p)).find())
            .toList();
        assertThat(offenders).isEmpty();
    }

    // G15-b. 🔴 면제가 열거한 테이블이 정말 RLS 밖인가
    //      면제를 "파일 이름 목록"으로 두면 아무나 이름을 넣어 게이트를 무력화한다.
    //      그래서 면제는 **읽는 테이블을 열거**하게 하고 — G15-EXEMPT(t1, t2) —
    //      그 주장을 마이그레이션의 ENABLE ROW LEVEL SECURITY 로 반증한다.
    //      거짓 면제는 red 다.
    @Test void rlsWaiversNameOnlyUnprotectedTables() throws IOException { ... }

    // G13. 🔴 enum 이 docs/MEMBER_ERROR_CODES.md 와 같은 집합인가
    //      이 드리프트로 이미 두 번 당했다 — 설계 18 / 계약 21 / PR1 지시서 17.
    //      사람이 표를 고치고 enum 을 안 고치는 걸(반대도) 여기서 막는다.
    @Test void errorCodeEnumMatchesDocument() throws IOException {
        var documented = Pattern.compile("`([A-Z][A-Z_]{3,})`")
            .matcher(Files.readString(Path.of("docs/MEMBER_ERROR_CODES.md")))
            .results().map(r -> r.group(1)).collect(Collectors.toCollection(TreeSet::new));
        var declared = Pattern.compile("^\\s*([A-Z][A-Z_]{3,})\\(", Pattern.MULTILINE)
            .matcher(Files.readString(MEMBER.resolve("common/error/MemberErrorCode.java")))
            .results().map(r -> r.group(1)).collect(Collectors.toCollection(TreeSet::new));
        assertThat(declared).isEqualTo(documented);   // 실패 시 양쪽 차집합이 그대로 보인다
    }

    // G9. 안건 번호 없는 TODO 금지
    @Test void todosCarryOpenItemId() throws IOException {
        assertThat(grepFiles("TODO(?!\\(MB-\\d+\\)|\\(PR\\d+\\))")).isEmpty();
    }

    // G10. Instant.now() 직접 호출 금지 — Clock 주입
    @Test void doesNotCallInstantNowDirectly() throws IOException {
        assertThat(grepFiles("Instant\\.now\\(\\)")).isEmpty();
    }

    // G11. catch (Exception ...) 빈 블록 금지
    @Test void noSilentCatch() { ... }

    // G12. Map<String, Object> 를 반환 타입으로 쓰지 않는다
    @Test void noMapAsBoundaryType() { ... }
}
```

🔴 **실패 메시지에 파일 경로가 그대로 나오게 만든다.** "규칙 위반 12건" 만 나오면 아무도 안 고친다.

🔴 **게이트를 느슨하게 만드는 PR 은 반려다.** 정규식에 예외를 추가하려면 PR 본문에 사유를 적고, `MEMBER_OPEN_ITEMS.md` 에 등재한다. 조용히 `@Disabled` 를 붙이는 것은 §10 위반이다.

**게이트가 못 잡는 것** — 이건 사람 리뷰의 몫이다. 정직하게 적어둔다.

- 스파게티 여부 자체(호출 그래프 복잡도) — G2~G5 는 *방향*만 잡는다
- 이름이 실제로 의미를 담는지
- 주석이 "왜"를 적었는지
- 중첩 깊이·순환 참조 — 정규식으로는 부정확하다. 필요해지면 ArchUnit 도입을 별도 공통 PR 로

---

## §11-3. 🔴 「키가 없다」와 「키는 있고 값이 null」을 구분하는 법

member 계약에는 두 종류가 섞여 있다. **응답 조립이 정반대**다.

| 계약 | 응답 | 예 |
|---|---|---|
| `nullable: true` | 🔴 **키를 두고 값을 `null`** | `grade` · `activationStatus` · `studentPublicId` |
| `nullable` 없음 + `required` 아님 | 🔴 **키를 뺀다** | `teachers` · `notificationsEnabled` |

🔴 **`nullable` 이 아닌 필드에 `null` 을 넣으면 계약 위반이고, 빈 배열/기본값으로 채우면 위조다.**
못 채우면 키를 뺀다. 그게 유일한 비위조 선택지다.

### 🔴 `jsonPath(...).doesNotExist()` 로 단언하지 마라 — 둘을 구분하지 못한다

Spring 의 `JsonPathExpectationsHelper#doesNotExist` 는 정해진(definite) 경로에서
**값이 `null` 이어도 통과**한다. 즉 `{"teachers": null}` 도 초록불이다.
**그 구분이 이 단언의 전부인데, 그 도구는 구분을 못 한다.**

→ **응답 본문 문자열에서 키 이름의 부재를 본다.** (PR4 실측으로 확인된 함정)

🔴 그리고 **반대 방향 단언을 같이 둔다** — `nullable` 인 필드는 **키가 남고 값이 `null`** 인지.
없으면 누군가 「`null` 이면 키를 뺀다」를 레코드 전체에 걸고(`@JsonInclude(NON_NULL)`),
그 순간 `nullable` 필드 쪽이 계약 위반이 된다. PR4 가 `grade` 로 그 자물쇠를 걸었다.

## §12. 리뷰 반려 체크리스트

리뷰어가 이 순서로 본다. 🔴 는 **하나라도 걸리면 즉시 반려**.

- [ ] 🔴 `MemberCodeRuleTest` 전부 green (G1~G15, G7·G15 는 -a/-b 포함 **17개**)
- [ ] 🔴 `scripts/member-no-regression.sh` PASS (R1~R8)
- [ ] 🔴 `01_endpoint_branch_matrix.md` 표 행 수 = 테스트 수
- [ ] 🔴 기존 패키지 파일 변경 0건 (`git diff --name-only`)
- [ ] 🔴 `build.gradle`·`application*.yaml`·`.github/**` 무변경
- [ ] 🔴 실 LLM 호출 0회
- [ ] 하드코딩된 정책값 없음 (§2)
- [ ] 상태·오류 코드가 enum (§2)
- [ ] 클래스 400줄 / 메서드 50줄 / 파라미터 5개 이내 (§3-3)
- [ ] `@Transactional` 이 application 에만, RLS 컨텍스트가 진입 직후 (§6)
- [ ] DB 제약 위반을 constraint 이름으로 분기 (§5)
- [ ] null 이 "모른다"는 뜻으로만 쓰였고 0 으로 채우지 않았다 (§4)
- [ ] 상한으로 자른 곳에 무엇을 왜 잘랐는지 적혀 있다 (§8)
- [ ] TODO 에 안건 번호가 있다 (§7)
- [ ] 동어반복 주석 없음 (§7)
- [ ] PR 이 한 가지 일만 하고 400줄 이내 (§10)
- [ ] PR 본문에 실행 OS·pass/skip·CI 차이 기록 (§10)

---

### 🔴 게이트를 넓힌 기록

| 날짜 | 게이트 | 무엇 | 왜 |
|---|---|---|---|
| 2026-08-25 | **G8** | 금지 코드 6개 → **21개 전량** · `MemberErrorCode.java` 제외 · 따옴표 포함 매치 | 6개만 막으면 나머지 15개가 리터럴로 샌다. PR1 전수에서 나옴 |
| 2026-08-25 | **G13 신설** | enum 집합 == `docs/MEMBER_ERROR_CODES.md` 집합 | 같은 목록이 세 곳에서 **18 / 21 / 17** 로 갈렸다. 사람 눈으로는 두 번 다 못 잡았다 |
| 2026-08-25 | **G14 신설** | 코드의 `TODO(MB-xx)` 번호가 `MEMBER_OPEN_ITEMS.md` 에 실재하는가 | PR1 이 `TODO(MB-29)` 를 안건 등록 없이 넣었다. **G9 는 형식만 보고 실재는 안 본다** — 죽은 참조가 조용히 생긴다 |
| 2026-08-26 | **G14 구현** | 문서에는 있는데 **테스트에 없었다** | PR3 에서 실측 — 문서가 「G1~G14 green」을 요구하는데 G14 테스트가 존재하지 않았다. §13 이 금지하는 「문서만 고치고 게이트를 안 둔」 상태였다 |
| 2026-08-26 | **G15 신설** | RLS 테이블을 읽는 서비스가 자기 트랜잭션에서 컨텍스트를 여는가 | PR3 에서 결함 3건이 여기서 나왔다. 컨텍스트를 안 열면 **예외가 아니라 0행**이라 조용하다 — 규칙으로는 못 막는다 |
| 2026-08-26 | **G15-b 신설** | G15 면제가 열거한 테이블이 정말 RLS 밖인가 | 면제를 이름 목록으로 두면 게이트가 무력해진다. 면제자가 **읽는 테이블을 적게** 하고 마이그레이션으로 반증한다 |

🔴 **G13 이 이 문서에서 제일 중요한 게이트일 수 있다.** 지금까지 나온 반증 중 가장 자주 반복된 게 "같은 표가 문서마다 다르다"였다.

## §13. 이 문서를 바꾸는 절차

1. 규칙을 추가·완화하려면 **먼저 이 문서를 고치고** PR 본문에 사유를 적는다.
2. §11 게이트가 있는 규칙이면 테스트도 같이 고친다. **문서만 고치고 게이트를 두는 것도, 게이트만 고치고 문서를 두는 것도 반려**다.
3. `CheckOn-AI` `docs/03_coding_rules.md` 와 충돌하는 규칙은 여기 쓰지 않는다. 원칙이 바뀌어야 하면 그쪽을 먼저 고친다.
4. 🔴 **이 문서가 기존 패키지에 소급 적용된다고 주장하지 마라.** member 전용이다. 승우님 코드를 이 기준으로 반려할 수 없다.
