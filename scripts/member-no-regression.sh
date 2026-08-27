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
# 🔴 **항목이 「파일 하나」인지 구조로 확인한다.** 고의 파괴로 실측했다(W2):
#    예외를 디렉터리(`.../global/openapi/`)로 바꾸고 그 안의 **다른** 파일을 고쳤더니
#    게이트가 **green** 이었다 — 개수 검사(위)도 완전 일치 비교(아래)도 통과한다.
#    「하나만 둔다」는 규칙이 「한 줄만 적는다」로 지켜지면 그 한 줄이 폴더 전체일 수 있다.
#    실재하는 파일인지도 함께 본다 — 파일이 사라지면 목록이 썩은 것이고, 썩은 예외는
#    아무것도 안 지키면서 게이트를 통과시킨다.
for exception in "${FOREIGN_EXCEPTIONS[@]}"; do
  case "$exception" in
    */) echo "R8 예외가 디렉터리다 — 파일 전체 경로로 적어라: $exception"; FAIL=1 ;;
  esac
  if [ ! -f "$exception" ]; then
    echo "R8 예외 파일이 실재하지 않는다 — 목록이 썩었다: $exception"; FAIL=1
  fi
done

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
