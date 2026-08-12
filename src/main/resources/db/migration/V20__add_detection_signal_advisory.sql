ALTER TABLE detection_signal_results
    ADD COLUMN advisory BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN detection_signal_results.advisory IS
    'AI advisory signal retained for detail but excluded from alert and Todo candidates';
