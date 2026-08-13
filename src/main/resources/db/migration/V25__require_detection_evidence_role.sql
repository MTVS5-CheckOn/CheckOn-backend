UPDATE detection_result_evidence
SET role = 'trigger'
WHERE role IS NULL;

ALTER TABLE detection_result_evidence
    ALTER COLUMN role SET NOT NULL;

