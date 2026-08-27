# MEMBER 월별 보고서 **발행** 계약 (강사측 · 미구현)

작성 2026-08-27 (KST) · 대상 `com.checkon.member.report` · 저장소 `CheckOn-backend`
정본 스키마: `src/main/resources/db/migration/V45__member_report.sql`
정본 분기표: `docs/MEMBER_BRANCH_MATRIX.md` §4 · 정본 계약: `src/main/resources/openapi/member-api.yaml`

---

## 0. 🔴 이 문서가 왜 있나 — PR9 에는 **프로덕션 writer 가 없다**

PR9 는 학부모의 **읽기** 경로만 만들었다. `member_published_reports` · `_sections` ·
`member_report_files` · `member_report_publication_outbox` 에 **행을 넣는 코드가
애플리케이션 어디에도 없다.** 테스트 픽스처가 DB 에 직접 INSERT 할 뿐이다.

🔴 **이것은 빠뜨린 게 아니라 결정이다.** 발행은 강사 경계의 일이고, 강사 경계는

- `roster`·`problem`·`counsel` 등 **승우님 소유 패키지**이거나
- member 에 강사용 sub-context 를 새로 여는 **별도 결정**이다.

member 는 그 둘 다 단독으로 못 한다(CLAUDE.md 절대 규칙 1·2). 그래서 「테스트 편의로」
임시 발행 엔드포인트를 만들지 않았다 — 만들면 그것이 곧 운영 경로가 되고, 아무도 계약을
합의하지 않은 채 발행 정책이 코드로 굳는다. PR8 의 `applyOutcome` 과 같은 상태다.

이 문서는 **그 합의가 필요한 지점 전부**를 적어 둔 것이다.

---

## 1. 스키마는 이미 고정돼 있다 — 무엇을 못 바꾸나

V45 는 dev 에 머지되면 Flyway checksum 때문에 **고칠 수 없다.** 발행 구현은 아래를
전제로 설계해야 한다.

### 1-1. 상태와 전이

```
DRAFT ──▶ REVIEW_READY ──▶ PUBLISHED     (PUBLISHED 는 terminal)
  └──────────┴──────────────▶ FAILED     (어느 단계에서든)
```

| 강제하는 것 | 어디서 |
|---|---|
| `status ∈ {DRAFT, REVIEW_READY, PUBLISHED, FAILED}` | `ck_member_published_reports_status` |
| `(status = 'PUBLISHED') = (published_at IS NOT NULL)` | `ck_member_published_reports_published` |
| `status = 'FAILED' OR failure_reason IS NULL` | `ck_member_published_reports_failure` |
| 🔴 **`PUBLISHED` 행은 UPDATE 가 0행이다** | `member_published_reports_member_teacher_update` 의 **USING** 에 `status <> 'PUBLISHED'` |

🔴 **USING 과 WITH CHECK 가 다르다.** USING 은 고치기 **전** 행, WITH CHECK 는 고친 **뒤**
행이다. 그래서 `REVIEW_READY → PUBLISHED` 는 통과하고 `PUBLISHED → 무엇이든` 은 0행이다.
🔴 **0행은 예외가 아니다.** 발행 구현은 `update()` 의 반환값을 확인해야 한다 — 안 보면
「고쳤다고 생각했는데 안 고쳐진」 상태로 조용히 지나간다.

### 1-2. 정정은 UPDATE 가 아니라 **새 revision**

```
uq_member_published_reports_revision UNIQUE (student_id, teacher_id, report_month, revision)
```

같은 달을 다시 내려면 `revision + 1` 의 **새 행**을 발행한다. 파일 행도 새로 생긴다
(`uq_member_report_files_report UNIQUE (report_id)`). 🔴 **기존 파일 행을 UPDATE 하지
않는다** — 학부모가 이미 받은 URL 이 가리키는 바이트가 소리 없이 바뀌면 안 된다.

🔴 **미해결**: 이전 revision 을 학부모 목록에서 계속 보일 것인가. 현재 구현은 **전부
보인다**(목록이 revision 을 거르지 않는다). → **MB-10**

### 1-3. 부모-자식은 복합 FK 로 묶여 있다

자식 세 테이블은 `student_id` · `teacher_id` · `published_at` 을 **비정규화해서 들고 있고**,
셋 다 부모와 복합 FK 로 묶인다:

```
FOREIGN KEY (report_id, student_id)   REFERENCES member_published_reports (id, student_id)
FOREIGN KEY (report_id, teacher_id)   REFERENCES member_published_reports (id, teacher_id)
FOREIGN KEY (report_id, published_at) REFERENCES member_published_reports (id, published_at)
```

🔴 **왜 비정규화했나** — 자식 테이블의 RLS 술어가 부모를 `EXISTS` 로 참조할 수 없다
(설계 불변식 4 · PR2 가 실측한 무한 재귀). 술어에 필요한 값을 자기 컬럼으로 들고 있어야
하는데, 그 값이 부모와 갈리면 미발행 은닉이 무너진다. **복합 FK 가 갈림 자체를 INSERT
실패로 만든다.**

🔴 **발행 구현에 주는 제약** — `published_at` 을 자식에 쓸 때 **부모와 정확히 같은 값**을
써야 한다. `Instant.now()` 를 두 번 뜨면 FK 위반이다. 순서도 정해져 있다:

```
① 부모 UPDATE: status = 'PUBLISHED', published_at = T
② 자식 UPDATE: published_at = T          ← 같은 T
③ outbox INSERT: published_at = T        ← 같은 T
```

②를 ①보다 먼저 하면 부모에 `(id, T)` 가 없어 FK 위반이다.

### 1-4. 미발행 은닉이 스키마에 박혀 있다

| 무엇 | 어떻게 |
|---|---|
| 부모 | 학부모 SELECT 술어에 `status = 'PUBLISHED'` |
| 섹션·파일 | 학부모 SELECT 술어에 `published_at IS NOT NULL` (부모 값과 복합 FK 로 고정) |
| outbox | `published_at` 이 **NOT NULL** — 미발행 보고서의 행이 물리적으로 못 들어온다 |

🔴 **필터 한 줄이 아니다.** 애플리케이션 WHERE 절을 전부 지워도 학부모 컨텍스트에서는
0건이다(실증: `ReportRlsIntegrationTest#unpublishedIsInvisibleAtSqlLayer`).

---

## 2. 합의가 필요한 것 — 항목별

### 2-1. 발행 API 경로 후보

계약(`member-api.yaml`) **46개 오퍼레이션에 발행 API 가 없다.** 넣으려면 계약을 먼저 고치고
`MemberImplementedApiOpenApiContractTest.CONTRACT_OPERATION_COUNT` 를 같은 커밋에서 올린다.

| 후보 | 장점 | 단점 |
|---|---|---|
| `POST /api/v1/member/teachers/me/students/{studentId}/reports` | member 경계 안, RLS 정책이 이미 `current_checkon_teacher_id()` 기준 | 🔴 member 에 강사 sub-context 를 여는 **결정**이 필요하다(MB-35 와 같은 종류) |
| `roster`·`dashboard` 쪽 신규 경로 | 강사 화면과 같은 경계 | 🔴 member 가 만들 수 없다(무접촉). 승우님 작업 |
| 배치/운영 스크립트 | API 없이 시작 가능 | 감사 로그·멱등·권한이 전부 수작업 |

🔴 **member 가 단독으로 못 정한다.** → **MB-11**

### 2-2. 누가 `status` 를 `PUBLISHED` 로 올리고 `published_at` 을 쓰는가

전제: RLS 정책상 **강사 컨텍스트**(`checkon.current_teacher_id`)가 열려야 한다.
🔴 **member 는 그 컨텍스트를 절대 열지 않는다**(CLAUDE.md 절대 규칙 3 · 설계 불변식 1).
즉 발행을 member 안에서 하려면 그 규칙을 깨거나, member 강사 경계용 새 주체 함수를
도입해야 한다 — **둘 다 승인 안건이다.**

### 2-3. outbox INSERT 를 발행과 **같은 트랜잭션**에 둔다

```
BEGIN
  UPDATE member_published_reports  ... status='PUBLISHED', published_at=T
  UPDATE member_published_report_sections ... published_at=T
  UPDATE member_report_files ... published_at=T
  INSERT INTO member_report_publication_outbox ... status='PENDING', published_at=T
COMMIT
```

🔴 **별도 트랜잭션이면 발행이 롤백돼도 알림이 남는다** — 학부모가 「존재하지 않는 보고서」
알림을 받는다. PR6 `NotificationPort` 주석이 같은 규약을 이미 적었다.

🔴 반대로 **알림 발행 자체는 발행 트랜잭션에 넣지 않는다.** outbox 까지가 발행이고,
실제 알림은 학부모 진입 시 인라인 drain 이 만든다(`ReportPublicationNotifier`).
이유는 수신자가 학부모라 **학부모 컨텍스트가 필요**하기 때문이다 — 강사 트랜잭션에서는
`member_notifications` INSERT 정책(`current_checkon_account_id() IS NOT NULL`)을 만족시켜도
수신자 계정을 강사가 알 수 없다.

### 2-4. PDF 업로드 주체와 `object_key` 네이밍

| 항목 | 상태 |
|---|---|
| 업로드 주체 | **미정** — member 는 PDF 를 만들지도 올리지도 않는다 |
| `object_key` 형식 | **미정.** 🔴 지어내지 않았다. 제안: `<report_month>/<report_id>-r<revision>.pdf` — 🔴 **실명·별칭·공개 학생 ID 를 넣지 마라**(키가 로그·버킷 목록에 남는다) |
| 버킷·엔드포인트·리전·자격증명 | **미정** — 환경변수 이름만 등재했다 → **MB-52** |
| 저장소 구현 | `LocalFileObjectStorageAdapter` 하나. S3 는 SDK 의존성이 필요하고 `build.gradle` 무접촉 |

🔴 `object_key` 는 **응답 DTO 에 담을 필드 자체가 없다**(`ReportFileAccessResponse`).
발행 구현이 이 값을 클라이언트에 되돌려주려 하면 타입을 고쳐야 하고, 그 diff 는 반려다.

### 2-5. `checksum` 과 `page_count` 를 누가 계산해 넘기는가

| 컬럼 | 규약 | 현재 |
|---|---|---|
| `checksum` | `^sha256:[0-9a-f]{64}$` (V32·PR2 와 같은 규약). **NOT NULL** | 🔴 **발행자가 계산해 넘겨야 한다.** member 는 발급 시점에 저장소 바이트로 **재계산해 대조**만 한다 |
| `size_bytes` | `> 0`. **NOT NULL** | 위와 같다. `head()` 결과와 다르면 발급하지 않고 `503` |
| `page_count` | `NULL` 이거나 `>= 1` | 🔴 **항상 `null` 이다.** PDF 파서 의존성이 0건이다 → **MB-55** |

🔴 **member 는 fail-closed 다.** 저장된 바이트의 sha256 이 DB 값과 다르면 **URL 을
발급하지 않고** `503` 이다. 상한(`max-verify-bytes`, 기본 20MB)을 넘으면 검증할 수 없으므로
**역시 발급하지 않는다** — 검증을 건너뛰고 주는 분기는 코드에 없다.

→ 발행 구현이 `checksum` 을 틀리게 넣으면 **그 보고서의 PDF 는 영원히 503 이다.**
정정은 새 revision 이므로 잘못된 파일 행을 UPDATE 할 수도 없다. 계산을 정확히 하라.

### 2-6. 섹션 `kind` 어휘와 `evidence_refs` 필수 여부

🔴 **V45 는 `kind` 에 CHECK 어휘를 만들지 않았다.** 계약의 `kind` 가 enum 없는 문자열이고
설계 정본에도 목록이 없기 때문이다 — 없는 어휘를 지어내면 발행자가 그 목록 밖 섹션을
넣지 못한다. 대신 두 CHECK 가 정직성을 지킨다:

```sql
CHECK ((status = 'NOT_PRODUCED') = (unproduced_reason IS NOT NULL))
CHECK (status <> 'AVAILABLE' OR body IS NOT NULL OR content IS NOT NULL)
```

즉 **`NOT_PRODUCED` 인데 사유가 없거나 `AVAILABLE` 인데 내용이 둘 다 비면 INSERT 가
실패한다.** 필터 실수가 아니라 쓰기 실패로 드러난다.

`evidence_refs` 는 배열 CHECK 만 있고 **비어 있어도 통과한다.** CheckOn-AI `CLAUDE.md`
불변식 2「evidence 없는 산출물 금지」와 맞출지가 합의 사항이다 → **MB-53**

### 2-7. 🔴 전국 백분위 섹션을 만들지 마라

`CheckOn-AI/src/ai/report/data/unproduced_metrics.yaml`:

```yaml
national_percentile:
  reason: BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음
```

그리고 `CheckOn-AI/src/ai/report/assembler.py:126` 이 `unproduced=tuple(ReportUnproducedMetric)`
로 전량 미산출 처리한다. 🔴 **응답 스키마에도 `percentile` 필드가 없다**(실증:
`ReportIntegrationTest#noNationalPercentileField` 가 상세 응답 전체 문자열에서 0회를 단언).

발행 시 이 항목을 넣어야 한다면 `status = 'NOT_PRODUCED'` + 위 `reason` **원문 그대로**를
`unproduced_reason` 에 넣는다. 요약하거나 다른 말로 바꾸지 마라.

---

## 3. 읽기 쪽이 이미 보장하는 것 — 발행 구현이 다시 하지 않아도 되는 것

| 보장 | 어디서 |
|---|---|
| 미발행은 학부모에게 목록·상세·PDF 전부 404 | V45 정책 + 리포지토리 WHERE (두 겹) |
| 연결 안 된 강사의 보고서는 404 | `ParentChildAccessGuard` — 매 요청 `parent↔student` + `student↔teacher` 교집합 재검증 |
| PDF URL 은 수명이 짧고 추측 불가 | `ReportFileTokenCodec` — HMAC-SHA256 + 128비트 nonce + 주입 `Clock` 만료 |
| 유출 URL 이라도 관계가 끊기면 즉시 404 | `ReportFileDownloadService` — 다운로드 시점 관계 재검증 |
| 바이트가 바뀐 파일에는 URL 을 주지 않는다 | `ReportFileAccessService` — 발급 전 sha256 재계산 대조 |
| 발행 알림은 같은 원본으로 두 번 안 간다 | `uq_member_notifications_source` + `ON CONFLICT DO NOTHING` |

---

## 4. 🔴 발행 구현이 반드시 지켜야 할 다섯 줄

1. `published_at` 은 **한 번만 뜨고** 부모·자식·outbox 에 **같은 값**을 쓴다.
2. 부모 → 자식 → outbox **순서**로 쓴다. 뒤집으면 복합 FK 위반이다.
3. `PUBLISHED` 행을 UPDATE 하려 들지 마라. 정정은 `revision + 1` 의 새 행이다.
4. `UPDATE` 의 **반환 행 수를 확인**하라. RLS 는 예외가 아니라 0행으로 거절한다.
5. `checksum` 을 정확히 계산하라. 틀리면 그 PDF 는 영원히 `503` 이고 고칠 방법이 없다.

---

## 5. 관련 안건

| ID | 무엇 |
|---|---|
| **MB-10** | PDF 보존 기간 · 공유 링크 · 이전 revision 노출 정책 |
| **MB-11** | 발행 주체와 transport (AI `/v1/reports` 는 인메모리라 원장이 될 수 없다) |
| **MB-52** | object storage 운영값 (버킷·엔드포인트·리전·자격증명) |
| **MB-53** | 섹션 `kind` 어휘 · `AVAILABLE` 섹션의 `evidence_refs` 필수 여부 |
| **MB-54** | outbox 운영 창 (`@Scheduled` 미등록) |
| **MB-55** | `page_count` 원본 부재 → 항상 `null` |
| **MB-56** | `student↔teacher` 를 `ACTIVE` 로만 본다 (PAUSED 제외) |
| **MB-57** | 앱 계층 `PUBLISHED` 필터를 단독으로 잡는 테스트 없음 |
