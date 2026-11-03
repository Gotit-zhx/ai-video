ALTER TABLE finance_sync_states
 ADD COLUMN priority_requested_at DATETIME(6) NULL,
 ADD COLUMN last_progress_at DATETIME(6) NULL;
