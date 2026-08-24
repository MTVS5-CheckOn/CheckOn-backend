ALTER TABLE problem_generation_outbox
    DROP CONSTRAINT uq_problem_generation_outbox_request_type,
    ADD COLUMN problem_execution_id UUID,
    ADD CONSTRAINT fk_problem_generation_outbox_execution
        FOREIGN KEY (problem_execution_id) REFERENCES problem_generation_executions (id) ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_problem_generation_outbox_parent_event
    ON problem_generation_outbox (problem_request_id, event_type)
    WHERE problem_execution_id IS NULL;
CREATE UNIQUE INDEX uq_problem_generation_outbox_child_event
    ON problem_generation_outbox (problem_execution_id, event_type)
    WHERE problem_execution_id IS NOT NULL;

ALTER TABLE problem_generation_executions
    ADD COLUMN result_payload JSONB,
    ADD COLUMN versions_payload JSONB;
