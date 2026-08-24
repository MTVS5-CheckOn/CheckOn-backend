CREATE TABLE engagement_alerts (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    teacher_id UUID NOT NULL,
    student_id UUID NOT NULL,
    detection_signal_result_id UUID NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING_REVIEW',
    decision_note TEXT,
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_engagement_alert_teacher FOREIGN KEY (teacher_id) REFERENCES teacher_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_engagement_alert_student FOREIGN KEY (student_id) REFERENCES student_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_engagement_alert_signal FOREIGN KEY (detection_signal_result_id) REFERENCES detection_signal_results(id) ON DELETE RESTRICT,
    CONSTRAINT uq_engagement_alert_signal UNIQUE (detection_signal_result_id),
    CONSTRAINT ck_engagement_alert_status CHECK (status IN ('PENDING_REVIEW','APPROVED','REJECTED')),
    CONSTRAINT ck_engagement_alert_decision CHECK (
        (status = 'PENDING_REVIEW' AND decision_note IS NULL AND decided_at IS NULL)
        OR (status = 'APPROVED' AND decided_at IS NOT NULL)
        OR (status = 'REJECTED' AND decision_note IS NOT NULL AND btrim(decision_note) <> '' AND decided_at IS NOT NULL)
    ),
    CONSTRAINT ck_engagement_alert_time CHECK (updated_at >= created_at AND (decided_at IS NULL OR decided_at >= created_at))
);

-- A plain FK proves only that the signal exists. This trigger also proves that
-- the signal belongs to this teacher and already has evidence before review.
CREATE FUNCTION validate_engagement_alert_source() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM detection_signal_results s
        JOIN detection_runs r ON r.id = s.detection_run_id
        JOIN detection_result_evidence e ON e.detection_signal_result_id = s.id
        JOIN ai_student_aliases a
          ON a.teacher_id = r.teacher_id
         AND a.alias = s.student_ref
        WHERE s.id = NEW.detection_signal_result_id
          AND r.teacher_id = NEW.teacher_id
          AND a.student_id = NEW.student_id
    ) THEN
        RAISE EXCEPTION 'engagement alert requires tenant-owned detection evidence';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_engagement_alert_source
BEFORE INSERT OR UPDATE OF teacher_id, detection_signal_result_id ON engagement_alerts
FOR EACH ROW EXECUTE FUNCTION validate_engagement_alert_source();

CREATE TABLE interventions (
    id UUID PRIMARY KEY DEFAULT uuidv7(), teacher_id UUID NOT NULL, student_id UUID NOT NULL,
    alert_id UUID NOT NULL, type VARCHAR(40) NOT NULL, content TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'OPEN', completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_intervention_teacher FOREIGN KEY (teacher_id) REFERENCES teacher_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_intervention_student FOREIGN KEY (student_id) REFERENCES student_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_intervention_alert FOREIGN KEY (alert_id) REFERENCES engagement_alerts(id) ON DELETE RESTRICT,
    CONSTRAINT ck_intervention_type CHECK (btrim(type) <> ''),
    CONSTRAINT ck_intervention_content CHECK (btrim(content) <> ''),
    CONSTRAINT ck_intervention_status CHECK (status IN ('OPEN','COMPLETED','CANCELLED')),
    CONSTRAINT ck_intervention_completion CHECK ((status = 'COMPLETED') = (completed_at IS NOT NULL)),
    CONSTRAINT ck_intervention_time CHECK (updated_at >= created_at AND (completed_at IS NULL OR completed_at >= created_at))
);

CREATE FUNCTION validate_intervention_alert() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM engagement_alerts a WHERE a.id=NEW.alert_id AND a.teacher_id=NEW.teacher_id AND a.student_id=NEW.student_id AND a.status='APPROVED') THEN
        RAISE EXCEPTION 'intervention requires an approved tenant-owned alert';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_intervention_alert BEFORE INSERT OR UPDATE OF teacher_id, student_id, alert_id
ON interventions FOR EACH ROW EXECUTE FUNCTION validate_intervention_alert();

CREATE TABLE intervention_reminders (
    id UUID PRIMARY KEY DEFAULT uuidv7(), teacher_id UUID NOT NULL, intervention_id UUID NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    finished_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_reminder_teacher FOREIGN KEY (teacher_id) REFERENCES teacher_profiles(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reminder_intervention FOREIGN KEY (intervention_id) REFERENCES interventions(id) ON DELETE RESTRICT,
    CONSTRAINT ck_reminder_status CHECK (status IN ('ACTIVE','COMPLETED','CANCELLED')),
    CONSTRAINT ck_reminder_finished CHECK ((status = 'ACTIVE' AND finished_at IS NULL) OR (status IN ('COMPLETED','CANCELLED') AND finished_at IS NOT NULL)),
    CONSTRAINT ck_reminder_time CHECK (updated_at >= created_at AND (finished_at IS NULL OR finished_at >= created_at))
);
-- History is append-only; only currently ACTIVE rows participate in uniqueness.
CREATE UNIQUE INDEX uq_active_reminder_per_intervention ON intervention_reminders(intervention_id) WHERE status='ACTIVE';

CREATE FUNCTION validate_reminder_intervention() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM interventions i WHERE i.id=NEW.intervention_id AND i.teacher_id=NEW.teacher_id) THEN
        RAISE EXCEPTION 'reminder requires a tenant-owned intervention';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_validate_reminder_intervention BEFORE INSERT OR UPDATE OF teacher_id, intervention_id
ON intervention_reminders FOR EACH ROW EXECUTE FUNCTION validate_reminder_intervention();

CREATE INDEX idx_engagement_alert_pending ON engagement_alerts(teacher_id, created_at, id) WHERE status='PENDING_REVIEW';
CREATE INDEX idx_intervention_alert ON interventions(teacher_id, alert_id, created_at);
CREATE INDEX idx_reminder_teacher_schedule ON intervention_reminders(teacher_id, status, scheduled_at);

ALTER TABLE engagement_alerts ENABLE ROW LEVEL SECURITY; ALTER TABLE engagement_alerts FORCE ROW LEVEL SECURITY;
ALTER TABLE interventions ENABLE ROW LEVEL SECURITY; ALTER TABLE interventions FORCE ROW LEVEL SECURITY;
ALTER TABLE intervention_reminders ENABLE ROW LEVEL SECURITY; ALTER TABLE intervention_reminders FORCE ROW LEVEL SECURITY;

CREATE POLICY engagement_alert_select ON engagement_alerts FOR SELECT USING (teacher_id=current_checkon_teacher_id());
CREATE POLICY engagement_alert_insert ON engagement_alerts FOR INSERT WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY engagement_alert_update ON engagement_alerts FOR UPDATE USING (teacher_id=current_checkon_teacher_id()) WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY engagement_alert_delete ON engagement_alerts FOR DELETE USING (teacher_id=current_checkon_teacher_id());
CREATE POLICY intervention_select ON interventions FOR SELECT USING (teacher_id=current_checkon_teacher_id());
CREATE POLICY intervention_insert ON interventions FOR INSERT WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY intervention_update ON interventions FOR UPDATE USING (teacher_id=current_checkon_teacher_id()) WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY intervention_delete ON interventions FOR DELETE USING (teacher_id=current_checkon_teacher_id());
CREATE POLICY reminder_select ON intervention_reminders FOR SELECT USING (teacher_id=current_checkon_teacher_id());
CREATE POLICY reminder_insert ON intervention_reminders FOR INSERT WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY reminder_update ON intervention_reminders FOR UPDATE USING (teacher_id=current_checkon_teacher_id()) WITH CHECK (teacher_id=current_checkon_teacher_id());
CREATE POLICY reminder_delete ON intervention_reminders FOR DELETE USING (teacher_id=current_checkon_teacher_id());
