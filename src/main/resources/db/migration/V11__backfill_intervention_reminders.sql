-- Backfill only legacy OPEN interventions that have never had any Reminder.
-- Existing Reminder history and scheduled times are deliberately preserved.
INSERT INTO intervention_reminders (
    teacher_id, intervention_id, scheduled_at, status,
    finished_at, created_at, updated_at
)
SELECT i.teacher_id, i.id, i.created_at + INTERVAL '7 days', 'ACTIVE',
       NULL, statement_timestamp(), statement_timestamp()
FROM interventions i
WHERE i.status = 'OPEN'
  AND NOT EXISTS (
      SELECT 1 FROM intervention_reminders r WHERE r.intervention_id = i.id
  )
ON CONFLICT (intervention_id) WHERE status = 'ACTIVE' DO NOTHING;

CREATE INDEX idx_reminder_dashboard_candidates
    ON intervention_reminders (teacher_id, scheduled_at, intervention_id)
    WHERE status = 'ACTIVE';
