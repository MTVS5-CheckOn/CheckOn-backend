-- §7 부록: 강사가 실제로 발송한 본문을 보관해, 나중에 AI 마지막 초안과의
-- 차이를 사람이 집계할 수 있게 한다. AI 계약 변경은 없다 — 백엔드가
-- "발송 완료 처리" 시점의 본문만 남기면 된다.
ALTER TABLE counsel_draft_jobs
    ADD COLUMN sent_text TEXT,
    ADD COLUMN sent_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_counsel_draft_job_sent_pair CHECK (
        (sent_text IS NULL AND sent_at IS NULL) OR (sent_text IS NOT NULL AND sent_at IS NOT NULL)
    );

COMMENT ON COLUMN counsel_draft_jobs.sent_text IS
    'The text the teacher actually sent through their own channel, recorded for AI-vs-sent diffing. Not used for AI training (contract §7).';
