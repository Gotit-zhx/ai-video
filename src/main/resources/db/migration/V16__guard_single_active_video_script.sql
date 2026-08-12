ALTER TABLE video_scripts
    ADD COLUMN active_owner_key VARCHAR(80)
        GENERATED ALWAYS AS (
            CASE
                WHEN status IN ('QUEUED', 'EXTRACTING_FRAMES', 'AI_ANALYZING', 'FAILED')
                THEN CONCAT(organization_id, ':', created_by)
                ELSE NULL
            END
        ) STORED,
    ADD UNIQUE KEY uk_video_scripts_active_owner (active_owner_key);
