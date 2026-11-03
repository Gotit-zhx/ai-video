-- A successful recent page or historical scan is not proof of a complete month.
-- Existing checkpoints are retained; workers certify each shop/channel after
-- the last page of its explicit previous-calendar-month scan succeeds.
ALTER TABLE finance_sync_states
    ADD COLUMN completed_month DATE NULL,
    ADD COLUMN month_completed_at DATETIME(6) NULL;
