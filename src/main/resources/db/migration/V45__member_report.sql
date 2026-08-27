-- V45 — member 월별 보고서 발행 스냅샷(1) · 섹션(1) · PDF 파일(1) · 발행 outbox(1) 총 4테이블.
--
-- 이 파일이 만드는 것:
--   1. member_published_reports            발행 스냅샷 원장 (강사가 확정한 그 달의 값)
--   2. member_published_report_sections    발행 후 불변인 표시 스냅샷
--   3. member_report_files                 발행된 PDF 의 메타데이터 (바이트는 object storage)
--   4. member_report_publication_outbox    발행 알림 발행 대기열
--   5. 네 테이블 전부 ENABLE + FORCE RLS + 신규 정책 15개
--
-- 🔴 기존(승우님) 테이블 정책 변경 0건. 기존 테이블 ALTER 0건.
--    예약표 규칙 1 — 기존 테이블 정책 추가는 V38 뿐이다(예외 승인 1건: V40/MB-36).
--    🔴 V37 의 monthly_report_* (승우님 소유)에는 손대지 않는다. 이 PR 은 member 소유
--       신규 테이블로만 간다. 승우님 리포트 테이블을 읽어야 할 일이 생기면 승인 안건이다.
--
-- 🔴 모든 술어의 첫 조건은 current_checkon_*_id() IS NOT NULL. 이름에 _member_ 포함.
--    회귀 검증(R3)이 기준선 대비 삭제·변경만 red 로 잡는다. 순수 추가는 통과한다.
--
-- 🔴 불변식 4(설계 §6-4·§6-4-1) — 정책 술어에서 RLS 켜진 테이블을 EXISTS·IN·JOIN 으로
--    참조하지 않는다. 학부모의 교차 조회는 §6-4-2 범위 세션 변수
--    current_checkon_scope_student_id() 로 한다. 그 값은 MemberDatabaseContext
--    .withVerifiedChildScope 가 parent_student_relationships 를 학부모 컨텍스트로 먼저
--    읽어 확인한 뒤에만 열린다 — 확인을 건너뛸 문법적 방법이 없다.
--    🔴 PR9 지시서 §2 의 RLS 표는 부모 SELECT 술어에 parent_student_relationships 와
--       teacher_student_relationships 를 EXISTS 로 넣으라고 적었는데, 그것이 정확히
--       PR2 가 실측으로 무한 재귀를 낸 형태다. 지시서 대신 불변식 4 를 따랐다.
--
-- 🔴 부모-자식 owner 값이 갈리지 않도록 student/teacher/published_at 복합 FK 비정규화를
--    쓴다(V44 가 만든 선례 그대로). 자식 테이블은 부모를 EXISTS 로 참조할 수 없으므로
--    술어에 필요한 값을 자기 컬럼으로 들고 있어야 하는데, 그 값이 부모와 갈리면 은닉이
--    무너진다. 복합 FK 가 갈림 자체를 INSERT 실패로 만든다.
--
-- 🔴 미발행 은닉은 필터가 아니라 스키마와 정책이 보장한다:
--      · 부모 SELECT 술어에 status = 'PUBLISHED'
--      · 자식 SELECT 술어에 published_at IS NOT NULL  (부모의 published_at 을 복합 FK 로 고정)
--      · outbox 는 published_at 이 NOT NULL 이라 미발행 행이 물리적으로 들어올 수 없다
--    애플리케이션 WHERE 절이 지워져도 학부모 컨텍스트에서는 여전히 0건이다.
--
-- 🔴 PUBLISHED 는 terminal 이고 수정 불가다. 정정은 UPDATE 가 아니라 revision + 1 의
--    새 행 발행이고, 그래서 unique 키에 revision 이 들어간다. 강사 UPDATE 정책의 USING 에
--    status <> 'PUBLISHED' 를 두어 발행본 UPDATE 가 0행이 되게 한다 — USING 은 고치기 전
--    행을, WITH CHECK 는 고친 뒤 행을 본다. 둘을 같게 쓰면 REVIEW_READY → PUBLISHED 전이
--    자체가 막힌다.
--
-- 🔴 이 PR 에는 프로덕션 writer 가 없다. 행을 넣는 코드는 테스트 픽스처뿐이고 발행 경로
--    계약은 docs/MEMBER_REPORT_PUBLICATION_CONTRACT.md 로 넘긴다.
--
-- 🔴 kind 에 CHECK 어휘를 만들지 않는다 — 계약(member-api.yaml:2273)의 kind 는 enum 없는
--    type: string 이고 어휘 확정은 MB-53 이다. 없는 어휘를 지어내지 않는다.
-- 🔴 전국 백분위 섹션·필드를 만들지 않는다. CheckOn-AI 의
--    src/ai/report/data/unproduced_metrics.yaml 이 national_percentile.reason 을
--    「BE 비교집단 API·원천·모수·산식 계약이 확정되지 않음」으로 두고
--    src/ai/report/assembler.py:126 이 전량 미산출 처리한다.
--
-- TODO(MB-08): 관계 종료 후 과거 보고서 열람 정책이 미확정이다. 현재는 fail-closed 로
--              학부모 SELECT 가 범위 세션 변수에 걸려 0행이 된다. 값을 바꿀 지점은
--              ParentChildAccessGuard 한 곳이다.

-- ═════════════════════ 1. member_published_reports ═════════════════════
CREATE TABLE member_published_reports (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    student_id UUID NOT NULL REFERENCES student_profiles (id) ON DELETE RESTRICT,
    teacher_id UUID NOT NULL REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    report_month VARCHAR(7) NOT NULL,
    -- 🔴 그 달을 어느 zone 으로 잘랐는가. PR7 이 월 경계를 값으로 남긴 것과 같은 이유다
    --    (MB-07 이 아직 PROPOSED 라 코드 기본값이 바뀌면 옛 행의 뜻이 흔들린다).
    month_zone VARCHAR(64) NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(16) NOT NULL,
    snapshot_version VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    failure_reason VARCHAR(200),
    CONSTRAINT ck_member_published_reports_month
        CHECK (report_month ~ '^\d{4}-\d{2}$'),
    CONSTRAINT ck_member_published_reports_revision
        CHECK (revision >= 1),
    CONSTRAINT ck_member_published_reports_status
        CHECK (status IN ('DRAFT', 'REVIEW_READY', 'PUBLISHED', 'FAILED')),
    -- 🔴 status 와 published_at 이 갈리지 않는다. 발행 여부의 최종 보장은 여기다.
    CONSTRAINT ck_member_published_reports_published
        CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    CONSTRAINT ck_member_published_reports_failure
        CHECK (status = 'FAILED' OR failure_reason IS NULL),
    CONSTRAINT ck_member_published_reports_updated_at
        CHECK (updated_at >= created_at),
    -- 정정은 새 revision 이다. 같은 (학생, 강사, 달, revision) 은 한 번뿐이다.
    CONSTRAINT uq_member_published_reports_revision
        UNIQUE (student_id, teacher_id, report_month, revision),
    -- 복합 FK 비정규화용. 자식이 부모의 owner·발행시각과 갈리는 것을 막는다.
    CONSTRAINT uq_member_published_reports_student UNIQUE (id, student_id),
    CONSTRAINT uq_member_published_reports_teacher UNIQUE (id, teacher_id),
    CONSTRAINT uq_member_published_reports_published_at UNIQUE (id, published_at)
);

-- 목록 cursor 용. 정렬은 published_at DESC, id DESC (PR7 규약).
CREATE INDEX ix_member_published_reports_parent_page
    ON member_published_reports (student_id, teacher_id, published_at DESC, id DESC);
CREATE INDEX ix_member_published_reports_teacher_month
    ON member_published_reports (teacher_id, report_month, status);

-- ═════════════════════ 2. member_published_report_sections ═════════════════════
-- 🔴 status·body·content 의 정직성 장치가 CHECK 두 개다. NOT_PRODUCED 인데 사유가 없거나
--    AVAILABLE 인데 내용이 둘 다 비면 필터 실수가 아니라 INSERT 실패로 드러난다.
CREATE TABLE member_published_report_sections (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    report_id UUID NOT NULL,
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    -- 🔴 부모의 published_at 을 그대로 들고 온다. NULL 이면 미발행이라 학부모에게 안 보인다.
    published_at TIMESTAMPTZ,
    kind VARCHAR(40) NOT NULL,
    title VARCHAR(200),
    ordinal INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    body TEXT,
    -- 차트용 원시 값. 발행 후 불변인 표시 스냅샷이다(member-api.yaml:2276-2279).
    -- 🔴 상태·검색 키는 정규 컬럼이다. JSONB 는 표시 스냅샷에만 쓴다.
    content JSONB,
    evidence_refs JSONB NOT NULL DEFAULT '[]'::jsonb,
    unproduced_reason VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_published_report_sections_student
        FOREIGN KEY (report_id, student_id)
        REFERENCES member_published_reports (id, student_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_published_report_sections_teacher
        FOREIGN KEY (report_id, teacher_id)
        REFERENCES member_published_reports (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_published_report_sections_published
        FOREIGN KEY (report_id, published_at)
        REFERENCES member_published_reports (id, published_at) ON DELETE RESTRICT,
    CONSTRAINT ck_member_published_report_sections_status
        CHECK (status IN ('AVAILABLE', 'INSUFFICIENT', 'NO_DATA', 'NOT_PRODUCED')),
    CONSTRAINT ck_member_published_report_sections_ordinal
        CHECK (ordinal >= 0),
    CONSTRAINT ck_member_published_report_sections_evidence
        CHECK (jsonb_typeof(evidence_refs) = 'array'),
    CONSTRAINT ck_member_published_report_sections_unproduced
        CHECK ((status = 'NOT_PRODUCED') = (unproduced_reason IS NOT NULL)),
    CONSTRAINT ck_member_published_report_sections_available
        CHECK (status <> 'AVAILABLE' OR body IS NOT NULL OR content IS NOT NULL),
    CONSTRAINT uq_member_published_report_sections_kind UNIQUE (report_id, kind)
);

CREATE INDEX ix_member_published_report_sections_order
    ON member_published_report_sections (report_id, ordinal);

-- ═════════════════════ 3. member_report_files ═════════════════════
-- 🔴 member 는 PDF 를 만들지 않는다. 발행된 파일을 참조하고 서명 URL 을 발급할 뿐이다.
--    바이트 파서 의존성이 없고 build.gradle 은 무접촉이라 page_count 는 발행자가 준 값이
--    없으면 null 이다 — 세어 보지 않은 숫자를 넣지 않는다(MB-55).
-- 🔴 object_key 를 응답 DTO 에 담는 필드는 존재하지 않는다. 비우는 게 아니라 타입에 없다.
CREATE TABLE member_report_files (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    report_id UUID NOT NULL,
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    published_at TIMESTAMPTZ,
    object_key VARCHAR(512) NOT NULL,
    checksum VARCHAR(71) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    page_count INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_report_files_student
        FOREIGN KEY (report_id, student_id)
        REFERENCES member_published_reports (id, student_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_report_files_teacher
        FOREIGN KEY (report_id, teacher_id)
        REFERENCES member_published_reports (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_report_files_published
        FOREIGN KEY (report_id, published_at)
        REFERENCES member_published_reports (id, published_at) ON DELETE RESTRICT,
    -- 보고서 하나에 파일 하나. revision 이 올라가면 보고서 행이 새로 생기므로 파일 행도
    -- 새로 생긴다 — 기존 파일 행을 UPDATE 하지 않는다.
    CONSTRAINT uq_member_report_files_report UNIQUE (report_id),
    -- V32·PR2 와 같은 checksum 규약.
    CONSTRAINT ck_member_report_files_checksum
        CHECK (checksum ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_member_report_files_content_type
        CHECK (content_type = 'application/pdf'),
    CONSTRAINT ck_member_report_files_size
        CHECK (size_bytes > 0),
    CONSTRAINT ck_member_report_files_page_count
        CHECK (page_count IS NULL OR page_count >= 1)
);

-- ═════════════════════ 4. member_report_publication_outbox ═════════════════════
-- 🔴 published_at 이 NOT NULL 이라 미발행 보고서의 outbox 행은 물리적으로 들어올 수 없다
--    (복합 FK 가 부모의 published_at 과 일치를 강제한다). V44 의 messages.published_at
--    NOT NULL 과 같은 수법이다 — 거르는 것보다 못 들어오게 만드는 쪽이 낫다.
CREATE TABLE member_report_publication_outbox (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    report_id UUID NOT NULL,
    student_id UUID NOT NULL,
    teacher_id UUID NOT NULL,
    published_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT fk_member_report_publication_outbox_student
        FOREIGN KEY (report_id, student_id)
        REFERENCES member_published_reports (id, student_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_report_publication_outbox_teacher
        FOREIGN KEY (report_id, teacher_id)
        REFERENCES member_published_reports (id, teacher_id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_report_publication_outbox_published
        FOREIGN KEY (report_id, published_at)
        REFERENCES member_published_reports (id, published_at) ON DELETE RESTRICT,
    CONSTRAINT uq_member_report_publication_outbox_report UNIQUE (report_id),
    CONSTRAINT ck_member_report_publication_outbox_status
        CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT ck_member_report_publication_outbox_attempts
        CHECK (attempt_count >= 0)
);

CREATE INDEX ix_member_report_publication_outbox_pending
    ON member_report_publication_outbox (student_id, status, created_at);

-- ═════════════════════ 5. RLS ═════════════════════
ALTER TABLE member_published_reports ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_published_reports FORCE ROW LEVEL SECURITY;
ALTER TABLE member_published_report_sections ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_published_report_sections FORCE ROW LEVEL SECURITY;
ALTER TABLE member_report_files ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_report_files FORCE ROW LEVEL SECURITY;
ALTER TABLE member_report_publication_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_report_publication_outbox FORCE ROW LEVEL SECURITY;

-- ── 5-1. member_published_reports ──
-- 🔴 학부모 술어에 status = 'PUBLISHED' 를 넣는 이유: 애플리케이션 WHERE 절이 지워져도
--    학부모 컨텍스트에서는 여전히 0건이다. 필터가 두 겹인 것을 알고 쓴다 — 앱 필터 단독을
--    잡는 테스트는 없다(MB-57).
CREATE POLICY member_published_reports_member_parent_scope_select
    ON member_published_reports
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
        AND status = 'PUBLISHED'
    );

CREATE POLICY member_published_reports_member_teacher_select
    ON member_published_reports
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_published_reports_member_teacher_insert
    ON member_published_reports
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- 🔴 USING 과 WITH CHECK 를 같게 쓰지 마라. USING 은 고치기 전 행, WITH CHECK 는 고친 뒤
--    행이다. USING 에 status <> 'PUBLISHED' 를 두면 REVIEW_READY → PUBLISHED 는 통과하고
--    PUBLISHED → 무엇이든 은 0행이 된다. 이게 불변성의 1차 방어다.
CREATE POLICY member_published_reports_member_teacher_update
    ON member_published_reports
    FOR UPDATE USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND status <> 'PUBLISHED'
    ) WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 5-2. member_published_report_sections ──
CREATE POLICY member_published_report_sections_member_parent_select
    ON member_published_report_sections
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
        AND published_at IS NOT NULL
    );

CREATE POLICY member_published_report_sections_member_teacher_select
    ON member_published_report_sections
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_published_report_sections_member_teacher_insert
    ON member_published_report_sections
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND published_at IS NULL
    );

CREATE POLICY member_published_report_sections_member_teacher_update
    ON member_published_report_sections
    FOR UPDATE USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND published_at IS NULL
    ) WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 5-3. member_report_files ──
CREATE POLICY member_report_files_member_parent_select
    ON member_report_files
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
        AND published_at IS NOT NULL
    );

CREATE POLICY member_report_files_member_teacher_select
    ON member_report_files
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

CREATE POLICY member_report_files_member_teacher_insert
    ON member_report_files
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND published_at IS NULL
    );

CREATE POLICY member_report_files_member_teacher_update
    ON member_report_files
    FOR UPDATE USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND published_at IS NULL
    ) WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 5-4. member_report_publication_outbox ──
-- 🔴 학부모가 자기 자녀의 PENDING 행을 소비한다(설계 §11-1 인라인 drain). 발행 주체와
--    수신자가 달라서 학부모 컨텍스트에서 도는 것이고, member_notifications 의 INSERT 정책이
--    current_checkon_account_id() IS NOT NULL 하나뿐이라 가능하다(V41 §5-4). 그 정책을
--    좁히는 것은 PR6 의 open item 이고 여기서 기존 정책을 건드리지 않는다.
CREATE POLICY member_report_publication_outbox_member_teacher_insert
    ON member_report_publication_outbox
    FOR INSERT WITH CHECK (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
        AND status = 'PENDING'
    );

CREATE POLICY member_report_publication_outbox_member_parent_select
    ON member_report_publication_outbox
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );

CREATE POLICY member_report_publication_outbox_member_parent_update
    ON member_report_publication_outbox
    FOR UPDATE USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    ) WITH CHECK (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );

COMMENT ON TABLE member_published_reports IS
    'Teacher-confirmed monthly report snapshot. PUBLISHED is terminal; corrections are new revisions.';
COMMENT ON TABLE member_published_report_sections IS
    'Display snapshot frozen at publication. Never recomputed at read time.';
COMMENT ON TABLE member_report_files IS
    'PDF metadata only. member does not produce PDFs; object bytes live in object storage.';
COMMENT ON TABLE member_report_publication_outbox IS
    'Publication notification queue. Rows exist only for published reports.';
