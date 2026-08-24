CREATE TABLE alert_follow_up_todos (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    kind VARCHAR(30) NOT NULL,
    alert_id UUID NOT NULL,
    text TEXT NOT NULL,
    due_date DATE NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_alert_follow_up_todo_teacher FOREIGN KEY (teacher_id) REFERENCES teacher_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_alert_follow_up_todo_alert FOREIGN KEY (alert_id) REFERENCES engagement_alerts(id) ON DELETE RESTRICT,
    CONSTRAINT uq_alert_follow_up_todo_alert_kind UNIQUE (alert_id, kind),
    CONSTRAINT ck_alert_follow_up_todo_kind CHECK (kind = 'ALERT_FOLLOW_UP'),
    CONSTRAINT ck_alert_follow_up_todo_text CHECK (btrim(text) <> ''),
    CONSTRAINT ck_alert_follow_up_todo_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT ck_alert_follow_up_todo_completion CHECK (
        (status = 'OPEN' AND completed_at IS NULL)
        OR (status = 'DONE' AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_alert_follow_up_todo_time CHECK (
        updated_at >= created_at
        AND (completed_at IS NULL OR completed_at >= created_at)
    )
);

CREATE FUNCTION validate_alert_follow_up_todo_alert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM engagement_alerts alert
        WHERE alert.id = NEW.alert_id AND alert.teacher_id = NEW.teacher_id
    ) THEN
        RAISE EXCEPTION 'todo requires a tenant-owned alert';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_alert_follow_up_todo_alert
BEFORE INSERT OR UPDATE OF teacher_id, alert_id ON alert_follow_up_todos
FOR EACH ROW EXECUTE FUNCTION validate_alert_follow_up_todo_alert();

INSERT INTO alert_follow_up_todos (
    teacher_id, kind, alert_id, text, due_date, status,
    completed_at, created_at, updated_at
)
SELECT alert.teacher_id, 'ALERT_FOLLOW_UP', alert.id,
       '확인이 필요한 경보를 검토하고 상담 여부를 결정해 주세요.',
       (alert.created_at AT TIME ZONE 'Asia/Seoul')::date,
       'OPEN', NULL, alert.created_at, alert.created_at
FROM engagement_alerts alert
WHERE alert.status = 'PENDING_REVIEW'
ON CONFLICT (alert_id, kind) DO NOTHING;

CREATE INDEX idx_alert_follow_up_todo_open_due
ON alert_follow_up_todos(teacher_id, due_date, created_at, id)
WHERE status = 'OPEN';

ALTER TABLE alert_follow_up_todos ENABLE ROW LEVEL SECURITY;
ALTER TABLE alert_follow_up_todos FORCE ROW LEVEL SECURITY;
CREATE POLICY alert_follow_up_todo_select ON alert_follow_up_todos FOR SELECT
USING (teacher_id = current_checkon_teacher_id());
CREATE POLICY alert_follow_up_todo_insert ON alert_follow_up_todos FOR INSERT
WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY alert_follow_up_todo_update ON alert_follow_up_todos FOR UPDATE
USING (teacher_id = current_checkon_teacher_id())
WITH CHECK (teacher_id = current_checkon_teacher_id());
CREATE POLICY alert_follow_up_todo_delete ON alert_follow_up_todos FOR DELETE
USING (teacher_id = current_checkon_teacher_id());

