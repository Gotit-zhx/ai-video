ALTER TABLE automation_commands
    DROP CHECK ck_automation_commands_status;

ALTER TABLE automation_commands
    ADD CONSTRAINT ck_automation_commands_status
        CHECK (status IN (
            'QUEUED', 'RUNNING',
            'PAUSE_REQUESTED', 'INTERRUPT_REQUESTED', 'CANCEL_REQUESTED',
            'PAUSED', 'INTERRUPTED',
            'SUCCEEDED', 'FAILED', 'CANCELLED'
        ));
