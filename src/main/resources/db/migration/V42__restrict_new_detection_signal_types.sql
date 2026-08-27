-- Existing rows are append-only audit history and may contain legacy values.
-- NOT VALID preserves those rows while PostgreSQL enforces the contract for
-- every newly inserted or subsequently updated row.
ALTER TABLE detection_signal_results
    ADD CONSTRAINT ck_detection_signal_results_supported_signal_type
    CHECK (signal_type IN (
        'acc_drop',
        'submit_drop',
        'volume_gap',
        'hidden_risk',
        'return_care',
        'type_bias'
    )) NOT VALID;
