-- V42 — 월별 결정론 집계 3테이블.
--
-- 🔴 이 파일이 만드는 것:
--   1. member_monthly_student_metrics    (§1)
--   2. member_monthly_weakness_metrics   (§2)
--   3. member_metric_refresh_outbox      (§3)
--   4. 세 테이블 전부 ENABLE + FORCE RLS + 정책 (§4)
--
-- 🔴 member_learning_sessions 는 V40 이 이미 만들었다. 여기서 만들지 않는다
--    (착수 시점 psql 실측: to_regclass('public.member_learning_sessions')='member_learning_sessions').
--
-- 🔴 기존(승우님) 테이블 정책 변경 0건. 기존 테이블 ALTER 0건.
--    예약표 규칙 1 — 기존 테이블 정책 추가는 V38 뿐이다.
--
-- 🔴 모든 술어의 첫 조건은 current_checkon_*_id() IS NOT NULL. 이름에 _member_ 포함.
--    회귀 R3 이 정책 문자열을 그대로 비교하므로 규칙을 어기면 오진된다.
--
-- 🔴 accuracy_rate 컬럼을 만들지 않는다. 비율은 correct_count/scored_count 로 읽는 쪽에서
--    계산한다. 값과 그 유도식이 두 곳에 있으면 언젠가 갈라진다.
--
-- 🔴 status 어휘는 AVAILABLE | NO_DATA 둘뿐 — 셀 자체의 데이터 유무.
--    개선도의 NO_PREVIOUS_PERIOD · INSUFFICIENT_SAMPLE 은 저장하지 않는다.
--    설정(minimumSampleSize)이 바뀌면 저장값이 거짓이 된다.

-- ═════════════════════ 1. member_monthly_student_metrics ═════════════════════
CREATE TABLE member_monthly_student_metrics (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    month VARCHAR(7) NOT NULL,
    month_zone VARCHAR(64) NOT NULL,
    scored_count INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    total_active_sec INTEGER NOT NULL DEFAULT 0,
    calculation_version VARCHAR(20) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_monthly_student_metrics_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_monthly_student_metrics_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_monthly_student_metrics
        UNIQUE (teacher_id, student_id, month),
    CONSTRAINT ck_member_monthly_student_metrics_month
        CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_member_monthly_student_metrics_counts
        CHECK (scored_count >= 0
               AND correct_count BETWEEN 0 AND scored_count
               AND total_active_sec >= 0)
);

CREATE INDEX ix_member_monthly_student_metrics_student_month
    ON member_monthly_student_metrics (student_id, month);
CREATE INDEX ix_member_monthly_student_metrics_teacher_student_month
    ON member_monthly_student_metrics (teacher_id, student_id, month);

-- ═════════════════════ 2. member_monthly_weakness_metrics ═════════════════════
-- 셀 = (teacher_id, student_id, month, area_tag, type_tag). 태그는 소문자 정본(설계 §1-4 ④).
CREATE TABLE member_monthly_weakness_metrics (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    month VARCHAR(7) NOT NULL,
    month_zone VARCHAR(64) NOT NULL,
    area_tag VARCHAR(32) NOT NULL,
    type_tag VARCHAR(32) NOT NULL,
    scored_count INTEGER NOT NULL,
    correct_count INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    calculation_version VARCHAR(20) NOT NULL,
    calculated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_member_monthly_weakness_metrics_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_monthly_weakness_metrics_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT uq_member_monthly_weakness_metrics
        UNIQUE (teacher_id, student_id, month, area_tag, type_tag),
    CONSTRAINT ck_member_monthly_weakness_metrics_month
        CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_member_monthly_weakness_metrics_counts
        CHECK (scored_count >= 0 AND correct_count BETWEEN 0 AND scored_count),
    CONSTRAINT ck_member_monthly_weakness_metrics_status
        CHECK (status IN ('AVAILABLE', 'NO_DATA')),
    CONSTRAINT ck_member_monthly_weakness_metrics_area
        CHECK (area_tag IN ('language','media','literature','reading','speech_writing')),
    CONSTRAINT ck_member_monthly_weakness_metrics_type
        CHECK (type_tag IN ('fact','infer','critic','concept'))
);

CREATE INDEX ix_member_monthly_weakness_metrics_student_month
    ON member_monthly_weakness_metrics (student_id, month);
CREATE INDEX ix_member_monthly_weakness_metrics_teacher_student_month
    ON member_monthly_weakness_metrics (teacher_id, student_id, month);

-- ═════════════════════ 3. member_metric_refresh_outbox ═════════════════════
-- 🔴 부분 unique — 같은 (teacher_id, student_id, month) 의 PENDING 은 한 행만.
--    학생이 같은 달을 여러 번 제출해도 outbox 는 조용히 합쳐진다.
CREATE TABLE member_metric_refresh_outbox (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    month VARCHAR(7) NOT NULL,
    month_zone VARCHAR(64) NOT NULL,
    reason VARCHAR(40) NOT NULL,
    source_ref VARCHAR(255),
    status VARCHAR(20) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    CONSTRAINT fk_member_metric_refresh_outbox_teacher
        FOREIGN KEY (teacher_id) REFERENCES teacher_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT fk_member_metric_refresh_outbox_student
        FOREIGN KEY (student_id) REFERENCES student_profiles (id) ON DELETE RESTRICT,
    CONSTRAINT ck_member_metric_refresh_outbox_month
        CHECK (month ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT ck_member_metric_refresh_outbox_status
        CHECK (status IN ('PENDING', 'DONE', 'FAILED')),
    CONSTRAINT ck_member_metric_refresh_outbox_attempt_count
        CHECK (attempt_count >= 0)
);

CREATE UNIQUE INDEX uq_member_metric_refresh_outbox_pending
    ON member_metric_refresh_outbox (teacher_id, student_id, month)
    WHERE status = 'PENDING';
CREATE INDEX ix_member_metric_refresh_outbox_student_status
    ON member_metric_refresh_outbox (student_id, status);

-- ═════════════════════ 4. RLS ═════════════════════
ALTER TABLE member_monthly_student_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_monthly_student_metrics FORCE ROW LEVEL SECURITY;
ALTER TABLE member_monthly_weakness_metrics ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_monthly_weakness_metrics FORCE ROW LEVEL SECURITY;
ALTER TABLE member_metric_refresh_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE member_metric_refresh_outbox FORCE ROW LEVEL SECURITY;

-- ── 4-1. member_monthly_student_metrics ──
-- 학생 self SELECT/INSERT/UPDATE. 학부모 활성 자녀 SELECT (§6-4-2 scope). 강사 SELECT.
CREATE POLICY member_monthly_student_metrics_member_student_select
    ON member_monthly_student_metrics
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_student_metrics_member_student_insert
    ON member_monthly_student_metrics
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_student_metrics_member_student_update
    ON member_monthly_student_metrics
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_student_metrics_member_parent_scope_select
    ON member_monthly_student_metrics
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
CREATE POLICY member_monthly_student_metrics_member_teacher_select
    ON member_monthly_student_metrics
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 4-2. member_monthly_weakness_metrics ──
CREATE POLICY member_monthly_weakness_metrics_member_student_select
    ON member_monthly_weakness_metrics
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_weakness_metrics_member_student_insert
    ON member_monthly_weakness_metrics
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_weakness_metrics_member_student_update
    ON member_monthly_weakness_metrics
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_monthly_weakness_metrics_member_parent_scope_select
    ON member_monthly_weakness_metrics
    FOR SELECT USING (
        current_checkon_parent_id() IS NOT NULL
        AND current_checkon_scope_student_id() IS NOT NULL
        AND student_id = current_checkon_scope_student_id()
    );
CREATE POLICY member_monthly_weakness_metrics_member_teacher_select
    ON member_monthly_weakness_metrics
    FOR SELECT USING (
        current_checkon_teacher_id() IS NOT NULL
        AND teacher_id = current_checkon_teacher_id()
    );

-- ── 4-3. member_metric_refresh_outbox ──
-- 학생 self SELECT/INSERT/UPDATE 만. 학부모·강사 정책은 만들지 않는다.
CREATE POLICY member_metric_refresh_outbox_member_student_select
    ON member_metric_refresh_outbox
    FOR SELECT USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_metric_refresh_outbox_member_student_insert
    ON member_metric_refresh_outbox
    FOR INSERT WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
CREATE POLICY member_metric_refresh_outbox_member_student_update
    ON member_metric_refresh_outbox
    FOR UPDATE USING (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    ) WITH CHECK (
        current_checkon_student_id() IS NOT NULL
        AND student_id = current_checkon_student_id()
    );
