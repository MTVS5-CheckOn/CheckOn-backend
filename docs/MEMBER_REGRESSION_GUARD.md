# 승우님 파트 무영향 증명 절차 (로컬 선개발 → 회귀 증명 → 푸시)

> 원칙: **로컬에서 먼저 다 만들고, 승우님 것이 그대로 도는지 증명하고, 내 것도 도는 걸 확인한 뒤에 push 한다.**
> 이 문서는 그 "증명"을 말로 하지 않고 **명령과 종료 코드로** 하게 만든다.
>
> 🔴 이 문서와 산출물 경로는 **저장소 루트 기준**이다. 이 폴더(`docs/member-backend/`)와 기준선 폴더(`.member-baseline/`)는
> `.git/info/exclude` 에 등록돼 있어 `git status` 에 뜨지 않는다. 명령은 전부 **`~/AI/CheckOn-backend` 루트에서** 실행한다.

---

## 0. 왜 이게 필요한가 — 실측

| 사실 | 근거 |
|---|---|
| `CheckOn-backend` 최근 커밋 **80건이 전원 한승우님** | `git log --format='%an'` : `한승우 <tmddn_00@naver.com>` 62 + GitHub noreply 18 |
| `CODEOWNERS` **없음** | `find . -name CODEOWNERS` → 없음. 리뷰어 자동 지정이 없다 |
| 원격 CI 가 **돈다** | `.github/workflows/ci.yml` "Backend CI" (PR→dev/main, `timeout-minutes: 15`) + `publish-image.yml` |
| V38 가 정책을 추가할 9개 테이블이 **전부 승우님 것** | `parent_*` ← V33 · `teacher_student_relationships` ← V6 · `problem_assignments`·`saved_problem_set*` ← V17 · `learning_records` ← V8 |
| 지침 `docs/02_ownership.md` 는 이 저장소를 **「읽기만·수정 PR 금지」** 로 규정 | 프로젝트 지침 §3 |

즉 **파일이 겹치는 게 문제가 아니라, 그의 테이블 보안 정책이 내 PR로 바뀐다는 게 문제**다. 그래서 "안 겹쳤다"가 아니라 **"바꿨는데 그의 것이 그대로 돈다"** 를 증명해야 한다.

---

## 1. 겹침 지도 — 4단위

### 1-1. 파일·디렉터리

| 경로 | 겹침 | 완화 |
|---|---|---|
| `src/main/java/com/checkon/member/**` | ❌ 신규 | — |
| `src/test/java/com/checkon/member/**` | ❌ 신규 | — |
| `src/main/resources/openapi/member-api.yaml` | ⚠ 디렉터리 공유 | 신규 파일. `dashboard-api.yaml` 무접촉 |
| `docs/MEMBER_*.md` | ⚠ 디렉터리 공유 | 접두 `MEMBER_` 고정. `POLICY_REGISTER.md` 무접촉 |
| `scripts/` | ⚠ **이미 존재** (승우님 `.ps1` 6개 + `demo/`, `problem-generation/`) | 신규 파일은 `scripts/member-*` 접두 |
| `build.gradle` · `application*.yaml` · `.github/workflows/**` | ⚠ 공유 | 🔴 **무접촉.** 의존성·설정 추가가 필요하면 별도 공통 PR |

### 1-2. Flyway 번호

전역 단일 네임스페이스. `_MIGRATION_RESERVATION.md` 로 V38~V44 을 한 번에 예약한다. 🔴 하나씩 예약하면 중간에 밀린다.

### 1-3. DB 테이블 — 🔴 여기가 진짜 겹침

V38 가 정책을 **추가**하는 9개는 전부 승우님 소유다. 추가만 하고 `DROP POLICY`/`ALTER POLICY`/`DISABLE ROW LEVEL SECURITY` 는 0회다. teacher 컨텍스트에서는 새 정책 술어가 `current_checkon_student_id() IS NULL` 로 false 라 **기존 동작이 바뀌지 않는다** — 이걸 §3 으로 증명한다.

### 1-4. Spring 런타임

| 겹침 | 위험 | 완화 |
|---|---|---|
| `SecurityFilterChain` 순서 | `@Order(0)` 이 기존 `@Order(1)/(2)` 앞에 온다 | `securityMatcher("/api/v1/member/**")` 로 경로를 격리. 기존 경로는 도달조차 안 함 |
| `@RestControllerAdvice` 우선순위 | member advice 가 기존 컨트롤러 오류 형식을 삼킬 수 있다 | `basePackageClasses = MemberPackageMarker.class` 로 범위 제한 + 회귀 테스트 |
| 컴포넌트 스캔 | member 빈이 기존 빈과 충돌 | 이름 접두 `Member*` 고정. `@Primary` 금지 |
| 기동 검증기 | `TenantDatabaseRoleSafetyVerifier` 가 15개를 센다 | 🔴 그 파일 무접촉. member 는 별도 `MemberDatabaseRoleSafetyVerifier` |
| 테스트 시간 | CI 가 15분 타임아웃 | member 통합테스트 소요를 매 PR 기록. 초과 위험 시 분리 |

---

## 1-5. 로컬 환경 전제 (2026-08-25 셋업 실측)

이 절차의 명령들은 아래 환경을 가정한다. 다르면 **명령을 고치기 전에 이 표를 고친다.**

| 항목 | 값 | 비고 |
|---|---|---|
| JDK | **Temurin 25** (`~/Library/Java/JavaVirtualMachines/temurin-25/`) | `build.gradle` 툴체인 25 · CI·Dockerfile 과 동일 |
| Spring Boot | **4.1.0** | 🔴 5 가 아니다 |
| Gradle wrapper | 9.5.1 | |
| PostgreSQL | **`localhost:5433`** | `CheckOn-AI` 의 `checkon-ai-db-1` 이 5432 점유 → `compose.override.yaml` 로 5433 매핑 |
| DB 역할 | `checkonAdmin`(superuser, Flyway) · **`checkon_app`(제한 역할, 런타임)** | 🔴 RLS 검증은 **`checkon_app`** 으로 |
| Kafka | `localhost:9094` | |
| AI 서버 | **`https://checkon.bellrajin.com`** | Cloudflare Tunnel → 윈도우 호스트 9000 → 컨테이너 8000 |
| Kafka Adapter | 로컬 미기동 (기본 `localhost:8081`) | member 개발에 불필요 |

🔴 **앱·테스트 실행 시 `SPRING_DOCKER_COMPOSE_ENABLED=false` 를 붙인다.** `spring-boot-docker-compose` 가 `developmentOnly` 로 들어 있어서, 안 붙이면 `compose.yaml` 의 **모든** 서비스(배포 이미지 `back`, nginx `web` 80/443 포함)를 자동 기동한다.

```bash
docker compose up -d postgres kafka
SPRING_PROFILES_ACTIVE=dev SPRING_DOCKER_COMPOSE_ENABLED=false ./gradlew bootRun
```

### 🔴 기준선 실측 (2026-08-25 · `origin/dev` PR #92 머지 후 `1a37c56`)

| 항목 | 값 |
|---|---|
| `./gradlew test` | **468 tests · 0 failures · exit 0 · 3분 52초** (기존 428 + member 40) |
| 테스트 클래스 파일 | 124 |
| RLS 정책 | 🔴 **174개** (`policies.txt` 는 **240줄** — 21개 정책의 `qual` 에 개행이 있다. **줄 수로 세지 마라**) |
| RLS 켜진 테이블 | **48개** (⚠ `TenantDatabaseRoleSafetyVerifier` 의 `15` 는 그 파일이 **이름으로 세는 15개**일 뿐 DB 전체가 아니다) |
| `schema.sql` | 5,916줄 |
| 엔드포인트 | 67건 (member 제외 필터 적용) |
| Flyway | 37개 적용 → `v37` |

🔴 **389 · 394 · 396 · 436 은 전부 옛 값이다.** 인용하지 마라.

### 🔴 CI 시간을 로컬×배율로 예측하지 마라 — 두 점이 반대로 움직였다

| | 로컬(mac · Temurin 25) | 원격 CI (ubuntu-latest) |
|---|---|---|
| `906943a` (389 tests) | 3분 52초 | 5분 39초 (run `32768947996`) |
| **PR #87** (396 tests) | **3분 20초** ↓ | **5분 59초** ↑ (run `32814654572`) |

🔴 **로컬이 32초 줄었는데 CI 는 20초 늘었다.** 배율(1.46× / 1.79×) 둘 다 근거가 없다 — CI 는
체크아웃·Gradle·Docker **고정 비용이 지배**한다. **매 PR 의 CI 실측을 그대로 기록**하고
그 숫자가 15분에 다가가는지로 판단한다. 현재 여유 **9분**. CI 타임아웃은 15분(`ci.yml`).

| **PR #92** (468 tests) | 4분 9초 | **6분 26초 / 6분 31초 / 6분 54초** |

🔴 **PR #92 는 같은 468 테스트에서 CI 가 28초 폭으로 흔들렸다**(비율 1.55~1.66×). 배율 모델이
성립하지 않는다는 직접 증거다. 🔴 **환산표를 만들지 마라.** CI 실측을 그대로 적고
그 숫자가 15분에 다가가는지로만 판단한다. 현재 여유 약 **8분 30초**.

🔴 **로컬 `./gradlew test` 총 소요가 6분을 넘으면 중단하고 분리 전략을 먼저 정한다**(§8 중단 규칙 5번).

🔴 **테스트는 AI 서버에 의존하지 않는다.** `AI_BASE_URL` 이 죽은 주소를 가리킨 상태에서 전량 통과했다. 즉 AI 서버는 member 개발의 **선행 조건이 아니다.**

## 2. 작업 순서 — 🔴 `dev` 를 체크아웃하지 않는다

```
① 브랜치를 먼저 판다            git switch -c feature/member/prN
② 기준선 캡처  ← 브랜치 위에서   이 시점의 브랜치 = dev 와 동일하다
③ 개발
④ 회귀 증명   (승우님 것이 그대로 도는가)
⑤ 자기 검증   (내 것이 도는가)
⑥ origin/dev 를 merge → ④⑤ 재실행
⑦ push + PR
```

### 🔴 왜 ①이 ②보다 앞인가

브랜치를 **판 직후의 내 브랜치는 `dev` 와 글자 하나까지 같다.** 그러니 기준선을 여기서 뜨면 된다. `dev` 를 체크아웃할 이유가 **전혀 없다.**

`dev` 로 갔다 오면 두 가지가 위험하다:
- 브랜치 파는 걸 잊고 `dev` 에 커밋한다 — 🔴 이건 되돌리기 비싸다
- 테스트 4분을 `dev` 에서 태우고 다시 브랜치로 옮겨야 한다 — 아무 이득이 없다

🔴 **이 절차 전체에서 `git switch dev` 는 한 번도 나오지 않는다.** 나오면 절차가 틀린 것이다.

### 🔴 ⑥은 rebase 가 아니라 merge 다

실측 — 이 팀은 **merge 를 쓴다**:

```
Merge branch 'dev' into bug/problem-bug/60
Merge branch 'dev' into feature/dashboard/25
Merge remote-tracking branch 'origin/feature/dashboard/25' into feature/dashboard/25
```

전체 210 커밋 중 **62개가 머지 커밋**이고, PR 도 `Merge pull request` 방식이다.

```bash
git fetch origin dev
git merge origin/dev        # 🔴 rebase 아님
```

rebase 를 쓰면 이미 push 한 브랜치에 **force-push** 가 필요하고, 리뷰 중인 PR 의 diff 가 통째로 흔들린다. 팀 관행과도 어긋난다.

🔴 ⑥ 을 빼지 마라. ④⑤ 를 통과했어도 그 사이 승우님이 `dev` 를 밀었으면 **다시 증명해야 한다.**

---

## 2-5. 🔴 저장소 사본 동기화 — 매 PR 의 마지막 커밋

`docs/member-backend/` 의 작업 원본과 저장소에 커밋된 사본은 **자동으로 같아지지 않는다.**

| 저장소 사본 (커밋됨) | 작업 원본 (git 무시) |
|---|---|
| `docs/MEMBER_DESIGN.md` | `docs/member-backend/00_member_backend_design.md` |
| `docs/MEMBER_BRANCH_MATRIX.md` | `docs/member-backend/01_endpoint_branch_matrix.md` |
| `docs/MEMBER_CODE_RULES.md` | `docs/member-backend/03_backend_code_rules.md` |
| `docs/MEMBER_CONSULTATION_HITL_CONTRACT.md` | `docs/member-backend/04_consultation_hitl_contract.md` |
| 🔴 `docs/MEMBER_REPORT_PUBLICATION_CONTRACT.md` | `docs/member-backend/05_report_publication_contract.md` | (PR9 추가 · 여섯째)
| 🔴 `docs/MEMBER_REGRESSION_GUARD.md` | `docs/member-backend/02_regression_guard.md` | (W1 추가 · 일곱째 — 게이트를 저장소에 올리면서)
| 🔴 `src/main/resources/openapi/member-teacher-api.yaml` | `docs/member-backend/member-teacher-api.yaml` | (W2 추가 · 여덟째 — 강사 발행 계약)
| `docs/MEMBER_ERROR_CODES.md` | 설계 §7-2 |
| `docs/MEMBER_OPEN_ITEMS.md` | 설계 §17 + 지시서의 MB-xx |
| 🔴 `src/main/resources/openapi/member-api.yaml` | `docs/member-backend/member-api.yaml` |

🔴 **push 직전에 반드시 돌린다:**

```bash
for pair in "docs/MEMBER_DESIGN.md:docs/member-backend/00_member_backend_design.md" \
            "docs/MEMBER_BRANCH_MATRIX.md:docs/member-backend/01_endpoint_branch_matrix.md" \
            "docs/MEMBER_CODE_RULES.md:docs/member-backend/03_backend_code_rules.md" \
            "docs/MEMBER_CONSULTATION_HITL_CONTRACT.md:docs/member-backend/04_consultation_hitl_contract.md" \
            "docs/MEMBER_REPORT_PUBLICATION_CONTRACT.md:docs/member-backend/05_report_publication_contract.md" \
            "docs/MEMBER_REGRESSION_GUARD.md:docs/member-backend/02_regression_guard.md" \
            "src/main/resources/openapi/member-teacher-api.yaml:docs/member-backend/member-teacher-api.yaml" \
            "src/main/resources/openapi/member-api.yaml:docs/member-backend/member-api.yaml"; do
  a=${pair%%:*}; b=${pair##*:}
  cmp -s "$a" "$b" && echo "✅ $a" || echo "🔴 다름 $a"
done
```

다르면 **원본을 사본으로 복사하고 별도 커밋**으로 올린다. 커밋 메시지에 무엇이 왜 바뀌었는지 적는다.

🔴 **방향이 정해져 있다 — 원본(`docs/member-backend/`) 을 고치고 사본으로 복사한다.**
반대로 하면 다음에도 반대로 한다. PR5 에서 실제로 뒤집혔다: 계약을 저장소 사본
(`src/main/resources/openapi/`)에서 먼저 고쳐 놓고 정본이 뒤처진 채로 게이트가 초록불이었다.

🔴 **계약 쌍이 목록에 없던 이유가 중요하다.** PR0~PR4 는 계약을 <b>고치지 않아서</b> 드리프트가
드러나지 않았다. **PR5 가 계약을 고치는 첫 PR** 이고, 그 순간 바로 갈렸다 —
§3-9 「게이트는 실제로 무언가를 막아본 뒤에야 검증된다」와 정확히 같은 자리다.
쌍이 **여덟**로 늘었으니(PR9 이 발행 계약을, W1 이 이 문서 자체를, W2 가 강사 계약을 추가했다) 새 문서를 만들 때는 **여기 먼저 등록**한다.
🔴 PR9 실측 — 새 문서는 커밋 사본만 만들고 작업 원본이 없었다. 그러면 **드리프트가 날 수는 없지만
   다음 사람이 어느 쪽을 고쳐야 하는지 모른다.** 원본을 만들고 여기 등록해서 방향을 고정했다.

🔴 **`MEMBER_CODE_RULES.md` 가 특히 위험하다.** `03_backend_code_rules.md` §13 이
「게이트만 고치고 문서를 두는 것도 반려」라고 규정하는데, **저장소 사본이 낡으면 그 반려 조건에 스스로 걸린다.**
리뷰어는 `MemberCodeRuleTest`와 `MEMBER_CODE_RULES.md` 를 나란히 보고 **테스트가 틀렸다**고 판단한다.

### 실제로 있었던 일 (2026-08-25 · PR1)

PR0 가 사본 5개를 올린 뒤, PR0 보정과 PR1 준비 과정에서 **원본만** 두 번 고쳤다:
- 설계에 **§7-3**(PR1 확정 3건) 추가
- 코드 규칙에 **G8 확대(6→21) · G13 신설**

PR1 완료 시점 실측: `MEMBER_DESIGN.md` **43줄 차이** · `MEMBER_CODE_RULES.md` **45줄 차이**(G13 0건).
`MEMBER_BRANCH_MATRIX.md` 만 우연히 같았다.

---

## 3. ② 기준선 캡처 — 🔴 **내 브랜치 위에서**, 아무것도 고치기 전에

> ### ✅ 기준선 — `1a37c56` (PR #92 머지 후)
>
> | 항목 | 값 |
> |---|---|
> | tests | **468** (기존 **428** + member 40) · failures 0 · exit 0 · 3분 52초 |
> | 정책 | **174개** (파일 240줄 — 🔴 줄 수로 세지 마라) |
> | 엔드포인트 | 67건 · 스키마 5,916줄 · Flyway 37 |
>
> 🔴 **비교 대상은 총계가 아니라 「기존 428건이 그대로 통과하는가」**다.
> 🔴 dev 가 밀릴 때마다 이 숫자가 바뀐다. `git merge origin/dev` 후 **반드시 다시 잰다.**


```bash
# 0. 브랜치를 먼저 판다. 이 시점의 브랜치 = origin/dev 와 동일하다.
git fetch origin dev
git switch -c feature/member/prN origin/dev
git --no-optional-locks status --short      # 🔴 깨끗해야 한다

# 0-1. DB 를 깨끗하게 올린다 (dev 의 마이그레이션만 적용된 상태 = V33)
docker compose down -v
docker compose up -d postgres kafka
```

🔴 **`git switch dev` 를 하지 않는다.** `git switch -c <새브랜치> origin/dev` 한 번이면 브랜치도 파고 최신 dev 지점에서 출발한다.

🔴 **DB 를 `down -v` 로 지우고 다시 올린다.** 이전 작업의 V38 가 남아 있으면 기준선이 오염된다.

산출물 5개는 git 무시되는 로컬 폴더에 둔다(커밋하지 않는다).

```bash
mkdir -p .member-baseline

# B1. 커밋 기준점
git rev-parse HEAD > .member-baseline/head.txt

# B2. 테스트 전량 + 개수 + 소요
./gradlew test --rerun-tasks > .member-baseline/test.log 2>&1
echo "exit=$?" >> .member-baseline/test.log
# 🔴 클래스별로 센다. 히스토그램(`124 errors="0"`)은 목적과 반대로 동작한다 — §3-9.
# 🔴 **파일 이름이 아니라 XML 안의 `classname` 속성을 쓴다.**
#    Gradle 은 긴 클래스명을 잘라 해시로 바꾼다(실측 12개):
#      TEST-c-CUM6CI2G2JT78.problem.presentation.…$GivenActiveStudentTarget.xml
#    파일명으로 뽑으면 이런 줄은 `com.checkon.member.*` 제외를 **빠져나간다.**
#    오늘은 member 7개가 전부 온전한 FQCN 이라 안 터지지만, member 테스트에
#    @Nested + 긴 이름이 붙는 순간 R2 범위로 새어 들어온다. §3-14.
#    `classname` 속성은 잘림과 무관하게 **항상 FQCN** 이다(실측 126개 중 비FQCN 0).
grep -ho 'classname="[^"]*"' build/test-results/test/TEST-*.xml \
  | sed 's/^classname="//; s/"$//' \
  | grep -v '^com\.checkon\.member\.' \
  | sort | uniq -c | awk '{printf "%s %s\n", $2, $1}' | sort \
  > .member-baseline/test-counts.txt
# 🔴 실측 기준값: 119 클래스 / 430건 (member 44 를 뺀 수). 총계 474 와 맞는지 확인해라.
ls build/test-results/test/*.xml | wc -l > .member-baseline/test-classes.txt

# B3. RLS 정책 전량
# 🔴 psql·pg_dump 는 호스트에 없다(PR2 전수 실측). 컨테이너 안의 것을 쓴다.
#    DB 이름도 checkon 이 아니라 .env 의 값이다(실측: checkon_backend).
# 🔴 반드시 런타임 제한 역할(checkon_app)로 접속한다 —
#    checkonAdmin(superuser) 으로 뜨면 RLS 가 우회돼서 기준선이 무의미해진다.
#    docker exec 로 들어가면 5433 은 의미가 없다(호스트→컨테이너 경로일 뿐, 같은 DB).
PGC=$(docker compose ps -q postgres)
DBNAME=$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2)
dpsql() { docker exec -i "$PGC" psql -U checkon_app -d "$DBNAME" "$@"; }

dpsql -Atc "SELECT current_user, rolsuper, rolbypassrls FROM pg_roles WHERE rolname=current_user"
# 🔴 super=f, bypassrls=f 를 눈으로 확인하고 넘어간다

dpsql -Atc "SELECT tablename||'|'||policyname||'|'||cmd||'|'||coalesce(qual,'-')||'|'||coalesce(with_check,'-') \
            FROM pg_policies WHERE schemaname='public' ORDER BY 1" \
  > .member-baseline/policies.txt

# 🔴 정책 개수는 줄 수로 세지 마라 — qual 안에 개행이 있어서 부풀려진다(실측 174개 / 240줄).
dpsql -Atc "SELECT count(*) FROM pg_policies WHERE schemaname='public'" \
  > .member-baseline/policy-count.txt

# B4. 스키마 구조 (컬럼·제약·인덱스)
# 🔴 pg_dump 18 은 매 실행마다 랜덤 토큰이 붙은 \restrict / \unrestrict 줄을 낸다.
#    그대로 두면 같은 DB 를 두 번 떠도 diff 가 나서 R4 가 영구 red 가 된다. 제거한다.
docker exec -i "$PGC" pg_dump -U checkon_app -d "$DBNAME" \
    --schema-only --no-owner --no-privileges \
  | grep -vE '^\\(un)?restrict ' > .member-baseline/schema.sql

# B5. 기존 엔드포인트 목록
# 🔴 경로 문자열로 거르지 않는다. member 컨트롤러는 클래스에 @RequestMapping("/api/v1/member"),
#    메서드에 @GetMapping("/ping") 으로 나뉘어 있어 **메서드 애노테이션 줄에는 그 문자열이 없다.**
#    필터를 빠져나가 기준선에 섞이고, 나중에 그 엔드포인트를 지우면 R5 가 오탐 red 를 낸다
#    (PR3 실측 — @GetMapping("/ping") 1줄). §3-13.
#    대신 **파일 위치**로 가른다. 이름이 아니라 구조다. R5 와 이 블록은 항상 같아야 한다.
# 🔴 제외 대상을 손으로 적지 않는다 — `$OURS` 하나로 §4 R5·R8 과 같은 값을 쓴다(W2).
#    이전 판은 member 만 적었고, publication 이 엔드포인트를 처음 만든 순간 R5 가 red 였다.
OURS='member|publication'
find src/main/java -name '*.java' \
  | grep -Ev "^src/main/java/com/checkon/(${OURS})/" \
  | tr '\n' '\0' \
  | xargs -0 grep -hoE '@(Get|Post|Put|Patch|Delete|Request)Mapping\("[^"]*"\)' \
  | sort > .member-baseline/endpoints.txt
```

🔴 B3 을 **런타임 앱 역할**로 뜨는 게 중요하다. Flyway DDL 역할로 뜨면 보이는 정책이 달라진다.

---

## 3-9. 🔴 PR2 가 잡은 스크립트 결함 4건 (2026-08-25)

**「아무것도 고치기 전에 한 번 돌려서 전부 exit 0 인지 확인해라」가 정확히 이걸 위해 있었다.**
스크립트를 처음 쓰는 PR 에서 **결함 4건이 연달아** 나왔다 — 코드가 아니라 **게이트 자체의 결함**이다.
변경이 0인데 **R4·R5 가 실패**했다 — 코드 탓이 아니라 **스크립트 자체의 결함**이었다.

| # | 증상 | 원인 | 조치 |
|---|---|---|---|
| **R4** | 같은 DB 를 두 번 떠도 diff | `pg_dump 18` 이 매 실행마다 **랜덤 토큰이 붙은 `\restrict` 줄**을 낸다 | 양쪽에서 `\restrict`/`\unrestrict` 제거 |
| **R5** | 항상 1줄 차이 | §3 B5 는 member **포함**, §4 R5 는 member **제외** — 필터가 달랐다. PR1 머지로 `MemberPingController` 가 생긴 순간부터 영구 red | 기준선도 **같은 필터**로 재캡처 (68 → 67) |
| **R7** | 🔴 **아무 증상 없음 — 그래서 더 위험했다** | 버전 정규식이 `3[0-3]` 이라 **V34~V37 이 통째로 무방비**였다 | 🔴 **정규식을 버렸다.** `git diff --name-status` 로 "추가가 아닌 변경"을 잡는다 — 번호가 몇까지 가든 다시 손댈 일이 없다 |
| **R4** | 🔴 **아직 안 터졌지만 V38 에서 터진다** | `grep -v 'member_'` 로 걸렀는데 V38 의 함수 3개는 `current_checkon_*_id` 라 그 필터를 못 빠져나간다 | 🔴 **이름 필터를 버렸다.** `diff \| grep '^<'` — "기준선 줄이 사라지거나 바뀌지 않았다". 추가는 `>` 로만 나오므로 이름과 무관하다 |
| **R2** | 🔴 **테스트를 추가하기만 해도 red** | ① `grep -v member` 가 **하는 일이 없다** — `test-counts.txt` 에 클래스 이름이 아예 없다(`grep -c member` = 0). ② **히스토그램 비교**(`124 errors="0"` / `24 tests="2"`)라 목적("사라졌는가")과 반대로 동작한다 | 🔴 **클래스별로 본다.** `<testcase classname="...">` 로 집계해 **기준선 클래스가 사라졌거나 테스트 수가 줄었을 때만** 실패. 추가는 허용 |

🔴 **R2·R4·R7 은 같은 병이다 — 게이트가 이름·번호·개수에 묶여 있으면 그 목록이 늘 때마다 조용히 썩는다.**
셋 다 **구조로 바꿨다**: "무엇이 추가됐나"가 아니라 **"기존 것이 그대로인가"**. 이 형태는 안 낡는다.

| 게이트 | 낡는 형태 (버림) | 안 낡는 형태 (채택) |
|---|---|---|
| R2 | 히스토그램 개수 비교 | 클래스별 **감소만** 실패 |
| R4 | `grep -v '이름'` | `diff \| grep '^<'` |
| R7 | 버전 정규식 `3[0-3]` | `git diff --name-status \| grep -v '^A'` |

🔴 **R2 는 PR1(40건 추가) 때도 red 였을 것이다.** 그때는 스크립트가 없어서 안 드러났을 뿐이다.
⚠ `test-counts.txt` 는 클래스명이 없어 **"어느 클래스가 사라졌는지"를 원리적으로 알 수 없었다.**
기준선을 `git switch --detach <base>` 로 다시 떠야 했다 — 히스토그램은 클래스별로 **변환이 불가능**하다.
⚠ 파일명이 Gradle 해시로 잘리므로(`TEST-c-CUM6CI2G2JT78.problem...`) **파일명이 아니라 `classname` 속성**을 쓴다.

🔴 **R7 은 `isEqualTo("34")` 와 같은 종류의 결함이다** — 사람이 손으로 밀어야 하는 숫자.
게이트 안에 그런 숫자가 있으면 **조용히 썩는다.** 발견되는 건 이미 뚫린 뒤다.

⚠ 이 셋은 **PR0·PR1 에서는 안 드러났다.** 마이그레이션 0개, member 컨트롤러 0개라
R3·R4·R6 가 검사할 게 없었기 때문이다. **게이트는 실제로 무언가를 막아본 뒤에야 검증된다.**

---

## 3-14. 🔴 **열 번째 — Gradle 이 파일 이름을 자른다. 아직 안 터진 결함을 미리 닫는다**

2026-08-25 PR3 작업 1·2 보고에서 나왔다. **이번 것은 아직 red 를 내지 않았다.**

R2 를 「클래스별 비교」로 고치면서 클래스명을 `TEST-<class>.xml` **파일 이름**에서 뽑았다.
그런데 Gradle 은 긴 이름을 잘라 해시로 바꾼다 — 실측 12개:

```
TEST-c-CUM6CI2G2JT78.problem.presentation.…$GivenActiveStudentTarget.xml
TEST-com-M0OGUMRN2G77M.problem.presentation.…$GivenActiveClassTarget.xml
     ↑ com.checkon 이 통째로 뭉개졌다
```

`grep -v '^com\.checkon\.member\.'` 가 이런 줄을 **못 거른다.**
오늘 member 테스트 7개는 전부 온전한 FQCN 이라 동작하지만,
**member 클래스에 `@Nested` + 긴 `@DisplayName` 이 붙는 순간** 제외를 빠져나가 R2 범위에 들어온다.
게다가 잘린 이름에는 **해시**가 들어가 있어 기준선 줄이 사람이 읽을 수 없는 문자열이 된다 —
§3-9 가 R2 를 고친 이유(「어느 클래스가 사라졌는지 알 수 있게」)를 절반만 달성한 셈이다.

### 처방 — XML 안의 `classname` 속성을 쓴다

```bash
grep -ho 'classname="[^"]*"' build/test-results/test/TEST-*.xml \
  | sed 's/^classname="//; s/"$//' \
  | grep -v '^com\.checkon\.member\.' \
  | sort | uniq -c | awk '{printf "%s %s\n", $2, $1}' | sort
```

실측: 전체 **126 클래스 중 비FQCN 0건**. 잘린 파일 안에서도 `classname` 은 온전하다.
결과 **119 클래스 / 430건** — 「기존 430 + member 44 = 474」와 정확히 맞는다.

🔴 **B2 와 R2 를 같은 편집에서 고쳤다**(§3-13 쌍 규칙).

### 🔴 이번 건의 성격 — 처음으로 **터지기 전에** 닫았다

결함 1~9 는 전부 **터진 뒤** 또는 **터질 수 있는 상태로 발견**됐다. 이건 다르다.
「오늘은 안 터진다」에서 멈추지 않고 **어떤 조건에서 터지는지**까지 말한 보고가 잡았다.

🔴 게이트를 볼 때 물어야 할 질문이 하나 늘었다:
**「이 판정이 의존하는 값을 누가 만드는가? 그가 그 값을 바꿀 수 있는가?」**
여기서는 Gradle 이 파일 이름을 만들고, 클래스 이름 길이에 따라 바꾼다.

---

## 3-13. 🔴 **일곱 번째 — 그리고 더 나쁜 것: 이 문서가 자기 표를 안 지켰다**

2026-08-25 PR3 작업 1 에서 나왔다. 셋이 한 번에 드러났다.

### ① R5 — 애노테이션이 나뉘어 있으면 경로 필터가 안 걸린다

```java
@RestController
@RequestMapping("/api/v1/member")     ← 여기에만 경로가 있다
class MemberPingController {
    @GetMapping("/ping")              ← 이 줄에는 /api/v1/member 가 없다
```

`grep -v '/api/v1/member'` 는 **줄 단위**라 두 번째 줄을 못 거른다. 그대로 기준선에 섞였고,
MB-29 로 그 엔드포인트를 지우자 R5 가 **「기존 엔드포인트가 사라졌다」고 오탐**했다.

🔴 **PR2 에서 R5 를 「필터 양쪽 정렬」로 고친 처방이 불완전했다는 뜻이다.**
필터를 맞춰도 애노테이션 분리는 남는다. §3-11 이 이미 말한 그대로다 — **이름으로 거른 게 원인**이다.
→ **파일 위치로 가른다**(`grep -Ev "^src/main/java/com/checkon/(${OURS})/"`). §3-12 의 원칙과 같다.

### ② R3 는 bare `psql`, R4 는 `docker exec` — 같은 스크립트 안에서 갈렸다

게다가 `$PGC`·`$DBNAME` 은 **§4 어디에서도 정의되지 않았다.** `set -u` 라 그대로 죽는다.
PR2 가 §3 만 고치고 §4 를 안 따라가서 생긴 구멍이다. → §4 머리에 `dpsql()` 을 두고 통일했다.

### ③ 🔴 **가장 나쁜 것 — §3-9 표는 「R2 를 클래스별로 고쳤다」고 적혀 있는데 §4 본문은 옛 판이었다**

```
§3-9 표  : 🔴 클래스별로 본다. 기준선 클래스가 사라졌거나 테스트 수가 줄었을 때만 실패
§4 본문  : diff <(grep -v member ...) <(grep -v member ...)     ← 히스토그램. 안 고쳐졌다
```

지시는 「문서 §4 를 통째로 꺼내라」였다. **그대로 따랐으면 PR2 에서 고친 R2 가 되돌아갔다.**
작업자가 표와 본문을 대조해서 잡았다.

🔴 **요약표는 색인이지 정본이 아니다.** 표에 「고쳤다」고 쓸 때는
**본문의 코드를 그 자리에 붙여넣어 대조**한다. 표만 고치고 본문을 두면
**표가 거짓말을 하고, 그 거짓말은 다음 사람이 본문을 그대로 꺼낼 때 조용히 퇴행으로 돌아온다.**

### 이번에 정리한 것

| 곳 | 전 | 후 |
|---|---|---|
| §3 B2 · §4 R2 | 히스토그램 + `grep -v member` | 클래스별 + 패키지 경로 제외 + `^<` |
| §3 B5 · §4 R5 · 재기준선 | `grep -v '/api/v1/member'` | `find` + `grep -Ev "…/(${OURS})/"` (W2 에서 `$OURS` 로 통일) |
| §4 R3 · 재기준선 | bare `psql` | `dpsql()` (§3 과 동일) |

🔴 **B(기준선)와 R(검사)은 항상 쌍이다.** 한쪽만 고치면 §3-11 의 3번·5번이 다시 난다.
고칠 때 **두 곳을 같은 편집에서** 고쳤는지 확인해라.

### 🔴 R2 의 「의도된 감소」는 이제 문제가 아니다

PR3 의 계약 대조 테스트 3 → 2 는 §12 지시대로 지운 결과다.
member 를 **패키지 경로로 제외**하므로 R2 는 아예 보지 않는다.

🔴 **기준선을 다시 뜨는 것으로 넘어가지 마라.** 그건 의도된 삭제와 **의도치 않은 삭제를 함께**
지워버린다. 게이트가 오탐하면 게이트를 고치는 것이지, 기준선을 오탐에 맞추는 게 아니다.

---

## 3-12. 🔴 **여섯 번째 결함 — 커밋 전 게이트는 절반이 헛돈다**

2026-08-25 PR3 중간 보고에서 나왔다. **PASS 인데 아무것도 검사하지 않았다.**

```
R3-b · R6 · R7 · R8    git diff --name-only <base>..HEAD
                                                 ↑ 커밋된 것만 본다
새 마이그레이션 diff 대상:                        ← 빈 출력 (V39 는 아직 워킹트리)
```

🔴 **red 가 아니라 조용한 통과다.** 이게 §3-11 의 다섯 건보다 나쁘다 —
저것들은 최소한 red 를 내서 눈에 띄었다. 이건 **초록불을 켜고 아무 일도 안 한다.**

### 무엇이 걸렸나

| 게이트 | 커밋 전에 놓치는 것 |
|---|---|
| R3-b | 새 마이그레이션이 기존 테이블에 정책을 붙였는가 |
| R6 | 새 마이그레이션의 `DROP`·`SECURITY DEFINER` |
| R7 | 🔴 **기존 마이그레이션 수정** · 공유 설정 파일 변경 |
| R8 | 🔴 **승우님 패키지 java 파일 변경** ← 절대 규칙 1번 |

R8 이 최악이다. 「승우님 파일 안 고쳤다」를 커밋 전에는 **증명하지 않고 있었다.**

### 처방 — `changed()` 하나로 통일

```bash
BASE_REF=$(cat "$BASE/head.txt")
changed() {
  { git diff --name-only "$BASE_REF" ${1:+-- "$1"}     # base ↔ 워킹트리 (staged 포함)
    git ls-files --others --exclude-standard ${1:+-- "$1"}; } | sort -u   # 아직 add 안 한 것
}
```

- `..HEAD` 를 떼면 **워킹트리까지** 본다
- `git ls-files --others` 가 **untracked 새 파일**(V39 가 그랬다)을 더한다
- R7 만 `--name-status` 를 그대로 쓴다 — untracked 는 애초에 안 잡히고(= 추가는 허용),
  기존 파일 수정·삭제는 커밋 전에도 `M`/`D` 로 잡힌다

### 🔴 그리고 검사 대상을 **찍는다**

```bash
changed src/main/resources/db/migration > /tmp/new-migrations.txt
printf '%-28s %s\n' "검사 대상 마이그레이션" "$(wc -l < /tmp/new-migrations.txt)건"
cat /tmp/new-migrations.txt
```

🔴 **「0건이라 통과」와 「검사해서 통과」는 다른 말이다.** 이번 결함이 두 달을 갈 수도 있었던 건
게이트가 그 둘을 같은 `exit=0` 으로 출력했기 때문이다. §3-9 조용한 절단 금지와 같은 원칙이다.

### 게이트 결함 11건 — 한 문장씩

| # | 게이트 | 무엇이 문제였나 | 증상 |
|---|---|---|---|
| 1 | R2 | `grep -v member` 가 아무 일도 안 함 | 오탐 red |
| 2 | R4 | `grep -v 'member_'` 를 새 함수명이 빠져나감 | 오탐 red |
| 3 | R5 | B5 포함 · R5 제외 | 영구 red |
| 4 | R7 | 버전 정규식 `3[0-3]` | 🔴 **무증상** |
| 5 | R3 | `NOT LIKE '%\_member\_%'` 불일치 | 영구 red |
| 6 | **R3-b·R6·R7·R8** | **커밋 전 워킹트리 실명** | 🔴 **무증상 — 초록불** |
| 7 | R5 | 애노테이션이 나뉘어 경로 필터를 빠져나감 | 오탐 red |
| 8 | R3 | §3 은 `dpsql`, §4 는 bare `psql` · `$PGC` 미정의 | 실행 불가 |
| 9 | **문서 자신** | **§3-9 표는 고쳤다는데 §4 본문이 옛 판** | 🔴 **무증상 — 그대로 꺼내면 퇴행** |
| 10 | R2 | Gradle 이 긴 클래스명을 잘라 해시로 바꿈 → member 제외를 빠져나감 | 🔴 **아직 안 터짐 — 미리 닫음** |
| 11 | `MemberCodeRuleTest` G2·G3·G4·G5·G15 | **경로 구분자를 OS 가 만든다** — `contains("/…/")` 가 Windows 에서 오탐 | 🔴 **CI(ubuntu)에서는 안 보인다** (승우님 Windows 로컬 빌드에서 발견) |

🔴 **무증상 두 건(4·6)이 위험한 것들이다.** red 는 사람을 불러오지만 초록불은 안 부른다.
게이트를 새로 쓰거나 고칠 때 물어야 할 마지막 질문: **「이게 아무것도 검사 안 하는 상태로도 통과하나?」**

🔴 **「이 판정이 의존하는 값을 누가 만드는가」를 또 안 물었다.** 결함 10번(Gradle 이 파일명을 자른다)과
같은 질문이었는데 놓쳤다. 이번엔 **OS 가 경로 구분자를 만든다.** 그리고 우리 CI 는 ubuntu 하나라
영영 안 보인다 — **CI 가 한 종류면 그 CI 가 못 보는 축이 반드시 생긴다.**

---

## 3-11. 🔴 **다섯 번째 결함 — 이름 필터는 예외 없이 썩는다**

2026-08-25 PR3 전수에서 나왔다. **변경 0인 상태에서 R3 가 red** 였다.

```
§3 B3 : ... FROM pg_policies WHERE schemaname='public' ORDER BY 1          ← member 포함
§4 R3 : ... WHERE schemaname='public' AND policyname NOT LIKE '%\_member\_%'  ← member 제외
```

기준선에는 있고 R3 결과에는 없어서 **영구히 21행 차이**가 난다.
PR2 까지는 안 드러났다 — 기준선을 뜰 때 member 정책이 DB 에 0개였기 때문이다.
**PR2 가 머지되어 dev 에 21개가 들어온 순간** 터졌다.

### 🔴 「필터를 양쪽에 맞춘다」는 처방이 아니다

전수 보고는 B3 에도 같은 `NOT LIKE` 를 넣자고 제안했다. 그러면 red 는 사라지지만 **병은 남는다**:

| | 제안(양쪽 필터) | 채택(필터 제거 + `^<`) |
|---|---|---|
| 이름 규칙 어긴 새 정책 | 🔴 양쪽에서 다르게 걸려 또 어긋난다 | 이름을 안 본다 |
| 앞 PR 의 member 정책을 뒤 PR 이 수정 | 🔴 **필터에 걸려 안 보인다** — V38 checksum 이 깨지는데 통과 | `<` 로 잡힌다 |
| 정책 이름 규칙이 바뀌면 | 게이트를 또 손봐야 한다 | 무관 |

🔴 `%\_member\_%` 에는 이미 구멍이 있었다 — `member_student_activation_parent_select` 같은
**앞머리가 `member_` 인 이름은 `_member_` 에 매칭되지 않는다.** PR3 가 만들 이름이 바로 그 모양이다.

### 이름·번호·개수로 거른 게이트 — 5건 전부

| 게이트 | 무엇으로 걸렀나 | 어떻게 썩었나 | 지금 |
|---|---|---|---|
| R2 | `grep -v member` (테스트 개수) | 클래스명이 파일에 없어 필터가 **아무 일도 안 했다** | 구조 |
| R4 | `grep -v 'member_'` (스키마) | `current_checkon_account_id` 가 필터를 안 빠져나감 | `^<` |
| R5 | 필터 불일치 (엔드포인트) | B5 포함 · R5 제외 → 영구 red | 필터 정렬 |
| R7 | 정규식 `3[0-3]` (버전) | V34~V37 무방비. **증상이 없어서 가장 위험했다** | 구조 |
| **R3** | `NOT LIKE '%\_member\_%'` (정책) | B3 포함 · R3 제외 → 영구 red | **`^<`** |

🔴 **게이트에 이름·번호·개수가 보이면 그 자리가 다음 결함이다.**
물어야 할 것은 「무엇이 추가됐나」가 아니라 **「있던 것이 그대로인가」**다.
🔴 그리고 이번엔 **R3-b 를 새로 붙였다** — `^<` 로 바꾸면 "기존 테이블에 정책을 *추가*"가
통과해버린다. **구조 검사로 바꿀 때는 그 검사가 놓아주는 게 무엇인지 함께 물어라.**

---

## 3-10. 🔴 고의 파괴가 red 를 냈다고 끝이 아니다 — **누가 낸 red 인가**

2026-08-25 PR2 커밋 ① 에서 나온 것. 파괴 0-a(V33 중복 인위 생성)는 **red 를 냈지만**,
그 red 는 새로 넣은 단언이 낸 게 아니었다.

```
마이그레이션 버전이 서로 중복되지 않는다
  -> ParameterResolutionException: Failed to resolve parameter [JdbcTemplate jdbcTemplate]
```

중복 파일이 있으면 **Flyway 가 먼저 죽어서 Spring 컨텍스트가 안 뜬다.** 그 클래스의 테스트 9개가
전부 `ParameterResolutionException` 으로 무너지고, **새 단언은 실행조차 되지 않는다.**

🔴 즉 그 위치의 중복 검증은 **구조적으로 스스로 실패할 수 없다.**
중복이 없으면 통과하고, 중복이 있으면 컨텍스트가 먼저 죽는다. **탐지력이 0이다.**

### 그래서 파괴 결과를 볼 때 두 가지를 구분한다

| 물음 | 확인 방법 |
|---|---|
| red 가 났는가 | 종료 코드 |
| 🔴 **내가 넣은 단언이 낸 red 인가** | **실패 메시지 본문**을 읽는다. 내 단언의 문구·기대값이 보이는가? |

두 번째를 안 보면 **죽은 게이트를 살아 있다고 착각한다.** 지금까지 세 번 나왔다 —
PR1 파괴 3(`basePackageClasses`), PR1 파괴 a(계약 단방향), PR2 파괴 0-a(중복 검증).

### 대처 — 단언을 **실패할 수 있는 자리**로 옮긴다

무거운 컨텍스트(`@SpringBootTest`)에 기대는 단언은, 그 컨텍스트를 죽이는 종류의 결함을 잡지 못한다.
**컨텍스트 없이 도는 순수 JUnit 클래스**로 빼면 항상 실행되고, 실패 메시지가 원인을 이름으로 짚는다.

⚠ 이건 "탐지"만의 문제가 아니라 **진단**의 문제다. Flyway 가 어차피 죽으니 사고는 막힌다.
다만 PR #86 때처럼 **242개가 무너진 스택을 파야 "V33 이 두 개"라는 걸 알게 된다.**

---

## 4. ③ 회귀 증명 — 승우님 것이 그대로 도는가

`scripts/member-no-regression.sh` 로 만든다. 🔴 **각 단계 종료 코드를 개별로 기록하고, 하나라도 non-zero 면 전체 실패.**

### 🔴 환경 전제 — 2026-08-25 PR2 전수에서 정정된 것

| 이전 판 | 실측 | 왜 |
|---|---|---|
| `psql -Atc` · `pg_dump` 직접 호출 | 🔴 **둘 다 호스트에 없다** | `docker exec` 로 컨테이너 안의 것을 쓴다. 제한 역할(`checkon_app`)은 그대로 지킨다 |
| `PGDATABASE=checkon` | 🔴 **`checkon_backend`** | `.env` 에서 읽는다. 이름을 코드에 박지 마라 |
| `PGPORT=5433` | `docker exec` 에서는 **무의미** | 5433 은 호스트→컨테이너 경로일 뿐이다. 같은 DB |

🔴 **이 셋은 PR0·PR1 에서는 안 드러났다** — 마이그레이션 0개, member 컨트롤러 0개라 R3·R4·R6 가 검사할 게 없었기 때문이다. **PR2 가 이 스크립트를 처음 실제로 쓰는 PR이다.**

### 🔴 SKIP_R1 — R1 을 로컬에서 돌리지 않는 실행 모드 (2026-08-27)

CLAUDE.md §0-2 개정으로 **R1(전체 실행)은 CI 가 담당**한다(`ci.yml:33`).
로컬에서 5분 33초를 다시 태우는 것은 CI 가 더 잘하는 일의 중복이다.

🔴 그런데 **R2 는 R1 의 출력(`build/test-results/test/TEST-*.xml`)을 먹는다.** R1 을 건너뛰고
R2 만 돌리면 XML 이 이 회차의 member 것뿐이라 **기존 클래스가 「사라졌다」로 판정돼 요란하게 실패**한다.
그래서 **R1 을 건너뛰면 R2 도 함께 건너뛴다** — 스크립트에 `SKIP_R1=1` 을 전달한다.

- 조용히 건너뛰지 않는다(§3-9 조용한 절단 금지). 시작 시에 이렇게 찍는다:
  `⏭ R1·R2 건너뜀 — 전체 실행은 CI 가 한다(ci.yml:33). R2 는 R1 의 XML 을 먹어 함께 건너뛴다.`
- **알고 받는 위험 1건**: 우리 코드가 승우님 테스트를 조용히 skip 시키는 경우는 CI 도 개수를 비교하지 않아 못 잡는다.
  R8(승우님 자바 파일 무접촉)이 green 이면 그 파일의 테스트가 사라질 수 없으므로 **파일 관점에서는 구조적으로 덮인다.**
  다만 `@Disabled`·`@Tag` 같은 우리 쪽 설정을 통해 우회하는 경로는 CI 도 우리도 못 잡는다.

```bash
#!/usr/bin/env bash
# 승우님 파트 무영향 증명. 통과 전에는 push 하지 않는다.
#
# ─────────────────────────────────────────────────────────────────────────────
# 🔴 먼저 기준선을 떠라. 이 스크립트만으로는 아무것도 못 한다.
#
#   이 스크립트는 `.member-baseline/` 의 네 파일과 **비교**할 뿐이다. 그 폴더는 커밋되지
#   않는다(사람마다·시점마다 다른 값이라 공유하면 오히려 거짓이 된다).
#   🔴 그래서 **브랜치를 판 직후, 아무것도 고치기 전에** 한 번 떠야 한다.
#
#   0) 브랜치를 먼저 판다. 🔴 `git switch dev` 를 하지 않는다.
#        git fetch origin dev && git switch -c <새브랜치> origin/dev
#   1) DB 를 깨끗이 올리고 마이그레이션을 적용한다(앱을 한 번 띄우면 Flyway 가 돈다).
#        docker compose down -v && docker compose up -d postgres kafka
#        SPRING_PROFILES_ACTIVE=dev SPRING_DOCKER_COMPOSE_ENABLED=false ./gradlew bootRun
#        # "Started CheckOnApplication" 이 보이면 끄면 된다
#   2) 기준선을 뜬다:
#        mkdir -p .member-baseline
#        git rev-parse HEAD > .member-baseline/head.txt
#        PGC=$(docker compose ps -q postgres)
#        DBNAME=$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2)
#        docker exec -i "$PGC" psql -U checkon_app -d "$DBNAME" -Atc \
#          "SELECT tablename||'|'||policyname||'|'||cmd||'|'||coalesce(qual,'-')||'|'||coalesce(with_check,'-') \
#           FROM pg_policies WHERE schemaname='public' ORDER BY 1" > .member-baseline/policies.txt
#        docker exec -i "$PGC" pg_dump -U checkon_app -d "$DBNAME" \
#          --schema-only --no-owner --no-privileges | grep -vE '^\\(un)?restrict ' \
#          > .member-baseline/schema.sql
#        OURS='member|publication'   # 🔴 §4 R5 와 **같은 값**이어야 한다
#        find src/main/java -name '*.java' \
#          | grep -Ev "^src/main/java/com/checkon/(${OURS})/" \
#          | tr '\n' '\0' \
#          | xargs -0 grep -hoE '@(Get|Post|Put|Patch|Delete|Request)Mapping\("[^"]*"\)' \
#          | sort > .member-baseline/endpoints.txt
#   3) 고치기 전에 한 번 돌려서 R3~R8 이 전부 exit 0 인지 확인한다:
#        SKIP_R1=1 ./scripts/member-no-regression.sh
#
#   🔴 `checkon_app` 은 **런타임 제한 역할**이다. superuser(checkonAdmin)로 뜨면 RLS 가
#      우회돼 기준선이 무의미해진다. `rolsuper/rolbypassrls` 가 f/f 인지 눈으로 확인해라.
#   🔴 `SKIP_R1=1` 은 R1(전체 테스트)·R2 를 건너뛴다 — CI 가 그 둘을 돈다(ci.yml:33).
#      전체 절차와 각 단계가 무엇을 잡는지는 `docs/MEMBER_REGRESSION_GUARD.md` 를 봐라.
# ─────────────────────────────────────────────────────────────────────────────
set -u
BASE=.member-baseline
FAIL=0
SKIP_R1="${SKIP_R1:-}"   # 🔴 1 이면 R1·R2 를 함께 건너뛴다 (CI 가 R1 을 돈다)
# 🔴 런타임 제한 역할·5433 고정. superuser 로 돌면 R3 이 무의미해진다.
# 🔴 호스트에 psql·pg_dump 가 없다. §3 과 **똑같이** 컨테이너 안에서 실행한다.
#    🔴 이전 판은 §4 안에서 R3 는 bare psql, R4 는 docker exec 로 갈렸고
#       $PGC·$DBNAME 은 §4 어디에서도 정의되지 않았다(set -u 라 그대로 죽는다). §3-13.
PGC=$(docker compose ps -q postgres)
DBNAME=$(grep -E '^POSTGRES_DB=' .env | cut -d= -f2)   # 🔴 checkon 아니다. checkon_backend
dpsql() { docker exec -i "$PGC" psql -U checkon_app -d "$DBNAME" "$@"; }
export SPRING_DOCKER_COMPOSE_ENABLED=false
step() { printf '%-28s exit=%s\n' "$1" "$2"; [ "$2" -ne 0 ] && FAIL=1; }

# 🔴 **우리 경계의 정본.** R5(엔드포인트)와 R8(파일 무변경)이 **같은 값**을 쓴다.
#    경계가 늘면 여기 한 곳만 고친다 — 두 곳에 적으면 언젠가 갈린다.
#    🔴 W2 실측: R5 는 member 만 제외하고 있어서 publication 이 엔드포인트를 처음 만든
#       순간 red 가 났다. R8 은 이미 구조 판정으로 고쳐 뒀는데 R5 는 안 고쳐져 있었다 —
#       같은 병이 게이트 안에서 **한 칸 옆으로** 남아 있었다.
OURS='member|publication'

# 🔴 기준선 이후 내가 만들거나 고친 파일 전량 — **커밋 여부와 무관하게**.
#    `git diff base..HEAD` 만 쓰면 커밋 전 워킹트리 파일이 안 보인다.
#    그러면 R3-b·R6 는 검사할 게 없어서 **red 가 아니라 조용히 통과**한다(§3-12).
#      · git diff --name-only <base>            → base 대비 워킹트리(추적 파일, staged 포함)
#      · git ls-files --others --exclude-standard → 아직 add 안 한 새 파일
BASE_REF=$(cat "$BASE/head.txt")
changed() {   # $1 = pathspec (선택)
  { git diff --name-only "$BASE_REF" ${1:+-- "$1"}
    git ls-files --others --exclude-standard ${1:+-- "$1"}; } | sort -u
}

# 🔴 무엇을 검사 대상으로 잡았는지 **눈에 보이게 찍는다.** 조용한 절단 금지(§3-9).
#    "0건이라 통과"와 "검사해서 통과"는 다른 말이다.
changed src/main/resources/db/migration > /tmp/new-migrations.txt
printf '%-28s %s\n' "검사 대상 마이그레이션" "$(wc -l < /tmp/new-migrations.txt)건"
cat /tmp/new-migrations.txt

if [ -n "$SKIP_R1" ]; then
  # 🔴 조용한 절단 금지(§3-9) — 왜 건너뛰는지 사람이 읽을 수 있어야 한다.
  echo "⏭ R1·R2 건너뜀 — 전체 실행은 CI 가 한다(ci.yml:33). R2 는 R1 의 XML 을 먹어 함께 건너뛴다."
else
  # R1. 기존 테스트 전량 green + 개수가 줄지 않았는가
  ./gradlew test --rerun-tasks > /tmp/after-test.log 2>&1; step "R1 gradle test" $?
  # R2. 기존(승우님) 테스트가 조용히 사라지거나 줄지 않았는가
  # 🔴 §3 B2 와 **똑같은 방식**이어야 한다. 히스토그램 비교는 버렸다(§3-9).
  # 🔴 member 를 파일 이름으로 제외하므로 **member 테스트를 의도적으로 지워도 R2 는 안 운다.**
  #    PR3 의 계약 대조 테스트 3 → 2 감소가 그 경우다. 기준선을 다시 뜰 일이 아니다 — §3-13.
  # 🔴 §3 B2 와 한 글자도 다르면 안 된다. classname 속성 — 파일명은 잘린다(§3-14).
  grep -ho 'classname="[^"]*"' build/test-results/test/TEST-*.xml \
    | sed 's/^classname="//; s/"$//' \
    | grep -v '^com\.checkon\.member\.' \
    | sort | uniq -c | awk '{printf "%s %s\n", $2, $1}' | sort \
    > /tmp/after-counts.txt
  # 클래스가 사라졌거나(<) 테스트 수가 줄었을 때만(<) 실패. 추가는 '>' 라 통과한다.
  diff "$BASE/test-counts.txt" /tmp/after-counts.txt | grep '^<' > /tmp/count.diff
  [ ! -s /tmp/count.diff ]; step "R2 기존 테스트 유지" $?
fi

# R3. 기준선에 있던 정책이 사라지거나 바뀌지 않았는가  ← 가장 중요
# 🔴 이름으로 거르지 않는다. R2·R4·R7 과 같은 이유다(§3-11).
#    이전 판은 NOT LIKE '%_member_%' 로 걸렀는데, 기준선(B3)은 안 걸렀다.
#    PR2 가 머지되어 member 정책 21개가 dev 에 들어온 순간 영구 21행 차이가 났다.
#    필터를 양쪽에 맞추는 건 처방이 아니다 — 이름 규칙을 어긴 새 정책에서 또 어긋난다.
#    대신 "기준선에 있던 줄이 사라지거나 바뀌지 않았다"를 본다.
#    순수 추가는 '>' 로만 나오고, 삭제·변경은 '<' 를 낸다. 이름과 무관하다.
# 🔴 부수 효과 하나는 이득이다 — 앞선 PR 이 만든 member 정책을 뒤 PR 이 고치는 것도 이제 잡는다.
#    V38 은 이미 적용된 마이그레이션이라 고치면 checksum 이 깨진다. 잡혀야 맞다.
dpsql -Atc "SELECT tablename||'|'||policyname||'|'||cmd||'|'||coalesce(qual,'-')||'|'||coalesce(with_check,'-') \
            FROM pg_policies WHERE schemaname='public' ORDER BY 1" \
  > /tmp/after-policies.txt
diff "$BASE/policies.txt" /tmp/after-policies.txt | grep '^<' > /tmp/policy.diff
[ ! -s /tmp/policy.diff ]; step "R3 기존 정책 무변경" $?

# R3-b. 🔴 새 마이그레이션이 **기존(승우님) 테이블**에 정책을 붙이지 않았는가
#    예약표 규칙 1 — 기존 테이블에 정책을 추가하는 것은 V38 뿐이다.
#    R3 는 '>' 를 통과시키므로 이건 R3 가 못 잡는다. PR2 까지는 검사할 게 없어서 안 드러났다.
# 🔴 "내 테이블"의 정의를 버전 번호로 두지 않는다(번호는 썩는다).
#    **member 마이그레이션 파일이 만든 테이블**이 내 테이블이다. 파일명 규칙은 예약표가 고정한다.
#    🔴 이 판정은 안전한 쪽으로 실패한다 — 파일명을 잘못 지으면 내 테이블이 남의 테이블로
#       분류돼 red 가 난다. 조용히 통과하는 방향이 아니다.
cat src/main/resources/db/migration/V*__member_*.sql 2>/dev/null \
  | grep -ioE '^[[:space:]]*CREATE TABLE [a-z0-9_]+' \
  | awk '{print tolower($NF)}' | sort -u > /tmp/member-tables.txt
# 🔴 tr 로 한 줄로 편 뒤에 뽑는다. grep 은 줄 단위라 아래 형태를 **못 잡는다**:
#        CREATE POLICY foo
#            ON some_table
#    V38 의 21개 정책 중 이 형태가 16개다. 편평화 전 추출은 5개만 잡았다 —
#    🔴 **조용히 적게 잡는 판정은 red 를 안 내고 통과한다.** 실측으로 확인한 결함이다.
while read -r f; do [ -f "$f" ] && cat "$f"; done < /tmp/new-migrations.txt \
  | tr '\n' ' ' \
  | grep -ioE 'CREATE POLICY[[:space:]]+[a-z0-9_]+[[:space:]]+ON[[:space:]]+(ONLY[[:space:]]+)?[a-z0-9_.]+' \
  | awk '{print tolower($NF)}' | sed 's/^public\.//' | sort -u > /tmp/policy-targets.txt

# 🔴 자기 점검: V38 로 이 추출식을 돌리면 **12개 테이블**(기존 9 + member 3)이 나와야 한다.
#    5개가 나오면 편평화가 빠진 것이다.

# 🔴 승인된 예외. 여기 넣으려면 **팀 승인 인용문과 날짜**가 있어야 한다.
#    지금 하나뿐이다 — 예약표 규칙 1 상 승우님 테이블에 정책을 더 넣을 일은 없다.
#    「예상된 red」로 두면 다음 사람이 모든 red 를 예상된 것으로 읽는다 — escape hatch 가
#    되지 않게 목록으로 좁게 관리한다. 게이트 결함 10건에서 배운 원칙과 같은 방향이다.
#    teacher_student_relationships : MB-36 · 2026-08-25 승우님 승인
#      「네 진행해주세요! PR은 따로 안해도 괜찮습니다.」
APPROVED_POLICY_TABLES="teacher_student_relationships"

comm -23 /tmp/policy-targets.txt \
     <({ cat /tmp/member-tables.txt; printf '%s\n' $APPROVED_POLICY_TABLES; } | sort -u) \
  > /tmp/policy-on-existing.txt

# 🔴 승인으로 통과시킨 것을 **반드시 찍는다.** 조용히 넘기지 않는다(§3-9).
for t in $APPROVED_POLICY_TABLES; do
  grep -qx "$t" /tmp/policy-targets.txt && \
    printf '%-28s %s\n' "⚠ 승인 예외 통과" "$t (MB-36 · 2026-08-25)"
done
[ ! -s /tmp/policy-on-existing.txt ]; step "R3-b 기존테이블 정책추가" $?

# R4. 기존 테이블의 컬럼·제약이 그대로인가 (신규 추가는 허용)
# 🔴 이름으로 거르지 않는다. R7 과 같은 이유다 —
#    이전 판은 grep -v 'member_' 로 걸렀는데, V38 이 만드는 함수 3개는
#    current_checkon_account_id / _student_id / _parent_id 라 그 필터를 안 빠져나간다.
#    필터에 이름을 추가하는 방식은 새 이름이 생길 때마다 또 손대야 한다.
#    대신 "기준선에 있던 줄이 사라지거나 바뀌지 않았다"를 본다.
#    순수 추가는 '>' 로만 나오고, 삭제·변경은 '<' 를 낸다. 이름과 무관하다.
docker exec -i "$PGC" pg_dump -U checkon_app -d "$DBNAME" \
    --schema-only --no-owner --no-privileges \
  | grep -vE '^\\(un)?restrict ' > /tmp/after-schema.sql
diff "$BASE/schema.sql" /tmp/after-schema.sql | grep '^<' > /tmp/schema.diff
[ ! -s /tmp/schema.diff ]; step "R4 기존 스키마 무변경" $?

# R5. 기존 엔드포인트가 사라지거나 바뀌지 않았는가
# 🔴 §3 B5 와 **똑같아야 한다.** 경로 문자열이 아니라 파일 위치로 가른다(§3-13).
# 🔴 제외 대상을 손으로 적지 않는다 — `$OURS` 하나로 R8 과 같은 값을 쓴다.
find src/main/java -name '*.java' \
  | grep -Ev "^src/main/java/com/checkon/(${OURS})/" \
  | tr '\n' '\0' \
  | xargs -0 grep -hoE '@(Get|Post|Put|Patch|Delete|Request)Mapping\("[^"]*"\)' \
  | sort > /tmp/after-endpoints.txt
diff "$BASE/endpoints.txt" /tmp/after-endpoints.txt > /tmp/endpoint.diff
[ ! -s /tmp/endpoint.diff ]; step "R5 기존 엔드포인트" $?

# R6. 금지 SQL 이 신규 마이그레이션에 없는가
xargs -r grep -inE 'DROP (POLICY|TABLE|COLUMN|INDEX|CONSTRAINT)|ALTER POLICY|DISABLE ROW LEVEL|SECURITY DEFINER' \
  < /tmp/new-migrations.txt > /tmp/forbidden.txt
[ ! -s /tmp/forbidden.txt ]; step "R6 금지 SQL" $?

# R7. 무접촉 파일이 변경되지 않았는가
# 🔴 마이그레이션은 **버전 번호를 하드코딩하지 않는다.**
#    이전 판은 V([1-9]|[12][0-9]|3[0-3])__ 였고, dev 가 V37 까지 오는 동안
#    V34~V37 이 통째로 무방비였다(PR2 전수에서 발견). isEqualTo("34") 와 같은 종류의 결함이다.
#    대신 "추가(A)가 아닌 변경"을 잡는다 — 새 파일 추가는 허용, 기존 파일 수정·삭제는 금지.
#    이 형태는 번호가 몇까지 가든 다시 손댈 필요가 없다.
# 🔴 ..HEAD 를 떼면 워킹트리까지 본다. 새 파일은 untracked 라 여기 안 잡히고(= 추가는 허용),
#    기존 파일 수정·삭제는 커밋 전에도 M/D 로 잡힌다.
git diff --name-status "$BASE_REF" -- src/main/resources/db/migration \
  | grep -v '^A' > /tmp/touched.txt
changed \
  | grep -E '^(build\.gradle|src/main/resources/application.*\.yaml|\.github/|src/main/resources/openapi/dashboard-api\.yaml)' \
  >> /tmp/touched.txt
[ ! -s /tmp/touched.txt ]; step "R7 무접촉" $?

# R8. 기존 패키지 파일이 변경되지 않았는가 (main + test)
# 🔴 여기서 ..HEAD 를 쓰면 **커밋 전에 승우님 파일을 고쳐놓고도 통과**한다.
#    R3-b·R6·R7 과 같은 병인데 R8 이 가장 위험하다 — 절대 규칙 1번을 지키는 게이트다.
#
# 🔴 **이름을 손으로 나열하지 않는다.** 이전 판은 8개를 적어 뒀는데 실제 최상위 패키지는
#    12개였다 — **detection 과 report 두 개가 빠져 있었다.** 둘 다 승우님 것이고,
#    W1(발행 배치)이 바로 그 report 옆에서 일했는데 게이트가 아무 말도 하지 않았다.
#    원인은 하나다: **목록을 손으로 관리했다.** 패키지가 늘 때마다 사람이 기억해야 하고,
#    안 늘어난 척 조용히 통과한다. 게이트 결함 4번(마이그레이션 번호를 3[0-3] 로 박아
#    V34~V37 이 무방비였던 것)과 **정확히 같은 병**이다.
#
# 🔴 그래서 **구조로 판정한다** — 실제 패키지 목록을 읽고 거기서 **우리 것만 뺀다.**
#    새 패키지가 생기면 자동으로 무접촉 대상이 되고, 우리 경계가 늘면 OURS 만 고친다.
# 🔴 **`src/main/java` 만 보면 승우님 *테스트* 를 고쳐도 아무도 모른다.** W2 에서 우리가
#    바로 그 구멍으로 들어갔다(계약 스캐너 예외 한 줄). 들어가면서 문을 단다 —
#    main 과 test 를 **함께** 본다.
# 🔴 패키지 목록도 두 트리의 **합집합**이다. `src/test/java/com/checkon/support` 처럼
#    테스트에만 있는 패키지가 실재한다(승우님 `RosterTestFixture`) — main 만 읽으면 빠진다.
FOREIGN=$( { ls -d src/main/java/com/checkon/*/ 2>/dev/null
             ls -d src/test/java/com/checkon/*/ 2>/dev/null; } | xargs -n1 basename \
  | sort -u | grep -Ev "^(${OURS})$" | paste -sd'|' -)

# 🔴 **승인받은 예외를 여기에 적는다. 접두사가 아니라 전체 경로 완전 일치다.**
#    접두사로 적으면 같은 디렉터리의 다른 파일까지 조용히 열린다.
FOREIGN_EXCEPTIONS=(
  # publication 예외 추가 · 승우님 승인 2026-08-27 · W2
  # (계약 스캐너 패키지 예외에 com.checkon.publication 한 줄. 잃는 보증은
  #  PublicationImplementedApiOpenApiContractTest 가 양방향 대조로 메운다)
  'src/test/java/com/checkon/global/openapi/ImplementedApiOpenApiContractTest.java'
)
# 🔴 **목록이 둘이 되면 그 자리에서 FAIL 한다.** 예외 목록이 자라기 시작하면 게이트가 죽는다 —
#    「목록에 있으니까 괜찮다」가 「왜 있는지 아무도 모른다」와 같은 뜻이 된다.
#    둘째가 필요하면 게이트를 고치기 전에 **왜 필요한지부터** 답해야 한다.
if [ "${#FOREIGN_EXCEPTIONS[@]}" -gt 1 ]; then
  echo "R8 예외가 ${#FOREIGN_EXCEPTIONS[@]} 개다 — 하나를 넘으면 승인 절차를 다시 밟아라"
  FAIL=1
fi

# 🔴 조용한 절단 금지(§3-9). 무엇을 무접촉으로 봤고 무엇을 뺐는지 찍는다 —
#    "0건이라 통과"와 "검사해서 통과"는 다른 말이다.
printf '%-28s %s\n' "무접촉 대상 패키지" "${FOREIGN:-(없음)}"
printf '%-28s %s\n' "R8 승인 예외" "${FOREIGN_EXCEPTIONS[*]:-(없음)}"
if [ -z "$FOREIGN" ]; then
  # 🔴 목록이 비면 grep 패턴이 () 가 되어 **아무것도 안 잡고 통과**한다. 그건 통과가 아니다.
  echo "R8 대상 패키지를 하나도 못 찾았다 — 판정이 헛돈다"; FAIL=1
fi
changed \
  | grep -E "^src/(main|test)/java/com/checkon/(${FOREIGN})/" \
  | grep -Fxvf <(printf '%s\n' "${FOREIGN_EXCEPTIONS[@]}") \
  > /tmp/pkg.txt
[ ! -s /tmp/pkg.txt ]; step "R8 기존 패키지 무변경" $?

echo "---"
[ $FAIL -eq 0 ] && echo "REGRESSION GUARD: PASS" || { echo "REGRESSION GUARD: FAIL"; \
  for f in /tmp/count.diff /tmp/policy.diff /tmp/schema.diff /tmp/endpoint.diff \
           /tmp/forbidden.txt /tmp/touched.txt /tmp/pkg.txt; do
    [ -s "$f" ] && { echo "== $f =="; cat "$f"; }; done; }
exit $FAIL
```

### 각 단계가 무엇을 잡는가

| 단계 | 잡는 것 |
|---|---|
| R1 | 내 변경이 승우님 테스트를 깨뜨렸는가 |
| R2 | 🔴 기존 테스트가 **조용히 사라졌는가** (green 만 보면 못 잡는다) |
| R3 | 🔴 **기준선에 있던 RLS 정책이 술어까지 그대로인가.** `qual`·`with_check` 를 문자열로 비교한다. 추가(`>`)는 통과, 삭제·변경(`<`)만 red |
| R3-b | 🔴 **새 마이그레이션이 기존(승우님) 테이블에 정책을 붙였는가** (예약표 규칙 1). R3 가 못 잡는 구멍이다. 🔴 승인된 예외는 `APPROVED_POLICY_TABLES` 목록으로 좁게 통과시키고 「⚠ 승인 예외 통과」줄로 반드시 찍는다 (지금 하나: `teacher_student_relationships` — MB-36 · 2026-08-25 승우님 승인) |
| R4 | 기존 테이블 컬럼·제약이 그대로인가 |
| R5 | 기존 엔드포인트가 사라지거나 경로가 바뀌었는가 |
| R6 | 신규 마이그레이션에 파괴적 SQL 이 들어갔는가 |
| R7 | 공유 설정 파일을 건드렸는가 · 🔴 **기존 마이그레이션을 *수정*했는가**(추가는 허용). 버전 번호를 하드코딩하지 않는다 |
| R8 | 기존 패키지 자바 파일을 건드렸는가 — 🔴 **`src/main` 과 `src/test` 를 함께** 본다. 승인 예외는 **전체 경로 완전 일치**로 하나만 두고, 둘이 되면 게이트가 FAIL 한다 |

🔴 R3 이 이 스크립트의 존재 이유다. "정책을 추가만 했다"는 주장을 **정책 본문 diff** 로 증명한다.

---

## 5. ④ 자기 검증 — 내 것이 도는가

```bash
./gradlew test --tests 'com.checkon.member.*' --rerun-tasks ; echo "exit=$?"
# 🔴 Gradle 은 --tests 필터가 0건 매치면 실패한다. 실행된 테스트 수를 함께 확인한다.
ls build/test-results/test/*member*.xml | wc -l
```

추가로:
- 제한 DB 역할로 RLS 매트릭스 통과 (§6-6)
- `01_endpoint_branch_matrix.md` 의 해당 엔드포인트 표 **모든 행**에 테스트가 1:1 로 있는가
- member 통합테스트 소요 시간 기록 (CI 15분 대비)

---

## 6. ⑥ `origin/dev` merge 후 재증명 — 🔴 rebase 아님

```bash
git fetch origin dev
git log --oneline "$(cat .member-baseline/head.txt)"..origin/dev   # 승우님이 무엇을 밀었나
```

**아무것도 안 밀렸으면** (출력 0줄) 그냥 §4·§5 를 다시 돌리고 push 하면 된다.

**뭔가 밀렸으면:**

```bash
git merge origin/dev        # 🔴 rebase 금지 — 팀 관행이 merge 다 (§2 근거)
```

### 🔴 기준선을 다시 떠야 하나

승우님이 무엇을 밀었는지에 따라 다르다.

| 그가 민 것 | 기준선 | 해야 할 일 |
|---|---|---|
| 문서·CI·README 만 | 그대로 유효 | §4·§5 재실행만 |
| 자바 코드·테스트 | 🔴 **`test-counts.txt`·`endpoints.txt` 무효** | 아래 절차 |
| **새 마이그레이션** | 🔴 **`policies.txt`·`schema.sql` 무효** | 아래 절차 + 🔴 **번호 재예약** |

**기준선 재캡처 — 여기서도 `dev` 를 체크아웃하지 않는다.** 별도 worktree 를 쓴다:

```bash
# 1. origin/dev 를 별도 디렉터리에 잠깐 펼친다 (현재 브랜치는 그대로 유지된다)
git worktree add /tmp/checkon-dev origin/dev

# 2. 거기서 코드 기준선을 다시 뜬다
OURS='member|publication'   # 🔴 §3 B5 · §4 R5 와 **같은 값**이어야 한다
( cd /tmp/checkon-dev && find src/main/java -name '*.java' \
    | grep -Ev "^src/main/java/com/checkon/(${OURS})/" \
    | tr '\n' '\0' \
    | xargs -0 grep -hoE '@(Get|Post|Put|Patch|Delete|Request)Mapping\("[^"]*"\)' \
    | sort > "$OLDPWD/.member-baseline/endpoints.txt" )

# 3. DB 기준선은 깨끗한 DB 에 dev 마이그레이션만 올려서 뜬다
docker compose down -v && docker compose up -d postgres
( cd /tmp/checkon-dev && ./gradlew flywayMigrate )   # 실제 실행 방식은 전수에서 확인한 것을 쓴다
dpsql -Atc "SELECT tablename||'|'||policyname||'|'||cmd||'|'||coalesce(qual,'-')||'|'||coalesce(with_check,'-') \
            FROM pg_policies WHERE schemaname='public' ORDER BY 1" > .member-baseline/policies.txt

# 4. 정리
git worktree remove /tmp/checkon-dev
git rev-parse origin/dev > .member-baseline/head.txt
```

그 다음 **내 브랜치의 DB 를 다시 올리고**(`down -v` → 내 마이그레이션까지 적용) §4·§5 를 재실행한다.

🔴 마이그레이션 번호가 밀렸으면 `_MIGRATION_RESERVATION.md` 표 **전체**를 다시 매기고 영향 지시서를 갱신한다. 파일명만 바꾸지 마라.

---

## 7. ⑥ push 전 체크리스트

- [ ] `scripts/member-no-regression.sh` 가 **PASS** (R1~R8 전부 exit 0)
- [ ] `./gradlew test --tests 'com.checkon.member.*'` green, 실행 테스트 수 > 0
- [ ] 제한 DB 역할로 RLS 매트릭스 통과
- [ ] `01_endpoint_branch_matrix.md` 의 담당 표 모든 행에 테스트 존재
- [ ] `git diff --stat origin/dev...HEAD` 에 `com/checkon/member/`·`db/migration/V3x`·`docs/MEMBER_*`·`openapi/member-api.yaml`·`scripts/member-*` **외 경로 0건**
- [ ] 최신 `origin/dev` 를 **merge**(rebase 아님) 한 뒤 위를 **다시** 통과
- [ ] 🔴 이 작업 내내 `git switch dev` 를 **한 번도 하지 않았다** (`git reflog | grep 'checkout: moving to dev'` → 0건)
- [ ] member 통합테스트 소요 시간을 기록했다 (CI `timeout-minutes: 15` 대비)
- [ ] 🔴 V38 를 포함하는 PR이면 본문 첫 줄에
      `⚠ 승인 필요: db/migration/V38__member_subject_rls.sql — 기존 9테이블 정책 추가 (DROP/ALTER 0회)`

## PR 본문 「확인 방법」 양식

```
## 확인 방법
- 실행 OS: macOS (버전)
- 브랜치: (이름) · 🔴 `dev` 체크아웃 0회 · `origin/dev` merge 여부:
- 기준 커밋: <baseline head>
- 회귀 증명: scripts/member-no-regression.sh → PASS
  - R1 gradle test exit=0  (기존 N건 → N건, 실패 0)
  - R3 기존 정책 diff exit=0  (정책 수 X → Y, 증가분 = 신규 _member_ 정책 수와 일치)
  - R6 금지 SQL 0건 / R7 무접촉 0건 / R8 기존 패키지 0건
- member 테스트: N건 통과, **로컬 M분 → CI M'분** (배율 ?× · CI 상한 15분)
- 제한 DB 역할 RLS 매트릭스: N건 통과
- skip: (있으면 전량 나열, 없으면 "없음")
- 원격 CI 결과와 로컬 차이: (있으면 기술)
```

---

## 8. 🔴 중단 규칙 — 지장을 발견했을 때

1. **R3 diff 가 non-zero** → 즉시 중단. 기존 정책이 바뀌었다. 되돌리고 원인 보고. 고치려 들지 마라.
2. **R1 에서 기존 테스트가 새로 깨진다** → 즉시 중단. 실패 목록 전량 보고. 🔴 승우님 테스트를 고쳐서 통과시키지 마라.
3. **R2 에서 기존 테스트 개수가 줄었다** → 중단. green 이어도 중단이다.
4. **R7/R8 이 non-zero** → 중단. 무접촉 위반이다.
5. **로컬 `./gradlew test` 총 소요가 6분을 넘는다** → 중단하고 분리 전략을 먼저 정한다. (기준선 로컬 3분 52초 → CI 5분 39초, 배율 1.46× · CI 상한 15분. §1-5 참조)
6. **merge 후 승우님이 같은 테이블에 정책을 추가했다** → 중단. 두 정책이 OR 로 합쳐지므로 접근이 넓어졌을 수 있다. 함께 검토.
7. **어떤 이유로든 기존 패키지 파일을 열어야 할 것 같다** → 열지 말고 중단, 무엇이 왜 필요한지 보고.
8. **`build.gradle` 에 의존성을 넣고 싶다** → 넣지 마라. 별도 공통 PR.

---

## 9. 승우님께 미리 알릴 것 (합의가 아니라 통보로 충분한 것 / 합의가 필요한 것)

| 항목 | 통보 | 🔴 합의 필요 |
|---|---|---|
| `com.checkon.member` 패키지 신설 | ✅ | |
| `member-api.yaml`·`docs/MEMBER_*` 추가 | ✅ | |
| `scripts/member-*` 추가 | ✅ | |
| **Flyway V38~V44 번호 점유** | | ✅ |
| **V38 가 그의 9개 테이블에 정책 추가** | | ✅ |
| `@Order(0)` 보안 체인 신설 | | ✅ (기존 체인 앞에 선다) |
| CODEOWNERS 도입 | | ✅ |
| 🔴 `gradlew`·`01-create-checkon-app-role.sh` 실행 비트 (100644 → 100755) | | ✅ — §10 참조 |

합의 요청 시 같이 보낼 것: `scripts/member-no-regression.sh` 의 **R3 결과**. "정책을 추가만 했고 기존 술어는 한 글자도 안 바뀌었다"를 diff 로 보여주는 게 말보다 빠르다.

---

## 10. 🔴 리포지토리 자체 버그 2건 — 고쳐주지 말고 알린 뒤 별도 PR

셋업 중 발견됐다. **내 기능 PR 에 섞지 않는다.**

| 파일 | 증상 | 원인 |
|---|---|---|
| `gradlew` | mac 에서 `./gradlew` → `permission denied` | git 에 **100644** 로 등록. CI 는 `chmod +x` 로 우회 중 |
| `docker/.../01-create-checkon-app-role.sh` | postgres initdb 가 `bad interpreter: Permission denied` 로 죽음 | 동일 |

Windows 팀원은 Docker Desktop 마운트 특성상 안 걸리고, **mac 에서만 무조건 터진다.** 그래서 지금까지 안 잡혔다.

🔴 `core.fileMode=false` 는 **로컬 회피책일 뿐**이다. 그걸로 덮어두면 다음 mac 사용자가 똑같이 당한다. 리포를 고쳐야 한다.

```bash
# 별도 브랜치 · 이 커밋 하나만. 기능 PR 에 섞지 않는다.
git checkout -b fix/executable-bits
git update-index --chmod=+x gradlew
git update-index --chmod=+x docker/*/01-create-checkon-app-role.sh   # 실제 경로는 실측으로 확인
git diff --cached --summary        # 🔴 mode change 두 줄이 보여야 한다
git commit -m "fix: gradlew·initdb 스크립트에 실행 비트를 부여한다"
```

⚠ `core.fileMode=false` 를 켜둔 상태여도 `git update-index --chmod=+x` 는 인덱스를 직접 바꾸므로 동작한다. 다만 `git diff` 에는 안 잡히니 **`--cached --summary` 로 확인**한다.

🔴 승우님 파일이므로 PR 본문 첫 줄에 `⚠ 승인 필요: gradlew, 01-create-checkon-app-role.sh (실행 비트만, 내용 변경 0줄)`. 파일 **내용은 한 글자도 바꾸지 않는다** — mode 비트만이다.

---

## 11. 🔴 변경 유형별 최소 게이트 — "CI 가 알려주겠지" 금지

§4 의 R1~R8 은 **member 코드 PR** 을 위한 것이다. 그런데 `.gitignore` 같은 **공유 설정 파일**을 고치는 PR 은 member PR 이 아니라서 §4 가 안 걸린다 — 이 문서의 구멍이었다.

🔴 **어떤 PR 이든 push 전에 "이 변경의 영향 범위"를 먼저 판정하고, 그에 맞는 게이트를 통과한 근거를 PR 본문에 적는다.** 판정 없이 push 하고 CI 결과를 기다리는 것은 금지다.

| 티어 | 무엇 | 필수 게이트 | 코드 테스트 |
|---|---|---|---|
| **T0** | `docs/**` · `README` 만 | `git diff --name-only origin/dev...HEAD` 로 범위 증명 | 불필요 |
| **T1** | 공유 설정 — `.gitignore` · `.github/**` · `compose*.yaml` · `docker/**` | 아래 T1 절차 | **영향 판정 결과에 따라** |
| **T2** | member 자바 코드 · member 마이그레이션 | R1~R8 전량 | 필수 |
| **T3** | 🔴 **기존 테이블 정책**(V38) | R1~R8 + 제한 role RLS 매트릭스 + 고의 파괴 | 필수 |
| **T4** | 🔴 **`com.checkon.member` 밖에 `@RestController` 추가·경로 변경** | R1~R8 + **`com.checkon.global.openapi.*`** | 필수 |

### 🔴 T4 — 왜 별도 티어인가 (W2 · 2026-08-28 실측)

R5 는 「**승우님 엔드포인트가 그대로인가**」만 본다. 우리가 **새 엔드포인트를 만들었을 때
승우님 테스트가 그걸 자기 것으로 보고 깨지는 경우**는 R1~R8 어디에도 없다.

`global/openapi/ImplementedApiOpenApiContractTest` 는 `com.checkon` 전체의
`@RestController` 를 훑어 `/api/v1/**` 오퍼레이션이 **전부 `dashboard-api.yaml` 에 있어야
한다**고 단언한다. 예외는 `com.checkon.member` **하나뿐**이다(`:41-43`).
W2 에서 `com.checkon.publication` 이 엔드포인트를 처음 만들자 **CI 가 9분 11초에 red** 였고,
로컬은 §0-2 영향 범위 규칙대로 member·publication 만 돌려 **못 봤다.**

🔴 **로컬 비용은 초 단위다**(Spring 컨텍스트가 없는 순수 스캔 테스트 · 실측 11초).
「CI 가 알려주겠지」로 미룰 이유가 없다.

```bash
./gradlew test --rerun-tasks --tests 'com.checkon.global.openapi.*'
```

### T1 절차 — 공유 설정 파일

**① 영향 범위를 판정한다.** 코드·빌드·테스트에 닿는가?

```bash
git diff origin/dev...HEAD --stat          # 파일과 줄 수
git diff origin/dev...HEAD                 # 실제 내용
```

**② 판정에 맞는 검증을 고른다.**

| 고친 것 | 검증 |
|---|---|
| `.gitignore` | 🔴 `git ls-files \| grep -iE "<추가한 패턴>"` → **0건**(추적 중이던 파일이 빠지지 않음) · `git check-ignore <대상>; echo $?` → 무시돼야 할 것만 **exit 0** · `git diff --stat` → 예상 줄 수. 🔴 **판정에 `-v` 를 쓰지 마라** — 부정 패턴(`!`)에 매치돼도 `-v` 는 exit **0** 을 낸다. `-v` 는 "어느 줄이 먹었나"를 볼 때만 쓴다 |
| `.github/workflows/**` | 🔴 **워크플로는 머지돼야 도는 것이 대부분**이다. 로컬에서 `act` 등으로 재현할 수 없으면 **재현 불가를 PR 에 명시**하고 리뷰어에게 알린다 |
| `compose*.yaml` · `docker/**` | `docker compose config` 문법 검증 + 실제 기동 |
| **빌드에 닿는 것**(`build.gradle` 등) | 🔴 T2 로 올린다. R1~R8 전량 |

**③ 판정 근거를 PR 본문에 적는다.** "코드에 영향 없어서 테스트를 생략했다" 는 **주장이 아니라 명령 출력**으로 적는다.

🔴 **"영향이 없어 보인다"로 생략하지 마라.** `.gitignore` 는 추적 중이던 파일을 무시 목록에 넣으면 **다음 커밋에서 조용히 빠진다.** 그래서 `git ls-files` 확인이 T1 필수다.

### 🔴 CI 가 대신해주지 못하는 것

CI 가 green 이어도 아래는 **아무것도 증명되지 않는다.**

| CI 가 못 하는 것 | 왜 |
|---|---|
| **기존 RLS 정책이 안 바뀌었다** | CI 에는 `.member-baseline/policies.txt` 가 없다. 비교 대상이 없다 |
| **제한 role(`checkon_app`) 에서 RLS 가 실제로 막는다** | CI 는 기본 role 로 돈다. superuser 로 통과한 테스트는 의미가 없다 |
| **기존 테스트가 조용히 사라지지 않았다** | CI 는 "green" 만 본다. 389 → 350 이어도 green 이다 |
| **무접촉 파일을 안 건드렸다** | CI 는 diff 범위를 검사하지 않는다 |
| **고의 파괴가 red 를 낸다** | CI 는 파괴본을 돌려주지 않는다 |

🔴 **CI 는 "내가 안 깨뜨렸다"의 증명이 아니라 "빌드가 된다"의 확인이다.** 회귀 증명은 로컬에서 내가 한다.

### 사후 복구 — 이미 push 했다면

되돌릴 필요는 없다. **머지 전에** 해당 티어의 게이트를 돌리고 결과를 PR 본문에 추가하면 절차가 복구된다.

```
## 테스트 (사후 보강)
- 티어 판정: T1 (공유 설정 · 코드 무영향)
- 근거: git ls-files | grep -iE "CLAUDE.md|.DS_Store"  → 0건
        git check-ignore -v CLAUDE.md                   → .gitignore:63
        git diff --stat origin/dev...HEAD               → 1 file, N insertions
- 원격 CI: pass (run <번호>, N분)
```
